package com.example.openaichat

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Offline decoder for already saved direct-OpenAI data. Never executes tools or transport. */
object LegacyOpenAiCheckpoint {
    private const val OUTPUT_PREFIX_FIELD = "openai_tool_output_prefix_attached"
    private const val ROOT = "openai_received_sse"
    private val terminalStatuses = setOf("completed", "cancelled", "failed", "incomplete")
    private val names = setOf("list_attached_files", "search_attached_file", "read_attached_file", "read_attached_table", "patch_local_file")

    private fun committedTelemetry(checkpoint: JSONObject): JSONObject {
        val prior = checkpoint.optJSONArray("prior_output") ?: JSONArray()
        val outputIds = (0 until prior.length()).mapNotNull { prior.optJSONObject(it) }
            .filter { it.optString("type") == "function_call_output" }.map { it.optString("call_id") }.toSet()
        val tools = JSONArray()
        for (i in 0 until prior.length()) {
            val item = prior.optJSONObject(i) ?: continue
            if (item.optString("type") == "function_call" && item.optString("call_id") in outputIds && item.optString("name") in names)
                tools.put(item.getString("name"))
        }
        return JSONObject().put("rounds_completed", checkpoint.optInt("round"))
            .put("calls_executed", tools.length()).put("executed_tools", tools)
    }

    fun durableResponse(checkpoint: JSONObject?): JSONObject? {
        val saved = checkpoint ?: return null
        val finished = saved.optString("phase") == "finished"
        val source = if (finished) saved.optJSONObject("final_response") else saved.optJSONObject("received_response")
        val prior = saved.optJSONArray("prior_output")
        if (source == null && (prior == null || prior.length() == 0)) return null
        val response = source?.let(JsonProtocol::objectCopy) ?: JSONObject()
            .put("id", JsonProtocol.nonBlank(saved, "parent_id") ?: JSONObject.NULL)
        if (!response.optBoolean(OUTPUT_PREFIX_FIELD)) {
            val output = if (finished) response.optJSONArray("output") ?: JSONArray()
                else receivedOutput(saved, JsonProtocol.nonBlank(response, "id"), response.optJSONArray("output") ?: JSONArray())
            if (output.length() > 0 || response.has("output")) response.put("output", output)
            response.put(OUTPUT_PREFIX_FIELD, true)
        }
        response.put("app_local_artifacts", JsonProtocol.arrayCopy(saved.optJSONArray("local_artifacts") ?: JSONArray()))
        return OpenAiUsageLedger.attach(response, OpenAiUsageLedger.resolve(saved, response))
    }

    fun extractTelemetrySummary(checkpoint: JSONObject?): JSONObject? {
        val saved = checkpoint ?: return null
        val out = saved.optJSONObject("telemetry")?.let(JsonProtocol::objectCopy) ?: committedTelemetry(saved)
        val ledger = OpenAiUsageLedger.fromCheckpoint(saved)
        if (OpenAiUsageLedger.total(ledger) != null) out.put(OpenAiUsageLedger.FIELD, ledger)
        return out.takeIf { it.length() > 0 }
    }

    private fun receivedOutput(saved: JSONObject?, responseId: String?, current: JSONArray): JSONArray {
        if (saved == null) return current
        if (saved.optString("phase") == "finished") return saved.optJSONObject("final_response")?.optJSONArray("output") ?: current
        val prior = saved.optJSONArray("prior_output") ?: return current
        val paid = saved.optJSONObject("received_response")
        // A prepared/posting batch already includes its parent's output. Legacy checkpoints
        // cannot name every earlier parent, so a stale first response must not be appended.
        if (prior.length() > 0 && saved.optString("phase") in setOf("prepared", "posting")) return JsonProtocol.arrayCopy(prior)
        val paidId = paid?.let { JsonProtocol.nonBlank(it, "id") }
        val waitingId = JsonProtocol.nonBlank(saved, "response_id")
        val belongsToWaitingChild = saved.optString("phase") == "waiting" && responseId == waitingId
        if (prior.length() > 0 && saved.optString("phase") == "waiting" && !belongsToWaitingChild && paid == null)
            return JsonProtocol.arrayCopy(prior)
        val paidIsTerminal = paid?.optString("status") in terminalStatuses
        val usePaid = paid != null && (current.length() == 0 || paidIsTerminal && (paidId == responseId || paidId == waitingId) ||
            responseId != paidId && !belongsToWaitingChild)
        val effectiveCurrent = if (usePaid) paid?.optJSONArray("output") ?: JSONArray() else current
        if (prior.length() == 0) return effectiveCurrent
        val result = JsonProtocol.arrayCopy(prior)
        val effectiveId = if (usePaid) paidId else responseId
        if (effectiveId != saved.optString("parent_id")) for (i in 0 until effectiveCurrent.length()) result.put(JsonProtocol.copy(effectiveCurrent.get(i)))
        return result
    }

