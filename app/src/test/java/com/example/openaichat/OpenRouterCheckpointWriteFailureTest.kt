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

/** A checkpoint-table failure must not discard received bytes if terminal SQLite still works. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OpenRouterCheckpointWriteFailureTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var client: OpenRouterChatClient
    private val chat = "checkpoint-write-failure"
    private val request = "checkpoint-write-request"
    private val text = "Получено полностью 字🙂"

    @Before fun before() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("app", 0).edit().clear().putBoolean("response_notifications", false).commit()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
        ChatDatabase(context).use { db ->
            db.ensureChat(chat)
            db.setActiveResponse(chat, null, request, "", "in_progress", System.currentTimeMillis(),
                "high", "high", false, 0, 0, false)
            assertTrue(db.startRequestAccounting(chat, request, "openrouter", "anthropic/claude-test", "openrouter_api_key"))
        }
        server = MockWebServer().apply { start() }
        client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
    }

    @After fun after() {
        OpenRouterResponseRuntime.cancel(request)
        awaitGone()
        client.close()
        server.shutdown()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }

    private fun awaitGone() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (OpenRouterResponseRuntime.contains(request) && System.nanoTime() < deadline) Thread.sleep(5)
        assertFalse("Runtime must stop without another POST", OpenRouterResponseRuntime.contains(request))
    }

    private fun start(endWithDone: Boolean) {
        val delta = JSONObject().put("content", text).put("reasoning_details", JSONArray().put(JSONObject()
            .put("index", 0).put("type", "reasoning.encrypted").put("data", "opaque-original")
            .put("signature", "signature-original")))
        val event = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta)))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: $event\n\n" + if (endWithDone) "data: [DONE]\n\n" else ""))
        OpenRouterResponseRuntime.start(context, OpenRouterRuntimeRequest(
            chatId = chat, requestId = request, model = "anthropic/claude-test",
            messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Continue")),
            attachments = emptyList(), effort = "high", verbosity = "", maxTokens = null,
            github = false, githubRepo = "", githubBranches = emptySet(), webSearch = false, webFetch = false,
            parallel = false, cacheMode = "off", background = false, startedAt = SystemClock.elapsedRealtime(),
            taskClass = "general", effortPref = "high", autoSelected = false, promptChars = 8,
            apiKey = "synthetic-checkpoint-key", metadataClient = OkHttpClient(), pdfEngine = "native"
        ), client)
        awaitGone()
    }

    private fun assertReopenedTerminalRetainsExactReceivedTextAndUnreplayableReasoning() {
        ChatDatabase(context).use { db ->
            assertNull(db.getActiveResponse(chat))
            val messages = db.getChat(chat)!!.getJSONArray("messages")
            assertEquals(1, messages.length())
            val message = messages.getJSONObject(0)
            assertEquals("failed", message.getString("status"))
            assertEquals(text, message.getString("text"))
            val protocol = message.getJSONObject("openrouter_protocol")
            val interrupted = protocol.getJSONObject("interrupted_assistant")
            assertEquals(text, interrupted.getString("content"))
            assertFalse(interrupted.getBoolean("replay_safe"))
            val details = interrupted.getJSONArray("reasoning_details").getJSONObject(0)
            assertEquals("signature-original", details.getString("signature"))
            assertEquals("opaque-original", details.getString("data"))
            assertEquals(1, interrupted.getJSONArray("reasoning_delta_fragments").length())
            val replay = JSONArray()
            assertTrue(OpenRouterProtocolCodec.append(replay, message))
            for (i in 0 until replay.length()) assertFalse(replay.getJSONObject(i).has("reasoning_details"))
            assertEquals(text, replay.getJSONObject(replay.length() - 1).getString("content"))
            assertEquals(1, db.listRequestAccounting(chat).size)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun checkpointInsertFailureStillFinalizesLatestTextAndReasoningUsingOwnerAwareTerminalTransaction() {
        ChatDatabase(context).use { db -> db.writableDatabase.execSQL("""CREATE TRIGGER fail_stream_checkpoint
            BEFORE INSERT ON openrouter_checkpoints BEGIN SELECT RAISE(ABORT, 'controlled checkpoint failure'); END""") }
        start(endWithDone = true)
        assertReopenedTerminalRetainsExactReceivedTextAndUnreplayableReasoning()
    }

    @Test fun brokenSseAfterTextAndReasoningRetainsCurrentRoundInsteadOfOnlyEarlierToolRoundText() {
        start(endWithDone = false)
        assertReopenedTerminalRetainsExactReceivedTextAndUnreplayableReasoning()
    }
}
