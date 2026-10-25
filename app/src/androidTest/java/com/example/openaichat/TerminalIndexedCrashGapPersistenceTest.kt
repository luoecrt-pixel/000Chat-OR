package com.example.openaichat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Native SQLite/reopen coverage for imported Responses bytes; no provider transport executes. */
@RunWith(AndroidJUnit4::class)
class TerminalIndexedCrashGapPersistenceTest {
    private fun prepare(db: ChatDatabase, chat: String, request: String, state: ResponseEventState) {
        db.ensureChat(chat, "Legacy crash gap")
        db.upsertCurrentChat(chat, "Legacy crash gap", null, "high", listOf(JSONObject()
            .put("role", "user").put("text", "Saved prompt").put("provider", "openai").put("request_id", request)))
        db.startRequestAccounting(chat, request, "openai", "legacy-model", "legacy")
        db.setActiveResponse(chat, state.responseId, request, "", "starting", 1L, "high", "high", false, 12, 0, true)
        db.updateActiveResponse(chat, state.responseId, state.text, state.terminalState?.wireStatus ?: "in_progress",
            9, request, ResponseIndexedCheckpoint.serialize(state))
    }

    @Test fun multiMegabytePaidTerminalAndSignedItemsSurviveReopenAndOneOfflineClaim() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chat = "legacy-large-" + UUID.randomUUID()
        val request = "local_large"
        val text = "x😀\u0000".repeat(100_000)
        val signed = "signed\u0000😀".repeat(60_000)
        val reasoning = JSONObject().put("type", "reasoning").put("id", "rs_large").put("encrypted_content", signed)
        val output = JSONArray().put(reasoning).put(JSONObject().put("type", "message").put("role", "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text))))
        val terminal = JSONObject().put("id", "resp_large").put("status", "completed").put("output", output)
            .put("usage", JSONObject().put("input_tokens", 21).put("output_tokens", 45))
        val state = ResponseEventState(responseId = "resp_large", text = text,
            indexedTextParts = mapOf(ResponseContentIndex(1, 0) to text), typedOutputItems = mapOf(0 to reasoning),
            terminalState = ResponseTerminalState.COMPLETED, terminalResponse = terminal, usage = terminal.getJSONObject("usage"))
        val checkpoint = ResponseIndexedCheckpoint.serialize(state)
        assertTrue(checkpoint.toByteArray().size > 2 * 1024 * 1024)
        try {
            ChatDatabase(context).use { prepare(it, chat, request, state) }
            ChatDatabase(context).use { db ->
                assertEquals(checkpoint, db.getActiveResponse(chat)!!.getString("indexed_checkpoint"))
                assertTrue(db.recoverLegacyOpenAiRequestLocally(chat, request))
                assertFalse(db.recoverLegacyOpenAiRequestLocally(chat, request))
                assertNull(db.getActiveResponse(chat))
            }
            ChatDatabase(context).use { db ->
                val saved = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(1)
                assertEquals(text, saved.getString("text"))
                assertEquals("openai", saved.getString("provider"))
                assertEquals("completed", saved.getString("status"))
                assertEquals(signed, saved.getJSONArray("openai_protocol_output").getJSONObject(0).getString("encrypted_content"))
                val archive = db.getRequestRecoveryArchive(chat, request)!!
                assertEquals(checkpoint, archive.getJSONObject("legacy_active_snapshot").getString("indexed_checkpoint"))
                assertEquals(45, saved.getJSONObject("usage").getInt("output_tokens"))
            }
        } finally { ChatDatabase(context).use { it.deleteChat(chat) } }
    }

    @Test fun signedReasoningAndToolsWithNoVisibleTextAreRetiredLocallyWithoutLoss() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chat = "legacy-signed-" + UUID.randomUUID()
        val request = "local_signed"
        val state = ResponseEventState(responseId = "resp_signed", reasoningSummary = "Received readable summary",
            typedOutputItems = mapOf(0 to JSONObject().put("type", "reasoning").put("id", "signed_only")
                .put("encrypted_content", "original-signed-bytes"),
                1 to JSONObject().put("type", "web_search_call").put("id", "tool_before_text").put("status", "completed")))
        try {
            ChatDatabase(context).use { prepare(it, chat, request, state) }
            ChatDatabase(context).use { db ->
                assertTrue(db.recoverLegacyOpenAiRequestLocally(chat, request))
                val saved = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(1)
                assertEquals("", saved.getString("text"))
                assertTrue(saved.getBoolean("incomplete"))
                assertEquals("Received readable summary", saved.getString("reasoning_summary"))
                assertEquals("original-signed-bytes", saved.getJSONArray("openai_protocol_output").getJSONObject(0).getString("encrypted_content"))
                assertEquals("tool_before_text", saved.getJSONArray("openai_protocol_output").getJSONObject(1).getString("id"))
            }
        } finally { ChatDatabase(context).use { it.deleteChat(chat) } }
    }

    @Test fun oldFlatCheckpointRetainsVisiblePartialAndNeverStartsAContinuation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chat = "legacy-flat-" + UUID.randomUUID()
        val request = "local_flat"
        try {
            ChatDatabase(context).use { db ->
                db.ensureChat(chat, "Old flat")
                db.startRequestAccounting(chat, request, "openai", "legacy-model", "legacy")
                db.setActiveResponse(chat, "resp_flat", request, "Hello", "in_progress", 1L,
                    "high", "high", false, 5, 0, true)
                db.updateActiveResponse(chat, "resp_flat", "Hello", "in_progress", 10, request)
            }
            ChatDatabase(context).use { db ->
                assertTrue(ResponseIndexedCheckpoint.restore(db.getActiveResponse(chat)!!).legacyFlatCheckpoint)
                assertTrue(db.recoverLegacyOpenAiRequestLocally(chat, request))
                assertFalse(db.recoverLegacyOpenAiRequestLocally(chat, request))
                val saved = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(0)
                assertEquals("Hello", saved.getString("text"))
                assertEquals("incomplete", saved.getString("status"))
                assertNull(db.getActiveResponse(chat))
            }
        } finally { ChatDatabase(context).use { it.deleteChat(chat) } }
    }
}
