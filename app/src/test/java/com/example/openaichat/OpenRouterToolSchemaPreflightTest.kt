package com.example.openaichat

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenRouterToolSchemaPreflightTest {
    private val model = "anthropic/claude-haiku-5.5"

    private fun catalog(github: Boolean, search: Boolean, fetch: Boolean) = OpenRouterAppToolCatalog.tools().apply {
        if (github) GitHubToolCatalog.tools().let { extra -> for (i in 0 until extra.length()) put(extra.get(i)) }
        OpenRouterServerToolCatalog.tools(search, fetch).let { extra -> for (i in 0 until extra.length()) put(extra.get(i)) }
    }

    /** A synthetic upstream boundary matching the observed provider rejection, not a live provider. */
    private fun providerResponse(request: RecordedRequest): MockResponse {
        val tools = JSONObject(request.body.clone().readUtf8()).getJSONArray("tools")
        for (index in 0 until tools.length()) {
            val tool = tools.getJSONObject(index)
            if (tool.getString("type") != "function") continue
            val parameters = tool.getJSONObject("function").getJSONObject("parameters")
            if (listOf("oneOf", "allOf", "anyOf").any(parameters::has)) {
                return MockResponse().setResponseCode(400).setBody(JSONObject().put("error", JSONObject()
                    .put("code", 400).put("message", "tools.$index.custom.input_schema: input_schema does not support oneOf, allOf, or anyOf at the top level")).toString())
            }
        }
        return MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
    }

    @Test fun providerContractRejectsLegacyPatchUnionEvenForTheFirstHello() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = providerResponse(request)
            }
            val legacy = OpenRouterAppToolCatalog.tools()
            legacy.getJSONObject(1).getJSONObject("function").getJSONObject("parameters")
                .put("anyOf", JSONArray().put(JSONObject().put("required", JSONArray().put("file_id")))
                    .put(JSONObject().put("required", JSONArray().put("filename"))))
            val body = JSONObject().put("model", model).put("tools", legacy)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Привет")))
            val http = OkHttpClient()
            try {
                val request = Request.Builder().url(server.url("/api/v1/chat/completions"))
                    .post(StreamingJsonRequestBody(body, oneShot = true)).build()
                http.newCall(request).execute().use { response ->
                    assertEquals(400, response.code)
                    assertTrue(response.body!!.string().contains("tools.1.custom.input_schema"))
                }
                assertEquals(1, server.requestCount)
            } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
        }
    }

    @Test fun everyRuntimeToolCombinationReachesHttpWithClaudeCompatibleSchemas() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = providerResponse(request)
            }
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
            try {
                for (github in listOf(false, true)) for (search in listOf(false, true)) for (fetch in listOf(false, true)) {
                    val tools = catalog(github, search, fetch)
                    val result = complete(client, tools)
                    assertEquals("Hello", result.text)
                    val wire = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
                    assertEquals(model, wire.getString("model"))
                    val sent = wire.getJSONArray("tools")
                    assertEquals(tools.length(), sent.length())
                    val names = mutableSetOf<String>()
                    val serverTypes = mutableSetOf<String>()
                    for (index in 0 until sent.length()) {
                        val tool = sent.getJSONObject(index)
                        if (tool.getString("type") != "function") { serverTypes.add(tool.getString("type")); continue }
                        val function = tool.getJSONObject("function")
                        assertTrue("Every function has a unique name", names.add(function.getString("name")))
                        val schema = function.getJSONObject("parameters")
                        assertEquals("object", schema.getString("type"))
                        val properties = schema.getJSONObject("properties")
                        assertFalse(schema.getBoolean("additionalProperties"))
                        for (keyword in listOf("oneOf", "allOf", "anyOf")) assertFalse(schema.has(keyword))
                        schema.optJSONArray("required")?.let { required ->
                            for (i in 0 until required.length()) assertTrue(properties.has(required.getString(i)))
                        }
                    }
                    assertTrue(names.containsAll(setOf("create_local_text_file", "patch_local_file", "file_list",
                        "file_info", "file_read_range", "file_search", "file_table_stats")))
                    assertEquals(github, "get_file" in names)
                    assertEquals(github, "merge_pull_request" in names)
                    assertEquals(search, "openrouter:web_search" in serverTypes)
                    assertEquals(fetch, "openrouter:web_fetch" in serverTypes)
                }
                assertEquals(8, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun unsupportedRootUnionsAndDuplicateNamesFailBeforeAnyHttpRequest() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
            }
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
            try {
                for (keyword in listOf("oneOf", "allOf", "anyOf")) {
                    val tools = OpenRouterAppToolCatalog.tools()
                    tools.getJSONObject(1).getJSONObject("function").getJSONObject("parameters").put(keyword, JSONArray())
                    val error = assertThrows(IOException::class.java) { complete(client, tools) }
                    assertTrue(error.message.orEmpty().contains("patch_local_file"))
                    assertTrue(error.message.orEmpty().contains(keyword))
                }
                val duplicate = OpenRouterAppToolCatalog.tools().apply { put(getJSONObject(0)) }
                val error = assertThrows(IOException::class.java) { complete(client, duplicate) }
                assertTrue(error.message.orEmpty().contains("create_local_text_file"))
                assertEquals("Preflight must precede any potentially paid HTTP", 0, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun nestedUnionsArePreservedAndMalformedClosedSchemasAreRejected() {
        val tools = OpenRouterAppToolCatalog.tools()
        val parameters = tools.getJSONObject(0).getJSONObject("function").getJSONObject("parameters")
        val nested = JSONObject().put("oneOf", JSONArray().put(JSONObject().put("type", "string"))
            .put(JSONObject().put("type", "integer")))
        parameters.getJSONObject("properties").put("extra", nested)
        val exact = tools.toString()
        OpenRouterToolSchemaPreflight.validate(tools)
        assertEquals("Validation must not silently rewrite schema semantics", exact, tools.toString())
        parameters.getJSONArray("required").put("undeclared")
        val failure = assertThrows(IOException::class.java) { OpenRouterToolSchemaPreflight.validate(tools) }
        assertTrue(failure.message.orEmpty().contains("undeclared property"))
        assertThrows(IOException::class.java) { OpenRouterToolSchemaPreflight.validate(JSONArray().put("not a declaration")) }
    }

    private fun complete(client: OpenRouterChatClient, tools: JSONArray) = client.complete(
        apiKey = "synthetic-schema-key", model = model,
        messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Привет")),
        tools = tools, maxTokens = null, reasoningEffort = "low", verbosity = null,
        parallelToolCalls = false, cacheMode = "off", claudeRoute = OpenRouterClaudeRoute.ANTHROPIC,
        executeTool = { _, _ -> error("The greeting must not execute any local tool") })
}
