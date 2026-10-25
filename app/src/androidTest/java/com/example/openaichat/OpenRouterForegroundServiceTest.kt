package com.example.openaichat

import android.app.ActivityManager
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual Android foreground-service lifetime; localhost HTTPS never calls a provider. */
@RunWith(AndroidJUnit4::class)
class OpenRouterForegroundServiceTest {
    @Suppress("DEPRECATION") // Android exposes this limited view of the app's own running services.
    @Test fun foregroundServiceFinishesAndPersistsAnswerAfterActivityStops() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chat = "native-fgs-" + UUID.randomUUID()
        val request = "request-$chat"
        val model = "anthropic/claude-sonnet-5.5"
        val answer = "Ответ сохранён фоновой службой 😀"
        val prefs = context.getSharedPreferences("app", 0)
        val keys = listOf("current_chat", "model", "response_notifications")
        val previous = keys.associateWith { prefs.all[it] }
        val releaseResponse = CountDownLatch(1)
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .readTimeout(0, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
        val server = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            dispatcher = object : Dispatcher() {
                override fun dispatch(recorded: RecordedRequest): MockResponse {
                    if (!releaseResponse.await(30, TimeUnit.SECONDS))
                        return MockResponse().setResponseCode(599).setBody("Fixture did not enter background")
                    val frame = JSONObject().put("id", "gen-native-fgs").put("model", model)
                        .put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", answer))
                            .put("finish_reason", "stop")))
                    return MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody("data: $frame\n\ndata: [DONE]\n\n")
                }
            }
            start()
        }
        val client = OpenRouterChatClient(server.url("/api/v1").toString(), http, reconcileGenerations = false)
        var scenario: ActivityScenario<MainActivity>? = null
        fun await(message: String, condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 20_000
            while (SystemClock.elapsedRealtime() < until) {
                if (condition()) return
                SystemClock.sleep(20)
            }
            assertTrue(message, condition())
        }
        fun serviceIsForeground(): Boolean = context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE).any {
                it.service.className == OpenRouterResponseService::class.java.name && it.foreground
            }
        try {
            ChatDatabase(context).use { it.ensureChat(chat, "Foreground service") }
            assertTrue(prefs.edit().putString("current_chat", chat).putString("model", model)
                .putBoolean("response_notifications", false).commit())
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity {
                ChatDatabase(context).use { db ->
                    db.upsertCurrentChat(chat, "Foreground service", null, null, listOf(JSONObject().put("role", "user")
                        .put("text", "Continue in background").put("provider", "openrouter").put("request_id", request)))
                    db.setActiveResponse(chat, null, request, "", "in_progress", System.currentTimeMillis(),
                        "high", "high", false, 22, 0, true)
                    assertTrue(db.startRequestAccounting(chat, request, "openrouter", model, "openrouter_api_key"))
                }
                OpenRouterResponseRuntime.start(context, OpenRouterRuntimeRequest(
                    chatId = chat, requestId = request, model = model,
                    messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Continue in background")),
                    attachments = emptyList(), effort = "high", verbosity = "", maxTokens = null,
                    github = false, githubRepo = "", githubBranches = emptySet(), webSearch = false,
                    webFetch = false, parallel = false, cacheMode = "off", background = true,
                    startedAt = SystemClock.elapsedRealtime(), taskClass = "general", effortPref = "high",
                    autoSelected = false, promptChars = 22, apiKey = "sk-or-v1-synthetic-fgs-fixture",
                    metadataClient = http, pdfEngine = "native"), client)
            }
            assertNotNull("Actual foreground service must launch the HTTPS round", server.takeRequest(20, TimeUnit.SECONDS))
            await("OpenRouterResponseService was not actually foreground", ::serviceIsForeground)
            scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse("The response must finish with no visible Activity", OpenRouterUiBridge.viewing(chat))
            assertTrue("Stopping the Activity must retain background runtime", OpenRouterResponseRuntime.contains(request))
            assertTrue(serviceIsForeground())
            releaseResponse.countDown()
            await("Background runtime did not finalize", { !OpenRouterResponseRuntime.contains(request) })
            ChatDatabase(context).use { db ->
                assertNull(db.getActiveResponse(chat))
                val rows = db.getChat(chat)!!.getJSONArray("messages")
                assertEquals(2, rows.length())
                assertEquals(answer, rows.getJSONObject(1).getString("text"))
                assertEquals("completed", rows.getJSONObject(1).getString("status"))
                assertEquals("completed", db.listRequestAccounting(chat).single().getString("local_status"))
            }
            assertEquals("No hidden inference retry", 1, server.requestCount)
            await("Idle foreground service was not stopped", { !serviceIsForeground() })
        } finally {
            releaseResponse.countDown()
            OpenRouterResponseRuntime.cancel(request)
            await("Foreground fixture runtime cleanup", { !OpenRouterResponseRuntime.contains(request) })
            scenario?.close()
            client.close()
            server.shutdown()
            ChatDatabase(context).use { it.deleteChat(chat) }
            val restore = prefs.edit()
            previous.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value)
                is Boolean -> restore.putBoolean(key, value)
                else -> restore.remove(key)
            } }
            assertTrue(restore.commit())
        }
    }
}
