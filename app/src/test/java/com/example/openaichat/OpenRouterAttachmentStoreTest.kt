package com.example.openaichat

import java.io.File
import java.nio.charset.Charset
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterAttachmentStoreTest {
    /** Exercise the same file-backed JSON path used by OpenRouter requests. */
    private fun wireDataUrl(attachment: OpenRouterLocalAttachment): String {
        val body = StreamingJsonRequestBody(JSONObject()
            .put("content", OpenRouterAttachmentStore.toContentBlock(attachment)))
        val sink = Buffer()
        body.writeTo(sink)
        val part = JSONObject(sink.readUtf8()).getJSONObject("content")
        return if (attachment.kind == "image") part.getJSONObject("image_url").getString("url")
            else part.getJSONObject("file").getString("file_data")
    }

    @Test fun interruptedHashingPreservesOriginalAndCreatesNoDerivative() {
        val dir = createTempDir()
        val bytes = "original UTF-8 text".toByteArray(Charsets.UTF_8)
        val file = File(dir, "original.txt").apply { writeBytes(bytes) }
        try {
            Thread.currentThread().interrupt()
            val error = org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                OpenRouterAttachmentStore.classifyLocalFile(file, file.name, "text/plain")
            }
            assertTrue(error.message.orEmpty().contains("отменена"))
            assertTrue("Cancellation must remain visible to the request owner", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        try {
            assertTrue(bytes.contentEquals(file.readBytes()))
            assertEquals(listOf(file.name), dir.listFiles()!!.map { it.name })
        } finally { dir.deleteRecursively() }
    }

    @Test fun phoneJpegAllowsLargeVendorTrailerButRejectsMissingEoi() {
        val dir = createTempDir()
        val jpeg = realImageBytes("jpeg")
        try {
            val file = File(dir, "motion.jpg").apply { writeBytes(jpeg + ByteArray(300_000) { 65 }) }
            val a = OpenRouterAttachmentStore.classifyLocalFile(file, file.name, "image/jpeg")
            assertEquals("image/jpeg", a.mime)
            assertTrue(Base64.getDecoder().decode(wireDataUrl(a).substringAfter(',')).contentEquals(file.readBytes()))
            file.writeBytes(jpeg.copyOf(jpeg.size - 2) + ByteArray(300_000) { 65 })
            var rejected = false
            try { OpenRouterAttachmentStore.classifyLocalFile(file, file.name, "image/jpeg") }
            catch (_: java.io.IOException) { rejected = true }
            assertTrue(rejected)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun unsupportedLegacyClassificationNeverDeletesOriginalBytes() {
        val source = File(createTempDir(), "persistent-ready.exe").apply {
            writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5, 6))
        }
        var rejected = false
        try {
            OpenRouterAttachmentStore.classifyLocalFile(source, source.name)
        } catch (_: java.io.IOException) {
            rejected = true
        }
        assertTrue("Unsupported binary must fail clearly", rejected)
        assertTrue("READY/legacy originals must not be deleted on failure", source.isFile)
        assertEquals(7L, source.length())
    }

    @Test
    fun ktOctetStreamClassifiesAsTextAndPersistsUtf8Artifact() {
        val dir = createTempDir()
        val file = File(dir, "Sample.kt").apply { writeText("fun main() = println(\"привет\")", Charsets.UTF_8) }
        val a = OpenRouterAttachmentStore.classifyLocalFile(file, "Sample.kt", "application/octet-stream")
        assertEquals("text", a.kind)
        assertEquals("UTF-8", a.textEncoding)
        assertEquals("fun main() = println(\"привет\")", OpenRouterAttachmentStore.textContent(a))
        assertTrue(a.textPath?.let { File(it).isFile } == true)
    }

    @Test
    fun markdownFallsBackToWindows1251() {
        val dir = createTempDir()
        val file = File(dir, "readme.md")
        file.writeBytes("Привет, мир".toByteArray(Charset.forName("windows-1251")))
        val a = OpenRouterAttachmentStore.classifyLocalFile(file, "readme.md", "application/octet-stream")
        assertEquals("text", a.kind)
        assertEquals("windows-1251", a.textEncoding)
        assertEquals("Привет, мир", OpenRouterAttachmentStore.textContent(a))
    }

    @Test
    fun withTextEncodingPreservesPreviousNormalizedFileReferencedByAnotherOwner() {
        val dir = createTempDir()
        try {
            val text = "Shared text attachment"
            val source = File(dir, "shared.txt").apply {
                writeText(text, Charsets.UTF_8)
            }
            val initial = OpenRouterAttachmentStore.classifyLocalFile(source, source.name, "text/plain")
            assertEquals("UTF-8", initial.textEncoding)
            // Another chat or active request keeps the initial attachment manifest.
            val otherOwnerPaths = LocalAttachmentReferences.openRouterPaths(JSONArray().put(initial.toJson()))
            val previousFile = File(initial.textPath!!)

            val updated = OpenRouterAttachmentStore.withTextEncoding(initial, "windows-1251")
            assertEquals("windows-1251", updated.textEncoding)
            val updatedFile = File(updated.textPath!!)
            val updatedOwnerPaths = LocalAttachmentReferences.openRouterPaths(JSONArray().put(updated.toJson()))

            assertTrue(initial.textPath != updated.textPath)
            assertTrue(previousFile.isFile)
            assertTrue(updatedFile.isFile)
            assertEquals(text, previousFile.readText(Charsets.UTF_8))
            assertEquals(text, updatedFile.readText(Charsets.UTF_8))
            assertTrue(previousFile.absolutePath in otherOwnerPaths)
            assertFalse(previousFile.absolutePath in updatedOwnerPaths)

            val nowMs = System.currentTimeMillis()
            assertTrue(previousFile.setLastModified(nowMs - 8L * 24 * 60 * 60 * 1000))
            assertFalse(LocalAttachmentReferences.isOrphanOldEnough(
                previousFile.absolutePath, previousFile.lastModified(), nowMs,
                otherOwnerPaths + updatedOwnerPaths
            ))
            // Once the other owner releases it, normal orphan cleanup may reclaim the old copy.
            assertTrue(LocalAttachmentReferences.isOrphanOldEnough(
                previousFile.absolutePath, previousFile.lastModified(), nowMs, updatedOwnerPaths
            ))
            assertTrue(previousFile.isFile)
            assertTrue(updatedFile.isFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(expected = java.io.IOException::class)
    fun nulByteIsNotText() {
        val dir = createTempDir()
        val file = File(dir, "bad.txt").apply { writeBytes(byteArrayOf(65, 0, 66)) }
        OpenRouterAttachmentStore.classifyLocalFile(file, "bad.txt", "text/plain")
    }

    @Test
    fun imageUsesOriginalBytesForDataUrl() {
        val dir = createTempDir()
        val original = realImageBytes("jpeg")
        val file = File(dir, "x.jpg").apply { writeBytes(original) }
        val a = OpenRouterLocalAttachment("1", "x.jpg", "image", file.absolutePath, "image/jpeg", original.size.toLong(), "hash")
        val data = wireDataUrl(a)
        assertTrue(data.startsWith("data:image/jpeg;base64,"))
        val encoded = data.substringAfter(",")
        assertTrue(Base64.getDecoder().decode(encoded).contentEquals(original))
    }

    @Test
    fun legacyPngWireIsDetectedOnlyWhenOriginalIsJpeg() {
        val dir = createTempDir()
        val jpeg = File(dir, "camera.jpg").apply { writeBytes(realImageBytes("jpeg")) }
        val png = File(dir, "camera.png").apply { writeBytes(realImageBytes("png")) }

        val legacy = OpenRouterLocalAttachment(
            "legacy", "camera.jpg", "image", jpeg.absolutePath, "image/jpeg", jpeg.length(), "sha",
            wirePath = File(dir, "wire.png").absolutePath, wireMime = "image/png"
        )
        val actualPng = OpenRouterLocalAttachment(
            "png", "camera.png", "image", png.absolutePath, "image/png", png.length(), "sha",
            wirePath = File(dir, "wire.png").absolutePath, wireMime = "image/png"
        )

        assertTrue(OpenRouterAttachmentStore.shouldRefreshLegacyPngWire(legacy))
        assertTrue(!OpenRouterAttachmentStore.shouldRefreshLegacyPngWire(actualPng))
    }

    @Test
    fun largeTextIsNotTruncated() {
        val dir = createTempDir()
        val expected = "абвгд".repeat(30000)
        val file = File(dir, "large.txt").apply { writeText(expected, Charsets.UTF_8) }
        val a = OpenRouterAttachmentStore.classifyLocalFile(file, "large.txt", "application/octet-stream")
        assertEquals(expected, OpenRouterAttachmentStore.textContent(a))
    }

    @Test
    fun unsafeZipPathDoesNotEscapeAttachmentName() {
        assertTrue(OpenRouterAttachmentStore.isUnsafeZipPath(".." + "/escape.txt"))
        assertTrue(OpenRouterAttachmentStore.isUnsafeZipPath("a/" + "../escape.txt"))
        assertTrue(OpenRouterAttachmentStore.isUnsafeZipPath("/absolute.txt"))
        assertTrue(OpenRouterAttachmentStore.isUnsafeZipPath("C:/absolute.txt"))
        assertTrue(!OpenRouterAttachmentStore.isUnsafeZipPath("nested/ok.txt"))
    }
    @Test
    fun pdfBlockUsesFileContentShape() {
        val dir = createTempDir()
        val file = File(dir, "x.pdf").apply { writeBytes("%PDF-1.7".toByteArray()) }
        val a = OpenRouterLocalAttachment("1", "x.pdf", "pdf", file.absolutePath, "application/pdf", file.length(), "hash")
        val block = org.json.JSONObject(java.io.StringWriter().also {
            StreamingJsonRequestBody.writeJson(OpenRouterAttachmentStore.toContentBlock(a), it)
        }.toString())
        assertEquals("file", block.optString("type"))
        assertEquals("x.pdf", block.optJSONObject("file")?.optString("filename"))
        assertTrue(block.optJSONObject("file")?.optString("file_data")?.startsWith("data:application/pdf;base64,") == true)
    }
    @Test
    fun mismatchedFilenameAndPickerMimeUseActualJpegBytes() {
        val dir = createTempDir()
        val jpg = File(dir, "wrong.png").apply {
            writeBytes(realImageBytes("jpeg"))
        }
        val attachment = OpenRouterAttachmentStore.classifyLocalFile(jpg, "wrong.png", "image/png")
        assertEquals("image", attachment.kind)
        assertEquals("image/jpeg", attachment.mime)
        val block = org.json.JSONObject(java.io.StringWriter().also {
            StreamingJsonRequestBody.writeJson(OpenRouterAttachmentStore.toContentBlock(attachment), it)
        }.toString())
        val url = block.getJSONObject("image_url").getString("url")
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        assertTrue(Base64.getDecoder().decode(url.substringAfter(",")).contentEquals(jpg.readBytes()))
    }

    @Test
    fun legacyNullWireFieldsRoundTripAsAbsent() {
        val dir = createTempDir()
        val jpeg = File(dir, "camera.jpg").apply {
            writeBytes(realImageBytes("jpeg"))
        }
        val a = OpenRouterLocalAttachment.fromJson(
            org.json.JSONObject()
                .put("id","one").put("name","camera.jpg").put("path",jpeg.absolutePath)
                .put("kind","image").put("mime","image/jpeg")
                .put("wire_path",org.json.JSONObject.NULL).put("wire_mime",org.json.JSONObject.NULL)
        )
        assertEquals(null, a.wirePath)
        assertEquals(null, a.wireMime)
        assertTrue(wireDataUrl(a).startsWith("data:image/jpeg;base64,"))
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsFakeImageEvenWithJpegExtensionAndMime() {
        val dir = createTempDir()
        val fake = File(dir, "fake.jpg").apply { writeText("NOT A JPEG") }
        val a = OpenRouterLocalAttachment("id","fake.jpg","image",fake.absolutePath,"image/jpeg",fake.length(),"sha")
        OpenRouterAttachmentStore.toContentBlock(a)
    }

    @Test(expected = java.io.IOException::class)
    fun missingWireFileDoesNotProduceEmptyBase64() {
        val a = OpenRouterLocalAttachment("id","x.png","image","/missing/app04-image.png","image/png",12L,"sha")
        wireDataUrl(a)
    }

    @Test
    fun validPngGifAndWebpWireBytesMatchDetectedMime() {
        val dir = createTempDir()
        for ((format, mime) in listOf(
            "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp"
        )) {
            val original = realImageBytes(format)
            val file = File(dir, "wrong.jpeg").apply { writeBytes(original) }
            val attachment = OpenRouterAttachmentStore.classifyLocalFile(file, "wrong.jpeg", "image/jpeg")
            assertEquals(mime, attachment.mime)
            val url = wireDataUrl(attachment)
            assertTrue(url.startsWith("data:$mime;base64,"))
            assertTrue(Base64.getDecoder().decode(url.substringAfter(",")).contentEquals(original))
        }
    }

    @Test
    fun correctMagicWithTruncatedTailIsRejectedForAllSupportedImages() {
        val dir = createTempDir()
        for (format in listOf("jpeg", "png", "gif", "webp")) {
            val original = realImageBytes(format)
            val broken = File(dir, "broken.$format").apply {
                writeBytes(original.copyOf(original.size - 2))
            }
            val attachment = OpenRouterLocalAttachment(
                "bad", "broken.$format", "image", broken.absolutePath, "image/$format",
                broken.length(), "sha"
            )
            var rejected = false
            try {
                wireDataUrl(attachment)
            } catch (_: java.io.IOException) {
                rejected = true
            }
            assertTrue("Truncated $format must not be sent", rejected)
        }
    }

    private fun realImageBytes(format: String): ByteArray = Base64.getDecoder().decode(
        when (format) {
            "jpeg" -> "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgICAgMCAgIDAwMDBAYEBAQEBAgGBgUGCQgKCgkICQkKDA8MCgsOCwkJDRENDg8QEBEQCgwSExIQEw8QEBD/2wBDAQMDAwQDBAgEBAgQCwkLEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBD/wAARCAACAAIDAREAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDnK/Bz9oP/2Q=="
            "png" -> "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR4nGP8yyfNwMDAxMDAwMDAAAAO1AEqT6J0VgAAAABJRU5ErkJggg=="
            "gif" -> "R0lGODdhAgACAIEAAP0OGwAAAAAAAAAAACwAAAAAAgACAAAIBgABCAQQEAA7"
            "webp" -> "UklGRjwAAABXRUJQVlA4IDAAAADwAQCdASoCAAIAAUAmJaACdLoB+AAETAAA/vCbQ/8WcjEdnm//PaPz2j89o/88IAA="
            else -> error("Unknown test image format: $format")
        }
    )


}
