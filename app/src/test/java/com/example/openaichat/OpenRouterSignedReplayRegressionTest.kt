package com.example.openaichat

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic wire integrity only: no test signature is a provider-valid Claude signature. */
class OpenRouterSignedReplayRegressionTest {
    private val model = "anthropic/claude-opus-5.5"

    private fun stream(vararg deltas: JSONObject): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(deltas.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    private fun delta(details: JSONArray? = null, callId: String? = null, name: String? = null,
        finish: String? = null, content: String? = null): JSONObject = JSONObject().put("choices", JSONArray()
        .put(JSONObject().apply {
            finish?.let { put("finish_reason", it) }
            put("delta", JSONObject().apply {
                details?.let { put("reasoning_details", it) }
                content?.let { put("content", it) }
                if (callId != null) put("tool_calls", JSONArray().put(JSONObject()
                    .put("index", 0).put("id", callId).put("type", "function")
                    .put("function", JSONObject().put("name", name).put("arguments", "{}"))))
            })
        }))

    private fun complete(client: OpenRouterChatClient, messages: JSONArray,
        execute: (String, JSONObject) -> String) = client.complete("synthetic-key", model, messages,
        tools = OpenRouterAppToolCatalog.tools(), maxTokens = null, reasoningEffort = "xhigh",
        verbosity = null, parallelToolCalls = false, executeTool = execute)

    @Test fun fragmentedIdentityAliasesSurviveThreeToolsNextPostAndSavedReplay() {
        val server = MockWebServer()
        val requests = AtomicInteger()
        val seen = mutableListOf<JSONObject>()
        val expected = JSONArray().apply {
            repeat(6) { index -> put(JSONObject().put("index", index).put("id", "opaque-$index")
                .put("type", "reasoning.encrypted").put("format", "anthropic-claude-v1")
                .put("data", "redacted-$index-" + "ab".repeat(1024))) }
            put(JSONObject().put("index", 6).put("id", "thinking-6").put("type", "reasoning.text")
                .put("format", "anthropic-claude-v1").put("text", "Plan α β")
                .put("signature", "sig-part-1-part-2-part-3"))
        }
        val firstFragments = JSONArray(expected.toString()).apply {
            getJSONObject(6).put("text", "Plan α ").put("signature", "sig-part-1")
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val wire = JSONObject(request.body.readUtf8())
                synchronized(seen) { seen += wire }
                return when (requests.incrementAndGet()) {
                    1 -> stream(delta(callId = "info", name = "file_info", finish = "tool_calls"))
                    2 -> stream(delta(callId = "read", name = "file_read_range", finish = "tool_calls"))
                    3 -> stream(
                        delta(firstFragments),
                        // The id is unique and index is optional in the reasoning-details schema.
                        delta(JSONArray().put(JSONObject().put("id", "thinking-6").put("type", "reasoning.text")
                            .put("text", "β").put("signature", "-part-2"))),
                        delta(JSONArray().put(JSONObject().put("index", 6).put("id", "thinking-6")
                            .put("signature", "-part-3")), "create", "create_local_text_file", "tool_calls"))
                    else -> {
                        val details = wire.getJSONArray("messages").getJSONObject(5).getJSONArray("reasoning_details")
                        if (expected.toString() != details.toString()) MockResponse().setResponseCode(400).setBody(
                            JSONObject().put("error", JSONObject().put("code", 400).put("message",
                                "messages.1.content.6: thinking or redacted_thinking blocks cannot be modified")).toString())
                        else stream(delta(finish = "stop", content = if (requests.get() == 4) "Done" else "Next"))
                    }
                }
            }
        }
        server.start()
        val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
        val folder = Files.createTempDirectory("signed-tool-output-").toFile()
        val artifact = File(folder, "report.txt")
        val executed = mutableListOf<String>()
        val input = JSONArray().put(JSONObject().put("role", "user").put("content", "Inspect data and save a report"))
        try {
            var failure: Throwable? = null
            val first = try {
                complete(client, input) { name, _ ->
                    executed += name
                    if (name == "create_local_text_file") artifact.writeText("confirmed tool result")
                    JSONObject().put("ok", true).put("filename", artifact.name).toString()
                }
            } catch (error: Throwable) { failure = error; null }
            assertEquals(listOf("file_info", "file_read_range", "create_local_text_file"), executed)
            assertEquals("confirmed tool result", artifact.readText())
            assertEquals("There must be one explicit POST per tool continuation, with no retry", 4, requests.get())
            failure?.let { throw it }
            assertEquals("Done", first!!.text)

            val saved = JSONObject(JSONObject().put("role", "assistant").put("text", first.text)
                .put("openrouter_protocol", first.protocol).toString())
            val history = listOf(JSONObject().put("role", "user").put("text", input.getJSONObject(0).getString("content")), saved,
                JSONObject().put("role", "user").put("text", "Continue"))
            val next = OpenRouterContextSnapshot.build(history, null, List(history.size) { emptyList() })
            assertEquals("Next", complete(client, next) { _, _ -> error("Saved tools must not be executed again") }.text)
            assertEquals(5, requests.get())
            for (wire in synchronized(seen) { seen.toList() }.drop(3)) {
                assertEquals("xhigh", wire.getJSONObject("reasoning").getString("effort"))
                val details = wire.getJSONArray("messages").getJSONObject(5).getJSONArray("reasoning_details")
                assertEquals(expected.toString(), details.toString())
                assertArrayEquals(expected.getJSONObject(6).getString("signature").toByteArray(),
                    details.getJSONObject(6).getString("signature").toByteArray())
                assertFalse(wire.toString().contains("_astra_"))
            }
            assertEquals("confirmed tool result", artifact.readText())
        } finally { client.close(); server.shutdown(); folder.deleteRecursively() }
    }

