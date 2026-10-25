package com.example.openaichat

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.FileNotFoundException
import java.util.UUID
import java.nio.file.FileSystemException

class LocalArtifactToolExecutor(private val context: Context) {
    companion object {
        private val patchLock = Any()
        /** Only descriptors, never device paths or full local diff contents, reach the model. */
        fun toolResult(metadata: JSONObject): JSONObject = JsonProtocol.objectCopy(metadata).apply {
            remove("path"); remove("attachment"); remove("patch_diff_path"); remove("source_path")
        }
    }
    fun execute(name: String, arguments: JSONObject): String {
        return when (name) {
            "create_local_text_file" -> createTextFile(arguments).toString()
            else -> throw IOException("Unknown local artifact tool: $name")
        }
    }

    fun createTextFile(arguments: JSONObject): JSONObject =
        prepareTextFile(arguments, UUID.randomUUID().toString()).use { it.publish() }

    private inline fun preparing(block: (java.io.Closeable) -> LocalArtifactPublicationPlan): LocalArtifactPublicationPlan {
        val ownership = LocalArtifactOwnership.lease()
        return try { block(ownership) } catch (error: Throwable) { ownership.close(); throw error }
    }

    fun prepareTextFile(arguments: JSONObject, artifactKey: String): LocalArtifactPublicationPlan = preparing { ownership ->
        val rawName = JsonProtocol.nonBlank(arguments, "filename")?.trim() ?: throw IOException("filename is required")
        val content = JsonProtocol.text(arguments, "content") ?: throw IOException("content must be a string")
        if (TextFileFormatPolicy.extension(rawName) in TextFileFormatPolicy.office + setOf("pdf", "doc", "xls", "ppt", "png", "jpg", "jpeg", "gif", "webp", "zip", "mp3", "mp4"))
            throw IOException("create_local_text_file создаёт UTF-8 текст, а не настоящий бинарный ${TextFileFormatPolicy.extension(rawName)}. Используйте текстовый формат, например TXT, Markdown, CSV или исходный код.")
        val target = File(File(context.filesDir, "generated"), "create_${artifactToken(artifactKey)}_${safeFilename(rawName)}")
        val staged = File.createTempFile("artifact_text_", ".tmp", context.cacheDir)
        try {
            staged.bufferedWriter(Charsets.UTF_8, 64 * 1024).use { writer ->
                var offset = 0
                while (offset < content.length) {
                    LocalUtf8FilePatch.checkActive { true }
                    val count = minOf(64 * 1024, content.length - offset)
                    writer.write(content, offset, count)
                    offset += count
                }
            }
            val sha = LocalUtf8FilePatch.sha256(staged)
            val metadata = withAttachment(JSONObject().put("ok", true).put("type", "local_file")
                .put("filename", rawName).put("path", target.absolutePath).put("mime_type", mimeForFilename(rawName))
                .put("size_bytes", staged.length()), sha)
            LocalArtifactPublicationPlan(context, publicationSnapshot(metadata), staged, ownership)
        } catch (error: Throwable) { staged.delete(); throw error }
    }

    fun patchLocalFile(arguments: JSONObject, files: List<OpenRouterLocalAttachment>,
        active: () -> Boolean = { true }, artifactKey: String? = null): JSONObject =
        preparePatchLocalFile(arguments, files, artifactKey ?: UUID.randomUUID().toString(), active).use { it.publish(active) }

