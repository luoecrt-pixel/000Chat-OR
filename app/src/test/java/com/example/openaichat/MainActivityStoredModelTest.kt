package com.example.openaichat

import android.app.Application
import android.content.Context
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityStoredModelTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private var controller: ActivityController<MainActivity>? = null

    @Before fun before() {
        prefs.edit().clear().putBoolean("response_notifications", false).commit()
        context.getSharedPreferences("secure", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("astra_chats.db")
    }

    @After fun after() {
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
        controller?.destroy()
        prefs.edit().clear().commit()
        context.getSharedPreferences("secure", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("astra_chats.db")
    }

    private fun launch(model: String): MainActivity {
        prefs.edit().putString("model", model).putString("provider", "openai").commit()
        return Robolectric.buildActivity(MainActivity::class.java).create().also { controller = it }.get()
    }

    private fun invoke(activity: MainActivity, method: String): Any? = MainActivity::class.java
        .getDeclaredMethod(method).apply { isAccessible = true }.invoke(activity)

    private fun field(activity: MainActivity, name: String): Any? = MainActivity::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(activity)

    @Test fun legacyModelDoesNotCrashStartupSettingsPickerOrSendAndNeedsExplicitChoice() {
        val legacy = "gpt-6-astra"
        val activity = launch(legacy)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(legacy, prefs.getString("model", null))
        assertTrue((field(activity, "modelBadge") as TextView).text.toString().contains("Claude"))
        activity.showResponseSettings()
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()

        SecureStore(activity).setOpenRouterApiKey("synthetic-stored-model-key")
        (field(activity, "input") as EditText).setText("keep this draft")
        MainActivity::class.java.getDeclaredMethod("sendMessage", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("keep this draft", (field(activity, "input") as EditText).text.toString())
        assertTrue((field(activity, "status") as TextView).text.toString().contains("Claude"))
        ChatDatabase(activity).use { db ->
            assertEquals(0, db.requestAccountingTotals().getInt("requests"))
        }
        assertEquals(legacy, prefs.getString("model", null))

        invoke(activity, "showModelPicker")
        val picker = ShadowAlertDialog.getLatestAlertDialog()!!
        val list = picker.listView
        list.performItemClick(list.getChildAt(0), 0, list.adapter.getItemId(0))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ModelCatalog.isClaudeModel(prefs.getString("model", null)))
        assertEquals("openrouter", prefs.getString("provider", null))
        assertEquals(prefs.getString("model", null), ContextRequestPolicy.model("openrouter", prefs.getString("model", null)))
    }

    @Test fun customClaudePreferenceSurvivesStartupWithoutSilentSubstitution() {
        val model = "anthropic/claude-private-route"
        val activity = launch(model)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(model, prefs.getString("model", null))
        assertTrue((field(activity, "modelBadge") as TextView).text.toString().contains(model))
        assertEquals(model, ContextRequestPolicy.model("openrouter", model))
    }

    @Test fun legacy128000DefaultDoesNotBecomeMandatoryApplicationOutputLimit() {
        val activity = launch("anthropic/claude-sonnet-5.5")
        for (unbounded in listOf("", "128000", "0", "-1", "invalid")) {
            prefs.edit().putString("max_output", unbounded).commit()
            assertNull("Stored value $unbounded", invoke(activity, "configuredMaxOutputTokens"))
        }
        prefs.edit().putString("max_output", "250000").commit()
        assertEquals(250000, invoke(activity, "configuredMaxOutputTokens"))
    }
}
