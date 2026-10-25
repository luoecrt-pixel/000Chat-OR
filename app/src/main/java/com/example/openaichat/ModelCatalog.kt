package com.example.openaichat

data class ModelOption(
    val id: String,
    val label: String
)

data class ApiSourceOption(
    val id: String,
    val label: String,
    val models: List<ModelOption>
)

object ModelCatalog {
    // Read-only labels for imported history; these are never selectable or sent.
    private val legacyDisplayNames = mapOf(
        "gpt-6-astra" to "GPT-6 Astra",
        "gpt-6.1-sol" to "GPT-6.1 Sol",
        "gpt-6-luna" to "GPT-6 Luna"
    )

    val sources: List<ApiSourceOption> = listOf(
        ApiSourceOption(
            id = "openrouter",
            label = "OpenRouter / Anthropic",
            models = listOf(
                ModelOption("anthropic/claude-opus-5.5", "Claude Opus 5.5"),
                ModelOption("anthropic/claude-sonnet-5.5", "Claude Sonnet 5.5"),
                ModelOption("anthropic/claude-haiku-5.5", "Claude Haiku 5.5"),
                ModelOption("anthropic/claude-fable-5.1", "Claude Fable 5.1")
            )
        )
    )

    fun source(id: String?): ApiSourceOption =
        sources.firstOrNull { it.id == id } ?: sources.first()

    fun isClaudeModel(modelId: String?): Boolean =
        modelId?.trim()?.removePrefix("~")?.startsWith("anthropic/claude-") == true

    /** Preserve a configured Claude ID; never silently substitute another selected model. */
    fun normalizedClaudeModel(modelId: String?): String {
        val normalized = modelId?.trim()?.removePrefix("~")?.takeIf { it.isNotEmpty() }
            ?: source("openrouter").models.first().id
        require(isClaudeModel(normalized)) { "Выберите модель Claude через OpenRouter" }
        return normalized
    }

    fun model(providerId: String?, modelId: String?): ModelOption? {
        val normalized = modelId?.trim()?.removePrefix("~").orEmpty()
        return source(providerId).models.firstOrNull { it.id == normalized }
    }

    fun displayName(providerId: String?, modelId: String?): String {
        val normalized = modelId?.trim()?.removePrefix("~").orEmpty()
        return model(providerId, normalized)?.label
            ?: legacyDisplayNames[normalized]
            ?: normalized.ifBlank { "Модель не выбрана" }
    }

    enum class Match {
        EXACT,
        MISMATCH,
        UNKNOWN
    }

    fun match(requestedId: String?, responseId: String?): Match {
        val requested = requestedId?.trim()?.removePrefix("~").orEmpty()
        val response = responseId?.trim()?.removePrefix("~").orEmpty()
        if (requested.isBlank() || response.isBlank()) return Match.UNKNOWN
        return if (requested == response) Match.EXACT else Match.MISMATCH
    }
}
