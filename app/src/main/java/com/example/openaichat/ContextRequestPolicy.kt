package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

/** Small request decisions shared by Send and the read-only context inspector. */
object ContextRequestPolicy {
    fun model(provider: String, configured: String?): String {
        require(provider == "openrouter") { "Поддерживается только OpenRouter" }
        return ModelCatalog.normalizedClaudeModel(configured)
    }

    fun openRouterTools(github: Boolean, webSearch: Boolean, webFetch: Boolean): JSONArray =
        OpenRouterAppToolCatalog.tools().apply {
            if (github) append(this, GitHubToolCatalog.tools())
            append(this, OpenRouterServerToolCatalog.tools(webSearch, webFetch))
        }

    private fun append(target: JSONArray, source: JSONArray) {
        for (i in 0 until source.length()) target.put(source.get(i))
    }

    fun readableFile(name: String, mime: String): Boolean =
        !mime.startsWith("image/") && mime != "application/pdf" && TextFileFormatPolicy.extension(name) != "pdf" &&
            (mime.startsWith("text/") || mime == "application/json" || TextFileFormatPolicy.knownText(name) ||
                TextFileFormatPolicy.extension(name) in TextFileFormatPolicy.office)

    /** This selects preparation; the bridge still adds schemas only for successfully prepared tool files. */
    fun localToolCandidates(history: List<JSONObject>, artifacts: List<JSONObject>): Boolean =
        history.any { message ->
            val manifests = message.optJSONArray("attachments")
            val knownPaths = mutableSetOf<String>()
            var readable = false
            if (manifests != null) for (i in 0 until manifests.length()) manifests.optJSONObject(i)?.let { a ->
                JsonProtocol.nonBlank(a, "path")?.let(knownPaths::add)
                if (a.optString("kind") in setOf("text", "document", "table")) readable = true
            }
            val paths = message.optJSONArray("file_paths")
            if (paths != null) for (i in 0 until paths.length()) {
                val path = JsonProtocol.string(paths, i)
                if (path.isNotBlank() && path !in knownPaths && readableFile(
                        message.optJSONArray("file_names")?.let { JsonProtocol.string(it, i) }.orEmpty().ifBlank { path.substringAfterLast('/') },
                        message.optJSONArray("file_mimes")?.let { JsonProtocol.string(it, i) }.orEmpty())) readable = true
            }
            readable
        } || artifacts.any { artifact ->
            val manifest = artifact.optJSONObject("attachment_manifest") ?: artifact.optJSONObject("attachment")
            if (manifest != null) manifest.optString("kind") in setOf("text", "document", "table") else
                readableFile(JsonProtocol.string(artifact, "filename", JsonProtocol.string(artifact, "path")),
                    JsonProtocol.string(artifact, "mime_type"))
        }
}
