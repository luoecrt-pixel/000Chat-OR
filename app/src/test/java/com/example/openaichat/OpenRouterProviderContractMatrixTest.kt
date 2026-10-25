package com.example.openaichat

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Actual Chat Completions wire across every selectable Claude model and effort. */
class OpenRouterProviderContractMatrixTest {
    private val efforts = listOf("low", "medium", "high", "xhigh", "max")
    private val cacheModes = listOf("off", "5m", "1h")
    private fun response(content: String = "ok"): MockResponse {
        val event = JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("delta", JSONObject().put("content", content)).put("finish_reason", "stop")))
        return MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: $event\n\ndata: [DONE]\n\n")
    }

    @Test fun allSelectableClaudeEffortsKeepExactRouteCacheAndToolConfigurationOnWire() {
        MockWebServer().use { server ->
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
            try {
                val models = ModelCatalog.source("openrouter").models
                for ((modelIndex, model) in models.withIndex()) for ((effortIndex, effort) in efforts.withIndex()) {
                    val index = modelIndex * efforts.size + effortIndex
                    val route = OpenRouterClaudeRoute.entries[index % OpenRouterClaudeRoute.entries.size]
                    val cache = cacheModes[index % cacheModes.size]
                    val github = index and 1 != 0
                    val webSearch = index and 2 != 0
                    val webFetch = index and 4 != 0
                    val tools = ContextRequestPolicy.openRouterTools(github, webSearch, webFetch)
                    server.enqueue(response())
                    val result = client.complete("synthetic-provider-matrix", model.id,
                        messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Привет")),
                        tools = tools, maxTokens = null, reasoningEffort = effort, verbosity = "high",
                        parallelToolCalls = index % 2 == 0, cacheMode = cache, claudeRoute = route,
                        executeTool = { _, _ -> fail("The fixture returned no tool call"); "" })
                    assertEquals("ok", result.text)
                    val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                    assertEquals("POST", request.method)
                    assertEquals("/api/v1/chat/completions", request.path)
                    val body = JSONObject(request.body.readUtf8())
                    assertEquals(model.id, body.getString("model"))
                    assertEquals(effort, body.getJSONObject("reasoning").getString("effort"))
                    assertEquals(index % 2 == 0, body.getBoolean("parallel_tool_calls"))
                    assertFalse("Claude verbosity must not override the selected effort", body.has("verbosity"))
                    for (key in listOf("max_tokens", "max_output_tokens", "max_completion_tokens"))
                        assertFalse("No implicit output cap: $key", body.has(key))
                    assertEquals(StreamingJsonRequestBody.sha256(tools),
                        StreamingJsonRequestBody.sha256(body.getJSONArray("tools")))
                    val provider = body.optJSONObject("provider")
                    if (route == OpenRouterClaudeRoute.ANTHROPIC) {
                        assertEquals("anthropic", provider!!.getJSONArray("order").getString(0))
                        assertFalse(provider.getBoolean("allow_fallbacks"))
                    } else assertNull(provider)
                    if (cache == "off") assertFalse(body.has("cache_control")) else {
                        val marker = body.getJSONObject("cache_control")
                        assertEquals("ephemeral", marker.getString("type"))
                        assertEquals(cache == "1h", marker.has("ttl"))
                        if (cache == "1h") assertEquals("1h", marker.getString("ttl"))
                    }
                    val compression = body.getJSONArray("plugins").getJSONObject(0)
                    assertEquals("context-compression", compression.getString("id"))
                    assertFalse(compression.getBoolean("enabled"))
                    assertFalse(body.has("debug"))
                }
                assertEquals(models.size * efforts.size, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun structuredOutputKeepsRequiredParametersAndPinnedRouteTogether() {
        MockWebServer().use { server ->
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
            try {
                val format = OpenRouterStructuredOutputPolicy.format("json_schema",
                    """{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}""")!!
                for (model in ModelCatalog.source("openrouter").models) for (route in OpenRouterClaudeRoute.entries) {
                    server.enqueue(response("{\"answer\":\"ok\"}"))
                    client.complete("synthetic-provider-matrix", model.id,
                        messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Return JSON")),
                        tools = ContextRequestPolicy.openRouterTools(false, false, false), maxTokens = null,
                        reasoningEffort = "low", verbosity = null, parallelToolCalls = false,
                        cacheMode = "off", claudeRoute = route, responseFormat = format,
                        executeTool = { _, _ -> fail("No tools requested"); "" })
                    val body = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
                    assertEquals(format.toString(), body.getJSONObject("response_format").toString())
                    val provider = body.getJSONObject("provider")
                    assertTrue(provider.getBoolean("require_parameters"))
                    assertEquals(route == OpenRouterClaudeRoute.ANTHROPIC, provider.has("order"))
                    if (route == OpenRouterClaudeRoute.ANTHROPIC) assertFalse(provider.getBoolean("allow_fallbacks"))
                }
            } finally { client.close() }
        }
    }
}
