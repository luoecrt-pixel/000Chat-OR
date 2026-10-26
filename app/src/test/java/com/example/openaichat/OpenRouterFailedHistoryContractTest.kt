package com.example.openaichat

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** A rejected HTTP request is a local error record, not an empty model turn to replay. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OpenRouterFailedHistoryContractTest {
    private lateinit var context: Context
    private val chat = "failed-history-contract"
    private val request = "failed-history-request"
    private val model = "anthropic/claude-haiku-5.5"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("response_notifications", false).commit()
    }

    @After fun cleanup() {
        OpenRouterResponseRuntime.cancel(request)
        awaitRuntimeExit()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }

    private fun awaitRuntimeExit() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (OpenRouterResponseRuntime.contains(request) && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse("The failed request must release its owner", OpenRouterResponseRuntime.contains(request))
    }

    private fun user(text: String) = JSONObject().put("role", "user").put("text", text)
    private fun snapshot(history: List<JSONObject>) =
        OpenRouterContextSnapshot.build(history, null, List(history.size) { emptyList() })

    @Test fun http400ReopenAndNextExplicitSendDoNotReplaySyntheticEmptyAssistant() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":400,"message":"Provider returned error","metadata":{"provider_name":"Anthropic","raw":"tools.1.custom.input_schema: input_schema does not support anyOf at the top level"}}}"""))
            val firstUser = user("Привет").put("provider", "openrouter").put("request_id", request)
            ChatDatabase(context).use { db ->
                db.upsertCurrentChat(chat, "Failed greeting", null, null, listOf(firstUser))
                db.setActiveResponse(chat, null, request, "", "in_progress", System.currentTimeMillis(),
                    "low", "low", false, 6, 0, false)
                assertTrue(db.startRequestAccounting(chat, request, "openrouter", model, "openrouter_api_key"))
            }
            val metadata = OkHttpClient()
            try {
                val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
                OpenRouterResponseRuntime.start(context, OpenRouterRuntimeRequest(
                    chatId = chat, requestId = request, model = model,
                    messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Привет")),
                    attachments = emptyList(), effort = "low", verbosity = "", maxTokens = null,
                    github = false, githubRepo = "", githubBranches = emptySet(), webSearch = false,
                    webFetch = false, parallel = false, cacheMode = "off", background = false,
                    startedAt = SystemClock.elapsedRealtime(), taskClass = "short", effortPref = "low",
                    autoSelected = false, promptChars = 6, apiKey = "synthetic-history-key", metadataClient = metadata,
                    pdfEngine = "native"), client)
                awaitRuntimeExit()
                assertEquals(1, server.requestCount)
                val history = ChatDatabase(context).use { db ->
                    val rows = db.getChat(chat)!!.getJSONArray("messages")
                    assertEquals(2, rows.length())
                    val failed = rows.getJSONObject(1)
                    assertEquals("failed", failed.getString("status"))
                    assertEquals("", failed.getString("text"))
                    assertTrue(failed.getString("error").contains("Provider returned error"))
                    assertNull(db.getActiveResponse(chat))
                    (0 until rows.length()).map { JsonProtocol.objectCopy(rows.getJSONObject(it)) }
                }
                val failedBefore = history[1].toString()
                val next = snapshot(history + user("Ответь ещё раз"))
                assertEquals("A rejected request must not invent a model turn", 2, next.length())
                assertEquals("user", next.getJSONObject(0).getString("role"))
                assertEquals("user", next.getJSONObject(1).getString("role"))
                assertEquals("Read-only replay must preserve the local error record", failedBefore, history[1].toString())
                server.takeRequest(2, TimeUnit.SECONDS)!!
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Привет после ошибки\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
                val nextClient = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
                try {
                    val result = nextClient.complete("synthetic-history-key", model, next, tools = OpenRouterAppToolCatalog.tools(),
                        maxTokens = null, reasoningEffort = "low", verbosity = null, parallelToolCalls = false,
                        cacheMode = "off", executeTool = { _, _ -> fail("No tool was requested"); "" })
                    assertEquals("Привет после ошибки", result.text)
                    val wire = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
                    assertEquals(2, wire.length())
                    assertFalse(wire.toString().contains("Provider returned error"))
                    assertEquals(2, server.requestCount)
                } finally { nextClient.close() }
            } finally {
                metadata.dispatcher.executorService.shutdownNow()
                metadata.connectionPool.evictAll()
            }
        }
    }

    @Test fun existingEmptyErrorTailIsSkippedWithoutMutatingStoredRecoveryEvidence() {
        val protocol = JSONObject().put("version", 1)
            .put("tail", JSONArray().put(JSONObject().put("role", "assistant").put("content", "")))
            .put("interrupted_assistant", JSONObject().put("role", "assistant").put("content", "")
                .put("reasoning_details", JSONArray().put(JSONObject().put("type", "reasoning.encrypted").put("data", "partial-opaque")))
                .put("replay_safe", false))
        for (status in listOf("failed", "disconnected")) {
            val failed = JSONObject().put("role", "assistant").put("text", "").put("status", status)
                .put("error", "Local diagnostic").put("openrouter_protocol", protocol)
            val before = failed.toString()
            val wire = snapshot(listOf(user("First"), failed, user("Next")))
            assertEquals(2, wire.length())
            assertEquals(before, failed.toString())
            assertFalse(wire.toString().contains("partial-opaque"))
        }
    }

    @Test fun staleEmptyErrorTailFallsBackToStoredTextWithoutChangingRecoveryEvidence() {
        val text = "Полученный текст 字🙂"
        for (status in listOf("failed", "disconnected")) {
            val protocol = JSONObject().put("version", 1)
                .put("tail", JSONArray().put(JSONObject().put("role", "assistant").put("content", "")))
                .put("tool_states", JSONObject())
                .put("interrupted_assistant", JSONObject().put("role", "assistant").put("content", text)
                    .put("reasoning_details", JSONArray().put(JSONObject().put("type", "reasoning.encrypted")
                        .put("data", "unfinished-local-evidence"))).put("replay_safe", false))
            val failed = JSONObject().put("role", "assistant").put("text", text).put("status", status)
                .put("openrouter_protocol", protocol)
            val before = failed.toString()
            val wire = snapshot(listOf(user("First"), failed, user("Next")))
            assertEquals("Stored text must survive a stale synthetic protocol placeholder", 3, wire.length())
            assertEquals("assistant", wire.getJSONObject(1).getString("role"))
            assertEquals(text, wire.getJSONObject(1).getString("content"))
            assertFalse(wire.toString().contains("unfinished-local-evidence"))
            assertEquals("Read-only repair must preserve raw recovery evidence", before, failed.toString())
        }
    }

    @Test fun unfamiliarOrSignedEmptyBlocksAndUnknownToolEvidenceAreNotDiscarded() {
        val signed = JSONObject().put("role", "assistant").put("content", "")
            .put("reasoning_details", JSONArray().put(JSONObject().put("type", "reasoning.encrypted").put("data", "signed-data")))
        val unfamiliar = JSONObject().put("role", "assistant").put("content", "").put("vendor_field", "opaque")
        val plain = JSONObject().put("role", "assistant").put("content", "")
        for ((tail, states) in listOf(
            signed to null,
            unfamiliar to null,
            plain to JSONObject().put("unknown-call", JSONObject().put("state", "outcome_unknown"))
        )) {
            val protocol = JSONObject().put("version", 1).put("tail", JSONArray().put(tail))
            states?.let { protocol.put("tool_states", it) }
            val failed = JSONObject().put("role", "assistant").put("text", "").put("status", "failed")
                .put("openrouter_protocol", protocol)
            val before = failed.toString()
            val wire = snapshot(listOf(user("First"), failed, user("Next")))
            assertEquals("Only the known synthetic placeholder may be skipped", 3, wire.length())
            assertEquals(before, failed.toString())
            if (tail.has("reasoning_details")) assertEquals("signed-data", wire.getJSONObject(1)
                .getJSONArray("reasoning_details").getJSONObject(0).getString("data"))
            if (tail.has("vendor_field")) assertEquals("opaque", wire.getJSONObject(1).getString("vendor_field"))
        }
    }

    @Test fun toolOnlyAssistantAndItsExactSignedReasoningRemainInReplay() {
        val call = JSONObject().put("id", "already-completed").put("type", "function")
            .put("function", JSONObject().put("name", "file_info").put("arguments", "{}"))
        val assistant = JSONObject().put("role", "assistant").put("content", "")
            .put("tool_calls", JSONArray().put(call)).put("reasoning_details", JSONArray()
                .put(JSONObject().put("type", "reasoning.encrypted").put("data", "exact-signed-tool-data")))
        val result = JSONObject().put("role", "tool").put("tool_call_id", "already-completed")
            .put("content", "{\"ok\":true,\"size_bytes\":42}")
        val protocol = JSONObject().put("version", 1).put("tail", JSONArray().put(assistant).put(result))
        val stored = JSONObject().put("role", "assistant").put("text", "").put("status", "failed")
            .put("openrouter_protocol", protocol)
        val wire = snapshot(listOf(user("Read"), stored, user("Next")))
        assertEquals(4, wire.length())
        assertEquals("already-completed", wire.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("exact-signed-tool-data", wire.getJSONObject(1).getJSONArray("reasoning_details").getJSONObject(0).getString("data"))
        assertEquals(result.toString(), JSONObject(wire.getJSONObject(2).toString()).apply {
            remove("_astra_binding_sha256"); remove("_astra_reset_reasoning"); remove("_astra_prefix_valid")
        }.toString())
    }
}
