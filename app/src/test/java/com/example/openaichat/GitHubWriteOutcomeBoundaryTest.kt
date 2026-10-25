package com.example.openaichat

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.lang.reflect.InvocationTargetException

/** Real transport boundary: an accepted request with a lost response is never retried. */
@RunWith(RobolectricTestRunner::class)
class GitHubWriteOutcomeBoundaryTest {
    private fun executor(confirm: Boolean = true) = GitHubToolExecutor(
        SecureStore(RuntimeEnvironment.getApplication()), { "owner/repo" }, { setOf("main") },
        { _, _ -> confirm }, { _, _ -> })

    @Suppress("UNCHECKED_CAST")
    private fun call(executor: GitHubToolExecutor, request: Request): Pair<Int, String> {
        val method = GitHubToolExecutor::class.java.getDeclaredMethod("call", Request::class.java).apply { isAccessible = true }
        return try { method.invoke(executor, request) as Pair<Int, String> }
        catch (error: InvocationTargetException) { throw error.targetException }
    }

    @Test fun responseLostAfterAcceptedMutationIsUnknownWithOneRequest() {
        val server = MockWebServer(); server.start()
        val executor = executor()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            assertThrows(GitHubToolOutcomeUnknownException::class.java) {
                call(executor, Request.Builder().url(server.url("/mutation")).post("{}".toRequestBody()).build())
            }
            assertEquals("POST", server.takeRequest().method)
            assertEquals(1, server.requestCount)
        } finally { executor.close(); server.shutdown() }
    }

    @Test fun knownRejectedMutationIsReturnedWithoutAutomaticRetry() {
        val server = MockWebServer(); server.start()
        val executor = executor()
        try {
            for (status in listOf(400, 403, 429)) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("denied"))
                assertEquals(status, call(executor, Request.Builder().url(server.url("/mutation"))
                    .post("{}".toRequestBody()).build()).first)
            }
            assertEquals(3, server.requestCount)
        } finally { executor.close(); server.shutdown() }
    }

    @Test fun serverFailureAfterMutationDoesNotClaimKnownFailure() {
        val server = MockWebServer(); server.start()
        val executor = executor()
        try {
            server.enqueue(MockResponse().setResponseCode(500).setBody("unknown commit status"))
            assertThrows(GitHubToolOutcomeUnknownException::class.java) {
                call(executor, Request.Builder().url(server.url("/mutation")).put("{}".toRequestBody()).build())
            }
            assertEquals(1, server.requestCount)
        } finally { executor.close(); server.shutdown() }
    }

    @Test fun retryAfterZeroCannotTransparentlyResendAnAcceptedMutation() {
        val server = MockWebServer(); server.start()
        val executor = executor()
        try {
            // OkHttp otherwise follows this 503 even when retryOnConnectionFailure is false.
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setResponseCode(201).setBody("{\"duplicate\":true}"))
            val outcome = runCatching {
                call(executor, Request.Builder().url(server.url("/mutation")).post("exact-body".toRequestBody()).build())
            }
            val request = server.takeRequest()
            assertEquals("exact-body", request.body.readUtf8())
            assertEquals("10", request.getHeader("Content-Length"))
            assertEquals("A 503 follow-up must not repeat the mutation", 1, server.requestCount)
            assertTrue(outcome.exceptionOrNull() is GitHubToolOutcomeUnknownException)
        } finally { executor.close(); server.shutdown() }
    }

    @Test fun declinedWriteRemainsKnownNeverSentAndSuccessReturnsExactBody() {
        val server = MockWebServer(); server.start()
        val executor = executor(false)
        try {
            val declined = JSONObject(executor.execute("create_issue_comment", JSONObject()
                .put("owner", "owner").put("repo", "repo").put("issue_number", 1).put("body", "example")))
            assertFalse(declined.getBoolean("ok"))
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(201).setBody("{\"id\":17}"))
            assertEquals(201 to "{\"id\":17}", call(executor, Request.Builder().url(server.url("/mutation"))
                .post("{}".toRequestBody()).build()))
            assertEquals(1, server.requestCount)
        } finally { executor.close(); server.shutdown() }
    }
}
