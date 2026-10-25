package com.example.openaichat

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import fr.free.nrw.commons.ajpegtran.Jpegtran
import fr.free.nrw.commons.ajpegtran.Properties
import fr.free.nrw.commons.ajpegtran.rotate.RotationDegree
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

data class OpenRouterLocalAttachment(
    val id: String,
    val name: String,
    val kind: String,
    val path: String,
    val mime: String,
    val size: Long,
    val sha256: String,
    val textPath: String? = null,
    val textEncoding: String? = null,
    val wirePath: String? = null,
    val wireMime: String? = null,
    val encodingGuessed: Boolean = textEncoding.equals("windows-1251", ignoreCase = true),
    val textSha256: String? = null,
    val wireSha256: String? = null
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind).put("path", path).put("mime", mime).put("size", size).put("sha256", sha256).apply {
        textPath?.let { put("text_path", it) }
        textEncoding?.let { put("text_encoding", it); put("encoding_guessed", encodingGuessed) }
        wirePath?.let { put("wire_path", it) }
        wireMime?.let { put("wire_mime", it) }
        textSha256?.let { put("text_sha256", it) }
        wireSha256?.let { put("wire_sha256", it) }
    }
    companion object {
        private fun optional(o: JSONObject, key: String): String? =
            JsonProtocol.nonBlank(o, key)?.takeIf { it != "null" }

        fun fromJson(o: JSONObject) = OpenRouterLocalAttachment(
            JsonProtocol.string(o, "id"), JsonProtocol.string(o, "name", "attachment"), JsonProtocol.string(o, "kind", "unknown"),
            JsonProtocol.string(o, "path"), JsonProtocol.string(o, "mime", "application/octet-stream"), o.optLong("size", 0L),
            JsonProtocol.string(o, "sha256"), optional(o, "text_path"), optional(o, "text_encoding"),
            optional(o, "wire_path"), optional(o, "wire_mime"),
            o.optBoolean("encoding_guessed", optional(o, "text_encoding").equals("windows-1251", ignoreCase = true)),
            optional(o, "text_sha256"), optional(o, "wire_sha256")
        )
    }
}
 
fun interface AttachmentInputStreamSource {
    fun openInputStream(context: Context, uri: Uri): InputStream?
}

object DefaultAttachmentInputStreamSource : AttachmentInputStreamSource {
    override fun openInputStream(context: Context, uri: Uri): InputStream? =
        context.contentResolver.openInputStream(uri)
}


object OpenRouterAttachmentStore {
    const val MAX_ATTACHMENTS = 12
    const val MAX_ZIP_ENTRIES = 100
    const val MAX_ZIP_ENTRY_BYTES = 25L * 1024L * 1024L
    const val MAX_ZIP_TOTAL_BYTES = 200L * 1024L * 1024L
    private val textExtensions = TextFileFormatPolicy.extensions
    private val imageExtensions = setOf("png","jpg","jpeg","webp","gif")

    fun supportedFormatsText() = (textExtensions.sorted() + TextFileFormatPolicy.office.sorted() + listOf("png", "jpg", "jpeg", "webp", "gif", "pdf", "zip")).joinToString(", ")

