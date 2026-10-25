package com.example.openaichat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TombstonedArtifactCleanupDatabaseTest {
    private lateinit var context: Context
    private val files = mutableListOf<File>()
    private val chat = "cleanup-owner"
    private val request = "cleanup-request"
    private val call = "actual-cleanup-call"
    private val tool = "create_local_text_file"
    private fun arguments() = JSONObject().put("filename", "keep.txt").put("content", "published original")
    private fun argsHash() = StreamingJsonRequestBody.sha256(arguments())

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }
    @After fun cleanup() {
        files.forEach(File::delete)
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }

    private fun intent(db: ChatDatabase, tombstone: Boolean = true): JSONObject {
        db.ensureChat(chat, "Original")
        db.startRequestAccounting(chat, request, "openrouter", "test-model", "inert-key")
        db.setActiveResponse(chat, null, request, "", "in_progress", 1L, "high", "high", false, 0, 0, false)
        LocalArtifactToolExecutor(context).prepareTextFile(arguments(), "$chat\u0000$request\u0000$call").use { plan ->
            val claim = db.beginLocalArtifactIntentIfActive(chat, request, call, tool, argsHash(), plan.snapshot)!!
            val published = plan.publish()
            files += File(published.getString("path"))
            db.completeLocalArtifactIntent(chat, request, call, tool, argsHash(), claim.getString("plan_sha256"), published)
        }
        if (tombstone) db.deleteChat(chat)
        return db.getLocalArtifactIntent(chat, request, call)!!
    }

    private fun guarded(db: ChatDatabase, record: JSONObject, action: (JSONObject, Set<String>) -> Boolean): Boolean =
        db.withTombstonedArtifactCleanup(chat, request, call, tool, argsHash(), record.getString("plan_sha256"),
            record.getLong("updated_at"), action)

    private fun sharedMessage(db: ChatDatabase, value: JSONObject) {
        db.ensureChat("shared", "Shared")
        db.upsertCurrentChat("shared", "Shared", null, "high", listOf(value.put("role", "user").put("text", "Keep original")))
    }

    @Test fun callbackRequiresExactTombstoneTimestampAndIdentityAndNeverDeletesLedgerRow() {
        ChatDatabase(context).use { db ->
            val active = intent(db, tombstone = false)
            var invoked = false
            assertFalse(guarded(db, active) { _, _ -> invoked = true; true })
            db.deleteChat(chat)
            val tombstone = db.getLocalArtifactIntent(chat, request, call)!!
            assertFalse(db.withTombstonedArtifactCleanup(chat, request, call, tool, argsHash(), tombstone.getString("plan_sha256"),
                tombstone.getLong("updated_at") + 1) { _, _ -> invoked = true; true })
            try {
                db.withTombstonedArtifactCleanup(chat, request, call, tool, StreamingJsonRequestBody.sha256("different"),
                    tombstone.getString("plan_sha256"), tombstone.getLong("updated_at")) { _, _ -> invoked = true; true }
                fail("Changed immutable identity must fail closed")
            } catch (_: IllegalStateException) { }
            assertFalse(invoked)
            assertTrue(guarded(db, tombstone) { saved, _ ->
                assertEquals(tombstone.toString(), saved.toString()); true
            })
            assertTrue(db.getLocalArtifactIntent(chat, request, call)!!.getBoolean("tombstoned"))
            assertNull(db.getChat(chat))
        }
    }

    @Test fun canonicalAliasInAnotherChatPreventsCallback() {
        ChatDatabase(context).use { db ->
            val record = intent(db)
            val target = File(record.getString("path"))
            val alias = File(target.parentFile, "../generated/${target.name}").path
            sharedMessage(db, JSONObject().put("file_paths", JSONArray().put(alias)))
            assertFalse(guarded(db, record) { _, _ -> fail("Shared canonical original must not be unlinked"); true })
            assertEquals("published original", target.readText())
        }
    }

    @Test fun persistedOpenRouterCheckpointAnnotationProtectsSharedFile() {
        ChatDatabase(context).use { db ->
            val record = intent(db)
            db.ensureChat("shared", "Shared checkpoint")
            db.setActiveResponse("shared", null, "shared-request", "", "in_progress", 1L,
                "high", "high", false, 0, 0, false)
            assertTrue(db.saveOpenRouterCheckpoint("shared", "shared-request", JSONObject().put("version", 1)
                .put("tail", JSONArray().put(JSONObject().put("role", "assistant")
                    .put("annotations", JSONArray().put(JSONObject().put("local_path", record.getString("path"))))))))
            assertFalse(guarded(db, record) { _, _ -> fail("Checkpoint still owns the file"); true })
        }
    }

    @Test fun rawArchivedColumnsAndLegacyCheckpointKeepTheirReferencedFiles() {
        ChatDatabase(context).use { db ->
            val record = intent(db)
            db.ensureChat("shared", "Shared archive")
            db.writableDatabase.insertOrThrow("request_recovery_archive", null, ContentValues().apply {
                put("chat_id", "shared"); put("request_id", "archive-owner"); put("created_at", 1L)
                put("recovery_message", JSONObject().put("legacy_stored_message_columns", JSONObject()
                    .put("file_paths", JSONArray().put(record.getString("path")).toString())).toString())
            })
            assertFalse(guarded(db, record) { _, _ -> fail("Raw archived original must remain"); true })
            db.writableDatabase.delete("request_recovery_archive", null, null)
            db.writableDatabase.insertOrThrow("openai_tool_checkpoints", null, ContentValues().apply {
                put("chat_id", "shared"); put("request_id", "legacy-owner")
                put("request_checkpoint", JSONObject().put("attachments", JSONArray().put(JSONObject()
                    .put("path", record.getString("path")))).toString())
            })
            assertFalse(guarded(db, record) { _, _ -> fail("Legacy checkpoint still owns the file"); true })
        }
    }

    @Test fun damagedDurableOwnershipAbortsBeforeCallback() {
        ChatDatabase(context).use { db ->
            val record = intent(db)
            sharedMessage(db, JSONObject())
            db.writableDatabase.execSQL("UPDATE messages SET attachments=? WHERE chat_id='shared'", arrayOf("{}"))
            var invoked = false
            try { guarded(db, record) { _, _ -> invoked = true; true }; fail("Unknown ownership must stop cleanup") }
            catch (_: JSONException) { }
            assertFalse(invoked)
            assertTrue(File(record.getString("path")).isFile)
        }
    }

    @Test fun archivedRawIndexedCheckpointProtectsItsOnlyFileReferenceAndCorruptionStopsCleanup() {
        ChatDatabase(context).use { db ->
            val record = intent(db)
            db.ensureChat("shared", "Legacy indexed archive")
            val indexed = ResponseIndexedCheckpoint.serialize(ResponseEventState(typedOutputItems = mapOf(
                0 to JSONObject().put("type", "message").put("annotations", JSONArray().put(JSONObject()
                    .put("local_path", record.getString("path")))))))
            val archive = JSONObject().put("legacy_active_snapshot", JSONObject().put("indexed_checkpoint", indexed))
            db.writableDatabase.insertOrThrow("request_recovery_archive", null, ContentValues().apply {
                put("chat_id", "shared"); put("request_id", "indexed-owner"); put("created_at", 1L)
                put("recovery_message", archive.toString())
            })
            assertFalse(guarded(db, record) { _, _ -> fail("Indexed raw output still owns the file"); true })
            archive.getJSONObject("legacy_active_snapshot").put("indexed_checkpoint", "{damaged original")
            db.writableDatabase.execSQL("UPDATE request_recovery_archive SET recovery_message=? WHERE chat_id='shared'",
                arrayOf(archive.toString()))
            var invoked = false
            try { guarded(db, record) { _, _ -> invoked = true; true }; fail("Unknown indexed ownership must stop cleanup") }
            catch (_: JSONException) { }
            assertFalse(invoked)
            assertEquals("published original", File(record.getString("path")).readText())
        }
    }

    @Test fun cleanupTransactionSerializesDurableWriterAndNeverRecreatesDeletedOwner() {
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writerStarted = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        try {
            ChatDatabase(context).use { db ->
                val record = intent(db)
                ChatDatabase(context).use { writerDb ->
                    writerDb.writableDatabase // Open independently before the guarded transaction begins.
                    val cleanup = pool.submit<Boolean> {
                        guarded(db, record) { _, refs ->
                            assertFalse(record.getString("path") in refs)
                            entered.countDown()
                            assertTrue(release.await(5, TimeUnit.SECONDS))
                            true
                        }
                    }
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    val writer = pool.submit {
                        writerStarted.countDown()
                        try { sharedMessage(writerDb, JSONObject().put("file_paths", JSONArray().put(record.getString("path")))) }
                        finally { writerFinished.countDown() }
                    }
                    assertTrue(writerStarted.await(5, TimeUnit.SECONDS))
                    assertFalse(writerFinished.await(100, TimeUnit.MILLISECONDS))
                    release.countDown()
                    assertTrue(cleanup.get(5, TimeUnit.SECONDS))
                    writer.get(5, TimeUnit.SECONDS)
                    assertNull(db.getChat(chat))
                    assertTrue(db.getLocalArtifactIntent(chat, request, call)!!.getBoolean("tombstoned"))
                    assertFalse(guarded(db, record) { _, _ -> fail("New durable owner must protect the file"); true })
                }
            }
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
