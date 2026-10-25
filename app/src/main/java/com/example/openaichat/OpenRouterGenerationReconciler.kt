package com.example.openaichat

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Bounded read-only OpenRouter generation reconciliation. Every request is
 * an already-observed generation ID, never a new model completion. The metadata
 * callback is responsible for immutable local request and round ownership.
 */
class OpenRouterGenerationReconciler(
    private val http: OkHttpClient,
    private val baseUrl: HttpUrl = "https://openrouter.ai/api/v1/".toHttpUrl(),
    private val bodyLimits: BoundedResponseDecoder.BodyLimits = BoundedResponseDecoder.BodyLimits(),
    private val onDiagnostic: (Int, String) -> Unit = { _, _ -> },
    private val maxDurationMillis: Long = 120_000L,
    private val monotonicNanos: () -> Long = System::nanoTime
) {
    fun reconcile(
        rounds: List<JSONObject>,
        apiKey: String,
        onMetadata: (generationId: String, metadata: JSONObject) -> Unit
    ): Int {
        if (apiKey.isBlank()) return 0
        require(maxDurationMillis > 0L) { "Reconciliation time budget must be positive" }
        val startedNanos = monotonicNanos()
        val budgetNanos = TimeUnit.MILLISECONDS.toNanos(maxDurationMillis)
        var attempts = 0
        fun report(status: Int, failure: IOException) {
            try { onDiagnostic(status, "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}") }
            catch (_: Exception) { /* Optional diagnostics do not replace unknown accounting. */ }
        }
        val candidates = rounds.asSequence()
            .filter { it.optString("cost_kind", "unknown") != "provider_reported" }
            .mapNotNull { it.optString("generation_id").takeIf { id ->
                id.matches(Regex("[A-Za-z0-9_-]{1,128}"))
            } }
            .distinct()
        for (generationId in candidates) {
            // Reconcile every pending ID while the explicit read-only pass budget
            // remains. Successfully saved IDs are skipped on the next pass/reopen.
            if (Thread.currentThread().isInterrupted) break
            val remainingNanos = budgetNanos - (monotonicNanos() - startedNanos).coerceAtLeast(0L)
            if (remainingNanos <= 0L) break
            val url = baseUrl.newBuilder().addPathSegment("generation")
                .addQueryParameter("id", generationId).build()
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer $apiKey").get().build()
            var code = 0
            var metadata: JSONObject? = null
            try {
                val call = http.newCall(request)
                val configuredNanos = call.timeout().timeoutNanos()
                call.timeout().timeout(if (configuredNanos > 0L) minOf(configuredNanos, remainingNanos)
                    else remainingNanos, TimeUnit.NANOSECONDS)
                call.execute().use { response ->
                    code = response.code
                    metadata = if (response.isSuccessful) {
                        val raw = BoundedResponseDecoder.readBody(response.body, bodyLimits)
                        try { JSONObject(raw).optJSONObject("data") }
                        catch (_: Exception) { null }
                    } else {
                        BoundedResponseDecoder.readErrorBody(response.body, onDecodeFailure = { report(code, it) })
                        null
                    }
                }
            } catch (failure: IOException) {
                report(code, failure)
                if (code != 0) attempts++
                break
            } catch (_: Exception) {
                break
            }
            attempts++
            if (code == 401 || code == 403 || code == 429 || code >= 500) break
            val verified = metadata ?: continue
            if (code != 200 || verified.optString("id") != generationId) continue
            // total_cost is the USD amount; 'usage' is not a token usage JSON here.
            val valid = verified.opt("total_cost")?.toString()?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0.0 }
            if (valid != null) onMetadata(generationId, verified)
        }
        return attempts
    }
}
