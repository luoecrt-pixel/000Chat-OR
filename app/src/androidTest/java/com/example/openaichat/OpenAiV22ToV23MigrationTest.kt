package com.example.openaichat

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opens a complete existing v22 file through every migration up to the current v25 schema. */
@RunWith(AndroidJUnit4::class)
class OpenAiV22ToV23MigrationTest {
    @Test fun nonDestructiveUpgradePreservesLegacyMessagesAttachmentsAndLedger() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.cacheDir, "v22-fixture-" + UUID.randomUUID()).apply { mkdirs() }
        val path = File(folder, "astra_chats.db")
        val chatId = "old-chat-" + UUID.randomUUID()
        val localRequestId = "old-req-" + UUID.randomUUID()
        val attachmentPaths = JSONArray().put("/unchanged/legacy/owned-file.txt").toString()
        val annotations = JSONArray().put(org.json.JSONObject()
            .put("type", "file_citation").put("filename", "existing.pdf")).toString()
        val imageManifest = JSONArray().put(org.json.JSONObject()
            .put("state", "READY").put("local_path", "/unchanged/paid.png")).toString()

        // A complete historical schema, with v23/v24/v25 additions genuinely absent.
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            LegacyOpenAiMigrationSchema.create(db, version = 22)
            db.execSQL("INSERT INTO chats (id,title,response_id,updated_at) VALUES (?,?,?,?)",
                arrayOf(chatId, "Old saved chat", "resp_old", 123456L))
            db.execSQL("""INSERT INTO messages (
                chat_id,position,role,text,response_id,request_id,file_paths,
                annotations,generated_images,status,provider,requested_model
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                arrayOf(chatId, 0, "assistant", "Preserved original answer",
                    "resp_old", localRequestId, attachmentPaths, annotations,
                    imageManifest, "completed", "openai", "old-model"))
            db.execSQL("""INSERT INTO request_accounting (
                chat_id,local_request_id,provider,credential_source,created_at,
                updated_at,local_status,input_tokens,output_tokens,cost_kind,cost_usd
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                arrayOf(chatId, localRequestId, "openai", "openai_api_key", 10L,
                    20L, "completed", 125, 250, "provider_reported", "0.025"))
            db.version = 22
        }
        val isolatedContext = object : ContextWrapper(base) {
            override fun getDatabasePath(name: String): File = path
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
            ): SQLiteDatabase = SQLiteDatabase.openDatabase(path.absolutePath,
                factory, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY)
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?
            ): SQLiteDatabase = SQLiteDatabase.openDatabase(path.absolutePath,
                factory, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        }
        try {
            ChatDatabase(isolatedContext).use { upgraded ->
                assertEquals(25, upgraded.readableDatabase.version)
                LegacyOpenAiMigrationSchema.assertCurrentAdditions(upgraded.readableDatabase)
                val chat = upgraded.getChat(chatId)!!
                val restored = chat.getJSONArray("messages")
                assertEquals(1, restored.length())
                val old = restored.getJSONObject(0)
                assertEquals("Preserved original answer", old.getString("text"))
                assertEquals("resp_old", old.getString("response_id"))
                assertEquals(attachmentPaths, old.getJSONArray("file_paths").toString())
                assertEquals(annotations, old.getJSONArray("annotations").toString())
                assertEquals(imageManifest, old.getJSONArray("generated_images").toString())
                assertFalse("No historical signed output can be fabricated", old.has("openai_protocol_output"))
                assertFalse("No old instruction digest can be fabricated",
                    old.has("openai_instructions_sha256"))
                upgraded.readableDatabase.rawQuery(
                    "SELECT input_tokens,output_tokens,cost_kind,cost_usd FROM request_accounting WHERE local_request_id=?",
                    arrayOf(localRequestId)).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(125, cursor.getInt(0))
                    assertEquals(250, cursor.getInt(1))
                    assertEquals("provider_reported", cursor.getString(2))
                    assertEquals("0.025", cursor.getString(3))
                }
                // Reopening the migrated file must not re-run the migration.
            }
            ChatDatabase(isolatedContext).use { reopened ->
                assertEquals(25, reopened.readableDatabase.version)
                LegacyOpenAiMigrationSchema.assertCurrentAdditions(reopened.readableDatabase)
                assertEquals("Preserved original answer",
                    reopened.getChat(chatId)!!.getJSONArray("messages")
                        .getJSONObject(0).getString("text"))
            }
        } finally {
            path.delete()
            folder.deleteRecursively()
        }
    }
}


