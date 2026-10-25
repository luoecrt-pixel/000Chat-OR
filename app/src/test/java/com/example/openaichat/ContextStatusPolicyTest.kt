package com.example.openaichat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContextStatusPolicyTest {
    private fun build(history: List<JSONObject>? = null, accounting: JSONObject? = null,
        binding: String? = null, expected: String? = null): ContextStatusSnapshot {
        val actualHistory = history ?: listOf(JSONObject().put("role", "assistant")
            .put("provider", "openrouter").put("text", "Ответ")
            .put("openrouter_protocol", JSONObject().put("version", 1).put("binding_sha256", binding ?: JSONObject.NULL)
                .put("tail", org.json.JSONArray().put(JSONObject().put("reasoning", "preserve")))))
        val before = actualHistory.map { it.toString() }
        return ContextStatusPolicy.build("anthropic/claude-test", actualHistory, expected, OpenRouterClaudeRoute.ANTHROPIC,
            "1h", listOf("данные.csv"), listOf("file_search"), accounting, 1000L, 4000L).also {
            assertEquals("Inspection must not mutate protocol", before, actualHistory.map { m -> m.toString() })
        }
    }

    @Test fun missingSavedProtocolDoesNotClaimPreservedBinding() {
        val plain = JSONObject().put("role", "assistant").put("provider", "openrouter").put("text", "Visible")
        val snapshot = build(history = listOf(plain))
        assertEquals("кэш 1h", snapshot.mode)
        assertTrue(snapshot.explanation.contains("Сохранённой подписанной привязки нет"))
        assertEquals("Anthropic через OpenRouter", snapshot.route)
        assertEquals(7L, snapshot.textCharacters)
    }

    @Test fun absentNullInvalidAndObservedZeroCacheRemainDistinct() {
        assertNull(build().cacheReadTokens)
        assertNull(build(accounting = JSONObject().put("cached_tokens", JSONObject.NULL)).cacheReadTokens)
        assertNull(build(accounting = JSONObject().put("cached_tokens", -1)).cacheReadTokens)
        val known = build(accounting = JSONObject().put("cached_tokens", 0).put("cache_write_tokens", 42))
        assertEquals(0L, known.cacheReadTokens)
        assertEquals(42L, known.cacheWriteTokens)
        assertEquals(3000L, known.lastResponseAgeMillis)
    }

    @Test fun invalidatedUnsupportedAndMissingTailNeverClaimPreservedBinding() {
        for (case in listOf("invalidated", "version", "tail")) {
            val protocol = JSONObject().put("version", 1).put("binding_sha256", "bound")
                .put("tail", org.json.JSONArray().put(JSONObject().put("role", "assistant")))
            val message = JSONObject().put("role", "assistant").put("openrouter_protocol", protocol)
            when (case) {
                "invalidated" -> message.put("protocol_context_state", "invalidated")
                "version" -> protocol.put("version", 2)
                "tail" -> protocol.remove("tail")
            }
            val value = ContextStatusPolicy.build("anthropic/claude-test", listOf(message),
                "bound", OpenRouterClaudeRoute.AUTO, "1h", emptyList(), emptyList(), null, null, 10,
                openRouterPrefixMatches = false, openRouterPrefixReason = "Изменены инструкции")
            assertFalse(case, value.explanation.contains("привязка сохранена"))
            assertFalse(case, value.explanation.contains("инструментов сохранена"))
            assertTrue(value.explanation.contains("Изменены инструкции"))
        }
    }

    @Test fun routerBindingAndUnverifiedPrefixAreSeparateAndSecretsAreAbsent() {
        val matching = build(binding = "bound", expected = "bound")
        assertTrue(matching.explanation.contains("сохранена"))
        assertTrue(matching.explanation.contains("при подготовке"))
        assertTrue(build(binding = "old", expected = "new").explanation.contains("изменилась"))
        val details = matching.details()
        assertFalse(details.contains("synthetic-key"))
        assertFalse(details.contains("preserve"))
        assertTrue(details.contains("не гарантирует"))
    }
}