    fun preparePatchLocalFile(arguments: JSONObject, files: List<OpenRouterLocalAttachment>,
        artifactKey: String, active: () -> Boolean = { true }): LocalArtifactPublicationPlan = preparing { ownership -> try { synchronized(patchLock) {
        LocalUtf8FilePatch.checkActive(active)
        if (StreamingJsonRequestBody.countBytes(arguments) > 256 * 1024) throw IOException("Аргументы патча превышают 256 КБ")
        val selector = JsonProtocol.nonBlank(arguments, "file_id") ?: JsonProtocol.nonBlank(arguments, "filename")
            ?: throw IOException("Укажите file_id или однозначное filename")
        val candidates = files.filter { it.id == selector }.ifEmpty { files.filter { it.name == selector } }
            .distinctBy { File(it.path).canonicalPath }
        if (candidates.size != 1) throw IOException("Файл отсутствует или имя неоднозначно; укажите точный file_id")
        val source = candidates.single()
        if (source.kind != "text" || (source.textEncoding != null && !source.textEncoding.equals("UTF-8", true)))
            throw IOException("Патч поддерживает только исходные UTF-8 текстовые файлы, не извлечённые офисные документы")
        JsonProtocol.nonBlank(arguments, "filename")?.let { if (it != source.name) throw IOException("filename не соответствует выбранному file_id") }
        val expected = JsonProtocol.nonBlank(arguments, "expected_sha256")
        if (arguments.has("expected_sha256") && expected == null) throw IOException("expected_sha256 должен быть непустой строкой")
        if (expected != null && !Regex("[0-9a-fA-F]{64}").matches(expected)) throw IOException("expected_sha256 должен содержать 64 hex символа")
        if (expected != null && source.sha256.isNotBlank() && !source.sha256.equals(expected, true))
            throw IOException("expected_sha256 не соответствует версии файла")
        val rawPatches = arguments.optJSONArray("patches") ?: throw IOException("patches должен быть массивом")
        val patches = (0 until rawPatches.length()).map { index ->
            val patch = rawPatches.optJSONObject(index) ?: throw IOException("Недействительный блок patches")
            LocalUtf8FilePatch.Replacement(JsonProtocol.text(patch, "search") ?: throw IOException("search должен быть строкой"),
                JsonProtocol.text(patch, "replace") ?: throw IOException("replace должен быть строкой"))
        }
        LocalUtf8FilePatch.prepare(File(source.path), context.cacheDir, patches,
            source.sha256.takeIf { it.isNotBlank() } ?: expected, active).use { plan ->
            val operation = StreamingJsonRequestBody.sha256(JSONObject().put("source", source.id)
                .put("sha256", plan.sourceSha256).put("filename", source.name).put("patches", rawPatches))
            val token = artifactToken(artifactKey)
            val dir = File(context.filesDir, "generated")
            val target = File(dir, "patch_${token}_${safeFilename(source.name)}")
            val diff = File(File(dir, "patch-diffs"), "$token.json")
            val staged = File.createTempFile("patch_result_", ".tmp", context.cacheDir)
            try {
                val resultSha = plan.write(staged)
                val metadata = withAttachment(JSONObject().put("ok", true).put("type", "local_file")
                    .put("filename", source.name).put("path", target.absolutePath).put("mime_type", source.mime)
                    .put("size_bytes", staged.length()).put("patch_count", plan.hunks.size)
                    .put("source_file_id", source.id).put("source_sha256", plan.sourceSha256)
                    .put("source_path", source.path).put("patch_diff_path", diff.absolutePath), resultSha)
                val hunks = JSONArray().apply { plan.hunks.forEach { h -> put(JSONObject().put("line", h.line)
                    .put("search", h.replacement.search).put("replace", h.replacement.replace)) } }
                val sidecar = JSONObject().put("version", 1).put("source_file_id", source.id).put("source_sha256", plan.sourceSha256)
                    .put("result_sha256", resultSha).put("operation_sha256", operation).put("hunks", hunks).put("artifact", metadata)
                LocalArtifactPublicationPlan(context, publicationSnapshot(metadata, sidecar.toString()), staged, ownership)
            } catch (error: Throwable) { staged.delete(); throw error }
        }
    } } catch (error: FileNotFoundException) {
        throw IOException("Исходный файл или каталог версии недоступен. Исходник не изменён.", error)
    } catch (error: FileSystemException) {
        throw IOException("Не удалось прочитать или сохранить версию файла. Исходник не изменён.", error)
    } }

    fun recoverPublished(snapshot: JSONObject): JSONObject? = LocalArtifactPublicationPlan.recoverPublished(context, snapshot)

    private fun publicationSnapshot(metadata: JSONObject, diffDocument: String? = null): JSONObject {
        val publication = JSONObject().put("target_path", metadata.getString("path"))
            .put("expected_sha256", metadata.getString("sha256")).put("expected_bytes", metadata.getLong("size_bytes"))
        if (diffDocument != null) publication.put("diff_path", metadata.getString("patch_diff_path"))
            .put("diff_document", diffDocument).put("diff_sha256", LocalArtifactPublicationPlan.documentSha256(diffDocument))
        return JSONObject().put("version", 1).put("artifact", metadata).put("publication", publication)
    }

    private fun artifactToken(key: String): String = LocalUtf8FilePatch.hex(java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)))
    private fun safeFilename(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[^A-Za-z0-9._-]"), "_").take(160).ifBlank { "generated.txt" }

    private fun withAttachment(metadata: JSONObject, sha: String): JSONObject {
        val path = metadata.getString("path")
        val name = metadata.getString("filename")
        // Bytes may return to an older value (or a patch may be a no-op). The
        // published filename carries the immutable version token, so retained
        // versions stay addressable while a retry of the same target keeps its ID.
        // Persisted manifests retain their original IDs; only new versions use v2.
        val id = "local_v2_" + StreamingJsonRequestBody.sha256(JSONObject()
            .put("version_token", File(path).name).put("sha256", sha).put("filename", name))
        val attachment = OpenRouterLocalAttachment(id, name, "text", path, metadata.getString("mime_type"),
            metadata.getLong("size_bytes"), sha, textPath = path, textEncoding = "UTF-8")
        return metadata.put("sha256", sha).put("file_id", id).put("attachment", attachment.toJson())
    }

    private fun mimeForFilename(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "log" -> "text/plain"
        "md", "markdown" -> "text/markdown"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "kt", "java", "kotlin", "py", "js", "ts", "xml", "yaml", "yml" -> "text/plain"
        "json" -> "application/json"
        else -> "text/plain"
    }
}
