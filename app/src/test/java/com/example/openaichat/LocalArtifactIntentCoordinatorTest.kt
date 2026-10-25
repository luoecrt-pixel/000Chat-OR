package com.example.openaichat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
class LocalArtifactIntentCoordinatorTest {
    private lateinit var context: Context
    private val chat = "artifact-journal"
    private val request = "artifact-request"
    private val call = "provider-call-actual"
    private fun args() = JSONObject().put("filename", "result.txt").put("content", "paid tool output")
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
        File(context.filesDir, "generated").deleteRecursively()
    }
    @After fun cleanup() {
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
        File(context.filesDir, "generated").deleteRecursively()
    }
    private fun start(db: ChatDatabase) {
        db.ensureChat(chat, "Artifacts")
        db.setActiveResponse(chat, null, request, "", "in_progress", 1L,
            "high", "high", false, 0, 0, false)
        assertTrue(db.startRequestAccounting(chat, request, "openrouter", "claude-test", "key"))
    }
    private fun coordinator(db: ChatDatabase) = LocalArtifactIntentCoordinator(db,
        LocalArtifactToolExecutor(context), chat, request) { db.isActiveRequest(chat, request) }
    private fun expectUnknown(action: () -> Unit) {
        try { action(); fail("Unsafe outcome must stop execution") }
        catch (_: LocalArtifactOutcomeUnknownException) { }
    }

    @Test fun sqliteCompletionFailureRetainsFileAndReopenAdoptsWithoutSecondWrite() {
        var path = ""
        var modified = 0L
        ChatDatabase(context).use { db ->
            start(db)
            db.writableDatabase.execSQL("CREATE TRIGGER deny_artifact_completion BEFORE UPDATE OF state ON local_artifact_intents WHEN NEW.state='completed' BEGIN SELECT RAISE(ABORT,'completion failed'); END")
            expectUnknown { coordinator(db).execute(call, "create_local_text_file", args(), emptyList()) }
            val intent = db.getLocalArtifactIntent(chat, request, call)!!
            assertEquals("outcome_unknown", intent.getString("state"))
            path = intent.getJSONObject("plan").getJSONObject("artifact").getString("path")
            assertEquals("paid tool output", File(path).readText())
            assertTrue(File(path).setLastModified(123456000L))
            modified = File(path).lastModified()
            db.writableDatabase.execSQL("DROP TRIGGER deny_artifact_completion")
        }
        ChatDatabase(context).use { db ->
            val outcome = coordinator(db).execute(call, "create_local_text_file", args(), emptyList())
            assertEquals(path, outcome.getString("path"))
            assertEquals(modified, File(path).lastModified())
            assertEquals("completed", db.getLocalArtifactIntent(chat, request, call)!!.getString("state"))
            assertEquals(1, db.listLocalFiles(chat).count { it.optString("path") == path })
        }
    }

    @Test fun missingPublishedTargetNeverRepublishesPreparedContent() {
        ChatDatabase(context).use { db ->
            start(db)
            val tools = LocalArtifactToolExecutor(context)
            val snapshot = tools.prepareTextFile(args(), "same-key").use { it.snapshot }
            assertNotNull(db.beginLocalArtifactIntentIfActive(chat, request, call, "create_local_text_file",
                StreamingJsonRequestBody.sha256(args()), snapshot))
            expectUnknown { coordinator(db).execute(call, "create_local_text_file", args(), emptyList()) }
            assertFalse(File(snapshot.getJSONObject("artifact").getString("path")).exists())
            assertEquals("outcome_unknown", db.getLocalArtifactIntent(chat, request, call)!!.getString("state"))
        }
    }

    @Test fun deniedSqlOwnerClaimPublishesNothingAndReleasesStaging() {
        ChatDatabase(context).use { db ->
            db.ensureChat(chat, "No active owner")
            val before = context.cacheDir.listFiles()?.filter { it.name.startsWith("artifact_text_") }?.map { it.path }?.toSet().orEmpty()
            val stale = LocalArtifactIntentCoordinator(db, LocalArtifactToolExecutor(context), chat, request) { true }
            expectUnknown { stale.execute(call, "create_local_text_file", args(), emptyList()) }
            assertNull(db.getLocalArtifactIntent(chat, request, call))
            assertTrue(File(context.filesDir, "generated").listFiles().orEmpty().isEmpty())
            assertEquals(before, context.cacheDir.listFiles()?.filter { it.name.startsWith("artifact_text_") }?.map { it.path }?.toSet().orEmpty())
        }
    }

    @Test fun previouslyAuthorizedPublishedFileCanFinishAfterStopWithoutRevivingRequest() {
        ChatDatabase(context).use { db ->
            start(db)
            LocalArtifactToolExecutor(context).prepareTextFile(args(), "stop-key").use { plan ->
                assertNotNull(db.beginLocalArtifactIntentIfActive(chat, request, call, "create_local_text_file",
                    StreamingJsonRequestBody.sha256(args()), plan.snapshot))
                val published = plan.publish()
                db.clearActiveResponse(chat)
                val outcome = coordinator(db).execute(call, "create_local_text_file", args(), emptyList())
                assertEquals(published.getString("path"), outcome.getString("path"))
                assertFalse(db.isActiveRequest(chat, request))
                assertEquals("completed", db.getLocalArtifactIntent(chat, request, call)!!.getString("state"))
            }
        }
    }
}
