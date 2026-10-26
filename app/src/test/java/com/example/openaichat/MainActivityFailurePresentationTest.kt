package com.example.openaichat

import android.app.Application
import android.content.Context
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

/** Real Send/HTTP/runtime/SQLite failures must not invent received answer text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [SyntheticFailureCredentials::class])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityFailurePresentationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("app", 0)
    private val chat = "failure-presentation"
    private val model = "anthropic/claude-haiku-5.5"
    private lateinit var server: MockWebServer
    private var controller: ActivityController<MainActivity>? = null

    @Before fun before() {
        context.deleteDatabase("astra_chats.db")
        prefs.edit().clear().putString("model", model).putString("provider", "openrouter")
            .putString("current_chat", chat).putBoolean("background_responses", false)
            .putBoolean("response_notifications", false).commit()
        context.getSharedPreferences("secure", 0).edit().clear().commit()
        // Android Keystore is covered on the emulator. This boundary supplies
        // only a synthetic credential; Send, HTTP, runtime, SQLite and UI run normally.
        assertEquals("synthetic-failure-presentation-key", SecureStore(context).getOpenRouterApiKey())
        server = MockWebServer().apply { start() }
    }

    @After fun after() {
        ChatDatabase(context).use { db -> db.listRequestAccounting(chat).forEach {
            OpenRouterResponseRuntime.cancel(it.getString("local_request_id"))
        } }
        await("Runtime cleanup") { ChatDatabase(context).use { db -> db.listRequestAccounting(chat)
            .none { OpenRouterResponseRuntime.contains(it.getString("local_request_id")) } } }
        controller?.pause()?.stop()?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        if (::server.isInitialized) server.shutdown()
        context.deleteDatabase("astra_chats.db")
        prefs.edit().clear().commit()
        context.getSharedPreferences("secure", 0).edit().clear().commit()
    }

    private fun field(activity: MainActivity, name: String) = MainActivity::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun launch(): MainActivity {
        val activityController = Robolectric.buildActivity(MainActivity::class.java)
        val factory: () -> OpenRouterChatClient = {
            OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
        }
        MainActivity::class.java.getDeclaredField("openRouterClientFactory").apply { isAccessible = true }
            .set(activityController.get(), factory)
        controller = activityController.setup()
        return activityController.get()
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(message, condition())
    }

    private fun answer(): JSONObject? = ChatDatabase(context).use { db ->
        val messages = db.getChat(chat)?.optJSONArray("messages") ?: return@use null
        (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }
            .lastOrNull { it.optString("role") == "assistant" }
    }

    private fun sendAndAwaitFailure(response: MockResponse): Pair<MainActivity, JSONObject> {
        server.enqueue(response)
        val activity = launch()
        (field(activity, "input") as EditText).setText("Привет")
        activity.send.performClick()
        await("Actual Send must persist its failed assistant and render the terminal state") {
            val message = answer()
            message != null && !OpenRouterResponseRuntime.contains(message.optString("request_id")) &&
                (field(activity, "status") as TextView).text.toString().startsWith("Ошибка OpenRouter")
        }
        assertEquals(1, server.requestCount)
        val wire = JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
        assertEquals(model, wire.getString("model"))
        ChatDatabase(context).use { assertNull(it.getActiveResponse(chat)) }
        return activity to answer()!!
    }

    @Test fun initialHttp400DoesNotClaimThatAPartialAnswerWasReceived() {
        val error = JSONObject().put("error", JSONObject().put("code", 400)
            .put("message", "Provider returned error").put("metadata", JSONObject().put("provider_name", "Anthropic")
                .put("raw", JSONObject().put("error", JSONObject().put("type", "invalid_request_error")
                    .put("message", "tools.1.custom.input_schema: input_schema does not support oneOf, allOf, or anyOf at the top level")))))
        val (activity, message) = sendAndAwaitFailure(MockResponse().setResponseCode(400).setBody(error.toString()))
        assertEquals("", message.getString("text"))
        assertEquals("failed", message.getString("status"))
        assertEquals("Ошибка OpenRouter — текст ответа не получен", (field(activity, "status") as TextView).text.toString())
        assertTrue(message.getString("error").contains("схема инструмента Claude"))
        assertFalse(message.getString("error").contains("начните новый чат"))
        assertFalse(message.getString("error").contains("synthetic-failure-presentation-key"))
    }

    @Test fun sseFailureAfterTextRetainsExactTextAndReportsThePartialAnswer() {
        val text = "Полученный текст 字🙂 "
        val frame = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", text))))
        val error = JSONObject().put("error", JSONObject().put("code", 500).put("message", "Synthetic stream failure"))
        val (activity, message) = sendAndAwaitFailure(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: $frame\n\ndata: $error\n\n"))
        assertEquals(text, message.getString("text"))
        assertEquals("Ошибка OpenRouter — частичный ответ сохранён", (field(activity, "status") as TextView).text.toString())
        assertEquals(text, answer()!!.getString("text"))
    }

    @Test fun restartWithoutReceivedTextDoesNotInventAPartialAnswerOrSendHttp() {
        ChatDatabase(context).use { db ->
            db.ensureChat(chat)
            db.setActiveResponse(chat, null, "empty-interrupted", "", "in_progress", 1L,
                "high", "high", false, 0, 0, false)
            assertTrue(db.startRequestAccounting(chat, "empty-interrupted", "openrouter", model, "openrouter_api_key"))
        }
        launch()
        await("Startup must recover the interrupted owner") { answer() != null }
        val recovered = answer()!!
        assertEquals("disconnected", recovered.getString("status"))
        assertEquals("", recovered.getString("text"))
        assertTrue(recovered.getString("error").contains("Текст ответа не получен"))
        assertFalse(recovered.getString("error").contains("Частичный ответ сохранён"))
        assertEquals(0, server.requestCount)
    }

    @Test fun malformedLegacyHistorySurvivesStartupWithAnExplicitWarning() {
        val raw = "[{broken legacy history"
        prefs.edit().putString("chat_store", raw).putBoolean("chat_store_migrated_v7", false).commit()
        val activity = launch()
        assertEquals(raw, prefs.getString("chat_store", null))
        assertFalse(prefs.getBoolean("chat_store_migrated_v7", false))
        assertTrue((field(activity, "status") as TextView).text.toString().contains("Исходные данные сохранены"))
        assertEquals(0, server.requestCount)
    }
}

@Implements(value = SecureStore::class, isInAndroidSdk = false)
class SyntheticFailureCredentials {
    @Implementation fun getOpenRouterApiKey(): String = "synthetic-failure-presentation-key"
}
