package com.example.openaichat

import android.app.Application
import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.LinearLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [MainActivityPdfResourceTest.BrokenPdf::class])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityPdfResourceTest {
    @Implements(PdfRenderer::class)
    class BrokenPdf {
        @Implementation fun __constructor__(descriptor: ParcelFileDescriptor) {
            opened = descriptor
            throw IOException("Invalid PDF")
        }
    }

    companion object { @Volatile var opened: ParcelFileDescriptor? = null }
    private var controller: org.robolectric.android.controller.ActivityController<MainActivity>? = null
    private lateinit var file: File

    @Before fun before() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("app", Context.MODE_PRIVATE).edit().clear().putBoolean("response_notifications", false).commit()
        app.deleteDatabase("astra_chats.db")
        file = File(app.cacheDir, "broken-preview.pdf").apply { writeText("invalid PDF") }
        opened = null
    }

    @After fun after() { controller?.destroy(); file.delete(); opened = null }

    @Test fun retainedPdfPreviewEntrypointsCloseDescriptorWhenRendererConstructionFails() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().also { controller = it }.get()
        val executorField = MainActivity::class.java.getDeclaredField("executor").apply { isAccessible = true }
        val previous = executorField.get(activity) as ExecutorService
        previous.shutdown()
        assertTrue(previous.awaitTermination(10, TimeUnit.SECONDS))
        // A single queue lets the barrier await the actual preview finally block.
        val executor = Executors.newSingleThreadExecutor()
        executorField.set(activity, executor)
        fun invoke(name: String, vararg args: Any) {
            MainActivity::class.java.declaredMethods.single { it.name == name }.apply { isAccessible = true }.invoke(activity, *args)
        }
        fun assertClosed() {
            executor.submit {}.get(10, TimeUnit.SECONDS)
            val descriptor = opened ?: fail("PdfRenderer constructor was not reached").let { return }
            assertThrows(IllegalStateException::class.java) { descriptor.fd }
            opened = null
        }
        invoke("showPdfPreview", file.path, file.name, { true })
        assertClosed()
        invoke("addCompactPdfPreview", LinearLayout(activity), file.path, file.name)
        assertClosed()
    }
}
