package com.example.openaichat

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteException
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

/** Real SharedPreferences -> SQLite migration must not consume unreadable history. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LegacyPrefsMigrationSafetyTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private var database: ChatDatabase? = null
    private val sourceKey = "chat_store"
    private val markerKey = "chat_store_migrated_v7"

    @Before fun before() {
        context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("legacy-prefs-migration-safety", Context.MODE_PRIVATE)
        reset()
    }

    @After fun after() {
        database?.close()
        database = null
        context.deleteDatabase("astra_chats.db")
        prefs.edit().clear().commit()
    }

    private fun reset() {
        database?.close()
        database = null
        context.deleteDatabase("astra_chats.db")
        assertTrue(prefs.edit().clear().commit())
    }

    private fun db(): ChatDatabase = database ?: ChatDatabase(context).also { database = it }

    private fun reopen(): ChatDatabase {
        database?.close()
        database = null
        return db()
    }

    // Any? lets the corruption regression also compile against the former Unit
    // API, so its baseline fails on lost data rather than a changed signature.
    private fun migrate(): Any? = db().migrateLegacyPrefs(prefs)

    private fun message(text: String = "Original text \u0000 字🙂  \n") = JSONObject()
        .put("role", "assistant").put("text", text).put("request_id", "legacy-request")
        .put("provider", "openai").put("requested_model", "legacy-model")
        .put("status", "completed").put("openai_instructions_sha256", "original-instructions-hash")
        .put("openai_protocol_output", JSONArray().put(JSONObject().put("type", "reasoning")
            .put("id", "original-reasoning").put("encrypted_content", "opaque-original")))
        .put("usage", JSONObject().put("input_tokens", 7).put("output_tokens", 11))

    private fun chat(id: String, text: String = "Original text \u0000 字🙂  \n") = JSONObject()
        .put("id", id).put("title", "Legacy title 字🙂").put("response_id", "legacy-response")
        .put("baseline_effort", "high").put("messages", JSONArray().put(message(text)))

    private fun seed(raw: String) {
        assertTrue(prefs.edit().putString(sourceKey, raw).remove(markerKey).commit())
    }

    private fun assertSourceRetained(raw: String) {
        assertEquals("The only raw legacy source must stay byte-for-byte available", raw, prefs.getString(sourceKey, null))
        assertFalse("Incomplete migration must remain retryable", prefs.getBoolean(markerKey, false))
    }

    @Test fun malformedLegacyJsonRemainsAvailableAfterReopenAndRetry() {
        for (raw in listOf("[{\"id\":\"damaged\",\"messages\":[\"Получено 🙂\"", "[]\u0000trailing", "[]\u0000", "[] trailing")) {
            reset()
            seed(raw)
            val result = migrate()
            // These checks expose the old catch->empty-array->remove-source path.
            assertSourceRetained(raw)
            assertEquals(false, result)
            assertTrue(reopen().listChats().isEmpty())
            assertEquals(false, migrate())
            assertSourceRetained(raw)
        }
    }

    @Test fun invalidEntriesAndDuplicateIdsRejectTheWholeBatchWithoutConsumingSource() {
        val badEntries = listOf<Any>(
            "not a chat", JSONObject.NULL,
            chat(" "), chat("bad-id-type").put("id", 7),
            chat("bad-messages").put("messages", "not an array"),
            chat("bad-message-entry").put("messages", JSONArray().put("not a message")),
            chat("bad-null-message").put("messages", JSONArray().put(JSONObject.NULL)),
            chat("first", "Duplicate legacy chat must not win"),
            chat(" first ", "Normalized duplicate legacy chat must not win")
        )
        for (bad in badEntries) {
            reset()
            val raw = JSONArray().put(chat("first")).put(bad).toString()
            seed(raw)
            assertEquals("Invalid batch must report incomplete migration: $bad", false, migrate())
            assertSourceRetained(raw)
            assertTrue("Validation must happen before importing the first valid chat", reopen().listChats().isEmpty())
        }
    }

    @Test fun existingSqliteChatAndItsAccountingAndActiveCheckpointAreNotReplaced() {
        val current = message("Newer SQLite answer 字🙂").put("provider", "openrouter")
            .put("request_id", "sqlite-current").put("response_model", "anthropic/claude-test")
        db().upsertCurrentChat("same-id", "Newer SQLite title", null, "max", listOf(current))
        db().setActiveResponse("same-id", null, "sqlite-current", "Current partial 字🙂", "in_progress", 1L,
            "max", "max", false, 8, 0, false)
        assertTrue(db().startRequestAccounting("same-id", "sqlite-current", "openrouter", "anthropic/claude-test", "openrouter_api_key"))
        val protocol = JSONObject().put("version", 1).put("tail", JSONArray()).put("local-evidence", "Original signed bytes")
        assertTrue(db().saveOpenRouterCheckpoint("same-id", "sqlite-current", protocol))
        val beforeChat = db().getChat("same-id")!!.toString()
        val beforeAccounting = db().listRequestAccounting("same-id").single().toString()
        val beforeActive = db().getActiveResponse("same-id")!!.toString()
        val raw = JSONArray().put(chat("would-import-first")).put(chat(" same-id ", "Older preference answer")).toString()
        seed(raw)

        assertEquals(false, migrate())
        assertSourceRetained(raw)
        val reopened = reopen()
        assertEquals(beforeChat, reopened.getChat("same-id")!!.toString())
        assertEquals(beforeAccounting, reopened.listRequestAccounting("same-id").single().toString())
        assertEquals(beforeActive, reopened.getActiveResponse("same-id")!!.toString())
        assertEquals(protocol.toString(), reopened.getOpenRouterCheckpoint("same-id", "sqlite-current")!!.toString())
        assertNull("A later conflict must also roll back the first import", reopened.getChat("would-import-first"))
    }

    @Test fun validLegacyHistoryKeepsExactTextMetadataAndFingerprintAfterReopen() {
        val original = chat("legacy-valid")
        seed(JSONArray().put(original).toString())
        assertEquals(true, migrate())
        assertTrue(prefs.getBoolean(markerKey, false))
        assertFalse(prefs.contains(sourceKey))
        val loaded = reopen().getChat("legacy-valid")!!
        assertEquals(original.getString("title"), loaded.getString("title"))
        assertEquals(original.getString("response_id"), loaded.getString("response_id"))
        val expected = original.getJSONArray("messages").getJSONObject(0)
        val actual = loaded.getJSONArray("messages").getJSONObject(0)
        assertEquals(expected.getString("text"), actual.getString("text"))
        assertEquals(expected.getJSONArray("openai_protocol_output").toString(), actual.getJSONArray("openai_protocol_output").toString())
        assertEquals(expected.getJSONObject("usage").toString(), actual.getJSONObject("usage").toString())
        assertEquals(expected.getString("openai_instructions_sha256"), actual.getString("openai_instructions_sha256"))
        assertEquals(ChatMessageFingerprint.of(expected), ChatMessageFingerprint.of(actual))
        assertEquals(true, migrate())
        assertEquals(1, reopen().getChat("legacy-valid")!!.getJSONArray("messages").length())
    }

    @Test fun absentEmptyAndHistoricallyEmptyChatsCanFinishMigration() {
        val sources = listOf(null, "", "  \n", "[]", JSONArray().put(JSONObject().put("id", "empty-chat")).toString())
        for (raw in sources) {
            reset()
            if (raw != null) seed(raw)
            assertEquals(true, migrate())
            assertTrue(prefs.getBoolean(markerKey, false))
            assertFalse(prefs.contains(sourceKey))
            if (raw?.contains("empty-chat") == true)
                assertEquals(0, reopen().getChat("empty-chat")!!.getJSONArray("messages").length())
            else assertTrue(reopen().listChats().isEmpty())
        }
    }

    @Test fun completedMarkerMakesLaterCallsNoOpWithoutDeletingAnotherSource() {
        db().upsertCurrentChat("existing", "Existing", null, null, listOf(message("Current SQL")))
        val raw = JSONArray().put(chat("existing", "Stale preference" )).toString()
        assertTrue(prefs.edit().putString(sourceKey, raw).putBoolean(markerKey, true).commit())
        assertEquals(true, migrate())
        assertEquals(raw, prefs.getString(sourceKey, null))
        assertEquals("Current SQL", reopen().getChat("existing")!!.getJSONArray("messages").getJSONObject(0).getString("text"))
    }

    @Test fun sqliteFailureRollsBackEntireBatchAndLeavesSourceForSuccessfulRetry() {
        val raw = JSONArray().put(chat("first")).put(chat("second")).toString()
        seed(raw)
        db().writableDatabase.execSQL("""CREATE TRIGGER reject_legacy_second BEFORE INSERT ON messages
            WHEN NEW.chat_id='second' BEGIN SELECT RAISE(ABORT, 'fixture migration failure'); END""")
        assertThrows(SQLiteException::class.java) { migrate() }
        assertSourceRetained(raw)
        assertTrue(reopen().listChats().isEmpty())
        db().writableDatabase.execSQL("DROP TRIGGER reject_legacy_second")
        assertEquals(true, migrate())
        assertFalse(prefs.contains(sourceKey))
        assertTrue(prefs.getBoolean(markerKey, false))
        assertEquals(setOf("first", "second"), reopen().listChats().map { it.getString("id") }.toSet())
        for (id in listOf("first", "second"))
            assertEquals(message().getString("text"), db().getChat(id)!!.getJSONArray("messages").getJSONObject(0).getString("text"))
    }
}
