package com.example.openaichat

import java.io.Closeable

/** Cleanup waits until no UI session or preparation can hold unsaved references. */
object LocalArtifactOwnership {
    internal val lock = Any()
    private var leases = 0

    fun lease(): Closeable = synchronized(lock) {
        leases++
        object : Closeable {
            private var closed = false
            override fun close() = synchronized(lock) {
                if (!closed) { closed = true; leases-- }
            }
        }
    }

    internal fun <T> whenIdle(action: () -> T): T? = synchronized(lock) {
        if (leases != 0 || OpenRouterResponseRuntime.count() != 0) null else action()
    }
}