/**
 * Frozen v22 schema (history, metrics, events, ledger, rounds and local artifacts),
 * based on ChatDatabase at 5584466 with only the documented v23/v24 additions removed.
 * This never opens a current helper or fabricates a legacy DB by lowering its version.
 */
internal object LegacyOpenAiMigrationSchema {
    fun create(db: SQLiteDatabase, version: Int) {
        require(version == 22 || version == 23)
        val statements = listOf(
            """CREATE TABLE chats (
            id TEXT PRIMARY KEY NOT NULL,
            title TEXT NOT NULL,
            response_id TEXT,
            baseline_effort TEXT,
            updated_at INTEGER NOT NULL,
            active_response_id TEXT,
            active_request_id TEXT,
            active_text TEXT,
            active_status TEXT,
            active_started_at INTEGER,
            active_effort_pref TEXT,
            active_effective_effort TEXT,
            active_auto_selected INTEGER NOT NULL DEFAULT 0,
            active_prompt_chars INTEGER NOT NULL DEFAULT 0,
            active_attachments INTEGER NOT NULL DEFAULT 0,
            active_background_enabled INTEGER NOT NULL DEFAULT 1,
            active_last_sequence_number INTEGER NOT NULL DEFAULT -1
            )""",
            """CREATE TABLE messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            chat_id TEXT NOT NULL,
            position INTEGER NOT NULL,
            role TEXT NOT NULL,
            text TEXT NOT NULL,
            response_id TEXT,
            request_id TEXT,
            file_ids TEXT,
            file_names TEXT,
            file_sizes TEXT,
            file_paths TEXT,
            file_mimes TEXT,
            attachments TEXT,
            image_path TEXT,
            image_file_id TEXT,
            image_name TEXT,
            image_mime TEXT,
            image_size INTEGER NOT NULL DEFAULT 0,
            annotations TEXT,
            generated_images TEXT,
            tools TEXT,
            status TEXT,
            feedback INTEGER,
            content_hash TEXT,
            provider TEXT,
            model TEXT,
            requested_model TEXT,
            response_model TEXT,
            reasoning_summary TEXT,
            error TEXT,
            tool_trace TEXT,
            usage TEXT,
            finish_reason TEXT,
            openrouter_protocol TEXT,
            protocol_context_state TEXT,
            FOREIGN KEY(chat_id) REFERENCES chats(id) ON DELETE CASCADE
            )""",
            """CREATE INDEX idx_messages_chat_position ON messages(chat_id, position)""",
            """CREATE INDEX idx_messages_chat_request ON messages(chat_id, request_id)""",
            """CREATE TABLE effort_metrics (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            chat_id TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            effort_pref TEXT NOT NULL,
            effective_effort TEXT NOT NULL,
            auto_selected INTEGER NOT NULL,
            prompt_chars INTEGER NOT NULL,
            attachments INTEGER NOT NULL,
            latency_ms INTEGER NOT NULL,
            input_tokens INTEGER NOT NULL DEFAULT 0,
            cached_tokens INTEGER NOT NULL DEFAULT 0,
            cache_write_tokens INTEGER NOT NULL DEFAULT 0,
            output_tokens INTEGER NOT NULL DEFAULT 0,
            reasoning_tokens INTEGER NOT NULL DEFAULT 0,
            success INTEGER NOT NULL,
            error_type TEXT,
            request_id TEXT,
            task_class TEXT NOT NULL DEFAULT 'general',
            feedback INTEGER,
            estimated_cost_usd REAL NOT NULL DEFAULT 0
            )""",
            """CREATE INDEX idx_effort_metrics_created ON effort_metrics(created_at)""",
            """CREATE INDEX idx_effort_metrics_task ON effort_metrics(task_class, effective_effort, feedback)""",
            """CREATE UNIQUE INDEX idx_effort_metrics_chat_request ON effort_metrics(chat_id, request_id) WHERE request_id IS NOT NULL""",
            """CREATE TABLE request_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            chat_id TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            event_type TEXT NOT NULL,
            request_id TEXT,
            provider TEXT NOT NULL,
            requested_model TEXT,
            response_model TEXT,
            model_match TEXT NOT NULL,
            status TEXT NOT NULL,
            input_tokens INTEGER NOT NULL DEFAULT 0,
            cached_tokens INTEGER NOT NULL DEFAULT 0,
            cache_write_tokens INTEGER NOT NULL DEFAULT 0,
            output_tokens INTEGER NOT NULL DEFAULT 0,
            reasoning_tokens INTEGER NOT NULL DEFAULT 0,
            estimated_cost_usd REAL NOT NULL DEFAULT 0,
            cost_kind TEXT NOT NULL DEFAULT 'unknown',
            cost_usd TEXT,
            latency_ms INTEGER NOT NULL DEFAULT 0,
            tool_summary TEXT,
            error TEXT,
            FOREIGN KEY(chat_id) REFERENCES chats(id) ON DELETE CASCADE
            )""",
            """CREATE INDEX idx_request_events_chat_created ON request_events(chat_id, created_at DESC, id DESC)""",
            """CREATE TABLE IF NOT EXISTS request_accounting (
            chat_id TEXT NOT NULL,
            local_request_id TEXT NOT NULL,
            provider TEXT NOT NULL,
            requested_model TEXT,
            credential_source TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            local_status TEXT NOT NULL DEFAULT 'running',
            provider_status TEXT,
            response_id TEXT,
            http_request_id TEXT,
            input_tokens INTEGER,
            cached_tokens INTEGER,
            output_tokens INTEGER,
            reasoning_tokens INTEGER,
            cost_kind TEXT NOT NULL DEFAULT 'unknown',
            cost_usd TEXT,
            error TEXT,
            rounds_sealed INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(chat_id, local_request_id),
            FOREIGN KEY(chat_id) REFERENCES chats(id) ON DELETE CASCADE
            )""",
            """CREATE INDEX IF NOT EXISTS idx_request_accounting_created ON request_accounting(created_at)""",
            """CREATE TABLE IF NOT EXISTS openrouter_request_rounds (
            chat_id TEXT NOT NULL,
            local_request_id TEXT NOT NULL,
            round_index INTEGER NOT NULL,
            generation_id TEXT,
            cost_kind TEXT NOT NULL DEFAULT 'unknown',
            cost_usd TEXT,
            input_tokens INTEGER,
            output_tokens INTEGER,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(chat_id, local_request_id, round_index),
            FOREIGN KEY(chat_id, local_request_id)
            REFERENCES request_accounting(chat_id, local_request_id)
            ON DELETE CASCADE
            )""",
            """CREATE UNIQUE INDEX IF NOT EXISTS idx_openrouter_generation_identity ON openrouter_request_rounds(generation_id) WHERE generation_id IS NOT NULL""",
            """CREATE TABLE IF NOT EXISTS answer_notifications (
            chat_id TEXT NOT NULL, request_id TEXT NOT NULL, completed INTEGER NOT NULL,
            created_at INTEGER NOT NULL, PRIMARY KEY(chat_id, request_id),
            FOREIGN KEY(chat_id) REFERENCES chats(id) ON DELETE CASCADE
            )""",
            """CREATE TABLE IF NOT EXISTS openrouter_checkpoints (
            chat_id TEXT NOT NULL, request_id TEXT NOT NULL, protocol TEXT NOT NULL,
            PRIMARY KEY(chat_id, request_id), FOREIGN KEY(chat_id) REFERENCES chats(id) ON DELETE CASCADE
            )""",
            """CREATE TABLE IF NOT EXISTS local_artifacts (
            path TEXT PRIMARY KEY NOT NULL, chat_id TEXT NOT NULL, request_id TEXT NOT NULL,
            created_at INTEGER NOT NULL, metadata TEXT NOT NULL,
            FOREIGN KEY(chat_id) REFERENCES chats(id) ON DELETE CASCADE
            )"""
        )
        statements.forEach(db::execSQL)
        if (version >= 23) {
            db.execSQL("ALTER TABLE messages ADD COLUMN openai_instructions_sha256 TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN openai_protocol_output TEXT")
        }
        assertFalse(columns(db, "chats").contains("active_indexed_checkpoint"))
        assertFalse(columns(db, "messages").contains("telemetry_summary"))
        assertFalse(columns(db, "request_accounting").contains("cache_write_tokens"))
        assertFalse(columns(db, "openrouter_request_rounds").contains("usage_snapshot"))
        assertEquals(version >= 23, columns(db, "messages").contains("openai_protocol_output"))
        db.version = version
    }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    fun assertCurrentAdditions(db: SQLiteDatabase) {
        assertTrue(columns(db, "chats").contains("active_indexed_checkpoint"))
        assertTrue(columns(db, "messages").containsAll(setOf(
            "openai_instructions_sha256", "openai_protocol_output", "telemetry_summary", "openai_usage_ledger")))
        assertTrue(columns(db, "request_accounting").containsAll(setOf("telemetry_summary", "cache_write_tokens")))
        assertTrue(columns(db, "openrouter_request_rounds").contains("usage_snapshot"))
        assertTrue(columns(db, "effort_metrics").contains("cost_kind"))
        assertTrue(columns(db, "request_recovery_archive").contains("recovery_message"))
    }
}
