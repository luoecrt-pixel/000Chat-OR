package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

/** An explicit, readable export surface; protocol objects are never serialized. */
object DialogConversationExporter {
    private const val ACTIVE_MARKER = "_dialog_export_active"
    private const val WRITE_CHUNK_CHARS = 8 * 1024
    enum class Format(val extension: String, val mimeType: String, val label: String) {
        MARKDOWN("md", "text/markdown", "Markdown (.md)"),
        TEXT("txt", "text/plain", "Текст (.txt)")
    }

    private val terminal = setOf("completed", "failed", "cancelled", "canceled", "stopped", "incomplete", "disconnected")
    private val toolNames = setOf(
        "create_local_text_file", "patch_local_file", "file_list", "file_info", "file_read_range", "file_search", "file_table_stats",
        "list_attached_files", "search_attached_file", "read_attached_file", "read_attached_table",
        "get_file", "get_file_range", "list_directory", "get_pull_request", "list_pull_requests", "list_issues",
        "get_workflow_runs", "get_workflow_run_jobs", "create_file", "update_file", "delete_file",
        "create_branch", "create_pull_request", "create_issue", "update_issue", "add_issue_comment",
        "create_issue_comment", "submit_pull_request_review", "merge_pull_request", "create_or_update_file", "trigger_workflow_dispatch",
        "web_search", "web_fetch", "file_search", "code_interpreter", "image_generation", "computer"
    )

    fun export(title: String, messages: JSONArray?, activeResponse: JSONObject? = null, format: Format = Format.MARKDOWN): String =
        buildString { writeTo(this, title, messages, activeResponse, format) }

    fun export(title: String, messages: List<JSONObject>, activeResponse: JSONObject? = null, format: Format = Format.MARKDOWN): String =
        buildString { writeTo(this, title, messages, activeResponse, format) }

    /** Writes incrementally without assembling a second, whole-conversation String. Does not close [out]. */
    fun writeTo(out: Appendable, title: String, messages: JSONArray?, activeResponse: JSONObject? = null, format: Format = Format.MARKDOWN) =
        writeTo(out, title, if (messages == null) emptyList() else (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }, activeResponse, format)

    fun writeTo(out: Appendable, title: String, messages: List<JSONObject>, activeResponse: JSONObject? = null, format: Format = Format.MARKDOWN) {
        val visible = messages.filter { text(it, "role") in setOf("user", "assistant") }.toMutableList()
        if (activeResponse != null && text(activeResponse, "role").let { it.isEmpty() || it == "assistant" }) {
            val active = JsonProtocol.objectCopy(activeResponse).put("role", "assistant").put(ACTIVE_MARKER, true)
            val request = text(active, "request_id").takeIf { it.isNotBlank() }
            val index = if (request == null) -1 else visible.indexOfLast {
                text(it, "role") == "assistant" && text(it, "request_id") == request
            }
            if (index < 0) visible.add(active)
            else {
                val stored = visible[index]
                val oldText = text(stored, "text")
                val newText = text(active, "text")
                if (text(stored, "status") !in terminal) {
                    visible[index] = JsonProtocol.objectCopy(stored).put(ACTIVE_MARKER, true).apply {
                        if (newText.isNotEmpty() && (oldText.isEmpty() || newText.startsWith(oldText))) put("text", newText)
                        if (active.has("status")) put("status", active.opt("status"))
                    }
                }
            }
        }
        with(out) {
            append(if (format == Format.MARKDOWN) "# " else "")
            appendBounded(title)
            append("\n\n")
            visible.forEach { message ->
                val assistant = text(message, "role") == "assistant"
                append(if (format == Format.MARKDOWN) "## " else "")
                append(if (assistant) "Ассистент" else "Пользователь")
                if (assistant && message.optBoolean(ACTIVE_MARKER)) append(" — незавершённый активный ответ")
                append("\n\n")
                // Never trim or filter message source: spaces, fences, math and Unicode are data.
                appendBounded(text(message, "text"))
                append("\n\n")
                if (assistant) append("Статус: ").append(status(message)).append("\n")
                attachments(message).forEach { append("Вложение: ").append(it).append("\n") }
                if (assistant) tools(message).forEach { append("Инструмент: ").append(it).append("\n") }
                append('\n')
            }
        }
    }

