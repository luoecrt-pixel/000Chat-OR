package com.example.openaichat

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/** Actual SSE/runtime/SQLite publication failure: retaining bytes must not cause a paid continuation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OpenRouterArtifactRuntimeBoundaryTest {
    @Test fun failedSqlCompletionRetainsActualProviderCallArtifactAndStopsBeforeSecondPost() {
        val context: Context = RuntimeEnvironment.getApplication()
        val chat = "runtime-artifact-gap"
        val request = "runtime-artifact-request"
        val providerCall = "provider-issued-call-387"
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
        File(context.filesDir, "generated").deleteRecursively()
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putBoolean("response_notifications", false).commit()
        val server = MockWebServer().apply { start() }
        val metadataClient = OkHttpClient()
        val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
        try {
            ChatDatabase(context).use { db ->
                db.ensureChat(chat, "Boundary")
                db.setActiveResponse(chat, null, request, "", "in_progress", 1L, "high", "high", false, 0, 0, false)
                assertTrue(db.startRequestAccounting(chat, request, "openrouter", "test-model", "key"))
                db.writableDatabase.execSQL("CREATE TRIGGER runtime_deny_completion BEFORE UPDATE OF state ON local_artifact_intents WHEN NEW.state='completed' BEGIN SELECT RAISE(ABORT,'completion failed'); END")
            }
            val tool = JSONObject().put("index", 0).put("id", providerCall).put("type", "function")
                .put("function", JSONObject().put("name", "create_local_text_file").put("arguments",
                    JSONObject().put("filename", "retained.txt").put("content", "retained paid result").toString()))
            val chunk = JSONObject().put("id", "generation-boundary").put("model", "test-model")
                .put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
                    .put("delta", JSONObject().put("tool_calls", JSONArray().put(tool)))))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: $chunk\n\ndata: [DONE]\n\n"))
            // If the unsafe continuation regresses, fail promptly rather than waiting on an empty queue.
            server.enqueue(MockResponse().setResponseCode(500).setBody("unsafe continuation"))
            OpenRouterResponseRuntime.start(context, OpenRouterRuntimeRequest(chat, request, "test-model",
                JSONArray().put(JSONObject().put("role", "user").put("content", "Create a file")),
                emptyList(), "high", "", null, false, "", emptySet(), false, false, false, "off", false,
                SystemClock.elapsedRealtime(), "general", "high", false, 13, "mock-key", metadataClient, "auto"), client)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (OpenRouterResponseRuntime.contains(request) && System.nanoTime() < deadline) Thread.sleep(10)
            assertFalse("Runtime must finish the failed tool round", OpenRouterResponseRuntime.contains(request))
            assertEquals("Unknown publication outcome forbids the next inference POST", 1, server.requestCount)
            ChatDatabase(context).use { db ->
                val intent = db.getLocalArtifactIntent(chat, request, providerCall)!!
                assertEquals(providerCall, intent.getString("tool_call_id"))
                assertEquals("outcome_unknown", intent.getString("state"))
                val target = File(intent.getJSONObject("plan").getJSONObject("artifact").getString("path"))
                assertEquals("retained paid result", target.readText())
                assertNull(db.getLocalArtifactIntent(chat, request, "local_artifact_0"))
            }
        } finally {
            OpenRouterResponseRuntime.cancel(request)
            client.close()
            server.shutdown()
            metadataClient.dispatcher.executorService.shutdownNow()
            metadataClient.connectionPool.evictAll()
            SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
            File(context.filesDir, "generated").deleteRecursively()
        }
    }
}
