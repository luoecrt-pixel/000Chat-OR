package com.example.openaichat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClaudeReviewRegressionTest {
    @Test fun rawPromptHasNoEscapedQuotesAndOnlyAdvertisesInstalledRenderers() {
        val active = ResponsePresentationInstructions.text
        assertTrue(active.contains("<file name=\""))
        assertFalse(active.contains("\\\""))
        assertFalse(active.contains("Формулы пиши как"))
        assertFalse(active.contains("Диаграммы оформляй отдельным mermaid"))
        val ready = ResponsePresentationInstructions.forRenderers(math = true, mermaid = true)
        assertTrue(ready.contains("\\(...\\)")); assertFalse(ready.contains("\\\\("))
    }

    @Test fun androidLenientJsonDoesNotTurnOrdinaryTextIntoJsonCode() {
        listOf("[Важно] привет", "{слово} текст", "{\"x\":1} trailing", "[1,]", "{'x':1}").forEach {
            assertFalse(StrictJsonValue.isObjectOrArray(it))
            assertTrue(ResponseBlockParser.parse(it).first() is ResponseBlockParser.Block.Prose)
        }
        assertTrue(StrictJsonValue.isObjectOrArray(" {\"x\":[null,true,1.2e-3,\"😀\"]} \n"))
    }

    @Test fun jsonNullNeverBecomesAnAssistantMessageOrFilePath() {
        val nil = JSONObject().put("role", "assistant").put("text", JSONObject.NULL)
        assertEquals(0, OpenRouterContextSnapshot.build(listOf(nil), null, listOf(emptyList())).length())
        val attachment = OpenRouterLocalAttachment.fromJson(JSONObject().put("path", JSONObject.NULL))
        assertEquals("", attachment.path)
        assertEquals("", JsonProtocol.string(JSONArray().put(JSONObject.NULL), 0))
    }

    @Test fun failedStatusAndUiNoticeSurviveReopenWithoutContaminatingModelText() {
        val context: Context = RuntimeEnvironment.getApplication()
        val chat = "claude-status-review"
        ChatDatabase(context).use { db ->
            db.ensureChat(chat, "Review")
            db.setActiveResponse(chat, null, "failed-review", "", "in_progress", 1, "max", "max", false, 0, 0, true)
            db.writableDatabase.execSQL("INSERT INTO answer_notifications VALUES(?,?,?,?)", arrayOf(chat, "failed-review", 0, 0))
            val answer = JSONObject().put("role", "assistant").put("request_id", "failed-review")
                .put("text", "partial science").put("status", "failed").put("error", "UI failure notice")
            assertTrue(db.finalizeCurrentChatIfActiveRequest(chat, "", null, null, listOf(answer), "failed-review"))
        }
        ChatDatabase(context).use { db ->
            try {
                val answer = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(0)
                assertFalse(ResponseStatusLabel.restored(answer.getString("status")).contains("Готово"))
                val input = OpenRouterContextSnapshot.build(listOf(answer), null, listOf(emptyList())).toString()
                assertTrue(input.contains("partial science")); assertFalse(input.contains("UI failure notice"))
            } finally { db.deleteChat(chat) }
        }
    }

    @Test fun limitsAreWireSafetyAndNeverAnOutputCap() {
        assertThrows(IOException::class.java) { OpenRouterAttachmentPreflight.validateRequestBytes("anthropic/claude-opus-5.5", 32_000_001, OpenRouterClaudeRoute.ANTHROPIC) }
        OpenRouterAttachmentPreflight.validateRequestBytes("anthropic/claude-opus-5.5", 32_000_000, OpenRouterClaudeRoute.ANTHROPIC)
        val trace = ToolTraceSummary.entry("file_read_range", JSONObject(), "{\"ok\":false,\"error\":\"missing\"}")
        assertEquals("ошибка", trace.getString("status"))
    }

    @Test fun requestDebugStringDoesNotSerializeAttachmentsOrExposeCredentials() {
        val file = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "safe-request-review.bin").apply { writeText("bytes") }
        try {
            val messages = JSONArray().put(JSONObject().put("data", FileBackedDataUrl(file, "application/pdf")))
            val request = OpenRouterRuntimeRequest(chatId = "c", requestId = "r", model = "m", messages = messages,
                attachments = emptyList(), effort = "max", verbosity = "", maxTokens = null, github = false,
                githubRepo = "", githubBranches = emptySet(), webSearch = false, webFetch = false, parallel = true,
                cacheMode = "1h", background = false, startedAt = 0, taskClass = "science", effortPref = "max",
                autoSelected = false, promptChars = 0, apiKey = "secret-review", metadataClient = okhttp3.OkHttpClient(), pdfEngine = "native")
            assertTrue(request.toString().contains("requestId=r")); assertFalse(request.toString().contains("secret-review"))
        } finally { file.delete() }
    }

    @Test fun differentFileLocksRunIndependentlyAndSameFileWaitIsCancellable() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val thread = Thread { KeyedFileLocks.with("a", { true }) { entered.countDown(); release.await(5, TimeUnit.SECONDS) } }
        thread.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals("other", KeyedFileLocks.with("b", { true }) { "other" })
            assertThrows(IOException::class.java) { KeyedFileLocks.with("a", { false }) { fail("Cancelled read entered lock") } }
        } finally { release.countDown(); thread.join(5000) }
        assertFalse(KeyedFileLocks.busy("a"))
    }

    @Test fun oldSentNotificationsExpireWhilePendingDeliverySurvives() {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = context.getSharedPreferences("outbox_review", Context.MODE_PRIVATE)
        store.edit().clear().putBoolean("sent:old", true).putLong("sent_at:old", 1)
            .putBoolean("sent:pending", true).putLong("sent_at:pending", 1).putString("pending:pending", "{}").commit()
        AnswerNotificationOutbox.prune(store, 31L * 24 * 60 * 60 * 1000)
        assertFalse(store.contains("sent:old")); assertTrue(store.contains("pending:pending"))
    }

    @Test fun largeLocalFilePagesUseSparseSeekAndKeepAnsiText() {
        val context: Context = RuntimeEnvironment.getApplication()
        val file = java.io.File(context.cacheDir, "indexed-review.log")
        try {
            file.outputStream().buffered().use { out ->
                val block = "abcdef0123456789".repeat(256).toByteArray()
                repeat(5200) { out.write(block) }
                out.write("\u001b[31mОшибка\u001b[0m 😀".toByteArray())
            }
            val pages = LocalTextPageIndex()
            pages.range(file, 0, 24_000) { true }
            val page = pages.range(file, 2_400_000, 24_000) { true }
            assertTrue(page.getLong("index_preceding_chars") <= 4097)
            assertEquals("abcdef0123456789".repeat(1500), page.getString("content"))
            val tail = pages.range(file, 5200L * 4096, 100) { true }
            assertTrue(tail.getString("content").contains("\u001b[31mОшибка")); assertTrue(tail.getBoolean("eof"))
            file.writeText("replacement")
            assertEquals("replacement", pages.range(file, 0, 100) { true }.getString("content"))
        } finally { file.delete() }
    }

    @Test fun reasoningTypesSharingAnIndexKeepSeparateBlocksAndOpaqueFragments() {
        val details = ReasoningDetailAccumulator()
        details.accept(JSONArray().put(JSONObject().put("index", 0).put("type", "reasoning.text").put("text", "a").put("signature", "sig")))
        details.accept(JSONArray().put(JSONObject().put("index", 0).put("type", "reasoning.encrypted").put("data", "opaque")))
        val all = details.array()
        assertEquals(2, all.length()); assertEquals("sig", all.getJSONObject(0).getString("signature"))
        assertEquals("opaque", all.getJSONObject(1).getString("data"))
        assertThrows(IOException::class.java) { details.accept(JSONArray().put(JSONObject().put("index", 0).put("text", "ambiguous"))) }
    }

    @Test fun xlsxDateBooleanAndPhoneticTextAreNotMisrepresentedAsPlainNumbers() {
        val context: Context = RuntimeEnvironment.getApplication()
        val folder = java.io.File(context.cacheDir, "xlsx-review").apply { mkdirs() }
        val file = java.io.File(folder, "science.xlsx")
        try {
            val entries = mapOf(
                "xl/styles.xml" to "<styleSheet><cellXfs><xf numFmtId=\"14\"/></cellXfs></styleSheet>",
                "xl/_rels/workbook.xml.rels" to "<Relationships><Relationship Id=\"r1\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
                "xl/workbook.xml" to "<workbook xmlns:r=\"urn:test\"><sheets><sheet name=\"Data\" r:id=\"r1\"/></sheets></workbook>",
                "xl/sharedStrings.xml" to "<sst><si><t><![CDATA[science]]></t><rPh><t>phonetic</t></rPh></si></sst>",
                "xl/worksheets/sheet1.xml" to "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" s=\"0\"><v>45000</v></c><c r=\"B1\" t=\"b\"><v>1</v></c><c r=\"C1\" t=\"s\"><v>0</v></c></row></sheetData></worksheet>"
            )
            java.util.zip.ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, text) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            } }
            val a = OpenRouterAttachmentStore.classifyLocalFile(file)
            val row = JSONObject(java.io.File(a.textPath!!).readLines().last())
            assertTrue(row.getJSONArray("cells").getJSONObject(0).getBoolean("date_formatted"))
            assertEquals("science", row.getJSONArray("cells").getJSONObject(2).getString("value"))
            val tools = LocalFileTools(listOf(a))
            for (column in 1..2) {
                val result = JSONObject(tools.execute("file_table_stats", JSONObject().put("file_id", a.id).put("column", column)))
                assertFalse(result.getBoolean("ok"))
            }
        } finally { folder.deleteRecursively() }
    }
}
