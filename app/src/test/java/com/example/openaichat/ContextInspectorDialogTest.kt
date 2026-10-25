package com.example.openaichat

import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ContextInspectorDialogTest {
    @Test fun activityBadgeIsInTelemetryHeaderAndClickOpensInspector() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("app", android.content.Context.MODE_PRIVATE).edit().clear()
            .putString("provider", "openrouter").putString("model", "anthropic/claude-opus-5.5")
            .putBoolean("response_notifications", false).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val badge = activity.window.decorView.findViewWithTag<TextView>("context-status-badge")
            assertNotNull(badge)
            assertTrue(badge.text.contains("Контекст"))
            val shown = CountDownLatch(1)
            MainActivity::class.java.getDeclaredField("contextInspectionCompletedHook").apply { isAccessible = true }
                .set(activity, { shown.countDown() })
            assertTrue(badge.performClick())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (shown.count > 0 && System.nanoTime() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                shown.await(5, TimeUnit.MILLISECONDS)
            }
            assertEquals("The captured context inspection must finish", 0L, shown.count)
            val dialog = ShadowDialog.getLatestDialog()
            assertTrue(dialog.isShowing)
            assertNotNull(dialog.findViewById<android.view.View>(android.R.id.content)
                .findViewWithTag<TextView>("context-inspector-details"))
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun dialogUsesCapturedSnapshotAndOpensOnlyOnExplicitAction() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val snapshot = ContextStatusSnapshot("captured-model", "Anthropic через OpenRouter", "кэш 1h", "reason",
            2, 42L, listOf("данные.csv"), emptyList(), null, 0L, null)
        val dialog = ContextInspectorDialog.show(activity, AppThemePalette.byId("dark"), snapshot)
        assertTrue(dialog.isShowing)
        val text = dialog.findViewById<android.view.View>(android.R.id.content)
            .findViewWithTag<TextView>("context-inspector-details")
        assertTrue(text.text.contains("captured-model"))
        assertTrue(text.text.contains("неизвестно"))
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        activity.finish()
    }
}
