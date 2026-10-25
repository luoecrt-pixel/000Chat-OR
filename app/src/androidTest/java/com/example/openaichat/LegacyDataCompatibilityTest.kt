package com.example.openaichat

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Native SQLite and real file-card coverage for offline, imported OpenAI history. */
@RunWith(AndroidJUnit4::class)
class LegacyDataCompatibilityTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val chat = "legacy-native-" + UUID.randomUUID()
    private val request = "legacy-native-request"
    private val files = mutableListOf<File>()

    @After fun cleanup() {
        ChatDatabase(context).use { it.deleteChat(chat) }
        files.forEach(File::delete)
    }

    @Test fun fingerprintMetadataAndToolExportSurviveNativeReopenAndResave() {
        assertEquals("f161e32fc417c9264d6a177c8246e2c8d6bb7b23c5eda841f9cbe0b285621801",
            ChatMessageFingerprint.of(JSONObject().put("role", "assistant").put("text", "hello").put("provider", "openrouter")))
        val output = JSONArray()
            .put(JSONObject().put("type", "reasoning").put("encrypted_content", "opaque-native-signed"))
            .put(JSONObject().put("type", "function_call").put("call_id", "native-call").put("name", "create_local_text_file"))
            .put(JSONObject().put("type", "function_call_output").put("call_id", "native-call")
                .put("output", JSONObject().put("ok", true).put("type", "local_file").put("filename", "native-result.md").toString()))
        val ledger = OpenAiUsageLedger.observe(null, "resp_native", JSONObject().put("input_tokens", 7), "completed")
        val original = JSONObject().put("role", "assistant").put("text", "Сохранённый ответ 😀")
            .put("provider", "openai").put("request_id", request).put("status", "completed")
            .put("openai_instructions_sha256", "legacy-exact-instructions-hash")
            .put("openai_protocol_output", output).put(OpenAiUsageLedger.FIELD, ledger)
        val fingerprint = ChatMessageFingerprint.of(original)
        ChatDatabase(context).use { db -> db.upsertCurrentChat(chat, "Imported native", null, null, listOf(original)) }
        repeat(2) {
            ChatDatabase(context).use { db ->
                val restored = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(0)
                assertEquals(fingerprint, ChatMessageFingerprint.of(restored))
                for (key in MessageMetadataCodec.allKeys)
                    assertEquals("Metadata $key", MessageMetadataCodec.serialized(original, key), MessageMetadataCodec.serialized(restored, key))
                for (format in DialogConversationExporter.Format.values()) {
                    val exported = DialogConversationExporter.export("Imported native", listOf(restored), format = format)
                    assertTrue(exported.contains("create_local_text_file — готово; файл: native-result.md"))
                    assertFalse(exported.contains("opaque-native-signed"))
                }
                db.upsertCurrentChat(chat, "Imported native", null, null, listOf(restored))
            }
        }
    }

    @Test fun typedLegacyActiveCheckpointRetiresLocallyWithExactRawArchiveAcrossReopen() {
        val reasoning = JSONObject().put("type", "reasoning").put("id", "rs_native")
            .put("encrypted_content", "opaque-native-reasoning")
        val tool = JSONObject().put("type", "web_search_call").put("id", "search_native").put("status", "completed")
        val state = ResponseEventState(responseId = "resp_native", reasoningSummary = "Received summary",
            typedOutputItems = mapOf(0 to reasoning, 1 to tool))
        val indexed = ResponseIndexedCheckpoint.serialize(state)
        val checkpoint = JSONObject().put("version", 1).put("phase", "waiting").put("response_id", "resp_native")
            .put("received_response", JSONObject().put("id", "resp_native").put("status", "completed")
                .put("usage", JSONObject().put("input_tokens", 11).put("output_tokens", 13))
                .put("output", JSONArray().put(reasoning).put(tool)))
        ChatDatabase(context).use { db ->
            db.upsertCurrentChat(chat, "Imported active", null, null, listOf(JSONObject().put("role", "user")
                .put("provider", "openai").put("request_id", request).put("text", "Original prompt")))
            assertTrue(db.startRequestAccounting(chat, request, "openai", "legacy-model", "legacy"))
            db.setActiveResponse(chat, "resp_native", request, "", "in_progress", 1L, "high", "high", false, 0, 0, false)
            db.updateActiveResponse(chat, "resp_native", "", "in_progress", 3, request, indexed)
            assertTrue(db.saveOpenAiToolCheckpoint(chat, request, checkpoint))
        }
        ChatDatabase(context).use { db ->
            // This path has no transport dependency or credential, and cannot resume a paid request.
            assertTrue(db.recoverLegacyOpenAiRequestLocally(chat, request))
            assertFalse(db.recoverLegacyOpenAiRequestLocally(chat, request))
        }
        ChatDatabase(context).use { db ->
            assertNull(db.getActiveResponse(chat))
            assertNull(db.getOpenAiToolCheckpoint(chat, request))
            val archive = db.getRequestRecoveryArchive(chat, request)!!
            assertEquals(indexed, archive.getJSONObject("legacy_active_snapshot").getString("indexed_checkpoint"))
            assertEquals(checkpoint.toString(), archive.getString("legacy_tool_checkpoint_raw"))
            val saved = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(1)
            assertEquals("", saved.getString("text"))
            assertEquals("Received summary", saved.getString("reasoning_summary"))
            assertEquals(reasoning.toString(), saved.getJSONArray("openai_protocol_output").getJSONObject(0).toString())
            assertEquals(tool.toString(), saved.getJSONArray("openai_protocol_output").getJSONObject(1).toString())
            assertFalse(OpenRouterResponseRuntime.contains(request))
            assertEquals(13, db.listRequestAccounting(chat).single().getInt("output_tokens"))
        }
    }

    @Test fun existingLocalFileIdRestoresReadyCardWhileMissingIdNeverUsesSameFilename() {
        val existing = File.createTempFile("legacy-native-", ".txt", context.filesDir).apply {
            writeText("Saved original bytes 😀"); files += this
        }
        val fileId = "file-native-" + UUID.randomUUID()
        val source = JSONObject().put("type", "file_citation").put("file_id", fileId).put("filename", "report.txt")
            .put("state", "EXPIRED").put("local_path", existing.path).put("mime_type", "text/plain")
        ChatDatabase(context).use { db ->
            db.upsertCurrentChat(chat, "Local files", null, null, listOf(JSONObject().put("role", "assistant")
                .put("text", "Old answer").put("annotations", JSONArray().put(source))))
        }
        ChatDatabase(context).use { db ->
            assertEquals(existing.path, db.findLocalFileByFileId(fileId)!!.getString("path"))
            assertNull(db.findLocalFileByFileId("missing-$fileId"))
        }
        val prefs = context.getSharedPreferences("app", 0)
        val keys = listOf("current_chat", "model", "response_notifications")
        val previous = keys.associateWith { prefs.all[it] }
        assertTrue(prefs.edit().putString("current_chat", chat).putString("model", "anthropic/claude-sonnet-5.5")
            .putBoolean("response_notifications", false).commit())
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val stale = JSONObject().put("type", "file_citation").put("file_id", fileId)
                        .put("filename", "report.txt").put("state", "EXPIRED")
                    val card = LinearLayout(activity)
                    val render = MainActivity::class.java.getDeclaredMethod("addGeneratedFileCard", JSONObject::class.java,
                        LinearLayout::class.java, JSONObject::class.java, String::class.java, Long::class.javaPrimitiveType!!)
                        .apply { isAccessible = true }
                    render.invoke(activity, stale, card, JSONObject().put("role", "assistant"), chat, 0L)
                    assertEquals("READY", stale.getString("state"))
                    assertEquals(existing.path, stale.getString("local_path"))
                    assertTrue(texts(card).contains("Сохранить"))
                    val missing = JSONObject().put("type", "file_citation").put("file_id", "missing-$fileId")
                        .put("filename", "report.txt").put("state", "EXPIRED")
                    val missingCard = LinearLayout(activity)
                    render.invoke(activity, missing, missingCard, JSONObject().put("role", "assistant"), chat, 0L)
                    assertFalse(missing.has("local_path"))
                    assertFalse(texts(missingCard).contains("Сохранить"))
                    assertTrue(texts(missingCard).any { it.contains("Загрузка из прямого OpenAI API отключена") })
                }
            }
        } finally {
            val restore = prefs.edit()
            previous.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value)
                is Boolean -> restore.putBoolean(key, value)
                else -> restore.remove(key)
            } }
            assertTrue(restore.commit())
        }
        assertEquals("Saved original bytes 😀", existing.readText())
        ChatDatabase(context).use { db ->
            val restored = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(0).getJSONArray("annotations").getJSONObject(0)
            assertEquals(existing.path, restored.getString("local_path"))
            assertEquals("READY", restored.getString("state"))
        }
    }

    private fun texts(view: View): List<String> = buildList {
        if (view is TextView) add(view.text.toString())
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(texts(view.getChildAt(i)))
    }
}
