package com.example.openaichat

import org.json.JSONObject

/** Test-only fixture decoder for historical on-disk checkpoints. Not packaged in the app. */
data class ResponseEventDecision(
    val state: ResponseEventState,
    val textChanged: Boolean = false,
    val reasoningChanged: Boolean = false,
    val responseIdChanged: Boolean = false
) {
    val terminal: Boolean get() = state.terminalState != null
}

/** Reject non-string SSE protocol fields, especially Android JSONObject.NULL. */
object ResponseEventReducer {
    fun reduce(current: ResponseEventState, event: JSONObject): ResponseEventDecision =
        reduce(current, event, Long.MAX_VALUE)

    internal fun reduce(current: ResponseEventState, event: JSONObject,
                        maxStateAllocationBytes: Long): ResponseEventDecision {
        // A terminal response is immutable. Late SSE callbacks must not append
        // content, change the response ID or replace the canonical terminal.
        if (current.terminalState != null) return ResponseEventDecision(current)
        val receivedIds = listOfNotNull(
            event.optJSONObject("response")?.let { JsonProtocol.nonBlank(it, "id") },
            JsonProtocol.nonBlank(event, "response_id")
        ).distinct()
        if (receivedIds.size > 1 || (current.responseId != null &&
                receivedIds.any { it != current.responseId })) {
            // Reject before canonical output, usage or accumulated parts change.
            // The local request ID alone cannot authorize a different response.
            throw ResponsesResponseIdentityException()
        }
        return when (JsonProtocol.string(event, "type")) {
            "response.created" -> {
                val responseId = event.optJSONObject("response")?.let { JsonProtocol.nonBlank(it, "id") }
                val state = if (responseId == null || responseId == current.responseId) current
                    else current.copy(responseId = responseId)
                ResponseEventDecision(state, responseIdChanged = state.responseId != current.responseId)
            }
            "response.output_item.done" -> {
                val outputIndex = event.optInt("output_index", -1)
                val item = event.optJSONObject("item")
                // Preserve fully received messages too: their refusals and
                // annotations are not reconstructible from visible deltas.
                // Provisional output_item.added still cannot enter typed replay.
                if (outputIndex < 0 || item == null || JsonProtocol.nonBlank(item, "type") == null)
                    ResponseEventDecision(current)
                else {
                    ResponseAccumulatorMemoryBudget.check(current, replacementIndex = outputIndex,
                        replacementItem = item, limitBytes = maxStateAllocationBytes)
                    ResponseEventDecision(current.copy(
                        typedOutputItems = current.typedOutputItems +
                            (outputIndex to JSONObject(item.toString()))
                    ))
                }
            }
            "response.output_text.delta", "response.refusal.delta" ->
                changeText(current, event, JsonProtocol.string(event, "delta"),
                    replacePart = false, maxStateAllocationBytes = maxStateAllocationBytes)
            "response.output_text.done" ->
                changeText(current, event, JsonProtocol.string(event, "text"),
                    replacePart = true, maxStateAllocationBytes = maxStateAllocationBytes)
            "response.refusal.done" ->
                changeText(current, event, JsonProtocol.string(event, "refusal"),
                    replacePart = true, maxStateAllocationBytes = maxStateAllocationBytes)
            "response.reasoning_summary_text.delta" -> {
                val delta = JsonProtocol.string(event, "delta")
                if (delta.isEmpty()) ResponseEventDecision(current)
                else {
                    ResponseAccumulatorMemoryBudget.check(current,
                        summaryCharacters = current.reasoningSummary.length.toLong() + delta.length,
                        limitBytes = maxStateAllocationBytes)
                    ResponseEventDecision(current.copy(reasoningSummary = current.reasoningSummary + delta), reasoningChanged = true)
                }
            }
            "response.completed" -> terminal(current, event, ResponseTerminalState.COMPLETED,
                maxStateAllocationBytes)
            "response.failed" -> terminal(current, event, ResponseTerminalState.FAILED,
                maxStateAllocationBytes,
                event.optJSONObject("response")?.let { response ->
                    response.optJSONObject("error")?.let { JsonProtocol.nonBlank(it, "message") }
                        ?: JsonProtocol.nonBlank(response, "error")
                })
            "response.incomplete" -> terminal(current, event, ResponseTerminalState.INCOMPLETE,
                maxStateAllocationBytes,
                event.optJSONObject("response")?.optJSONObject("incomplete_details")?.let {
                    JsonProtocol.nonBlank(it, "reason")
                })
            "response.cancelled" -> terminal(current, event, ResponseTerminalState.CANCELLED,
                maxStateAllocationBytes)
            "error", "response.error" -> streamingError(current, event, maxStateAllocationBytes)
            else -> ResponseEventDecision(current)
        }
    }

