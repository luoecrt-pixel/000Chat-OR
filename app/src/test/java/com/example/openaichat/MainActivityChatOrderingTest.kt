package com.example.openaichat

import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityChatOrderingTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private var controller: ActivityController<MainActivity>? = null

    @Before fun before() {
        context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("response_notifications", false)
            .putString("provider", "openrouter").putString("model", "anthropic/claude-sonnet-5.5")
            .putString("current_chat", "older").commit()
        context.getSharedPreferences("secure", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("astra_chats.db")
    }

    @After fun after() {
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
        controller?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        prefs.edit().clear().commit()
    }

    @Test fun actualSwitchNewChatAndSettingsSaveKeepSessionsAtTheirContentModificationTimes() {
        ChatDatabase(context).use { db ->
            for ((id, title) in listOf("older" to "Older", "newer" to "Newer")) {
                db.upsertCurrentChat(id, title, null, null,
                    listOf(JSONObject().put("role", "user").put("request_id", "$id-request").put("text", "$id message")))
            }
            db.writableDatabase.execSQL("UPDATE chats SET updated_at=CASE id WHEN 'older' THEN 100 ELSE 200 END")
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().also { controller = it }.get()
        val switch = MainActivity::class.java.getDeclaredMethod("switchChat", String::class.java).apply { isAccessible = true }
        repeat(2) {
            switch.invoke(activity, "newer")
            switch.invoke(activity, "older")
        }
        activity.showSettings()
        ShadowAlertDialog.getLatestAlertDialog()!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.newChat()

        ChatDatabase(context).use { db ->
            assertEquals(listOf("newer", "older"), db.listChatSummaries().map { it.getString("id") })
            db.readableDatabase.rawQuery("SELECT id,updated_at FROM chats WHERE id IN ('older','newer')", null).use { c ->
                val times = mutableMapOf<String, Long>()
                while (c.moveToNext()) times[c.getString(0)] = c.getLong(1)
                assertEquals(mapOf("older" to 100L, "newer" to 200L), times)
            }
            assertEquals("older message", db.getChat("older")!!.getJSONArray("messages").getJSONObject(0).getString("text"))
            assertEquals("newer message", db.getChat("newer")!!.getJSONArray("messages").getJSONObject(0).getString("text"))
            assertEquals(0, db.getChat(prefs.getString("current_chat", "")!!)!!.getJSONArray("messages").length())
        }
    }
}
