package com.example.openaichat

import android.app.Application
import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

/** Explicit user limits remain distinct from the obsolete implicit 128000 default. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityMaxOutputOverrideTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private var controller: ActivityController<MainActivity>? = null

    @Before fun before() {
        prefs.edit().clear().putString("provider", "openrouter")
            .putString("model", "anthropic/claude-sonnet-5.5")
            .putBoolean("response_notifications", false).commit()
        context.getSharedPreferences("secure", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("astra_chats.db")
    }

    @After fun after() {
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
        controller?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        prefs.edit().clear().commit()
        context.deleteDatabase("astra_chats.db")
    }

    private fun launch(): MainActivity = Robolectric.buildActivity(MainActivity::class.java)
        .create().also { controller = it }.get()

    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    private fun outputField(activity: MainActivity): EditText {
        activity.showResponseSettings()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()!!
        return views(dialog.window!!.decorView).filterIsInstance<EditText>()
            .single { it.hint?.toString() == "Max output tokens" }
    }

    private fun saveSettings() {
        ShadowAlertDialog.getLatestAlertDialog()!!.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun configured(activity: MainActivity): Int? = MainActivity::class.java
        .getDeclaredMethod("configuredMaxOutputTokens").apply { isAccessible = true }.invoke(activity) as Int?

    private fun wire(activity: MainActivity): JSONObject {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
            try {
                client.complete(apiKey = "synthetic-max-output-key", model = "anthropic/claude-sonnet-5.5",
                    messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Hello")),
                    tools = null, maxTokens = configured(activity), reasoningEffort = "high", verbosity = null,
                    parallelToolCalls = false, executeTool = { _, _ -> error("No tool may run") }, cacheMode = "off")
                assertEquals(1, server.requestCount)
                return JSONObject(server.takeRequest().body.readUtf8())
            } finally { client.close() }
        }
    }

    @Test fun explicitlySaving128000SurvivesSettingsReopenAndBecomesTheActualWireLimit() {
        val activity = launch()
        outputField(activity).setText("128000")
        saveSettings()
        assertEquals(128000, configured(activity))
        controller!!.destroy()
        controller = null
        val reopened = launch()
        assertEquals(128000, configured(reopened))
        assertEquals("128000", outputField(reopened).text.toString())
        ShadowAlertDialog.getLatestAlertDialog()!!.dismiss()
        assertEquals(128000, wire(reopened).getInt("max_tokens"))
    }

    @Test fun legacyImplicit128000StaysUnboundedAndTheUserCanExplicitlyClearAnOverride() {
        prefs.edit().putString("max_output", "128000").remove("max_output_explicit").commit()
        val activity = launch()
        assertNull(configured(activity))
        assertEquals("", outputField(activity).text.toString())
        ShadowAlertDialog.getLatestAlertDialog()!!.dismiss()
        assertFalse(wire(activity).has("max_tokens"))

        outputField(activity).setText("128000")
        saveSettings()
        assertEquals(128000, configured(activity))
        outputField(activity).setText("")
        saveSettings()
        assertNull(configured(activity))
        assertFalse(wire(activity).has("max_tokens"))
    }
}
