package com.example.openaichat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ResponseIndexedCheckpointTest {
    @Test fun reopenedIndexedAndLegacyStateKeepOriginalResponseIdentity() {
        val state = ResponseEventReducer.reduce(ResponseEventState(responseId = "resp_owned"),
            delta(1, 0, 0, "paid partial")).state
        for (indexed in listOf(false, true)) {
            val active = JSONObject().put("response_id", "resp_owned").put("text", state.text)
                .put("last_sequence_number", 1)
            if (indexed) active.put("indexed_checkpoint", ResponseIndexedCheckpoint.serialize(state))
            val reopened = ResponseIndexedCheckpoint.restore(active)
            assertEquals("resp_owned", reopened.responseId)
            try {
                ResponseEventReducer.reduce(reopened, JSONObject().put("type", "response.completed")
                    .put("response", JSONObject().put("id", "resp_other")))
                fail("Reopening must not permit another response to claim the original request")
            } catch (_: ResponsesResponseIdentityException) { }
            assertEquals("paid partial", reopened.text)
        }
    }

    @Test fun terminalCheckpointOfDifferentResponseIsRejectedBeforeFinalization() {
        val state = ResponseEventReducer.reduce(ResponseEventState(), JSONObject().put("type", "response.completed")
            .put("response", JSONObject().put("id", "resp_other").put("status", "completed"))).state
        val active = JSONObject().put("response_id", "resp_owned").put("text", state.text)
            .put("indexed_checkpoint", ResponseIndexedCheckpoint.serialize(state))
        try {
            ResponseIndexedCheckpoint.restore(active)
            fail("A matching local request cannot authorize a mismatched terminal response")
        } catch (error: UnrecoverableResponsesCheckpointException) {
            assertTrue(error.message.orEmpty().contains("response ID mismatch"))
        }
    }

    private fun delta(sequence: Int, output: Int, content: Int, value: String) =
        JSONObject().put("type", "response.output_text.delta")
            .put("sequence_number", sequence)
            .put("output_index", output).put("content_index", content)
            .put("delta", value)

    @Test fun sameIndexedPartAppendsAfterResumeAndSecondDisconnect() {
        var state = ResponseEventState()
        state = ResponseEventReducer.reduce(state, delta(10, 0, 0, "Hello")).state
        val checkpoint10 = JSONObject().put("text", state.text)
            .put("last_sequence_number", 10)
            .put("indexed_checkpoint", ResponseIndexedCheckpoint.serialize(state))
        var resumed = ResponseIndexedCheckpoint.restore(checkpoint10)
        resumed = ResponseEventReducer.reduce(resumed, delta(11, 0, 0, " World")).state
        assertEquals("Hello World", resumed.text)
        val checkpoint11 = JSONObject().put("text", resumed.text)
            .put("last_sequence_number", 11)
            .put("indexed_checkpoint", ResponseIndexedCheckpoint.serialize(resumed))
        resumed = ResponseIndexedCheckpoint.restore(checkpoint11)
        resumed = ResponseEventReducer.reduce(resumed, delta(12, 0, 0, "!")).state
        assertEquals("Hello World!", resumed.text)
        assertEquals(1, resumed.indexedTextParts.size)
    }

    @Test fun interleavedOutputsRetainOrderingAcrossMultipleResumeOwners() {
        var state = ResponseEventState()
        state = ResponseEventReducer.reduce(state, delta(1, 2, 0, "C1")).state
        state = ResponseEventReducer.reduce(state, delta(2, 0, 0, "A1")).state
        state = ResponseEventReducer.reduce(state, delta(3, 1, 0, "B1")).state
        val saved = JSONObject().put("text", "A1B1C1")
            .put("last_sequence_number", 3)
            .put("indexed_checkpoint", ResponseIndexedCheckpoint.serialize(state))
        var worker = ResponseIndexedCheckpoint.restore(saved)
        worker = ResponseEventReducer.reduce(worker, delta(4, 2, 0, "C2")).state
        worker = ResponseEventReducer.reduce(worker, delta(5, 0, 0, "A2")).state
        assertEquals("A1A2B1C1C2", worker.text)
        val reopen = JSONObject().put("text", worker.text)
            .put("last_sequence_number", 5)
            .put("indexed_checkpoint", ResponseIndexedCheckpoint.serialize(worker))
        var activity = ResponseIndexedCheckpoint.restore(reopen)
        activity = ResponseEventReducer.reduce(activity, JSONObject()
            .put("type", "response.output_text.done")
            .put("output_index", 1).put("content_index", 0).put("text", "B-DONE")).state
        assertEquals("A1A2B-DONEC1C2", activity.text)
        val finalMessage = JSONObject().put("type", "message").put("content",
            org.json.JSONArray().put(JSONObject().put("type", "output_text")
                .put("text", "AUTHORITATIVE")))
        val terminalResponse = JSONObject().put("id", "resp")
            .put("output", org.json.JSONArray().put(finalMessage))
        activity = ResponseEventReducer.reduce(activity,
            JSONObject().put("type", "response.completed")
                .put("response", terminalResponse)).state
        assertEquals("AUTHORITATIVE", activity.text)
        assertEquals(emptyMap<ResponseContentIndex, String>(), activity.indexedTextParts)
        val terminalSaved = JSONObject()
            .put("text", activity.text)
            .put("last_sequence_number", 6)
            .put("status", "completed")
            .put("indexed_checkpoint", ResponseIndexedCheckpoint.serialize(activity))
        val terminalRestored = ResponseIndexedCheckpoint.restore(terminalSaved)
        assertEquals("AUTHORITATIVE", terminalRestored.text)
        assertEquals(ResponseTerminalState.COMPLETED, terminalRestored.terminalState)
        assertEquals("resp", terminalRestored.terminalResponse?.getString("id"))
        assertEquals("AUTHORITATIVE",
            LegacyOpenAiCheckpoint.terminalOutputText(terminalRestored.terminalResponse))
    }

    @Test fun oldFlatCheckpointCanAppendUnindexedButMustRejectIndexedCorrection() {
        val old = JSONObject().put("last_sequence_number", 10).put("text", "Hello")
        var state = ResponseIndexedCheckpoint.restore(old)
        assertTrue(state.legacyFlatCheckpoint)
        state = ResponseEventReducer.reduce(state,
            JSONObject().put("type", "response.output_text.delta").put("delta", " World")).state
        assertEquals("Hello World", state.text)
        val newSnapshot = JSONObject().put("last_sequence_number", 11)
            .put("text", state.text).put("indexed_checkpoint", ResponseIndexedCheckpoint.serialize(state))
        val recovered = ResponseIndexedCheckpoint.restore(newSnapshot)
        assertTrue(recovered.legacyFlatCheckpoint)
        try {
            ResponseEventReducer.reduce(recovered, delta(12, 0, 0, "late"))
            fail("Incomplete legacy indexed context must not be reordered")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("legacy flattened"))
        }
        assertEquals("Hello World", recovered.text)
    }

    @Test fun corruptedFlatTextAndIndexedPartsAreRejectedBeforeNetwork() {
        val state = ResponseEventReducer.reduce(ResponseEventState(), delta(1, 0, 0, "Hello")).state
        val snapshot = JSONObject().put("last_sequence_number", 1)
            .put("text", "tampered")
            .put("indexed_checkpoint", ResponseIndexedCheckpoint.serialize(state))
        try {
            ResponseIndexedCheckpoint.restore(snapshot)
            fail("Tampering must fail closed")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("mismatch"))
        }
    }
    @Test fun canonicalTerminalCheckpointRoundTripsWithoutPartialMismatch() {
        var state = ResponseEventReducer.reduce(ResponseEventState(),
            delta(10, 0, 0, "partial")).state
        val signed = JSONObject().put("type", "reasoning")
            .put("encrypted_content", "opaque-signed-reasoning").put("id", "reason_001")
        val reply = JSONObject().put("type", "message")
            .put("content", org.json.JSONArray().put(JSONObject()
                .put("type", "output_text").put("text", "corrected")))
        val response = JSONObject().put("id", "resp_paid")
            .put("status", "completed")
            .put("output", org.json.JSONArray().put(signed).put(reply))
            .put("usage", JSONObject().put("output_tokens", 37))
        state = ResponseEventReducer.reduce(state, JSONObject()
            .put("type", "response.completed").put("response", response)).state
        assertEquals("corrected", state.text)
        assertTrue(state.indexedTextParts.isEmpty())
        val active = JSONObject().put("status", "completed").put("text", state.text)
            .put("last_sequence_number", 11)
            .put("indexed_checkpoint", ResponseIndexedCheckpoint.serialize(state))
        val reopened = ResponseIndexedCheckpoint.restore(active)
        assertEquals("corrected", reopened.text)
        assertEquals(ResponseTerminalState.COMPLETED, reopened.terminalState)
        assertEquals("opaque-signed-reasoning",
            reopened.terminalResponse!!.getJSONArray("output")
                .getJSONObject(0).getString("encrypted_content"))
        assertEquals(37L, reopened.terminalResponse!!.getJSONObject("usage")
            .getLong("output_tokens"))
        // A crash-gap owner cannot append late callbacks to paid terminal data.
        val late = ResponseEventReducer.reduce(reopened, delta(12, 0, 0, "impostor"))
        assertEquals(reopened, late.state)
        assertFalse(late.textChanged)
    }

    @Test fun incompleteOrFailedCanonicalTerminalAlsoRemainsRecoverable() {
        for (type in listOf("response.incomplete", "response.failed", "response.cancelled")) {
            var state = ResponseEventReducer.reduce(ResponseEventState(),
                delta(2, 1, 0, "draft")).state
            val content = JSONObject().put("type", "output_text")
                .put("text", "canonical")
            val message = JSONObject().put("type", "message")
                .put("content", org.json.JSONArray().put(content))
            val response = JSONObject().put("id", "resp_terminal")
                .put("status", type.removePrefix("response."))
                .put("output", org.json.JSONArray().put(message))
            state = ResponseEventReducer.reduce(state, JSONObject()
                .put("type", type).put("response", response)).state
            val active = JSONObject().put("text", state.text).put("status",
                state.terminalState!!.wireStatus).put("last_sequence_number", 3)
                .put("indexed_checkpoint", ResponseIndexedCheckpoint.serialize(state))
            val reopened = ResponseIndexedCheckpoint.restore(active)
            assertEquals("canonical", reopened.text)
            assertEquals(state.terminalState, reopened.terminalState)
            assertNotNull(reopened.terminalResponse)
        }
    }

    @Test fun missingTerminalProviderOutputIsUnrecoverableNotRetryable() {
        val state = ResponseEventReducer.reduce(ResponseEventState(),
            delta(2, 0, 0, "visible")).state
        for (terminal in ResponseTerminalState.values()) {
            val broken = JSONObject(ResponseIndexedCheckpoint.serialize(state))
                .put("terminal_state", terminal.wireStatus)
            val active = JSONObject().put("status", terminal.wireStatus)
                .put("text", "visible").put("last_sequence_number", 3)
                .put("indexed_checkpoint", broken.toString())
            try {
                ResponseIndexedCheckpoint.restore(active)
                fail("A paid terminal output cannot be regenerated from a flat string")
            } catch (e: UnrecoverableResponsesCheckpointException) {
                assertTrue(e.message.orEmpty().contains("not preserved"))
            }
        }
    }

    @Test fun streamingErrorsRestoreAsOwnedFailedTerminalWithOriginalErrorAndPartial() {
        for (type in listOf("error", "response.error")) {
            var state = ResponseEventReducer.reduce(ResponseEventState(responseId = "resp_owned"),
                delta(1, 0, 0, "paid partial")).state
            state = ResponseEventReducer.reduce(state, JSONObject().put("type", type)
                .put("error", JSONObject().put("code", "server_error")
                    .put("message", "provider failed"))).state
            assertEquals(ResponseTerminalState.FAILED, state.terminalState)
            val reopened = ResponseIndexedCheckpoint.restore(JSONObject().put("response_id", "resp_owned")
                .put("text", state.text).put("status", "failed")
                .put("indexed_checkpoint", ResponseIndexedCheckpoint.serialize(state)))
            assertEquals("paid partial", reopened.text)
            assertEquals("resp_owned", reopened.terminalResponse!!.getString("id"))
            assertEquals("failed", reopened.terminalResponse!!.getString("status"))
            assertEquals("server_error", reopened.terminalResponse!!.getJSONObject("error").getString("code"))
            assertEquals("provider failed", reopened.completionError)
            val late = ResponseEventReducer.reduce(reopened, delta(2, 0, 0, "late mutation"))
            assertEquals(reopened, late.state)
        }
    }

    @Test fun doneStructuredMessageSurvivesMissingTerminalOutputAndReplayButAddedMessageIsNotTrusted() {
        val message = JSONObject().put("type", "message").put("id", "msg_paid")
            .put("role", "assistant").put("content", org.json.JSONArray()
                .put(JSONObject().put("type", "output_text").put("text", "Received text")
                    .put("annotations", org.json.JSONArray().put(JSONObject()
                        .put("type", "url_citation").put("url", "https://example.test/paid"))))
                .put(JSONObject().put("type", "refusal").put("refusal", "Received refusal")))
        val initial = ResponseEventState(responseId = "resp_owned")
        val provisional = ResponseEventReducer.reduce(initial, JSONObject()
            .put("type", "response.output_item.added").put("output_index", 0).put("item", message)).state
        assertTrue("Provisional messages cannot be replayed as completed typed output",
            provisional.typedOutputItems.isEmpty())
        for (type in listOf("response.completed", "response.incomplete", "response.failed",
            "response.cancelled", "error", "response.error")) {
            var state = ResponseEventReducer.reduce(initial, JSONObject()
                .put("type", "response.output_item.done").put("output_index", 0).put("item", message)).state
            val event = JSONObject().put("type", type)
            if (type == "error" || type == "response.error")
                event.put("error", JSONObject().put("message", "provider failure"))
            else event.put("response", JSONObject().put("id", "resp_owned")
                .put("status", type.removePrefix("response.")))
            state = ResponseEventReducer.reduce(state, event).state
            val reopened = ResponseIndexedCheckpoint.restore(JSONObject().put("response_id", "resp_owned")
                .put("text", state.text).put("status", state.terminalState!!.wireStatus)
                .put("indexed_checkpoint", ResponseIndexedCheckpoint.serialize(state)))
            val effective = OpenAiEffectiveResponse.assemble(reopened.terminalResponse, reopened)
            val canonical = LegacyOpenAiCheckpoint.terminalOutputText(effective)
            assertEquals("Received textReceived refusal", canonical)
            val model = AstraResponseModel.fromResponse(effective, canonical)
            assertEquals("https://example.test/paid", model.annotations.getJSONObject(0).getString("url"))
            val assistant = model.toChatJson().put("provider", "openai")
                .put("openai_protocol_output", effective.getJSONArray("output"))
            val storedOutput = assistant.getJSONArray("openai_protocol_output")
            assertEquals(type, message.toString(), storedOutput.getJSONObject(0).toString())

        }
    }

    @Test fun interruptedSignedReasoningAndToolsSurviveResumeAndTypedHistoryReplay() {
        val signed = JSONObject().put("type", "reasoning").put("id", "r_signed")
            .put("encrypted_content", "opaque-signed-blob")
        val tool = JSONObject().put("type", "web_search_call").put("id", "t_real")
            .put("status", "completed").put("action",
                JSONObject().put("type", "search").put("query", "verified"))
        var state = ResponseEventReducer.reduce(ResponseEventState(),
            JSONObject().put("type", "response.created")
                .put("response", JSONObject().put("id", "resp_interrupted"))).state
        for ((index, item) in listOf(signed, tool).withIndex()) {
            state = ResponseEventReducer.reduce(state, JSONObject()
                .put("type", "response.output_item.done")
                .put("output_index", index).put("item", item)).state
        }
        state = ResponseEventReducer.reduce(state, delta(3, 2, 0, "Visible partial")).state
        val save = JSONObject().put("text", state.text).put("last_sequence_number", 3)
            .put("indexed_checkpoint", ResponseIndexedCheckpoint.serialize(state))
        val restored = ResponseIndexedCheckpoint.restore(save)
        assertEquals("Visible partial", restored.text)
        assertEquals(2, restored.typedOutputItems.size)
        val output = org.json.JSONArray()
        restored.typedOutputItems.toSortedMap().values.forEach {
            output.put(JSONObject(it.toString()))
        }
        val incomplete = JSONObject().put("role", "assistant")
            .put("provider", "openai").put("status", "disconnected")
            .put("incomplete", true).put("text", restored.text)
            .put("response_id", "resp_interrupted")
            .put("openai_protocol_output", output)
        val stored = incomplete.getJSONArray("openai_protocol_output")
        assertEquals("reasoning", stored.getJSONObject(0).getString("type"))
        assertEquals("opaque-signed-blob", stored.getJSONObject(0).getString("encrypted_content"))
        assertEquals("t_real", stored.getJSONObject(1).getString("id"))
        assertEquals("web_search_call", stored.getJSONObject(1).getString("type"))
        assertEquals("Visible partial", incomplete.getString("text"))
        assertFalse(incomplete.has("usage"))
        assertEquals(2, output.length())
    }

}
