package com.example.openaichat

import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Non-destructive upgrade of a genuine v23 active flat response cursor. */
@RunWith(AndroidJUnit4::class)
class OpenAiV23ToV24ActiveCheckpointMigrationTest {
    @Test fun preexistingFlatTextAndSequenceStayUntouchedAndIndexedStateStaysUnknown() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.cacheDir, "v23-indexed-" + UUID.randomUUID()).apply { mkdirs() }
        val file = File(folder, "astra_chats.db")
        val chatId = "old-active-" + UUID.randomUUID()
        val requestId = "owned-v23"
        val signedOutput = """[{"type":"reasoning","encrypted_content":"preserved-v23-signature"}]"""
        val instructionFingerprint = "existing-v23-instruction-binding"
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            LegacyOpenAiMigrationSchema.create(db, version = 23)
            db.execSQL("""INSERT INTO chats(
                id,title,updated_at,active_response_id,active_request_id,
                active_text,active_status,active_started_at,active_last_sequence_number)
                VALUES (?,?,?, ?,?,?,?,?,?)""",
                arrayOf(chatId, "Unfinished", 1L, "resp_v23", requestId,
                    "Hello", "in_progress", 1L, 10))
            db.execSQL("""INSERT INTO messages (
                chat_id,position,role,text,response_id,request_id,provider,status,
                openai_instructions_sha256,openai_protocol_output
                ) VALUES (?,?,?,?,?,?,?,?,?,?)""",
                arrayOf(chatId, 0, "assistant", "Existing signed answer", "resp_earlier", "local_earlier",
                    "openai", "completed", instructionFingerprint, signedOutput))
            db.execSQL("""INSERT INTO request_accounting (
                chat_id,local_request_id,provider,credential_source,created_at,updated_at,
                local_status,response_id,input_tokens,output_tokens
                ) VALUES (?,?,?,?,?,?,?,?,?,?)""",
                arrayOf(chatId, requestId, "openai", "openai_api_key", 1L, 1L,
                    "running", "resp_v23", 100, 25))
        }
        val isolated = object : ContextWrapper(base) {
            override fun getDatabasePath(name: String): File = file
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
            ): SQLiteDatabase = SQLiteDatabase.openDatabase(file.absolutePath, factory,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY)
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?
            ): SQLiteDatabase = SQLiteDatabase.openDatabase(file.absolutePath, factory,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        }
        try {
            ChatDatabase(isolated).use { db ->
                assertEquals(25, db.readableDatabase.version)
                LegacyOpenAiMigrationSchema.assertCurrentAdditions(db.readableDatabase)
                val active = db.getActiveResponse(chatId)!!
                assertEquals("Hello", active.getString("text"))
                assertEquals(10L, active.getLong("last_sequence_number"))
                assertEquals(requestId, active.getString("request_id"))
                assertFalse(active.has("indexed_checkpoint"))
                assertTrue(ResponseIndexedCheckpoint.restore(active).legacyFlatCheckpoint)
                val historical = db.getChat(chatId)!!.getJSONArray("messages").getJSONObject(0)
                assertEquals("Existing signed answer", historical.getString("text"))
                assertEquals(instructionFingerprint, historical.getString("openai_instructions_sha256"))
                assertEquals(signedOutput, historical.getJSONArray("openai_protocol_output").toString())
                val accounting = db.listRequestAccounting(chatId).single()
                assertEquals("running", accounting.getString("local_status"))
                assertEquals(100L, accounting.getLong("input_tokens"))
                assertEquals(25L, accounting.getLong("output_tokens"))
            }
            ChatDatabase(isolated).use { db ->
                LegacyOpenAiMigrationSchema.assertCurrentAdditions(db.readableDatabase)
                val active = db.getActiveResponse(chatId)!!
                assertEquals("Hello", active.getString("text"))
                assertEquals(10L, active.getLong("last_sequence_number"))
                assertEquals(requestId, active.getString("request_id"))
                assertFalse(active.has("indexed_checkpoint"))
                assertTrue(ResponseIndexedCheckpoint.restore(active).legacyFlatCheckpoint)
                assertEquals(signedOutput, db.getChat(chatId)!!.getJSONArray("messages")
                    .getJSONObject(0).getJSONArray("openai_protocol_output").toString())
                assertEquals(25, db.readableDatabase.version)
            }
        } finally {
            file.delete()
            folder.deleteRecursively()
        }
    }
}
