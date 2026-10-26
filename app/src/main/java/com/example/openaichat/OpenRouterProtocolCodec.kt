package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class OpenRouterToolOutcomeUnknownException(message: String) : IOException(message)

/** UI text and the signed protocol are distinct representations. */
object OpenRouterProtocolCodec {
    private const val BINDING = "_astra_binding_sha256"
    private const val RESET = "_astra_reset_reasoning"
    private const val VALID_PREFIX = "_astra_prefix_valid"

    /**
     * Every tool_call declared by an assistant message in tail must have a matching
     * role="tool" message before the next turn is sent, even when the request was
     * stopped or crashed between tool A and tool B. Already completed tools keep
     * their exact results and are never re-executed.
     */
    fun completePendingToolCalls(tail: JSONArray, toolStates: JSONObject? = null, requireSafeReplay: Boolean = true) {
        val normalized = mutableListOf<JSONObject>()
        var i = 0
        while (i < tail.length()) {
            val item = tail.optJSONObject(i)
            if (item == null) {
                if (requireSafeReplay) throw OpenRouterToolOutcomeUnknownException("Повреждённая запись протокола; отправка заблокирована.")
                return // Preserve the raw checkpoint rather than discard unknown data.
            }
            if (item.optString("role") == "tool") {
                if (requireSafeReplay) throw OpenRouterToolOutcomeUnknownException("Результат инструмента не связан с вызовом; отправка заблокирована.")
                return
            }
            normalized.add(item)
            if (item.optString("role") == "assistant") {
                val calls = item.optJSONArray("tool_calls")
                if (calls != null && calls.length() > 0) {
                    val expectedIds = mutableListOf<String>()
                    for (c in 0 until calls.length()) {
                        val id = calls.optJSONObject(c)?.let { JsonProtocol.nonBlank(it, "id") }?.trim()
                        if (id.isNullOrEmpty() || id in expectedIds) {
                            if (requireSafeReplay) throw OpenRouterToolOutcomeUnknownException("Неоднозначный идентификатор инструмента; отправка заблокирована.")
                            return
                        }
                        expectedIds.add(id)
                    }
                    val seenIds = mutableSetOf<String>()
                    var j = i + 1
                    while (j < tail.length()) {
                        val next = tail.optJSONObject(j) ?: break
                        if (next.optString("role") != "tool") break
                        val resultId = JsonProtocol.nonBlank(next, "tool_call_id")?.trim()
                        if (resultId == null || resultId !in expectedIds || !seenIds.add(resultId)) {
                            if (requireSafeReplay) throw OpenRouterToolOutcomeUnknownException("Неоднозначный результат инструмента; отправка заблокирована.")
                            return
                        }
                        normalized.add(next)
                        j++
                    }
                    for (missingId in expectedIds) {
                        if (missingId !in seenIds) {
                            // Absence is not proof of cancellation: the process may have died
                            // after a side effect and before saving its exact result.
                            val evidence = toolStates?.optJSONObject(missingId)
                            val call = (0 until calls.length()).mapNotNull { calls.optJSONObject(it) }
                                .firstOrNull { JsonProtocol.nonBlank(it, "id")?.trim() == missingId }
                            val function = call?.optJSONObject("function")
                            val argumentsHash = function?.let { fn ->
                                runCatching { StreamingJsonRequestBody.sha256(JSONObject(JsonProtocol.string(fn, "arguments", "{}"))) }.getOrNull()
                            }
                            val provenNotStarted = evidence?.optString("state") == "not_started" &&
                                evidence.optString("tool_name") == function?.optString("name")?.trim() &&
                                argumentsHash != null && evidence.optString("arguments_sha256") == argumentsHash
                            if (!provenNotStarted) {
                                if (requireSafeReplay) throw OpenRouterToolOutcomeUnknownException(
                                    "Результат инструмента $missingId неизвестен; повторная отправка контекста заблокирована.")
                                continue
                            }
                            normalized.add(
                                JSONObject()
                                    .put("role", "tool")
                                    .put("tool_call_id", missingId)
                                    .put(
                                        "content",
                                        JSONObject()
                                            .put("ok", false)
                                            .put("cancelled", true)
                                            .put("status", "cancelled")
                                            .put("error", "Выполнение инструмента отменено: запрос был остановлен до запуска.")
                                            .toString()
                                    )
                            )
                        }
                    }
                    i = j
                    continue
                }
            }
            i++
        }
        while (tail.length() > 0) tail.remove(tail.length() - 1)
        normalized.forEach { tail.put(it) }
    }

