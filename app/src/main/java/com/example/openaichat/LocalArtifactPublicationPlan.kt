package com.example.openaichat

import android.content.Context
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Cache staging is disposable; published bytes belong to the durable SQL intent. */
class LocalArtifactPublicationPlan internal constructor(
    private val context: Context,
    snapshot: JSONObject,
    private val staged: File,
    private val ownership: Closeable
) : Closeable {
    private val snapshotText = snapshot.toString()
    val snapshot: JSONObject get() = JSONObject(snapshotText)
    private var closed = false

    fun publish(active: () -> Boolean = { true }): JSONObject = synchronized(publicationLock) {
        if (closed) throw IOException("План публикации уже закрыт")
        LocalUtf8FilePatch.checkActive(active)
        val saved = snapshot
        val target = validate(context, saved)
        val publication = saved.getJSONObject("publication")
        if (target.exists()) {
            return@synchronized recoverPublished(context, saved)
                ?: throw IOException("Существующая версия файла не соответствует сохранённому плану; перезапись запрещена")
        }
        val diff = JsonProtocol.nonBlank(publication, "diff_path")?.let(::File)
        if (diff?.exists() == true) throw IOException("Предыдущее применение патча неполно; повторная запись запрещена")
        if (!target.parentFile!!.isDirectory && !target.parentFile!!.mkdirs()) throw IOException("Каталог версий файла недоступен")
        if (diff != null && !diff.parentFile!!.isDirectory && !diff.parentFile!!.mkdirs()) throw IOException("Каталог изменений файла недоступен")
        if (!staged.isFile || staged.length() != publication.getLong("expected_bytes") ||
            LocalUtf8FilePatch.sha256(staged, active) != publication.getString("expected_sha256"))
            throw IOException("Подготовленные данные файла повреждены")
        FileOutputStream(staged, true).use { it.fd.sync() }
        LocalUtf8FilePatch.checkActive(active)
        Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        // After this rename neither Stop nor a failed metadata/SQLite commit may delete the result.
        recoverPublished(context, saved)
            ?: throw IOException("Файл опубликован, но его результат пока не подтверждён")
    }

    override fun close() { synchronized(publicationLock) {
        closed = true
        try { staged.delete() } finally { ownership.close() }
    } }

    companion object {
        private val publicationLock = Any()
        internal fun documentSha256(document: String): String = LocalUtf8FilePatch.hex(
            MessageDigest.getInstance("SHA-256").digest(document.toByteArray(Charsets.UTF_8)))

        /** No target content is generated here. Only an exact persisted diff sidecar may be repaired. */
        fun recoverPublished(context: Context, snapshot: JSONObject): JSONObject? = synchronized(publicationLock) {
            val target = validate(context, snapshot)
            val publication = snapshot.getJSONObject("publication")
            if (!target.isFile || target.length() != publication.getLong("expected_bytes") ||
                LocalUtf8FilePatch.sha256(target) != publication.getString("expected_sha256")) return@synchronized null
            JsonProtocol.nonBlank(publication, "diff_path")?.let { path ->
                val diff = File(path)
                val expected = publication.getString("diff_sha256")
                if (diff.exists()) {
                    if (!diff.isFile || LocalUtf8FilePatch.sha256(diff) != expected) return@synchronized null
                } else {
                    if (!diff.parentFile!!.isDirectory && !diff.parentFile!!.mkdirs()) throw IOException("Каталог изменений файла недоступен")
                    val pending = File.createTempFile("artifact_diff_", ".tmp", context.cacheDir)
                    try {
                        FileOutputStream(pending).use { output ->
                            output.write(publication.getString("diff_document").toByteArray(Charsets.UTF_8))
                            output.fd.sync()
                        }
                        Files.move(pending.toPath(), diff.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    } finally { pending.delete() }
                }
            }
            JsonProtocol.objectCopy(snapshot.getJSONObject("artifact"))
        }

        internal fun validate(context: Context, snapshot: JSONObject): File {
            if (snapshot.optInt("version") != 1) throw IOException("Неизвестный формат плана публикации")
            val artifact = snapshot.getJSONObject("artifact")
            val publication = snapshot.getJSONObject("publication")
            val path = publication.getString("target_path")
            val target = File(path)
            val expectedSha = publication.getString("expected_sha256")
            val expectedBytes = publication.getLong("expected_bytes")
            val generated = File(context.filesDir, "generated").canonicalFile
            val manifest = artifact.getJSONObject("attachment")
            if (!artifact.optBoolean("ok") || artifact.optString("type") != "local_file" ||
                Files.isSymbolicLink(target.toPath()) || target.canonicalFile.parentFile != generated ||
                !expectedSha.matches(Regex("[0-9a-f]{64}")) || expectedBytes < 0 ||
                artifact.getString("path") != path || artifact.getString("sha256") != expectedSha ||
                artifact.getLong("size_bytes") != expectedBytes || manifest.getString("path") != path ||
                manifest.getString("text_path") != path || manifest.getString("sha256") != expectedSha ||
                manifest.getLong("size") != expectedBytes || JsonProtocol.nonBlank(artifact, "file_id") == null ||
                artifact.getString("file_id") != manifest.getString("id")) throw IOException("Метаданные плана публикации повреждены")
            JsonProtocol.nonBlank(publication, "diff_path")?.let { diffPath ->
                val diff = File(diffPath)
                val document = publication.getString("diff_document")
                if (Files.isSymbolicLink(diff.toPath()) || diff.canonicalFile.parentFile != File(generated, "patch-diffs").canonicalFile ||
                    artifact.optString("patch_diff_path") != diffPath || documentSha256(document) != publication.getString("diff_sha256"))
                    throw IOException("Метаданные изменений файла повреждены")
                val saved = JSONObject(document)
                if (saved.optString("result_sha256") != expectedSha ||
                    saved.getJSONObject("artifact").toString() != artifact.toString()) throw IOException("Документ изменений не соответствует версии файла")
            }
            return target
        }
    }
}
