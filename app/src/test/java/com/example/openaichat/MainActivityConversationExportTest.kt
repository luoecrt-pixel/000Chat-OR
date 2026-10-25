package com.example.openaichat

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.Bundle
import android.widget.EditText
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityConversationExportTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("app", 0)
    private lateinit var activity: MainActivity
    private var controller: org.robolectric.android.controller.ActivityController<MainActivity>? = null
    private val raw = "  Кириллица 😀\n| a | b |\n|---|---|\n\\(x^2\\)\n```kotlin\nval x = 1\n```\n"
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    @Before fun before() {
        context.deleteDatabase("astra_chats.db")
        prefs.edit().clear().putString("current_chat", "visible").putBoolean("response_notifications", false).commit()
        context.getSharedPreferences("secure", 0).edit().clear().commit()
        ChatDatabase(context).use { db ->
            db.upsertCurrentChat("visible", "Visible", null, null, listOf(JSONObject().put("role", "user").put("text", "OTHER CHAT")))
            db.upsertCurrentChat("selected", "Selected", null, null, listOf(JSONObject().put("role", "user").put("text", raw)))
        }
        controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        activity = controller!!.get()
        (field("input") as EditText).setText("Unsent draft")
    }
    @After fun after() {
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
        controller?.pause()?.stop()?.destroy()
        File(context.filesDir, "exports").deleteRecursively()
        File(context.cacheDir, "conversation-output.md").delete()
        prefs.edit().clear().commit()
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail(message)
    }
    private fun start(formatIndex: Int): File {
        MainActivity::class.java.getDeclaredMethod("showChatActions", JSONObject::class.java)
            .apply { isAccessible = true }.invoke(activity, JSONObject().put("id", "selected").put("title", "Selected"))
        val menu = ShadowAlertDialog.getLatestAlertDialog()!!
        assertEquals(listOf("Переименовать", "Экспорт диалога", "Удалить"), shadowOf(menu).items.map { it.toString() })
        menu.listView.performItemClick(null, 1, 1)
        val formats = ShadowAlertDialog.getLatestAlertDialog()!!
        formats.listView.performItemClick(null, formatIndex, formatIndex.toLong())
        await("Export snapshot was not prepared") { field("pendingExportPath") != null }
        return File(field("pendingExportPath") as String)
    }
    @Test fun drawerExportsSelectedChatAsTextWithoutSwitchingOrLosingDraftAndCancelCleansSnapshot() {
        val snapshot = start(1)
        val intent = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("text/plain", intent.type)
        assertTrue(intent.getStringExtra(Intent.EXTRA_TITLE)!!.endsWith(".txt"))
        assertTrue(snapshot.readText().contains(raw))
        assertFalse(snapshot.readText().contains("OTHER CHAT"))
        assertEquals("visible", prefs.getString("current_chat", null))
        assertEquals("Unsent draft", (field("input") as EditText).text.toString())
        activity.handleActivityResult(5104, Activity.RESULT_CANCELED, null)
        assertFalse(snapshot.exists())
        assertNull(field("pendingExportPath"))
    }
    @Test fun markdownSafResultWritesExactUtf8SnapshotAndRemovesTemporaryFile() {
        val snapshot = start(0)
        val expected = snapshot.readBytes()
        val intent = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals("text/markdown", intent.type)
        assertTrue(intent.getStringExtra(Intent.EXTRA_TITLE)!!.endsWith(".md"))
        val destination = File(context.cacheDir, "conversation-output.md")
        activity.handleActivityResult(5104, Activity.RESULT_OK, Intent().setData(Uri.fromFile(destination)))
        await("SAF output was not committed") { destination.exists() && !snapshot.exists() }
        assertArrayEquals(expected, destination.readBytes())
        assertTrue(destination.readText().contains(raw))
    }

    @Test fun startupCleanupPreservesSavedPickerAndSafStillWritesOriginalSnapshot() {
        val snapshot = start(0)
        val expected = snapshot.readBytes()
        val staleTime = System.currentTimeMillis() - ConversationExportSnapshots.RETENTION_MS - 60_000
        assertTrue(snapshot.setLastModified(staleTime))
        val orphan = File(snapshot.parentFile, "dialog_${UUID.randomUUID()}.txt").apply {
            writeText("abandoned private text")
            assertTrue(setLastModified(staleTime))
        }
        val saved = Bundle()
        controller!!.saveInstanceState(saved).pause().stop().destroy()
        controller = Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume().visible()
        activity = controller!!.get()
        await("Startup cleanup did not remove stale orphan") { !orphan.exists() }
        assertEquals(snapshot.path, field("pendingExportPath"))
        assertTrue(snapshot.exists())
        val destination = File(context.cacheDir, "conversation-output.md")
        activity.handleActivityResult(5104, Activity.RESULT_OK, Intent().setData(Uri.fromFile(destination)))
        await("Restored picker did not finish export") { destination.exists() && !snapshot.exists() }
        assertArrayEquals(expected, destination.readBytes())
    }
}
