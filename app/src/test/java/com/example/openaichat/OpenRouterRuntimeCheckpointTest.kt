package com.example.openaichat

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Real SSE -> runtime -> SQLite -> Activity adapter, with the stream still open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class OpenRouterRuntimeCheckpointTest {
    private lateinit var context: Context
    private lateinit var metadataClient: OkHttpClient
    private lateinit var server: MockWebServer
    private var controller: ActivityController<MainActivity>? = null
    private val chat = "runtime-checkpoint"
    private val requestId = "request-checkpoint"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("response_notifications", false).putString("provider", "openai")
            .putString("model", "gpt-6-astra").putString("current_chat", chat).commit()
        context.getSharedPreferences("secure", Context.MODE_PRIVATE).edit().clear().commit()
        metadataClient = OkHttpClient()
        server = MockWebServer().apply { start() }
        ChatDatabase(context).use { it.ensureChat(chat, "Checkpoint") }
    }

    @After fun teardown() {
        OpenRouterResponseRuntime.cancel(requestId)
        await("Runtime cleanup") { !OpenRouterResponseRuntime.contains(requestId) }
        controller?.pause()?.stop()?.destroy()
        controller = null
        shadowOf(Looper.getMainLooper()).idle()
        server.shutdown()
        metadataClient.dispatcher.executorService.shutdownNow()
        metadataClient.connectionPool.evictAll()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }

    private fun started(id: String = requestId, provider: String = "openrouter") = ChatDatabase(context).use { db ->
        db.setActiveResponse(chat, null, id, "", "in_progress", System.currentTimeMillis(),
            "high", "high", false, 0, 0, false)
        assertTrue(db.startRequestAccounting(chat, id, provider, "test-model", "${provider}_api_key"))
    }

    private fun runtimeRequest() = OpenRouterRuntimeRequest(
        chatId = chat, requestId = requestId, model = "test-model",
        messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Continue")),
        attachments = emptyList(), effort = "high", verbosity = "", maxTokens = null,
        github = false, githubRepo = "", githubBranches = emptySet(), webSearch = false,
        webFetch = false, parallel = false, cacheMode = "off", background = false,
        startedAt = SystemClock.elapsedRealtime(), taskClass = "general", effortPref = "high",
        autoSelected = false, promptChars = 8, apiKey = "mock-key", metadataClient = metadataClient,
        pdfEngine = "auto"
    )

    private fun frame(text: String, terminal: Boolean = false): String {
        val choice = JSONObject().put("delta", JSONObject().put("content", text))
        if (terminal) choice.put("finish_reason", "stop")
        val payload = JSONObject().put("id", "gen-checkpoint").put("model", "test-model")
            .put("choices", JSONArray().put(choice))
        return "data: $payload".padEnd(510, ' ') + "\n\n"
    }

    private fun startDelayedStream() {
        // Equal-sized complete SSE frames make every prefix observable before
        // the next frame. No fake callbacks or in-memory replacement database.
        val body = frame("prefix ") + frame("continues ") + frame("done", terminal = true) + "data: [DONE]\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(body).throttleBody(512, 1, TimeUnit.SECONDS))
        val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
        OpenRouterResponseRuntime.start(context, runtimeRequest(), client)
    }

    private fun active() = ChatDatabase(context).use { it.getActiveResponse(chat) }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(10)
        }
        assertTrue(message, predicate())
    }

    @Suppress("UNCHECKED_CAST")
    private fun adapter(activity: MainActivity): ChatMessageAdapter = MainActivity::class.java
        .getDeclaredField("messageAdapter").apply { isAccessible = true }.get(activity) as ChatMessageAdapter

    @Test fun delayedSsePersistsAndDisplaysGrowingPrefixesBeforeTerminalCommit() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().also { controller = it }.get()
        started()
        startDelayedStream()
        await("First SSE prefix must reach SQLite while the response is open") { active()?.optString("text") == "prefix " }
        assertTrue(OpenRouterResponseRuntime.contains(requestId))
        await("Activity must render the committed first prefix") { adapter(activity).snapshot().lastOrNull()?.text == "prefix " }
        val firstCursor = active()!!.getLong("last_sequence_number")
        ShadowSystemClock.advanceBy(Duration.ofMillis(600))
        await("Second delayed SSE prefix must be checkpointed before terminal") { active()?.optString("text") == "prefix continues " }
        assertTrue(OpenRouterResponseRuntime.contains(requestId))
        assertTrue(active()!!.getLong("last_sequence_number") > firstCursor)
        await("Activity must render the growing prefix before completion") {
            adapter(activity).snapshot().lastOrNull()?.text == "prefix continues "
        }
        ChatDatabase(context).use { assertEquals(0, it.getChat(chat)!!.getJSONArray("messages").length()) }
        await("Terminal runtime commit") { !OpenRouterResponseRuntime.contains(requestId) }
        assertNull(active())
        await("Activity must replace the prefix with the final answer") {
            adapter(activity).snapshot().lastOrNull()?.text == "prefix continues done"
        }
        ChatDatabase(context).use { db ->
            val messages = db.getChat(chat)!!.getJSONArray("messages")
            assertEquals(1, messages.length())
            assertEquals("completed", messages.getJSONObject(0).getString("status"))
            assertEquals("prefix continues done", messages.getJSONObject(0).getString("text"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun replacedRequestRejectsDelayedOldStreamAndItsTerminalCommit() {
        started()
        startDelayedStream()
        await("Old request prefix") { active()?.optString("text") == "prefix " }
        started("replacement-request")
        ChatDatabase(context).use { db ->
            assertTrue(db.checkpointOpenRouterResponse(chat, "replacement-request", "replacement", 0L))
            assertFalse(db.checkpointOpenRouterResponse(chat, requestId, "late old prefix", 100L))
        }
        ShadowSystemClock.advanceBy(Duration.ofMillis(600))
        await("Replaced runtime must finish without claiming its successor") { !OpenRouterResponseRuntime.contains(requestId) }
        val replacement = active()!!
        assertEquals("replacement-request", replacement.getString("request_id"))
        assertEquals("replacement", replacement.getString("text"))
        assertEquals(0L, replacement.getLong("last_sequence_number"))
        assertEquals("in_progress", replacement.getString("status"))
        ChatDatabase(context).use { assertEquals(0, it.getChat(chat)!!.getJSONArray("messages").length()) }
        assertEquals(1, server.requestCount)
    }

    @Test fun stopArchivesPersistedPrefixAndLateStreamCannotRestoreVisibleAnswer() {
        started()
        startDelayedStream()
        await("Prefix before Stop") { active()?.optString("text") == "prefix " }
        ChatDatabase(context).use { db ->
            assertTrue(db.stopActiveRequestOnce(chat, requestId))
            assertFalse(db.checkpointOpenRouterResponse(chat, requestId, "late stopped prefix", 100L))
            assertEquals("prefix ", db.getRequestRecoveryArchive(chat, requestId)!!.getString("text"))
        }
        OpenRouterResponseRuntime.cancel(requestId, "Остановлено пользователем")
        await("Stopped runtime cleanup") { !OpenRouterResponseRuntime.contains(requestId) }
        assertNull(active())
        ChatDatabase(context).use { db ->
            assertEquals(0, db.getChat(chat)!!.getJSONArray("messages").length())
            assertEquals("stopped", db.listRequestAccounting(chat).single().getString("local_status"))
            assertEquals("prefix ", db.getRequestRecoveryArchive(chat, requestId)!!.getString("text"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun checkpointCursorIsStrictlyIncreasingAndCannotOverwriteTerminalOrOpenAiState() {
        started()
        ChatDatabase(context).use { db ->
            assertTrue(db.checkpointOpenRouterResponse(chat, requestId, "newer", 2L))
            assertFalse(db.checkpointOpenRouterResponse(chat, requestId, "older", 1L))
            assertFalse(db.checkpointOpenRouterResponse(chat, requestId, "equal stale", 2L))
            assertEquals("newer", db.getActiveResponse(chat)!!.getString("text"))
            db.updateActiveResponse(chat, text = "canonical", status = "completed", lastSequenceNumber = 2L,
                expectedRequestId = requestId)
            assertFalse(db.checkpointOpenRouterResponse(chat, requestId, "late terminal", 3L))
            assertEquals("canonical", db.getActiveResponse(chat)!!.getString("text"))
        }
        started("openai-request", provider = "openai")
        ChatDatabase(context).use { db ->
            assertFalse(db.checkpointOpenRouterResponse(chat, "openai-request", "wrong transport", 100L))
            db.updateActiveResponse(chat, text = "unsequenced new text", status = "in_progress", expectedRequestId = "openai-request")
            assertEquals("", db.getActiveResponse(chat)!!.getString("text"))
            db.updateActiveResponse(chat, text = "OpenAI event", status = "in_progress", lastSequenceNumber = 20L,
                expectedRequestId = "openai-request")
            db.updateActiveResponse(chat, text = "stale equal event", status = "in_progress", lastSequenceNumber = 20L,
                expectedRequestId = "openai-request")
            db.updateActiveResponse(chat, text = "unsequenced rollback", status = "in_progress", expectedRequestId = "openai-request")
            val checkpoint = db.getActiveResponse(chat)!!
            assertEquals("OpenAI event", checkpoint.getString("text"))
            assertEquals(20L, checkpoint.getLong("last_sequence_number"))
        }
    }
}
