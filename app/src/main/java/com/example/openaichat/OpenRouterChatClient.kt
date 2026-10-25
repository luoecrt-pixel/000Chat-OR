package com.example.openaichat

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException

data class OpenRouterCompletionResult(
    val text: String,
    val usage: JSONObject?,
    val responseModel: String?,
    val assistantMessage: JSONObject,
    val toolNames: List<String>,
    val finishReason: String? = null,
    val protocol: JSONObject? = null,
    val fileIoTelemetry: JSONObject? = null,
    val roundsCompleted: Int = 0
)

class OpenRouterCompletionException(
    message: String,
    val partialText: String,
    cause: Throwable? = null,
    val accumulatedUsage: JSONObject? = null,
    val fileIoTelemetry: JSONObject? = null,
    val roundsCompleted: Int = 0
) : IOException(message, cause)

class OpenRouterUpstreamException(
    message: String,
    val stage: String,
    val roundIndex: Int,
    val httpStatus: Int?,
    val errorType: String?,
    val providerName: String?,
    val providerCode: String?,
    val safeMetadata: String?,
    val retryEligible: Boolean = false,
    val retryAfterSeconds: Long? = null,
    val limitSource: String? = null,
    val resetsAt: String? = null
) : IOException(message)

private data class OpenRouterStreamToolCall(
    var id: String = "",
    var type: String = "function",
    var name: String = "",
    val arguments: StringBuilder = StringBuilder()
)

