package com.example.openaichat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalArtifactCleanupTest {
    private lateinit var context: Context
    private val now = System.currentTimeMillis()
    private val old = now - 10L * 24 * 60 * 60 * 1000
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
        File(context.filesDir, "generated").deleteRecursively()
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() {
        File(context.filesDir, "generated").deleteRecursively()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun tombstoned(db: ChatDatabase, id: String = "cleanup"): File {
        db.ensureChat(id, "Cleanup")
        db.setActiveResponse(id, null, "req-$id", "", "in_progress", 1L, "high", "high", false, 0, 0, false)
        assertTrue(db.startRequestAccounting(id, "req-$id", "openrouter", "claude-test", "key"))
        val args = JSONObject().put("filename", "result.txt").put("content", "published once")
        val file = LocalArtifactToolExecutor(context).prepareTextFile(args, id).use { plan ->
            assertNotNull(db.beginLocalArtifactIntentIfActive(id, "req-$id", "call", "create_local_text_file",
                StreamingJsonRequestBody.sha256(args), plan.snapshot))
            File(plan.publish().getString("path"))
        }
        db.deleteChat(id)
        db.writableDatabase.execSQL("UPDATE local_artifact_intents SET updated_at=? WHERE chat_id=?", arrayOf(old, id))
        assertTrue(file.setLastModified(old))
        return file
    }

    @Test fun deletesOnlyVerifiedOldPublicationAndRetainsTombstone() {
        ChatDatabase(context).use { db ->
            val file = tombstoned(db)
            val unrelated = File(file.parentFile, "unrelated.txt").apply { writeText("original"); setLastModified(old) }
            assertEquals(1, LocalArtifactCleanup(context, db).run(now))
            assertFalse(file.exists())
            assertEquals("original", unrelated.readText())
            assertTrue(db.getLocalArtifactIntent("cleanup", "req-cleanup", "call")!!.getBoolean("tombstoned"))
            assertNull(db.getChat("cleanup"))
            assertEquals(0, LocalArtifactCleanup(context, db).run(now))
        }
    }

    @Test fun openUiAndPreparationLeasesDeferDeletionUntilClosed() {
        ChatDatabase(context).use { db ->
            val file = tombstoned(db)
            LocalArtifactOwnership.lease().use { assertEquals(0, LocalArtifactCleanup(context, db).run(now)) }
            val args = JSONObject().put("filename", "staging.txt").put("content", "prepared")
            LocalArtifactToolExecutor(context).prepareTextFile(args, "other").use {
                assertEquals(0, LocalArtifactCleanup(context, db).run(now))
                assertTrue(file.exists())
            }
            val lease = PreparedAttachmentFiles.lease(context)
            try {
                assertEquals(0, LocalArtifactCleanup(context, db).run(now))
            } finally { lease.defer() }
            assertEquals(1, LocalArtifactCleanup(context, db).run(now))
        }
    }

    @Test fun persistedComposerAndCanonicalAliasKeepSharedBytes() {
        ChatDatabase(context).use { db ->
            val file = tombstoned(db)
            val alias = File(file.parentFile, "./" + file.name).path
            val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
            prefs.edit().putString("composer_state_other", JSONObject().put("file_paths", JSONArray().put(alias)).toString()).commit()
            assertEquals(0, LocalArtifactCleanup(context, db).run(now))
            assertEquals("published once", file.readText())
            prefs.edit().clear().commit()
            assertEquals(1, LocalArtifactCleanup(context, db).run(now))
        }
    }

    @Test fun changedBytesAndFreshTombstoneAreNeverDeleted() {
        ChatDatabase(context).use { db ->
            val changed = tombstoned(db, "changed").apply { writeText("user changed bytes"); setLastModified(old) }
            val recent = tombstoned(db, "recent")
            db.writableDatabase.execSQL("UPDATE local_artifact_intents SET updated_at=? WHERE chat_id='recent'", arrayOf(now))
            assertEquals(0, LocalArtifactCleanup(context, db).run(now))
            assertEquals("user changed bytes", changed.readText())
            assertTrue(recent.exists())
        }
    }

    @Test fun malformedComposerOwnershipStopsCleanup() {
        ChatDatabase(context).use { db ->
            val file = tombstoned(db)
            context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putString("composer_state_other",
                JSONObject().put("openrouter_attachments", JSONObject().put("path", file.path)).toString()).commit()
            assertThrows(IllegalStateException::class.java) { LocalArtifactCleanup(context, db).run(now) }
            assertEquals("published once", file.readText())
        }
    }

    @Test fun patchSidecarAndTargetAreRetainedTogetherUntilBothVerify() {
        ChatDatabase(context).use { db ->
            db.ensureChat("patch", "Patch")
            db.setActiveResponse("patch", null, "req", "", "in_progress", 1L, "high", "high", false, 0, 0, false)
            assertTrue(db.startRequestAccounting("patch", "req", "openrouter", "claude-test", "key"))
            val tools = LocalArtifactToolExecutor(context)
            val sourceMetadata = tools.createTextFile(JSONObject().put("filename", "source.txt").put("content", "original"))
            val source = OpenRouterLocalAttachment.fromJson(sourceMetadata.getJSONObject("attachment"))
            val arguments = JSONObject().put("file_id", source.id).put("patches", JSONArray()
                .put(JSONObject().put("search", "original").put("replace", "changed")))
            lateinit var target: File
            lateinit var diff: File
            lateinit var document: String
            tools.preparePatchLocalFile(arguments, listOf(source), "patch-key").use { plan ->
                assertNotNull(db.beginLocalArtifactIntentIfActive("patch", "req", "call", "patch_local_file",
                    StreamingJsonRequestBody.sha256(arguments), plan.snapshot))
                val outcome = plan.publish()
                target = File(outcome.getString("path")); diff = File(outcome.getString("patch_diff_path"))
                document = diff.readText()
            }
            db.deleteChat("patch")
            db.writableDatabase.execSQL("UPDATE local_artifact_intents SET updated_at=? WHERE chat_id='patch'", arrayOf(old))
            target.setLastModified(old)
            diff.writeText("mismatched sidecar"); diff.setLastModified(old)
            assertEquals(0, LocalArtifactCleanup(context, db).run(now))
            assertEquals("changed", target.readText())
            diff.writeText(document); diff.setLastModified(old)
            assertEquals(2, LocalArtifactCleanup(context, db).run(now))
            assertFalse(target.exists()); assertFalse(diff.exists())
            assertEquals("original", File(source.path).readText())
        }
    }
}
