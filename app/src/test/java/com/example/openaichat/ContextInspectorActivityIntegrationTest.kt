package com.example.openaichat

import android.app.Application
import android.content.Context
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
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
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ContextInspectorActivityIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private var controller: ActivityController<MainActivity>? = null
    private lateinit var activity: MainActivity
    private val files = mutableListOf<File>()

    @Before fun before() {
        context.deleteDatabase("astra_chats.db")
        prefs.edit().clear().putString("provider", "openrouter").putString("model", "anthropic/claude-opus-5.5")
            .putString("current_chat", "inspector-chat").putBoolean("response_notifications", false).commit()
        context.getSharedPreferences("secure", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun after() {
        ShadowDialog.getLatestDialog()?.dismiss()
        controller?.pause()?.stop()?.destroy()
        files.forEach { it.delete() }
        prefs.edit().clear().commit()
    }
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun start(filename: String, mime: String) {
        val file = File(context.filesDir, "inspector-$filename").apply { writeText("synthetic source") }
        files += file
        val user = JSONObject().put("role", "user").put("text", "saved")
            .put("file_paths", JSONArray().put(file.path)).put("file_names", JSONArray().put(filename))
            .put("file_mimes", JSONArray().put(mime))
        ChatDatabase(context).use { it.upsertCurrentChat("inspector-chat", "Inspector", null, null, listOf(user)) }
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller!!.get()
    }
    private fun inspect(): String {
        ShadowDialog.getLatestDialog()?.dismiss()
        val shown = CountDownLatch(1)
        MainActivity::class.java.getDeclaredField("contextInspectionCompletedHook").apply { isAccessible = true }
            .set(activity, { shown.countDown() })
        activity.window.decorView.findViewWithTag<TextView>("context-status-badge").performClick()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (shown.count > 0 && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            shown.await(5, TimeUnit.MILLISECONDS)
        }
        assertEquals("Inspection callback must correspond to an installed dialog", 0L, shown.count)
        return ShadowDialog.getLatestDialog().findViewById<android.view.View>(android.R.id.content)
            .findViewWithTag<TextView>("context-inspector-details").text.toString()
    }

    @Test fun pdfOnlyHistoryDoesNotClaimReadableLocalTextOrMaterializeNewFiles() {
        start("document.pdf", "application/pdf")
        val before = context.filesDir.walkTopDown().filter { it.isFile }.map { it.path }.toSet()
        val details = inspect()
        assertTrue(details.contains("document.pdf"))
        assertFalse(details.contains("Локальные инструменты будут доступны после успешной проверки файлов"))
        assertEquals(before, context.filesDir.walkTopDown().filter { it.isFile }.map { it.path }.toSet())
    }

    @Test fun savedTextUsesLocalToolsAndInspectionCapturesCurrentOptionsAndDraft() {
        start("data.txt", "text/plain")
        assertTrue(inspect().contains("file_read_range"))
        prefs.edit().putBoolean("openrouter_web_search", true).putBoolean("openrouter_web_fetch", true).commit()
        (field("input") as EditText).setText("new draft")
        val details = inspect()
        assertTrue(details.contains("openrouter:web_search"))
        assertTrue(details.contains("openrouter:web_fetch"))
        assertTrue(details.contains("file_read_range"))
        assertTrue("Current draft must be part of the captured next request", details.contains("История: 2 сообщений"))
        assertEquals("new draft", (field("input") as EditText).text.toString())
        prefs.edit().putBoolean("openrouter_web_search", false).putBoolean("openrouter_web_fetch", false).commit()
        val next = inspect()
        assertFalse(next.contains("openrouter:web_search"))
        assertFalse(next.contains("openrouter:web_fetch"))
        assertTrue(next.lineSequence().first { it.startsWith("Инструменты:") }.contains("file_search"))
    }

    @Test fun delayedInspectorFromPreviousChatCannotOpenAfterSwitch() {
        start("data.txt", "text/plain")
        ShadowDialog.getLatestDialog()?.dismiss()
        ChatDatabase(context).use { it.upsertCurrentChat("other-chat", "Other", null, null,
            listOf(JSONObject().put("role", "user").put("text", "other conversation"))) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val shown = CountDownLatch(1)
        val preparation = MainActivity::class.java.getDeclaredField("contextInspectionPreparationHook").apply { isAccessible = true }
        preparation.set(activity, { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) })
        MainActivity::class.java.getDeclaredField("contextInspectionCompletedHook").apply { isAccessible = true }
            .set(activity, { shown.countDown() })
        try {
            activity.window.decorView.findViewWithTag<TextView>("context-status-badge").performClick()
            assertTrue("Old inspection must be blocked before switching", entered.await(5, TimeUnit.SECONDS))
            MainActivity::class.java.getDeclaredMethod("switchChat", String::class.java).apply { isAccessible = true }
                .invoke(activity, "other-chat")
            assertEquals("other-chat", prefs.getString("current_chat", null))
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (field("contextDialogPending") == true && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                shown.await(5, TimeUnit.MILLISECONDS)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Old request must release its pending state", false, field("contextDialogPending"))
            assertEquals("A stale result must not display an inspector", 1L, shown.count)
            assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
            assertEquals("other-chat", prefs.getString("current_chat", null))
        } finally {
            release.countDown()
            preparation.set(activity, null)
        }
    }

    @Test fun settingsChangedDuringBadgeInspectionAreCoalescedIntoFreshSnapshot() {
        start("data.txt", "text/plain")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val preparation = MainActivity::class.java.getDeclaredField("contextBadgePreparationHook").apply { isAccessible = true }
        val refresh = MainActivity::class.java.getDeclaredMethod("requestContextSnapshot", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        preparation.set(activity, { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) })
        try {
            refresh.invoke(activity, false)
            val startedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (entered.count > 0 && System.nanoTime() < startedDeadline) {
                shadowOf(Looper.getMainLooper()).idle()
                entered.await(5, TimeUnit.MILLISECONDS)
            }
            assertEquals("The original captured snapshot must be blocked", 0L, entered.count)
            prefs.edit().putString("provider", "openrouter").putString("model", "anthropic/claude-updated")
                .putString("system_prompt", "updated instructions").commit()
            refresh.invoke(activity, false)
            preparation.set(activity, null)
            release.countDown()
            val badge = activity.window.decorView.findViewWithTag<TextView>("context-status-badge")
            val wait = CountDownLatch(1)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!badge.contentDescription.toString().contains("anthropic/claude-updated") && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                wait.await(5, TimeUnit.MILLISECONDS)
            }
            assertTrue("A refresh skipped during prior work must eventually use the latest model",
                badge.contentDescription.toString().contains("anthropic/claude-updated"))
            assertTrue(badge.contentDescription.toString().contains("OpenRouter"))
            assertFalse("The captured OpenRouter cache mode remains visible", badge.text.toString().contains("replay"))
        } finally {
            preparation.set(activity, null)
            release.countDown()
        }
    }

    private class TrackedHistoryObject(
        private val keysVisits: AtomicInteger,
        private val stringVisits: AtomicInteger,
        private val mainVisits: AtomicInteger
    ) : JSONObject() {
        override fun keys(): MutableIterator<String> {
            keysVisits.incrementAndGet()
            if (Looper.myLooper() == Looper.getMainLooper()) mainVisits.incrementAndGet()
            return super.keys()
        }
        override fun toString(): String {
            stringVisits.incrementAndGet()
            if (Looper.myLooper() == Looper.getMainLooper()) mainVisits.incrementAndGet()
            return super.toString()
        }
    }

    @Test fun actualInspectorCapturesHeadersWithoutTraversing2010LiveProtocolObjects() {
        start("saved.txt", "text/plain")
        ShadowDialog.getLatestDialog()?.dismiss()
        val keysVisits = AtomicInteger()
        val stringVisits = AtomicInteger()
        val mainVisits = AtomicInteger()
        @Suppress("UNCHECKED_CAST")
        val live = field("chat") as MutableList<JSONObject>
        // Build the fixture outside the checked request invocation: ten messages
        // and two thousand typed protocol outputs, none persisted to SQLite.
        val fixture = (0 until 10).map { messageIndex ->
            val output = JSONArray().apply { repeat(200) { index ->
                put(TrackedHistoryObject(keysVisits, stringVisits, mainVisits)
                    .put("type", "function_call_output").put("call_id", "$messageIndex-$index")
                    .put("output", "immutable source"))
            } }
            TrackedHistoryObject(keysVisits, stringVisits, mainVisits)
                .put("role", "assistant").put("provider", "openrouter").put("status", "completed")
                .put("text", "captured answer $messageIndex").put("openai_protocol_output", output)
                .put("openrouter_protocol", JSONObject().put("version", 1).put("binding_sha256", "saved-binding")
                    .put("tail", JSONArray().put(JSONObject().put("role", "assistant").put("content", "signed tail"))))
        }
        live.clear(); live.addAll(fixture)
        prefs.edit().putString("provider", "openrouter").putString("model", "anthropic/claude-captured").commit()
        val draft = "captured draft"
        (field("input") as EditText).setText(draft)
        val expectedCharacters = fixture.sumOf { JsonProtocol.string(it, "text").length } + draft.length
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val shown = CountDownLatch(1)
        val preparation = MainActivity::class.java.getDeclaredField("contextInspectionPreparationHook").apply { isAccessible = true }
        val completion = MainActivity::class.java.getDeclaredField("contextInspectionCompletedHook").apply { isAccessible = true }
        preparation.set(activity, { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) })
        completion.set(activity, { shown.countDown() })
        try {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            MainActivity::class.java.getDeclaredMethod("requestContextSnapshot", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, true)
            assertEquals("Capture must not enumerate the live protocol graph", 0, keysVisits.get())
            assertEquals("Capture must not serialize live message or protocol objects", 0, stringVisits.get())
            assertEquals("No recursive graph work may run on UI", 0, mainVisits.get())
            assertTrue("The real executor must reach its preparation boundary", entered.await(5, TimeUnit.SECONDS))
            fixture.first().put("text", "later UI mutation")
            fixture.first().getJSONArray("openai_protocol_output").getJSONObject(0).put("output", "later protocol mutation")
            prefs.edit().putString("model", "anthropic/claude-later").commit()
            (field("input") as EditText).setText("later draft")
            preparation.set(activity, null)
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (shown.count > 0 && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                shown.await(5, TimeUnit.MILLISECONDS)
            }
            assertEquals("Captured inspection must complete", 0L, shown.count)
            val details = ShadowDialog.getLatestDialog().findViewById<android.view.View>(android.R.id.content)
                .findViewWithTag<TextView>("context-inspector-details").text.toString()
            assertTrue(details.contains("anthropic/claude-captured"))
            assertFalse(details.contains("anthropic/claude-later"))
            assertTrue(details.contains("История: 11 сообщений · $expectedCharacters символов"))
            assertTrue("Unsaved UI history must not be replaced by older SQLite rows",
                details.contains("ожидает согласованного сохранённого снимка истории"))
            assertEquals("Worker must not traverse the live UI graph either", 0, keysVisits.get())
            assertEquals(0, stringVisits.get())
        } finally {
            preparation.set(activity, null)
            completion.set(activity, null)
            release.countDown()
        }
    }
}
