package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import okio.Buffer
import java.io.File
import java.io.IOException
import java.nio.charset.Charset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoricalAttachmentRepairTest {
    @Test fun selectiveRepairPreservesAnswersAndRejectsStaleMutation() {
        val context = RuntimeEnvironment.getApplication()
        val id = "attachment-cas-review"
        ChatDatabase(context).use { db ->
            try {
                val user = JSONObject().put("role", "user").put("text", "Question").put("request_id", "old")
                    .put("file_ids", JSONArray().put("file_missing").put("file_keep"))
                    .put("file_names", JSONArray().put("missing.txt").put("keep.txt"))
                    .put("file_paths", JSONArray().put("").put("/owned/keep.txt"))
                val answer = JSONObject().put("role", "assistant").put("text", "Existing answer")
                    .put("openrouter_protocol", JSONObject().put("version", 1).put("tail", JSONArray()
                        .put(JSONObject().put("role", "assistant").put("content", "signed history"))))
                db.upsertCurrentChat(id, "Repair", "resp_old", "max", listOf(user, answer))
                val original = db.getChat(id)!!.getJSONArray("messages").getJSONObject(0)
                val removed = AttachmentMessageState.remove(original, fileIndex = 0)
                assertTrue(db.updateMessageAttachments(id, original, removed, true))
                assertFalse(db.updateMessageAttachments(id, original, removed, true))
                val restored = db.getChat(id)!!
                assertTrue(restored.isNull("response_id"))
                val rows = restored.getJSONArray("messages")
                assertEquals(2, rows.length())
                assertEquals("file_keep", rows.getJSONObject(0).getJSONArray("file_ids").getString(0))
                assertEquals("Existing answer", rows.getJSONObject(1).getString("text"))
                assertTrue(rows.getJSONObject(1).has("openrouter_protocol"))
                assertFalse(OpenRouterProtocolCodec.append(JSONArray(), rows.getJSONObject(1)))
            } finally { db.deleteChat(id) }
        }
    }

    @Test fun migration22KeepsSignedProtocolAndOldText() {
        val context = RuntimeEnvironment.getApplication()
        val sql = android.database.sqlite.SQLiteDatabase.create(null)
        try {
            sql.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY, text TEXT, openrouter_protocol TEXT)")
            sql.execSQL("INSERT INTO messages VALUES(1,'answer','{signature}')")
            ChatDatabase(context).use { it.onUpgrade(sql, 21, 22) }
            sql.rawQuery("SELECT text,openrouter_protocol,protocol_context_state FROM messages", null).use {
                assertTrue(it.moveToFirst()); assertEquals("answer", it.getString(0)); assertEquals("{signature}", it.getString(1)); assertTrue(it.isNull(2))
            }
        } finally { sql.close() }
    }

    @Test fun cacheOnlyWritePreservesConcurrentTailAndCannotChangeOriginalReferences() {
        val context = RuntimeEnvironment.getApplication()
        val id = "attachment-cache-review"
        ChatDatabase(context).use { db ->
            try {
                val user = JSONObject().put("role", "user").put("text", "keep").put("request_id", "r")
                    .put("file_paths", JSONArray().put("/owned/source.txt"))
                db.upsertCurrentChat(id, "Cache", null, null, listOf(user))
                val before = db.getChat(id)!!.getJSONArray("messages").getJSONObject(0)
                val updated = JsonProtocol.objectCopy(before).put("attachments", JSONArray().put(JSONObject()
                    .put("path", "/owned/source.txt").put("text_path", "/owned/derived.utf8")))
                db.setActiveResponse(id, null, "r", "", "in_progress", 1L, "max", "max", false, 0, 0, true)
                assertTrue(db.updateMessageAttachments(id, before, updated, false))
                assertEquals("/owned/derived.utf8", db.cachedAttachmentManifest(id, before)!!.getJSONObject(0).getString("text_path"))
                assertFalse(db.updateMessageAttachments(id, updated, AttachmentMessageState.remove(updated, fileIndex = 0), true))
                val changedSource = JsonProtocol.objectCopy(updated).put("file_paths", JSONArray().put("/another.txt"))
                assertFalse(db.updateMessageAttachments(id, updated, changedSource, false))
                val answer = JSONObject().put("role", "assistant").put("text", "worker result").put("request_id", "r")
                assertTrue(db.finalizeCurrentChatIfActiveRequest(id, "Cache", null, null, listOf(user, answer), "r"))
                val rows = db.getChat(id)!!.getJSONArray("messages")
                assertEquals("worker result", rows.getJSONObject(rows.length() - 1).getString("text"))
                assertEquals("/owned/derived.utf8", rows.getJSONObject(0).getJSONArray("attachments").getJSONObject(0).getString("text_path"))
            } finally { db.deleteChat(id) }
        }
    }

    @Test fun manifestReuseAvoidsNewDerivedFilesAndSelectedDecoderSurvivesEviction() {
        val dir = File(RuntimeEnvironment.getApplication().cacheDir, "manifest-review").apply { mkdirs() }
        val source = File(dir, "input.txt").apply { writeBytes("Философия".toByteArray(Charset.forName("KOI8-R"))) }
        try {
            val item = OpenRouterAttachmentStore.classifyLocalFile(source, "input.txt", "text/plain")
            val selected = OpenRouterAttachmentStore.withTextEncoding(item, "KOI8-R")
            assertFalse(selected.encodingGuessed)
            assertEquals("Философия", File(selected.textPath!!).readText())
            val message = JSONObject().put("role", "user").put("file_paths", JSONArray().put(source.path))
                .put("file_names", JSONArray().put("input.txt")).put("attachments", JSONArray().put(selected.toJson()))
            val count = dir.listFiles()!!.size
            LocalArtifactAttachmentResolver.resolveForTools(RuntimeEnvironment.getApplication(), selected)
            LocalArtifactAttachmentResolver.resolveForTools(RuntimeEnvironment.getApplication(), selected)
            assertEquals(count, dir.listFiles()!!.size)
            File(selected.textPath!!).delete()
            val restored = LocalArtifactAttachmentResolver.resolveForTools(RuntimeEnvironment.getApplication(), selected)
            assertEquals("KOI8-R", restored.textEncoding)
            assertEquals("Философия", File(restored.textPath!!).readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun fullLargeTextStreamsWithCorrectEscapingLengthAndMutationDetection() {
        val source = File(RuntimeEnvironment.getApplication().cacheDir, "large-text-review.utf8")
        val text = "Философия 科学 😀 \"\\\n\t\u001B\n".repeat(10000)
        source.writeText(text)
        try {
            val value = FileBackedText(source, "<document>\n", "\n</document>")
            val body = StreamingJsonRequestBody(JSONObject().put("text", value))
            val wire = Buffer(); body.writeTo(wire)
            assertEquals(wire.size, body.contentLength())
            assertEquals("<document>\n$text\n</document>", JSONObject(wire.readUtf8()).getString("text"))
            source.writeText(text.replaceFirst("Ф", "Ж"))
            assertThrows(IOException::class.java) { body.writeTo(Buffer()) }
        } finally { source.delete() }
    }
}