class OpenRouterChatClient(
    private val baseUrl: String = "https://openrouter.ai/api/v1",
    httpClientOverride: OkHttpClient? = null,
    internal val reconcileGenerations: Boolean = true,
    internal val sseLimits: BoundedResponseDecoder.SseLimits = BoundedResponseDecoder.SseLimits()
) {
    internal val metadataBaseUrl get() = (baseUrl.trimEnd('/') + "/").toHttpUrl()
    private companion object {
        // Safety ceiling for one OpenRouter task. Do not add smaller per-response
        // time/iteration caps here: this ceiling is the emergency stop.
        const val COMPLETE_TIME_LIMIT_MS = 5 * 60 * 60 * 1000L
        const val MAX_ACCUMULATED_TOTAL_TOKENS = 5_000_000L
    }

    private fun upstreamError(
        error: JSONObject, stage: String, roundIndex: Int, status: Int? = null, retryAfterHeader: String? = null
    ): OpenRouterUpstreamException =
        OpenRouterErrorClassifier.classify(error, stage, roundIndex, status, retryAfterHeader)

    private val httpClient = httpClientOverride ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    fun complete(
        apiKey: String,
        model: String,
        messages: JSONArray,
        sessionId: String? = null,
        tools: JSONArray?,
        maxTokens: Int?,
        reasoningEffort: String?,
        verbosity: String?,
        parallelToolCalls: Boolean,
        executeTool: (name: String, arguments: JSONObject) -> String,
        onCallCreated: (Call) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
        onRoundObserved: (roundIndex: Int, generationId: String?, usage: JSONObject?) -> Unit = { _, _, _ -> },
        onTextDelta: (String) -> Unit = {},
        onPayloadObserved: (roundIndex: Int, jsonBytes: Int) -> Unit = { _, _ -> },
        cacheMode: String = "1h",
        onProtocolWarning: (String) -> Unit = {},
        onProtocolCheckpoint: (JSONObject) -> Unit = {},
        pdfEngine: String? = null,
        responseFormat: JSONObject? = null,
        claudeRoute: OpenRouterClaudeRoute = OpenRouterClaudeRoute.AUTO,
        onFileIoObserved: (roundIndex: Int, stats: JSONObject) -> Unit = { _, _ -> },
        executeToolWithId: ((id: String, name: String, arguments: JSONObject) -> String)? = null
    ): OpenRouterCompletionResult {
        // Resolve once before HTTP and all tool rounds; Activity already resolves auto.
        val requestEffort = OpenRouterReasoningPolicy.requestEffort(model, reasoningEffort)
        val history = JsonProtocol.arrayCopy(messages)
        // Validate direct callers as well as ContextSnapshot before any paid POST.
        OpenRouterProtocolCodec.completePendingToolCalls(history)
        val inputCount = history.length()
        val binding = OpenRouterProtocolCodec.binding(model, tools, apiKey, responseFormat, claudeRoute)
        val sourceInputHash = OpenRouterProtocolCodec.fingerprint(history)
        val bindingReset = OpenRouterProtocolCodec.checkBinding(history, binding)
        if (bindingReset) {
            onProtocolWarning("Изменён подписанный контекст Claude. История сообщений сохранена; прежние служебные блоки мышления нельзя повторно использовать с этим запросом.")
        }
        val inputHash = if (bindingReset) StreamingJsonRequestBody.sha256(history) else sourceInputHash
        val allowedFunctionTools: Set<String> = buildSet {
            if (tools != null) {
                for (i in 0 until tools.length()) {
                    val item = tools.optJSONObject(i) ?: continue
                    if (item.optString("type").startsWith("openrouter:")) continue
                    val fnName = item.optJSONObject("function")?.let { JsonProtocol.nonBlank(it, "name") }
                        ?: JsonProtocol.nonBlank(item, "name")
                    fnName?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        }
        val toolNames = mutableListOf<String>()
        val toolStates = JSONObject()
        val accumulatedText = mutableListOf<String>()
        val roundUsages = mutableListOf<JSONObject?>()
        var responseModel: String? = null
        var accumulatedTotalTokens = 0L
        var nextRoundIndex = 0
        var roundsCompleted = 0
        var previousFileIoSnapshot: FileBackedIoTelemetry.Snapshot? = null
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COMPLETE_TIME_LIMIT_MS)

        fun snapshotAccumulatedUsage(): JSONObject? = OpenRouterUsageAccumulator.aggregate(roundUsages)

        fun currentFileIoJson(): JSONObject? = try {
            (previousFileIoSnapshot ?: FileBackedIoTelemetry.snapshot(history)).takeIf { it.hasSourceWork }?.toJson()
        } catch (_: Exception) { null }

        fun budgetException(message: String): OpenRouterCompletionException =
            OpenRouterCompletionException(
                message = message,
                partialText = accumulatedText.joinToString("\n\n").trim(),
                accumulatedUsage = snapshotAccumulatedUsage(),
                fileIoTelemetry = currentFileIoJson(), roundsCompleted = roundsCompleted
            )

        fun ensureActive() {
            if (!shouldContinue()) {
                throw CancellationException("OpenRouter request is no longer current.")
            }
        }

        fun ensureWithinBudget() {
            ensureActive()
            if (System.nanoTime() >= deadlineNanos) {
                throw budgetException("Лимит времени OpenRouter complete() (5 часов) исчерпан.")
            }
            if (accumulatedTotalTokens >= MAX_ACCUMULATED_TOTAL_TOKENS) {
                throw budgetException("Аварийный бюджет одного ответа OpenRouter (5 млн usage-токенов) исчерпан. Он суммирует вход каждого раунда, включая повторную историю и кэш, и выход с мышлением; история чата сохранена.")
            }
        }

        fun emitProtocolCheckpoint(interruptedAssistant: JSONObject? = null) {
            val checkpointTail = JSONArray()
            for (i in inputCount until history.length()) checkpointTail.put(JsonProtocol.copy(history.get(i)))
            onProtocolCheckpoint(
                JSONObject().put("version", 1).put("input_sha256", sourceInputHash)
                    .put("wire_input_sha256", inputHash).put("binding_sha256", binding).put("tail", checkpointTail)
                    .put("tool_states", JsonProtocol.objectCopy(toolStates))
                    .apply { interruptedAssistant?.let { put("interrupted_assistant", it) } }
            )
        }

        fun requestMessage(toolChoice: String): Pair<JSONObject, JSONObject> {
            ensureWithinBudget()
            OpenRouterProtocolCodec.completePendingToolCalls(history)
            val body = JSONObject()
                .put("model", model)
                .put("messages", history)
                .put("stream", true)
                .put("stream_options", JSONObject().put("include_usage", true))
                .put("parallel_tool_calls", parallelToolCalls)
                .put("usage", JSONObject().put("include", true))
            maxTokens?.takeIf { it > 0 }?.let { body.put("max_tokens", it) }
            responseFormat?.let {
                body.put("response_format", JsonProtocol.objectCopy(it))
            }
            claudeRoute.provider(model, responseFormat != null)?.let { body.put("provider", it) }
            // Explicitly disable OpenRouter middle-out context compression on every
            // request so conversation history and signed prefixes are never silently trimmed.
            val plugins = JSONArray().put(
                JSONObject().put("id", "context-compression").put("enabled", false)
            )
            pdfEngine?.let { engine ->
                require(engine in setOf("native", "auto", "cloudflare-ai", "mistral-ocr"))
                if (engine != "auto") {
                    plugins.put(
                        JSONObject().put("id", "file-parser")
                            .put("pdf", JSONObject().put("engine", engine))
                    )
                }
            }
            body.put("plugins", plugins)
            tools?.takeIf { it.length() > 0 }?.let {
                body.put("tools", it)
                body.put("tool_choice", toolChoice)
            }
            sessionId?.trim()?.takeIf { it.isNotBlank() }?.let {
                body.put("session_id", it)
            }
            OpenRouterCachePolicy.configureRequest(body, model, cacheMode)
            requestEffort?.let {
                body.put("reasoning", JSONObject().put("effort", it))
            }
            verbosity?.takeIf {
                it.isNotBlank() && OpenRouterReasoningPolicy.supportsResponseVerbosity(model)
            }?.let { body.put("verbosity", it) }

            // An inference POST must never be replayed by OkHttp's 408/503 follow-up.
            // Each explicit tool continuation below constructs its own new body.
            val wireBody = StreamingJsonRequestBody(body, oneShot = true)
            OpenRouterAttachmentPreflight.validateRequestBytes(model, wireBody.contentLength(), claudeRoute)
            ensureActive()
            val roundIndex = nextRoundIndex++
            onRoundObserved(roundIndex, null, null)
            onPayloadObserved(roundIndex, wireBody.contentLength().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .addHeader("HTTP-Referer", "com.example.openaichat")
                .addHeader("X-Title", "OpenAIApiChat")
                .addHeader("X-OpenRouter-Cache", "false")
                .post(wireBody)
                .build()

            val call = httpClient.newCall(request)
            if (!shouldContinue()) {
                call.cancel()
                throw CancellationException("OpenRouter request is no longer current.")
            }
            onCallCreated(call)
            ensureActive()
            val remainingMillis = TimeUnit.NANOSECONDS.toMillis((deadlineNanos - System.nanoTime()).coerceAtLeast(0L))
            if (remainingMillis <= 0L) {
                call.cancel()
                throw budgetException("Лимит времени OpenRouter complete() (5 часов) исчерпан.")
            }
            call.timeout().timeout(remainingMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)

            var role = "assistant"
            var sawDone = false
            var finishReason: String? = null
            var roundResponseModel: String? = null
            var roundReportedCost: Double? = null
            var roundUsage: JSONObject? = null
            var roundUsageCommitted = false
            fun commitRoundUsage() {
                if (roundUsageCommitted) return
                roundUsageCommitted = true
                val observed = roundUsage?.let(JsonProtocol::objectCopy)
                roundReportedCost?.let { observed?.put("cost", it) }
                roundUsages += observed
                roundUsage?.let { u ->
                    accumulatedTotalTokens += u.optLong("total_tokens", 0L).takeIf { it > 0L }
                        ?: (u.optLong("prompt_tokens", 0L).coerceAtLeast(0L) + u.optLong("completion_tokens", 0L).coerceAtLeast(0L))
                }
            }
            var generationId: String? = null
            val contentParts = StringBuilder()
            var lastTextSnapshot = 0L
            var emittedTextLength = 0
            val reasoningDetails = ReasoningDetailAccumulator()
            val toolCallsByIndex = sortedMapOf<Int, OpenRouterStreamToolCall>()

            fun emitTextSnapshot(force: Boolean = false) {
                if (contentParts.length == emittedTextLength) return
                val now = System.nanoTime()
                if (!force && lastTextSnapshot != 0L && now - lastTextSnapshot < 75_000_000L) return
                lastTextSnapshot = now; emittedTextLength = contentParts.length
                onTextDelta((accumulatedText + contentParts.toString()).filter { it.isNotEmpty() }.joinToString("\n\n"))
            }

            fun consume(data: String) {
                if (data == "[DONE]") {
                    sawDone = true
                    return
                }
                val json = try {
                    JSONObject(data)
                } catch (e: Exception) {
                    throw IOException("Invalid OpenRouter SSE event", e)
                }
                json.optJSONObject("error")?.let { error ->
                    throw upstreamError(error, "SSE", roundIndex)
                }
                val candidateId = JsonProtocol.nonBlank(json, "id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
                if (candidateId != null && generationId == null) {
                    generationId = candidateId
                    onRoundObserved(roundIndex, candidateId, null)
                }
                JsonProtocol.nonBlank(json, "model")?.let { roundResponseModel = it }
                json.optJSONObject("usage")?.let { usage ->
                    // Repeated final usage frames are snapshots, not billable deltas.
                    roundUsage = OpenRouterUsageAccumulator.mergeSnapshot(roundUsage, usage)
                    // Cost may arrive after finish_reason, just before [DONE].
                    // Explicit zero is known; missing/invalid values are not.
                    // A later missing/null/invalid field is not evidence that a confirmed
                    // charge disappeared. Match observeOpenRouterRound's durable contract.
                    OpenRouterUsageSnapshot.from(usage).reportedCostUsd?.let { roundReportedCost = it }
                    onRoundObserved(roundIndex, generationId, roundUsage)
                }
                val choices = json.optJSONArray("choices")
                if (choices == null || choices.length() == 0) return
                val choice = choices.optJSONObject(0) ?: return
                JsonProtocol.nonBlank(choice, "finish_reason")?.let { finishReason = it }
                val delta = choice.optJSONObject("delta") ?: return

                JsonProtocol.nonBlank(delta, "role")?.let { incomingRole ->
                    if (incomingRole != "assistant") throw IOException("Invalid OpenRouter delta role: $incomingRole")
                    role = incomingRole
                }
                JsonProtocol.text(delta, "content")?.takeIf { it.isNotEmpty() }?.let { chunk ->
                    contentParts.append(chunk)
                    emitTextSnapshot()
                }

                delta.optJSONArray("reasoning_details")?.let(reasoningDetails::accept)

                delta.optJSONArray("tool_calls")?.let { calls ->
                    for (i in 0 until calls.length()) {
                        val call = calls.optJSONObject(i) ?: continue
                        val index = call.optInt("index", i)
                        val target = toolCallsByIndex.getOrPut(index) { OpenRouterStreamToolCall() }
                        JsonProtocol.nonBlank(call, "id")?.let { incomingId ->
                            if (target.id.isNotEmpty() && target.id != incomingId) {
                                throw IOException("Conflicting streamed tool call id at index $index")
                            }
                            target.id = incomingId
                        }
                        JsonProtocol.nonBlank(call, "type")?.let { target.type = it }
                        call.optJSONObject("function")?.let { function ->
                            JsonProtocol.nonBlank(function, "name")?.let { incomingName ->
                                if (target.name.isEmpty()) target.name = incomingName
                                else if (incomingName != target.name) target.name += incomingName
                            }
                            JsonProtocol.text(function, "arguments")?.takeIf { it.isNotEmpty() }?.let { target.arguments.append(it) }
                        }
                    }
                }
            }

            try {
                call.execute().use { httpResponse ->
                    ensureActive()
                    val headerGenId = httpResponse.header("X-Generation-Id")?.trim()
                        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
                    if (headerGenId != null && generationId == null) {
                        generationId = headerGenId
                        onRoundObserved(roundIndex, headerGenId, null)
                    }
                    if (!httpResponse.isSuccessful) {
                        val retryAfterHeader = httpResponse.header("Retry-After")
                        val raw = BoundedResponseDecoder.readErrorBody(httpResponse.body)
                        val error = runCatching { JSONObject(raw).optJSONObject("error") }.getOrNull()
                        if (error != null) throw upstreamError(error, "HTTP", roundIndex, httpResponse.code, retryAfterHeader)
                        val fallbackError = JSONObject().put("message", "HTTP ${httpResponse.code}: ${raw.take(320)}")
                        throw upstreamError(fallbackError, "HTTP", roundIndex, httpResponse.code, retryAfterHeader)
                    }
                    val bodyResponse = httpResponse.body ?: throw IOException("OpenRouter response has no body")
                    BoundedResponseDecoder.openSse(bodyResponse.byteStream(), sseLimits).use { reader ->
                        BoundedResponseDecoder.readSse(reader, sseLimits, onLine = { ensureWithinBudget() }) { event ->
                            consume(event.data)
                            sawDone
                        }
                    }
                    if (!sawDone) throw IOException("OpenRouter SSE ended before [DONE]")
                }
            } catch (e: Exception) {
                emitTextSnapshot(force = true)
                commitRoundUsage()
                val receivedReasoning = reasoningDetails.receivedFragments()
                if (receivedReasoning.length() > 0) {
                    // Keep the original fragments for local recovery, outside the replay tail.
                    // An ambiguous/incomplete signed turn must never be guessed into a new POST.
                    emitProtocolCheckpoint(JSONObject().put("role", "assistant")
                        .put("content", contentParts.toString())
                        .put("reasoning_details", reasoningDetails.array())
                        .put("reasoning_delta_fragments", receivedReasoning).put("replay_safe", false))
                }
                if (e is CancellationException) throw e
                if (e is OpenRouterCompletionException) {
                    val partial = buildList {
                        addAll(accumulatedText)
                        if (contentParts.isNotEmpty()) add(contentParts.toString())
                    }.joinToString("\n\n").trim()
                    throw OpenRouterCompletionException(
                        message = e.message ?: e.javaClass.simpleName,
                        partialText = partial,
                        cause = e.cause ?: e,
                        accumulatedUsage = snapshotAccumulatedUsage() ?: e.accumulatedUsage,
                        fileIoTelemetry = e.fileIoTelemetry ?: currentFileIoJson(),
                        roundsCompleted = roundsCompleted
                    )
                }
                if (contentParts.isNotEmpty()) {
                    accumulatedText += contentParts.toString()
                }
                throw e
            } finally {
                // Optional diagnostics must not replace the result, cancellation or upstream error.
                try {
                    val stats = FileBackedIoTelemetry.snapshot(body)
                    val delta = stats.deltaFrom(previousFileIoSnapshot)
                    previousFileIoSnapshot = stats
                    onFileIoObserved(roundIndex, stats.toJson()
                        .put("round_read_passes", delta.readPasses).put("round_read_bytes", delta.readBytes)
                        .put("round_work_ms", delta.workMs))
                } catch (_: Exception) {
                    // Diagnostics are best-effort. Never swallow VM/heap Errors.
                }
            }

            emitTextSnapshot(force = true)
            ensureActive()
            commitRoundUsage()
            roundsCompleted++
            val message = JSONObject()
                .put("role", role)
                .put("content", contentParts.toString())
            val completeDetails = reasoningDetails.array()
            if (completeDetails.length() > 0) {
                message.put("reasoning_details", completeDetails)
            }
            if (toolCallsByIndex.isNotEmpty()) {
                val toolCalls = JSONArray()
                val seenToolCallIds = mutableSetOf<String>()
                toolCallsByIndex.forEach { (_, call) ->
                    if (finishReason in setOf("tool_calls", "stop") && (call.id.isBlank() || call.name.isBlank())) {
                        throw IOException("Malformed streamed tool call")
                    }
                    if (call.id.isNotBlank() && !seenToolCallIds.add(call.id)) {
                        throw IOException("Duplicate streamed tool call id: ${call.id}")
                    }
                    toolCalls.put(
                        JSONObject()
                            .put("id", call.id)
                            .put("type", call.type)
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", call.name)
                                    .put("arguments", call.arguments.toString())
                            )
                    )
                }
                message.put("tool_calls", toolCalls)
            }
            return JSONObject()
                .put("stream", true)
                .put("finish_reason", finishReason ?: JSONObject.NULL)
                .put("model", roundResponseModel ?: JSONObject.NULL) to message
        }
        try {
            while (true) {
                ensureWithinBudget()
                val (responseJson, message) = requestMessage("auto")
                JsonProtocol.nonBlank(responseJson, "model")?.let { responseModel = it }
                val finishReason = JsonProtocol.nonBlank(responseJson, "finish_reason")
                val calls = message.optJSONArray("tool_calls")
                // A complete SSE [DONE] frame may omit finish_reason even after a
                // fully formed tool call. The per-tool id/name/JSON checks below
                // still reject incomplete or malformed tool execution requests.
                // Missing finish_reason does not authorize an incomplete tool.
                // A closed SSE stream may legitimately omit it, but every tool
                // must have an ID, function name, and complete JSON arguments.
                val completeUnconfirmedCalls = finishReason == null && calls != null &&
                    (0 until calls.length()).all { index ->
                        val candidate = calls.optJSONObject(index) ?: return@all false
                        val function = candidate.optJSONObject("function") ?: return@all false
                        val arguments = JsonProtocol.text(function, "arguments")
                        !JsonProtocol.nonBlank(candidate, "id").isNullOrBlank() &&
                            !JsonProtocol.nonBlank(function, "name").isNullOrBlank() &&
                            arguments != null && runCatching { JSONObject(arguments) }.isSuccess
                    }
                if (calls == null || calls.length() == 0 ||
                    (finishReason == null && !completeUnconfirmedCalls) ||
                    (finishReason != null && finishReason !in setOf("tool_calls", "stop"))) {
                    val tail = JSONArray()
                    for (i in inputCount until history.length()) tail.put(JsonProtocol.copy(history.get(i)))
                    val replayMessage = JsonProtocol.objectCopy(message)
                    if (finishReason != "stop") {
                        replayMessage.remove("tool_calls"); replayMessage.remove("reasoning_details"); replayMessage.remove("reasoning")
                    }
                    tail.put(replayMessage)
                    return OpenRouterCompletionResult(
                        text = (accumulatedText + JsonProtocol.string(message, "content"))
                            .filter { it.isNotBlank() }.joinToString("\n\n").trim(),
                        usage = snapshotAccumulatedUsage(),
                        responseModel = responseModel,
                        assistantMessage = JSONObject(message.toString()),
                        toolNames = toolNames.distinct(),
                        finishReason = finishReason,
                        protocol = JSONObject().put("version", 1).put("input_sha256", sourceInputHash)
                            .put("wire_input_sha256", inputHash)
                            .put("binding_sha256", binding).put("tail", tail)
                            .put("tool_states", JsonProtocol.objectCopy(toolStates)).apply {
                                if (finishReason != "stop") put("interrupted_assistant", JsonProtocol.objectCopy(message))
                            },
                        fileIoTelemetry = currentFileIoJson(), roundsCompleted = roundsCompleted
                    )
                }
                val validatedCalls = mutableListOf<Triple<String, String, JSONObject>>()
                for (i in 0 until calls.length()) {
                    val callObject = calls.optJSONObject(i) ?: continue
                    val function = callObject.optJSONObject("function")
                        ?: throw IOException("Malformed tool call")
                    val name = function.optString("name").trim()
                    val id = callObject.optString("id").trim()
                    if (name.isBlank() || id.isBlank()) throw IOException("Malformed tool call id/name")
                    if (name.startsWith("openrouter:")) {
                        throw IOException("Unexpected OpenRouter server-side tool call: $name")
                    }
                    if (allowedFunctionTools.isEmpty() || name !in allowedFunctionTools) {
                        throw IOException("Unexpected tool call outside allowed catalog: $name")
                    }
                    val arguments = try {
                        JSONObject(JsonProtocol.string(function, "arguments", "{}"))
                    } catch (e: Exception) {
                        throw IOException("Invalid tool arguments for $name", e)
                    }
                    validatedCalls.add(Triple(id, name, arguments))
                }
                JsonProtocol.string(message, "content").trim().takeIf { it.isNotBlank() }?.let { accumulatedText += it }
                for ((id, name, arguments) in validatedCalls) {
                    if (toolStates.has(id)) throw IOException("Reused provider tool call id: $id")
                    toolStates.put(id, JSONObject().put("state", "not_started").put("tool_name", name)
                        .put("arguments_sha256", StreamingJsonRequestBody.sha256(arguments)))
                }
                history.put(JSONObject(message.toString()))
                emitProtocolCheckpoint()
                for ((id, name, arguments) in validatedCalls) {
                    ensureWithinBudget()
                    toolNames += name
                    ensureActive()
                    toolStates.getJSONObject(id).put("state", "in_flight")
                    // A failed durable acknowledgement aborts before the side effect.
                    emitProtocolCheckpoint()
                    ensureActive()
                    val toolResult = try {
                        executeToolWithId?.invoke(id, name, arguments) ?: executeTool(name, arguments)
                    } catch (failure: Exception) {
                        toolStates.getJSONObject(id).put("state", "outcome_unknown")
                        try { emitProtocolCheckpoint() } catch (saveFailure: Exception) { failure.addSuppressed(saveFailure) }
                        throw failure
                    }
                    history.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", id)
                            .put("content", toolResult)
                    )
                    toolStates.getJSONObject(id).put("state", "completed")
                    // Persist exact output and completed state together before another POST.
                    emitProtocolCheckpoint()
                    ensureWithinBudget()
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val partialUsage = snapshotAccumulatedUsage()
            val ioJson = currentFileIoJson()
            if (e is OpenRouterCompletionException) {
                throw OpenRouterCompletionException(
                    message = e.message ?: e.javaClass.simpleName,
                    partialText = e.partialText, cause = e.cause ?: e,
                    accumulatedUsage = partialUsage ?: e.accumulatedUsage,
                    fileIoTelemetry = ioJson ?: e.fileIoTelemetry,
                    roundsCompleted = maxOf(roundsCompleted, e.roundsCompleted)
                )
            }
            val partialText = accumulatedText.joinToString("\n\n").trim()
            // File I/O alone must not change the typed upstream-error contract.
            if (partialText.isNotBlank() || partialUsage != null) {
                throw OpenRouterCompletionException(
                    message = e.message ?: e.javaClass.simpleName,
                    partialText = partialText, cause = e,
                    accumulatedUsage = partialUsage, fileIoTelemetry = ioJson,
                    roundsCompleted = roundsCompleted
                )
            }
            throw e
        }
    }

    fun close() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        httpClient.cache?.close()
    }
}
