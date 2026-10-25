package com.example.openaichat

import android.app.Application
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/** A real tool/HTTP chain cannot switch accounts when settings change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GitHubCredentialBindingTest {
    private val accountA = "synthetic-github-account-a"
    private val accountB = "synthetic-github-account-b"

    private fun executor(server: MockWebServer, credential: AtomicReference<String>,
        confirm: () -> Boolean = { true }, audit: () -> Unit = {},
        expectedCredential: String? = null): GitHubToolExecutor {
        val executor = GitHubToolExecutor(SecureStore(RuntimeEnvironment.getApplication()),
            { "owner/repo" }, { setOf("main") }, { _, _ -> confirm() }, { _, _ -> audit() },
            credentialSource = credential::get, expectedCredential = expectedCredential)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request()
            val url = server.url(original.url.encodedPath).newBuilder()
                .encodedQuery(original.url.encodedQuery).build()
            chain.proceed(original.newBuilder().url(url).build())
        }.build()
        GitHubToolExecutor::class.java.getDeclaredField("httpClient").apply { isAccessible = true }
            .set(executor, http)
        return executor
    }

    private fun comment() = JSONObject().put("owner", "owner").put("repo", "repo")
        .put("issue_number", 1).put("body", "Exact approved comment")

    private fun assertCredentialError(result: String) {
        val json = JSONObject(result)
        assertFalse(json.getBoolean("ok"))
        assertTrue(json.getString("error").contains("GitHub PAT"))
        assertFalse("Credential material must stay outside tool errors", result.contains(accountA))
        assertFalse(result.contains(accountB))
    }

    @Test fun rotatingCredentialDuringConfirmationNeverPostsWithTheNewAccount() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("{\"id\":7}"))
            val credential = AtomicReference(accountA)
            val executor = executor(server, credential, confirm = { credential.set(accountB); true })
            try {
                assertCredentialError(executor.execute("create_issue_comment", comment()))
                assertEquals("An account change cannot reuse an earlier approval", 0, server.requestCount)
            } finally { executor.close() }
        }
    }

    @Test fun credentialCapturedBeforePreparationCannotSwitchBeforeExecutorConstruction() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("{\"id\":11}"))
            val credential = AtomicReference(accountB)
            val executor = executor(server, credential, expectedCredential = accountA)
            try {
                assertCredentialError(executor.execute("create_issue_comment", comment()))
                assertEquals(0, server.requestCount)
            } finally { executor.close() }
        }
    }

    @Test fun readThenCredentialReplacementCannotContinueTheChainUnderAnotherAccount() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"number\":1,\"title\":\"Approved account data\"}"))
            server.enqueue(MockResponse().setResponseCode(201).setBody("{\"id\":8}"))
            val credential = AtomicReference(accountA)
            val executor = executor(server, credential)
            try {
                val read = JSONObject(executor.execute("get_pull_request", JSONObject()
                    .put("owner", "owner").put("repo", "repo").put("pull_number", 1)))
                assertTrue(read.getBoolean("ok"))
                assertEquals("Bearer $accountA", server.takeRequest().getHeader("Authorization"))
                credential.set(accountB)
                assertCredentialError(executor.execute("create_issue_comment", comment()))
                assertEquals("Only the original read may have reached HTTP", 1, server.requestCount)
            } finally { executor.close() }
        }
    }

    @Test fun removalAfterApprovalButBeforeHttpAlsoPreventsTheWrite() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("{\"id\":9}"))
            val credential = AtomicReference(accountA)
            val executor = executor(server, credential, audit = { credential.set("") })
            try {
                assertCredentialError(executor.execute("create_issue_comment", comment()))
                assertEquals(0, server.requestCount)
            } finally { executor.close() }
        }
    }

    @Test fun unchangedCredentialSendsExactlyTheApprovedBodyWithItsCapturedAccount() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("{\"id\":10}"))
            val credential = AtomicReference(accountA)
            val executor = executor(server, credential)
            try {
                val result = JSONObject(executor.execute("create_issue_comment", comment()))
                assertTrue(result.getBoolean("ok"))
                val sent = server.takeRequest()
                assertEquals("POST", sent.method)
                assertEquals("Bearer $accountA", sent.getHeader("Authorization"))
                assertEquals("Exact approved comment", JSONObject(sent.body.readUtf8()).getString("body"))
                assertEquals(1, server.requestCount)
            } finally { executor.close() }
        }
    }

    @Test fun acceptedWriteWithLostResponseStillHasUnknownOutcomeAfterCredentialRotation() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val credential = AtomicReference(accountA)
            val executor = executor(server, credential)
            // The server has accepted the request; rotating local settings does
            // not turn the missing response into a proven never-sent operation.
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    credential.set(accountB)
                    return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                }
            }
            try {
                assertThrows(GitHubToolOutcomeUnknownException::class.java) {
                    executor.execute("create_issue_comment", comment())
                }
                assertEquals(1, server.requestCount)
                assertEquals("Bearer $accountA", server.takeRequest().getHeader("Authorization"))
            } finally { executor.close() }
        }
    }
}
