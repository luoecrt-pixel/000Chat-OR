package com.example.openaichat

import org.json.JSONObject

/** Local compatibility model for persisted Responses checkpoints; no stream execution. */

enum class ResponseTerminalState(val wireStatus: String) {
    COMPLETED("completed"), FAILED("failed"), INCOMPLETE("incomplete"), CANCELLED("cancelled")
}

data class ResponseContentIndex(val outputIndex: Int, val contentIndex: Int) : Comparable<ResponseContentIndex> {
    override fun compareTo(other: ResponseContentIndex): Int =
        compareValuesBy(this, other, ResponseContentIndex::outputIndex, ResponseContentIndex::contentIndex)
}

data class ResponseEventState(
    val responseId: String? = null,
    val text: String = "",
    val reasoningSummary: String = "",
    val terminalState: ResponseTerminalState? = null,
    val terminalResponse: JSONObject? = null,
    val completionError: String? = null,
    val usage: JSONObject? = null,
    // Responses output_text/refusal deltas can interleave across output items.
    // Do not append in arrival order: sort by provider output/content indices.
    val indexedTextParts: Map<ResponseContentIndex, String> = emptyMap(),
    val unindexedText: String = "",
    /** v23 had only flattened text; indexed replay must fail closed. */
    val legacyFlatCheckpoint: Boolean = false,
    /** Fully received, provider-authored output items only.
     * Do not fabricate encrypted reasoning from a summary or a provisional
     * output_item.added event. Includes signed reasoning, completed tools and
     * structured messages (refusals/annotations can outlive missing terminal output).
     */
    val typedOutputItems: Map<Int, JSONObject> = emptyMap()
)
