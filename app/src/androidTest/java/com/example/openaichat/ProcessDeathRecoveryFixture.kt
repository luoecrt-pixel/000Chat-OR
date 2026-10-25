package com.example.openaichat

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Host-driven real process death after local HTTPS tool round and a persisted SSE prefix. */
@RunWith(AndroidJUnit4::class)
class ProcessDeathRecoveryFixture {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val prefs get() = context.getSharedPreferences("process_recovery_fixture", 0)
    private val scenario get() = arguments.getString("recoveryScenario").orEmpty().also {
        require(it in setOf("partial", "stopped", "replaced", "intent_gap", "deleted_intent"))
    }
    private val chatId get() = "openrouter-process-fixture-$scenario"
    private val providerCallId = "fixture-create"
    private val runToken get() = prefs.getString("run_token", "unseeded")!!
    private val requestId get() = "request-process-fixture-$runToken"
    private val replacementId get() = "request-replacement-fixture-$runToken"
    private val model = "anthropic/claude-sonnet-5.5"
    private val syntheticKey = "sk-or-v1-synthetic-process-fixture"
    private val prefix = "Полученный до остановки процесса текст 😀"
    private val unfinishedReasoning = "unfinished-process-reasoning-data"
    private val original = "Original tool result\nUnicode 😀\n"
    private val appPreferenceKeys = listOf("current_chat", "provider", "model", "response_notifications")
    private fun generationId(stage: String) = "gen-fixture-$runToken-$stage"

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(20)
        }
        assertTrue(message, condition())
    }

    private fun seedDiagnostics(server: MockWebServer): String {
        fun safeError(raw: String): String = AstraDiagnostics(context).redactDisplayText(raw)
            .replace(Regex("/(?:data|storage|sdcard|mnt|workspace)/[^\\s,;)\"]+"), "[local-path]").take(512)
        val state = JSONObject().put("http_count", server.requestCount)
            .put("runtime_present", OpenRouterResponseRuntime.contains(requestId))
        try {
            ChatDatabase(context).use { db ->
                db.getActiveResponse(chatId)?.let { active ->
                    state.put("active_owner_matches", active.optString("request_id") == requestId)
                        .put("active_status", active.optString("status"))
                        .put("active_text_chars", active.optString("text").length)
                } ?: state.put("active_owner", JSONObject.NULL)
                val messages = db.getChat(chatId)?.optJSONArray("messages") ?: JSONArray()
                val terminal = (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }
                    .lastOrNull { it.optString("role") == "assistant" && it.optString("request_id") == requestId }
                terminal?.let {
                    state.put("assistant_status", it.optString("status"))
                        .put("assistant_text_chars", it.optString("text").length)
                        .put("assistant_error", safeError(it.optString("error")))
                }
                state.put("rounds", JSONArray().apply {
                    db.listOpenRouterRounds(chatId, requestId).forEach { round -> put(JSONObject()
                        .put("index", round.optInt("round_index"))
                        .put("generation_id", round.optString("generation_id"))
                        .put("cost_kind", round.optString("cost_kind"))
                        .put("has_usage", round.has("usage_snapshot"))) }
                })
                state.put("request_status", db.listRequestAccounting(chatId, setOf(requestId))
                    .singleOrNull()?.optString("local_status") ?: JSONObject.NULL)
                val intent = db.getLocalArtifactIntent(chatId, requestId, providerCallId)
                state.put("intent_state", intent?.optString("state") ?: JSONObject.NULL)
                state.put("intent_tombstoned", intent?.optBoolean("tombstoned") ?: JSONObject.NULL)
            }
        } catch (error: Exception) {
            state.put("diagnostic_db_error", error.javaClass.simpleName + ": " + safeError(error.message.orEmpty()))
        }
        return state.toString()
    }

    private fun awaitPartialPrefix(server: MockWebServer) {
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ChatDatabase(context).use { it.getActiveResponse(chatId)?.optString("text") == prefix }) return
            if (!OpenRouterResponseRuntime.contains(requestId))
                throw AssertionError("Runtime exited before persisting its second-round prefix: " + seedDiagnostics(server))
            SystemClock.sleep(20)
        }
        throw AssertionError("A real second HTTP round must persist the partial prefix: " + seedDiagnostics(server))
    }

    private fun savePreferences() {
        val app = context.getSharedPreferences("app", 0)
        val saved = JSONObject()
        for (key in appPreferenceKeys) saved.put(key, app.all[key] ?: JSONObject.NULL)
        val secure = context.getSharedPreferences("secure", 0)
        assertTrue(prefs.edit().putString("saved_app", saved.toString())
            .putString("saved_router_key", secure.getString("openrouter_key", null)).commit())
        // Back up only encrypted bytes; the fixture never reads or uses a prior provider credential.
        // Keep a synthetic key available across death so a missing-key guard cannot mask auto-resend.
        assertTrue(SecureStore(context).setOpenRouterApiKey(syntheticKey))
        assertTrue(app.edit().putString("provider", "openrouter").putString("model", model)
            .putString("current_chat", chatId).putBoolean("response_notifications", false).commit())
    }

    @Test fun seedAndWaitForHostProcessKill() {
        assumeTrue(arguments.getString("recoveryStage") == "seed")
        // Intent tombstones are deliberately permanent; each host run receives a fresh request identity.
        assertTrue(prefs.edit().putString("run_token", UUID.randomUUID().toString()).commit())
        savePreferences()
        ChatDatabase(context).use { db ->
            db.deleteChat(chatId)
            db.upsertCurrentChat(chatId, "Process recovery", null, null, listOf(JSONObject()
                .put("role", "user").put("text", "Create a local result").put("provider", "openrouter")
                .put("request_id", requestId)))
            db.setActiveResponse(chatId, null, requestId, "", "in_progress", System.currentTimeMillis(),
                "high", "high", false, 20, 0, false)
            assertTrue(db.startRequestAccounting(chatId, requestId, "openrouter", model, "openrouter_api_key"))
            if (scenario == "intent_gap") db.writableDatabase.execSQL("""
                CREATE TRIGGER fixture_reject_intent_completion BEFORE UPDATE OF state ON local_artifact_intents
                WHEN NEW.chat_id='$chatId' AND NEW.state='completed'
                BEGIN SELECT RAISE(ABORT, 'fixture intent registration gap'); END
            """.trimIndent())
        }
        if (scenario == "deleted_intent") {
            seedDeletedIntentAndWaitForHostKill()
            return
        }
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .readTimeout(0, TimeUnit.SECONDS).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val call = JSONObject().put("index", 0).put("id", providerCallId).put("type", "function")
            .put("function", JSONObject().put("name", "create_local_text_file").put("arguments", JSONObject()
                .put("filename", "process-result.txt").put("content", original).toString()))
        val toolFrame = JSONObject().put("id", generationId("tool")).put("model", model)
            .put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()
                .put("reasoning_details", JSONArray().put(JSONObject().put("type", "reasoning.encrypted")
                    .put("data", "synthetic-signed-process-data").put("index", 0)))
                .put("tool_calls", JSONArray().put(call))).put("finish_reason", "tool_calls")))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: $toolFrame\n\ndata: [DONE]\n\n"))
        val partialFrame = JSONObject().put("id", generationId("partial")).put("model", model)
            .put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", prefix)
                .put("reasoning_details", JSONArray().put(JSONObject().put("type", "reasoning.encrypted")
                    .put("index", 0).put("data", unfinishedReasoning))))))
        val firstData = "data: $partialFrame"
        val padding = 510 - firstData.toByteArray(Charsets.UTF_8).size
        require(padding >= 0)
        val firstChunk = firstData + " ".repeat(padding) + "\n\n"
        if (scenario == "intent_gap") server.enqueue(MockResponse().setResponseCode(599)
            .setBody("Unexpected continuation after failed artifact registration"))
        else server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(firstChunk + "data: [DONE]\n\n").throttleBody(512, 30, TimeUnit.SECONDS))
        val client = OpenRouterChatClient(server.url("/api/v1").toString(), http, reconcileGenerations = false)
        OpenRouterResponseRuntime.start(context, OpenRouterRuntimeRequest(
            chatId = chatId, requestId = requestId, model = model,
            messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Create a local result")),
            attachments = emptyList(), effort = "high", verbosity = "", maxTokens = null,
            github = false, githubRepo = "", githubBranches = emptySet(), webSearch = false,
            webFetch = false, parallel = false, cacheMode = "off", background = false,
            startedAt = SystemClock.elapsedRealtime(), taskClass = "general", effortPref = "high",
            autoSelected = false, promptChars = 20, apiKey = syntheticKey, metadataClient = http,
            pdfEngine = "native"), client)
        if (scenario == "intent_gap") {
            await("Failed SQL registration must release the runtime without another POST") {
                !OpenRouterResponseRuntime.contains(requestId)
            }
            assertEquals("Unknown tool outcome must block the continuation POST", 1, server.requestCount)
            ChatDatabase(context).use { db ->
                val intent = db.getLocalArtifactIntent(chatId, requestId, providerCallId)!!
                assertEquals("outcome_unknown", intent.getString("state"))
                assertFalse(intent.has("outcome"))
                assertEquals(0, artifactIndexCount(db))
                val answer = storedAssistant(db)
                val protocol = answer.getJSONObject("openrouter_protocol")
                assertEquals("outcome_unknown", protocol.getJSONObject("tool_states").getJSONObject(providerCallId).getString("state"))
                val tail = protocol.getJSONArray("tail")
                assertEquals(1, tail.length())
                assertEquals("assistant", tail.getJSONObject(0).getString("role"))
                val path = intent.getJSONObject("plan").getJSONObject("publication").getString("target_path")
                saveArtifactEvidence(path, tail)
                db.writableDatabase.execSQL("DROP TRIGGER fixture_reject_intent_completion")
            }
            waitForHostKill()
            return
        }
        awaitPartialPrefix(server)
        assertEquals(2, server.requestCount)
        ChatDatabase(context).use { db ->
            val checkpoint = db.getOpenRouterCheckpoint(chatId, requestId)!!
            assertUnfinishedReasoningRetainedOutsideReplay(checkpoint)
            val tail = checkpoint.getJSONArray("tail")
            assertEquals("synthetic-signed-process-data", tail.getJSONObject(0)
                .getJSONArray("reasoning_details").getJSONObject(0).getString("data"))
            val file = db.listLocalFiles(chatId).single()
            val path = file.getString("path")
            assertEquals(original, File(path).readText())
            saveArtifactEvidence(path, tail)
            if (scenario != "partial") {
                assertTrue(OpenRouterResponseRuntime.stop(context, chatId, requestId, false))
                OpenRouterResponseRuntime.cancel(requestId)
                if (scenario == "replaced") {
                    db.setActiveResponse(chatId, null, replacementId, "", "in_progress", System.currentTimeMillis(),
                        "high", "high", false, 0, 0, false)
                    assertTrue(db.startRequestAccounting(chatId, replacementId, "openrouter", model, "openrouter_api_key"))
                    assertTrue(db.checkpointOpenRouterResponse(chatId, replacementId, "Replacement prefix", 0))
                }
            }
        }
        if (scenario != "partial") await("Cancelled runtime must release the old owner") {
            !OpenRouterResponseRuntime.contains(requestId)
        }
        waitForHostKill()
    }

    private fun saveArtifactEvidence(path: String, tail: JSONArray? = null) {
        val file = File(path)
        assertEquals(original, file.readText())
        val edit = prefs.edit().putString("result_path", path)
            .putString("result_sha256", LocalUtf8FilePatch.sha256(file)).putLong("result_mtime", file.lastModified())
        tail?.let { edit.putString("tail", it.toString()) }
        assertTrue(edit.commit())
    }

    private fun storedAssistant(db: ChatDatabase): JSONObject {
        val messages = db.getChat(chatId)!!.getJSONArray("messages")
        return (0 until messages.length()).map { messages.getJSONObject(it) }
            .single { it.optString("role") == "assistant" && it.optString("request_id") == requestId }
    }

    private fun artifactIndexCount(db: ChatDatabase): Int = db.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM local_artifacts WHERE chat_id=? AND request_id=?", arrayOf(chatId, requestId)
    ).use { it.moveToFirst(); it.getInt(0) }

    private fun seedDeletedIntentAndWaitForHostKill() {
        val executor = LocalArtifactToolExecutor(context)
        val args = JSONObject().put("filename", "process-result.txt").put("content", original)
        executor.prepareTextFile(args, "$chatId\u0000$requestId\u0000$providerCallId").use { plan ->
            ChatDatabase(context).use { db ->
                val record = db.beginLocalArtifactIntentIfActive(chatId, requestId, providerCallId,
                    "create_local_text_file", StreamingJsonRequestBody.sha256(args), plan.snapshot)!!
                assertTrue(record.getBoolean("_inserted"))
                db.upsertCurrentChat("$chatId-survivor", "Fixture survivor", null, null, emptyList())
                db.deleteChat(chatId)
                // Model the already-authorized writer crossing Delete after its last ownership check.
                // Production Plan and DB APIs execute normally; no production test hook is installed.
                val outcome = plan.publish()
                val completion = db.completeLocalArtifactIntent(chatId, requestId, providerCallId,
                    "create_local_text_file", record.getString("arguments_sha256"), record.getString("plan_sha256"), outcome)!!
                assertTrue(completion.getBoolean("tombstoned"))
                assertFalse(completion.has("outcome"))
                assertNull(db.getChat(chatId))
                assertEquals(0, artifactIndexCount(db))
                saveArtifactEvidence(outcome.getString("path"))
                assertTrue(context.getSharedPreferences("app", 0).edit().putString("current_chat", "$chatId-survivor").commit())
            }
        }
        waitForHostKill()
    }

    private fun waitForHostKill() {
        assertTrue(prefs.edit().putInt("seed_pid", Process.myPid()).putString("scenario", scenario).commit())
        instrumentation.sendStatus(2, Bundle().apply {
            putString("stream", "PROCESS_RECOVERY_SEEDED scenario=$scenario pid=${Process.myPid()}\n")
        })
        CountDownLatch(1).await(180, TimeUnit.SECONDS)
        fail("Host did not terminate the live seeded process")
    }

    @Test fun verifyLocalRecoveryInNewProcess() {
        assumeTrue(arguments.getString("recoveryStage") == "verify")
        assertEquals(scenario, prefs.getString("scenario", ""))
        assertNotEquals(prefs.getInt("seed_pid", -1), Process.myPid())
        assertTrue("Pre-create interception requires the CI emulator's API 29+", Build.VERSION.SDK_INT >= 29)
        assertEquals(syntheticKey, SecureStore(context).getOpenRouterApiKey())
        assertFalse(OpenRouterResponseRuntime.contains(requestId))
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val explicitReplayAllowed = AtomicBoolean(false)
        val server = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    if (!explicitReplayAllowed.get()) return MockResponse().setResponseCode(599).setBody("Unexpected recovery HTTP")
                    val frame = JSONObject().put("id", generationId("explicit-replay")).put("model", model)
                        .put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", "Recovered file verified"))
                            .put("finish_reason", "stop")))
                    return MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody("data: $frame\n\ndata: [DONE]\n\n")
                }
            }
            start()
        }
        val endpoint = server.url("/api/v1").toString()
        val injectedActivities = AtomicInteger()
        val application = context.applicationContext as Application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is MainActivity) return
                val factory: () -> OpenRouterChatClient = {
                    // Each Activity owns a fresh pool/dispatcher; closing the first one cannot
                    // prevent a hypothetical second restart from reaching the tripwire server.
                    val http = OkHttpClient.Builder()
                        .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                        .callTimeout(5, TimeUnit.SECONDS).build()
                    OpenRouterChatClient(endpoint, http, reconcileGenerations = false)
                }
                MainActivity::class.java.getDeclaredField("openRouterClientFactory")
                    .apply { isAccessible = true }.set(activity, factory)
                injectedActivities.incrementAndGet()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            repeat(2) {
                ActivityScenario.launch(MainActivity::class.java).use { activityScenario ->
                    activityScenario.onActivity {
                        assertEquals(syntheticKey, SecureStore(context).getOpenRouterApiKey())
                        assertFalse(OpenRouterResponseRuntime.contains(requestId))
                        assertFalse(OpenRouterResponseRuntime.contains(replacementId))
                    }
                    if (scenario == "intent_gap") await("Startup must reconcile the exact published tool result locally") {
                        ChatDatabase(context).use { db ->
                            db.getLocalArtifactIntent(chatId, requestId, providerCallId)?.optString("state") == "completed" &&
                                storedAssistant(db).optJSONObject("openrouter_protocol")?.optJSONObject("tool_states")
                                    ?.optJSONObject(providerCallId)?.optString("state") == "completed"
                        }
                    }
                }
                assertEquals("Restart recovery must not send HTTP with the available synthetic key", 0, server.requestCount)
            }
            assertEquals("Both production Activity restarts must receive the pre-create tripwire", 2, injectedActivities.get())
            if (scenario == "intent_gap") {
                // Recovery above sent no HTTP. This is a separate, explicit synthetic next-turn wire check.
                explicitReplayAllowed.set(true)
                verifyRecoveredIntentAndExplicitReplay(server, clientTls)
            }
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            server.shutdown()
        }
        ChatDatabase(context).use { db ->
            assertNull(db.getActiveResponse(chatId))
            if (scenario == "deleted_intent") {
                assertNull("Startup must not resurrect the deleted chat", db.getChat(chatId))
                val intent = db.getLocalArtifactIntent(chatId, requestId, providerCallId)!!
                assertTrue(intent.getBoolean("tombstoned"))
                assertFalse(intent.has("outcome"))
                assertEquals(0, artifactIndexCount(db))
                verifyArtifactEvidence()
                return@use
            }
            if (scenario == "intent_gap") {
                assertEquals(1, artifactIndexCount(db))
                assertEquals(1, recordedRoundCount(db))
                assertEquals(2, db.getChat(chatId)!!.getJSONArray("messages").length())
                verifyArtifactEvidence()
                return@use
            }
            val messages = db.getChat(chatId)!!.getJSONArray("messages")
            assertEquals(if (scenario == "stopped") 1 else 2, messages.length())
            if (scenario == "partial") {
                val answer = messages.getJSONObject(1)
                assertEquals(prefix, answer.getString("text"))
                assertEquals("disconnected", answer.getString("status"))
                assertEquals("openrouter", answer.getString("provider"))
                val originalTail = JSONArray(prefs.getString("tail", "[]"))
                val recoveredProtocol = answer.getJSONObject("openrouter_protocol")
                assertUnfinishedReasoningRetainedOutsideReplay(recoveredProtocol)
                val recoveredTail = recoveredProtocol.getJSONArray("tail")
                for (index in 0 until originalTail.length())
                    assertEquals(originalTail.get(index).toString(), recoveredTail.get(index).toString())
                val history = (0 until messages.length()).map { messages.getJSONObject(it) }
                val wire = OpenRouterContextSnapshot.build(history, null, List(history.size) { emptyList() })
                assertFalse("An unfinished signed round must not be replayed", wire.toString().contains(unfinishedReasoning))
            } else {
                val archive = db.getRequestRecoveryArchive(chatId, requestId)!!
                assertEquals(prefix, archive.getString("text"))
                assertUnfinishedReasoningRetainedOutsideReplay(archive.getJSONObject("openrouter_protocol"))
                if (scenario == "replaced") {
                    assertEquals(replacementId, messages.getJSONObject(1).getString("request_id"))
                    assertEquals("Replacement prefix", messages.getJSONObject(1).getString("text"))
                }
            }
            // Sealing may update status/cost, but process restart must never create another HTTP round.
            assertEquals(2, recordedRoundCount(db))
            verifyArtifactEvidence()
        }
    }

    private fun recordedRoundCount(db: ChatDatabase): Int = db.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM openrouter_request_rounds WHERE chat_id=? AND local_request_id=?",
        arrayOf(chatId, requestId)).use { it.moveToFirst(); it.getInt(0) }

    private fun assertUnfinishedReasoningRetainedOutsideReplay(protocol: JSONObject) {
        val interrupted = protocol.getJSONObject("interrupted_assistant")
        assertEquals(prefix, interrupted.getString("content"))
        assertFalse(interrupted.getBoolean("replay_safe"))
        assertEquals(unfinishedReasoning, interrupted.getJSONArray("reasoning_details").getJSONObject(0).getString("data"))
        assertEquals(unfinishedReasoning, interrupted.getJSONArray("reasoning_delta_fragments").getJSONObject(0).getString("data"))
        assertFalse(protocol.getJSONArray("tail").toString().contains(unfinishedReasoning))
    }

    private fun verifyArtifactEvidence() {
        val file = File(prefs.getString("result_path", "")!!)
        assertEquals(original, file.readText())
        assertEquals(prefs.getString("result_sha256", ""), LocalUtf8FilePatch.sha256(file))
        assertEquals("Recovery must not rewrite target content", prefs.getLong("result_mtime", -1L), file.lastModified())
    }

    private fun verifyRecoveredIntentAndExplicitReplay(server: MockWebServer, tls: HandshakeCertificates) {
        val history = ChatDatabase(context).use { db ->
            val intent = db.getLocalArtifactIntent(chatId, requestId, providerCallId)!!
            assertFalse(intent.getBoolean("tombstoned"))
            assertEquals("completed", intent.getString("state"))
            val protocol = storedAssistant(db).getJSONObject("openrouter_protocol")
            val tail = protocol.getJSONArray("tail")
            assertEquals(2, tail.length())
            assertEquals("Signed assistant must stay byte-for-byte unchanged", JSONArray(prefs.getString("tail", "[]"))
                .getJSONObject(0).toString(), tail.getJSONObject(0).toString())
            val result = tail.getJSONObject(1)
            assertEquals("tool", result.getString("role"))
            assertEquals(providerCallId, result.getString("tool_call_id"))
            val output = JSONObject(result.getString("content"))
            assertTrue(output.getBoolean("ok"))
            assertFalse(output.optBoolean("cancelled"))
            assertEquals(intent.getJSONObject("outcome").getString("file_id"), output.getString("file_id"))
            assertFalse("Private device path must stay outside replay", output.has("path"))
            assertEquals("completed", protocol.getJSONObject("tool_states").getJSONObject(providerCallId).getString("state"))
            assertEquals(1, recordedRoundCount(db))
            val rows = db.getChat(chatId)!!.getJSONArray("messages")
            (0 until rows.length()).map { rows.getJSONObject(it) }
        }
        val messages = OpenRouterContextSnapshot.build(history, null, List(history.size) { emptyList() })
            .put(JSONObject().put("role", "user").put("content", "Confirm the recovered local result"))
        val http = OkHttpClient.Builder().sslSocketFactory(tls.sslSocketFactory(), tls.trustManager)
            .callTimeout(10, TimeUnit.SECONDS).build()
        val client = OpenRouterChatClient(server.url("/api/v1").toString(), http, reconcileGenerations = false)
        val executed = AtomicInteger()
        try {
            val response = client.complete(syntheticKey, model, messages, sessionId = chatId,
                tools = OpenRouterAppToolCatalog.tools(), maxTokens = null, reasoningEffort = "high", verbosity = "",
                parallelToolCalls = false, cacheMode = "off", executeTool = { _, _ ->
                    executed.incrementAndGet(); fail("Replay must not execute the previous file tool"); ""
                })
            assertEquals("Recovered file verified", response.text)
            assertEquals(0, executed.get())
            assertEquals("Only the explicitly requested next turn may POST", 1, server.requestCount)
            val wire = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
            val results = (0 until wire.length()).map { wire.getJSONObject(it) }
                .filter { it.optString("role") == "tool" }
            assertEquals(1, results.size)
            assertEquals(providerCallId, results.single().getString("tool_call_id"))
            assertTrue(JSONObject(results.single().getString("content")).getBoolean("ok"))
            val assistant = (0 until wire.length()).map { wire.getJSONObject(it) }
                .single { it.optJSONArray("tool_calls") != null }
            assertEquals("synthetic-signed-process-data", assistant.getJSONArray("reasoning_details")
                .getJSONObject(0).getString("data"))
        } finally { client.close() }
        verifyArtifactEvidence()
    }

    @Test fun cleanupProcessRecoveryFixture() {
        assumeTrue(arguments.getString("recoveryStage") == "cleanup")
        OpenRouterResponseRuntime.cancel(requestId)
        val paths = ChatDatabase(context).use { db ->
            db.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fixture_reject_intent_completion")
            val owned = db.listLocalFiles(chatId).mapNotNull { JsonProtocol.nonBlank(it, "path") }
            db.deleteChat(chatId)
            db.deleteChat("$chatId-survivor")
            owned + listOfNotNull(prefs.getString("result_path", null))
        }
        paths.forEach { File(it).delete() }
        val app = context.getSharedPreferences("app", 0).edit()
        val saved = JSONObject(prefs.getString("saved_app", "{}")!!)
        for (key in appPreferenceKeys) when (val value = saved.opt(key)) {
            null, JSONObject.NULL -> app.remove(key)
            is Boolean -> app.putBoolean(key, value)
            else -> app.putString(key, value.toString())
        }
        assertTrue(app.commit())
        assertTrue(context.getSharedPreferences("secure", 0).edit()
            .putString("openrouter_key", prefs.getString("saved_router_key", null)).commit())
        assertTrue(prefs.edit().clear().commit())
    }
}
