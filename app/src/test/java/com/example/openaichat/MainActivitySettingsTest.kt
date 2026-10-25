package com.example.openaichat

import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivitySettingsTest {
    private lateinit var prefs: SharedPreferences
    private var controller: ActivityController<MainActivity>? = null

    @Before fun before() {
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("w1080dp-h1800dp-mdpi")
        val context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("response_notifications", false).commit()
        context.getSharedPreferences("secure", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("astra_chats.db")
    }

    @After fun after() {
        RichResponsePanel.beforeAsyncParseForTests = null
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
        controller?.destroy()
        RuntimeEnvironment.setFontScale(1f)
        prefs.edit().clear().commit()
    }

    private fun launch(provider: String = "openrouter"): MainActivity {
        prefs.edit().putString("provider", provider)
            .putString("model", if (provider == "openai") "gpt-6-astra" else "anthropic/claude-haiku-5.5").commit()
        return Robolectric.buildActivity(MainActivity::class.java).create().also { controller = it }.get()
    }

    private fun openAppSettings(activity: MainActivity): AlertDialog {
        MainActivity::class.java.getDeclaredMethod("showSettings").apply { isAccessible = true }.invoke(activity)
        return ShadowAlertDialog.getLatestAlertDialog()!!
    }

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun layoutWindow(activity: MainActivity, width: Int = 1080, height: Int = 1800) {
        @Suppress("DEPRECATION") val display = activity.windowManager.defaultDisplay
        val shadowDisplay = shadowOf(display)
        @Suppress("DEPRECATION")
        if (display.width != width || display.height != height) {
            shadowDisplay.setWidth(width)
            shadowDisplay.setHeight(height)
        }
        val decor = activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, width, height)
    }
    private fun widgets(dialog: AlertDialog) = views(dialog.window!!.decorView)
    private fun labels(dialog: AlertDialog) = widgets(dialog).filterIsInstance<TextView>().map { it.text.toString() }
    private fun checkbox(dialog: AlertDialog, prefix: String) = widgets(dialog).filterIsInstance<CheckBox>()
        .single { it.text.startsWith(prefix) }
    private fun save(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun routineStatusIsHiddenAndImportantNoticeDoesNotMoveComposerOrLog() {
        val activity = launch()
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as View
        val notice = field("status") as TextView
        val log = field("requestTelemetryCard")
        val model = field("modelBadge")
        layoutWindow(activity)
        val logTop = log.top
        val modelTop = model.top
        listOf("Готово", "Новый чат", "Модель отвечает…", "Продолжается генерация…", "").forEach {
            notice.text = it
            assertEquals(View.GONE, notice.visibility)
        }
        notice.text = "Не удалось открыть файл"
        layoutWindow(activity)
        assertEquals(View.VISIBLE, notice.visibility)
        assertEquals(logTop, log.top)
        assertEquals(modelTop, model.top)
        assertTrue(notice.parent is android.widget.FrameLayout)
        notice.text = "Скопировано"
        assertEquals(View.VISIBLE, notice.visibility)
        notice.text = "Готово"
        assertEquals(View.GONE, notice.visibility)
    }

    @Test fun allComposerOptionsHaveEqualCompactHeightAtDifferentFontScales() {
        listOf(1f, 1.5f).forEach { scale ->
            RuntimeEnvironment.setFontScale(scale)
            val activity = launch()
            fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as TextView
            layoutWindow(activity)
            val model = field("modelBadge")
            val effort = field("effortBadge")
            val settings = field("responseSettingsBtn")
            assertTrue(effort.height > 0)
            assertEquals(effort.height, model.height)
            assertEquals(effort.height, settings.height)
            assertEquals(0, settings.minimumHeight)
            assertTrue((model.layoutParams as ViewGroup.MarginLayoutParams).rightMargin > 0)
            controller!!.destroy()
            controller = null
        }
    }

    @Test fun appSettingsContainKeysAppearanceAndDiagnosticsButNoModelControls() {
        val activity = launch()
        val dialog = openAppSettings(activity)
        val hints = widgets(dialog).filterIsInstance<EditText>().map { it.hint?.toString() }
        assertTrue(hints.containsAll(listOf("OpenRouter API key", "GitHub fine-grained PAT")))
        assertFalse(hints.contains("OpenAI API key"))
        assertTrue(labels(dialog).containsAll(listOf("Настройки приложения", "Тема оформления Integral LLM", "🧪 Диагностика")))
        assertTrue(checkbox(dialog, "Продолжать генерацию ответа в фоне").isChecked)
        assertEquals(1, widgets(dialog).filterIsInstance<Spinner>().size)
        assertFalse(labels(dialog).any { it.contains("Усилие мышления") || it.contains("Автосжатие") || it.contains("Web Search") })
        assertFalse(hints.contains("Системный промпт"))
        save(dialog)
        assertFalse(prefs.contains("openai_auto_compaction_user_explicit"))
    }

    @Test fun claudeResponseSettingsSaveToolsAndInstructionsWithoutChangingAppPreferences() {
        val activity = launch()
        activity.showResponseSettings()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()!!
        val texts = labels(dialog)
        assertTrue(texts.containsAll(listOf("Настройки модели и ответа", "Инструменты, маршрут и кэш OpenRouter", "Усилие мышления")))
        assertFalse(texts.any { it.contains("API key") || it.contains("Тема оформления") || it.contains("GitHub fine-grained PAT") })
        assertFalse(texts.contains("Продолжать генерацию ответа в фоне"))
        assertFalse(texts.any { it.contains("Автосжатие") || it.contains("Инструменты и контекст OpenAI") })
        checkbox(dialog, "OpenRouter: Web Search").isChecked = true
        widgets(dialog).filterIsInstance<EditText>().single { it.hint == "Системный промпт" }.setText("Keep all evidence")
        save(dialog)
        assertTrue(prefs.getBoolean("openrouter_web_search", false))
        assertEquals("Keep all evidence", prefs.getString("system_prompt", null))
        assertFalse(prefs.contains("app_theme"))
        activity.showResponseSettings()
        val reopened = ShadowAlertDialog.getLatestAlertDialog()!!
        assertTrue(checkbox(reopened, "OpenRouter: Web Search").isChecked)
        checkbox(reopened, "OpenRouter: Web Search").isChecked = false
        save(reopened)
        assertFalse(prefs.getBoolean("openrouter_web_search", true))
    }

    @Test fun openRouterResponseSettingsDoNotOverwriteHiddenOpenAiSettings() {
        prefs.edit().putBoolean("openai_auto_compaction", true).putBoolean("openai_auto_compaction_user_explicit", true)
            .putBoolean("web", true).putString("vector_store", "vs_keep").commit()
        val activity = launch("openrouter")
        activity.showResponseSettings()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()!!
        assertTrue(labels(dialog).containsAll(listOf("OpenRouter: Web Search", "Разрешить GitHub-инструменты", "Маршрут Claude (по умолчанию Прямой Anthropic)")))
        assertFalse(labels(dialog).any { it.contains("Автосжатие") || it == "Web Search (OpenAI)" })
        checkbox(dialog, "OpenRouter: Web Search").isChecked = true
        save(dialog)
        assertTrue(prefs.getBoolean("openrouter_web_search", false))
        assertTrue(prefs.getBoolean("openai_auto_compaction", false))
        assertTrue(prefs.getBoolean("web", false))
        assertEquals("vs_keep", prefs.getString("vector_store", null))
        assertEquals("anthropic", prefs.getString("openrouter_claude_route", null))
    }

    @Test fun migrationReplacesRetiredProviderAndPreservesBackgroundChoice() {
        prefs.edit().putBoolean("background_responses", false).commit()
        val activity = launch("openai")
        assertEquals("openrouter", prefs.getString("provider", null))
        assertEquals("anthropic/claude-opus-5.5", prefs.getString("model", null))
        assertFalse(prefs.getBoolean("background_responses", true))
        prefs.edit().putString("model", "anthropic/claude-custom").commit()
        MainActivity::class.java.getDeclaredMethod("migrateLegacySettingsPreferences").apply { isAccessible = true }.invoke(activity)
        assertEquals("anthropic/claude-custom", prefs.getString("model", null))
        assertFalse(prefs.getBoolean("background_responses", true))
    }

    @Test fun savedBackgroundResponsesFalseSurvivesMigrationRestartAndSettingsSave() {
        prefs.edit().putBoolean("background_responses", false)
            .remove("background_responses_migrated_v25").commit()
        var activity = launch()
        assertFalse(prefs.getBoolean("background_responses", true))
        var dialog = openAppSettings(activity)
        assertFalse(checkbox(dialog, "Продолжать генерацию ответа в фоне").isChecked)
        save(dialog)
        assertFalse(prefs.getBoolean("background_responses", true))

        controller!!.destroy()
        controller = null
        activity = launch()
        assertFalse(prefs.getBoolean("background_responses", true))
        dialog = openAppSettings(activity)
        assertFalse(checkbox(dialog, "Продолжать генерацию ответа в фоне").isChecked)
        checkbox(dialog, "Продолжать генерацию ответа в фоне").isChecked = true
        save(dialog)
        assertTrue(prefs.getBoolean("background_responses", false))

        dialog = openAppSettings(activity)
        assertTrue(checkbox(dialog, "Продолжать генерацию ответа в фоне").isChecked)
        checkbox(dialog, "Продолжать генерацию ответа в фоне").isChecked = false
        save(dialog)
        assertFalse(prefs.getBoolean("background_responses", true))

        controller!!.destroy()
        controller = null
        activity = launch()
        assertFalse(prefs.getBoolean("background_responses", true))
        dialog = openAppSettings(activity)
        assertFalse(checkbox(dialog, "Продолжать генерацию ответа в фоне").isChecked)
    }

    @Test fun missingBackgroundPreferenceDefaultsToTrueEvenWithLegacyMigrationMarker() {
        prefs.edit().remove("background_responses")
            .putBoolean("background_responses_migrated_v25", true).commit()
        val activity = launch()
        assertTrue(prefs.contains("background_responses"))
        assertTrue(prefs.getBoolean("background_responses", false))
        assertTrue(checkbox(openAppSettings(activity), "Продолжать генерацию ответа в фоне").isChecked)
    }

    @Test fun migrationPreservesSavedClaudeRouteAndCache() {
        val activity = launch()
        prefs.edit().putString("model", "~anthropic/claude-custom")
            .putString("openrouter_claude_route", "auto").putString("openrouter_cache_mode", "5m").commit()
        MainActivity::class.java.getDeclaredMethod("migrateLegacySettingsPreferences").apply { isAccessible = true }.invoke(activity)
        assertEquals("~anthropic/claude-custom", prefs.getString("model", null))
        assertEquals("auto", prefs.getString("openrouter_claude_route", null))
        assertEquals("5m", prefs.getString("openrouter_cache_mode", null))
    }

    @Test fun selectingEachThemeAppliesRootHeaderAndInputColorsWithoutRestart() {
        val activity = launch()
        val input = MainActivity::class.java.getDeclaredField("input").apply { isAccessible = true }.get(activity) as EditText
        input.setText("Неотправленный черновик 😀")
        val model = prefs.getString("model", null)
        val chat = prefs.getString("current_chat", null)
        for (id in AppThemePalette.themes) {
            val dialog = openAppSettings(activity)
            widgets(dialog).filterIsInstance<Spinner>().single().setSelection(AppThemePalette.themes.indexOf(id))
            save(dialog)
            val theme = AppThemePalette.byId(id)
            fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
            assertEquals(id, prefs.getString("app_theme", null))
            assertEquals(theme.rootBg, ((field("rootLayout") as View).background as ColorDrawable).color)
            assertEquals(theme.headerBg, ((field("headerLayout") as View).background as ColorDrawable).color)
            assertEquals(theme.textPrimary, (field("input") as EditText).currentTextColor)
            assertEquals(theme.hintText, (field("input") as EditText).currentHintTextColor)
            assertEquals("Неотправленный черновик 😀", input.text.toString())
            assertEquals(model, prefs.getString("model", null))
            assertEquals(chat, prefs.getString("current_chat", null))
        }
        controller!!.destroy()
        controller = null
        val reopened = launch()
        val root = MainActivity::class.java.getDeclaredField("rootLayout").apply { isAccessible = true }.get(reopened) as View
        assertEquals(AppThemePalette.GRAY.rootBg, (root.background as ColorDrawable).color)
        val restoredInput = MainActivity::class.java.getDeclaredField("input").apply { isAccessible = true }.get(reopened) as EditText
        assertEquals("Неотправленный черновик 😀", restoredInput.text.toString())
    }

    @Test fun recyclerViewHasStableIdAndPreservesReadingPositionAcrossSwitchRecreateThemeAndBackgroundDelta() {
        val context = RuntimeEnvironment.getApplication()
        ChatDatabase(context).use { db ->
            val messagesA = mutableListOf<JSONObject>()
            for (i in 1..10) {
                val reqId = "req_a_$i"
                messagesA += JSONObject().put("role", "user").put("request_id", reqId)
                    .put("text", "Вопрос $i в чате A")
                val tallBody = (1..80).joinToString("\n\n") { line ->
                    "Ответ $i строка $line: подробное математическое и физическое объяснение."
                }
                messagesA += JSONObject().put("role", "assistant").put("request_id", reqId)
                    .put("status", "completed").put("text", tallBody)
            }
            db.ensureChat("chat-a", "Чат A")
            db.upsertCurrentChat("chat-a", "Чат A", null, null, messagesA)
            db.ensureChat("chat-b", "Чат B")
            db.upsertCurrentChat("chat-b", "Чат B", null, null, listOf(
                JSONObject().put("role", "user").put("request_id", "req_b_1").put("text", "Вопрос в B"),
                JSONObject().put("role", "assistant").put("request_id", "req_b_1").put("status", "completed").put("text", "Ответ в B")
            ))
        }
        prefs.edit().putString("current_chat", "chat-a").commit()
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller = c
        val activity = c.get()
        fun rv(act: MainActivity) = MainActivity::class.java.getDeclaredField("scroll").apply { isAccessible = true }.get(act) as RecyclerView
        fun jumpBtn(act: MainActivity) = MainActivity::class.java.getDeclaredField("jumpToBottomBtn").apply { isAccessible = true }.get(act) as TextView
        fun layoutRv(act: MainActivity, w: Int = 1080, h: Int = 1800) {
            layoutWindow(act, w, h)
        }
        layoutRv(activity)
        shadowOf(Looper.getMainLooper()).idle()
        layoutRv(activity)

        val scroll = rv(activity)
        assertEquals(R.id.chat_messages_recycler, scroll.id)
        val lm = scroll.layoutManager as LinearLayoutManager
        val lastIndex = scroll.adapter!!.itemCount - 1

        // 1. Cold launch opens at the true bottom of the tall last answer (FOLLOW_LATEST, jump button hidden).
        val distAtColdLaunch = MainActivity::class.java.getDeclaredMethod("pixelDistanceFromBottom")
            .apply { isAccessible = true }.invoke(activity) as Int
        assertEquals(0, distAtColdLaunch)
        assertEquals(View.GONE, jumpBtn(activity).visibility)

        // 2. User scrolls up into the middle of the tall last answer via scroll gesture callbacks (no manual lock injection).
        val bottomOffset = lm.findViewByPosition(lastIndex)!!.top - scroll.paddingTop
        assertTrue("Tall fixture must extend above the viewport: top=$bottomOffset height=${lm.findViewByPosition(lastIndex)!!.height} viewport=${scroll.height}", bottomOffset < -420)
        assertNull("Cold restore must finish before the user starts reading", MainActivity::class.java.getDeclaredField("pendingScrollRestoreChatId")
            .apply { isAccessible = true }.get(activity))
        val scrollStepUp = bottomOffset + 420
        scroll.scrollBy(0, scrollStepUp)
        layoutRv(activity)
        @Suppress("UNCHECKED_CAST")
        val locked = MainActivity::class.java.getDeclaredField("userScrollLockedChats").apply { isAccessible = true }.get(activity) as MutableSet<String>
        assertTrue("Upward scroll must lock reading mode automatically", "chat-a" in locked)
        assertEquals(lastIndex, lm.findFirstVisibleItemPosition())
        val readingView = lm.findViewByPosition(lastIndex)!!
        val readingOffset = readingView.top - scroll.paddingTop
        val readChar = MainActivity::class.java.getDeclaredMethod("findCharOffsetAtViewportTop", View::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val lineTop = MainActivity::class.java.getDeclaredMethod("findLineTopForCharOffset", View::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val readingChar = readChar.invoke(activity, readingView, -readingOffset) as Int
        assertTrue("Reading anchor must identify content inside the answer", readingChar > 0)
        assertTrue("Reading offset in tall bubble must be negative", readingOffset < 0)
        assertEquals(View.VISIBLE, jumpBtn(activity).visibility)

        // 3. Background delta arrives at the bottom of the last message: reading position must not jump or slingshot.
        val adapter = scroll.adapter as ChatMessageAdapter
        val lastItemId = adapter.snapshot().last().id
        val grownText = adapter.snapshot().last().text + "\n" + (81..110).joinToString("\n") { "Фоновая дельта строка $it" }
        adapter.updateText(lastItemId, grownText)
        MainActivity::class.java.getDeclaredMethod("scrollToLatest", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, false)
        shadowOf(Looper.getMainLooper()).idle()
        layoutRv(activity)
        assertEquals(readingOffset, lm.findViewByPosition(lastIndex)!!.top - scroll.paddingTop)

        // 4. A queued force-follow from B must not overwrite A's saved reading anchor.
        val switchMethod = MainActivity::class.java.getDeclaredMethod("switchChat", String::class.java).apply { isAccessible = true }
        switchMethod.invoke(activity, "chat-b")
        shadowOf(Looper.getMainLooper()).idle()
        layoutRv(activity)
        MainActivity::class.java.getDeclaredMethod("scrollToLatest", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, true)
        switchMethod.invoke(activity, "chat-a")
        shadowOf(Looper.getMainLooper()).idle()
        layoutRv(activity)
        assertEquals(lastIndex, lm.findFirstVisibleItemPosition())
        assertEquals(readingOffset, lm.findViewByPosition(lastIndex)!!.top - scroll.paddingTop)

        // 5. Theme switch and font-scale resize preserve reading lock and adapt offset without jumping to top/bottom.
        prefs.edit().putString("app_theme", "light").commit()
        MainActivity::class.java.getDeclaredMethod("applyTheme").apply { isAccessible = true }.invoke(activity)
        shadowOf(Looper.getMainLooper()).idle()
        layoutRv(activity)
        assertEquals(readingOffset, lm.findViewByPosition(lastIndex)!!.top - scroll.paddingTop)

        RuntimeEnvironment.setFontScale(1.3f)
        MainActivity::class.java.getDeclaredMethod("applyTheme").apply { isAccessible = true }.invoke(activity)
        shadowOf(Looper.getMainLooper()).idle()
        layoutRv(activity)
        val scaledView = lm.findViewByPosition(lastIndex)!!
        val scaledOffset = scaledView.top - scroll.paddingTop
        assertEquals("Font scaling must put the line containing the saved character at the viewport top",
            -(lineTop.invoke(activity, scaledView, readingChar) as Int), scaledOffset)
        assertTrue("Font scale increase must keep reading offset negative inside tall message", scaledOffset < 0)
        assertTrue("chat-a" in locked)
        RuntimeEnvironment.setFontScale(1f)
        MainActivity::class.java.getDeclaredMethod("applyTheme").apply { isAccessible = true }.invoke(activity)
        shadowOf(Looper.getMainLooper()).idle()
        layoutRv(activity)

        // 6. Saved-state recreate (including landscape width rotation 1080 -> 1920) preserves reading mode.
        val offsetBeforeRecreate = lm.findViewByPosition(lastIndex)!!.top - scroll.paddingTop
        val charBeforeRecreate = readChar.invoke(activity, lm.findViewByPosition(lastIndex)!!, -offsetBeforeRecreate) as Int
        val savedState = Bundle()
        c.saveInstanceState(savedState).pause().stop().destroy()
        val recreatedController = Robolectric.buildActivity(MainActivity::class.java)
            .create(savedState).start().postCreate(savedState).resume().visible()
        controller = recreatedController
        val recreated = recreatedController.get()
        layoutRv(recreated, w = 1920, h = 1080)
        shadowOf(Looper.getMainLooper()).idle()
        layoutRv(recreated, w = 1920, h = 1080)
        val recreatedScroll = rv(recreated)
        val recreatedLm = recreatedScroll.layoutManager as LinearLayoutManager
        assertEquals(lastIndex, recreatedLm.findFirstVisibleItemPosition())
        val rotatedView = recreatedLm.findViewByPosition(lastIndex)!!
        val rotatedOffset = rotatedView.top - recreatedScroll.paddingTop
        assertEquals("Landscape must keep the line containing the saved character visible",
            -(lineTop.invoke(recreated, rotatedView, charBeforeRecreate) as Int), rotatedOffset)
        assertEquals(View.VISIBLE, jumpBtn(recreated).visibility)

        // 7. Two-stage jump button: from earlier message -> Stage 1 (LAST_MESSAGE_START), Stage 2 (CHAT_BOTTOM).
        recreatedLm.scrollToPositionWithOffset(2, 0)
        layoutRv(recreated)
        val handleJump = MainActivity::class.java.getDeclaredMethod("handleTwoStageJumpClick").apply { isAccessible = true }
        handleJump.invoke(recreated)
        layoutRv(recreated)
        assertEquals(lastIndex, recreatedLm.findFirstVisibleItemPosition())
        assertEquals(0, recreatedLm.findViewByPosition(lastIndex)!!.top - recreatedScroll.paddingTop)
        assertEquals(View.VISIBLE, jumpBtn(recreated).visibility)

        handleJump.invoke(recreated)
        layoutRv(recreated)
        val finalDist = MainActivity::class.java.getDeclaredMethod("pixelDistanceFromBottom")
            .apply { isAccessible = true }.invoke(recreated) as Int
        assertEquals(0, finalDist)
        assertEquals(View.GONE, jumpBtn(recreated).visibility)
    }

    @Test fun systemFontScaleMatrixUsesNativeSpAndPreservesTheReadingLine() {
        val context = RuntimeEnvironment.getApplication()
        val answer = (1..100).joinToString("\n\n") { "Масштабируемый ответ, абзац $it: читаемое объяснение без ручного изменения системных метрик." }
        ChatDatabase(context).use { db ->
            db.ensureChat("font-scale", "Масштаб")
            db.upsertCurrentChat("font-scale", "Масштаб", null, null, listOf(
                JSONObject().put("role", "assistant").put("request_id", "font-answer")
                    .put("status", "completed").put("text", answer)))
        }
        prefs.edit().putString("current_chat", "font-scale").commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().also { controller = it }.get()
        val recycler = MainActivity::class.java.getDeclaredField("scroll").apply { isAccessible = true }.get(activity) as RecyclerView
        val lm = recycler.layoutManager as LinearLayoutManager
        val readChar = MainActivity::class.java.getDeclaredMethod("findCharOffsetAtViewportTop", View::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val lineTop = MainActivity::class.java.getDeclaredMethod("findLineTopForCharOffset", View::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        fun layout() {
            layoutWindow(activity)
        }
        layout(); shadowOf(Looper.getMainLooper()).idle(); layout()
        val bottomOffset = lm.findViewByPosition(0)!!.top - recycler.paddingTop
        assertTrue(bottomOffset < -450)
        recycler.scrollBy(0, bottomOffset + 450)
        layout()
        for (scale in listOf(1f, 1.3f, 1.6f, 2f)) {
            val before = lm.findViewByPosition(0)!!
            val beforeOffset = before.top - recycler.paddingTop
            val targetChar = readChar.invoke(activity, before, -beforeOffset) as Int
            val previousScale = activity.resources.configuration.fontScale
            RuntimeEnvironment.setFontScale(scale)
            @Suppress("DEPRECATION") val systemScaledDensity = activity.resources.displayMetrics.scaledDensity
            MainActivity::class.java.getDeclaredMethod("applyTheme").apply { isAccessible = true }.invoke(activity)
            shadowOf(Looper.getMainLooper()).idle(); layout()
            @Suppress("DEPRECATION") val afterThemeDensity = activity.resources.displayMetrics.scaledDensity
            assertEquals("Theme must preserve system metrics at fontScale=$scale", systemScaledDensity, afterThemeDensity, 0f)
            val card = lm.findViewByPosition(0)!!
            val offset = card.top - recycler.paddingTop
            val expectedOffset = if (scale == previousScale) beforeOffset else -(lineTop.invoke(activity, card, targetChar) as Int)
            assertEquals("Reading line at fontScale=$scale", expectedOffset, offset)
            val text = views(card).filterIsInstance<TextView>().first { it.text.contains("Масштабируемый ответ") }
            val nativeReference = TextView(activity).apply { textSize = 16f }
            assertEquals("Use framework sp conversion at fontScale=$scale", nativeReference.textSize, text.textSize, 0.01f)
        }
    }

    @Test fun asyncAnswerRestoreRetainsDeepAnchorUntilParsedContentIsLaidOut() {
        val context = RuntimeEnvironment.getApplication()
        val longAnswer = (1..900).joinToString("\n\n") {
            "Строка $it: длинное объяснение сохраняет читаемую позицию после фонового разбора ответа."
        }
        assertTrue("Fixture must exercise RichResponsePanel's asynchronous branch", longAnswer.length > 32_000)
        ChatDatabase(context).use { db ->
            db.ensureChat("async-a", "Асинхронный A")
            db.upsertCurrentChat("async-a", "Асинхронный A", null, null, listOf(
                JSONObject().put("role", "assistant").put("request_id", "async-answer")
                    .put("status", "completed").put("text", longAnswer)
            ))
            db.ensureChat("async-b", "Чат B")
            db.upsertCurrentChat("async-b", "Чат B", null, null, listOf(
                JSONObject().put("role", "assistant").put("request_id", "short-answer")
                    .put("status", "completed").put("text", "Короткий ответ в другом чате")
            ))
        }
        prefs.edit().putString("current_chat", "async-a").commit()
        val activityController = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller = activityController
        val activity = activityController.get()
        val recycler = MainActivity::class.java.getDeclaredField("scroll").apply { isAccessible = true }.get(activity) as RecyclerView
        val lm = recycler.layoutManager as LinearLayoutManager
        fun layout() {
            layoutWindow(activity)
        }
        fun currentPanel(): RichResponsePanel? = lm.findViewByPosition(0)?.let { card ->
            views(card).filterIsInstance<RichResponsePanel>().firstOrNull()
        }
        fun awaitRenderedPanel() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                layout()
                val panel = currentPanel()
                if (panel != null && !panel.isAsyncParsePending() && panel.height > 0) return
                Thread.sleep(10)
            }
            fail("Asynchronous answer did not finish parsing and layout")
        }
        layout()
        awaitRenderedPanel()
        val initialCard = lm.findViewByPosition(0)!!
        val bottomOffset = initialCard.top - recycler.paddingTop
        assertTrue("Async fixture must extend above the viewport: top=$bottomOffset height=${initialCard.height} viewport=${recycler.height}", bottomOffset < -600)
        assertNull("Initial async restore must finish before user scrolling", MainActivity::class.java.getDeclaredField("pendingScrollRestoreChatId")
            .apply { isAccessible = true }.get(activity))
        recycler.scrollBy(0, bottomOffset + 600)
        layout()
        val expectedOffset = lm.findViewByPosition(0)!!.top - recycler.paddingTop
        assertTrue("Fixture must establish a deep reading anchor", expectedOffset < 0)
        @Suppress("UNCHECKED_CAST")
        val anchors = MainActivity::class.java.getDeclaredField("perChatScrollAnchors").apply { isAccessible = true }
            .get(activity) as Map<String, ChatScrollAnchor>
        assertEquals(expectedOffset, anchors.getValue("async-a").topOffsetPx)
        val switchChat = MainActivity::class.java.getDeclaredMethod("switchChat", String::class.java).apply { isAccessible = true }
        switchChat.invoke(activity, "async-b")
        shadowOf(Looper.getMainLooper()).idle()
        layout()
        val parseEntered = CountDownLatch(1)
        val resumeParse = CountDownLatch(1)
        RichResponsePanel.beforeAsyncParseForTests = {
            parseEntered.countDown()
            check(resumeParse.await(10, TimeUnit.SECONDS)) { "Test did not release asynchronous parser" }
        }
        try {
            switchChat.invoke(activity, "async-a")
            shadowOf(Looper.getMainLooper()).idle()
            layout()
            assertTrue("Restore must actually wait for asynchronous parsing", parseEntered.await(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            layout()
            assertTrue(currentPanel()!!.isAsyncParsePending())
            assertEquals("An empty pending card must not overwrite the deep saved anchor",
                expectedOffset, anchors.getValue("async-a").topOffsetPx)
            assertEquals("async-a", MainActivity::class.java.getDeclaredField("pendingScrollRestoreChatId")
                .apply { isAccessible = true }.get(activity))
            resumeParse.countDown()
            awaitRenderedPanel()
            shadowOf(Looper.getMainLooper()).idle()
            layout()
            assertEquals("Once real content is measured, restore the original deep reading offset",
                expectedOffset, lm.findViewByPosition(0)!!.top - recycler.paddingTop)
            assertEquals(expectedOffset, anchors.getValue("async-a").topOffsetPx)
        } finally {
            RichResponsePanel.beforeAsyncParseForTests = null
            resumeParse.countDown()
        }
    }

}
