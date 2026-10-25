package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

object OpenRouterAppToolCatalog {
    fun tools(): JSONArray = JSONArray().put(
        JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "create_local_text_file")
                .put("description", "Create a real UTF-8 text/code file inside the app. The user can save or share it from Local Files.")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("filename", JSONObject().put("type", "string"))
                        .put("content", JSONObject().put("type", "string")))
                    .put("required", JSONArray().put("filename").put("content"))
                    .put("additionalProperties", false)))
    ).apply {
        put(JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "patch_local_file")
            .put("description", "Atomically create a new UTF-8 file version. Supply file_id or an unambiguous filename; at least one is required and the app rejects a missing or ambiguous selector. Each nonempty search must occur exactly once in the ORIGINAL file; patches cannot overlap. Old versions remain available. Prefer exact file_id when names are ambiguous.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("file_id", JSONObject().put("type", "string").put("description", "Captured file_id or unambiguous existing filename; never a device path"))
                .put("filename", JSONObject().put("type", "string"))
                .put("expected_sha256", JSONObject().put("type", "string"))
                .put("patches", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 64)
                    .put("items", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("search", JSONObject().put("type", "string").put("minLength", 1))
                        .put("replace", JSONObject().put("type", "string")))
                        .put("required", JSONArray().put("search").put("replace")).put("additionalProperties", false))))
                // Claude rejects root-level anyOf/oneOf/allOf even for an unused
                // tool. The executor enforces the selector alternative locally.
                .put("required", JSONArray().put("patches")).put("additionalProperties", false))))
        val id = JSONObject().put("type", "string").put("description", "The file_id from a supplied file descriptor, never a device path")
        fun add(name: String, description: String, extra: JSONObject, vararg required: String) {
            val properties = JSONObject().put("file_id", id)
            extra.keys().forEach { properties.put(it, extra.get(it)) }
            put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", name).put("description", description)
                .put("parameters", JSONObject().put("type", "object").put("properties", properties)
                    .put("required", JSONArray().put("file_id").apply { required.forEach { put(it) } }).put("additionalProperties", false))))
        }
        fun integer(description: String) = JSONObject().put("type", "integer").put("description", description)
        put(JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "file_list").put("description", "List the files available in this chat, including files created in earlier answers. Continue with next_offset until eof.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("offset", integer("File index, default 0")).put("limit", integer("Files per call, 1..50, default 30")))
                .put("additionalProperties", false))))
        add("file_info", "Inspect a file and its available representations.", JSONObject())
        add("file_read_range", "Read any part of a complete file. Continue with next_offset until eof if the entire file is needed; no total-reading limit.",
            JSONObject().put("offset", integer("UTF-16 character offset, default 0"))
                .put("limit", integer("Characters per call, 2..40000, default 24000")))
        add("file_search", "Find literal text in a full file without loading it into context. Use next_offset to continue an incomplete search.",
            JSONObject().put("query", JSONObject().put("type", "string"))
                .put("offset", integer("Search from this character offset, default 0"))
                .put("limit", integer("Matches per call, 1..50, default 20")), "query")
        add("file_table_stats", "Scan ALL requested rows in CSV/TSV/XLSX with exact decimal sum/min/max and an exact mean fraction. Inspect headers first. Formula caches are not recalculated. This is not a 1000-row preview.",
            JSONObject().put("column", integer("1-based column number"))
                .put("start_row", integer("1-based first row, default 1; set 2 to skip a header"))
                .put("end_row", integer("Last row; omit for all remaining rows"))
                .put("sheet", JSONObject().put("type", "string").put("description", "XLSX sheet; default first sheet"))
                .put("delimiter", JSONObject().put("type", "string").put("description", "One CSV delimiter character; default comma (tab for TSV)"))
                .put("strict", JSONObject().put("type", "boolean").put("description", "Default true: nonnumeric cells and formulas without a cache prevent an incomplete total")), "column")
    }
}
