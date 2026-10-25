package com.example.openaichat

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenRouterArtifactProtocolReconciliationTest {
    private val model = "anthropic/claude-sonnet-5.5"
    private val name = "create_local_text_file"
    private val arguments = JSONObject().put("filename", "result.txt").put("content", "full original")
    private val argumentsHash get() = StreamingJsonRequestBody.sha256(arguments)
    private fun input() = JSONArray().put(JSONObject().put("role", "user").put("content", "Create file"))
    private fun call(id: String, tool: String = name, args: JSONObject = arguments) = JSONObject()
        .put("id", id).put("type", "function").put("function", JSONObject().put("name", tool).put("arguments", args.toString()))
    private fun result(id: String, content: String) = JSONObject().put("role", "tool").put("tool_call_id", id).put("content", content)
    private fun outcome() = JSONObject().put("ok", true).put("type", "local_file").put("file_id", "artifact-id")
        .put("filename", "result.txt").put("sha256", "verified-sha").put("size_bytes", 13)
        .put("path", "/private/generated/result.txt").put("source_path", "/private/original")
        .put("patch_diff_path", "/private/generated/patch-diffs/result.json")
        .put("attachment", JSONObject().put("path", "/private/generated/result.txt"))
    private fun state(status: String = "in_flight") = JSONObject().put("state", status)
        .put("tool_name", name).put("arguments_sha256", argumentsHash)
    private fun protocol(calls: JSONArray = JSONArray().put(call("artifact-call"))) = JSONObject().put("version", 1)
        .put("input_sha256", OpenRouterProtocolCodec.fingerprint(input()))
        .put("binding_sha256", OpenRouterProtocolCodec.binding(model, null, "mock"))
        .put("tail", JSONArray().put(JSONObject().put("role", "assistant").put("content", "Prepare result")
            .put("reasoning_details", JSONArray()
                .put(JSONObject().put("type", "reasoning.encrypted").put("data", "opaque\u0000😀").put("signature", "sig-exact").put("unknown", JSONArray().put(1).put("x"))))
            .put("tool_calls", calls)))
        .put("tool_states", JSONObject().put("artifact-call", state()))
    private fun reconcile(protocol: JSONObject, artifact: JSONObject = outcome()) = OpenRouterProtocolCodec.reconcileCompletedLocalArtifact(
        protocol, "artifact-call", name, argumentsHash, artifact)

    @Test fun missingFirstResultIsInsertedBeforeExistingSiblingWithoutChangingSignedAssistant() {
        val source = protocol(JSONArray().put(call("artifact-call")).put(call("sibling", "file_info", JSONObject())))
        val exactSibling = result("sibling", " exact original sibling ").put("annotations", JSONArray().put("preserve"))
        source.getJSONArray("tail").put(exactSibling)
        val rawSource = source.toString()
        val assistant = source.getJSONArray("tail").getJSONObject(0).toString()
        val repaired = reconcile(source)!!
        assertEquals(rawSource, source.toString())
        assertEquals(assistant, repaired.getJSONArray("tail").getJSONObject(0).toString())
        assertEquals("artifact-call", repaired.getJSONArray("tail").getJSONObject(1).getString("tool_call_id"))
        assertEquals(LocalArtifactToolExecutor.toolResult(outcome()).toString(), repaired.getJSONArray("tail").getJSONObject(1).getString("content"))
        assertEquals(exactSibling.toString(), repaired.getJSONArray("tail").getJSONObject(2).toString())
        assertEquals("completed", repaired.getJSONObject("tool_states").getJSONObject("artifact-call").getString("state"))
    }

    @Test fun existingExactOutputAndDecorationsArePreservedAndSecondReconciliationIsNoOp() {
        val source = protocol()
        val exact = result("artifact-call", LocalArtifactToolExecutor.toolResult(outcome()).toString()).put("preserved_extra", "byte value")
        source.getJSONArray("tail").put(exact)
        val repaired = reconcile(source)!!
        assertEquals(source.getJSONArray("tail").toString(), repaired.getJSONArray("tail").toString())
        assertEquals(exact.toString(), repaired.getJSONArray("tail").getJSONObject(1).toString())
        assertNull(reconcile(repaired))
    }

    @Test fun unknownSiblingRemainsUnknownAndStillBlocksPaidReplay() {
        val source = protocol(JSONArray().put(call("sibling", "file_info", JSONObject())).put(call("artifact-call")))
        source.getJSONObject("tool_states").put("sibling", JSONObject().put("state", "outcome_unknown")
            .put("tool_name", "file_info").put("arguments_sha256", StreamingJsonRequestBody.sha256(JSONObject())))
        val repaired = reconcile(source)!!
        assertEquals(2, repaired.getJSONArray("tail").length())
        assertEquals("outcome_unknown", repaired.getJSONObject("tool_states").getJSONObject("sibling").getString("state"))
        assertThrows(OpenRouterToolOutcomeUnknownException::class.java) {
            OpenRouterProtocolCodec.append(input(), JSONObject().put("openrouter_protocol", repaired))
        }
    }

    @Test fun identityStateAndExistingOutputConflictsNeverModifySource() {
        val conflicting = listOf<(JSONObject) -> Unit>(
            { it.getJSONArray("tail").getJSONObject(0).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").put("name", "different") },
            { it.getJSONArray("tail").getJSONObject(0).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").put("arguments", "{\"content\":\"different\"}") },
            { it.getJSONObject("tool_states").getJSONObject("artifact-call").put("tool_name", "different") },
            { it.getJSONObject("tool_states").getJSONObject("artifact-call").put("arguments_sha256", "different") },
            { it.getJSONObject("tool_states").getJSONObject("artifact-call").put("state", "not_started") },
            { it.getJSONArray("tail").put(result("artifact-call", "conflicting output")) }
        )
        for (corrupt in conflicting) {
            val source = protocol().also(corrupt)
            val original = source.toString()
            assertThrows(OpenRouterToolOutcomeUnknownException::class.java) { reconcile(source) }
            assertEquals(original, source.toString())
        }
    }

    @Test fun repeatedActualIdAcrossAssistantBlocksAndMalformedResultsAreRejectedWithoutNormalization() {
        val variants = listOf<(JSONObject) -> Unit>(
            { it.getJSONArray("tail").put(result("artifact-call", LocalArtifactToolExecutor.toolResult(outcome()).toString()))
                .put(JSONObject(it.getJSONArray("tail").getJSONObject(0).toString())) },
            { it.getJSONArray("tail").put("unrecognized historical data") },
            { it.getJSONArray("tail").put(result("foreign", "unknown")) },
            { it.getJSONArray("tail").put(result("artifact-call", LocalArtifactToolExecutor.toolResult(outcome()).toString()))
                .put(result("artifact-call", LocalArtifactToolExecutor.toolResult(outcome()).toString())) }
        )
        for (corrupt in variants) {
            val source = protocol().also(corrupt)
            val original = source.toString()
            assertThrows(OpenRouterToolOutcomeUnknownException::class.java) { reconcile(source) }
            assertEquals(original, source.toString())
        }
    }

    @Test fun absentCallIsNoOpAndLegacyMissingStateCanUseVerifiedSqlIdentity() {
        val source = protocol(JSONArray().put(call("other")))
        assertNull(reconcile(source))
        val legacy = protocol().apply { remove("tool_states") }
        val repaired = reconcile(legacy)!!
        assertEquals("completed", repaired.getJSONObject("tool_states").getJSONObject("artifact-call").getString("state"))
    }

    @Test fun missingSecondResultIsInsertedAfterUnchangedFirstBeforeVisibleAssistant() {
        val source = protocol(JSONArray().put(call("first", "file_info", JSONObject())).put(call("artifact-call")))
        val first = result("first", " exact first result ")
        val visible = JSONObject().put("role", "assistant").put("content", "Stopped after file")
        source.getJSONArray("tail").put(first).put(visible)
        val repaired = reconcile(source)!!
        assertEquals(first.toString(), repaired.getJSONArray("tail").getJSONObject(1).toString())
        assertEquals("artifact-call", repaired.getJSONArray("tail").getJSONObject(2).getString("tool_call_id"))
        assertEquals(visible.toString(), repaired.getJSONArray("tail").getJSONObject(3).toString())
    }

    @Test fun explicitNextUserTurnSendsVerifiedReconciledOutputAndExactSignedBlocksWithoutExecutingTools() {
        val server = MockWebServer(); server.start()
        val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
        try {
            val original = protocol()
            val signedAssistant = original.getJSONArray("tail").getJSONObject(0).toString()
            val repaired = reconcile(original)!!
            val history = input()
            assertTrue(OpenRouterProtocolCodec.append(history, JSONObject().put("openrouter_protocol", repaired)))
            history.put(JSONObject().put("role", "user").put("content", "Continue explicitly"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Done\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            assertEquals("Done", client.complete("mock", model, history, tools = null, maxTokens = null,
                reasoningEffort = "high", verbosity = null, parallelToolCalls = false,
                executeTool = { _, _ -> throw AssertionError("Recovery cannot execute a tool") }).text)
            val sent = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            assertEquals(signedAssistant, sent.getJSONObject(1).toString())
            assertEquals(LocalArtifactToolExecutor.toolResult(outcome()).toString(), sent.getJSONObject(2).getString("content"))
            assertFalse(sent.getJSONObject(2).getString("content").contains("/private/"))
            assertEquals(1, server.requestCount)
        } finally { client.close(); server.shutdown() }
    }
}
