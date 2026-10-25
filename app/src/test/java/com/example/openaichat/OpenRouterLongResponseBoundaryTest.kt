package com.example.openaichat

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class OpenRouterLongResponseBoundaryTest {
    private fun frame(json: String) = "data: $json\n\n"
    private fun stream(vararg json: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(json.joinToString("") { frame(it) })
    private fun reasoning(id: String = "long-reasoning") =
        """{"id":"$id","choices":[{"delta":{"reasoning_details":[{"index":0,"type":"reasoning.encrypted","data":"opaque-fragment","signature":"exact-signature"}]}}]}"""
    private fun toolRound(index: Int, tokens: Long) = stream(
        """{"id":"gen-$index","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-$index","type":"function","function":{"name":"file_info","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
        """{"choices":[],"usage":{"total_tokens":$tokens}}""",
        """{"choices":[],"usage":{"total_tokens":$tokens}}""",
        "[DONE]"
    )
    private val tools get() = JSONArray().put(JSONObject().put("type", "function")
        .put("function", JSONObject().put("name", "file_info").put("parameters", JSONObject())))
    private fun complete(client: OpenRouterChatClient, tools: JSONArray? = null,
        onRound: (Int, String?, JSONObject?) -> Unit = { _, _, _ -> },
        checkpoint: (JSONObject) -> Unit = {}, execute: (String, JSONObject) -> String = { _, _ -> error("No tools") }) =
        client.complete(apiKey = "synthetic-long-key", model = "anthropic/claude-sonnet-5.5",
            messages = JSONArray().put(JSONObject().put("role", "user").put("content", "long answer")),
            tools = tools, maxTokens = null, reasoningEffort = "max", verbosity = null,
            parallelToolCalls = false, executeTool = execute, onRoundObserved = onRound,
            onProtocolCheckpoint = checkpoint)

    @Test fun reasoningWithoutVisibleTextCanContinueUntilAlmostFiveHoursWithoutImplicitOutputCap() {
        MockWebServer().use { server ->
            server.enqueue(stream(reasoning(),
                """{"choices":[{"delta":{"content":"eventual answer"},"finish_reason":"stop"}]}""", "[DONE]"))
            val clock = AtomicLong(1L)
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false,
                monotonicNanos = clock::get)
            try {
                val result = complete(client, onRound = { _, id, _ ->
                    if (id != null) clock.set(1L + TimeUnit.MINUTES.toNanos(299))
                })
                assertEquals("eventual answer", result.text)
                assertEquals("stop", result.finishReason)
                assertEquals("exact-signature", result.assistantMessage.getJSONArray("reasoning_details")
                    .getJSONObject(0).getString("signature"))
                val body = JSONObject(server.takeRequest().body.readUtf8())
                for (key in listOf("max_tokens", "max_output_tokens", "max_completion_tokens")) assertFalse(body.has(key))
                val http = OpenRouterChatClient::class.java.getDeclaredField("httpClient").apply { isAccessible = true }
                    .get(client) as OkHttpClient
                assertEquals(0, http.readTimeoutMillis)
                assertEquals(0, http.callTimeoutMillis)
                assertEquals(1, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun fiveHourDeadlineStopsReasoningOnlyStreamAndPreservesUnreplayableFragments() {
        MockWebServer().use { server ->
            server.enqueue(stream(reasoning(),
                """{"choices":[{"delta":{"content":"too late"},"finish_reason":"stop"}]}""", "[DONE]"))
            val clock = AtomicLong(1L)
            val checkpoints = mutableListOf<JSONObject>()
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false,
                monotonicNanos = clock::get)
            try {
                val failure = assertThrows(OpenRouterCompletionException::class.java) {
                    complete(client, onRound = { _, id, _ ->
                        if (id != null) clock.set(1L + TimeUnit.HOURS.toNanos(5))
                    }, checkpoint = { checkpoints += JsonProtocol.objectCopy(it) })
                }
                assertTrue(failure.message.orEmpty().contains("5 часов"))
                assertEquals("", failure.partialText)
                val partial = checkpoints.last().getJSONObject("interrupted_assistant")
                assertFalse(partial.getBoolean("replay_safe"))
                assertEquals("exact-signature", partial.getJSONArray("reasoning_delta_fragments")
                    .getJSONObject(0).getString("signature"))
                assertEquals(0, checkpoints.last().getJSONArray("tail").length())
                assertEquals(1, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun absoluteFiveHourDeadlineIsSharedAcrossToolRoundsInsteadOfRestarted() {
        MockWebServer().use { server ->
            server.enqueue(toolRound(1, 10))
            server.enqueue(stream("""{"choices":[{"delta":{"content":"done"},"finish_reason":"stop"}]}""", "[DONE]"))
            val clock = AtomicLong(1L)
            val timeouts = mutableListOf<Long>()
            val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS).eventListener(object : EventListener() {
                    override fun callStart(call: Call) { timeouts += call.timeout().timeoutNanos() }
                }).build()
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), http,
                reconcileGenerations = false, monotonicNanos = clock::get)
            try {
                val result = complete(client, tools = tools, execute = { _, _ ->
                    clock.addAndGet(TimeUnit.HOURS.toNanos(4)); "known result"
                })
                assertEquals("done", result.text)
                assertEquals(listOf(TimeUnit.HOURS.toNanos(5), TimeUnit.HOURS.toNanos(1)), timeouts)
                assertEquals(2, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun fiveMillionReportedTokensAcrossRoundsStopsBeforeNextToolOrPaidPostWithoutCountingDuplicateUsageTwice() {
        MockWebServer().use { server ->
            server.enqueue(toolRound(1, 2_499_999))
            server.enqueue(toolRound(2, 2_499_999))
            server.enqueue(toolRound(3, 2))
            var executed = 0
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
            try {
                val failure = assertThrows(OpenRouterCompletionException::class.java) {
                    complete(client, tools = tools, execute = { _, _ -> executed++; "known result" })
                }
                assertTrue(failure.message.orEmpty().contains("5 млн"))
                assertEquals(5_000_000L, failure.accumulatedUsage!!.getLong("total_tokens"))
                assertEquals(2, executed)
                assertEquals(3, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun reportedEmergencyBudgetStopsWithinCurrentSseRoundBeforeLaterText() {
        MockWebServer().use { server ->
            server.enqueue(stream(reasoning(),
                """{"choices":[],"usage":{"total_tokens":5000000}}""",
                """{"choices":[{"delta":{"content":"must not be consumed"},"finish_reason":"stop"}]}""", "[DONE]"))
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
            try {
                val failure = assertThrows(OpenRouterCompletionException::class.java) { complete(client) }
                assertTrue(failure.message.orEmpty().contains("5 млн"))
                assertEquals("", failure.partialText)
                assertEquals(5_000_000L, failure.accumulatedUsage!!.getLong("total_tokens"))
                assertEquals(1, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun reportedInputOutputWithoutTotalUsesOverflowSafeEmergencyBudget() {
        MockWebServer().use { server ->
            server.enqueue(stream(reasoning(),
                """{"choices":[],"usage":{"prompt_tokens":9223372036854775807,"completion_tokens":9223372036854775807}}""",
                """{"choices":[{"delta":{"content":"must not be consumed"},"finish_reason":"stop"}]}""", "[DONE]"))
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
            try {
                val failure = assertThrows(OpenRouterCompletionException::class.java) { complete(client) }
                assertTrue(failure.message.orEmpty().contains("5 млн"))
                assertEquals("", failure.partialText)
                assertEquals(Long.MAX_VALUE, failure.accumulatedUsage!!.getLong("prompt_tokens"))
                assertEquals(1, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun sparseEmptyUsageFrameDoesNotCrashOrInventCost() {
        MockWebServer().use { server ->
            server.enqueue(stream("""{"choices":[],"usage":{}}""",
                """{"choices":[{"delta":{"content":"done"},"finish_reason":"stop"}]}""", "[DONE]"))
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
            try {
                val result = complete(client)
                assertEquals("done", result.text)
                assertNull(result.usage)
                assertEquals(1, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun budgetReachedInSameEventAsPaidOutputPreservesThatEventBeforeStopping() {
        MockWebServer().use { server ->
            val event = JSONObject(reasoning()).put("usage", JSONObject().put("total_tokens", 5_000_000))
            event.getJSONArray("choices").getJSONObject(0).getJSONObject("delta").put("content", "paid text 字🙂")
            server.enqueue(stream(event.toString(),
                """{"choices":[{"delta":{"content":"must not be consumed"},"finish_reason":"stop"}]}""", "[DONE]"))
            val checkpoints = mutableListOf<JSONObject>()
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
            try {
                val failure = assertThrows(OpenRouterCompletionException::class.java) {
                    complete(client, checkpoint = { checkpoints += JsonProtocol.objectCopy(it) })
                }
                assertTrue(failure.message.orEmpty().contains("5 млн"))
                assertEquals("paid text 字🙂", failure.partialText)
                val interrupted = checkpoints.last().getJSONObject("interrupted_assistant")
                assertEquals("paid text 字🙂", interrupted.getString("content"))
                assertEquals("exact-signature", interrupted.getJSONArray("reasoning_details").getJSONObject(0).getString("signature"))
                assertFalse(interrupted.getBoolean("replay_safe"))
                assertEquals(1, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun lengthAfterReasoningOnlyRetainsRawDetailsOutsideReplayAndReportsIncomplete() {
        MockWebServer().use { server ->
            server.enqueue(stream(reasoning(), """{"choices":[{"delta":{},"finish_reason":"length"}]}""", "[DONE]"))
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
            try {
                val result = complete(client)
                assertEquals("", result.text)
                assertEquals("length", result.finishReason)
                assertFalse(OpenRouterFinishPolicy.completed(result.finishReason))
                val interrupted = result.protocol!!.getJSONObject("interrupted_assistant")
                assertFalse(interrupted.getBoolean("replay_safe"))
                assertEquals("exact-signature", interrupted.getJSONArray("reasoning_details").getJSONObject(0).getString("signature"))
                assertEquals("opaque-fragment", interrupted.getJSONArray("reasoning_delta_fragments").getJSONObject(0).getString("data"))
                assertFalse(result.protocol.getJSONArray("tail").getJSONObject(0).has("reasoning_details"))
                assertEquals(1, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun abruptSseDisconnectPreservesReasoningOnlyEvidenceAndNeverRetriesPost() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(frame(reasoning()) + ":" + "padding".repeat(4096) + "\n\n")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val checkpoints = mutableListOf<JSONObject>()
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
            try {
                assertThrows(java.io.IOException::class.java) {
                    complete(client, checkpoint = { checkpoints += JsonProtocol.objectCopy(it) })
                }
                val partial = checkpoints.last().getJSONObject("interrupted_assistant")
                assertFalse(partial.getBoolean("replay_safe"))
                assertEquals("opaque-fragment", partial.getJSONArray("reasoning_delta_fragments")
                    .getJSONObject(0).getString("data"))
                assertEquals(0, checkpoints.last().getJSONArray("tail").length())
                assertEquals(1, server.requestCount)
            } finally { client.close() }
        }
    }
}
