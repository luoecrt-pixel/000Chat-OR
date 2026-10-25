package com.example.openaichat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/** Deletes only old, verified tombstoned publications while all owners are quiescent. */
class LocalArtifactCleanup(private val context: Context, private val db: ChatDatabase) {
    private data class Candidate(val file: File, val attributes: BasicFileAttributes?)
    private val graceMs = 7L * 24 * 60 * 60 * 1000

    fun run(nowMs: Long = System.currentTimeMillis()): Int {
        if (LocalArtifactOwnership.whenIdle { true } != true) return 0
        var removed = 0
        for (record in db.listLocalArtifactIntents()) {
            if (!record.getBoolean("tombstoned")) continue
            val updated = record.getLong("updated_at")
            if (updated <= 0L || nowMs < updated || nowMs - updated < graceMs) continue
            val plan = record.getJSONObject("plan")
            LocalArtifactPublicationPlan.validate(context, plan)
            val publication = plan.getJSONObject("publication")
            val expected = mutableListOf(Triple(File(publication.getString("target_path")),
                publication.getString("expected_sha256"), publication.getLong("expected_bytes")))
            JsonProtocol.nonBlank(publication, "diff_path")?.let { path ->
                expected += Triple(File(path), publication.getString("diff_sha256"),
                    publication.getString("diff_document").toByteArray(Charsets.UTF_8).size.toLong())
            }
            // Hash outside the ownership fence/SQL transaction. A changed inode or
            // timestamp invalidates this snapshot before either unlink is attempted.
            val candidates = mutableListOf<Candidate>()
            var verified = true
            for ((file, hash, size) in expected) {
                val before = attributes(file)
                if (before == null) { candidates += Candidate(file, null); continue }
                if (!before.isRegularFile || before.size() != size ||
                    !LocalAttachmentReferences.isOrphanOldEnough(file.path, before.lastModifiedTime().toMillis(), nowMs, emptySet()) ||
                    LocalUtf8FilePatch.sha256(file) != hash || !same(before, attributes(file))) {
                    verified = false; break
                }
                candidates += Candidate(file, before)
            }
            if (!verified) continue
            LocalArtifactOwnership.whenIdle {
                db.withTombstonedArtifactCleanup(record.getString("chat_id"), record.getString("request_id"),
                    record.getString("tool_call_id"), record.getString("tool_name"),
                    record.getString("arguments_sha256"), record.getString("plan_sha256"), updated) { _, durable ->
                    val references = durable + strictComposerPaths(context.getSharedPreferences("app", Context.MODE_PRIVATE).all)
                    if (candidates.any { candidate ->
                            !same(candidate.attributes, attributes(candidate.file)) ||
                                candidate.attributes != null && !LocalAttachmentReferences.isOrphanOldEnough(
                                    candidate.file.path, candidate.attributes.lastModifiedTime().toMillis(), nowMs, references)
                        }) false
                    else {
                        candidates.forEach { candidate ->
                            if (candidate.attributes != null && Files.deleteIfExists(candidate.file.toPath())) removed++
                        }
                        true
                    }
                }
            }
        }
        return removed
    }

    private fun attributes(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: java.nio.file.NoSuchFileException) { null }

    private fun same(first: BasicFileAttributes?, second: BasicFileAttributes?): Boolean {
        if (first == null || second == null) return first == null && second == null
        return first.isRegularFile && second.isRegularFile && first.fileKey() != null &&
            first.fileKey() == second.fileKey() && first.size() == second.size() &&
            first.lastModifiedTime() == second.lastModifiedTime() && first.creationTime() == second.creationTime()
    }

    private fun strictComposerPaths(preferences: Map<String, *>): Set<String> {
        val paths = linkedSetOf<String>()
        fun path(source: JSONObject, key: String) {
            if (!source.has(key) || source.isNull(key)) return
            val value = JsonProtocol.text(source, key) ?: error("Invalid composer reference")
            if (value.isNotBlank()) paths.add(value)
        }
        for ((key, raw) in preferences) {
            if (!key.startsWith("composer_state_")) continue
            check(raw is String) { "Invalid composer metadata" }
            val composer = JSONObject(raw)
            path(composer, "image_path")
            for (field in listOf("file_paths", "openrouter_attachments", "pending_attachments")) {
                if (!composer.has(field) || composer.isNull(field)) continue
                val array = composer.optJSONArray(field) ?: error("Invalid composer attachment metadata")
                for (index in 0 until array.length()) {
                    if (field == "file_paths") {
                        val value = JsonProtocol.text(array, index) ?: error("Invalid composer path")
                        if (value.isNotBlank()) paths.add(value)
                    } else {
                        val item = array.optJSONObject(index) ?: error("Invalid composer attachment")
                        for (name in listOf("path", "text_path", "wire_path")) path(item, name)
                    }
                }
            }
        }
        return paths
    }
}