    @Test fun conflictingSignedMetadataStopsBeforeToolsAndPreservesOriginalFragments() {
        val server = MockWebServer().apply { start() }
        val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
        val checkpoints = mutableListOf<JSONObject>()
        val first = JSONObject().put("index", 0).put("id", "same-id").put("type", "reasoning.text")
            .put("format", "anthropic-claude-v1").put("text", "original").put("signature", "sig-a")
        val conflicting = JSONObject().put("index", 0).put("id", "same-id").put("type", "reasoning.text")
            .put("format", "different-format").put("signature", "sig-b")
        try {
            server.enqueue(stream(delta(JSONArray().put(first)), delta(JSONArray().put(conflicting),
                "create", "create_local_text_file", "tool_calls")))
            // A failing regression must finish promptly even if it incorrectly sends continuation.
            server.enqueue(stream(delta(finish = "stop", content = "Unsafe continuation")))
            val failure = assertThrows(java.io.IOException::class.java) {
                client.complete("synthetic-key", model, JSONArray().put(JSONObject().put("role", "user").put("content", "Save")),
                    tools = OpenRouterAppToolCatalog.tools(), maxTokens = null, reasoningEffort = "xhigh",
                    verbosity = null, parallelToolCalls = false,
                    executeTool = { _, _ -> error("Conflicting signed metadata must prevent tool execution") },
                    onProtocolCheckpoint = { checkpoints += JSONObject(it.toString()) })
            }
            assertTrue(failure.message.orEmpty().contains("Conflicting reasoning detail metadata"))
            assertEquals(1, server.requestCount)
            val interrupted = checkpoints.single().getJSONObject("interrupted_assistant")
            val raw = interrupted.getJSONArray("reasoning_delta_fragments")
            assertEquals(2, raw.length())
            assertEquals(first.toString(), raw.getJSONObject(0).toString())
            assertEquals(conflicting.toString(), raw.getJSONObject(1).toString())
            assertFalse(interrupted.getBoolean("replay_safe"))
            assertEquals(0, checkpoints.single().getJSONArray("tail").length())
        } finally { client.close(); server.shutdown() }
    }

}
