package com.example.openaichat

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import okhttp3.Call
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class OpenRouterRuntimeRequest(
    val chatId: String, val requestId: String, val model: String, val messages: JSONArray,
    val attachments: List<OpenRouterLocalAttachment>, val effort: String, val verbosity: String,
    val maxTokens: Int?, val github: Boolean, val githubRepo: String, val githubBranches: Set<String>,
    val webSearch: Boolean, val webFetch: Boolean, val parallel: Boolean, val cacheMode: String,
    val background: Boolean, val startedAt: Long, val taskClass: String, val effortPref: String,
    val autoSelected: Boolean, val promptChars: Int,
    val apiKey: String, val metadataClient: OkHttpClient, val pdfEngine: String,
    val responseFormat: JSONObject? = null,
    val claudeRoute: OpenRouterClaudeRoute = OpenRouterClaudeRoute.AUTO
) {
    override fun toString() = "OpenRouterRuntimeRequest(chatId=$chatId, requestId=$requestId, model=$model, background=$background)"
}

/** Application-owned requests. Lifecycle visibility is not request ownership. */
object OpenRouterResponseRuntime {
    private class Work(val context: Context, val request: OpenRouterRuntimeRequest, val client: OpenRouterChatClient) {
        val launched = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val runtimeAttachments = java.util.concurrent.CopyOnWriteArrayList<OpenRouterLocalAttachment>()
        @Volatile var call: Call? = null
        @Volatile var cancelReason = "Соединение прервано"
    }
    private val work = ConcurrentHashMap<String, Work>()
    private val executor = Executors.newCachedThreadPool()
    private val artifactRecoveryRunning = AtomicBoolean(false)
    private val artifactCleanupRunning = AtomicBoolean(false)
    fun cleanupLocalArtifacts(context: Context) {
        if (!artifactCleanupRunning.compareAndSet(false, true)) return
        val app = context.applicationContext
        executor.execute {
            try {
                ChatDatabase(app).use { db -> LocalArtifactCleanup(app, db).run() }
            } catch (error: Exception) {
                AstraDiagnostics(app).log("local_artifact_cleanup_deferred", mapOf("error_type" to error.javaClass.simpleName))
            } finally { artifactCleanupRunning.set(false) }
        }
    }
    /** Startup recovery only inspects published bytes. It never resumes inference or tool execution. */
    fun recoverLocalArtifacts(context: Context) {
        if (!artifactRecoveryRunning.compareAndSet(false, true)) return
        val app = context.applicationContext
        executor.execute {
            try {
                ChatDatabase(app).use { db ->
                    val artifactTools = LocalArtifactToolExecutor(app)
                    db.listLocalArtifactIntents().forEach { record ->
                        if (record.getBoolean("tombstoned") || contains(record.getString("request_id"))) return@forEach
                        try {
                            LocalArtifactIntentCoordinator(db, artifactTools, record.getString("chat_id"),
                                record.getString("request_id"), { false }).recoverStored(record) {
                                OpenRouterUiBridge.historyReconciled(record.getString("chat_id"), record.getString("request_id"))
                            }
                        } catch (error: Exception) {
                            AstraDiagnostics(app).log("local_artifact_recovery_unknown", mapOf(
                                "request_id" to record.getString("request_id"), "error_type" to error.javaClass.simpleName))
                        }
                    }
                }
            } catch (error: Exception) {
                AstraDiagnostics(app).log("local_artifact_recovery_failed", mapOf("error_type" to error.javaClass.simpleName))
            } finally { artifactRecoveryRunning.set(false) }
        }
    }
    fun contains(requestId: String) = work.containsKey(requestId)
    fun uses(client: OpenRouterChatClient) = work.values.any { it.client === client }
    fun count() = work.size
    fun localFilePaths(): Set<String> = work.values.flatMap { w ->
        (w.request.attachments + w.runtimeAttachments).flatMap { listOfNotNull(it.path, it.textPath, it.wirePath) }
    }.toSet()
    fun chatFor(requestId: String) = work[requestId]?.request?.chatId
    fun cancelForChat(chatId: String, reason: String = "Остановлено пользователем") {
        if (chatId.isBlank()) return
        work.values.filter { it.request.chatId == chatId }.forEach { cancel(it.request.requestId, reason) }
    }
    fun start(context: Context, request: OpenRouterRuntimeRequest, client: OpenRouterChatClient) {
        val item = Work(context.applicationContext, request, client)
        synchronized(LocalArtifactOwnership.lock) {
            check(work.putIfAbsent(request.requestId, item) == null) { "Request is already running" }
        }
        try {
            if (request.background) ContextCompat.startForegroundService(item.context,
                Intent(item.context, OpenRouterResponseService::class.java).putExtra(OpenRouterResponseService.EXTRA_REQUEST_ID, request.requestId))
            else launch(request.requestId)
        } catch (e: Exception) {
            if (!OpenRouterUiBridge.viewing(request.chatId)) {
                item.cancelReason = "Не удалось запустить фоновое выполнение: ${e.javaClass.simpleName}"
                item.cancelled.set(true)
            }
            launch(request.requestId)
        }
    }
    fun launch(requestId: String) {
        val item = work[requestId] ?: return
        if (item.launched.compareAndSet(false, true)) executor.execute { run(item) }
    }
    fun cancel(requestId: String, reason: String = "Соединение прервано") {
        work[requestId]?.let { it.cancelReason = reason; it.cancelled.set(true); it.call?.cancel() }
    }
    fun cancelAll(reason: String) { work.keys.toList().forEach { cancel(it, reason) } }
    fun cancelBackground(reason: String) { work.values.filter { it.request.background }.forEach { cancel(it.request.requestId, reason) } }
    private fun annotations(artifacts: List<JSONObject>) = JSONArray().apply {
        artifacts.forEach { a -> put(JSONObject().put("type", "local_file").put("state", "READY")
            .put("filename", JsonProtocol.string(a, "filename")).put("local_path", JsonProtocol.string(a, "path"))
            .put("mime_type", a.optString("mime_type")).put("size_bytes", a.optLong("size_bytes")).apply {
                listOf("sha256", "patch_diff", "patch_diff_truncated", "diff_path", "patch_diff_path", "source_path", "source_file_id", "source_sha256").forEach { key ->
                    a.opt(key)?.let { put(key, JsonProtocol.copy(it)) }
                }
            }) }
    }
    private fun run(item: Work) {
        val r = item.request
        val db = ChatDatabase(item.context)
        val diagnostics = AstraDiagnostics(item.context)
        val secure = SecureStore(item.context)
        val artifacts = mutableListOf<JSONObject>()
        val trace = JSONArray()
        var partial = ""
        var protocolNotice: String? = null
        var lastCheckpoint: Long? = null
        var textCheckpointSequence = 0L
        var fatalFailure = false
        var github: GitHubToolExecutor? = null
        var latestFileIoJson: JSONObject? = null
        fun active() = !item.cancelled.get() && !Thread.currentThread().isInterrupted && db.isActiveRequest(r.chatId, r.requestId)
        fun terminal(message: JSONObject, status: String, usage: JSONObject?, responseModel: String?, error: String? = null,
            fileIo: JSONObject? = null, roundsCompleted: Int = 0): Boolean {
            if (artifacts.isNotEmpty()) message.put("annotations", annotations(artifacts))
            if (trace.length() > 0) message.put("tool_trace", trace)
            val totals = OpenRouterUsageSnapshot.from(usage)
            val executedTools = (0 until trace.length()).mapNotNull { trace.optJSONObject(it)?.let { entry -> JsonProtocol.nonBlank(entry, "name") } }.distinct()
            val toolTelemetry = if (roundsCompleted > 0 || trace.length() > 0) JSONObject()
                .put("rounds_completed", roundsCompleted).put("calls_executed", trace.length())
                .put("executed_tools", JSONArray(executedTools)) else null
            val compactTelemetry = FileBackedIoTelemetry.mergeCompactTelemetry(toolTelemetry = toolTelemetry,
                fileIoJson = fileIo ?: latestFileIoJson, costDetails = totals.costDetails)
            compactTelemetry?.let { message.put("telemetry_summary", it) }
            val committed = db.finalizeCurrentChatIfActiveRequest(r.chatId, "", null, null, listOf(message), r.requestId)
            if (!committed) return false
            db.recordRequestAccounting(chatId = r.chatId, localRequestId = r.requestId, status = status, usage = usage, error = error,
                reportedCostUsd = totals.reportedCostUsd, telemetrySummary = compactTelemetry)
            db.recordRequestEvent(chatId = r.chatId, eventType = "request_$status", requestId = r.requestId,
                provider = "openrouter", requestedModel = r.model, responseModel = responseModel,
                modelMatch = ModelCatalog.match(r.model, responseModel), status = status,
                inputTokens = totals.inputTokens, cachedTokens = totals.cachedTokens, cacheWriteTokens = totals.cacheWriteTokens,
                outputTokens = totals.outputTokens, reasoningTokens = totals.reasoningTokens,
                estimatedCostUsd = totals.costUsd, reportedCostUsd = totals.reportedCostUsd,
                latencyMs = SystemClock.elapsedRealtime() - r.startedAt,
                toolSummary = executedTools.joinToString(","), error = error, telemetrySummary = compactTelemetry)
            ResponseNotifications.finished(item.context, r.chatId, r.requestId, status == "completed")
            OpenRouterUiBridge.changed(r.chatId, r.requestId)
            return true
        }
        fun base(text: String) = JSONObject().put("role", "assistant").put("provider", "openrouter")
            .put("requested_model", r.model).put("request_id", r.requestId).put("text", text)
        val runtimeLease = PreparedAttachmentFiles.lease(item.context)
        val runtimeLeaseScope = runtimeLease.capture()
        try {
            if (!active()) throw java.io.IOException(item.cancelReason)
            if (OpenRouterClaudeRoute.isClaude(r.model)) diagnostics.log("openrouter_route", mapOf(
                "request_id" to r.requestId, "model" to r.model, "route" to r.claudeRoute.preference,
                "image_base64_limit" to r.claudeRoute.imageBase64Limit, "request_byte_limit" to r.claudeRoute.requestByteLimit))
            val verifiedAttachments = r.attachments.map { attachment ->
                if (!active()) throw java.io.IOException("Подготовка файлов остановлена")
                if (attachment.kind in setOf("text", "document", "table"))
                    LocalArtifactAttachmentResolver.resolveForTools(item.context, attachment) else attachment
            }
            verifiedAttachments.filter { it !in item.runtimeAttachments }.forEach(item.runtimeAttachments::add)
            val fileTools = LocalFileTools(verifiedAttachments, ::active)
            val existingCanonicalPaths = verifiedAttachments.mapNotNull { runCatching { File(it.path).canonicalPath }.getOrNull() }.toMutableSet()
            db.listLocalFiles(r.chatId).forEach { a ->
                if (!active()) throw java.io.IOException("Подготовка файлов остановлена")
                val rawPath = JsonProtocol.nonBlank(a, "path") ?: return@forEach
                val file = File(rawPath)
                if (!file.isFile) return@forEach
                val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return@forEach
                if (!existingCanonicalPaths.add(canonical)) return@forEach
                try {
                    val classified = LocalArtifactAttachmentResolver.resolve(item.context, db, r.chatId, a)
                    item.runtimeAttachments.add(classified)
                    fileTools.register(classified)
                } catch (error: Exception) {
                    if (!active() || error is java.util.concurrent.CancellationException ||
                        a.has("attachment_manifest") || a.has("attachment")) throw error
                }
            }
            val artifactTools = LocalArtifactToolExecutor(item.context)
            val artifactIntents = LocalArtifactIntentCoordinator(db, artifactTools, r.chatId, r.requestId, ::active)
            if (r.github) {
                github = GitHubToolExecutor(secure, { r.githubRepo }, { r.githubBranches },
                    { name, args -> OpenRouterUiBridge.confirm(r.chatId, r.requestId, name, args) },
                    { name, args -> diagnostics.log("github_write", mapOf("tool" to name, "target" to ToolTraceSummary.entry(name, args, "{}").optJSONObject("target"))) },
                    ::active, File(item.context.cacheDir, "github_text_pages"))
            }
            val tools = OpenRouterAppToolCatalog.tools()
            if (r.github) GitHubToolCatalog.tools().let { array -> for (i in 0 until array.length()) tools.put(array.get(i)) }
            OpenRouterServerToolCatalog.tools(r.webSearch, r.webFetch).let { array -> for (i in 0 until array.length()) tools.put(array.get(i)) }
            val result = item.client.complete(apiKey = r.apiKey, model = r.model,
                messages = r.messages, sessionId = r.chatId, tools = tools, maxTokens = r.maxTokens,
                reasoningEffort = r.effort, verbosity = r.verbosity, parallelToolCalls = r.parallel, cacheMode = r.cacheMode,
                pdfEngine = r.pdfEngine.takeIf { r.attachments.any { a -> a.kind == "pdf" } },
                responseFormat = r.responseFormat, claudeRoute = r.claudeRoute,
                shouldContinue = ::active,
                executeTool = { _, _ -> throw LocalArtifactOutcomeUnknownException("Отсутствует provider tool_call_id") },
                executeToolWithId = { callId, name, args ->
                    val response = try { when {
                        name == "create_local_text_file" || name == "patch_local_file" -> {
                            val registered = artifactIntents.execute(callId, name, args,
                                r.attachments + item.runtimeAttachments)
                            if (!active()) throw LocalArtifactOutcomeUnknownException("Запрос остановлен после сохранения файла")
                            artifacts.add(registered)
                            val f = OpenRouterLocalAttachment.fromJson(registered.getJSONObject("attachment"))
                            item.runtimeAttachments.add(f)
                            fileTools.register(f)
                            LocalArtifactToolExecutor.toolResult(registered).toString()
                        }
                        name in LocalFileTools.names -> fileTools.execute(name, args)
                        r.github && github != null -> github!!.execute(name, args)
                        else -> JSONObject().put("ok", false).put("error", "Инструмент отключён").toString()
                    } } catch (e: LocalArtifactOutcomeUnknownException) {
                        throw e
                    } catch (e: GitHubToolOutcomeUnknownException) {
                        throw e
                    } catch (e: java.io.IOException) {
                        if (!active()) throw e
                        JSONObject().put("ok", false).put("error", e.message).toString()
                    }
                    trace.put(ToolTraceSummary.entry(name, args, response)); response
                },
                onCallCreated = { call -> item.call = call; if (!active()) call.cancel() },
                onRoundObserved = { round, id, usage -> db.observeOpenRouterRound(r.chatId, r.requestId, round, id, usage) },
                onTextDelta = { text ->
                    partial = text
                    // OpenRouter has no server SSE sequence. This cursor belongs to
                    // the single runtime writer, never to an OpenAI response stream.
                    val sequence = textCheckpointSequence++
                    val now = SystemClock.elapsedRealtime()
                    val previousCheckpoint = lastCheckpoint
                    if ((previousCheckpoint == null || now - previousCheckpoint >= 500L) && active()) {
                        if (db.checkpointOpenRouterResponse(r.chatId, r.requestId, text, sequence)) {
                            lastCheckpoint = now
                            OpenRouterUiBridge.changed(r.chatId, r.requestId)
                        }
                    }
                },
                onPayloadObserved = { round, size -> diagnostics.log("openrouter_http_payload", mapOf("chat_id" to r.chatId, "request_id" to r.requestId, "round" to round, "model" to r.model, "json_bytes" to size)) },
                onFileIoObserved = { round, stats ->
                    latestFileIoJson = JsonProtocol.objectCopy(stats)
                    diagnostics.log("openrouter_file_io", mapOf("chat_id" to r.chatId,
                        "request_id" to r.requestId, "round" to round, "files" to stats.optInt("files"),
                        "source_read_passes" to stats.optLong("source_read_passes"), "source_read_bytes" to stats.optLong("source_read_bytes"),
                        "file_work_ms" to stats.optLong("file_work_ms"), "round_read_passes" to stats.optLong("round_read_passes"),
                        "round_read_bytes" to stats.optLong("round_read_bytes"), "round_work_ms" to stats.optLong("round_work_ms")))
                },
                onProtocolCheckpoint = { checkpoint ->
                    if (!db.saveOpenRouterCheckpoint(r.chatId, r.requestId, checkpoint))
                        throw LocalArtifactOutcomeUnknownException("Не подтверждено сохранение состояния вызова инструмента")
                },
                onProtocolWarning = { warning ->
                    protocolNotice = warning
                    diagnostics.log("openrouter_protocol_reset", mapOf("request_id" to r.requestId, "reason" to warning))
                    OpenRouterUiBridge.warning(r.chatId, r.requestId, warning)
                })
            val completed = OpenRouterFinishPolicy.completed(result.finishReason)
            val status = if (completed) "completed" else "incomplete"
            val explanation = if (completed) "" else OpenRouterFinishPolicy.description(result.finishReason)
            val message = base(result.text).put("status", status).put("finish_reason", result.finishReason ?: JSONObject.NULL)
                .put("response_model", result.responseModel ?: JSONObject.NULL)
                .put("tools", JSONArray(result.toolNames))
            if (explanation.isNotBlank()) message.put("error", explanation) // UI metadata, never model content.
            result.protocol?.let { protocol -> protocolNotice?.let { protocol.put("context_notice", it) }; message.put("openrouter_protocol", protocol) }
            result.usage?.let { message.put("usage", it) }
            if (terminal(message, status, result.usage, result.responseModel, explanation.takeIf { it.isNotBlank() },
                    result.fileIoTelemetry, result.roundsCompleted)) {
                OpenRouterCompletionTelemetry.record(result.usage, { totals ->
                    db.recordEffortMetric(r.requestId, r.chatId, r.taskClass, r.effortPref, r.effort, r.autoSelected,
                        r.promptChars, r.attachments.size, SystemClock.elapsedRealtime() - r.startedAt,
                        totals.inputTokens, totals.cachedTokens, totals.cacheWriteTokens, totals.outputTokens,
                        totals.reasoningTokens, completed, result.finishReason.takeUnless { completed }, totals.reportedCostUsd,
                        costKind = totals.costKind)
                }, { })
            }
        } catch (e: Exception) {
            val upstream = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<OpenRouterUpstreamException>().firstOrNull()
            diagnostics.log("openrouter_failed", mapOf("chat_id" to r.chatId, "request_id" to r.requestId,
                "stage" to upstream?.stage, "round" to upstream?.roundIndex, "http_status" to upstream?.httpStatus,
                "error_type" to (upstream?.errorType ?: e.javaClass.simpleName), "provider_name" to upstream?.providerName,
                "provider_code" to upstream?.providerCode, "error_message" to (upstream?.message ?: e.message)?.take(600),
                "safe_metadata" to upstream?.safeMetadata))
            val completionError = e as? OpenRouterCompletionException
            val saved = completionError?.partialText?.takeIf { it.isNotBlank() } ?: partial
            val failedUsage = completionError?.accumulatedUsage
            val error = if (item.cancelled.get()) item.cancelReason else (e.message ?: e.javaClass.simpleName).take(800)
            val status = if (item.cancelled.get()) "disconnected" else "failed"
            val failed = base(saved)
                .put("status", status).put("error", error)
            failedUsage?.let { failed.put("usage", JSONObject(it.toString())) }
            db.getOpenRouterCheckpoint(r.chatId, r.requestId)?.let { failed.put("openrouter_protocol", OpenRouterProtocolCodec.withVisibleTail(it, failed)) }
            terminal(failed, status, failedUsage, null, error, completionError?.fileIoTelemetry,
                completionError?.roundsCompleted ?: 0)
        } catch (fatal: Error) {
            fatalFailure = true
            item.call?.cancel()
            // Best effort only: OOM may also prevent SQLite allocating. Never swallow VM errors.
            // Avoid copying tool/protocol arrays or formatting the throwable in this path.
            try {
                db.finalizeCurrentChatIfActiveRequest(r.chatId, "", null, null,
                    listOf(base(partial).put("status", "failed").put("error", fatal.javaClass.simpleName)), r.requestId)
            } catch (_: Throwable) {
                try { db.claimActiveRequestFailure(r.chatId, r.requestId) } catch (_: Throwable) { }
            }
            try { OpenRouterUiBridge.changed(r.chatId, r.requestId) } catch (_: Throwable) { }
            throw fatal
        } finally {
            try { runtimeLeaseScope.close() } catch (_: Exception) {}
            item.call = null
            try { github?.close() } catch (_: Exception) {}
            try {
                db.sealOpenRouterRounds(r.chatId, r.requestId)
                if (!fatalFailure && item.client.reconcileGenerations) {
                    var anyReconciled = false
                    OpenRouterGenerationReconciler(r.metadataClient, item.client.metadataBaseUrl)
                        .reconcile(db.listOpenRouterRounds(r.chatId, r.requestId), r.apiKey) { id, metadata ->
                            if (db.reconcileOpenRouterGeneration(r.chatId, r.requestId, id, metadata)) {
                                anyReconciled = true
                            }
                        }
                    if (anyReconciled) OpenRouterUiBridge.changed(r.chatId, r.requestId)
                }
            } catch (_: Exception) { /* Unknown cost stays unknown. */ }
            work.remove(r.requestId, item)
            if (!fatalFailure) try {
                val owners = PreparedAttachmentFiles.Owners(
                    db.listAllLocalFilePaths().toSet() +
                        LocalAttachmentReferences.allComposerPaths(item.context.getSharedPreferences("app", Context.MODE_PRIVATE).all),
                    localFilePaths() + db.listLegacyCheckpointAttachmentPaths()
                )
                runtimeLease.release(owners)
                PreparedAttachmentFiles.cleanupPending(item.context) { owners }
            } catch (_: Exception) { runtimeLease.defer() }
            else runtimeLease.defer()
            try {
                if (!uses(item.client) && !OpenRouterUiBridge.ownsClient(item.client)) item.client.close()
                if (work.values.none { it.request.background }) item.context.stopService(Intent(item.context, OpenRouterResponseService::class.java))
            } finally {
                db.close()
                if (!fatalFailure) cleanupLocalArtifacts(item.context)
            }
        }
    }
}
