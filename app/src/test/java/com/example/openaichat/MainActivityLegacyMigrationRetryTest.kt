package com.example.openaichat

import android.app.Application
import android.content.Context
import android.os.Looper
import android.widget.EditText
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
import java.io.File
import java.util.UUID

/** Exercise the actual startup/restart path that used to permanently block a repaired import. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityLegacyMigrationRetryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val legacyId = "legacy-current-retry"
    private val malformed = "[{\"id\":\"legacy-current-retry\",\"messages\":["
    private var controller: ActivityController<MainActivity>? = null
    private val fixtureFiles = mutableListOf<File>()

    @Before fun before() {
        context.deleteDatabase("astra_chats.db")
        prefs.edit().clear().putString("current_chat", legacyId)
            .putString("provider", "openrouter").putString("model", "anthropic/claude-haiku-5.5")
            .putBoolean("response_notifications", false).putBoolean("background_responses", false).commit()
        context.getSharedPreferences("secure", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun after() {
        closeActivity()
        context.deleteDatabase("astra_chats.db")
        prefs.edit().clear().commit()
        fixtureFiles.forEach { it.delete() }
    }

    private fun launch(): MainActivity {
        // onCreate itself invokes migrateLegacyPrefs(), ensureChatStore(), and
        // restores both the SQL history and the persisted composer.
        val next = Robolectric.buildActivity(MainActivity::class.java).create()
        controller = next
        shadowOf(Looper.getMainLooper()).idle()
        return next.get()
    }

    private fun closeActivity() {
        controller?.destroy()
        controller = null
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun input(activity: MainActivity) = MainActivity::class.java.getDeclaredField("input")
        .apply { isAccessible = true }.get(activity) as EditText

    @Suppress("UNCHECKED_CAST")
    private fun displayedMessages(activity: MainActivity): List<JSONObject> =
        MainActivity::class.java.getDeclaredField("chat").apply { isAccessible = true }
            .get(activity) as List<JSONObject>

    private fun legacyMessage() = JSONObject().put("role", "assistant")
        .put("text", "Original legacy answer \u0000 字🙂  \n")
        .put("request_id", "original-legacy-request").put("provider", "openai")
        .put("requested_model", "legacy-model").put("status", "completed")
        .put("openai_instructions_sha256", "original-instructions")
        .put("openai_protocol_output", JSONArray().put(JSONObject().put("type", "reasoning")
            .put("id", "original-reasoning").put("encrypted_content", "opaque-original")))
        .put("usage", JSONObject().put("input_tokens", 7).put("output_tokens", 11))

    private fun validSource() = JSONArray().put(JSONObject().put("id", legacyId)
        .put("title", "Recovered legacy title 字🙂").put("response_id", "legacy-response")
        .put("baseline_effort", "high").put("messages", JSONArray().put(legacyMessage()))).toString()

    private fun seedSource(raw: String) {
        assertTrue(prefs.edit().putString("chat_store", raw).remove("chat_store_migrated_v7").commit())
    }

    private fun assertSourceRetained(raw: String) {
        assertEquals(raw, prefs.getString("chat_store", null))
        assertFalse(prefs.getBoolean("chat_store_migrated_v7", false))
    }

    private fun draftKey() = "composer_state_$legacyId"

    @Test fun correctedSourceImportsAfterRealStartupAndReopenWhileDraftAndOriginalStayOwned() {
        val bytes = "Original unsent attachment\n字🙂\n".toByteArray(Charsets.UTF_8)
        val file = File(context.filesDir, "legacy-retry-${UUID.randomUUID()}.txt").also {
            it.parentFile?.mkdirs(); it.writeBytes(bytes); fixtureFiles += it
        }
        val initialDraft = JSONObject().put("text", "Draft before failed import")
            .put("file_ids", JSONArray().put("draft-original"))
            .put("file_names", JSONArray().put(file.name)).put("file_paths", JSONArray().put(file.path))
            .put("file_sizes", JSONArray().put(bytes.size)).put("file_mimes", JSONArray().put("text/plain"))
        assertTrue(prefs.edit().putString(draftKey(), initialDraft.toString()).commit())
        seedSource(malformed)
        val first = launch()
        assertSourceRetained(malformed)
        assertEquals("Draft before failed import", input(first).text.toString())
        val changedDraft = "New unsent text after failed import 字🙂"
        input(first).setText(changedDraft)
        closeActivity()

        val repaired = validSource()
        seedSource(repaired)
        val reopened = launch()
        // This assertion fails on bbd5715: ensureChatStore created an ordinary
        // empty row with this ID and every subsequent import sees a conflict.
        assertTrue("A startup-created empty placeholder must not permanently block repaired history",
            prefs.getBoolean("chat_store_migrated_v7", false))
        assertFalse(prefs.contains("chat_store"))
        ChatDatabase(context).use { db ->
            val chat = db.getChat(legacyId)!!
            assertEquals("Recovered legacy title 字🙂", chat.getString("title"))
            val actual = chat.getJSONArray("messages").getJSONObject(0)
            val expected = legacyMessage()
            assertEquals(expected.getString("text"), actual.getString("text"))
            assertEquals(expected.getJSONArray("openai_protocol_output").toString(), actual.getJSONArray("openai_protocol_output").toString())
            assertEquals(expected.getJSONObject("usage").toString(), actual.getJSONObject("usage").toString())
            assertEquals(ChatMessageFingerprint.of(expected), ChatMessageFingerprint.of(actual))
        }
        assertEquals(legacyMessage().getString("text"), displayedMessages(reopened).single().getString("text"))
        assertEquals(changedDraft, input(reopened).text.toString())
        assertArrayEquals(bytes, file.readBytes())
        val savedDraft = JSONObject(prefs.getString(draftKey(), null)!!)
        assertEquals(changedDraft, savedDraft.getString("text"))
        assertTrue(file.path in LocalAttachmentReferences.composerPaths(savedDraft))
        closeActivity()
        launch()
        ChatDatabase(context).use { db -> assertEquals(1, db.getChat(legacyId)!!.getJSONArray("messages").length()) }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun realSqlHistoryAndAccountingRemainUntouchedAcrossFailedImportAndRetry() {
        val currentMessage = JSONObject().put("role", "assistant").put("text", "New SQLite answer 字🙂")
            .put("provider", "openrouter").put("request_id", "new-sql-request")
            .put("response_model", "anthropic/claude-haiku-5.5").put("status", "completed")
            .put("usage", JSONObject().put("prompt_tokens", 3).put("completion_tokens", 5))
        val before = ChatDatabase(context).use { db ->
            db.upsertCurrentChat(legacyId, "Actual current SQLite title", null, "max", listOf(currentMessage))
            assertTrue(db.startRequestAccounting(legacyId, "new-sql-request", "openrouter", "anthropic/claude-haiku-5.5", "openrouter_api_key"))
            assertTrue(db.recordRequestAccounting(legacyId, "new-sql-request", "completed", reportedCostUsd = 0.0))
            db.getChat(legacyId)!!.toString() to db.listRequestAccounting(legacyId).single().toString()
        }
        seedSource(malformed)
        launch()
        assertSourceRetained(malformed)
        closeActivity()
        val repaired = validSource()
        seedSource(repaired)
        val reopened = launch()
        assertSourceRetained(repaired)
        ChatDatabase(context).use { db ->
            assertEquals(before.first, db.getChat(legacyId)!!.toString())
            assertEquals(before.second, db.listRequestAccounting(legacyId).single().toString())
        }
        assertEquals(currentMessage.getString("text"), displayedMessages(reopened).single().getString("text"))
    }

    @Test fun anUnmarkedUserCreatedEmptyChatRemainsARealConflict() {
        ChatDatabase(context).use { it.ensureChat(legacyId, "Новый чат") }
        val repaired = validSource()
        seedSource(repaired)
        launch()
        assertSourceRetained(repaired)
        closeActivity()
        launch()
        assertSourceRetained(repaired)
        ChatDatabase(context).use { db ->
            assertEquals("Новый чат", db.getChat(legacyId)!!.getString("title"))
            assertEquals(0, db.getChat(legacyId)!!.getJSONArray("messages").length())
        }
    }

    @Test fun aRenamedStartupPlaceholderDoesNotLoseTheUsersChosenTitle() {
        seedSource(malformed)
        launch()
        closeActivity()
        ChatDatabase(context).use { it.renameChat(legacyId, "User renamed this chat 字🙂") }
        val repaired = validSource()
        seedSource(repaired)
        launch()
        assertSourceRetained(repaired)
        ChatDatabase(context).use { db ->
            assertEquals("User renamed this chat 字🙂", db.getChat(legacyId)!!.getString("title"))
            assertEquals(0, db.getChat(legacyId)!!.getJSONArray("messages").length())
        }
    }

    @Test fun aUsedThenEmptiedStartupChatDoesNotBecomeImportableAgain() {
        seedSource(malformed)
        launch()
        closeActivity()
        ChatDatabase(context).use { db ->
            val userMessage = JSONObject().put("role", "user").put("text", "User wrote this after failed import")
            db.upsertCurrentChat(legacyId, "Новый чат", null, null, listOf(userMessage))
            // Removing the message does not undo the fact that this chat was
            // claimed by the user. A timestamp/empty-row heuristic is unsafe.
            db.upsertCurrentChat(legacyId, "Новый чат", null, null, emptyList())
        }
        val repaired = validSource()
        seedSource(repaired)
        launch()
        assertSourceRetained(repaired)
        ChatDatabase(context).use { db ->
            assertEquals("Новый чат", db.getChat(legacyId)!!.getString("title"))
            assertEquals(0, db.getChat(legacyId)!!.getJSONArray("messages").length())
        }
    }

    @Test fun renamingBackToDefaultDoesNotRestorePlaceholderPermission() {
        seedSource(malformed)
        launch()
        closeActivity()
        ChatDatabase(context).use { db ->
            db.renameChat(legacyId, "User chose this title")
            db.renameChat(legacyId, "Новый чат")
        }
        val repaired = validSource()
        seedSource(repaired)
        launch()
        assertSourceRetained(repaired)
        ChatDatabase(context).use { db ->
            assertEquals("Новый чат", db.getChat(legacyId)!!.getString("title"))
            assertEquals(0, db.getChat(legacyId)!!.getJSONArray("messages").length())
        }
    }

}
