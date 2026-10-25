package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalFileToolsRegressionTest {
    /** Saved v25 checkpoint fixture: no retired tool executor or network path is invoked. */
    private fun saveLegacyCheckpoint(db: ChatDatabase, chatId: String, requestId: String,
        files: List<OpenRouterLocalAttachment>) {
        assertTrue(db.saveOpenAiToolCheckpoint(chatId, requestId, JSONObject().put("version", 1)
            .put("phase", "idle").put("round", 0)
            .put("attachments", JSONArray().apply { files.forEach { put(it.toJson()) } })
            .put("prior_output", JSONArray()).put("local_artifacts", JSONArray())
            .put("usage", JSONObject()).put(OpenAiUsageLedger.FIELD, OpenAiUsageLedger.empty())))
    }

    private fun stats(a: OpenRouterLocalAttachment, args: JSONObject) = JSONObject(LocalFileTools(listOf(a))
        .execute("file_table_stats", args.put("file_id", a.id)))

    @Test fun fileListIncludesRegisteredArtifactsAndCorruptUtf8FailsClearly() {
        val folder = Files.createTempDirectory("file-list-").toFile()
        try {
            val file = File(folder, "created.txt").apply { writeText("hello") }
            val a = OpenRouterAttachmentStore.classifyLocalFile(file)
            val tools = LocalFileTools(emptyList()); tools.register(a)
            val listed = JSONObject(tools.execute("file_list", JSONObject()))
            assertTrue(listed.getBoolean("ok")); assertTrue(listed.getBoolean("eof"))
            assertEquals(a.id, listed.getJSONArray("files").getJSONObject(0).getString("file_id"))
            File(a.textPath!!).writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            assertFalse(JSONObject(tools.execute("file_read_range", JSONObject().put("file_id", a.id))).getBoolean("ok"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun searchAcrossBufferBoundaryRetainsFullLeftSnippetContextAndHonorsLimitAndCaseInsensitivity() {
        val folder = Files.createTempDirectory("search-boundary-").toFile()
        try {
            val leftContext = "L".repeat(LocalFileTools.SEARCH_LEFT_CONTEXT)
            val query = ("КвантовыйПоиск😀" + "Ω".repeat(25)).take(320)
            val rightContext = "R".repeat(LocalFileTools.SEARCH_RIGHT_CONTEXT)
            // Place the query so its last 10 characters cross the 8192-char reader buffer boundary.
            val prefixLen = 8192 - (query.length - 10) - leftContext.length
            val prefix = "p".repeat(prefixLen)
            val content = prefix + leftContext + query.uppercase() + rightContext + "tail " + query.lowercase()
            val file = File(folder, "boundary-unicode.txt").apply { writeText(content, Charsets.UTF_8) }
            val attachment = OpenRouterAttachmentStore.classifyLocalFile(file)
            val tools = LocalFileTools(listOf(attachment))
            val result = JSONObject(
                tools.execute(
                    "file_search",
                    JSONObject().put("file_id", attachment.id).put("query", query).put("limit", 1)
                )
            )
            assertTrue(result.getBoolean("ok"))
            assertFalse(result.getBoolean("eof"))
            assertFalse(result.getBoolean("all_matches_returned"))
            val matches = result.getJSONArray("matches")
            assertEquals(1, matches.length())
            val match = matches.getJSONObject(0)
            assertEquals((prefix.length + leftContext.length).toLong(), match.getLong("offset"))
            val snippet = match.getString("snippet")
            assertTrue("Boundary match must retain all 100 chars of left context", snippet.startsWith(leftContext + query.uppercase()))
            assertFalse(snippet.first().isLowSurrogate())
            assertFalse(snippet.last().isHighSurrogate())
            val rest = JSONObject(tools.execute("file_search", JSONObject().put("file_id", attachment.id)
                .put("query", query).put("offset", result.getLong("next_offset")).put("limit", 10)))
            assertTrue(rest.getBoolean("all_matches_returned"))
            assertEquals(1, rest.getJSONArray("matches").length())
            val legacy = JSONObject(tools.execute("file_search", JSONObject().put("file_id", attachment.id)
                .put("query", query).put("max_results", 1)))
            assertEquals(1, legacy.getJSONArray("matches").length())
            val pastEnd = JSONObject(tools.execute("file_search", JSONObject().put("file_id", attachment.id)
                .put("query", query).put("offset", content.length + 1)))
            assertTrue(pastEnd.getBoolean("all_matches_returned"))
            assertTrue(pastEnd.getBoolean("eof"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun chatBoundArtifactResolverClassifiesLocalFilesAndRejectsForeignChats() {
        val folder = Files.createTempDirectory("chat-artifacts-").toFile()
        try {
            val cp1251File = File(folder, "report-cp1251.txt").apply {
                writeBytes("Отчёт по эксперименту №1".toByteArray(Charset.forName("windows-1251")))
            }
            val foreignFile = File(folder, "foreign-secret.txt").apply {
                writeText("Чужой чат не должен быть виден", Charsets.UTF_8)
            }
            val localFiles = listOf(
                JSONObject().put("chat_id", "chat-1").put("path", cp1251File.path)
                    .put("filename", cp1251File.name).put("mime_type", "text/plain"),
                JSONObject().put("chat_id", "chat-2").put("path", foreignFile.path)
                    .put("filename", foreignFile.name).put("mime_type", "text/plain")
            )
            val context = RuntimeEnvironment.getApplication()
            val lease = PreparedAttachmentFiles.lease(context)
            val resolved = lease.capture().use {
                assertThrows(IOException::class.java) {
                    LocalArtifactAttachmentResolver.resolve(context, null, "chat-1", localFiles[1])
                }
                listOf(LocalArtifactAttachmentResolver.resolve(context, null, "chat-1", localFiles[0]))
            }
            assertEquals(1, resolved.size)
            val item = resolved.single()
            assertEquals("text", item.kind)
            assertEquals("windows-1251", item.textEncoding)
            assertNotNull(item.textPath)
            val derivedFile = File(item.textPath!!)
            assertEquals("Отчёт по эксперименту №1", derivedFile.readText(Charsets.UTF_8))

            // Legacy local_<filename> alias and canonical file_id both resolve in LocalFileTools:
            val tools = LocalFileTools(resolved)
            val readByCanonical = JSONObject(tools.execute("file_read_range", JSONObject().put("file_id", item.id)))
            assertTrue(readByCanonical.getBoolean("ok"))
            assertEquals("Отчёт по эксперименту №1", readByCanonical.getString("content"))
            val readByLegacyAlias = JSONObject(tools.execute("file_read_range", JSONObject().put("file_id", "local_${cp1251File.name}")))
            assertTrue(readByLegacyAlias.getBoolean("ok"))
            assertEquals("Отчёт по эксперименту №1", readByLegacyAlias.getString("content"))

            // End-to-end checkpoint persistence and Stop cleanup of temporary .utf8 derivative:
            ChatDatabase(context).use { db ->
                db.ensureChat("chat-1", "Чат 1")
                db.setActiveResponse("chat-1", "resp_1", "req_tool_1", "", "in_progress",
                    System.currentTimeMillis(), "high", "high", false, 10, 1, true)
                db.startRequestAccounting("chat-1", "req_tool_1", "openai", "gpt-5.4", "openai_api_key")
                saveLegacyCheckpoint(db, "chat-1", "req_tool_1", resolved)
                val activePaths = db.listLegacyCheckpointAttachmentPaths()
                assertTrue("Active checkpoint must protect .utf8 derivative path", derivedFile.path in activePaths)

                // Release lease while checkpoint is active: .utf8 derivative must stay on disk.
                val activeOwners = PreparedAttachmentFiles.Owners(
                    durable = db.listAllLocalFilePaths().toSet(),
                    active = db.listLegacyCheckpointAttachmentPaths()
                )
                lease.release(activeOwners)
                assertTrue("Derivative must remain while OpenAI tool checkpoint is active", derivedFile.isFile)

                // The production Stop path releases checkpoint ownership and cleans its journal.
                assertTrue(db.stopActiveRequestOnce("chat-1", "req_tool_1", removeUserMessage = true))
                assertFalse("Temporary .utf8 derivative for local artifact must not leak after checkpoint removal", derivedFile.exists())
                assertTrue("Original local artifact file must remain intact", cp1251File.isFile)
            }
        } finally { folder.deleteRecursively() }
    }


    @Test fun ambiguousLegacyAliasesFailClosedWhileCanonicalIdsRemainReadable() {
        val folder = Files.createTempDirectory("ambiguous-files-").toFile()
        try {
            val first = File(folder, "one/report.txt").apply { parentFile!!.mkdirs(); writeText("first") }
            val second = File(folder, "two/report.txt").apply { parentFile!!.mkdirs(); writeText("second") }
            val a = OpenRouterAttachmentStore.classifyLocalFile(first)
            val b = OpenRouterAttachmentStore.classifyLocalFile(second)
            for (tools in listOf(LocalFileTools(listOf(a, b)), LocalFileTools(listOf(a)).apply { register(b) })) {
                val ambiguous = JSONObject(tools.execute("file_read_range", JSONObject().put("file_id", "local_report.txt")))
                assertFalse(ambiguous.getBoolean("ok"))
                assertTrue(ambiguous.getString("error").contains("Неоднозначный"))
                assertEquals("first", JSONObject(tools.execute("file_read_range", JSONObject().put("file_id", a.id))).getString("content"))
                assertEquals("second", JSONObject(tools.execute("file_read_range", JSONObject().put("file_id", b.id))).getString("content"))
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun terminalCommitCleansCheckpointOnlyDerivativeWhileStaleOwnerPreservesIt() {
        val context = RuntimeEnvironment.getApplication()
        val chatId = "artifact-terminal-${System.nanoTime()}"
        val requestId = "request-terminal"
        val original = File(context.filesDir, "generated/$chatId/report.txt").apply {
            parentFile!!.mkdirs()
            writeBytes("Отчёт".toByteArray(Charset.forName("windows-1251")))
        }
        val lease = PreparedAttachmentFiles.lease(context)
        ChatDatabase(context).use { db ->
            try {
                val attachment = lease.capture().use {
                    OpenRouterAttachmentStore.classifyLocalFile(context, original)
                }
                val derivative = File(attachment.textPath!!)
                db.ensureChat(chatId, "Terminal cleanup")
                db.setActiveResponse(chatId, "resp_terminal", requestId, "", "in_progress",
                    System.currentTimeMillis(), "high", "high", false, 1, 1, true)
                db.startRequestAccounting(chatId, requestId, "openai", "gpt-5.4", "openai_api_key")
                saveLegacyCheckpoint(db, chatId, requestId, listOf(attachment))
                lease.release(PreparedAttachmentFiles.Owners(
                    db.listAllLocalFilePaths().toSet(), db.listLegacyCheckpointAttachmentPaths()))
                fun answer(owner: String) = JSONObject().put("role", "assistant").put("provider", "openai")
                    .put("request_id", owner).put("status", "completed").put("text", "Done")
                assertFalse(db.finalizeCurrentChatIfActiveRequest(chatId, "Terminal cleanup", "resp_terminal",
                    "high", listOf(answer("stale-request")), "stale-request"))
                assertTrue("Losing terminal owner must preserve active derivative", derivative.isFile)
                db.writableDatabase.execSQL("""CREATE TRIGGER abort_derivative_terminal
                    BEFORE UPDATE OF active_request_id ON chats
                    WHEN OLD.id='$chatId' AND NEW.active_request_id IS NULL
                    BEGIN SELECT RAISE(ABORT, 'injected terminal failure'); END""")
                try {
                    assertThrows(android.database.sqlite.SQLiteException::class.java) {
                        db.finalizeCurrentChatIfActiveRequest(chatId, "Terminal cleanup", "resp_terminal",
                            "high", listOf(answer(requestId)), requestId)
                    }
                    assertTrue("Rollback must preserve the derivative protected by its checkpoint", derivative.isFile)
                    assertTrue(db.isActiveRequest(chatId, requestId))
                    assertNotNull(db.getOpenAiToolCheckpoint(chatId, requestId))
                } finally {
                    db.writableDatabase.execSQL("DROP TRIGGER abort_derivative_terminal")
                }
                assertTrue(db.finalizeCurrentChatIfActiveRequest(chatId, "Terminal cleanup", "resp_terminal",
                    "high", listOf(answer(requestId)), requestId))
                assertFalse("Winning terminal commit must clean checkpoint-only derivative", derivative.exists())
                assertTrue("Terminal cleanup must preserve imported original", original.isFile)
            } finally {
                lease.defer()
                db.deleteChat(chatId)
                original.parentFile!!.deleteRecursively()
            }
        }
    }

    @Test fun terminalCleanupPreservesAnotherActiveRequestsDerivativeUntilItsOwnStop() {
        val context = RuntimeEnvironment.getApplication()
        val folder = Files.createTempDirectory("parallel-derivatives-").toFile()
        val leases = mutableListOf<PreparedAttachmentFiles.Lease>()
        val chatIds = (1..2).map { "parallel-derivative-$it-${System.nanoTime()}" }
        ChatDatabase(context).use { db ->
            try {
                val originals = (1..2).map { index ->
                    File(folder, "source-$index.txt").apply {
                        writeBytes("Отчёт $index".toByteArray(Charset.forName("windows-1251")))
                    }
                }
                val originalBytes = originals.map { it.readBytes() }
                val attachments = originals.map { original ->
                    val lease = PreparedAttachmentFiles.lease(context).also(leases::add)
                    lease.capture().use { OpenRouterAttachmentStore.classifyLocalFile(context, original) }
                }
                val derivatives = attachments.map { File(it.textPath!!) }
                chatIds.forEachIndexed { index, chatId ->
                    db.ensureChat(chatId, "Parallel cleanup")
                    db.setActiveResponse(chatId, "resp_$index", "req_$index", "", "in_progress",
                        System.currentTimeMillis(), "high", "high", false, 1, 1, true)
                    db.startRequestAccounting(chatId, "req_$index", "openai", "gpt-5.4", "openai_api_key")
                    saveLegacyCheckpoint(db, chatId, "req_$index", listOf(attachments[index]))
                }
                val owners = PreparedAttachmentFiles.Owners(
                    db.listAllLocalFilePaths().toSet(), db.listLegacyCheckpointAttachmentPaths())
                leases.forEach { it.release(owners) }
                assertTrue(derivatives.all { it.isFile })
                val answer = JSONObject().put("role", "assistant").put("provider", "openai")
                    .put("request_id", "req_0").put("status", "completed").put("text", "Done")
                assertTrue(db.finalizeCurrentChatIfActiveRequest(chatIds[0], "Parallel cleanup",
                    "resp_0", "high", listOf(answer), "req_0"))
                assertFalse("The completed request no longer owns its temporary derivative", derivatives[0].exists())
                assertTrue("Another active checkpoint must protect its derivative", derivatives[1].isFile)
                assertTrue(db.isActiveRequest(chatIds[1], "req_1"))
                assertTrue(db.stopActiveRequestOnce(chatIds[1], "req_1", removeUserMessage = true))
                assertFalse("The second derivative is released only after its own Stop", derivatives[1].exists())
                originals.forEachIndexed { index, file -> assertArrayEquals(originalBytes[index], file.readBytes()) }
            } finally {
                leases.forEach { it.defer() }
                chatIds.forEach { db.deleteChat(it) }
                folder.deleteRecursively()
            }
        }
    }

    @Test fun missingArtifactDerivativeIsRebuiltUnderLeaseAndReusedAcrossLocalReopen() {
        val context = RuntimeEnvironment.getApplication()
        val chatId = "artifact-rebuild-${System.nanoTime()}"
        val requestId = "request-rebuild"
        val original = File(context.filesDir, "generated/$chatId/report.txt").apply {
            parentFile!!.mkdirs()
            writeBytes("Отчёт по эксперименту №1".toByteArray(Charset.forName("windows-1251")))
        }
        var db = ChatDatabase(context)
        val leases = mutableListOf<PreparedAttachmentFiles.Lease>()
        try {
            val initialLease = PreparedAttachmentFiles.lease(context).also(leases::add)
            val attachment = initialLease.capture().use { OpenRouterAttachmentStore.classifyLocalFile(context, original) }
            val oldDerivative = File(attachment.textPath!!)
            db.ensureChat(chatId, "Rebuild")
            db.setActiveResponse(chatId, "resp_parent", requestId, "", "in_progress",
                System.currentTimeMillis(), "high", "high", false, 10, 1, true)
            db.startRequestAccounting(chatId, requestId, "openai", "legacy-model", "retired")
            saveLegacyCheckpoint(db, chatId, requestId, listOf(attachment))
            fun owners() = PreparedAttachmentFiles.Owners(db.listAllLocalFilePaths().toSet(), db.listLegacyCheckpointAttachmentPaths())
            initialLease.release(owners())
            assertTrue(oldDerivative.delete())

            val rebuiltLease = PreparedAttachmentFiles.lease(context).also(leases::add)
            val rebuilt = rebuiltLease.capture().use { LocalArtifactAttachmentResolver.resolveForTools(context, attachment) }
            val rebuiltFile = File(rebuilt.textPath!!)
            assertEquals(attachment.id, rebuilt.id)
            assertNotEquals(oldDerivative.path, rebuiltFile.path)
            assertEquals(File(context.filesDir, "attachments").canonicalFile, rebuiltFile.canonicalFile.parentFile)
            assertEquals("Отчёт по эксперименту №1", rebuiltFile.readText(Charsets.UTF_8))
            saveLegacyCheckpoint(db, chatId, requestId, listOf(rebuilt))
            rebuiltLease.release(owners())
            db.close()
            db = ChatDatabase(context)
            val persisted = OpenRouterLocalAttachment.fromJson(db.getOpenAiToolCheckpoint(chatId, requestId)!!
                .getJSONArray("attachments").getJSONObject(0))
            repeat(2) {
                val resolved = LocalArtifactAttachmentResolver.resolveForTools(context, persisted)
                assertEquals(rebuiltFile.path, resolved.textPath)
                val result = JSONObject(LocalFileTools(listOf(resolved)).execute("file_read_range",
                    JSONObject().put("file_id", resolved.id)))
                assertTrue(result.getBoolean("ok"))
                assertEquals("Отчёт по эксперименту №1", result.getString("content"))
            }
            assertTrue(rebuiltFile.path in db.listLegacyCheckpointAttachmentPaths())
            assertTrue(db.stopActiveRequestOnce(chatId, requestId, removeUserMessage = true))
            assertFalse("Temporary rebuilt derivative must be removed after Stop", rebuiltFile.exists())
            assertTrue("Original generated artifact must survive", original.isFile)
        } finally {
            leases.forEach { it.defer() }
            db.deleteChat(chatId)
            db.close()
            original.parentFile!!.deleteRecursively()
        }
    }

    @Test fun oversizedOriginalPhotoFailsBeforeNetworkEncoding() {
        val folder = Files.createTempDirectory("claude-photo-").toFile()
        try {
            val file = File(folder, "large.jpg")
            java.io.RandomAccessFile(file, "rw").use { it.setLength(8_000_000L) }
            val a = OpenRouterLocalAttachment("large", file.name, "image", file.path, "image/jpeg", file.length(), "")
            val failure = assertThrows(java.io.IOException::class.java) { OpenRouterAttachmentPreflight.validate("anthropic/claude-sonnet-5.5", listOf(a)) }
            assertTrue(failure.message!!.contains("Base64")); assertTrue(file.exists())
        } finally { folder.deleteRecursively() }
    }

    @Test fun changedSourceDoesNotReuseItsOldDescriptorOrThinkingPrefix() {
        val folder = Files.createTempDirectory("file-integrity-").toFile()
        try {
            val file = File(folder, "data.txt").apply { writeText("original") }
            val a = OpenRouterAttachmentStore.classifyLocalFile(file)
            file.writeText("changed")
            assertThrows(java.io.IOException::class.java) { OpenRouterAttachmentPreflight.verifySources(listOf(a)) }
        } finally { folder.deleteRecursively() }
    }

    @Test fun csvProcessesAllRowsAndQuotedNewlines() {
        val folder = Files.createTempDirectory("csv-all-").toFile()
        try {
            val file = File(folder, "data.csv").apply { writeText("value,note\n" + "1,ok\n".repeat(1500) + "10000,late\n") }
            val a = OpenRouterAttachmentStore.classifyLocalFile(file)
            val result = stats(a, JSONObject().put("column", 1).put("start_row", 2))
            assertTrue(result.getBoolean("ok")); assertEquals(1501L, result.getLong("numeric_count"))
            assertEquals("11500", result.getString("sum_exact")); assertTrue(result.getBoolean("complete_requested_range"))
            file.writeText("value,note\n1,\"two\nlines\"\n2,\"escaped \"\"quote\"\"\"\n")
            val quoted = OpenRouterAttachmentStore.classifyLocalFile(file)
            assertEquals("3", stats(quoted, JSONObject().put("column", 1).put("start_row", 2)).getString("sum_exact"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun malformedCsvAndUnknownDevicePathFailClearly() {
        val folder = Files.createTempDirectory("csv-fail-").toFile()
        try {
            val file = File(folder, "broken.csv").apply { writeText("\"1\"2\n") }
            val a = OpenRouterAttachmentStore.classifyLocalFile(file)
            assertFalse(stats(a, JSONObject().put("column", 1)).getBoolean("ok"))
            val unauthorized = JSONObject(LocalFileTools(listOf(a)).execute("file_read_range",
                JSONObject().put("file_id", "/data/data/private-secret").put("limit", 100)))
            assertFalse(unauthorized.getBoolean("ok"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun xlsxRetainsLateRowsEmptySheetsAndFormulaWithoutInventingItsValue() {
        val folder = Files.createTempDirectory("xlsx-all-").toFile()
        try {
            val file = File(folder, "all.xlsx")
            ZipOutputStream(file.outputStream()).use { zip ->
                fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
                entry("xl/_rels/workbook.xml.rels", """<Relationships><Relationship Id="r1" Target="worksheets/sheet1.xml"/><Relationship Id="r2" Target="worksheets/sheet2.xml"/></Relationships>""")
                entry("xl/workbook.xml", """<workbook xmlns:r="urn:relationships"><sheets><sheet name="Data" r:id="r1"/><sheet name="Empty" r:id="r2"/></sheets></workbook>""")
                val rows = (1..1500).joinToString("") { "<row r=\"$it\"><c r=\"A$it\"><v>1</v></c></row>" }
                entry("xl/worksheets/sheet1.xml", "<worksheet><sheetData>$rows<row r=\"1501\"><c r=\"A1501\"><f>1+9999</f><v>10000</v></c><c r=\"B1501\"><f>1+1</f></c></row></sheetData></worksheet>")
                entry("xl/worksheets/sheet2.xml", "<worksheet><sheetData/></worksheet>")
            }
            val a = OpenRouterAttachmentStore.classifyLocalFile(file)
            val result = stats(a, JSONObject().put("column", 1).put("sheet", "Data"))
            assertTrue(result.getBoolean("ok")); assertEquals("11500", result.getString("sum_exact"))
            assertEquals(1501L, result.getLong("numeric_count"))
            assertEquals(0L, stats(a, JSONObject().put("column", 1).put("sheet", "Empty")).getLong("rows_scanned"))
            assertFalse(stats(a, JSONObject().put("column", 1).put("sheet", "Unknown")).getBoolean("ok"))
            assertFalse(stats(a, JSONObject().put("column", 2).put("sheet", "Data").put("start_row", 1501)).getBoolean("ok"))
            val text = File(a.textPath!!).readText()
            assertTrue(text.contains("A1501")); assertTrue(text.contains("1+9999"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun sharedFormulaWithoutCachedValueIsNotSilentlyCountedAsAnEmptyCell() {
        val folder = Files.createTempDirectory("xlsx-shared-formula-").toFile()
        try {
            val file = File(folder, "shared.xlsx")
            ZipOutputStream(file.outputStream()).use { zip ->
                fun entry(name: String, xml: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(xml.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                entry("xl/_rels/workbook.xml.rels", """<Relationships><Relationship Id="r1" Target="worksheets/sheet1.xml"/></Relationships>""")
                entry("xl/workbook.xml", """<workbook xmlns:r="urn:relationships"><sheets><sheet name="Shared" r:id="r1"/></sheets></workbook>""")
                entry("xl/worksheets/sheet1.xml", """<worksheet><sheetData>
                    <row r="1"><c r="A1"><f t="shared" si="0" ref="A1:A3">ROW()</f><v>1</v></c></row>
                    <row r="2"><c r="A2"><f t="shared" si="0"/></c></row>
                    <row r="3"><c r="A3"><f t="shared" si="0"/><v>3</v></c></row>
                    </sheetData></worksheet>""".trimIndent())
            }
            val a = OpenRouterAttachmentStore.classifyLocalFile(file)
            val strict = stats(a, JSONObject().put("column", 1).put("sheet", "Shared"))
            assertFalse(strict.getBoolean("ok"))
            val nonStrict = stats(a, JSONObject().put("column", 1).put("sheet", "Shared").put("strict", false))
            assertTrue(nonStrict.getBoolean("ok"))
            assertEquals(1L, nonStrict.getLong("invalid_cells"))
            assertEquals(0L, nonStrict.getLong("missing_cells"))
            assertEquals("4", nonStrict.getString("sum_exact"))
            val lines = File(a.textPath!!).readLines().map { JSONObject(it) }
            val follower = lines.first { it.optLong("row") == 2L }.getJSONArray("cells").getJSONObject(0)
            assertTrue(follower.has("formula"))
            assertEquals("", follower.getString("formula"))
            assertEquals("shared", follower.getString("formula_t"))
            assertEquals("0", follower.getString("formula_si"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun blankHistoricalPathDoesNotSilentlyDropAnUploadedFile() {
        val message = JSONObject().put("role", "user").put("text", "Use my dataset")
            .put("file_ids", JSONArray().put("file_123")).put("file_paths", JSONArray().put(""))
        assertThrows(java.io.IOException::class.java) { AttachmentHistoryPolicy.validate(message) }
    }


}
