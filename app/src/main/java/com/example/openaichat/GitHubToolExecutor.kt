package com.example.openaichat

import android.net.Uri
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A mutation may have reached GitHub; it must not become an ordinary failed tool result. */
class GitHubToolOutcomeUnknownException(message: String, cause: Throwable? = null) : IOException(message, cause)

class GitHubToolExecutor(
    private val secureStore: SecureStore,
    private val allowedRepository: () -> String,
    private val allowedBranches: () -> Set<String>,
    private val confirmWrite: (toolName: String, arguments: JSONObject) -> Boolean,
    private val auditWrite: (toolName: String, arguments: JSONObject) -> Unit,
    private val canExecute: () -> Boolean = { true },
    private val cacheDirectory: java.io.File? = null
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    fun close() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        httpClient.cache?.close()
    }

    fun execute(name: String, arguments: JSONObject): String = try {
        if (!canExecute()) throw IOException("GitHub request owner is no longer current")
        when (name) {
            "get_pull_request" -> getPullRequest(arguments)
            "list_pull_requests" -> listPullRequests(arguments)
            "list_issues" -> listIssues(arguments)
            "get_workflow_runs" -> getWorkflowRuns(arguments)
            "get_workflow_run_jobs" -> getWorkflowRunJobs(arguments)
            "get_file" -> getFile(arguments)
            "get_file_range" -> getFileRange(arguments)
            "list_directory" -> listDirectory(arguments)
            "create_issue_comment" -> createIssueComment(arguments)
            "submit_pull_request_review" -> submitPullRequestReview(arguments)
            "merge_pull_request" -> mergePullRequest(arguments)
            "create_or_update_file" -> createOrUpdateFile(arguments)
            "trigger_workflow_dispatch" -> triggerWorkflowDispatch(arguments)
            else -> errorResult("Unknown GitHub tool: $name")
        }
    } catch (e: GitHubToolOutcomeUnknownException) {
        throw e
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        if (!canExecute()) throw IOException("GitHub request owner is no longer current", e)
        errorResult("GitHub request interrupted")
    } catch (e: Exception) {
        if (!canExecute() && e is IOException) throw e
        errorResult(e.message ?: e.javaClass.simpleName)
    }

    private fun token(): String =
        secureStore.getGitHubToken().takeIf { it.isNotBlank() }
            ?: throw IOException("GitHub PAT is not configured")

    private fun repo(a: JSONObject): Pair<String, String> {
        val owner = a.optString("owner").trim()
        val name = a.optString("repo").trim()
        if (owner.isBlank() || name.isBlank()) throw IOException("owner/repo are required")
        if ("$owner/$name" != allowedRepository()) throw IOException("Repository is outside the configured allow-list")
        return owner to name
    }

    private fun branch(value: String) {
        if (value.isBlank() || value !in allowedBranches()) {
            throw IOException("Branch '$value' is outside the configured allow-list")
        }
    }

    private fun validateRelativePath(rawPath: String, allowEmpty: Boolean = false): String {
        val normalized = rawPath.trim().removePrefix("/")
        if (!allowEmpty && normalized.isBlank()) throw IOException("Invalid path")
        if (normalized.contains('\\') || normalized.split('/').any { it == ".." || it == "." }) {
            throw IOException("Invalid path")
        }
        return normalized
    }

    private fun url(owner: String, repo: String, path: String) =
        "https://api.github.com/repos/$owner/$repo/$path"

    private fun request(url: String): Request.Builder = Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer ${token()}")
        .addHeader("Accept", "application/vnd.github+json")
        .addHeader("X-GitHub-Api-Version", "2022-11-28")

    private fun call(request: Request): Pair<Int, String> {
        val mutation = request.method !in setOf("GET", "HEAD")
        // A lost mutation response is not permission to send the mutation again.
        val transport = if (mutation) httpClient.newBuilder().retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).build() else httpClient
        val outbound = if (mutation && request.body != null) {
            val delegate = request.body!!
            request.newBuilder().method(request.method, object : RequestBody() {
                override fun contentType() = delegate.contentType()
                override fun contentLength() = delegate.contentLength()
                override fun isDuplex() = delegate.isDuplex()
                // OkHttp can follow 503 Retry-After:0 even with connection retries disabled.
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
            }).build()
        } else request
        var attempt = 0
        var code = 0
        var body = ""
        while (attempt < 3) {
            if (!canExecute()) throw IOException("GitHub request owner is no longer current")
            var observedCode: Int? = null
            try { transport.newCall(outbound).execute().use { httpResponse ->
                code = httpResponse.code
                observedCode = code
                if (mutation && code >= 500) throw GitHubToolOutcomeUnknownException(
                    "GitHub вернул HTTP $code после отправки записи; результат операции неизвестен")
                body = if (httpResponse.isSuccessful) {
                    BoundedResponseDecoder.readBody(httpResponse.body)
                } else {
                    BoundedResponseDecoder.readErrorBody(httpResponse.body)
                }
                if (mutation || (code != 403 && code != 429)) return code to body
                val retryMs = httpResponse.header("Retry-After")?.toLongOrNull()
                    ?.coerceIn(0L, 10L)?.times(1000L) ?: 750L * (attempt + 1)
                try {
                    Thread.sleep(retryMs)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("GitHub retry interrupted", interrupted)
                }
            } } catch (error: GitHubToolOutcomeUnknownException) { throw error }
            catch (error: IOException) {
                if (mutation && (observedCode == null || observedCode in 200..299))
                    throw GitHubToolOutcomeUnknownException(
                        "Ответ GitHub на запись не подтверждён; автоматическое продолжение запрещено", error)
                throw error
            }
            attempt++
        }
        return code to body
    }

    private fun ok(result: Pair<Int, String>): String {
        if (result.first !in 200..299) {
            throw IOException("GitHub HTTP ${result.first}:${result.second.take(1600)}")
        }
        return result.second
    }

    private fun getPullRequest(a: JSONObject): String {
        val (owner, repo) = repo(a)
        return json(ok(call(request(url(owner, repo, "pulls/${a.getInt("pull_number")}")).get().build())))
    }

    private fun listPullRequests(a: JSONObject): String {
        val (owner, repo) = repo(a)
        val state = a.getString("state")
        val path = "pulls?state=${Uri.encode(state)}&per_page=20"
        return json(ok(call(request(url(owner, repo, path)).get().build())))
    }

    private fun listIssues(a: JSONObject): String {
        val (owner, repo) = repo(a)
        val state = a.getString("state")
        val path = "issues?state=${Uri.encode(state)}&per_page=50"
        return json(ok(call(request(url(owner, repo, path)).get().build())))
    }

    private fun getWorkflowRuns(a: JSONObject): String {
        val (owner, repo) = repo(a)
        val branchName = a.getString("branch")
        branch(branchName)
        val path = "actions/runs?branch=${Uri.encode(branchName)}&per_page=20"
        return json(ok(call(request(url(owner, repo, path)).get().build())))
    }

    private fun getWorkflowRunJobs(a: JSONObject): String {
        val (owner, repo) = repo(a)
        val path = "actions/runs/${a.getLong("run_id")}/jobs?per_page=100"
        return json(ok(call(request(url(owner, repo, path)).get().build())))
    }

    private fun getFile(a: JSONObject): String {
        val (owner, repo) = repo(a)
        val path = validateRelativePath(a.getString("path"))
        val ref = GitHubAllowedRefPolicy.validate(a.optString("ref"), allowedBranches())
        val suffix = "?ref=${Uri.encode(ref)}"
        val raw = ok(call(request(url(owner, repo, "contents/${Uri.encode(path, "/")}$suffix")).get().build()))
        return GitHubFileContentPolicy.decode(path, JSONObject(raw)).toString()
    }

    private fun getFileRange(a: JSONObject): String {
        if (a.optLong("offset", 0L) > 0 && JsonProtocol.nonBlank(a, "etag") == null) {
            throw IOException("Later pages require the first page's ETag; restart reading from offset 0")
        }
        val (owner, repo) = repo(a)
        val path = a.getString("path").trim()
        if (path.isBlank() || path.startsWith("/") || path.contains('\\') || path.split('/').any { it == ".." || it == "." }) {
            throw IOException("Invalid path")
        }
        val ref = GitHubAllowedRefPolicy.validate(a.optString("ref"), allowedBranches())
        val endpoint = url(owner, repo, "contents/${Uri.encode(path, "/")}?ref=${Uri.encode(ref)}")
        if (!canExecute()) throw IOException("GitHub request owner is no longer current")
        cacheDirectory?.let { directory ->
            val cache = GitHubTextPageCache(directory)
            val key = StreamingJsonRequestBody.sha256(JSONObject().put("repository", "$owner/$repo")
                .put("path", path).put("ref", ref).put("credential", StreamingJsonRequestBody.sha256(token())))
            return cache.read(key, a.optLong("offset", 0L), a.optInt("limit", 24000), JsonProtocol.nonBlank(a, "etag"), canExecute) { tag, save ->
                val builder = request(endpoint).header("Accept", "application/vnd.github.raw+json")
                if (tag != null) builder.header("If-None-Match", tag)
                httpClient.newCall(builder.get().build()).execute().use { response ->
                    if (!canExecute()) throw IOException("GitHub request owner is no longer current")
                    if (response.code == 304) return@read tag ?: throw IOException("Unexpected HTTP 304")
                    if (!response.isSuccessful) throw IOException("GitHub HTTP ${response.code}")
                    val actual = response.header("ETag") ?: throw IOException("GitHub returned no ETag")
                    val body = response.body ?: throw IOException("Empty GitHub response")
                    if (body.contentLength() > 100L * 1024 * 1024) throw IOException("GitHub Contents file exceeds 100 MiB")
                    val bounded = object : java.io.FilterInputStream(body.byteStream()) {
                        var received = 0L
                        fun counted(n: Int): Int {
                            if (n > 0) received += n
                            if (received > 100L * 1024 * 1024) throw IOException("GitHub Contents file exceeds 100 MiB")
                            return n
                        }
                        override fun read(): Int = `in`.read().also { counted(if (it < 0) 0 else 1) }
                        override fun read(bytes: ByteArray, off: Int, len: Int): Int = counted(`in`.read(bytes, off, len))
                    }
                    java.io.InputStreamReader(bounded, cache.decoder()).use { save(it, actual) }
                    actual
                }
            }.put("ok", true).put("path", path).put("ref", ref).toString()
        }
        val req = request(endpoint).header("Accept", "application/vnd.github.raw+json").get().build()
        return httpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub HTTP ${response.code}")
            val expectedTag = JsonProtocol.nonBlank(a, "etag")
            val actualTag = response.header("ETag") ?: throw IOException("GitHub returned no ETag; consistent range reading is unavailable")
            if (expectedTag != null && expectedTag != actualTag) throw IOException("Repository file changed between pages; restart reading from offset 0")
            val source = response.body?.byteStream() ?: throw IOException("Empty GitHub response")
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            java.io.InputStreamReader(source, decoder).use { reader ->
                BoundedTextRead.range(reader, a.optLong("offset", 0L), a.optInt("limit", 24000), canExecute)
                    .put("ok", true).put("path", path).put("ref", ref)
                    .put("etag", actualTag ?: JSONObject.NULL).toString()
            }
        }
    }

    private fun listDirectory(a: JSONObject): String {
        val (owner, repo) = repo(a)
        val path = validateRelativePath(a.getString("path"), allowEmpty = true)
        val ref = GitHubAllowedRefPolicy.validate(a.optString("ref"), allowedBranches())
        val suffix = "?ref=${Uri.encode(ref)}"
        val raw = ok(call(request(url(owner, repo, "contents/${Uri.encode(path, "/")}$suffix")).get().build()))
        val entries = try { JSONArray(raw) } catch (_: Exception) { JSONArray().put(JSONObject(raw)) }
        return JSONObject().put("ok", true).put("entries", entries).toString()
    }

    private fun createIssueComment(a: JSONObject): String {
        val (owner, repo) = repo(a)
        requireConfirmation("create_issue_comment", a)
        auditWrite("create_issue_comment", sanitized(a))
        val path = "issues/${a.getInt("issue_number")}/comments"
        val raw = ok(call(request(url(owner, repo, path)).post(
            JSONObject().put("body", a.getString("body")).toString().toRequestBody(JSON)
        ).build()))
        return json(raw)
    }

    private fun submitPullRequestReview(a: JSONObject): String {
        val (owner, repo) = repo(a)
        requireConfirmation("submit_pull_request_review", a)
        val body = JSONObject().put("event", a.getString("event"))
        a.optString("body").takeIf { it.isNotBlank() }?.let { body.put("body", it) }
        auditWrite("submit_pull_request_review", sanitized(a))
        val raw = ok(call(request(url(owner, repo, "pulls/${a.getInt("pull_number")}/reviews")).post(body.toString().toRequestBody(JSON)).build()))
        return json(raw)
    }

    private fun mergePullRequest(a: JSONObject): String {
        val (owner, repo) = repo(a)
        val number = a.getInt("pull_number")
        fun fetchPr() = JSONObject(ok(call(request(url(owner, repo, "pulls/$number")).get().build())))
        val pr = fetchPr()
        val headSha = GitHubAllowedRefPolicy.approvedMergeHead(pr, allowedBranches())
        val base = pr.getJSONObject("base").getString("ref")
        val fileReply = JSONArray(ok(call(request(url(owner, repo, "pulls/$number/files?per_page=100")).get().build())))
        val changedFiles = pr.optInt("changed_files", -1)
        require(changedFiles > 0 && changedFiles == fileReply.length()) {
            "Full PR file list is required before a safe merge"
        }
        val paths = (0 until fileReply.length()).map { fileReply.getJSONObject(it).getString("filename") }
        val required = GitHubMergeCheckPolicy.required(paths)
        fun verifiedChecks(): String {
            val urlPath = "actions/runs?head_sha=${Uri.encode(headSha)}&per_page=100"
            val result = JSONObject(ok(call(request(url(owner, repo, urlPath)).get().build())))
            require(result.optInt("total_count", 101) <= 100) {
                "Workflow list is truncated; cannot verify required checks"
            }
            return GitHubMergeCheckPolicy.check(headSha, required, result.getJSONArray("workflow_runs"))
        }
        val checks = verifiedChecks()
        val preview = JSONObject(a.toString())
            .put("_verified_title", pr.optString("title"))
            .put("_verified_base_ref", base)
            .put("_verified_head_ref", pr.getJSONObject("head").optString("ref"))
            .put("_verified_head_sha", headSha)
            .put("_verified_workflows", checks)
        requireConfirmation("merge_pull_request", preview)
        // Check again directly before writing; an approval belongs to one exact PR head/base.
        val current = fetchPr()
        val currentHeadSha = GitHubAllowedRefPolicy.approvedMergeHead(current, allowedBranches())
        require(currentHeadSha == headSha &&
            current.getJSONObject("base").optString("ref") == base) {
            "PR head or base changed after confirmation; merge cancelled"
        }
        verifiedChecks()
        auditWrite("merge_pull_request", sanitized(a)
            .put("base_branch", base).put("head_sha", headSha).put("verified_checks", checks))
        val body = JSONObject().put("merge_method", a.getString("merge_method")).put("sha", headSha)
        val raw = ok(call(request(url(owner, repo, "pulls/$number/merge")).put(
            body.toString().toRequestBody(JSON)
        ).build()))
        return json(raw)
    }

    private fun createOrUpdateFile(a: JSONObject): String {
        val (owner, repo) = repo(a)
        val path = validateRelativePath(a.getString("path"))
        val branchName = a.getString("branch").trim()
        val contentBase64 = if (!a.isNull("content")) {
            Base64.encodeToString(
                JsonProtocol.string(a, "content").toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
        } else {
            a.optString("content_base64").takeIf { it.isNotBlank() }
                ?: throw IOException("content is required; content_base64 is supported for compatibility")
        }
        branch(branchName)
        var sha = a.optString("sha").trim().takeIf { it.isNotBlank() }
        if (sha == null) {
            val lookup = call(request(url(owner, repo, "contents/${Uri.encode(path, "/")}?ref=${Uri.encode(branchName)}")).get().build())
            if (lookup.first in 200..299) sha = JSONObject(lookup.second).optString("sha").takeIf { it.isNotBlank() }
            else if (lookup.first != 404) throw IOException("GitHub HTTP ${lookup.first}:${lookup.second.take(1600)}")
        }
        requireConfirmation("create_or_update_file", a)
        val body = JSONObject().put("message", a.getString("message")).put("content", contentBase64).put("branch", branchName)
        sha?.let { body.put("sha", it) }
        auditWrite(
            "create_or_update_file",
            sanitized(a)
                .put("resolved_sha", sha ?: JSONObject.NULL)
                .put("content_length_base64", contentBase64.length)
        )
        return json(ok(call(request(url(owner, repo, "contents/${Uri.encode(path, "/")}")).put(body.toString().toRequestBody(JSON)).build())))
    }

    private fun triggerWorkflowDispatch(a: JSONObject): String {
        val (owner, repo) = repo(a)
        val workflowId = a.getString("workflow_id").trim()
        if (workflowId.isBlank() || workflowId.contains('/') || workflowId.contains('\\') || workflowId.contains("..")) {
            throw IOException("Invalid workflow_id")
        }
        val ref = a.getString("ref").trim()
        branch(ref)
        requireConfirmation("trigger_workflow_dispatch", a)
        val body = JSONObject().put("ref", ref)
        a.optJSONObject("inputs")?.let { body.put("inputs", it) }
        auditWrite("trigger_workflow_dispatch", sanitized(a))
        val raw = ok(call(request(url(owner, repo, "actions/workflows/${Uri.encode(workflowId)}/dispatches")).post(body.toString().toRequestBody(JSON)).build()))
        return if (raw.isBlank()) JSONObject().put("ok", true).toString() else json(raw)
    }

    private fun requireConfirmation(name: String, args: JSONObject) {
        if (!canExecute() || !confirmWrite(name, args) || !canExecute())
            throw IOException("User declined or request owner is no longer current")
    }

    private fun sanitized(input: JSONObject): JSONObject = JSONObject(input.toString()).apply {
        remove("body")
        remove("content")
        remove("content_base64")
        remove("inputs")
    }

    private fun json(raw: String): String {
        val data: Any = try { JSONObject(raw) } catch (_: Exception) {
            try { JSONArray(raw) } catch (_: Exception) { raw }
        }
        return JSONObject().put("ok", true).put("data", data).toString()
    }

    private fun errorResult(message: String) = JSONObject().put("ok", false).put("error", message).toString()
    private val JSON = "application/json".toMediaType()
}
