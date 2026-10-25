package com.example.openaichat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Native coverage for read-only legacy source files after removing the streaming transport. */
@RunWith(AndroidJUnit4::class)
class RawResponseRecoveryPersistenceTest {
    private fun source(filesDir: File, chat: String, request: String, bytes: ByteArray): File {
        val owner = MessageDigest.getInstance("SHA-256").digest((chat + "\u0000" + request).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(filesDir, "openai_received_sse").apply { mkdirs() }, owner + "_fixture.sse").apply { writeBytes(bytes) }
    }

    @Test fun existingReceivedBytesBecomeOwnedLocalCardsAcrossOfflineRecoveryAndReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chat = "legacy-source-" + UUID.randomUUID()
        val request = "local_source"
        val bytes = "received opaque bytes\u0000😀".toByteArray()
        val file = source(context.filesDir, chat, request, bytes)
        try {
            ChatDatabase(context).use { db ->
                db.ensureChat(chat, "Old source")
                db.startRequestAccounting(chat, request, "openai", "legacy-model", "legacy")
                db.setActiveResponse(chat, "resp_source", request, "partial", "in_progress", 1L,
                    "high", "high", false, 7, 0, true)
                assertTrue(db.recoverLegacyOpenAiRequestLocally(chat, request))
            }
            ChatDatabase(context).use { db ->
                val message = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(0)
                assertEquals(file.absolutePath, message.getJSONArray("annotations").getJSONObject(0).getString("local_path"))
                assertTrue(db.listAllLocalFilePaths().contains(file.absolutePath))
                assertArrayEquals(bytes, file.readBytes())
                db.deleteChat(chat)
                assertFalse(db.listAllLocalFilePaths().contains(file.absolutePath))
            }
        } finally { file.delete(); ChatDatabase(context).use { it.deleteChat(chat) } }
    }

    @Test fun oldRequestCannotAttachItsSourceToAReplacementOwner() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chat = "legacy-source-switch-" + UUID.randomUUID()
        val old = "local_old"
        val file = source(context.filesDir, chat, old, "old bytes".toByteArray())
        try {
            ChatDatabase(context).use { db ->
                db.ensureChat(chat)
                db.startRequestAccounting(chat, old, "openai", "legacy-model", "legacy")
                db.setActiveResponse(chat, "resp_new", "local_new", "new partial", "in_progress", 2L,
                    "high", "high", false, 11, 0, true)
                assertFalse(db.recoverLegacyOpenAiRequestLocally(chat, old))
                assertEquals("local_new", db.getActiveResponse(chat)!!.getString("request_id"))
                assertNull(db.getRequestRecoveryArchive(chat, old))
                val message = JSONObject()
                LegacyOpenAiCheckpoint.attachRawSourceToMessage(message, context.filesDir, chat, "local_new")
                assertFalse(message.has("annotations"))
            }
        } finally { file.delete(); ChatDatabase(context).use { it.deleteChat(chat) } }
    }
}
