package com.example.openaichat

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Protocol
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The host fixture must throttle response bytes without throttling its continuation upload. */
class OpenRouterToolContinuationTransportTest {
    private val prefix = "Received prefix before process death 😀"
    private val signed = "synthetic-signed-tool-reasoning"
    private val result = "Created local result\nUnicode 😀\n"

    private fun firstResponse() = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: " + JSONObject().put("id", "gen-upload-tool").put("choices", JSONArray().put(
            JSONObject().put("delta", JSONObject()
                .put("reasoning_details", JSONArray().put(JSONObject().put("index", 0)
                    .put("type", "reasoning.encrypted").put("data", signed)))
                .put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", "create")
                    .put("type", "function").put("function", JSONObject()
                        .put("name", "create_local_text_file").put("arguments", "{}")))))
                .put("finish_reason", "tool_calls"))) + "\n\ndata: [DONE]\n\n")

    private fun secondResponse(periodSeconds: Long): MockResponse {
        val frame = "data: " + JSONObject().put("id", "gen-upload-partial").put("choices", JSONArray().put(
            JSONObject().put("delta", JSONObject().put("content", prefix)
                .put("reasoning_details", JSONArray().put(JSONObject().put("index", 0)
                    .put("type", "reasoning.encrypted").put("data", "unfinished-reasoning"))))))
        val padding = 510 - frame.toByteArray(Charsets.UTF_8).size
        require(padding >= 0)
        val chunk = frame + " ".repeat(padding) + "\n\n"
        assertEquals(512, chunk.toByteArray(Charsets.UTF_8).size)
        return MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(chunk + "data: [DONE]\n\n").throttleBody(512, periodSeconds, TimeUnit.SECONDS)
    }

    @Test fun queuedResponseThrottleAlsoDelaysReadingTheSecondPostBody() {
        runContinuation(responseOnly = false)
    }

    @Test fun dispatchingThrottleAfterRequestReadPreservesFullPostAndPartialCheckpoint() {
        runContinuation(responseOnly = true)
    }

    private fun runContinuation(responseOnly: Boolean) {
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false) }
        val uploaded = CountDownLatch(1)
        val checkpointed = CountDownLatch(1)
        val calls = AtomicInteger()
        val currentCall = AtomicReference<Call>()
        val latest = AtomicReference<OpenRouterStreamSnapshot>()
        val protocol = AtomicReference<Protocol>()
        val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS)
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .eventListenerFactory {
                val index = calls.incrementAndGet()
                object : EventListener() {
                    override fun connectionAcquired(call: Call, connection: Connection) {
                        protocol.set(connection.protocol())
                    }
                    override fun requestBodyEnd(call: Call, byteCount: Long) {
                        if (index == 2) {
                            assertTrue("Continuation exceeds a single throttle period", byteCount > 4096L)
                            uploaded.countDown()
                        }
                    }
                }
            }.build()
        val executor = Executors.newSingleThreadExecutor()
        var client: OpenRouterChatClient? = null
        try {
            val first = firstResponse()
            val second = secondResponse(if (responseOnly) 3L else 1L)
            if (responseOnly) {
                // Dispatcher.peek() remains its unthrottled default while reading
                // requests. The returned response retains the real SSE throttle.
                val dispatched = AtomicInteger()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (dispatched.incrementAndGet()) {
                            1 -> first
                            2 -> second
                            else -> MockResponse().setResponseCode(599)
                        }
                }
            } else {
                server.enqueue(first)
                server.enqueue(second)
            }
            server.start()
            val actualClient = OpenRouterChatClient(server.url("/api/v1").toString(), http, reconcileGenerations = false)
            client = actualClient
            val task = executor.submit<OpenRouterCompletionResult> {
                actualClient.complete(apiKey = "synthetic-upload-key", model = "anthropic/claude-sonnet-5.5",
                    messages = JSONArray().put(JSONObject().put("role", "user").put("content", "x".repeat(4096))),
                    tools = OpenRouterAppToolCatalog.tools(), maxTokens = null, reasoningEffort = "high",
                    verbosity = null, parallelToolCalls = false,
                    executeTool = { name, _ -> assertEquals("create_local_text_file", name); result },
                    onCallCreated = currentCall::set,
                    onStreamCheckpoint = { snapshot ->
                        if (snapshot.text == prefix) { latest.set(snapshot); checkpointed.countDown() }
                    })
            }
            assertNotNull("First real tool POST", server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("Match the native HTTPS transport", Protocol.HTTP_2, protocol.get())
            assertTrue("Client submitted its complete second POST", uploaded.await(5, TimeUnit.SECONDS))
            if (!responseOnly) {
                // MockWebServer 4.12 QueueDispatcher.peek() applies the queued
                // response throttle to request-body reads too. This is the old
                // host setup's failure mechanism, before dispatch or SSE begins.
                assertNull("Server is still reading the throttled continuation upload",
                    server.takeRequest(200, TimeUnit.MILLISECONDS))
                assertEquals(1, server.requestCount)
                assertEquals(1L, checkpointed.count)
                assertFalse(task.isDone)
                return
            }
            val secondPost = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull("Unthrottled complete continuation POST reaches dispatch", secondPost)
            val wire = JSONObject(secondPost!!.body.readUtf8())
            assertEquals(secondPost.getHeader("Content-Length")!!.toLong(), secondPost.bodySize)
            val messages = wire.getJSONArray("messages")
            assertEquals(result, messages.getJSONObject(messages.length() - 1).getString("content"))
            assertEquals(signed, messages.getJSONObject(messages.length() - 2)
                .getJSONArray("reasoning_details").getJSONObject(0).getString("data"))
            assertTrue("A response prefix is checkpointed before the next throttle period",
                checkpointed.await(2, TimeUnit.SECONDS))
            assertFalse("The SSE response remains open after its first chunk", task.isDone)
            val partial = latest.get().protocol.getJSONObject("interrupted_assistant")
            assertFalse(partial.getBoolean("replay_safe"))
            assertEquals("unfinished-reasoning", partial.getJSONArray("reasoning_delta_fragments")
                .getJSONObject(0).getString("data"))
            assertEquals(prefix, task.get(5, TimeUnit.SECONDS).text)
            assertEquals(2, server.requestCount)
        } finally {
            currentCall.get()?.cancel()
            executor.shutdownNow()
            client?.close()
            server.shutdown()
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
        }
    }
}