    fun completePendingOutputs(output: JSONArray): JSONArray = JsonProtocol.arrayCopy(output).apply {
        val answered = (0 until length()).mapNotNull { optJSONObject(it) }
            .filter { it.optString("type") == "function_call_output" }.map { it.optString("call_id") }.toMutableSet()
        val calls = (0 until length()).mapNotNull { optJSONObject(it) }.filter { it.optString("type") == "function_call" }
        for (call in calls) {
            val id = JsonProtocol.nonBlank(call, "call_id") ?: continue
            if (answered.add(id)) put(JSONObject().put("type", "function_call_output").put("call_id", id)
                .put("output", JSONObject().put("ok", false).put("status", "outcome_unknown")
                    .put("error", "Результат исторического вызова инструмента неизвестен; повторное исполнение не выполнялось").toString()))
        }
    }

    fun terminalOutputText(response: JSONObject?): String {
        val output = response?.optJSONArray("output") ?: return ""
        val text = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (JsonProtocol.string(item, "type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                when (JsonProtocol.string(part, "type")) {
                    "output_text" -> text.append(JsonProtocol.string(part, "text"))
                    "refusal" -> text.append(JsonProtocol.string(part, "refusal"))
                }
            }
        }
        return text.toString()
    }

    /** Only a finished local cycle or a tool-free saved terminal proves completion. */
    fun knownTerminalStatus(checkpoint: JSONObject?, state: ResponseEventState?): String? {
        val response = if (checkpoint != null) {
            if (checkpoint.optString("phase") != "finished") return null
            durableResponse(checkpoint)
        } else {
            val terminal = state?.terminalState ?: return null
            state.terminalResponse?.takeIf { JsonProtocol.string(it, "status") == terminal.wireStatus }
        } ?: return null
        val status = JsonProtocol.string(response, "status").takeIf { it in terminalStatuses } ?: return null
        if (status == "completed") {
            val output = response.optJSONArray("output") ?: JSONArray()
            val results = (0 until output.length()).mapNotNull { output.optJSONObject(it) }
                .filter { it.optString("type") == "function_call_output" }.mapNotNull { JsonProtocol.nonBlank(it, "call_id") }.toSet()
            if ((0 until output.length()).mapNotNull { output.optJSONObject(it) }
                    .any { it.optString("type") == "function_call" && JsonProtocol.nonBlank(it, "call_id") !in results }) return null
        }
        return status
    }

    /** Strict source ownership; malformed archived JSON stops cleanup instead of releasing files. */
    fun ownedPaths(checkpoint: JSONObject): Set<String> {
        val paths = linkedSetOf<String>()
        fun array(source: JSONObject, key: String): JSONArray? {
            if (!source.has(key) || source.isNull(key)) return null
            return source.optJSONArray(key) ?: error("Invalid legacy $key metadata")
        }
        fun addPath(source: JSONObject, key: String) {
            if (!source.has(key) || source.isNull(key)) return
            val path = JsonProtocol.text(source, key) ?: error("Invalid legacy $key reference")
            if (path.isNotBlank()) paths.add(path)
        }
        fun addManifest(manifest: JSONObject) {
            for (key in listOf("path", "text_path", "wire_path")) addPath(manifest, key)
        }
        array(checkpoint, "attachments")?.let { attachments ->
            for (i in 0 until attachments.length())
                addManifest(attachments.optJSONObject(i) ?: error("Invalid legacy attachment metadata"))
        }
        array(checkpoint, "local_artifacts")?.let { artifacts ->
            for (i in 0 until artifacts.length()) {
                val item = artifacts.optJSONObject(i) ?: error("Invalid legacy artifact metadata")
                for (key in listOf("path", "local_path", "source_path", "patch_diff_path", "diff_path"))
                    addPath(item, key)
                for (key in listOf("attachment", "attachment_manifest")) {
                    if (item.has(key) && !item.isNull(key)) {
                        val manifest = item.optJSONObject(key) ?: error("Invalid legacy artifact manifest")
                        addManifest(manifest)
                    }
                }
            }
        }
        return paths
    }

    private fun owner(chatId: String, requestId: String): String {
        require(chatId.isNotBlank() && requestId.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((chatId + "\u0000" + requestId).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun archivesFor(filesDir: File, chatId: String, requestId: String): List<File> {
        val prefix = owner(chatId, requestId) + "_"
        val root = File(filesDir, ROOT).canonicalFile
        return root.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".sse") &&
                it.canonicalFile.parentFile == root }
            ?.sortedBy { it.name }.orEmpty()
    }

    fun attachRawSourceToMessage(message: JSONObject, filesDir: File, chatId: String, requestId: String) {
        val files = archivesFor(filesDir, chatId, requestId).filter { it.length() > 0L }
        if (files.isEmpty()) return
        val annotations = message.optJSONArray("annotations") ?: JSONArray()
        val present = (0 until annotations.length()).mapNotNull {
            annotations.optJSONObject(it)?.optString("local_path")
        }.toMutableSet()
        files.forEach { file ->
            if (present.add(file.absolutePath)) {
                annotations.put(JSONObject().put("type", "local_file")
                    .put("file_id", "local_received_" + file.name.removeSuffix(".sse"))
                    .put("filename", "response-source-" + file.name.substringAfter('_'))
                    .put("local_path", file.absolutePath)
                    .put("mime_type", "application/octet-stream")
                    .put("size_bytes", file.length()).put("state", "READY")
                    .put("received_source", true))
            }
        }
        message.put("annotations", annotations)
    }
}
