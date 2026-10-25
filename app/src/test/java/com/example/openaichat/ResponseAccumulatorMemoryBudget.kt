package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

/**
 * Check the retained response before concatenating another small delta. A frame
 * budget alone cannot bound copies of the whole accumulated answer. The estimate
 * leaves room for indexed/flat strings, JSON escaping and owned persistence.
 * A refusal is an explicit processing failure; the received reader retains the
 * rest of the same paid HTTP body without interpreting it.
 */
internal object ResponseAccumulatorMemoryBudget {
    fun check(
        current: ResponseEventState,
        textCharacters: Long = current.text.length.toLong(),
        summaryCharacters: Long = current.reasoningSummary.length.toLong(),
        replacementIndex: Int? = null,
        replacementItem: JSONObject? = null,
        terminalResponse: JSONObject? = null,
        limitBytes: Long = Long.MAX_VALUE
    ) {
        val limit = minOf(limitBytes, Runtime.getRuntime().maxMemory() / 3L)
        var bytes = (textCharacters + summaryCharacters) * 32L +
            current.indexedTextParts.size.toLong() * 256L + 512L
        if (bytes > limit) fail()
        fun jsonBytes(value: Any?, depth: Int = 0): Long {
            if (depth > 128) fail()
            return when (value) {
                is String -> value.length.toLong() * 32L + 128L
                is JSONObject -> {
                    var total = 512L
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        total += key.length.toLong() * 32L + 128L + jsonBytes(value.opt(key), depth + 1)
                        if (total > limit) fail()
                    }
                    total
                }
                is JSONArray -> {
                    var total = 512L
                    for (i in 0 until value.length()) {
                        total += 128L + jsonBytes(value.opt(i), depth + 1)
                        if (total > limit) fail()
                    }
                    total
                }
                else -> 128L
            }
        }
        current.typedOutputItems.forEach { (index, item) ->
            if (index != replacementIndex) {
                bytes += 256L + jsonBytes(item)
                if (bytes > limit) fail()
            }
        }
        replacementItem?.let { bytes += 256L + jsonBytes(it) }
        // A terminal frame retains provider JSON as well as the assembled
        // transcript and already received signed/tool items. Charge that JSON
        // before the caller allocates the canonical StringBuilder/string.
        terminalResponse?.let { bytes += jsonBytes(it) }
        if (bytes > limit) fail()
    }

    private fun fail(): Nothing = throw ResponsesSseLimitException(
        "Накопленный ответ превысил безопасный лимит памяти для обработки.")
}
