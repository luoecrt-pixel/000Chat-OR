package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Reject invalid declarations before an inference POST; never rewrite their meaning. */
object OpenRouterToolSchemaPreflight {
    fun validate(tools: JSONArray?) {
        if (tools == null) return
        val names = mutableSetOf<String>()
        for (index in 0 until tools.length()) {
            val tool = tools.optJSONObject(index) ?: invalid("tools[$index]", "declaration must be an object")
            val type = JsonProtocol.nonBlank(tool, "type") ?: invalid("tools[$index]", "type is missing")
            // Server tools have provider-defined declarations, not a function input schema.
            if (type.startsWith("openrouter:")) {
                if (!names.add(type)) invalid(type, "duplicate tool declaration")
                continue
            }
            if (type != "function") invalid("tools[$index]", "unsupported tool type")
            val function = tool.optJSONObject("function") ?: invalid("tools[$index]", "function is missing")
            val name = JsonProtocol.nonBlank(function, "name") ?: invalid("tools[$index]", "function name is missing")
            if (!names.add(name)) invalid(name, "duplicate tool name")
            if (!function.has("parameters")) continue // A no-argument function may omit parameters.
            val schema = function.optJSONObject("parameters") ?: invalid(name, "parameters must be an object schema")
            for (keyword in listOf("oneOf", "anyOf", "allOf")) {
                if (schema.has(keyword)) invalid(name, "top-level $keyword is unsupported by Claude")
            }
            JsonProtocol.nonBlank(schema, "type")?.let { if (it != "object") invalid(name, "input type must be object") }
            if (schema.has("properties") && schema.optJSONObject("properties") == null)
                invalid(name, "properties must be an object")
            if (schema.has("required")) {
                val required = schema.optJSONArray("required") ?: invalid(name, "required must be an array")
                val seen = mutableSetOf<String>()
                for (fieldIndex in 0 until required.length()) {
                    val field = required.opt(fieldIndex) as? String ?: invalid(name, "required entries must be strings")
                    if (!seen.add(field)) invalid(name, "required contains duplicate entries")
                    if (schema.opt("additionalProperties") == false && schema.optJSONObject("properties")?.has(field) != true)
                        invalid(name, "required references an undeclared property")
                }
            }
        }
    }

    private fun invalid(name: String, reason: String): Nothing =
        throw IOException("Некорректная схема инструмента $name: $reason. Запрос не отправлен.")
}