    fun queryName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
    } catch (_: Exception) {
        null
    } ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }

    fun copyAndClassify(
        context: Context,
        uri: Uri,
        forcedName: String? = null,
        inputStreamSource: AttachmentInputStreamSource = DefaultAttachmentInputStreamSource
    ): OpenRouterLocalAttachment {
        val name = sanitizeDisplayName(forcedName ?: queryName(context, uri) ?: "attachment")
        val mime = runCatching { context.contentResolver.getType(uri).orEmpty() }.getOrDefault("").ifBlank { mimeForFilename(name) }
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        val raw = File(dir, "or_" + UUID.randomUUID() + "_" + safeFileName(name))
        inputStreamSource.openInputStream(context, uri)?.use { input ->
            BoundedAttachmentCopy.copy(input, raw)
        } ?: throw IOException("Не удалось открыть файл: " + name)
        return try { finalizeFile(context, dir, raw, name, mime) }
        catch (e: Exception) {
            raw.delete(); throw e
        }
    }

    fun classifyLocalFile(file: File, displayName: String = file.name, mime: String = mimeForFilename(displayName)): OpenRouterLocalAttachment =
        finalizeFile(null, file.parentFile ?: throw IOException("Нет каталога вложения"), file, sanitizeDisplayName(displayName), mime)

    fun classifyLocalFile(context: Context, file: File, displayName: String = file.name, mime: String = mimeForFilename(displayName)): OpenRouterLocalAttachment =
        finalizeFile(context, file.parentFile ?: throw IOException("Нет каталога вложения"), file, sanitizeDisplayName(displayName), mime)

    fun extractZipFile(context: Context, zipFile: File, zipName: String, report: (String) -> Unit = {}): List<OpenRouterLocalAttachment> {
        zipFile.inputStream().use { source ->
            return extractZipStream(context, source, zipName, report)
        }
    }

    private fun extractZipStream(context: Context, source: InputStream, zipName: String, report: (String) -> Unit): List<OpenRouterLocalAttachment> {
        val result = mutableListOf<OpenRouterLocalAttachment>()
        try {
        var entries = 0
        var total = 0L
        ZipInputStream(source.buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                entries++
                if (entries > MAX_ZIP_ENTRIES) throw IOException("ZIP содержит больше " + MAX_ZIP_ENTRIES + " файлов")
                val rawEntryPath = entry.name.replace('\\', '/')
                val unsafeEntry = isUnsafeZipPath(rawEntryPath)
                val entryName = rawEntryPath.substringAfterLast('/').trim()
                if (unsafeEntry) {
                    drainZipEntry(zin, entryName, report, "Небезопасный путь ZIP пропущен: " + entry.name) { n ->
                        total += n
                        if (total > MAX_ZIP_TOTAL_BYTES) throw IOException("ZIP распаковывается больше 200 МБ")
                    }
                } else if (!entry.isDirectory && entryName.isNotBlank()) {
                    val ext = entryName.substringAfterLast('.', "").lowercase()
                    val supported = TextFileFormatPolicy.knownText(entryName) || ext in TextFileFormatPolicy.office || ext in imageExtensions || ext == "pdf"
                    val nested = ext == "zip" || ext == "apk" || ext == "jar"
                    if (supported) {
                        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
                        val local = File(dir, "zip_" + UUID.randomUUID() + "_" + safeFileName(entryName))
                        var entryBytes = 0L
                        try {
                        local.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = zin.read(buffer); if (n < 0) break
                                entryBytes += n; total += n
                                if (entryBytes > MAX_ZIP_ENTRY_BYTES) throw IOException("ZIP-файл " + entryName + " превышает 25 МБ")
                                if (total > MAX_ZIP_TOTAL_BYTES) throw IOException("ZIP распаковывается больше 200 МБ")
                                out.write(buffer, 0, n)
                            }
                        }
                        } catch (e: Exception) { local.delete(); throw e }
                        if (entryBytes == 0L) { local.delete(); report("Пустой файл пропущен: " + entryName) }
                        else {
                            try {
                                result += finalizeFile(context, dir, local, sanitizeDisplayName(entryName), mimeForFilename(entryName))
                            } catch (e: Exception) { local.delete(); throw e }
                        }
                    } else {
                        drainZipEntry(zin, entryName, report, if (nested) "Пропущен вложенный архив/бинарник: " + entryName else "Неподдерживаемый файл пропущен: " + entryName) { n ->
                            total += n
                            if (total > MAX_ZIP_TOTAL_BYTES) throw IOException("ZIP распаковывается больше 200 МБ")
                        }
                    }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        if (result.isEmpty()) throw IOException("В ZIP не найдено поддерживаемых файлов")
        report("ZIP " + zipName + ": подготовлено " + result.size + " поддерживаемых файлов")
        return result
        } catch (e: Exception) {
            // Delete only freshly extracted artifacts belonging to this operation.
            result.forEach { item ->
                File(item.path).delete()
                item.textPath?.let { File(it).delete() }
                item.wirePath?.let { File(it).delete() }
            }
            throw e
        }
    }

    internal fun isUnsafeZipPath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized.startsWith("/") || normalized.split('/').any { it == ".." } || Regex("^[A-Za-z]:/").containsMatchIn(normalized)
    }
    private fun drainZipEntry(zin: ZipInputStream, entryName: String, report: (String) -> Unit, message: String, onBytes: (Long) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var entryBytes = 0L
        while (true) {
            val n = zin.read(buffer); if (n < 0) break
            entryBytes += n
            onBytes(n.toLong())
            if (entryBytes > MAX_ZIP_ENTRY_BYTES) throw IOException("ZIP entry превышает 25 МБ: " + entryName)
        }
        report(message)
    }

    fun extractZip(
        context: Context,
        uri: Uri,
        zipName: String,
        report: (String) -> Unit = {},
        inputStreamSource: AttachmentInputStreamSource = DefaultAttachmentInputStreamSource
    ): List<OpenRouterLocalAttachment> {
        inputStreamSource.openInputStream(context, uri)?.use { source ->
            return extractZipStream(context, source, zipName, report)
        } ?: throw IOException("Не удалось открыть ZIP")
    }

    fun toContentBlock(a: OpenRouterLocalAttachment): JSONObject = when (a.kind) {
        "text", "document", "table" -> JSONObject().put("type", "text").put("text", LocalFileTools.descriptor(a).toString())
        "image" -> JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", streamingDataUrl(a)))
        "pdf" -> JSONObject().put("type", "file").put("file", JSONObject().put("filename", a.name).put("file_data", streamingDataUrl(a)))
        else -> throw IOException("Вложение " + a.name + " нельзя отправить напрямую")
    }
    internal fun shouldRefreshLegacyPngWire(attachment: OpenRouterLocalAttachment): Boolean {
        if (attachment.kind != "image" || attachment.wireMime != "image/png") return false
        val source = File(attachment.path)
        return source.isFile && isJpegFile(source)
    }

    private fun checkedWire(a: OpenRouterLocalAttachment): Pair<File, String> {
        val wireFile = File(a.wirePath ?: a.path)
        if (!wireFile.isFile || wireFile.length() == 0L) {
            throw IOException("Локальные байты вложения недоступны: " + a.name)
        }
        if (wireFile.length() > BoundedAttachmentCopy.MAX_INPUT_BYTES) {
            throw IOException("Вложение превышает лимит 25 МБ для безопасной сериализации: " + a.name)
        }
        // A name, picker MIME or legacy manifest is not evidence of the actual wire type.
        val wireMime = when (a.kind) {
            "image" -> detectImageMime(wireFile)
                ?: throw IOException("Повреждённое или неподдерживаемое изображение: " + a.name)
            "pdf" -> if (wireFile.inputStream().use { input ->
                val header = ByteArray(5)
                input.read(header) == 5 && header.contentEquals("%PDF-".toByteArray())
            }) "application/pdf"
                else throw IOException("Повреждённый PDF: " + a.name)
            else -> a.wireMime ?: a.mime
        }
        return wireFile to wireMime
    }
    internal fun streamingDataUrl(a: OpenRouterLocalAttachment): FileBackedDataUrl {
        val (file, mime) = checkedWire(a)
        return FileBackedDataUrl(file, mime)
    }

    fun withTextEncoding(a: OpenRouterLocalAttachment, encoding: String): OpenRouterLocalAttachment {
        if (a.kind != "text" || encoding !in TextFileFormatPolicy.selectableEncodings) throw IOException("Недоступная кодировка")
        val source = File(a.path)
        if (!source.isFile || (a.sha256.isNotBlank() && sha256(source) != a.sha256)) throw IOException("Оригинал отсутствует или изменён")
        val normalized = PreparedAttachmentFiles.derivative(source.parentFile ?: throw IOException("Нет каталога оригинала"), ".utf8")
        try {
            TextFileFormatPolicy.normalize(source, normalized, false, encoding)
            return a.copy(textPath = normalized.absolutePath, textEncoding = encoding, encodingGuessed = false,
                textSha256 = sha256(normalized))
        } catch (e: Exception) { normalized.delete(); throw e }
    }

    fun textContent(a: OpenRouterLocalAttachment): String {
        val source = File(a.textPath ?: a.path)
        if (!source.isFile || source.length() > BoundedAttachmentCopy.MAX_INPUT_BYTES) {
            throw IOException("Текстовое вложение недоступно или превышает 25 МБ: " + a.name)
        }
        return source.readText(Charsets.UTF_8)
    }

    private fun finalizeFile(context: Context?, dir: File, file: File, name: String, mime: String): OpenRouterLocalAttachment {
        val kind = classifyKind(file, name)
        // classifyLocalFile may inspect a READY/legacy original. Never delete it on failure.
        if (kind == "unsupported") throw IOException("Формат '" + name + "' не поддерживается для OpenRouter. Поддерживаются: " + supportedFormatsText())
        if (kind == "binary") throw IOException("Файл '" + name + "' определён как бинарный и не входит в поддерживаемые форматы")
        val size = file.length(); val sha = sha256(file)
        val stableId = sha + "_" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(name.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }
        return if (kind == "text") {
            val textFile = PreparedAttachmentFiles.derivative(dir, ".utf8")
            try {
                val encoding = TextFileFormatPolicy.normalize(file, textFile, TextFileFormatPolicy.knownText(name))
                OpenRouterLocalAttachment(stableId, name, kind, file.absolutePath,
                    mimeForFilename(name).takeIf { it != "application/octet-stream" } ?: mime,
                    size, sha, textFile.absolutePath, encoding, textSha256 = sha256(textFile))
            } catch (error: Throwable) { textFile.delete(); throw error }
        } else if (kind == "document" || kind == "table") {
            val textFile = PreparedAttachmentFiles.derivative(dir, ".utf8")
            try {
                StructuredDocumentExtractor.extract(file, textFile, TextFileFormatPolicy.extension(name))
                OpenRouterLocalAttachment(stableId, name, kind, file.absolutePath, mime, size, sha,
                    textFile.absolutePath, "UTF-8", textSha256 = sha256(textFile))
            } catch (error: Throwable) { textFile.delete(); throw error }
        } else if (kind == "image") {
            val actualMime = detectImageMime(file)
                ?: throw IOException("Изображение не соответствует JPEG, PNG, GIF или WebP: " + name)
            val normalized = context?.let { normalizeImageOrientation(it, file, dir, name) }
            try {
                OpenRouterLocalAttachment(stableId, name, kind, file.absolutePath,
                    actualMime, size, sha, null, null, normalized?.absolutePath,
                    if (normalized != null) "image/jpeg" else actualMime,
                    wireSha256 = normalized?.let(::sha256))
            } catch (error: Throwable) { normalized?.delete(); throw error }
        } else {
            OpenRouterLocalAttachment(stableId, name, kind, file.absolutePath, if (kind == "pdf") "application/pdf" else mime, size, sha)
        }
    }

    /**
     * Cheap container-level validation: a signature alone accepts 3-byte "JPEGs".
     * JPEG markers are scanned through EOI in blocks; other formats inspect header/trailer.
     * This is not a full decode; byte integrity is checked separately with SHA.
     */
    internal fun detectImageMime(file: File): String? {
        if (!file.isFile) return null
        val magic = ByteArray(16)
        val count = try { file.inputStream().use { it.read(magic) } } catch (_: IOException) { return null }
        val type = when {
            count >= 3 && magic.copyOfRange(0, 3).contentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) -> "image/jpeg"
            count >= 8 && magic.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 13, 10, 26, 10)) -> "image/png"
            count >= 10 && (String(magic, 0, 6, Charsets.US_ASCII) == "GIF87a" ||
                String(magic, 0, 6, Charsets.US_ASCII) == "GIF89a") -> "image/gif"
            count >= 16 && String(magic, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(magic, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> return null
        }
        return type.takeIf { hasImageContainerEnding(file, magic, it) }
    }

    private fun hasImageContainerEnding(file: File, header: ByteArray, mime: String): Boolean {
        val length = file.length()
        return try {
            RandomAccessFile(file, "r").use { source ->
                when (mime) {
                    "image/jpeg" -> {
                        if (length < 64L) return false
                        // Motion photos / vendor metadata may follow EOI, even by many MB.
                        JpegContainerReader.complete(file)
                    }
                    "image/png" -> {
                        if (length < 45L) return false
                        val iend = ByteArray(12)
                        source.seek(length - 12L)
                        source.readFully(iend)
                        iend.contentEquals(byteArrayOf(
                            0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44,
                            0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
                        ))
                    }
                    "image/gif" -> {
                        if (length < 14L) return false
                        val width = (header[6].toInt() and 0xFF) or ((header[7].toInt() and 0xFF) shl 8)
                        val height = (header[8].toInt() and 0xFF) or ((header[9].toInt() and 0xFF) shl 8)
                        source.seek(length - 1L)
                        width > 0 && height > 0 && source.readUnsignedByte() == 0x3B
                    }
                    "image/webp" -> {
                        if (length < 20L) return false
                        val riffLength = (0..3).fold(0L) { acc, i ->
                            acc or ((header[4 + i].toLong() and 0xFFL) shl (8 * i))
                        }
                        val chunk = String(header, 12, 4, Charsets.US_ASCII)
                        riffLength + 8L == length &&
                            chunk in setOf("VP8 ", "VP8L", "VP8X")
                    }
                    else -> false
                }
            }
        } catch (_: IOException) { false }
    }

    private fun classifyKind(file: File, name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(); val magic = ByteArray(12)
        file.inputStream().use { it.read(magic) }
        if (magic.copyOfRange(0,5).contentEquals("%PDF-".toByteArray())) return "pdf"
        if (magic.copyOfRange(0,4).contentEquals(byteArrayOf(0x89.toByte(),0x50,0x4E,0x47))) return "image"
        if (magic.copyOfRange(0,3).contentEquals(byteArrayOf(0xFF.toByte(),0xD8.toByte(),0xFF.toByte()))) return "image"
        if (magic.copyOfRange(0,3).contentEquals("GIF".toByteArray())) return "image"
        if (magic.copyOfRange(0,4).contentEquals("RIFF".toByteArray()) && magic.copyOfRange(8,12).contentEquals("WEBP".toByteArray())) return "image"
        if (magic.copyOfRange(0,4).contentEquals(byteArrayOf(0x50,0x4B,0x03,0x04))) {
            if (ext == "xlsx") return "table"
            if (ext in TextFileFormatPolicy.office) return "document"
            return "zip"
        }
        if (TextFileFormatPolicy.knownText(name)) return "text"
        if (ext in imageExtensions) return "image"
        if (ext == "pdf") return "pdf"
        return "unsupported"
    }

    private fun normalizeImageOrientation(context: Context, file: File, dir: File, name: String): File? {
        if (!isJpegFile(file)) return null

        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) return null

        // Mirrored/transpose EXIF values stay as original JPEG/EXIF rather than being re-encoded.
        // That keeps wire bytes JPEG and avoids silently losing pixels when no strict lossless API is exposed.
        val rotation = LosslessJpegExifPolicy.rotationDegrees(orientation) ?: return null

        return try {
            val propertiesUri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val properties = Properties.of(context, propertiesUri)
            if (!LosslessJpegExifPolicy.isPerfectRotation(
                    orientation = orientation,
                    width = properties.width,
                    height = properties.height,
                    mcuWidth = properties.MCU_Width,
                    mcuHeight = properties.MCU_Height
                )
            ) {
                // Never crop or partially transform MCU edge blocks. Keep the original
                // JPEG/EXIF wire representation when a strictly lossless transform
                // cannot be proven.
                return null
            }

            val rotationDegree = when (rotation) {
                90 -> RotationDegree.ROTATE_90
                180 -> RotationDegree.ROTATE_180
                270 -> RotationDegree.ROTATE_270
                else -> return null
            }

            val transformer = Jpegtran(context.applicationContext, Uri.fromFile(file))
            var transformed: File? = null
            try {
                transformed = transformer.rotate(rotationDegree, trim = false)
                val owned = PreparedAttachmentFiles.derivative(dir, ".jpg")
                transformed.copyTo(owned, overwrite = true)
                return owned
            } finally {
                transformer.cleanup()
                transformed?.takeIf { it.exists() }?.delete()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isJpegFile(file: File): Boolean {
        if (file.length() < 3L) return false
        return file.inputStream().use { input ->
            input.read() == 0xFF && input.read() == 0xD8 && input.read() == 0xFF
        }
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw IOException("Подготовка файла отменена")
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sanitizeDisplayName(name: String) = name.substringAfterLast('/').substringAfterLast('\\').trim().ifBlank { "attachment" }.take(180)
    private fun safeFileName(name: String) = sanitizeDisplayName(name).replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
    private fun mimeForFilename(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "txt","log" -> "text/plain"; "md","markdown" -> "text/markdown"; "kt","java" -> "text/plain"; "json" -> "application/json"; "csv" -> "text/csv"; "xml" -> "application/xml"; "yaml","yml" -> "text/yaml"; "png" -> "image/png"; "jpg","jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"; "pdf" -> "application/pdf"; else -> "application/octet-stream"
    }
}