    private fun changeText(
        current: ResponseEventState, event: JSONObject,
        value: String, replacePart: Boolean, maxStateAllocationBytes: Long
    ): ResponseEventDecision {
        // Missing/empty .done payload is not proof that previously streamed
        // text vanished. Only a full terminal response may replace it with an
        // authoritative empty output. Never erase another indexed item.
        if (value.isEmpty()) return ResponseEventDecision(current)
        val outputIndex = event.optInt("output_index", -1)
        val contentIndex = event.optInt("content_index", -1)
        val updated = if (outputIndex >= 0 && contentIndex >= 0) {
            if (current.legacyFlatCheckpoint) {
                throw UnrecoverableResponsesCheckpointException(
                    "Indexed Responses continuation requires an owned checkpoint; " +
                    "legacy flattened partial cannot be safely reordered"
                )
            }
            val key = ResponseContentIndex(outputIndex, contentIndex)
            val oldParts = current.indexedTextParts
            val old = oldParts[key].orEmpty()
            val nextLength = current.text.length.toLong() - (if (replacePart) old.length else 0) + value.length
            ResponseAccumulatorMemoryBudget.check(current, textCharacters = nextLength,
                limitBytes = maxStateAllocationBytes)
            val newValue = if (replacePart) value else old + value
            if (newValue == old) return ResponseEventDecision(current)
            val parts = oldParts + (key to newValue)
            val loose = if (oldParts.isEmpty() && current.unindexedText.isEmpty())
                current.text else current.unindexedText
            current.copy(indexedTextParts = parts, unindexedText = loose,
                text = parts.toSortedMap().values.joinToString("") + loose)
        } else {
            // Old provider/fixture frames do not carry indices: preserve them
            // verbatim in arrival order, without claiming indexed ordering.
            val loose = if (current.indexedTextParts.isEmpty() &&
                current.unindexedText.isEmpty()) current.text else current.unindexedText
            val nextLength = current.text.length.toLong() - (if (replacePart) loose.length else 0) + value.length
            ResponseAccumulatorMemoryBudget.check(current, textCharacters = nextLength,
                limitBytes = maxStateAllocationBytes)
            val next = if (replacePart) value else loose + value
            if (next == loose) return ResponseEventDecision(current)
            current.copy(unindexedText = next, text =
                current.indexedTextParts.toSortedMap().values.joinToString("") + next)
        }
        return ResponseEventDecision(updated, textChanged = updated.text != current.text)
    }

    private fun streamingError(current: ResponseEventState, event: JSONObject,
                               maxStateAllocationBytes: Long): ResponseEventDecision {
        val receivedResponse = event.optJSONObject("response")
        val receivedError = event.optJSONObject("error") ?: receivedResponse?.optJSONObject("error")
        val errorMessage = receivedError?.let { JsonProtocol.nonBlank(it, "message") }
            ?: JsonProtocol.nonBlank(event, "message") ?: "Responses streaming error"
        val error = JSONObject().put("message", errorMessage)
        receivedError?.keys()?.let { keys ->
            while (keys.hasNext()) {
                val key = keys.next()
                error.put(key, receivedError.opt(key))
            }
        }
        // Keep code/details but normalize a missing/non-string message to the
        // readable diagnostic, without mutating the received event object.
        error.put("message", errorMessage)
        if (!error.has("code")) JsonProtocol.nonBlank(event, "code")?.let { error.put("code", it) }
        // error/response.error are terminal too. Preserve the received partial
        // response if present, but bind this failed snapshot to the owned ID.
        // Copy references without serializing an unbounded frame first.
        val response = JSONObject()
        receivedResponse?.keys()?.let { keys ->
            while (keys.hasNext()) {
                val key = keys.next()
                response.put(key, receivedResponse.opt(key))
            }
        }
        response.put("status", "failed").put("error", error)
        (current.responseId ?: receivedResponse?.let { JsonProtocol.nonBlank(it, "id") }
            ?: JsonProtocol.nonBlank(event, "response_id"))?.let { response.put("id", it) }
        return terminal(current, JSONObject().put("response", response),
            ResponseTerminalState.FAILED, maxStateAllocationBytes,
            errorMessage)
    }

    private fun terminal(current: ResponseEventState, event: JSONObject,
                         terminalState: ResponseTerminalState, maxStateAllocationBytes: Long,
                         completionError: String? = null): ResponseEventDecision {
        val response = event.optJSONObject("response")
        val responseId = response?.let { JsonProtocol.nonBlank(it, "id") }
        var canonicalCharacters = 0L
        response?.optJSONArray("output")?.let { output ->
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                if (JsonProtocol.string(item, "type") != "message") continue
                val content = item.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    canonicalCharacters += when (JsonProtocol.string(part, "type")) {
                        "output_text" -> JsonProtocol.string(part, "text").length.toLong()
                        "refusal" -> JsonProtocol.string(part, "refusal").length.toLong()
                        else -> 0L
                    }
                }
            }
        }
        ResponseAccumulatorMemoryBudget.check(current,
            textCharacters = current.text.length.toLong() + canonicalCharacters,
            terminalResponse = response, limitBytes = maxStateAllocationBytes)
        val canonical = LegacyOpenAiCheckpoint.terminalOutputText(response)
        val hasCanonical = canonical.isNotBlank()
        val state = current.copy(
            responseId = responseId ?: current.responseId,
            text = if (hasCanonical) canonical else current.text,
            // Terminal response.output is authoritative. The partial indexed
            // parts describe a *different* string; retain those only when
            // terminal supplied no canonical replacement.
            indexedTextParts = if (hasCanonical) emptyMap() else current.indexedTextParts,
            unindexedText = if (hasCanonical) canonical else current.unindexedText,
            legacyFlatCheckpoint = if (hasCanonical) false else current.legacyFlatCheckpoint,
            terminalState = terminalState,
            terminalResponse = response,
            completionError = completionError,
            usage = response?.optJSONObject("usage") ?: current.usage
        )
        return ResponseEventDecision(state,
            textChanged = state.text != current.text,
            responseIdChanged = state.responseId != current.responseId)
    }
}

class ResponsesResponseIdentityException : java.io.IOException("Responses response identity changed")
