package com.example.openaichat

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ModelCatalogTest {
    @Test
    fun everyCatalogModelAgreesWithProviderReasoningPolicies() {
        assertEquals(listOf("openrouter"), ModelCatalog.sources.map { it.id })
        for (model in ModelCatalog.source("openrouter").models) {
            assertTrue(ModelCatalog.isClaudeModel(model.id))
            assertEquals(model.label, ModelCatalog.displayName("openrouter", "~${model.id}"))
            assertFalse(OpenRouterReasoningPolicy.supportsResponseVerbosity(model.id))
            for (effort in listOf("low", "medium", "high", "xhigh", "max")) {
                assertEquals(effort, OpenRouterReasoningPolicy.requestEffort(model.id, effort))
            }
        }
    }

    @Test
    fun haikuEveryNativeEffortProducesValidHttpWithoutVerbosityOrHiddenTokenCaps() {
        val model = ModelCatalog.model("openrouter", "anthropic/claude-haiku-5.5")
            ?: throw AssertionError("Haiku is missing from the catalog")
        val server = MockWebServer()
        server.start()
        val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
        try {
            for (effort in listOf("low", "medium", "high", "xhigh", "max")) {
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"model\":\"${model.id}\",\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n"
                ))
                val result = client.complete(
                    apiKey = "mock-haiku-key",
                    model = model.id,
                    messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Привет")),
                    tools = null,
                    maxTokens = null,
                    reasoningEffort = effort,
                    verbosity = "medium",
                    parallelToolCalls = false,
                    executeTool = { _, _ -> error("No tools expected") }
                )
                assertEquals("done", result.text)
                assertEquals(model.id, result.responseModel)
                val request = server.takeRequest(2, TimeUnit.SECONDS)
                    ?: throw AssertionError("Missing Haiku HTTP request")
                assertEquals("POST", request.method)
                assertEquals("/api/v1/chat/completions", request.path)
                assertEquals("false", request.getHeader("X-OpenRouter-Cache"))
                assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/json"))
                assertEquals(request.body.size, request.getHeader("Content-Length")?.toLong())
                val body = JSONObject(request.body.readUtf8())
                assertEquals(model.id, body.getString("model"))
                assertEquals("Привет", body.getJSONArray("messages").getJSONObject(0).getString("content"))
                assertEquals(effort, body.getJSONObject("reasoning").getString("effort"))
                assertFalse(body.getJSONObject("reasoning").has("mode"))
                assertFalse(body.has("verbosity"))
                for (cap in listOf("max_tokens", "max_completion_tokens", "max_output_tokens")) {
                    assertFalse(body.has(cap))
                    assertFalse(body.getJSONObject("reasoning").has(cap))
                }
            }
        } finally {
            client.close()
            server.shutdown()
        }
    }

    @Test
    fun unsupportedStoredModelRequiresAnExplicitClaudeChoice() {
        assertEquals("anthropic/claude-custom", ModelCatalog.normalizedClaudeModel("~anthropic/claude-custom"))
        for (model in listOf("gpt-6-astra", "openai/gpt-test", "other/model")) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                ModelCatalog.normalizedClaudeModel(model)
            }
        }
    }

    @Test
    fun openRouterAnthropicContainsAllFourModelsIncludingHaiku() {
        assertEquals(
            listOf(
                "anthropic/claude-opus-5.5",
                "anthropic/claude-sonnet-5.5",
                "anthropic/claude-haiku-5.5",
                "anthropic/claude-fable-5.1"
            ),
            ModelCatalog.source("openrouter").models.map { it.id }
        )
        assertEquals("Claude Haiku 5.5", ModelCatalog.displayName("openrouter", "anthropic/claude-haiku-5.5"))
    }

    @Test
    fun unknownModelIsShownById() {
        assertEquals("custom/model", ModelCatalog.displayName("openrouter", "custom/model"))
        assertNull(ModelCatalog.model("openrouter", "custom/model"))
    }

    @Test
    fun matchIsTriState() {
        assertEquals(
            ModelCatalog.Match.EXACT,
            ModelCatalog.match("~gpt-6.1-sol", "gpt-6.1-sol")
        )
        assertEquals(
            ModelCatalog.Match.MISMATCH,
            ModelCatalog.match("gpt-6-astra", "gpt-6.1-sol")
        )
        assertEquals(
            ModelCatalog.Match.UNKNOWN,
            ModelCatalog.match("gpt-6-astra", null)
        )
    }
}
