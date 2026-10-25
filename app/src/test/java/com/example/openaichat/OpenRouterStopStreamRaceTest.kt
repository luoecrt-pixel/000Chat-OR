package com.example.openaichat

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.os.Looper
import android.widget.EditText
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Real HTTP/SSE runtime with barriers between parsed events, never sleeps to win a race. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OpenRouterStopStreamRaceTest {
    private lateinit var context: Context
    private val chat = "stop-stream"
    private val request = "stop-stream-request"
    private val allowSecond = CountDownLatch(1)
    private val afterFirst = CountDownLatch(1)
    private val afterSecond = CountDownLatch(1)
    private val allowEnd = CountDownLatch(1)
    private val clock = AtomicLong(1L)
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("app", 0).edit().clear().putBoolean("response_notifications", false).commit()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
        ChatDatabase(context).use { db ->
            db.ensureChat(chat, "Stream")
            db.setActiveResponse(chat, null, request, "", "in_progress", System.currentTimeMillis(), "high", "high", false, 0, 0, false)
            assertTrue(db.startRequestAccounting(chat, request, "openrouter", "anthropic/claude-test", "openrouter_api_key"))
        }
        server = MockWebServer().apply { start() }
    }

    @After fun teardown() {
        allowSecond.countDown(); allowEnd.countDown()
        OpenRouterResponseRuntime.cancel(request)
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (OpenRouterResponseRuntime.contains(request) && System.nanoTime() < until) Thread.yield()
        assertFalse("Runtime must exit without another HTTP request", OpenRouterResponseRuntime.contains(request))
        server.shutdown()
        if (::http.isInitialized) { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    }

    private fun frame(text: String, signature: String) = "data: " + JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("delta", JSONObject().put("content", text).put("reasoning_details", JSONArray().put(JSONObject()
            .put("index", 0).put("type", "reasoning.encrypted").put("data", "opaque-$signature").put("signature", signature)))))) + "\n\n"

    private fun start(first: String = "prefix ", second: String = "последние 字🙂") {
        val a = frame(first, "sig-A").toByteArray(Charsets.UTF_8)
        val b = frame(second, "sig-B").toByteArray(Charsets.UTF_8)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(Buffer().write(a).write(b).writeUtf8("data: [DONE]\n\n")))
        http = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body!!
            val source = object : ForwardingSource(body.source()) {
                var delivered = 0L
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (delivered == a.size.toLong()) { afterFirst.countDown(); check(allowSecond.await(10, TimeUnit.SECONDS)) }
                    if (delivered == (a.size + b.size).toLong()) { afterSecond.countDown(); check(allowEnd.await(10, TimeUnit.SECONDS)) }
                    val boundary = if (delivered < a.size) a.size.toLong() else if (delivered < a.size + b.size) (a.size + b.size).toLong() else Long.MAX_VALUE
                    return super.read(sink, minOf(byteCount, boundary - delivered)).also { if (it > 0) delivered += it }
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source(): BufferedSource = source
            }).build()
        }.build()
        val client = OpenRouterChatClient(server.url("/api/v1").toString(), http, reconcileGenerations = false, monotonicNanos = clock::get)
        val r = OpenRouterRuntimeRequest(chat, request, "anthropic/claude-test",
            JSONArray().put(JSONObject().put("role", "user").put("content", "Continue")), emptyList(), "high", "", null,
            false, "", emptySet(), false, false, false, "off", false, SystemClock.elapsedRealtime(), "general", "high", false,
            8, "synthetic-key", http, "native")
        OpenRouterResponseRuntime.start(context, r, client)
        assertTrue("First SSE event parsed", afterFirst.await(10, TimeUnit.SECONDS))
    }

    private fun streamFence(): Any {
        val requests = OpenRouterResponseRuntime::class.java.getDeclaredField("work").apply { isAccessible = true }
            .get(OpenRouterResponseRuntime) as Map<*, *>
        val item = requests[request]!!
        return (item.javaClass.getDeclaredField("stream").apply { isAccessible = true }.get(item) as OpenRouterStreamState).lock
    }

    private fun restoreLaggingDurableText() = ChatDatabase(context).use { db ->
        // Model a throttled text snapshot under the real writer fence, so even
        // a slow CI runner cannot let the trailing timer erase the test boundary.
        db.writableDatabase.execSQL("UPDATE chats SET active_text=? WHERE id=? AND active_request_id=?",
            arrayOf("prefix ", chat, request))
    }

    @Test fun stopAtomicallyArchivesNewestUnflushedUnicodeAndSignedFragmentsAfterReopen() {
        start()
        allowSecond.countDown()
        assertTrue("Second SSE event parsed, next read blocked", afterSecond.await(10, TimeUnit.SECONDS))
        synchronized(streamFence()) {
            restoreLaggingDurableText()
            assertTrue(OpenRouterResponseRuntime.stop(context, chat, request, removeUserMessage = true))
        }
        assertFalse(OpenRouterResponseRuntime.stop(context, chat, request, removeUserMessage = true))
        ChatDatabase(context).use { db ->
            assertNull(db.getActiveResponse(chat))
            val archive = db.getRequestRecoveryArchive(chat, request)!!
            assertEquals("prefix последние 字🙂", archive.getString("text"))
            val protocol = archive.getJSONObject("openrouter_protocol")
            val interrupted = protocol.getJSONObject("interrupted_assistant")
            assertEquals("sig-Asig-B", interrupted.getJSONArray("reasoning_details").getJSONObject(0).getString("signature"))
            assertEquals(2, interrupted.getJSONArray("reasoning_delta_fragments").length())
            assertFalse(interrupted.getBoolean("replay_safe"))
            val next = JSONArray()
            assertTrue(OpenRouterProtocolCodec.append(next, archive))
            for (i in 0 until next.length()) assertFalse(next.getJSONObject(i).has("reasoning_details"))
            assertEquals(0, db.getChat(chat)!!.getJSONArray("messages").length())
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun reasoningOnlySseIsDurableBeforeCompletionAndReopensWithExactFragments() {
        start("", "")
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(600))
        allowSecond.countDown()
        assertTrue(afterSecond.await(10, TimeUnit.SECONDS))
        ChatDatabase(context).use { db ->
            assertEquals("", db.getActiveResponse(chat)!!.getString("text"))
            val checkpoint = db.getOpenRouterCheckpoint(chat, request)!!
            assertEquals("sig-Asig-B", checkpoint.getJSONObject("interrupted_assistant")
                .getJSONArray("reasoning_details").getJSONObject(0).getString("signature"))
            assertEquals(0, checkpoint.getJSONArray("tail").length())
        }
        assertTrue(OpenRouterResponseRuntime.stop(context, chat, request, false))
        assertEquals(1, server.requestCount)
    }

    @Test fun trailingSmallFragmentBecomesDurableWhileTheSocketStaysIdle() {
        start()
        allowSecond.countDown()
        assertTrue(afterSecond.await(10, TimeUnit.SECONDS))
        // The injected inference clock stays fixed: only the trailing writer
        // can persist this delta while the real HTTP read remains blocked.
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var durable = false
        while (System.nanoTime() < until && !durable) {
            ChatDatabase(context).use { db ->
                durable = db.getActiveResponse(chat)?.optString("text") == "prefix последние 字🙂"
            }
            if (!durable) Thread.sleep(10)
        }
        assertTrue("The last small event must not remain volatile indefinitely", durable)
        ChatDatabase(context).use { db ->
            assertEquals("sig-Asig-B", db.getOpenRouterCheckpoint(chat, request)!!
                .getJSONObject("interrupted_assistant").getJSONArray("reasoning_details").getJSONObject(0).getString("signature"))
            assertTrue(OpenRouterResponseRuntime.contains(request))
        }
        assertEquals(1L, allowEnd.count)
        assertTrue(OpenRouterResponseRuntime.stop(context, chat, request, false))
        assertEquals(1, server.requestCount)
    }

    @Test fun failedStopTransactionKeepsOwnerAndPriorSnapshotAndAllowsSafeRetryWithoutPost() {
        start()
        allowSecond.countDown(); assertTrue(afterSecond.await(10, TimeUnit.SECONDS))
        synchronized(streamFence()) {
            restoreLaggingDurableText()
            ChatDatabase(context).use { db -> db.writableDatabase.execSQL("""CREATE TRIGGER fail_stop BEFORE INSERT ON request_recovery_archive
                BEGIN SELECT RAISE(ABORT, 'controlled Stop failure'); END""") }
            assertThrows(android.database.sqlite.SQLiteException::class.java) { OpenRouterResponseRuntime.stop(context, chat, request, false) }
            ChatDatabase(context).use { db ->
                assertEquals("prefix ", db.getActiveResponse(chat)!!.getString("text"))
                assertNull(db.getRequestRecoveryArchive(chat, request))
                db.writableDatabase.execSQL("DROP TRIGGER fail_stop")
            }
            assertTrue(OpenRouterResponseRuntime.stop(context, chat, request, false))
        }
        ChatDatabase(context).use { assertEquals("prefix последние 字🙂", it.getRequestRecoveryArchive(chat, request)!!.getString("text")) }
        assertEquals(1, server.requestCount)
    }

    @Test fun editUsesAtomicStopAndArchiveFailureCannotTruncateHistoryOrChangeDraft() {
        ChatDatabase(context).use { db -> db.upsertCurrentChat(chat, "Stream", null, null,
            listOf(JSONObject().put("role", "user").put("provider", "openrouter")
                .put("request_id", request).put("text", "Original question"))) }
        context.getSharedPreferences("app", 0).edit().putString("current_chat", chat)
            .putString("model", "anthropic/claude-test").commit()
        start()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val input = MainActivity::class.java.getDeclaredField("input").apply { isAccessible = true }.get(activity) as EditText
            input.setText("Existing draft")
            val generation = activity.activeGenerationForTesting()
            allowSecond.countDown(); assertTrue(afterSecond.await(10, TimeUnit.SECONDS))
            val edit = MainActivity::class.java.getDeclaredMethod("editMessage", Int::class.javaPrimitiveType).apply { isAccessible = true }
            synchronized(streamFence()) {
                restoreLaggingDurableText()
                ChatDatabase(context).use { db -> db.writableDatabase.execSQL("""CREATE TRIGGER fail_edit_stop
                    BEFORE INSERT ON request_recovery_archive BEGIN SELECT RAISE(ABORT, 'controlled edit failure'); END""") }
                edit.invoke(activity, 0)
                assertEquals(generation, activity.activeGenerationForTesting())
                assertEquals("Existing draft", input.text.toString())
                ChatDatabase(context).use { db ->
                    assertEquals(request, db.getActiveResponse(chat)!!.getString("request_id"))
                    assertEquals(1, db.getChat(chat)!!.getJSONArray("messages").length())
                    assertNull(db.getRequestRecoveryArchive(chat, request))
                    db.writableDatabase.execSQL("DROP TRIGGER fail_edit_stop")
                }
                edit.invoke(activity, 0)
            }
            assertEquals("Original question", input.text.toString())
            ChatDatabase(context).use { db ->
                assertNull(db.getActiveResponse(chat))
                assertEquals(0, db.getChat(chat)!!.getJSONArray("messages").length())
                val archive = db.getRequestRecoveryArchive(chat, request)!!
                assertEquals("prefix последние 字🙂", archive.getString("text"))
                assertEquals("sig-Asig-B", archive.getJSONObject("openrouter_protocol").getJSONObject("interrupted_assistant")
                    .getJSONArray("reasoning_details").getJSONObject(0).getString("signature"))
            }
            assertEquals(1, server.requestCount)
        } finally {
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun deletedChatRejectsLateSnapshotAndStopCannotRecreateIt() {
        start()
        allowSecond.countDown(); assertTrue(afterSecond.await(10, TimeUnit.SECONDS))
        ChatDatabase(context).use { it.deleteChat(chat) }
        assertFalse(OpenRouterResponseRuntime.stop(context, chat, request, false))
        ChatDatabase(context).use { assertNull(it.getChat(chat)); assertNull(it.getRequestRecoveryArchive(chat, request)) }
        assertEquals(1, server.requestCount)
    }
}
