package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

/** Captures only inspector headers and flat attachment metadata, never a protocol/tool JSON graph. */
class ContextHistorySnapshot private constructor(
    val messages: List<JSONObject>, private val signatures: List<String?>
) {
    /** Call on the worker with independently loaded durable rows, never with live UI messages. */
    fun validatedDurableHistory(durable: List<JSONObject>): List<JSONObject>? {
        if (durable.size != messages.size || signatures.any { it.isNullOrBlank() }) return null
        for (i in durable.indices) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Проверка контекста отменена")
            if (ChatMessageFingerprint.of(durable[i]) != signatures[i] ||
                StreamingJsonRequestBody.sha256(project(durable[i])) != StreamingJsonRequestBody.sha256(messages[i])) return null
        }
        return durable
    }

    companion object {
        private val scalarKeys = listOf("role", "text", "provider", "status", "request_id", "response_id",
            "protocol_context_state", "image_path", "image_name", "openai_instructions_sha256")

        fun capture(history: List<JSONObject>, items: List<ChatBubbleItem>): ContextHistorySnapshot {
            val fingerprints = java.util.IdentityHashMap<JSONObject, String>()
            items.forEach { item -> item.message?.let { fingerprints[it] = item.streamingBaseSignature ?: item.contentSignature } }
            return ContextHistorySnapshot(history.map(::project), history.map { fingerprints[it] })
        }

        private fun project(message: JSONObject): JSONObject = JSONObject().apply {
            scalarKeys.forEach { key -> JsonProtocol.text(message, key)?.let { put(key, it) } }
            for (key in listOf("file_paths", "file_names", "file_mimes")) message.optJSONArray(key)?.let { source ->
                put(key, JSONArray().apply { for (i in 0 until source.length()) put(JsonProtocol.string(source, i)) })
            }
            message.optJSONArray("attachments")?.let { source ->
                put("attachments", JSONArray().apply { for (i in 0 until source.length()) source.optJSONObject(i)?.let { a ->
                    // OpenRouterLocalAttachment is an immutable, flat value object.
                    put(OpenRouterLocalAttachment.fromJson(a).toJson())
                } })
            }
            message.optJSONObject("openrouter_protocol")?.let { protocol ->
                put("openrouter_protocol", JSONObject().put("version", protocol.optInt("version"))
                    .put("binding_sha256", JsonProtocol.string(protocol, "binding_sha256"))
                    .put("input_sha256", JsonProtocol.string(protocol, "input_sha256"))
                    .put("_context_tail_count", protocol.optJSONArray("tail")?.length() ?: -1))
            }
        }
    }
}
