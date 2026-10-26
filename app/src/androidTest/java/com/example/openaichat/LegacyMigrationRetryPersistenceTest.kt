package com.example.openaichat

import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Native startup and SQLite coverage; no Send, provider credential changes, or inference. */
@RunWith(AndroidJUnit4::class)
class LegacyMigrationRetryPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("app", 0)
    private val chat = "legacy-retry-native-${UUID.randomUUID()}"
    private val composerKey get() = "composer_state_$chat"
    private val malformed = "[{\"id\":\"$chat\",\"messages\":["
    private val keys get() = listOf("current_chat", "model", "provider", "chat_store",
        "chat_store_migrated_v7", "response_notifications", "background_responses", composerKey)
    private var saved: Map<String, Any?> = emptyMap()
    private val files = mutableListOf<File>()

    @Before fun before() {
        val before = prefs.all
        saved = keys.associateWith { before[it] }
        assertTrue(prefs.edit().putString("current_chat", chat).putString("provider", "openrouter")
            .putString("model", "anthropic/claude-haiku-5.5").putBoolean("response_notifications", false)
            .putBoolean("background_responses", false).remove("chat_store_migrated_v7")
            .putString("chat_store", malformed).commit())
    }

    @After fun after() {
        // A failed assertion must not replace another test's preferences or history.
        try { ChatDatabase(context).use { it.deleteChat(chat) } }
        finally {
            val edit = prefs.edit()
            saved.forEach { (key, value) -> when (value) {
                null -> edit.remove(key)
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unexpected saved preference type")
            } }
            assertTrue(edit.commit())
            files.forEach { it.delete() }
        }
    }

    private fun input(activity: MainActivity) = MainActivity::class.java.getDeclaredField("input")
        .apply { isAccessible = true }.get(activity) as EditText

    private fun originalMessage() = JSONObject().put("role", "assistant").put("provider", "openai")
        .put("text", "Native recovered history 字🙂  \n").put("request_id", "legacy-native-request")
        .put("status", "completed").put("openai_instructions_sha256", "original-native-instructions")
        .put("openai_protocol_output", JSONArray().put(JSONObject().put("type", "reasoning")
            .put("id", "native-reasoning").put("encrypted_content", "original-opaque-native-data")))
        .put("usage", JSONObject().put("input_tokens", 7).put("output_tokens", 11))

    private fun repairedSource() = JSONArray().put(JSONObject().put("id", chat)
        .put("title", "Recovered native history").put("messages", JSONArray().put(originalMessage()))).toString()

    private fun repairSource(): String = repairedSource().also {
        assertTrue(prefs.edit().putString("chat_store", it).remove("chat_store_migrated_v7").commit())
    }

    @Test fun repairedSourceImportsAfterRealActivityRestartAndPreservesDraftOriginalAndMetadata() {
        val bytes = "Unsent original file\n字🙂\n".toByteArray(Charsets.UTF_8)
        val file = File.createTempFile("legacy-native-draft-", ".txt", context.filesDir).apply {
            writeBytes(bytes); files += this
        }
        val modified = file.lastModified()
        val draftText = "Unsent draft after rejected import 字🙂"
        val draft = JSONObject().put("text", "Draft before startup")
            .put("file_ids", JSONArray().put("draft-native-file"))
            .put("file_names", JSONArray().put(file.name)).put("file_paths", JSONArray().put(file.path))
            .put("file_sizes", JSONArray().put(bytes.size)).put("file_mimes", JSONArray().put("text/plain"))
        assertTrue(prefs.edit().putString(composerKey, draft.toString()).commit())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(malformed, prefs.getString("chat_store", null))
                assertFalse(prefs.getBoolean("chat_store_migrated_v7", false))
                assertEquals("Draft before startup", input(activity).text.toString())
                input(activity).setText(draftText)
            }
        }
        repairSource()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("A startup placeholder must permit the repaired import", prefs.getBoolean("chat_store_migrated_v7", false))
                assertFalse(prefs.contains("chat_store"))
                assertEquals(draftText, input(activity).text.toString())
            }
        }
        repeat(2) {
            ChatDatabase(context).use { db ->
                val restored = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(0)
                val original = originalMessage()
                assertEquals(ChatMessageFingerprint.of(original), ChatMessageFingerprint.of(restored))
                for (key in MessageMetadataCodec.allKeys)
                    assertEquals("Metadata $key", MessageMetadataCodec.serialized(original, key), MessageMetadataCodec.serialized(restored, key))
                assertEquals(original.getString("text"), restored.getString("text"))
            }
        }
        val restoredDraft = JSONObject(prefs.getString(composerKey, null)!!)
        assertEquals(draftText, restoredDraft.getString("text"))
        assertTrue(LocalAttachmentReferences.composerPaths(restoredDraft).map { File(it).canonicalFile }.contains(file.canonicalFile))
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(modified, file.lastModified())
    }

    @Test fun userMessagesAndAccountingClaimThePlaceholderAndRemainUntouchedOnRetry() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertFalse(prefs.getBoolean("chat_store_migrated_v7", false)) }
        }
        val before = ChatDatabase(context).use { db ->
            val message = JSONObject().put("role", "user").put("text", "New user history after failed migration 字🙂")
                .put("request_id", "native-new-request").put("provider", "openrouter")
            db.upsertCurrentChat(chat, "User chose this title", null, "max", listOf(message))
            assertTrue(db.startRequestAccounting(chat, "native-new-request", "openrouter", "anthropic/claude-haiku-5.5", "openrouter_api_key"))
            assertTrue(db.recordRequestAccounting(chat, "native-new-request", "completed", reportedCostUsd = 0.0))
            db.getChat(chat)!!.toString() to db.listRequestAccounting(chat).single().toString()
        }
        val raw = repairSource()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertFalse(prefs.getBoolean("chat_store_migrated_v7", false))
                assertEquals(raw, prefs.getString("chat_store", null))
            }
        }
        ChatDatabase(context).use { db ->
            assertEquals(before.first, db.getChat(chat)!!.toString())
            assertEquals(before.second, db.listRequestAccounting(chat).single().toString())
        }
    }
}
