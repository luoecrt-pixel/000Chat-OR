package com.example.openaichat

data class GenerationInstructions(
    val systemPrompt: String?,
    val developerInstructions: String?,
    val presentationInstructions: String? = null
) {
    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    fun openRouter(
        githubContext: String?,
        attachmentContext: String?,
        localFileToolContext: String?
    ): String? =
        listOfNotNull(
            clean(githubContext),
            clean(systemPrompt),
            clean(developerInstructions),
            clean(attachmentContext),
            clean(localFileToolContext),
            clean(presentationInstructions)
        ).joinToString("\n\n").takeIf { it.isNotEmpty() }
}
