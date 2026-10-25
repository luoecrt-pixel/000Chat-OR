package com.example.openaichat

import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalArtifactPublicationPlanTest {
    @Test fun preparationAndDeniedPublicationLeaveNoFinalFileAndCloseReclaimsStaging() {
        val context = RuntimeEnvironment.getApplication()
        val executor = LocalArtifactToolExecutor(context)
        val cacheBefore = context.cacheDir.listFiles().orEmpty().map { it.name }.toSet()
        val plan = executor.prepareTextFile(JSONObject().put("filename", "stage.txt").put("content", "full original"), "stage-" + System.nanoTime())
        val snapshot = plan.snapshot
        val target = File(snapshot.getJSONObject("publication").getString("target_path"))
        try {
            assertFalse(target.exists())
            assertFalse(snapshot.toString().contains("full original"))
            plan.snapshot.getJSONObject("artifact").put("path", "changed by caller")
            assertEquals(target.path, plan.snapshot.getJSONObject("artifact").getString("path"))
            assertThrows(IOException::class.java) { plan.publish { false } }
            assertFalse(target.exists())
        } finally { plan.close() }
        assertEquals(cacheBefore, context.cacheDir.listFiles().orEmpty().map { it.name }.toSet())
    }

    @Test fun closeAndRecoveryPreservePublishedBytesWithoutRewritingAndMissingTargetStaysMissing() {
        val context = RuntimeEnvironment.getApplication()
        val executor = LocalArtifactToolExecutor(context)
        val plan = executor.prepareTextFile(JSONObject().put("filename", "durable.txt").put("content", "published bytes"), "durable-" + System.nanoTime())
        val snapshot = plan.snapshot
        val target = File(snapshot.getJSONObject("publication").getString("target_path"))
        try {
            val result = plan.publish()
            plan.close()
            assertTrue(target.isFile)
            val modified = target.lastModified()
            assertEquals(result.toString(), executor.recoverPublished(snapshot)!!.toString())
            assertEquals(modified, target.lastModified())
            assertEquals("published bytes", target.readText())
            assertTrue(target.delete())
            assertNull(executor.recoverPublished(snapshot))
            assertFalse(target.exists())
        } finally { plan.close(); target.delete() }
    }

    @Test fun recoveryRestoresOnlyExactDiffMetadataAndRejectsChangedTargetWithoutDeletingIt() {
        val context = RuntimeEnvironment.getApplication()
        val executor = LocalArtifactToolExecutor(context)
        val original = executor.createTextFile(JSONObject().put("filename", "original.txt").put("content", "old"))
        val source = OpenRouterLocalAttachment.fromJson(original.getJSONObject("attachment"))
        val arguments = JSONObject().put("file_id", source.id).put("patches", JSONArray()
            .put(JSONObject().put("search", "old").put("replace", "new")))
        val plan = executor.preparePatchLocalFile(arguments, listOf(source), "patch-recovery-" + System.nanoTime())
        val snapshot = plan.snapshot
        val publication = snapshot.getJSONObject("publication")
        val target = File(publication.getString("target_path"))
        val diff = File(publication.getString("diff_path"))
        try {
            plan.publish()
            val modified = target.lastModified()
            assertTrue(diff.delete())
            assertNotNull(executor.recoverPublished(snapshot))
            assertEquals(publication.getString("diff_document"), diff.readText())
            assertEquals(modified, target.lastModified())
            assertEquals("old", File(source.path).readText())
            target.writeText("corrupted")
            assertNull(executor.recoverPublished(snapshot))
            assertEquals("corrupted", target.readText())
            assertTrue(diff.isFile)
        } finally { plan.close(); target.delete(); diff.delete(); File(source.path).delete() }
    }

    @Test fun storageAncestorAliasIsSupportedButTargetSymlinkIsRejected() {
        val context = RuntimeEnvironment.getApplication()
        val alias = File(context.cacheDir, "files-alias-" + System.nanoTime())
        java.nio.file.Files.createSymbolicLink(alias.toPath(), context.filesDir.toPath())
        val wrapped = object : android.content.ContextWrapper(context) {
            override fun getFilesDir(): File = alias
        }
        val executor = LocalArtifactToolExecutor(wrapped)
        val args = JSONObject().put("filename", "alias.txt").put("content", "identical bytes")
        val plan = executor.prepareTextFile(args, "alias-" + System.nanoTime())
        val target = File(plan.snapshot.getJSONObject("publication").getString("target_path"))
        var otherTarget: File? = null
        try {
            plan.publish()
            assertEquals("identical bytes", target.readText())
            assertNotNull(executor.recoverPublished(plan.snapshot))
            executor.prepareTextFile(args, "symlink-" + System.nanoTime()).use { other ->
                otherTarget = File(other.snapshot.getJSONObject("publication").getString("target_path"))
                java.nio.file.Files.createSymbolicLink(otherTarget!!.toPath(), target.toPath())
                assertThrows(IOException::class.java) { other.publish() }
                assertEquals("identical bytes", target.readText())
            }
        } finally { plan.close(); otherTarget?.delete(); target.delete(); alias.delete() }
    }

}
