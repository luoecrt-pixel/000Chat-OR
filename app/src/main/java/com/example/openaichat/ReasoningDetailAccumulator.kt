package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Merge ordered deltas by either explicit identity; never rewrite opaque signed fields. */
class ReasoningDetailAccumulator {
    private val blocks = mutableListOf<JSONObject>()
    private data class Identity(val kind: String, val value: String, val type: String)
    private val indexes = mutableMapOf<Identity, Int>()
    private val fragments = JSONArray()

    private fun alias(kind: String, value: String, type: String): Identity {
        val untyped = Identity(kind, value, "")
        if (type.isNotBlank()) {
            val typed = Identity(kind, value, type)
            if (!indexes.containsKey(typed) && indexes.containsKey(untyped)) {
                indexes[typed] = indexes.remove(untyped)!!
            }
            return typed
        }
        val candidates = indexes.keys.filter { it.kind == kind && it.value == value }
        if (candidates.size > 1) throw IOException("Ambiguous reasoning block identity: missing type")
        return candidates.singleOrNull() ?: untyped
    }

    fun accept(details: JSONArray) {
        for (i in 0 until details.length()) {
            // Preserve the received fragment even if its identity proves inconsistent below.
            val raw = JsonProtocol.copy(details.get(i))
            fragments.put(raw)
            val item = raw as? JSONObject ?: throw IOException("Invalid reasoning detail fragment")
            val type = JsonProtocol.nonBlank(item, "type").orEmpty()
            val aliases = buildList {
                if (item.has("index") && !item.isNull("index")) add(alias("index", item.get("index").toString(), type))
                JsonProtocol.nonBlank(item, "id")?.let { add(alias("id", it, type)) }
            }
            val owners = aliases.mapNotNull { indexes[it] }.distinct()
            if (owners.size > 1) throw IOException("Conflicting reasoning block identities")
            val existing = owners.singleOrNull()
            if (existing == null) {
                aliases.forEach { indexes[it] = blocks.size }
                blocks.add(JsonProtocol.objectCopy(item))
            } else {
                val block = blocks[existing]
                // Both index and id are aliases of the same detail. A later frame can omit
                // either one, but it cannot change an already captured identity or format.
                item.keys().forEach { field ->
                    val value = item.opt(field)
                    if (value != null && value != JSONObject.NULL) {
                        val previous = block.opt(field)
                        if (field !in setOf("text", "summary", "data", "signature") &&
                            previous != null && previous != JSONObject.NULL && previous.toString() != value.toString()) {
                            throw IOException("Conflicting reasoning detail metadata: $field")
                        }
                        if (field in setOf("text", "summary", "data", "signature") && value !is String)
                            throw IOException("Invalid reasoning detail payload: $field")
                    }
                }
                aliases.forEach { indexes[it] = existing }
                item.keys().forEach { field ->
                    val value = item.opt(field)
                    if (value != null && value != JSONObject.NULL) {
                        // OpenRouter documents ordered delta concatenation, including opaque
                        // signature/data fragments. The first one does not close the block.
                        if (field in setOf("text", "summary", "data", "signature") && value is String)
                            block.put(field, JsonProtocol.text(block, field).orEmpty() + value)
                        else block.put(field, JsonProtocol.copy(value))
                    }
                }
            }
        }
    }

    fun array() = JSONArray().apply { blocks.forEach { put(JsonProtocol.objectCopy(it)) } }
    fun receivedFragments(): JSONArray = JsonProtocol.arrayCopy(fragments)
}
