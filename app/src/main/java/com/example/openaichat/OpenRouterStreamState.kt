package com.example.openaichat

import org.json.JSONObject

/** A parsed SSE event and Stop share this fence; no network read holds it. */
class OpenRouterStreamState {
    internal val lock = Any()
    private var source: (() -> OpenRouterStreamSnapshot)? = null
    private var revision = 0L
    private var persistedRevision = 0L

    internal fun bind(source: () -> OpenRouterStreamSnapshot) = synchronized(lock) { this.source = source; revision++ }
    internal fun changed() = synchronized(lock) { revision++ }
    internal fun persisted() = synchronized(lock) { persistedRevision = revision }
    internal fun dirtySnapshot(): OpenRouterStreamSnapshot? = synchronized(lock) {
        if (persistedRevision == revision) null else source?.invoke()
    }
    internal fun snapshot(): OpenRouterStreamSnapshot? = synchronized(lock) { source?.invoke() }
}

data class OpenRouterStreamSnapshot(val text: String, val protocol: JSONObject)
