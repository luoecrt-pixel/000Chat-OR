package com.example.openaichat

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityEvidenceReadingAnchorTest {
    @Test fun pendingOptionalEvidenceDoesNotBlockReadingOrFollowLatestAndLateFooterKeepsTheBottom() {
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("w1080dp-h1800dp-mdpi")
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        context.deleteDatabase("astra_chats.db")
        prefs.edit().clear().putString("current_chat", "reading-evidence")
            .putBoolean("response_notifications", false).commit()
        context.getSharedPreferences("secure", Context.MODE_PRIVATE).edit().clear().commit()
        val source = File(context.filesDir, "reading-evidence-source.txt").apply { writeText("Локальный исходник для проверки цитат.") }
        val attachment = OpenRouterLocalAttachment("reading-evidence-source", source.name, "text", source.path,
            "text/plain", source.length(), OpenRouterAttachmentStore.sha256(source), textPath = source.path, textEncoding = "UTF-8")
        val answer = (1..100).joinToString("\n\n") { "Абзац $it: сохранённая строка чтения должна восстановиться до завершения проверки файлов." }
        ChatDatabase(context).use { it.upsertCurrentChat("reading-evidence", "Reading", null, null,
            listOf(JSONObject().put("role", "assistant").put("status", "completed").put("text", answer)
                .put("annotations", JSONArray().put(JSONObject().put("type", "local_file")
                    .put("local_path", source.path).put("filename", source.name).put("mime_type", "text/plain"))))) }
        val block = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
        MainActivity::class.java.getDeclaredField("localEvidenceSourcesHookForTests").apply { isAccessible = true }
            .set(activity, {
                if (block.get()) { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
                listOf(attachment)
            })
        fun layout() {
            val decor = activity.window.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 1080, 1800)
        }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            val tick = CountDownLatch(1)
            while (System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle(); layout()
                if (condition()) return
                tick.await(5, TimeUnit.MILLISECONDS)
            }
            fail(message)
        }
        try {
            controller.setup()
            val recycler = field("scroll") as RecyclerView
            val manager = recycler.layoutManager as LinearLayoutManager
            fun panel() = manager.findViewByPosition(0)?.findViewWithTag<RichResponsePanel>("rich-response")
            await("Initial measured response must finish before reading") {
                field("localEvidencePresencePending") == false && panel()?.isAsyncParsePending() == false &&
                    panel()?.isEvidencePending() == false && field("pendingScrollRestoreChatId") == null
            }
            val bottomOffset = manager.findViewByPosition(0)!!.top - recycler.paddingTop
            assertTrue(bottomOffset < -450)
            recycler.scrollBy(0, bottomOffset + 450); layout()
            val before = manager.findViewByPosition(0)!!
            val beforeOffset = before.top - recycler.paddingTop
            val readChar = MainActivity::class.java.getDeclaredMethod("findCharOffsetAtViewportTop", View::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            val targetChar = readChar.invoke(activity, before, -beforeOffset) as Int
            assertTrue(targetChar > 0)
            block.set(true)
            // A theme rebind can reuse a valid chat snapshot. Explicitly start
            // a new metadata revision to exercise genuinely pending evidence.
            MainActivity::class.java.getDeclaredMethod("refreshLocalEvidenceEligibility").apply { isAccessible = true }.invoke(activity)
            await("New file revision must reach the controlled discovery") { entered.count == 0L }
            RuntimeEnvironment.setFontScale(1.3f)
            MainActivity::class.java.getDeclaredMethod("applyTheme").apply { isAccessible = true }.invoke(activity)
            await("Measured body restore must finish while evidence remains blocked") {
                entered.count == 0L && panel()?.isBodyLayoutPending() == false && field("pendingScrollRestoreChatId") == null
            }
            assertTrue("Optional evidence must genuinely still be loading", panel()!!.isEvidencePending())
            assertFalse("Optional evidence is not a core response-layout dependency", panel()!!.isAsyncParsePending())
            val restored = manager.findViewByPosition(0)!!
            val lineTop = MainActivity::class.java.getDeclaredMethod("findLineTopForCharOffset", View::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, restored, targetChar) as Int
            assertEquals("Keep the exact saved reading line at new font scale", -lineTop, restored.top - recycler.paddingTop)
            MainActivity::class.java.getDeclaredMethod("scrollToLatest", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, true)
            await("Follow latest must complete without waiting for optional evidence") {
                field("pendingScrollRestoreChatId") == null && panel()?.isAsyncParsePending() == false
            }
            assertTrue("Evidence remains blocked after follow-latest completes", panel()!!.isEvidencePending())
            release.countDown()
            await("Late optional footer must finish without reopening the restore") {
                panel()?.isEvidencePending() == false && field("pendingScrollRestoreChatId") == null
            }
            // Footer layout posts the ordinary follow-latest adjustment after
            // its own commit callback; drain that event before checking pixels.
            shadowOf(Looper.getMainLooper()).idle(); layout()
            val distance = MainActivity::class.java.getDeclaredMethod("pixelDistanceFromBottom").apply { isAccessible = true }.invoke(activity)
            assertEquals(0, distance)
        } finally {
            release.countDown()
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
            prefs.edit().clear().commit()
            source.delete()
        }
    }

    @Test fun threeVisibleResponsesShareDiscoveryAndRefreshTheirFootersOncePerChatRevision() {
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("w1080dp-h1800dp-mdpi")
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        context.deleteDatabase("astra_chats.db")
        prefs.edit().clear().putString("current_chat", "shared-evidence").putBoolean("response_notifications", false).commit()
        context.getSharedPreferences("secure", Context.MODE_PRIVATE).edit().clear().commit()
        val source = File(context.filesDir, "shared-evidence-source.txt").apply { writeText("source") }
        val attachment = OpenRouterLocalAttachment("shared", source.name, "text", source.path, "text/plain",
            source.length(), OpenRouterAttachmentStore.sha256(source), textPath = source.path, textEncoding = "UTF-8")
        val messages = (1..3).map { number ->
            JSONObject().put("role", "assistant").put("request_id", "shared-$number").put("status", "completed")
                .put("text", "Ответ $number").put("tool_trace", JSONArray().put(JSONObject().put("name", "file_read_range")
                    .put("ok", true).put("evidence", JSONObject().put("file_id", "shared").put("offset", 0)
                        .put("next_offset", 1).put("total_chars", 6).put("eof", false))))
        }
        messages.first().put("annotations", JSONArray().put(JSONObject().put("type", "local_file")
            .put("local_path", source.path).put("filename", source.name).put("mime_type", "text/plain")))
        ChatDatabase(context).use { it.upsertCurrentChat("shared-evidence", "Shared", null, null, messages) }
        val calls = AtomicInteger(); val name = AtomicReference("original-source.txt")
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        fun field(key: String) = MainActivity::class.java.getDeclaredField(key).apply { isAccessible = true }.get(activity)
        MainActivity::class.java.getDeclaredField("localEvidenceSourcesHookForTests").apply { isAccessible = true }
            .set(activity, { calls.incrementAndGet(); listOf(attachment.copy(name = name.get())) })
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun panels() = descendants(activity.window.decorView).filterIsInstance<RichResponsePanel>()
        fun settle(expectedName: String) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            val tick = CountDownLatch(1)
            while (System.nanoTime() < end) {
                shadowOf(Looper.getMainLooper()).idle()
                activity.window.decorView.apply {
                    measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
                    layout(0, 0, 1080, 1800)
                }
                if (field("localEvidencePresencePending") == false && panels().size == 3 && panels().all { panel ->
                        !panel.isAsyncParsePending() && !panel.isEvidencePending() && descendants(panel).filterIsInstance<TextView>()
                            .any { it.text.toString().contains(expectedName) }
                    }) return
                tick.await(5, TimeUnit.MILLISECONDS)
            }
            fail("All three real response cards must have the current optional footer")
        }
        try {
            controller.setup(); settle("original-source.txt")
            assertEquals("Three independent response panels must share one source discovery", 1, calls.get())
            MainActivity::class.java.getDeclaredMethod("applyLocalEvidenceEligibility").apply { isAccessible = true }.invoke(activity)
            settle("original-source.txt"); assertEquals(1, calls.get())
            val bodies = panels().map { panel -> descendants(panel).filterIsInstance<TextView>().first { it.text.toString().startsWith("Ответ ") } }
            name.set("updated-source.txt")
            MainActivity::class.java.getDeclaredMethod("refreshLocalEvidenceEligibility").apply { isAccessible = true }.invoke(activity)
            settle("updated-source.txt")
            assertEquals("A new chat revision reloads once, not once per response", 2, calls.get())
            val refreshed = panels().map { panel -> descendants(panel).filterIsInstance<TextView>().first { it.text.toString().startsWith("Ответ ") } }
            bodies.zip(refreshed).forEach { (before, after) -> assertSame("Optional discovery must preserve native body views", before, after) }
            (field("scroll") as RecyclerView).adapter!!.notifyDataSetChanged()
            settle("updated-source.txt")
            assertEquals("Full card rebinds may reuse the current version's metadata", 2, calls.get())
        } finally { controller.pause().stop().destroy(); prefs.edit().clear().commit(); source.delete(); RuntimeEnvironment.setFontScale(1f) }
    }

    @Test fun ordinaryAndForeignFileChatsNeverDiscoverEvidenceEvenAfterFontChange() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        for (foreignFile in listOf(false, true)) {
            RuntimeEnvironment.setFontScale(1f)
            RuntimeEnvironment.setQualifiers("w1080dp-h1800dp-mdpi")
            context.deleteDatabase("astra_chats.db")
            prefs.edit().clear().putString("current_chat", "ordinary")
                .putBoolean("response_notifications", false).commit()
            context.getSharedPreferences("secure", Context.MODE_PRIVATE).edit().clear().commit()
            val source = File(context.filesDir, "foreign-evidence-source.txt").apply { writeText("Foreign chat file") }
            ChatDatabase(context).use { db ->
                db.upsertCurrentChat("ordinary", "Ordinary", null, null, listOf(JSONObject().put("role", "assistant")
                    .put("status", "completed").put("text", "Обычный ответ без файлов.")))
                if (foreignFile) {
                    db.ensureChat("foreign", "Foreign")
                    assertTrue(db.registerLocalArtifact("foreign", "foreign-request", JSONObject().put("path", source.path)
                        .put("filename", source.name).put("mime_type", "text/plain").put("size_bytes", source.length())))
                }
            }
            val calls = AtomicInteger()
            val controller = Robolectric.buildActivity(MainActivity::class.java)
            val activity = controller.get()
            fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
            MainActivity::class.java.getDeclaredField("localEvidenceSourcesHookForTests").apply { isAccessible = true }
                .set(activity, { calls.incrementAndGet(); emptyList<OpenRouterLocalAttachment>() })
            fun settle() {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                val tick = CountDownLatch(1)
                while (System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    activity.window.decorView.apply {
                        measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
                        layout(0, 0, 1080, 1800)
                    }
                    if (field("localEvidencePresencePending") == false && field("pendingScrollRestoreChatId") == null) return
                    tick.await(5, TimeUnit.MILLISECONDS)
                }
                fail("Ordinary response must finish without file discovery; foreign=$foreignFile")
            }
            fun containsEvidence(view: View): Boolean = view is LocalEvidencePanel ||
                view is android.view.ViewGroup && (0 until view.childCount).any { containsEvidence(view.getChildAt(it)) }
            try {
                controller.setup(); settle()
                assertEquals(0, calls.get()); assertEquals(false, field("localEvidenceEligible"))
                assertFalse(containsEvidence(activity.window.decorView))
                RuntimeEnvironment.setFontScale(1.3f)
                MainActivity::class.java.getDeclaredMethod("applyTheme").apply { isAccessible = true }.invoke(activity)
                settle()
                assertEquals(0, calls.get()); assertFalse(containsEvidence(activity.window.decorView))
            } finally {
                controller.pause().stop().destroy(); source.delete(); prefs.edit().clear().commit()
                RuntimeEnvironment.setFontScale(1f)
            }
        }
    }
}
