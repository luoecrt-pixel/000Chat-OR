package com.example.openaichat

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
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

/** An unclassified imported owner must survive navigation and stale Send controls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityActiveOwnerGuardTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private var controller: ActivityController<MainActivity>? = null
    private lateinit var activity: MainActivity
    private val chatId = "unclassified-owner"
    private val requestId = "received-before-provider-was-known"
    private val partial = "Локально полученный фрагмент 😀"
    private val draft = "Новый несохранённый вопрос"

    @Before fun before() {
        context.deleteDatabase("astra_chats.db")
        prefs.edit().clear().putString("current_chat", chatId)
            .putString("provider", "openrouter").putString("model", "anthropic/claude-opus-5.5")
            .putBoolean("response_notifications", false)
            .putString("composer_state_$chatId", JSONObject().put("text", draft).toString()).commit()
        context.getSharedPreferences("secure", Context.MODE_PRIVATE).edit().clear().commit()
        ChatDatabase(context).use { db ->
            db.upsertCurrentChat(chatId, "Unclassified", null, null, listOf(JSONObject()
                .put("role", "user").put("provider", "unknown").put("request_id", requestId)
                .put("text", "Исходный вопрос")))
            assertTrue(db.startRequestAccounting(chatId, requestId, "unknown", null, "unknown"))
            db.setActiveResponse(chatId, null, requestId, partial, "in_progress", 1L,
                "high", "high", false, 15, 0, false)
        }
        controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        activity = controller!!.get()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun after() {
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
        controller?.pause()?.stop()?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        prefs.edit().clear().commit()
    }

    private fun field(name: String): Any = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)

    private fun invoke(name: String) = MainActivity::class.java.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)

    @Test fun unknownOwnerRemainsLocalAndOffersExplicitStopAfterStartupAndRestore() {
        repeat(2) { invoke("restoreCurrentChat") }
        assertFalse((field("send") as Button).isEnabled)
        assertEquals(View.VISIBLE, (field("stop") as View).visibility)
        assertTrue((field("status") as TextView).text.toString().contains("остановите"))
        assertEquals(draft, (field("input") as EditText).text.toString())
        assertFalse(OpenRouterResponseRuntime.contains(requestId))
        ChatDatabase(context).use { db ->
            val active = db.getActiveResponse(chatId)!!
            assertEquals(requestId, active.getString("request_id"))
            assertEquals(partial, active.getString("text"))
            assertEquals("unknown", db.listRequestAccounting(chatId).single().getString("provider"))
            assertEquals(1, db.getChat(chatId)!!.getJSONArray("messages").length())
            assertNull(db.getRequestRecoveryArchive(chatId, requestId))
        }
    }

    @Test fun staleSendControlCannotReplaceOwnerOrOpenCredentialPrompt() {
        // Simulate an old enabled control before a durable owner was reflected in the UI.
        (field("stop") as View).visibility = View.GONE
        (field("send") as Button).isEnabled = true
        val generation = activity.activeGenerationForTesting()
        MainActivity::class.java.getDeclaredMethod("sendMessage", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, false)
        assertEquals(generation, activity.activeGenerationForTesting())
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(draft, (field("input") as EditText).text.toString())
        assertFalse(OpenRouterResponseRuntime.contains(requestId))
        ChatDatabase(context).use { db ->
            assertEquals(requestId, db.getActiveResponse(chatId)!!.getString("request_id"))
            assertEquals(partial, db.getActiveResponse(chatId)!!.getString("text"))
            assertEquals(1, db.listRequestAccounting(chatId).size)
            assertEquals(1, db.getChat(chatId)!!.getJSONArray("messages").length())
        }
    }

    @Test fun explicitStopReleasesOwnerAndArchivesItsReceivedPartial() {
        invoke("stopCurrentResponse")
        assertEquals(View.GONE, (field("stop") as View).visibility)
        assertTrue((field("send") as Button).isEnabled)
        ChatDatabase(context).use { db ->
            assertNull(db.getActiveResponse(chatId))
            assertEquals(partial, db.getRequestRecoveryArchive(chatId, requestId)!!.getString("text"))
            assertEquals("stopped", db.listRequestAccounting(chatId).single().getString("local_status"))
        }
    }

    @Test fun stopArchiveFailureKeepsUiOwnerAndDraftForAnExplicitRetry() {
        val generation = activity.activeGenerationForTesting()
        ChatDatabase(context).use { db -> db.writableDatabase.execSQL("""CREATE TRIGGER fail_ui_stop
            BEFORE INSERT ON request_recovery_archive BEGIN SELECT RAISE(ABORT, 'controlled UI Stop failure'); END""") }
        invoke("stopCurrentResponse")
        assertEquals(generation, activity.activeGenerationForTesting())
        assertEquals(draft, (field("input") as EditText).text.toString())
        assertEquals(View.VISIBLE, (field("stop") as View).visibility)
        assertFalse((field("send") as Button).isEnabled)
        assertTrue((field("status") as TextView).text.toString().contains("повторите Stop"))
        ChatDatabase(context).use { db ->
            assertEquals(requestId, db.getActiveResponse(chatId)!!.getString("request_id"))
            assertEquals(partial, db.getActiveResponse(chatId)!!.getString("text"))
            assertNull(db.getRequestRecoveryArchive(chatId, requestId))
            db.writableDatabase.execSQL("DROP TRIGGER fail_ui_stop")
        }
        invoke("stopCurrentResponse")
        ChatDatabase(context).use { db ->
            assertNull(db.getActiveResponse(chatId))
            assertEquals(partial, db.getRequestRecoveryArchive(chatId, requestId)!!.getString("text"))
        }
    }

    @Test fun conflictingOpenRouterAccountingAndLegacyCheckpointSurviveStartupWithoutFinalization() {
        controller!!.pause().stop().destroy()
        val conflictChat = "conflicting-provider-owner"
        val conflictRequest = "router-account-with-legacy-checkpoint"
        val checkpoint = ResponseIndexedCheckpoint.serialize(ResponseEventState(
            text = partial, unindexedText = partial, reasoningSummary = "Received summary",
            typedOutputItems = mapOf(0 to JSONObject().put("type", "reasoning")
                .put("id", "signed-only-in-checkpoint").put("encrypted_content", "synthetic-signed-bytes"))))
        ChatDatabase(context).use { db ->
            db.upsertCurrentChat(conflictChat, "Conflicting owner", null, null, listOf(JSONObject()
                .put("role", "user").put("provider", "openrouter").put("request_id", conflictRequest)
                .put("text", "Question")))
            assertTrue(db.startRequestAccounting(conflictChat, conflictRequest, "openrouter",
                "anthropic/claude-opus-5.5", "openrouter_api_key"))
            db.setActiveResponse(conflictChat, null, conflictRequest, "", "in_progress", 1L,
                "high", "high", false, 8, 0, false)
            db.updateActiveResponse(conflictChat, null, partial, "in_progress", 1L,
                conflictRequest, checkpoint)
        }
        prefs.edit().putString("current_chat", conflictChat).commit()
        controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        activity = controller!!.get()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse((field("send") as Button).isEnabled)
        assertEquals(View.VISIBLE, (field("stop") as View).visibility)
        assertFalse(OpenRouterResponseRuntime.contains(conflictRequest))
        ChatDatabase(context).use { db ->
            val active = db.getActiveResponse(conflictChat)!!
            assertEquals(conflictRequest, active.getString("request_id"))
            assertEquals(partial, active.getString("text"))
            assertEquals(checkpoint, active.getString("indexed_checkpoint"))
            assertEquals(1, db.getChat(conflictChat)!!.getJSONArray("messages").length())
            assertEquals("running", db.listRequestAccounting(conflictChat).single().getString("local_status"))
            assertNull(db.getRequestRecoveryArchive(conflictChat, conflictRequest))
        }
    }
}
