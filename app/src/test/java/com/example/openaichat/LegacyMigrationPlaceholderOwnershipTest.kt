package com.example.openaichat

import android.app.Application
import android.content.Context
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LegacyMigrationPlaceholderOwnershipTest {
    private lateinit var context: Context
    private val id = "legacy-placeholder-owner"
    private val prefs get() = context.getSharedPreferences("placeholder-ownership", Context.MODE_PRIVATE)

    @Before fun before() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("astra_chats.db")
        prefs.edit().clear().commit()
    }

    @After fun after() {
        context.deleteDatabase("astra_chats.db")
        prefs.edit().clear().commit()
    }

    private fun source(vararg ids: String) = JSONArray().apply { ids.forEach { chatId -> put(JSONObject()
        .put("id", chatId).put("title", "Recovered history")
        .put("messages", JSONArray().put(JSONObject().put("role", "assistant").put("text", "Recovered 字🙂")))) }
    }.toString()

    private fun seed(raw: String) {
        assertTrue(prefs.edit().putString("chat_store", raw).remove("chat_store_migrated_v7").commit())
    }

    private fun retained(raw: String) {
        assertEquals(raw, prefs.getString("chat_store", null))
        assertFalse(prefs.getBoolean("chat_store_migrated_v7", false))
    }

    @Test fun failedImportRestoresPlaceholderEligibilityAndRetriesAfterSqliteReopen() {
        val raw = source(id)
        seed(raw)
        ChatDatabase(context).use { db ->
            db.ensureChat(id, legacyMigrationPlaceholder = true)
            db.writableDatabase.execSQL("""CREATE TRIGGER reject_placeholder_message BEFORE INSERT ON messages
                WHEN NEW.chat_id='$id' BEGIN SELECT RAISE(ABORT, 'fixture migration rollback'); END""")
            assertThrows(SQLiteException::class.java) { db.migrateLegacyPrefs(prefs) }
            retained(raw)
            assertEquals("Новый чат", db.getChat(id)!!.getString("title"))
            assertEquals(0, db.getChat(id)!!.getJSONArray("messages").length())
        }
        ChatDatabase(context).use { db ->
            db.writableDatabase.execSQL("DROP TRIGGER reject_placeholder_message")
            assertTrue("Rollback must restore the claim as well as the empty row", db.migrateLegacyPrefs(prefs))
            assertEquals("Recovered 字🙂", db.getChat(id)!!.getJSONArray("messages").getJSONObject(0).getString("text"))
        }
    }

    @Test fun aLaterRealConflictDoesNotConsumeAnEarlierPlaceholderClaim() {
        val conflict = "real-sql-conflict"
        val raw = source(id, conflict)
        seed(raw)
        ChatDatabase(context).use { db ->
            db.ensureChat(id, legacyMigrationPlaceholder = true)
            db.upsertCurrentChat(conflict, "Current data", null, null,
                listOf(JSONObject().put("role", "user").put("text", "Keep current SQL")))
            val original = db.getChat(conflict)!!.toString()
            assertFalse(db.migrateLegacyPrefs(prefs))
            retained(raw)
            assertEquals(original, db.getChat(conflict)!!.toString())
            assertEquals(0, db.getChat(id)!!.getJSONArray("messages").length())
            // Repair only the source conflict; the untouched placeholder stays eligible.
            seed(source(id))
            assertTrue(db.migrateLegacyPrefs(prefs))
            assertEquals(original, db.getChat(conflict)!!.toString())
        }
    }

    @Test fun preexistingTombstonedArtifactIntentPreventsAdoptionOfARecreatedId() {
        val raw = source(id)
        seed(raw)
        ChatDatabase(context).use { db ->
            // Intents deliberately have no chat FK and survive deletion.
            db.writableDatabase.execSQL("""INSERT INTO local_artifact_intents
                (chat_id,request_id,tool_call_id,tool_name,arguments_sha256,plan_sha256,path,plan,state,tombstoned,created_at,updated_at)
                VALUES (?, 'old-request','old-call','create_local_text_file','args','plan','old-path','{}','outcome_unknown',1,1,1)""",
                arrayOf(id))
            db.ensureChat(id, legacyMigrationPlaceholder = true)
            assertFalse(db.migrateLegacyPrefs(prefs))
            retained(raw)
            db.readableDatabase.rawQuery("SELECT state,tombstoned FROM local_artifact_intents WHERE chat_id=?", arrayOf(id)).use {
                assertTrue(it.moveToFirst()); assertEquals("outcome_unknown", it.getString(0)); assertEquals(1, it.getInt(1))
            }
            assertEquals(0, db.getChat(id)!!.getJSONArray("messages").length())
        }
    }

    @Test fun deletedPlaceholderCannotAuthorizeOverwritingARecreatedOrdinaryEmptyChat() {
        val raw = source(id)
        seed(raw)
        ChatDatabase(context).use { db ->
            db.ensureChat(id, legacyMigrationPlaceholder = true)
            db.deleteChat(id)
            db.ensureChat(id)
            assertFalse(db.migrateLegacyPrefs(prefs))
            retained(raw)
            assertEquals(0, db.getChat(id)!!.getJSONArray("messages").length())
        }
    }
}
