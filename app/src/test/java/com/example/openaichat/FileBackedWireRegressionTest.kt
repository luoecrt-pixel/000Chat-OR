package com.example.openaichat

import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileBackedWireRegressionTest {
    @Test fun diskWireMatchesCanonicalJsonAndCopiesWithoutMaterializingBase64() {
        val dir = File(RuntimeEnvironment.getApplication().cacheDir, "wire_test").apply { mkdirs() }
        try {
            val file = File(dir, "input").apply { writeBytes(ByteArray(131077) { (it * 37).toByte() }) }
            val streamed = JSONObject().put("file_data", FileBackedDataUrl(file, "application/pdf"))
            val copy = JsonProtocol.objectCopy(streamed)
            assertSame(streamed.get("file_data"), copy.get("file_data"))
            val expected = JSONObject().put("file_data", "data:application/pdf;base64," + Base64.getEncoder().encodeToString(file.readBytes()))
            assertEquals(StreamingJsonRequestBody.sha256(expected), StreamingJsonRequestBody.sha256(copy))
            val body = StreamingJsonRequestBody(copy); val sink = Buffer(); body.writeTo(sink)
            assertEquals(body.contentLength(), sink.size)
            assertEquals(expected.getString("file_data"), JSONObject(sink.readUtf8()).getString("file_data"))
            file.writeBytes(ByteArray(131077))
            assertThrows(java.io.IOException::class.java) { body.writeTo(Buffer()) }
        } finally { dir.deleteRecursively() }
    }
    @Test fun textTelemetryCountsOnlyPreparationFingerprintAndWriteAndDeduplicatesAliases() {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "wire-telemetry.txt")
            .apply { writeText("Текст 😀\n\t\"\\".repeat(4000)) }
        try {
            val wire = FileBackedText(file, "prefix\n", "\nend")
            val body = JSONObject().put("text", wire)
            val aliases = org.json.JSONArray().put(body).put(body).put(wire)
            assertEquals(1L, FileBackedIoTelemetry.snapshot(aliases).readPasses)
            val request = StreamingJsonRequestBody(body)
            repeat(3) { assertEquals(StreamingJsonRequestBody.countBytes(body), request.contentLength()) }
            assertEquals(1L, FileBackedIoTelemetry.snapshot(aliases).readPasses)
            val expected = JSONObject().put("text", "prefix\n" + file.readText() + "\nend")
            assertEquals(StreamingJsonRequestBody.sha256(expected), StreamingJsonRequestBody.sha256(body))
            val sink = Buffer()
            request.writeTo(sink)
            assertEquals(request.contentLength(), sink.size)
            assertEquals(expected.getString("text"), JSONObject(sink.readUtf8()).getString("text"))
            val stats = FileBackedIoTelemetry.snapshot(aliases)
            assertEquals(1, stats.files)
            assertEquals(3L, stats.readPasses)
            assertEquals(file.length() * 3L, stats.readBytes)
            assertTrue(stats.workNanos > 0)
            // A same-size replacement must still fail the digest guard after removing the pre-scan.
            file.writeBytes(ByteArray(file.length().toInt()) { 'x'.code.toByte() })
            assertThrows(java.io.IOException::class.java) { request.writeTo(Buffer()) }
        } finally { file.delete() }
    }

    @Test fun telemetryIncludesBothFileTypesWithoutCountingSharedNodesTwice() {
        val dir = File(RuntimeEnvironment.getApplication().cacheDir, "mixed-telemetry").apply { mkdirs() }
        try {
            val image = File(dir, "image").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val text = File(dir, "text").apply { writeText("abc") }
            val dataUrl = FileBackedDataUrl(image, "image/png")
            val document = FileBackedText(text)
            val value = JSONObject().put("image", dataUrl).put("text", document)
                .put("aliases", org.json.JSONArray().put(dataUrl).put(document))
            val stats = FileBackedIoTelemetry.snapshot(value)
            assertEquals(2, stats.files)
            assertEquals(2L, stats.readPasses)
            assertEquals(image.length() + text.length(), stats.readBytes)
        } finally { dir.deleteRecursively() }
    }


}
