package com.example.openaichat

import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReconciliationBoundedDecoderTest {
    @Test fun metadataPreservesCompleteOwnedUnicodeSnapshotWithOneGet() {
        withServer { server ->
            server.enqueue(MockResponse().setBody(
                """{"data":{"id":"gen-owned","total_cost":0.125,"provider_name":"привет 😀"}}"""
            ))
            val metadata = mutableListOf<JSONObject>()
            val lookups = OpenRouterGenerationReconciler(OkHttpClient(), server.url("/v1/"))
                .reconcile(rounds(), "synthetic-key") { _, body -> metadata += body }
            assertEquals(1, lookups)
            assertEquals("привет 😀", metadata.single().getString("provider_name"))
            assertEquals(1, server.requestCount)
            assertEquals("GET", server.takeRequest().method)
        }
    }

    @Test fun metadataSuccessfulBodyLimitOrInvalidUtf8LeavesUnknownAccountingAndStopsLookup() {
        val responses = listOf(
            MockResponse().setBody(JSONObject().put("data", JSONObject().put("id", "gen-owned")
                .put("total_cost", 123).put("extra", "x".repeat(200))).toString()),
            MockResponse().setBody(Buffer().write(byteArrayOf(0xF0.toByte(), 0x9F.toByte())))
        )
        for ((index, response) in responses.withIndex()) withServer { server ->
            server.enqueue(response)
            val diagnostics = mutableListOf<Pair<Int, String>>()
            var snapshots = 0
            val count = OpenRouterGenerationReconciler(OkHttpClient(), server.url("/v1/"),
                bodyLimits = BoundedResponseDecoder.BodyLimits(maxBytes = 128, maxChars = 512),
                onDiagnostic = { status, reason -> diagnostics += status to reason })
                .reconcile(rounds() + JSONObject().put("generation_id", "gen-second"), "synthetic-key") { _, _ -> snapshots++ }
            assertEquals(1, count)
            assertEquals(0, snapshots)
            assertEquals(200, diagnostics.single().first)
            assertTrue(diagnostics.single().second.contains(if (index == 0) "byte limit" else "MalformedInputException"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun metadataOversizedForbiddenErrorPreservesStatusDiagnosticAndStopsLookup() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("x".repeat(1024 * 1024 + 1)))
            val diagnostics = mutableListOf<Pair<Int, String>>()
            val count = OpenRouterGenerationReconciler(OkHttpClient(), server.url("/v1/"),
                onDiagnostic = { status, reason -> diagnostics += status to reason })
                .reconcile(rounds() + JSONObject().put("generation_id", "gen-second"), "synthetic-key") { _, _ ->
                    error("Forbidden metadata cannot attribute cost")
                }
            assertEquals(1, count)
            assertEquals(403, diagnostics.single().first)
            assertTrue("Expected bounded decoder limit for oversized HTTP 403, got: ${diagnostics.single().second}",
                isBoundedDecoderLimitMessage(diagnostics.single().second))
            assertFalse(diagnostics.single().second.contains("synthetic-key"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun readBodyEnforcesExactByteLimitDeterministicallyWithSmallCap() {
        val limits = BoundedResponseDecoder.BodyLimits(maxBytes = 128, maxChars = 512)
        val boundary = "x".repeat(128)
        boundary.toResponseBody().use { body ->
            assertEquals(boundary, BoundedResponseDecoder.readBody(body, limits))
        }
        "x".repeat(256).toResponseBody().use { body ->
            val failure = assertThrows(ResponsesSseLimitException::class.java) {
                BoundedResponseDecoder.readBody(body, limits)
            }
            assertEquals("HTTP body byte limit exceeded: 128 bytes", failure.message)
        }
    }

    private fun isBoundedDecoderLimitMessage(diagnostic: String?): Boolean {
        val text = diagnostic.orEmpty()
        return text.contains("byte limit") || text.contains("лимит памяти")
    }

    private fun rounds() = listOf(JSONObject().put("generation_id", "gen-owned").put("cost_kind", "unknown"))
    private fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try { block(server) } finally { server.shutdown() }
    }
}
