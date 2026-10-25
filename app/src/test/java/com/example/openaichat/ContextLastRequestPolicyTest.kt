package com.example.openaichat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContextLastRequestPolicyTest {
    private fun event(id: String, time: Long, type: String = "request_completed") = JSONObject()
        .put("request_id", id).put("created_at", time).put("event_type", type)
        .put("provider", "openrouter").put("requested_model", "claude-test")
    private fun account(id: String) = JSONObject().put("local_request_id", id).put("provider", "openrouter")
        .put("requested_model", "claude-test")

    @Test fun stopWithoutAssistantRemainsLatestAndDoesNotBorrowOlderCache() {
        val old = account("old").put("cached_tokens", 500)
        val latest = ContextLastRequestPolicy.selectLatestCompletedRequest(listOf(old),
            listOf(event("old", 1), event("stopped", 2, "request_stopped")))!!
        assertEquals("stopped", latest.requestId)
        assertEquals(2L, latest.terminalAtMillis)
        assertNull(latest.accounting)
        assertEquals("openrouter", latest.provider)
        assertEquals("claude-test", latest.model)
        val snapshot = ContextStatusPolicy.build("anthropic/claude-test", emptyList(), null,
            OpenRouterClaudeRoute.AUTO, "off", emptyList(), emptyList(), old, 1, 10, lastRequest = latest)
        assertNull(snapshot.cacheReadTokens)
        assertEquals("openrouter", snapshot.lastResponseProvider)
        assertEquals("claude-test", snapshot.lastResponseModel)
        assertEquals(8L, snapshot.lastResponseAgeMillis)
    }

    @Test fun actualZeroSurvivesProviderSwitchAndReturnedAccountingIsDetached() {
        val row = account("latest").put("cached_tokens", 0).put("cache_write_tokens", JSONObject.NULL)
        val latest = ContextLastRequestPolicy.selectLatestCompletedRequest(listOf(row), listOf(event("latest", 3)))!!
        val snapshot = ContextStatusPolicy.build("anthropic/claude-new", emptyList(), null,
            OpenRouterClaudeRoute.AUTO, "off", emptyList(), emptyList(), null, null, 10, lastRequest = latest)
        assertEquals(0L, snapshot.cacheReadTokens)
        assertNull(snapshot.cacheWriteTokens)
        latest.accounting!!.put("cached_tokens", 100)
        assertEquals(0, row.getInt("cached_tokens"))
    }

    @Test fun currentRunningAndLateNonterminalEventsNeverDisplaceTerminalRequest() {
        val latest = ContextLastRequestPolicy.selectLatestCompletedRequest(emptyList(), listOf(
            event("done", 10), event("new", 20, "request_start"), event("done", 30, "usage_updated")))!!
        assertEquals("done", latest.requestId)
        assertEquals(10L, latest.terminalAtMillis)
        assertNull(ContextLastRequestPolicy.selectLatestCompletedRequest(emptyList(), listOf(event("new", 20, "request_start"))))
    }

    @Test fun newerTerminalAccountingWithoutEventDoesNotFallBackToOlderKnownCache() {
        val newer = account("latest").put("local_status", "stopped").put("created_at", 20)
        val latest = ContextLastRequestPolicy.selectLatestCompletedRequest(
            listOf(account("old").put("cached_tokens", 500), newer), listOf(event("old", 10)))!!
        assertEquals("latest", latest.requestId)
        assertNull(latest.terminalAtMillis)
        assertFalse(latest.accounting!!.has("cached_tokens"))
    }

    @Test fun terminalOrderUsesTimeAndIdAndAccountMayUseHttpAlias() {
        val row = account("local").put("http_request_id", "remote").put("response_model", "actual-model")
        val latest = ContextLastRequestPolicy.selectLatestCompletedRequest(listOf(row), listOf(
            event("old", 50).put("id", 1), event("remote", 50, "request_failed").put("id", 2)))!!
        assertEquals("local", latest.requestId)
        assertEquals("actual-model", latest.model)
    }
}
