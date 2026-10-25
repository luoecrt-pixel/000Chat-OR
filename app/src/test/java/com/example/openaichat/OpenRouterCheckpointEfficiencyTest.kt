package com.example.openaichat

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong

/** Count bytes really inserted into SQLite by the HTTP/SSE checkpoint consumer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OpenRouterCheckpointEfficiencyTest {
    private lateinit var context: Context
    private val chat = "checkpoint-efficiency"
    private val request = "checkpoint-efficiency-request"

    @Before fun before() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
        ChatDatabase(context).use { db ->
            db.ensureChat(chat)
            db.setActiveResponse(chat, null, request, "", "in_progress", 1L, "max", "max", false, 0, 0, false)
            assertTrue(db.startRequestAccounting(chat, request, "openrouter", "anthropic/claude-test", "openrouter_api_key"))
            db.writableDatabase.execSQL("CREATE TABLE checkpoint_write_probe (bytes INTEGER NOT NULL)")
            db.writableDatabase.execSQL("""CREATE TRIGGER count_checkpoint_bytes AFTER INSERT ON openrouter_checkpoints
                BEGIN INSERT INTO checkpoint_write_probe VALUES (length(CAST(NEW.protocol AS BLOB)) +
                    (SELECT length(CAST(active_text AS BLOB)) FROM chats WHERE id=NEW.chat_id)); END""")
        }
    }

    private fun delta(value: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", value)))
    private fun frame(value: Any) = "data: $value\n\n"

    private fun complete(events: List<Any>, clock: () -> Long): Pair<OpenRouterCompletionResult, Long> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(events.joinToString("") { frame(it) }))
            val state = OpenRouterStreamState()
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false,
                monotonicNanos = clock)
            var checkpointBytes = 0L
            try {
                val result = client.complete(apiKey = "synthetic-efficiency-key", model = "anthropic/claude-test",
                    messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Continue")),
                    tools = null, maxTokens = null, reasoningEffort = "max", verbosity = null,
                    parallelToolCalls = false, executeTool = { _, _ -> error("No tool may execute") },
                    streamState = state, onStreamCheckpoint = { snapshot ->
                        ChatDatabase(context).use { db -> assertTrue(db.checkpointOpenRouterStream(chat, request, snapshot)) }
                        checkpointBytes += snapshot.protocol.toString().toByteArray(Charsets.UTF_8).size +
                            snapshot.text.toByteArray(Charsets.UTF_8).size
                        state.persisted()
                    })
                assertEquals(1, server.requestCount)
                return result to checkpointBytes
            } finally { client.close() }
        }
    }

    private fun writtenBytes(expectedWrites: Int, expectedBytes: Long) = ChatDatabase(context).use { db ->
        db.readableDatabase.rawQuery("SELECT COUNT(*), SUM(bytes) FROM checkpoint_write_probe", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            println("checkpoint_sql_writes=${cursor.getInt(0)} checkpoint_sql_bytes=${cursor.getLong(1)}")
            assertEquals("Only changed durable payloads may be written; bytes=${cursor.getLong(1)}", expectedWrites, cursor.getInt(0))
            assertEquals("Probe counts bytes actually inserted into SQLite", expectedBytes, cursor.getLong(1))
        }
    }

    @Test fun emptyAndUsageSseFramesDoNotRecopyMegabyteReasoningIntoSqlite() {
        val opaque = "x".repeat(1_048_576)
        val details = JSONArray().put(JSONObject().put("index", 0).put("type", "reasoning.encrypted")
            .put("data", opaque).put("signature", "exact-signature"))
        val events = buildList<Any> {
            add(delta(JSONObject().put("reasoning_details", details)))
            repeat(96) { index ->
                add(delta(JSONObject().put("content", "").put("reasoning_details", JSONArray()).put("tool_calls", JSONArray())))
                add(JSONObject().put("choices", JSONArray()))
                add(JSONObject().put("choices", JSONArray()).put("usage", JSONObject().put("total_tokens", index + 1)))
            }
            add(delta(JSONObject().put("content", "Final 字🙂")).apply {
                getJSONArray("choices").getJSONObject(0).put("finish_reason", "stop")
            })
            add("[DONE]")
        }
        // Each clock read advances by more than the checkpoint interval. This
        // exposes timer-driven rewrites without a slow wall-clock load test.
        val clock = AtomicLong(1L)
        val (result, bytes) = complete(events) { clock.addAndGet(600_000_000L) }
        writtenBytes(2, bytes)
        assertTrue("Two changed snapshots stay below 5 MiB despite hundreds of idle frames", bytes < 5L * 1024 * 1024)
        assertEquals("Final 字🙂", result.text)
        assertEquals(96L, result.usage!!.getLong("total_tokens"))
        assertEquals(opaque, result.assistantMessage.getJSONArray("reasoning_details").getJSONObject(0).getString("data"))
        ChatDatabase(context).use { db ->
            val interrupted = db.getOpenRouterCheckpoint(chat, request)!!.getJSONObject("interrupted_assistant")
            assertEquals("Final 字🙂", db.getActiveResponse(chat)!!.getString("text"))
            assertEquals(opaque, interrupted.getJSONArray("reasoning_details").getJSONObject(0).getString("data"))
            assertEquals("exact-signature", interrupted.getJSONArray("reasoning_delta_fragments").getJSONObject(0).getString("signature"))
            assertFalse(interrupted.getBoolean("replay_safe"))
        }
    }

    @Test fun finalUnflushedToolArgumentsRemainDurableWithoutClockAdvance() {
        fun tool(arguments: String) = JSONArray().put(JSONObject().put("index", 0).put("id", "call-exact")
            .put("type", "function").put("function", JSONObject().put("name", "file_info").put("arguments", arguments)))
        val events = listOf(
            delta(JSONObject().put("content", "prefix ").put("tool_calls", tool("{"))),
            delta(JSONObject().put("content", "final 字🙂").put("tool_calls", tool("\"path\":\"x\"}"))),
            delta(JSONObject()).apply { getJSONArray("choices").getJSONObject(0).put("finish_reason", "length") },
            "[DONE]"
        )
        val (result, bytes) = complete(events) { 1L }
        writtenBytes(2, bytes)
        assertEquals("length", result.finishReason)
        ChatDatabase(context).use { db ->
            assertEquals("prefix final 字🙂", db.getActiveResponse(chat)!!.getString("text"))
            val interrupted = db.getOpenRouterCheckpoint(chat, request)!!.getJSONObject("interrupted_assistant")
            assertEquals("{\"path\":\"x\"}", interrupted.getJSONArray("tool_calls").getJSONObject(0)
                .getJSONObject("function").getString("arguments"))
            assertFalse(interrupted.getBoolean("replay_safe"))
        }
    }
}
