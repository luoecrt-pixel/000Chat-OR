package com.example.openaichat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
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
import org.robolectric.annotation.SQLiteMode
import java.io.File

/** Recovery operates on persisted protocol owners and real native SQLite transactions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LocalArtifactProtocolReconciliationTest {
    private lateinit var context: Context
    private val files = mutableListOf<File>()
    private val chat = "reconcile-artifact"
    private val request = "reconcile-request"
    private val call = "actual-provider-call"
    private val tool = "create_local_text_file"
    private fun arguments() = JSONObject().put("filename", "result.txt").put("content", "original output\n")
    private fun argumentsHash() = StreamingJsonRequestBody.sha256(arguments())
    private fun user() = JSONObject().put("role", "user").put("provider", "openrouter")
        .put("request_id", request).put("text", "Create a local file")

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }

    @After fun cleanup() {
        files.forEach(File::delete)
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }

    private fun start(db: ChatDatabase) {
        db.ensureChat(chat, "Existing conversation")
        db.upsertCurrentChat(chat, "Existing conversation", null, "high", listOf(user()))
        assertTrue(db.startRequestAccounting(chat, request, "openrouter", "test-model", "inert-key"))
        db.setActiveResponse(chat, null, request, "", "in_progress", 1L,
            "high", "high", false, 0, 0, false)
    }

    private fun publishIntent(db: ChatDatabase, completed: Boolean = true): JSONObject {
        val executor = LocalArtifactToolExecutor(context)
        return executor.prepareTextFile(arguments(), "$chat\u0000$request\u0000$call").use { plan ->
            val intent = db.beginLocalArtifactIntentIfActive(chat, request, call, tool, argumentsHash(), plan.snapshot)!!
            val published = plan.publish()
            files += File(published.getString("path"))
            if (completed) complete(db, intent) else intent
        }
    }

    private fun complete(db: ChatDatabase, intent: JSONObject): JSONObject =
        db.completeLocalArtifactIntent(chat, request, call, tool, argumentsHash(), intent.getString("plan_sha256"),
            intent.getJSONObject("plan").getJSONObject("artifact"))!!

    private fun reconcile(db: ChatDatabase, intent: JSONObject): Boolean =
        db.reconcileLocalArtifactProtocolOutcome(chat, request, call, tool, argumentsHash(), intent.getString("plan_sha256"))

    private fun functionCall(id: String, name: String, args: JSONObject) = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", args.toString()))

    private fun protocol(orderedSiblings: Boolean = false, unknownSibling: Boolean = false,
        opaque: String = "opaque signed bytes\u0000\n"): JSONObject {
        val calls = JSONArray()
        val states = JSONObject()
        if (orderedSiblings) calls.put(functionCall("before", "file_info", JSONObject().put("file_id", "original")))
        calls.put(functionCall(call, tool, arguments()))
        if (orderedSiblings || unknownSibling)
            calls.put(functionCall("after", "file_info", JSONObject().put("file_id", "later")))
        states.put(call, JSONObject().put("state", "outcome_unknown").put("tool_name", tool)
            .put("arguments_sha256", argumentsHash()).put("extra_evidence", "preserve"))
        if (unknownSibling) states.put("after", JSONObject().put("state", "in_flight").put("tool_name", "file_info")
            .put("arguments_sha256", StreamingJsonRequestBody.sha256(JSONObject().put("file_id", "later"))))
        val assistant = JSONObject().put("role", "assistant").put("content", "")
            .put("reasoning_details", JSONArray().put(JSONObject().put("type", "reasoning.encrypted")
                .put("id", "signed-block").put("data", opaque).put("signature", "unaltered signature").put("index", 6)))
            .put("tool_calls", calls).put("future_provider_field", JSONObject().put("preserve", true))
        val tail = JSONArray().put(assistant)
        if (orderedSiblings) {
            tail.put(JSONObject().put("role", "tool").put("tool_call_id", "before").put("content", "{\"ok\":true,\"value\":\"first\"}"))
            tail.put(JSONObject().put("role", "tool").put("tool_call_id", "after").put("content", "{\"ok\":true,\"value\":\"last\"}"))
            tail.put(JSONObject().put("role", "assistant").put("content", "Preserve later assistant"))
        }
        return JSONObject().put("version", 1).put("input_sha256", OpenRouterProtocolCodec.fingerprint(JSONArray()))
            .put("binding_sha256", "unchanged binding").put("tail", tail).put("tool_states", states)
            .put("future_checkpoint_field", JSONObject().put("value", "preserve checkpoint metadata"))
    }

    private fun assistant(protocol: JSONObject) = JSONObject().put("role", "assistant").put("provider", "openrouter")
        .put("request_id", request).put("status", "failed").put("text", "Paid partial")
        .put("model", "test-model").put("usage", JSONObject().put("prompt_tokens", 17))
        .put("openai_protocol_output", JSONArray().put(JSONObject().put("type", "legacy").put("opaque", "preserve legacy")))
        .put("openai_instructions_sha256", "historical metadata")
        .put("openrouter_protocol", protocol)

    private fun saveAssistant(db: ChatDatabase, protocol: JSONObject) {
        db.upsertCurrentChat(chat, "Existing conversation", null, "high", listOf(user(), assistant(protocol)))
    }

    private fun rawCheckpoint(db: ChatDatabase): String = SqliteLargeTextQuery.query(db.readableDatabase,
        "openrouter_checkpoints", arrayOf("protocol"), "chat_id=? AND request_id=?", arrayOf(chat, request),
        null, null, null, "1", arrayOf("chat_id", "request_id")).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }

    private fun rawMessage(db: ChatDatabase): Map<String, String?> = SqliteLargeTextQuery.query(db.readableDatabase,
        "messages", null, "chat_id=? AND request_id=? AND role='assistant'", arrayOf(chat, request),
        null, null, "id DESC", "1").use { c ->
        assertTrue(c.moveToFirst())
        c.columnNames.associateWith { key -> c.getString(c.getColumnIndexOrThrow(key)) }
    }

    private fun rawArchive(db: ChatDatabase): String = SqliteLargeTextQuery.query(db.readableDatabase,
        "request_recovery_archive", arrayOf("recovery_message"), "chat_id=? AND request_id=?", arrayOf(chat, request),
        null, null, null, "1", arrayOf("chat_id", "request_id")).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }

    private fun insertArchive(db: ChatDatabase, value: JSONObject) {
        db.writableDatabase.insertOrThrow("request_recovery_archive", null, ContentValues().apply {
            put("chat_id", chat); put("request_id", request); put("created_at", 7L); put("recovery_message", value.toString())
        })
    }

    private fun assertRepaired(protocol: JSONObject, intent: JSONObject) {
        assertEquals("completed", protocol.getJSONObject("tool_states").getJSONObject(call).getString("state"))
        val tail = protocol.getJSONArray("tail")
        val result = (0 until tail.length()).map { tail.getJSONObject(it) }
            .single { JsonProtocol.nonBlank(it, "tool_call_id") == call }
        assertEquals(LocalArtifactToolExecutor.toolResult(intent.getJSONObject("outcome")).toString(), result.getString("content"))
    }

    @Test fun activeCheckpointRepairsAfterReopenWithoutRepublishingAndKeepsSignedAndSiblingOrder() {
        lateinit var intent: JSONObject
        val original = protocol(orderedSiblings = true)
        val originalTail = original.getJSONArray("tail")
        var modified = 0L
        ChatDatabase(context).use { db ->
            start(db)
            intent = publishIntent(db)
            val file = File(intent.getJSONObject("outcome").getString("path"))
            assertTrue(file.setLastModified(123456000L)); modified = file.lastModified()
            assertTrue(db.saveOpenRouterCheckpoint(chat, request, original))
        }
        ChatDatabase(context).use { db ->
            assertTrue(reconcile(db, intent))
            assertFalse(reconcile(db, intent))
            val repaired = db.getOpenRouterCheckpoint(chat, request)!!
            assertRepaired(repaired, intent)
            val tail = repaired.getJSONArray("tail")
            assertEquals(originalTail.getJSONObject(0).toString(), tail.getJSONObject(0).toString())
            assertEquals(originalTail.getJSONObject(1).toString(), tail.getJSONObject(1).toString())
            assertEquals(call, tail.getJSONObject(2).getString("tool_call_id"))
            assertEquals(originalTail.getJSONObject(2).toString(), tail.getJSONObject(3).toString())
            assertEquals(originalTail.getJSONObject(3).toString(), tail.getJSONObject(4).toString())
            assertEquals(original.getJSONObject("future_checkpoint_field").toString(), repaired.getJSONObject("future_checkpoint_field").toString())
            assertEquals("preserve", repaired.getJSONObject("tool_states").getJSONObject(call).getString("extra_evidence"))
            assertEquals(modified, File(intent.getJSONObject("outcome").getString("path")).lastModified())
            assertTrue(db.isActiveRequest(chat, request))
            assertEquals(1, db.getChat(chat)!!.getJSONArray("messages").length())
        }
    }

    @Test fun historicalFailedRowChangesOnlyProtocolAndFingerprintAndStaleUiCannotUndoRepair() {
        ChatDatabase(context).use { db ->
            start(db)
            val intent = publishIntent(db)
            saveAssistant(db, protocol())
            db.clearActiveResponse(chat)
            val before = rawMessage(db)
            val stale = db.getChat(chat)!!.getJSONArray("messages")
            assertTrue(reconcile(db, intent))
            val after = rawMessage(db)
            assertEquals(before - setOf("openrouter_protocol", "content_hash"), after - setOf("openrouter_protocol", "content_hash"))
            assertNotEquals(before["content_hash"], after["content_hash"])
            val saved = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(1)
            assertEquals(ChatMessageFingerprint.of(saved), after["content_hash"])
            assertRepaired(saved.getJSONObject("openrouter_protocol"), intent)
            assertTrue(db.upsertUiSnapshotPreservingTail(chat, "Existing conversation", null, "high",
                (0 until stale.length()).map { stale.getJSONObject(it) }))
            assertEquals(after, rawMessage(db))
            assertNull(db.getActiveResponse(chat))
            assertEquals("failed", saved.getString("status"))
        }
    }

    @Test fun stopArchiveReceivesKnownOutcomeWithoutRecreatingVisibleMessageOrActiveRequest() {
        ChatDatabase(context).use { db ->
            start(db)
            val intent = publishIntent(db, completed = false)
            assertTrue(db.saveOpenRouterCheckpoint(chat, request, protocol()))
            assertTrue(db.stopActiveRequestOnce(chat, request))
            val before = db.getRequestRecoveryArchive(chat, request)!!
            before.put("legacy_raw_snapshot", "{original raw evidence")
            db.writableDatabase.execSQL("UPDATE request_recovery_archive SET recovery_message=? WHERE chat_id=? AND request_id=?",
                arrayOf(before.toString(), chat, request))
            val messages = db.getChat(chat)!!.getJSONArray("messages").toString()
            val completed = complete(db, intent)
            assertTrue(reconcile(db, completed))
            val after = db.getRequestRecoveryArchive(chat, request)!!
            val beforeOther = JsonProtocol.objectCopy(before).apply { remove("openrouter_protocol") }
            val afterOther = JsonProtocol.objectCopy(after).apply { remove("openrouter_protocol") }
            assertEquals(beforeOther.toString(), afterOther.toString())
            assertRepaired(after.getJSONObject("openrouter_protocol"), completed)
            assertEquals(messages, db.getChat(chat)!!.getJSONArray("messages").toString())
            assertNull(db.getActiveResponse(chat))
            assertNull(db.getOpenRouterCheckpoint(chat, request))
            assertEquals("stopped", db.listRequestAccounting(chat).single().getString("local_status"))
        }
    }

    @Test fun unknownSiblingRemainsUnresolvedAndStillBlocksReplayAfterKnownFileRepair() {
        ChatDatabase(context).use { db ->
            start(db)
            val intent = publishIntent(db)
            val original = protocol(unknownSibling = true)
            assertTrue(db.saveOpenRouterCheckpoint(chat, request, original))
            assertTrue(reconcile(db, intent))
            val repaired = db.getOpenRouterCheckpoint(chat, request)!!
            assertRepaired(repaired, intent)
            assertEquals(original.getJSONObject("tool_states").getJSONObject("after").toString(),
                repaired.getJSONObject("tool_states").getJSONObject("after").toString())
            assertEquals(2, repaired.getJSONArray("tail").length())
            try {
                OpenRouterProtocolCodec.append(JSONArray(), JSONObject().put("openrouter_protocol", repaired))
                fail("An unknown sibling cannot become cancelled or be replayed")
            } catch (_: OpenRouterToolOutcomeUnknownException) { }
        }
    }

    @Test fun deleteAndRecreateSameChatCannotAdoptOldCompletedOutcome() {
        ChatDatabase(context).use { db ->
            start(db)
            val intent = publishIntent(db)
            assertTrue(db.saveOpenRouterCheckpoint(chat, request, protocol()))
            db.deleteChat(chat)
            assertFalse(reconcile(db, intent))
            assertNull(db.getChat(chat))
            start(db)
            assertTrue(db.saveOpenRouterCheckpoint(chat, request, protocol()))
            val raw = rawCheckpoint(db)
            assertFalse(reconcile(db, intent))
            assertEquals(raw, rawCheckpoint(db))
            assertEquals(1, db.getChat(chat)!!.getJSONArray("messages").length())
            assertNull(db.getRequestRecoveryArchive(chat, request))
        }
    }

    @Test fun staleActiveCheckpointAndWrongIdentityCannotBeRepaired() {
        ChatDatabase(context).use { db ->
            start(db)
            val intent = publishIntent(db)
            assertTrue(db.saveOpenRouterCheckpoint(chat, request, protocol()))
            val raw = rawCheckpoint(db)
            db.setActiveResponse(chat, null, "replacement", "", "in_progress", 2L,
                "high", "high", false, 0, 0, false)
            assertFalse(reconcile(db, intent))
            assertFalse(db.reconcileLocalArtifactProtocolOutcome(chat, "wrong-request", call, tool, argumentsHash(), intent.getString("plan_sha256")))
            try {
                db.reconcileLocalArtifactProtocolOutcome(chat, request, call, tool,
                    StreamingJsonRequestBody.sha256("wrong args"), intent.getString("plan_sha256"))
                fail("Arguments identity mismatch must fail closed")
            } catch (_: IllegalStateException) { }
            assertEquals(raw, rawCheckpoint(db))
            assertEquals("replacement", db.getActiveResponse(chat)!!.getString("request_id"))
        }
    }

    @Test fun conflictingArchiveRollsBackEarlierCheckpointAndMessageRepairsWithRawEvidenceIntact() {
        ChatDatabase(context).use { db ->
            start(db)
            val intent = publishIntent(db)
            assertTrue(db.saveOpenRouterCheckpoint(chat, request, protocol()))
            saveAssistant(db, protocol())
            val conflict = protocol()
            conflict.getJSONArray("tail").put(JSONObject().put("role", "tool").put("tool_call_id", call)
                .put("content", "{\"ok\":true,\"different_result\":true}"))
            insertArchive(db, assistant(conflict).put("legacy_raw_snapshot", "{untouched raw"))
            val checkpointBefore = rawCheckpoint(db)
            val messageBefore = rawMessage(db)
            val archiveBefore = rawArchive(db)
            try { reconcile(db, intent); fail("Conflicting archived result must abort the entire SQL transaction") }
            catch (_: OpenRouterToolOutcomeUnknownException) { }
            assertEquals(checkpointBefore, rawCheckpoint(db))
            assertEquals(messageBefore, rawMessage(db))
            assertEquals(archiveBefore, rawArchive(db))
        }
    }

    @Test fun sqliteAbortOnHistoricalWriteRollsBackCheckpointAndPreservesPublishedFile() {
        ChatDatabase(context).use { db ->
            start(db)
            val intent = publishIntent(db)
            assertTrue(db.saveOpenRouterCheckpoint(chat, request, protocol()))
            saveAssistant(db, protocol())
            val checkpointBefore = rawCheckpoint(db)
            val messageBefore = rawMessage(db)
            val target = File(intent.getJSONObject("outcome").getString("path"))
            val modified = target.lastModified()
            db.writableDatabase.execSQL("CREATE TRIGGER deny_protocol_repair BEFORE UPDATE OF openrouter_protocol ON messages BEGIN SELECT RAISE(ABORT,'injected reconciliation failure'); END")
            try { reconcile(db, intent); fail("Expected actual SQLite rollback") }
            catch (_: SQLiteException) { }
            assertEquals(checkpointBefore, rawCheckpoint(db))
            assertEquals(messageBefore, rawMessage(db))
            assertEquals("original output\n", target.readText())
            assertEquals(modified, target.lastModified())
            db.writableDatabase.execSQL("DROP TRIGGER deny_protocol_repair")
            assertTrue(reconcile(db, intent))
        }
    }

    @Test fun missingOrChangedFileNeverRepairsProtocolIntoCompleted() {
        ChatDatabase(context).use { db ->
            start(db)
            val intent = publishIntent(db)
            assertTrue(db.saveOpenRouterCheckpoint(chat, request, protocol()))
            val before = rawCheckpoint(db)
            val target = File(intent.getJSONObject("outcome").getString("path"))
            target.writeText("changed")
            assertFalse(reconcile(db, intent))
            assertEquals(before, rawCheckpoint(db))
            assertTrue(target.delete())
            assertFalse(reconcile(db, intent))
            assertEquals(before, rawCheckpoint(db))
        }
    }

    @Test fun largeSignedProtocolAndLegacyMetadataSurviveBoundedSqliteReadRepairAndReopen() {
        lateinit var intent: JSONObject
        val opaque = "signed data 😀\n".repeat(14_000)
        val original = protocol(opaque = opaque)
        ChatDatabase(context).use { db ->
            start(db)
            intent = publishIntent(db)
            saveAssistant(db, original)
            db.clearActiveResponse(chat)
            db.writableDatabase.execSQL("UPDATE messages SET openai_protocol_output=? WHERE chat_id=? AND role='assistant'",
                arrayOf("{malformed historical raw preserved", chat))
            assertTrue(reconcile(db, intent))
        }
        ChatDatabase(context).use { db ->
            val raw = rawMessage(db)
            assertEquals("{malformed historical raw preserved", raw["openai_protocol_output"])
            val repaired = JSONObject(raw.getValue("openrouter_protocol")!!)
            assertEquals(original.getJSONArray("tail").getJSONObject(0).toString(), repaired.getJSONArray("tail").getJSONObject(0).toString())
            assertEquals(opaque, repaired.getJSONArray("tail").getJSONObject(0).getJSONArray("reasoning_details").getJSONObject(0).getString("data"))
            assertRepaired(repaired, intent)
            assertFalse(reconcile(db, intent))
        }
    }
}
