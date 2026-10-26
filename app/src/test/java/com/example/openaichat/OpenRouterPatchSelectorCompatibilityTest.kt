package com.example.openaichat

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** The provider-compatible schema must retain both historical selector forms and fail closed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OpenRouterPatchSelectorCompatibilityTest {
    private fun patches() = JSONArray().put(JSONObject().put("search", "old").put("replace", "new 😀"))

    @Test fun exactIdAndUnambiguousFilenameBothCreateVersionsWithoutChangingOriginal() {
        val executor = LocalArtifactToolExecutor(RuntimeEnvironment.getApplication())
        val original = executor.createTextFile(JSONObject().put("filename", "selector.txt").put("content", "old\n"))
        val source = OpenRouterLocalAttachment.fromJson(original.getJSONObject("attachment"))
        val results = mutableListOf<JSONObject>()
        try {
            for (selector in listOf(JSONObject().put("file_id", source.id), JSONObject().put("filename", source.name))) {
                results += executor.patchLocalFile(selector.put("patches", patches()), listOf(source))
                assertEquals("new 😀\n", File(results.last().getString("path")).readText())
                assertEquals(source.id, results.last().getString("source_file_id"))
                assertEquals("old\n", File(source.path).readText())
            }
            assertNotEquals(results[0].getString("path"), results[1].getString("path"))
        } finally {
            File(source.path).delete()
            results.forEach { File(it.getString("path")).delete(); File(it.getString("patch_diff_path")).delete() }
        }
    }

    @Test fun missingBlankAmbiguousAndDevicePathSelectorsStillFailBeforePublication() {
        val executor = LocalArtifactToolExecutor(RuntimeEnvironment.getApplication())
        val originals = listOf("old\n", "old other\n").map { content ->
            executor.createTextFile(JSONObject().put("filename", "same-selector.txt").put("content", content))
        }
        val files = originals.map { OpenRouterLocalAttachment.fromJson(it.getJSONObject("attachment")) }
        try {
            for (selector in listOf(JSONObject(), JSONObject().put("file_id", " "),
                JSONObject().put("filename", files[0].name), JSONObject().put("file_id", files[0].path),
                JSONObject().put("file_id", files[0].id).put("filename", "different.txt"))) {
                assertThrows(IOException::class.java) { executor.patchLocalFile(selector.put("patches", patches()), files) }
            }
            assertEquals("old\n", File(files[0].path).readText())
            assertEquals("old other\n", File(files[1].path).readText())
        } finally { files.forEach { File(it.path).delete() } }
    }
}
