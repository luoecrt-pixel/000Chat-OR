package com.example.openaichat

object ModelRequestPolicy {
    /** Until auto is resolved for a request, the badge must not invent a level. */
    fun effortBadge(effort: String, resolvedEffort: String? = null): String =
        if (effort == "auto" && !resolvedEffort.isNullOrBlank())
            "⚡ auto → $resolvedEffort"
        else "⚡ $effort"
}