    private fun Appendable.appendBounded(value: String) {
        var start = 0
        while (start < value.length) {
            var end = start + minOf(WRITE_CHUNK_CHARS, value.length - start)
            // Keep UTF-16 pairs together even for consumers that encode each append independently.
            if (end < value.length && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end--
            append(value, start, end)
            start = end
        }
    }

    private fun text(value: JSONObject, key: String) = value.opt(key) as? String ?: ""
    private fun status(value: JSONObject, allowResultFlag: Boolean = false): String {
        if (value.opt("cancelled") == true || value.opt("canceled") == true) return "отменено"
        val explicit = text(value, "status")
        if (explicit.isNotEmpty()) return when (explicit) {
            "completed", "success", "succeeded", "готово" -> "готово"
            "failed", "error", "ошибка" -> "ошибка"
            "cancelled", "canceled", "stopped" -> "отменено"
            "incomplete", "disconnected" -> "неполный ответ"
            "in_progress", "running", "starting", "queued" -> "выполняется"
            else -> "неизвестно"
        }
        return if (allowResultFlag) when (value.opt("ok")) { true -> "готово"; false -> "ошибка"; else -> "неизвестно" }
            else "неизвестно"
    }

    private fun fileName(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\')
        .filter { !it.isISOControl() }.take(160)

    private fun attachments(message: JSONObject): List<String> = buildList {
        message.optJSONArray("file_names")?.let { names ->
            for (i in 0 until names.length()) (names.opt(i) as? String)?.let(::fileName)?.takeIf { it.isNotBlank() }?.let(::add)
        }
        fileName(text(message, "image_name")).takeIf { it.isNotBlank() }?.let(::add)
        message.optJSONArray("attachments")?.let { list ->
            for (i in 0 until list.length()) list.optJSONObject(i)?.let {
                fileName(text(it, "name")).takeIf { name -> name.isNotBlank() }?.let(::add)
            }
        }
        for (key in listOf("annotations", "generated_images")) message.optJSONArray(key)?.let { list ->
            for (i in 0 until list.length()) list.optJSONObject(i)?.let {
                if (key == "generated_images" || text(it, "type") in setOf("file_citation", "container_file_citation", "local_file"))
                    fileName(text(it, "filename")).takeIf { name -> name.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun safeToolName(raw: String): String = raw.removePrefix("openrouter:").let {
        if (it in toolNames) it else "инструмент"
    }

    private fun resultSummary(result: JSONObject): String {
        val filename = if (text(result, "type") == "local_file") fileName(text(result, "filename")) else ""
        return status(result, allowResultFlag = true) + if (filename.isNotBlank()) "; файл: $filename" else ""
    }

    private fun tools(message: JSONObject): List<String> = buildList {
        message.optJSONArray("tool_trace")?.let { trace ->
            for (i in 0 until trace.length()) trace.optJSONObject(i)?.let { entry ->
                // summary/error/target arguments are arbitrary provider or tool text, so omit them.
                add(safeToolName(text(entry, "name")) + " — " + status(entry, allowResultFlag = true))
            }
        }
        val output = message.optJSONArray("openai_protocol_output") ?: return@buildList
        val results = mutableMapOf<String, JSONObject>()
        for (i in 0 until output.length()) output.optJSONObject(i)?.let { item ->
            if (text(item, "type") == "function_call_output") {
                val id = text(item, "call_id")
                val raw = text(item, "output")
                if (id.isNotEmpty() && raw.length <= 64 * 1024)
                    runCatching { JSONObject(raw) }.getOrNull()?.let { results[id] = it }
            }
        }
        val seen = mutableSetOf<String>()
        for (i in 0 until output.length()) output.optJSONObject(i)?.let { item ->
            when (val type = text(item, "type")) {
                "function_call" -> {
                    val id = text(item, "call_id")
                    if (id.isEmpty() || seen.add(id)) {
                        val result = results[id]
                        add(safeToolName(text(item, "name")) + " — " + (result?.let(::resultSummary) ?: "неизвестно"))
                    }
                }
                "web_search_call", "web_fetch_call", "file_search_call", "code_interpreter_call", "image_generation_call", "computer_call" ->
                    add(safeToolName(type.removeSuffix("_call")) + " — " + status(item))
            }
        }
    }
}