    /**
     * Attach an independently verified, completed SQL publication to its original
     * call. This never executes a tool, changes an assistant block, or overwrites
     * an existing result. The caller owns publication verification and SQL CAS.
     */
    fun reconcileCompletedLocalArtifact(protocol: JSONObject, callId: String, toolName: String,
        argumentsSha256: String, outcome: JSONObject): JSONObject? {
        fun conflict(): Nothing = throw OpenRouterToolOutcomeUnknownException(
            "Сохранённый результат файла не соответствует протоколу; отправка заблокирована.")
        if (callId.isBlank() || toolName.isBlank() || argumentsSha256.isBlank()) conflict()
        if (protocol.optInt("version") != 1) conflict()
        val tail = protocol.optJSONArray("tail") ?: conflict()
        if (!outcome.optBoolean("ok") || outcome.optString("type") != "local_file" ||
            JsonProtocol.nonBlank(outcome, "file_id") == null) conflict()
        val wireResult = LocalArtifactToolExecutor.toolResult(outcome).toString()
        var insertionIndex = -1
        var matchingResult: JSONObject? = null
        var matches = 0
        var i = 0
        while (i < tail.length()) {
            val message = tail.optJSONObject(i) ?: conflict()
            if (message.optString("role") == "tool") conflict()
            val calls = message.optJSONArray("tool_calls")
            if (message.has("tool_calls") && !message.isNull("tool_calls") && calls == null) conflict()
            if (calls == null || calls.length() == 0) { i++; continue }
            if (message.optString("role") != "assistant") conflict()
            val ids = mutableListOf<String>()
            var targetRank = -1
            for (c in 0 until calls.length()) {
                val call = calls.optJSONObject(c) ?: conflict()
                val id = JsonProtocol.nonBlank(call, "id")?.trim() ?: conflict()
                if (id in ids) conflict()
                ids.add(id)
                val function = call.optJSONObject("function") ?: conflict()
                val name = JsonProtocol.nonBlank(function, "name")?.trim() ?: conflict()
                val arguments = JsonProtocol.text(function, "arguments") ?: conflict()
                val hash = try { StreamingJsonRequestBody.sha256(JSONObject(arguments)) }
                    catch (_: Exception) { conflict() }
                if (id == callId) {
                    matches++
                    if (matches != 1 || name != toolName || hash != argumentsSha256) conflict()
                    targetRank = c
                }
            }
            var j = i + 1
            var lastRank = -1
            var insertAt = -1
            while (j < tail.length()) {
                val result = tail.optJSONObject(j) ?: conflict()
                if (result.optString("role") != "tool") break
                val id = JsonProtocol.nonBlank(result, "tool_call_id")?.trim() ?: conflict()
                val rank = ids.indexOf(id)
                if (rank < 0 || rank <= lastRank) conflict()
                lastRank = rank
                if (targetRank >= 0 && rank > targetRank && insertAt < 0) insertAt = j
                if (targetRank >= 0 && id == callId) {
                    if (JsonProtocol.text(result, "content") != wireResult) conflict()
                    matchingResult = result
                }
                j++
            }
            if (targetRank >= 0) insertionIndex = if (insertAt >= 0) insertAt else j
            i = j
        }
        if (matches == 0) return null
        val states = protocol.optJSONObject("tool_states")
        if (protocol.has("tool_states") && !protocol.isNull("tool_states") && states == null) conflict()
        val state = states?.optJSONObject(callId)
        if (states?.has(callId) == true && state == null) conflict()
        if (state != null && (state.optString("tool_name") != toolName ||
                state.optString("arguments_sha256") != argumentsSha256 ||
                state.optString("state") !in setOf("in_flight", "outcome_unknown", "completed"))) conflict()
        if (matchingResult != null && state?.optString("state") == "completed") return null
        val reconciled = JsonProtocol.objectCopy(protocol)
        if (matchingResult == null) {
            val repairedTail = JSONArray()
            for (index in 0..tail.length()) {
                if (index == insertionIndex) repairedTail.put(JSONObject().put("role", "tool")
                    .put("tool_call_id", callId).put("content", wireResult))
                if (index < tail.length()) repairedTail.put(JsonProtocol.copy(tail.get(index)))
            }
            reconciled.put("tail", repairedTail)
        }
        val repairedStates = reconciled.optJSONObject("tool_states") ?: JSONObject().also { reconciled.put("tool_states", it) }
        val completed = state?.let(JsonProtocol::objectCopy) ?: JSONObject()
        repairedStates.put(callId, completed.put("state", "completed").put("tool_name", toolName)
            .put("arguments_sha256", argumentsSha256))
        return reconciled
    }

