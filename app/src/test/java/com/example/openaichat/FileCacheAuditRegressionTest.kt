package com.example.openaichat

import android.content.Context
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
import java.io.InputStreamReader

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileCacheAuditRegressionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Test fun laterGithubPagesRequireEtagBeforeAnyFetchWithOrWithoutDiskCache() {
        val dir = File(context.cacheDir, "audit-etag").apply { deleteRecursively(); mkdirs() }
        try {
            for (tag in listOf<String?>(null, "")) {
                assertThrows(IOException::class.java) {
                    GitHubTextPageCache(dir).read("repo/file", 1, 20, tag, { true }) { _, _ -> throw AssertionError("Must not fetch") }
                }
            }
            for (disk in listOf<File?>(null, dir)) {
                val executor = GitHubToolExecutor(SecureStore(context), { "o/r" }, { setOf("main") }, { _, _ -> false }, { _, _ -> }, cacheDirectory = disk)
                val result = JSONObject(executor.execute("get_file_range", JSONObject().put("owner", "o").put("repo", "r")
                    .put("ref", "main").put("path", "source.kt").put("offset", 10)))
                assertFalse(result.getBoolean("ok")); assertTrue(result.toString().contains("ETag"))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun diskBudgetIncludesReplacementAndPartialSnapshotsAndSameEtagNeedsNoSecondCopy() {
        val dir = File(context.cacheDir, "audit-budget").apply { deleteRecursively(); mkdirs() }
        val budget = 10000L
        fun used() = dir.listFiles().orEmpty().filter { it.extension in setOf("utf16", "partial") }.sumOf { it.length() }
        val reader = GitHubTextPageCache(dir, budget)
        fun read(key: String, tag: String, text: String) = reader.read(key, 0, 20, null, { true }) { _, save ->
            InputStreamReader(text.byteInputStream(), Charsets.UTF_8).use { save(it, tag) }
            assertTrue(used() <= budget); tag
        }
        try {
            assertEquals("a".repeat(20), read("first", "v1", "a".repeat(3500)).getString("content"))
            read("second", "v1", "b".repeat(3500)); assertTrue(used() <= budget)
            assertEquals(1, dir.listFiles().orEmpty().count { it.extension == "utf16" })
            read("second", "v1", "b".repeat(3500)); assertTrue(used() <= budget)
            File(dir, StreamingJsonRequestBody.sha256("second") + ".partial").writeBytes(ByteArray(8000))
            reader.read("second", 0, 20, "v1", { true }) { tag, _ -> assertEquals("v1", tag); "v1" }
            assertTrue(used() <= budget); assertTrue(dir.listFiles().orEmpty().none { it.extension == "partial" })
            read("second", "v2", "c".repeat(3500)); assertTrue(used() <= budget)
            assertThrows(IOException::class.java) { read("oversize", "v1", "d".repeat(6000)) }
            assertTrue(used() <= budget); assertTrue(dir.listFiles().orEmpty().none { it.extension == "partial" })
        } finally { dir.deleteRecursively() }
    }

    @Test fun telemetryCountsActualCanonicalScansButSizeChecksDoNotReadFiles() {
        val file = File(context.cacheDir, "audit-io.pdf").apply { writeBytes(ByteArray(1024 * 1024) { it.toByte() }) }
        try {
            val wire = FileBackedDataUrl(file, "application/pdf")
            val body = JSONObject().put("file_data", wire)
            assertEquals(1L, wire.ioStats().readPasses)
            repeat(3) { StreamingJsonRequestBody.countBytes(body) }
            assertEquals(1L, wire.ioStats().readPasses)
            StreamingJsonRequestBody.sha256(body) // fingerprint v1 deliberately includes full wire bytes
            val discard = object : java.io.Writer() {
                override fun write(chars: CharArray, off: Int, len: Int) {}
                override fun flush() {}
                override fun close() {}
            }
            repeat(2) { StreamingJsonRequestBody.writeJson(body, discard) }
            val stats = FileBackedIoTelemetry.snapshot(JSONArray().put(body).put(body))
            assertEquals(1, stats.files); assertEquals(4L, stats.readPasses)
            assertEquals(file.length() * 4L, stats.readBytes); assertTrue(stats.workNanos > 0L)
            file.writeBytes(ByteArray(1024 * 1024))
            assertThrows(IOException::class.java) { StreamingJsonRequestBody.writeJson(body, discard) }
        } finally { file.delete() }
    }


}
