package com.example.openaichat

import android.app.Application
import android.content.Context
import android.os.Looper
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class LocalArtifactDiffIntegrationTest {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun annotations(provider: String, artifact: JSONObject): JSONArray = if (provider == "openai")
        AstraResponseModel.fromResponse(JSONObject().put("app_local_artifacts", JSONArray().put(artifact)), "Ответ").annotations
    else OpenRouterResponseRuntime::class.java.getDeclaredMethod("annotations", List::class.java).apply { isAccessible = true }
        .invoke(OpenRouterResponseRuntime, listOf(artifact)) as JSONArray

    @Test fun actualProviderAnnotationsSurviveSqliteReopenAndRenderUsableDiffInEveryTheme() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("response_notifications", false).commit()
        context.deleteDatabase("astra_chats.db")
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        val executor = LocalArtifactToolExecutor(context)
        val original = executor.createTextFile(JSONObject().put("filename", "version.txt").put("content", "заголовок\nстарый\n"))
        var patched: JSONObject? = null
        try {
            val source = OpenRouterLocalAttachment.fromJson(original.getJSONObject("attachment"))
            val result = executor.patchLocalFile(JSONObject().put("file_id", source.id).put("patches", JSONArray()
                .put(JSONObject().put("search", "старый").put("replace", "новый"))), listOf(source)).also { patched = it }
            for (provider in listOf("openai", "openrouter")) {
                val chatId = "diff-$provider"
                val message = JSONObject().put("role", "assistant").put("status", "completed").put("text", "Ответ")
                    .put("provider", provider).put("annotations", annotations(provider, result))
                ChatDatabase(context).use { db ->
                    db.upsertCurrentChat(chatId, "Diff", null, null, listOf(message))
                    assertTrue(db.registerLocalArtifact(chatId, "request-$provider", result))
                }
                // Feed the actual persisted annotation into the production file-card entrypoint.
                val annotation = ChatDatabase(context).use { it.getChat(chatId)!!.getJSONArray("messages")
                    .getJSONObject(0).getJSONArray("annotations").getJSONObject(0) }
                for (themeId in AppThemePalette.themes) {
                    prefs.edit().putString("app_theme", themeId).commit()
                    val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    activity.setContentView(box)
                    MainActivity::class.java.getDeclaredMethod("populateReadyFileCard", LinearLayout::class.java,
                        JSONObject::class.java, String::class.java, String::class.java).apply { isAccessible = true }
                        .invoke(activity, box, annotation, result.getString("filename"), result.getString("path"))
                    val actions = views(box).filterIsInstance<TextView>().map { it.text.toString() }
                    assertTrue(actions.containsAll(listOf("Просмотр", "Сохранить", "Поделиться")))
                    views(box).filterIsInstance<Button>().single { it.tag == "local-artifact-diff-toggle" }.performClick()
                    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (System.nanoTime() < end && views(box).filterIsInstance<TextView>().none { it.text.toString().contains("+ новый") }) {
                        shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
                    }
                    val diff = views(box).filterIsInstance<TextView>().single { it.text.toString().contains("+ новый") }.text as Spanned
                    val colors = diff.getSpans(0, diff.length, ForegroundColorSpan::class.java).map { it.foregroundColor }
                    val theme = AppThemePalette.byId(themeId)
                    assertTrue(colors.contains(theme.diffAddedText)); assertTrue(colors.contains(theme.errorText))
                    assertEquals("заголовок\nстарый\n", File(original.getString("path")).readText())
                    assertEquals("заголовок\nновый\n", File(result.getString("path")).readText())
                }
            }
        } finally {
            controller.destroy(); prefs.edit().clear().commit()
            File(original.getString("path")).delete()
            patched?.let { File(it.getString("path")).delete(); File(it.getString("patch_diff_path")).delete() }
        }
    }
}