    fun append(destination: JSONArray, stored: JSONObject): Boolean {
        if (JsonProtocol.string(stored, "protocol_context_state") == "invalidated") return false
        val protocol = stored.optJSONObject("openrouter_protocol") ?: return false
        if (protocol.optInt("version") != 1) return false
        val rawTail = protocol.optJSONArray("tail") ?: return false
        // Older terminal error handling invented this placeholder even when an
        // HTTP rejection produced no model content. Keep its local evidence,
        // but do not turn it into an empty assistant message in the next POST.
        // Signed/tool-bearing or otherwise unfamiliar blocks are never filtered.
        if (stored.optString("status") in setOf("failed", "disconnected") && rawTail.length() == 1 &&
            (!protocol.has("tool_states") || protocol.optJSONObject("tool_states")?.length() == 0)) {
            val placeholder = rawTail.optJSONObject(0)
            if (placeholder?.length() == 2 && placeholder.optString("role") == "assistant" &&
                placeholder.opt("content") is String && placeholder.getString("content").isEmpty()) {
                // A stale placeholder must not hide text retained in the local
                // terminal record. Fall back to that text when it is present.
                return JsonProtocol.string(stored, "text").isEmpty()
            }
        }
        val tail = JsonProtocol.arrayCopy(rawTail).also { completePendingToolCalls(it, protocol.optJSONObject("tool_states")) }
        val exactPrefix = protocol.optString("input_sha256") == fingerprint(destination)
        for (i in 0 until tail.length()) {
            val message = tail.optJSONObject(i) ?: continue
            destination.put(JsonProtocol.objectCopy(message).apply {
                put(BINDING, protocol.optString("binding_sha256"))
                if (!exactPrefix) put(RESET, true)
                if (i == 0 && exactPrefix) put(VALID_PREFIX, true)
            })
        }
        return true
    }

    fun fingerprint(messages: JSONArray): String {
        val clean = JsonProtocol.arrayCopy(messages)
        for (i in 0 until clean.length()) clean.optJSONObject(i)?.apply { remove(BINDING); remove(RESET); remove(VALID_PREFIX) }
        return StreamingJsonRequestBody.sha256(clean)
    }

    /** Changing a signed prefix invalidates all subsequent thinking blocks. */
    fun checkBinding(messages: JSONArray, expected: String): Boolean {
        var reset = false
        var dropped = false
        for (i in 0 until messages.length()) {
            val item = messages.optJSONObject(i) ?: continue
            val binding = JsonProtocol.nonBlank(item, BINDING)
            if (item.optBoolean(VALID_PREFIX, false) && binding == expected) reset = false
            if (item.optBoolean(RESET, false)) reset = true
            // Old account/model blocks do not invalidate newer blocks signed after that switch.
            if (reset || (binding != null && binding != expected)) {
                if (item.has("reasoning_details") || item.has("reasoning")) dropped = true
                item.remove("reasoning_details"); item.remove("reasoning")
            }
            item.remove(BINDING); item.remove(RESET); item.remove(VALID_PREFIX)
        }
        return dropped
    }

    fun binding(model: String, tools: JSONArray?, apiKey: String, responseFormat: JSONObject? = null,
        claudeRoute: OpenRouterClaudeRoute = OpenRouterClaudeRoute.AUTO): String = StreamingJsonRequestBody.sha256(
        JSONObject().put("model", model).put("tools", tools ?: JSONArray())
            .put("credential_hash", StreamingJsonRequestBody.sha256(apiKey)).apply {
                responseFormat?.let { put("response_format", it) }
                // Keep old Auto bindings byte-compatible; a deliberate endpoint switch invalidates replay only.
                if (OpenRouterClaudeRoute.isClaude(model) && claudeRoute != OpenRouterClaudeRoute.AUTO)
                    put("claude_route", claudeRoute.preference)
            })

    fun withVisibleTail(checkpoint: JSONObject, message: JSONObject): JSONObject = JsonProtocol.objectCopy(checkpoint).apply {
        val tail = getJSONArray("tail")
        completePendingToolCalls(tail, optJSONObject("tool_states"), requireSafeReplay = false)
        val priorAssistantTexts = (0 until tail.length()).mapNotNull { i ->
            val item = tail.optJSONObject(i)
            if (item?.optString("role") == "assistant") {
                JsonProtocol.string(item, "content").trim().takeIf { it.isNotEmpty() }
            } else null
        }
        val fullText = JsonProtocol.string(message, "text")
        val priorJoined = priorAssistantTexts.joinToString("\n\n")
        val unrepresentedText = when {
            priorJoined.isEmpty() -> fullText
            fullText == priorJoined -> ""
            fullText.startsWith("$priorJoined\n\n") -> fullText.removePrefix("$priorJoined\n\n")
            else -> fullText
        }
        if (unrepresentedText.isNotEmpty()) {
            tail.put(JSONObject().put("role", "assistant").put("content", unrepresentedText))
        }
    }
}
