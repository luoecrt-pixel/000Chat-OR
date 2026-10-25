package com.example.openaichat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Real SQLite persistence/reopen and ownership tests for the state consumed
 * by the offline legacy checkpoint decoder.
 */
@RunWith(AndroidJUnit4::class)
class ActiveResponseIndexedCheckpointPersistenceTest {
    @Test fun atomicCheckpointSurvivesReopenAndPreventsOutOfOrderCallbacks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chatId = "indexed-sse-" + UUID.randomUUID()
        val requestId = "owned-" + UUID.randomUUID()
        try {
            val initial = ResponseEventState(text = "Hello World", indexedTextParts = mapOf(
                ResponseContentIndex(0, 0) to "Hello ", ResponseContentIndex(1, 0) to "World"))
            assertEquals("Hello World", initial.text)
            ChatDatabase(context).use { db ->
                db.ensureChat(chatId, "Indexed partial")
                db.setActiveResponse(chatId, "resp_owned", requestId, "", "starting",
                    1L, "high", "high", false, 10, 0, true)
                db.updateActiveResponse(chatId, "resp_owned", initial.text,
                    "in_progress", 2, requestId, ResponseIndexedCheckpoint.serialize(initial))
                var checkpoint = db.getActiveResponse(chatId)!!
                assertEquals(2L, checkpoint.getLong("last_sequence_number"))
                assertEquals(initial.text, checkpoint.getString("text"))
                assertTrue(checkpoint.has("indexed_checkpoint"))
                db.updateActiveResponse(chatId, "resp_owned", "corrupted",
                    "in_progress", 2, requestId, "bad-snapshot")
                checkpoint = db.getActiveResponse(chatId)!!
                assertEquals(initial.text, checkpoint.getString("text"))
                assertEquals(ResponseIndexedCheckpoint.serialize(initial),
                    checkpoint.getString("indexed_checkpoint"))
            }
            ChatDatabase(context).use { db ->
                // Process / SQLiteOpenHelper reopen restores the same indexed
                // prefix; seq=3 must extend the *existing* indexed part.
                val saved = db.getActiveResponse(chatId)!!
                val restored = ResponseIndexedCheckpoint.restore(saved)
                assertEquals("Hello World", restored.text)
                val resumed = restored.copy(text = "Hello there, World again", indexedTextParts = mapOf(
                    ResponseContentIndex(0, 0) to "Hello there, ", ResponseContentIndex(1, 0) to "World again"))
                assertEquals("Hello there, World again", resumed.text)
                db.updateActiveResponse(chatId, "resp_owned", resumed.text,
                    "in_progress", 4, requestId, ResponseIndexedCheckpoint.serialize(resumed))
                db.updateActiveResponse(chatId, "resp_owned", "late wrong owner",
                    "in_progress", 5, "other-request", "invalid")
                db.updateActiveResponse(chatId, "resp_owned", "late seq",
                    "in_progress", 3, requestId, "invalid")
                val latest = db.getActiveResponse(chatId)!!
                assertEquals(4L, latest.getLong("last_sequence_number"))
                assertEquals("Hello there, World again", latest.getString("text"))
                assertEquals(ResponseIndexedCheckpoint.serialize(resumed),
                    latest.getString("indexed_checkpoint"))
                db.updateActiveResponse(chatId, "resp_owned", "terminal canonical",
                    "completed", 5, requestId, ResponseIndexedCheckpoint.serialize(
                        ResponseEventState(text = "terminal canonical",
                            unindexedText = "terminal canonical")))
                db.updateActiveResponse(chatId, "resp_owned", "too late",
                    "in_progress", 6, requestId, "invalid")
                assertEquals("terminal canonical", db.getActiveResponse(chatId)!!.getString("text"))
            }
        } finally {
            ChatDatabase(context).use { it.deleteChat(chatId) }
        }
    }
}
