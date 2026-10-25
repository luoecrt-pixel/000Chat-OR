package com.example.openaichat

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Real inference POST/side-effect/checkpoint ordering; no live provider is used. */
class OpenRouterToolOutcomeBoundaryTest {
    private fun input() = JSONArray().put(JSONObject().put("role", "user").put("content", "Inspect"))
    private fun stream(event: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: $event\n\ndata: [DONE]\n\n")
    private fun toolRound() = stream("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"provider-call","type":"function","function":{"name":"file_info","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""")
    private fun pendingTail() = JSONArray().put(JSONObject().put("role", "assistant")
        .put("reasoning_details", JSONArray().put(JSONObject().put("type", "reasoning.encrypted").put("data", "opaque")))
        .put("tool_calls", JSONArray().put(JSONObject().put("id", "provider-call").put("type", "function")
            .put("function", JSONObject().put("name", "file_info").put("arguments", "{}")))))

    @Test fun failedInFlightAcknowledgementPreventsExecutionAndSecondPost() {
        val server = MockWebServer(); server.start()
        val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
        var executions = 0
        val checkpoints = mutableListOf<JSONObject>()
        try {
            server.enqueue(toolRound())
            assertThrows(IOException::class.java) {
                client.complete("mock", "anthropic/claude-sonnet-5.5", input(), tools = OpenRouterAppToolCatalog.tools(),
                    maxTokens = null, reasoningEffort = "high", verbosity = null, parallelToolCalls = false,
                    executeTool = { _, _ -> executions++; "{}" },
                    onProtocolCheckpoint = {
                        checkpoints += JSONObject(it.toString())
                        if (it.getJSONObject("tool_states").getJSONObject("provider-call").getString("state") == "in_flight")
                            throw IOException("synthetic SQLite failure")
                    })
            }
            assertEquals(0, executions); assertEquals(1, server.requestCount)
            assertEquals(2, checkpoints.size)
            assertEquals(1, checkpoints.last().getJSONArray("tail").length())
            assertFalse(checkpoints.last().toString().contains("cancelled"))
        } finally { client.close(); server.shutdown() }
    }

    @Test fun sideEffectThenFailureRetainsFileAndBlocksReplayWithoutFakeCancellation() {
        val server = MockWebServer(); server.start()
        val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
        val file = java.io.File.createTempFile("outcome-boundary", ".txt").apply { delete() }
        var checkpoint = JSONObject()
        var executions = 0
        try {
            server.enqueue(toolRound())
            assertThrows(IOException::class.java) {
                client.complete("mock", "anthropic/claude-sonnet-5.5", input(), tools = OpenRouterAppToolCatalog.tools(),
                    maxTokens = null, reasoningEffort = "high", verbosity = null, parallelToolCalls = false,
                    executeTool = { _, _ -> throw AssertionError("The provider id callback must be used") },
                    executeToolWithId = { id, name, _ ->
                        assertEquals("provider-call", id); assertEquals("file_info", name)
                        executions++; file.writeText("already published")
                        throw IOException("synthetic failure after publication")
                    }, onProtocolCheckpoint = { checkpoint = JSONObject(it.toString()) })
            }
            assertEquals("already published", file.readText())
            assertEquals(1, executions); assertEquals(1, server.requestCount)
            assertEquals("outcome_unknown", checkpoint.getJSONObject("tool_states")
                .getJSONObject("provider-call").getString("state"))
            val message = JSONObject().put("role", "assistant").put("text", "")
            message.put("openrouter_protocol", OpenRouterProtocolCodec.withVisibleTail(checkpoint, message))
            assertFalse(message.toString().contains("cancelled"))
            assertThrows(OpenRouterToolOutcomeUnknownException::class.java) {
                OpenRouterProtocolCodec.append(input(), JSONObject(message.toString()))
            }
            assertEquals(1, server.requestCount); assertEquals(1, executions)
        } finally { file.delete(); client.close(); server.shutdown() }
    }

    @Test fun legacyUnknownTailIsPreservedButCannotReachFirstPaidPost() {
        val server = MockWebServer(); server.start()
        val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
        try {
            val tail = pendingTail()
            val checkpoint = JSONObject().put("version", 1).put("tail", tail)
            val message = JSONObject().put("text", "Partial")
            val saved = OpenRouterProtocolCodec.withVisibleTail(checkpoint, message)
            assertEquals("opaque", saved.getJSONArray("tail").getJSONObject(0)
                .getJSONArray("reasoning_details").getJSONObject(0).getString("data"))
            assertFalse(saved.toString().contains("cancelled"))
            val history = input().put(tail.getJSONObject(0))
            assertThrows(OpenRouterToolOutcomeUnknownException::class.java) {
                client.complete("mock", "anthropic/claude-sonnet-5.5", history, tools = null,
                    maxTokens = null, reasoningEffort = "high", verbosity = null, parallelToolCalls = false,
                    executeTool = { _, _ -> throw AssertionError("Unknown tool must not execute") })
            }
            assertEquals(0, server.requestCount)
        } finally { client.close(); server.shutdown() }
    }

    @Test fun notStartedEvidenceMustMatchToolIdentityBeforeCancellation() {
        val evidence = JSONObject().put("provider-call", JSONObject().put("state", "not_started")
            .put("tool_name", "file_info").put("arguments_sha256", StreamingJsonRequestBody.sha256(JSONObject())))
        val tail = pendingTail()
        OpenRouterProtocolCodec.completePendingToolCalls(tail, evidence)
        assertEquals("cancelled", JSONObject(tail.getJSONObject(1).getString("content")).getString("status"))
        for (field in listOf("tool_name", "arguments_sha256")) {
            val mismatched = JSONObject(evidence.toString())
            mismatched.getJSONObject("provider-call").put(field, "different")
            val original = pendingTail()
            assertThrows(OpenRouterToolOutcomeUnknownException::class.java) {
                OpenRouterProtocolCodec.completePendingToolCalls(original, mismatched)
            }
            assertEquals(1, original.length())
        }
    }

    @Test fun malformedTailSurvivesPersistenceAndIsRejectedForReplay() {
        val raw = pendingTail().put("unrecognized historical value")
        val message = JSONObject().put("text", "")
        val checkpoint = JSONObject().put("tail", raw)
        val retained = OpenRouterProtocolCodec.withVisibleTail(checkpoint, message)
        assertEquals("unrecognized historical value", retained.getJSONArray("tail").get(1))
        assertThrows(OpenRouterToolOutcomeUnknownException::class.java) {
            OpenRouterProtocolCodec.completePendingToolCalls(retained.getJSONArray("tail"))
        }
        assertEquals("unrecognized historical value", retained.getJSONArray("tail").get(1))
    }

    @Test fun duplicateForeignOrBlankToolResultIsPreservedAndBlocksReplay() {
        val proper = JSONObject().put("role", "tool").put("tool_call_id", "provider-call").put("content", "first")
        for (id in listOf("provider-call", "foreign-call", "")) {
            val raw = pendingTail().put(JSONObject(proper.toString())).put(JSONObject(proper.toString()).put("tool_call_id", id))
            val original = raw.toString()
            OpenRouterProtocolCodec.completePendingToolCalls(raw, requireSafeReplay = false)
            assertEquals(original, raw.toString())
            assertThrows(OpenRouterToolOutcomeUnknownException::class.java) {
                OpenRouterProtocolCodec.completePendingToolCalls(raw)
            }
            assertEquals(original, raw.toString())
        }
    }

    @Test fun successfulActualIdCallbackStoresCompletedOutputBeforeContinuation() {
        val server = MockWebServer(); server.start()
        val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
        val checkpoints = mutableListOf<JSONObject>()
        try {
            server.enqueue(toolRound())
            server.enqueue(stream("""{"choices":[{"delta":{"content":"Done"},"finish_reason":"stop"}]}"""))
            val result = client.complete("mock", "anthropic/claude-sonnet-5.5", input(), tools = OpenRouterAppToolCatalog.tools(),
                maxTokens = null, reasoningEffort = "high", verbosity = null, parallelToolCalls = false,
                executeTool = { _, _ -> throw AssertionError("Legacy callback must not run") },
                executeToolWithId = { id, _, _ -> assertEquals("provider-call", id); "exact-output" },
                onProtocolCheckpoint = { checkpoints += JSONObject(it.toString()) })
            assertEquals(listOf("not_started", "in_flight", "completed"), checkpoints.map {
                it.getJSONObject("tool_states").getJSONObject("provider-call").getString("state") })
            assertEquals("completed", result.protocol!!.getJSONObject("tool_states").getJSONObject("provider-call").getString("state"))
            assertEquals("exact-output", checkpoints.last().getJSONArray("tail").getJSONObject(1).getString("content"))
            server.takeRequest()
            val continuation = server.takeRequest().body.readUtf8()
            assertTrue(continuation.contains("exact-output")); assertFalse(continuation.contains("tool_states"))
            assertEquals(2, server.requestCount)
        } finally { client.close(); server.shutdown() }
    }
}
