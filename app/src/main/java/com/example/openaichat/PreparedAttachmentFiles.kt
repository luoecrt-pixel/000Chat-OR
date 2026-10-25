package com.example.openaichat

import java.io.Closeable
import java.io.File
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/** Request-scoped ownership of new derivatives, never imported originals. */
object PreparedAttachmentFiles {
    private val current = ThreadLocal<Lease?>()
    private val live = ConcurrentHashMap<String, Lease>()
    private const val JOURNAL = "prepared_attachment_derivatives"
    data class Owners(val durable: Set<String>, val active: Set<String> = emptySet())
    fun lease(context: Context) = Lease(File(context.filesDir, "attachments"), context.getSharedPreferences(JOURNAL, Context.MODE_PRIVATE))
    class Lease(directory: File, private val journal: SharedPreferences? = null) {
        internal val directory = directory.canonicalFile.apply { mkdirs() }
        private val artifactOwnership = LocalArtifactOwnership.lease()
        private val id = java.util.UUID.randomUUID().toString()
        private val created = linkedSetOf<File>()
        private var released = false
        init { live[id] = this }
        fun capture(): Closeable {
            val previous = current.get()
            current.set(this)
            return Closeable { current.set(previous) }
        }
        @Synchronized internal fun record(file: File) {
            check(!released)
            val owned = file.canonicalFile
            require(owned.parentFile == directory && owned.name.startsWith("derived_"))
            created.add(owned)
            journal?.edit()?.putString(id, JSONArray(created.map { it.path }).toString())?.commit()
        }
        /** Caller snapshots durable/composer/runtime references immediately before release. */
        @Synchronized fun release(references: Owners) {
            if (released) return
            // If ownership lookup failed, the caller must not call release with an empty guess.
            val durable = references.durable.map { File(it).canonicalFile }.toSet()
            val keep = durable + references.active.map { File(it).canonicalFile }
            created.forEach { file -> if (file !in keep && file.isFile) file.delete() }
            val retained = created.filter { it.exists() && it !in durable }
            journal?.edit()?.apply { if (retained.isEmpty()) remove(id) else putString(id, JSONArray(retained.map { it.path }).toString()) }?.commit()
            released = true
            live.remove(id, this)
            artifactOwnership.close()
        }
        @Synchronized fun defer() { released = true; live.remove(id, this); artifactOwnership.close() }
    }
    fun derivative(directory: File, suffix: String): File {
        val lease = current.get()
        return File(lease?.directory ?: directory, "derived_" + java.util.UUID.randomUUID() + suffix).also { lease?.record(it) }
    }
    /** Captured live IDs protect preparations that finish while ownership is being queried. */
    fun cleanupPending(context: Context, references: () -> Owners) {
        val protectedLeases = live.keys.toMutableSet()
        val journal = context.getSharedPreferences(JOURNAL, Context.MODE_PRIVATE)
        val entries = journal.all.toMap()
        protectedLeases.addAll(live.keys)
        val owners = references()
        val durable = owners.durable.map { File(it).canonicalFile }.toSet()
        val keep = durable + owners.active.map { File(it).canonicalFile }
        val directory = File(context.filesDir, "attachments").canonicalFile
        for ((id, raw) in entries) {
            if (id in protectedLeases || live.containsKey(id) || raw !is String) continue
            val paths = try { JSONArray(raw) } catch (_: Exception) { continue }
            val retained = JSONArray()
            for (i in 0 until paths.length()) {
                val name = JsonProtocol.string(paths, i)
                if (name.isBlank()) continue
                val file = File(name).canonicalFile
                if (file.parentFile == directory && file.name.startsWith("derived_") && file !in keep && file.isFile) file.delete()
                if (file.parentFile == directory && file.name.startsWith("derived_") && file.exists() && file !in durable) retained.put(file.path)
            }
            journal.edit().apply { if (retained.length() == 0) remove(id) else putString(id, retained.toString()) }.commit()
        }
    }
}
