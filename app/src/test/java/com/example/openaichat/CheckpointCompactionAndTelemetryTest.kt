package com.example.openaichat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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

/** Exercises actual SQLite transactions; triggers inject write failures after the assistant insert. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CheckpointCompactionAndTelemetryTest {
    private lateinit var context: Context
    private val chat = "compact-chat"
    private val request = "local_compact"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }

    @After fun cleanup() {
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }

    private fun usage(input: Long, output: Long = 0L, cached: Long = 0L, reasoning: Long = 0L) =
        JSONObject().put("input_tokens", input).put("output_tokens", output)
            .put("input_tokens_details", JSONObject().put("cached_tokens", cached))
            .put("output_tokens_details", JSONObject().put("reasoning_tokens", reasoning))

    private fun raw(id: String, text: String, input: Long) = JSONObject()
        .put("id", id).put("model", "gpt-test").put("status", "completed").put("usage", usage(input))
        .put("output", JSONArray().put(JSONObject().put("type", "message").put("role", "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))))

    private fun prior(payload: String = "paid result") = JSONArray()
        .put(JSONObject().put("type", "reasoning").put("encrypted_content", "signed-preserve-me"))
        .put(JSONObject().put("type", "function_call").put("call_id", "call_1")
            .put("name", "read_attached_file").put("arguments", "{}"))
        .put(JSONObject().put("type", "function_call_output").put("call_id", "call_1").put("output", payload))

    private fun prepared(db: ChatDatabase, responseId: String = "resp_child", provider: String = "openai") {
        db.ensureChat(chat, "Compaction")
        db.upsertCurrentChat(chat, "Compaction", null, null, listOf(
            JSONObject().put("role", "user").put("text", "Question").put("request_id", request)))
        db.setActiveResponse(chat, responseId, request, "", "in_progress", 100L,
            "high", "high", false, 8, 1, true)
        assertTrue(db.startRequestAccounting(chat, request, provider, "gpt-test", "test-key-slot"))
    }

    private fun checkpoint(payload: String = "paid result", legacy: Boolean = false): JSONObject {
        val checkpoint = JSONObject().put("version", 1).put("phase", "waiting").put("round", 1)
            .put("parent_id", "resp_parent").put("response_id", "resp_child")
            .put("usage", usage(100, 10, 30, 4)).put("prior_output", prior(payload))
            .put("telemetry", JSONObject().put("rounds_completed", 1).put("calls_executed", 1)
                .put("executed_tools", JSONArray().put("read_attached_file")))
        if (!legacy) checkpoint.put(OpenAiUsageLedger.FIELD,
            OpenAiUsageLedger.observe(null, "resp_parent", usage(100, 10, 30, 4), "completed"))
        return checkpoint
    }

    private fun assistant(responseId: String = "resp_child", text: String = "Answer", input: Long? = null) =
        JSONObject().put("role", "assistant").put("provider", "openai").put("request_id", request)
            .put("response_id", responseId).put("status", "completed").put("text", text).apply {
                input?.let { put("usage", usage(it)) }
            }

    private fun finish(db: ChatDatabase, message: JSONObject = assistant()) =
        db.finalizeCurrentChatIfActiveRequest(chat, "Compaction", "resp_child", null, listOf(message), request)

    private fun account(db: ChatDatabase) = db.listRequestAccounting(chat).single()

    @Test fun largeFinishedCheckpointTransfersFullOutputAndAuthoritativeUsageBeforeCompactionAndReopen() {
        // Multi-byte Unicode forces >2 MB UTF-8 and crosses 32 KB byte-slice boundaries.
        val payload = "\uD83D\uDE80Пример\u0000".repeat(230_000)
        assertTrue(payload.toByteArray(Charsets.UTF_8).size > 2_200_000)
        ChatDatabase(context).use { db ->
            prepared(db)
            val finalResponse = raw("resp_child", "Full paid final text", 150)
                .put("output", prior(payload).put(raw("ignored", "Full paid final text", 1)
                    .getJSONArray("output").getJSONObject(0)))
            val saved = checkpoint(payload, legacy = true).put("phase", "finished")
                .put("final_response", finalResponse)
            assertTrue(db.saveOpenAiToolCheckpoint(chat, request, saved))
            assertTrue(finish(db, assistant(text = "", input = 50)))
            assertNull(db.getOpenAiToolCheckpoint(chat, request))
            assertNull(db.getActiveResponse(chat))
        }
        ChatDatabase(context).use { db ->
            val message = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(1)
            assertEquals("Full paid final text", message.getString("text"))
            assertEquals(payload, message.getJSONArray("openai_protocol_output").getJSONObject(2).getString("output"))
            assertEquals("signed-preserve-me", message.getJSONArray("openai_protocol_output").getJSONObject(0)
                .getString("encrypted_content"))
            assertEquals(150L, message.getJSONObject("usage").getLong("input_tokens"))
            assertEquals(150L, account(db).getLong("input_tokens"))
            assertEquals(1, message.getJSONObject("telemetry_summary").getInt("calls_executed"))
        }
    }

    @Test fun markedAggregateCannotBeReattributedToChildDuringFinalizationOrLateAccounting() {
        ChatDatabase(context).use { db ->
            prepared(db)
            val saved = checkpoint()
            assertTrue(db.saveOpenAiToolCheckpoint(chat, request, saved))
            assertTrue(db.recordRequestTelemetry(chat, request, JSONObject().put("files", 1)
                .put("source_read_passes", 2).put("source_read_bytes", 4096)))
            val combined = OpenAiUsageLedger.resolve(saved, raw("resp_child", "Answer", 20))
            val message = assistant().put(OpenAiUsageLedger.FIELD, combined)
                .put("usage", OpenAiUsageLedger.total(combined))
                .put("telemetry_summary", JSONObject().put(OpenAiUsageLedger.FIELD, combined))
            assertTrue(finish(db, message))
            assertEquals(120L, account(db).getLong("input_tokens"))
            assertEquals(4096L, db.getChat(chat)!!.getJSONArray("messages").getJSONObject(1)
                .getJSONObject("telemetry_summary").getLong("source_read_bytes"))
            repeat(2) {
                assertTrue(db.recordRequestAccounting(chat, request, "completed", responseId = "resp_child",
                    usage = message.getJSONObject("usage"), telemetrySummary = message.getJSONObject("telemetry_summary")))
                assertEquals(120L, account(db).getLong("input_tokens"))
            }
            // An unmarked raw parent callback enriches its own entry, not the aggregate.
            assertTrue(db.recordRequestAccounting(chat, request, "completed", responseId = "resp_parent", usage = usage(100)))
            assertEquals(120L, account(db).getLong("input_tokens"))
            assertEquals("resp_child", account(db).getString("response_id"))
            assertTrue(db.recordRequestAccounting(chat, request, "completed", usage = usage(20)))
            assertEquals(120L, account(db).getLong("input_tokens"))
        }
    }

    @Test fun stopArchivesPaidOutputAndLegacyUsageThenRepeatedLateChildIsExactly120() {
        ChatDatabase(context).use { db ->
            prepared(db)
            assertTrue(db.saveOpenAiToolCheckpoint(chat, request, checkpoint(legacy = true)))
            assertTrue(db.stopActiveRequestOnce(chat, request))
            assertNull(db.getOpenAiToolCheckpoint(chat, request))
            assertFalse(db.stopActiveRequestOnce(chat, request))
            assertEquals(1, db.getChat(chat)!!.getJSONArray("messages").length())
            val archive = db.getRequestRecoveryArchive(chat, request)!!
            assertEquals("paid result", archive.getJSONArray("openai_protocol_output").getJSONObject(2).getString("output"))
            assertEquals(100L, account(db).getLong("input_tokens"))
            assertEquals(100L, db.listRequestEvents(chat).single().getLong("input_tokens"))
        }
        ChatDatabase(context).use { db ->
            val late = JSONObject().put("id", "resp_child").put("status", "cancelled").put("usage", usage(20, 5, 2, 1))
            repeat(2) {
                assertTrue(db.reconcileStoppedOpenAiResponse(chat, request, "resp_child", late))
                val account = account(db)
                assertEquals("stopped", account.getString("local_status"))
                assertEquals(120L, account.getLong("input_tokens"))
                assertEquals(15L, account.getLong("output_tokens"))
                assertEquals(32L, account.getLong("cached_tokens"))
                assertEquals(5L, account.getLong("reasoning_tokens"))
                assertEquals(120L, db.listRequestEvents(chat).single().getLong("input_tokens"))
            }
            val stale = OpenAiUsageLedger.observe(OpenAiUsageLedger.fromCheckpoint(checkpoint(legacy = true)),
                "resp_child", usage(10), "cancelled")
            assertTrue(db.recordRequestAccounting(chat, request, "completed", responseId = "resp_child",
                usage = OpenAiUsageLedger.total(stale), telemetrySummary = JSONObject().put(OpenAiUsageLedger.FIELD, stale)))
            assertEquals(120L, account(db).getLong("input_tokens"))
            // The initial parent may be unknown in a multi-round legacy aggregate; no foreign ID may
            // be invented from an asynchronous raw callback once its owning request has stopped.
            assertTrue(db.recordRequestAccounting(chat, request, "completed", responseId = "resp_unknown_old",
                usage = usage(100)))
            assertEquals(120L, account(db).getLong("input_tokens"))
            assertEquals("resp_child", account(db).getString("response_id"))
            assertFalse(db.reconcileStoppedOpenAiResponse(chat, request, "resp_other", late))
            assertFalse(finish(db))
            db.recordRequestEvent(chat, "request_completed", request, "openai", "gpt-test", "gpt-test",
                ModelCatalog.Match.EXACT, "completed", inputTokens = 20)
            assertEquals(1, db.listRequestEvents(chat).size)
            assertEquals(1, db.getChat(chat)!!.getJSONArray("messages").length())
            db.deleteChat(chat)
            assertNull(db.getRequestRecoveryArchive(chat, request))
            assertFalse(db.reconcileStoppedOpenAiResponse(chat, request, "resp_child", late))
        }
    }

    @Test fun paidFinalResponseSurvivesNoAssistantFailureClaim() {
        ChatDatabase(context).use { db ->
            prepared(db)
            val saved = checkpoint(legacy = true).put("phase", "finished")
                .put("final_response", raw("resp_child", "Paid final answer", 150))
            assertTrue(db.saveOpenAiToolCheckpoint(chat, request, saved))
            assertTrue(db.claimActiveRequestFailure(chat, request))
            assertFalse(db.claimActiveRequestFailure(chat, request))
            assertNull(db.getOpenAiToolCheckpoint(chat, request))
        }
        ChatDatabase(context).use { db ->
            val message = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(1)
            assertEquals("Paid final answer", message.getString("text"))
            assertEquals("failed", message.getString("status"))
            assertEquals(150L, message.getJSONObject("usage").getLong("input_tokens"))
            assertEquals(150L, account(db).getLong("input_tokens"))
            assertEquals("failed", account(db).getString("local_status"))
        }
    }

    @Test fun failedClaimKeepsSignedOpenRouterTailAndBlocksUnknownToolOutcome() {
        ChatDatabase(context).use { db ->
            prepared(db, provider = "openrouter")
            val signed = JSONObject().put("role", "assistant").put("content", "paid reasoning text")
                .put("reasoning_details", JSONArray().put(JSONObject().put("type", "reasoning.encrypted")
                    .put("data", "signed-thoughts")))
                .put("tool_calls", JSONArray().put(JSONObject().put("id", "call_or").put("type", "function")
                    .put("function", JSONObject().put("name", "read_attached_file").put("arguments", "{}"))))
            db.saveOpenRouterCheckpoint(chat, request, JSONObject().put("version", 1).put("tail", JSONArray().put(signed)))
            assertTrue(db.claimActiveRequestFailure(chat, request))
            assertNull(db.getOpenRouterCheckpoint(chat, request))
        }
        ChatDatabase(context).use { db ->
            val message = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(1)
            assertEquals("failed", message.getString("status"))
            val tail = message.getJSONObject("openrouter_protocol").getJSONArray("tail")
            assertEquals("signed-thoughts", tail.getJSONObject(0).getJSONArray("reasoning_details")
                .getJSONObject(0).getString("data"))
            assertEquals(1, tail.length())
            assertEquals("call_or", tail.getJSONObject(0).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
            assertFalse(tail.toString().contains("cancelled"))
            assertThrows(OpenRouterToolOutcomeUnknownException::class.java) {
                OpenRouterProtocolCodec.append(JSONArray(), message)
            }
        }
    }

    @Test fun writeFailureAfterAssistantInsertRollsBackBothCheckpointDeletesHistoryAndLedger() {
        ChatDatabase(context).use { db ->
            prepared(db)
            assertTrue(db.saveOpenAiToolCheckpoint(chat, request, checkpoint()))
            db.saveOpenRouterCheckpoint(chat, request, JSONObject().put("version", 1)
                .put("tail", JSONArray().put(JSONObject().put("role", "assistant").put("content", "signed tail"))))
            // Abort the final CAS after persist(db) has written the assistant and accounting.
            db.writableDatabase.execSQL("""CREATE TRIGGER abort_terminal BEFORE UPDATE OF active_request_id ON chats
                WHEN OLD.id='$chat' AND NEW.active_request_id IS NULL
                BEGIN SELECT RAISE(ABORT, 'injected terminal failure'); END""")
            assertThrows(android.database.sqlite.SQLiteException::class.java) { finish(db) }
            assertNotNull(db.getOpenAiToolCheckpoint(chat, request))
            assertNotNull(db.getOpenRouterCheckpoint(chat, request))
            assertTrue(db.isActiveRequest(chat, request))
            assertEquals(1, db.getChat(chat)!!.getJSONArray("messages").length())
            assertEquals("running", account(db).getString("local_status"))
            db.writableDatabase.execSQL("DROP TRIGGER abort_terminal")
            assertTrue(finish(db))
            assertNull(db.getOpenAiToolCheckpoint(chat, request))
            assertNull(db.getOpenRouterCheckpoint(chat, request))
        }
    }

    @Test fun archiveFailureRollsBackStopAndLeavesPaidCheckpointRecoverable() {
        ChatDatabase(context).use { db ->
            prepared(db)
            assertTrue(db.saveOpenAiToolCheckpoint(chat, request, checkpoint()))
            db.writableDatabase.execSQL("""CREATE TRIGGER abort_archive BEFORE INSERT ON request_recovery_archive
                BEGIN SELECT RAISE(ABORT, 'injected archive failure'); END""")
            assertThrows(android.database.sqlite.SQLiteException::class.java) { db.stopActiveRequestOnce(chat, request) }
            assertNotNull(db.getOpenAiToolCheckpoint(chat, request))
            assertTrue(db.isActiveRequest(chat, request))
            assertEquals("running", account(db).getString("local_status"))
            assertTrue(db.listRequestEvents(chat).isEmpty())
        }
    }

    @Test fun staleOwnerCannotCompactAndMalformedPaidCheckpointFailsClosed() {
        ChatDatabase(context).use { db ->
            prepared(db)
            assertTrue(db.saveOpenAiToolCheckpoint(chat, request, checkpoint()))
            val wrong = assistant().put("request_id", "local_foreign")
            assertFalse(db.finalizeCurrentChatIfActiveRequest(chat, "Compaction", null, null, listOf(wrong), "local_foreign"))
            assertNotNull(db.getOpenAiToolCheckpoint(chat, request))
            db.writableDatabase.execSQL("UPDATE openai_tool_checkpoints SET request_checkpoint='invalid-json' WHERE chat_id=?", arrayOf(chat))
            assertThrows(org.json.JSONException::class.java) { finish(db) }
            assertTrue(db.isActiveRequest(chat, request))
            assertEquals("running", account(db).getString("local_status"))
            db.writableDatabase.rawQuery("SELECT COUNT(*) FROM openai_tool_checkpoints WHERE chat_id=?", arrayOf(chat)).use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
        }
    }

    @Test fun damagedIndexedCursorCannotBeSilentlyDeletedByFailureOrStop() {
        ChatDatabase(context).use { db ->
            prepared(db)
            assertTrue(db.saveOpenAiToolCheckpoint(chat, request, checkpoint()))
            db.writableDatabase.execSQL("UPDATE chats SET active_indexed_checkpoint='broken-paid-cursor' WHERE id=?", arrayOf(chat))
            assertThrows(UnrecoverableResponsesCheckpointException::class.java) { db.claimActiveRequestFailure(chat, request) }
            assertThrows(UnrecoverableResponsesCheckpointException::class.java) { db.stopActiveRequestOnce(chat, request) }
            assertNotNull(db.getOpenAiToolCheckpoint(chat, request))
            assertTrue(db.isActiveRequest(chat, request))
            assertTrue(db.listRequestEvents(chat).isEmpty())
        }
    }

    @Test fun omittedTelemetryDuringLateAccountingAndReconcilePreservesDurableSummary() {
        ChatDatabase(context).use { db ->
            prepared(db, provider = "openrouter")
            val tools = JSONObject().put("calls_executed", 3).put("rounds_completed", 2)
                .put("executed_tools", JSONArray().put("read_attached_file"))
                .put("source_read_bytes", 4096L)
            assertTrue(db.recordRequestTelemetry(chat, request, tools))
            assertTrue(db.observeOpenRouterRound(chat, request, 0, "gen-late"))
            assertTrue(db.sealOpenRouterRounds(chat, request))
            assertTrue(db.recordRequestAccounting(chat, request, "completed", telemetrySummary = null))
            assertTrue(db.reconcileOpenRouterGeneration(chat, request, "gen-late", JSONObject()
                .put("id", "gen-late").put("total_cost", 0.0125).put("tokens_prompt", 250)))
            // Event and metric arrive after the generation already reconciled.
            db.recordRequestEvent(chat, "request_completed", request, "openrouter", "gpt-test", "gpt-test",
                ModelCatalog.Match.EXACT, "completed", inputTokens = 100)
            val event = db.listRequestEvents(chat).single()
            assertEquals(250L, event.getLong("input_tokens"))
            assertEquals(0.0125, event.getString("cost_usd").toDouble(), 1e-9)
            val telemetry = RequestEventFormatter.parseTelemetry(event)!!
            assertEquals(3, telemetry.getInt("calls_executed"))
            assertEquals(4096L, telemetry.getLong("source_read_bytes"))
            assertEquals(3, JSONObject(account(db).getString("telemetry_summary")).getInt("calls_executed"))
            db.recordEffortMetric(request, chat, "general", "high", "high", false,
                8, 1, 25, 100, 0, 0, 0, 0, true, null)
            assertEquals(250L, db.usageTotals().getLong("input_tokens"))
            assertEquals(1, db.usageTotals().getInt("known_cost_requests"))
            assertEquals(0.0125, db.usageTotals().getDouble("provider_reported_cost_usd"), 1e-9)
        }
    }

    @Test fun diagnosticsAccountingFilterReadsOnlyExactRequestedIdentitiesWithinChat() {
        ChatDatabase(context).use { db ->
            prepared(db)
            assertTrue(db.startRequestAccounting(chat, "local_other", "openai", "gpt-test", "slot"))
            assertTrue(db.startRequestAccounting(chat, "local_'quoted", "openai", "gpt-test", "slot"))
            db.ensureChat("different-chat")
            assertTrue(db.startRequestAccounting("different-chat", request, "openai", "gpt-test", "slot"))
            assertEquals(3, db.listRequestAccounting(chat).size)
            assertTrue(db.listRequestAccounting(chat, emptySet()).isEmpty())
            assertEquals(setOf(request, "local_'quoted"), db.listRequestAccounting(chat,
                setOf(request, "local_'quoted", "does-not-exist")).map { it.getString("local_request_id") }.toSet())
        }
    }

    @Test fun stopSealsObservedOpenRouterRoundsAndPublishesTokensAndCostInSameTransaction() {
        ChatDatabase(context).use { db ->
            prepared(db, provider = "openrouter")
            assertTrue(db.observeOpenRouterRound(chat, request, 0, "gen-parent", JSONObject()
                .put("prompt_tokens", 100).put("completion_tokens", 10).put("cost", 0.0)))
            assertTrue(db.observeOpenRouterRound(chat, request, 1, "gen-child", JSONObject()
                .put("prompt_tokens", 150).put("completion_tokens", 20).put("cost", 0.2)))
            assertEquals(0, account(db).getInt("rounds_sealed"))
            assertTrue(db.stopActiveRequestOnce(chat, request))
            assertEquals(1, account(db).getInt("rounds_sealed"))
            assertEquals("stopped", account(db).getString("local_status"))
            assertEquals(250L, account(db).getLong("input_tokens"))
            assertEquals(30L, account(db).getLong("output_tokens"))
            val event = db.listRequestEvents(chat).single()
            assertEquals(250L, event.getLong("input_tokens"))
            assertEquals("provider_reported", event.getString("cost_kind"))
            assertEquals(0.2, event.getString("cost_usd").toDouble(), 1e-9)
            assertFalse(db.sealOpenRouterRounds(chat, request))
        }
    }

    @Test fun terminalRollbackRestoresOpenRouterRoundSealAndWinningCommitSealsWithoutExternalCallback() {
        ChatDatabase(context).use { db ->
            prepared(db, provider = "openrouter")
            assertTrue(db.observeOpenRouterRound(chat, request, 0, "gen-parent", JSONObject()
                .put("prompt_tokens", 100).put("completion_tokens", 10).put("cost", 0.1)))
            assertTrue(db.observeOpenRouterRound(chat, request, 1, "gen-child", JSONObject()
                .put("prompt_tokens", 150).put("completion_tokens", 20)))
            val message = assistant().put("provider", "openrouter").put("usage", JSONObject()
                .put("prompt_tokens", 999).put("completion_tokens", 999))
            db.writableDatabase.execSQL("""CREATE TRIGGER abort_terminal BEFORE UPDATE OF active_request_id ON chats
                WHEN OLD.id='$chat' AND NEW.active_request_id IS NULL
                BEGIN SELECT RAISE(ABORT, 'injected terminal failure'); END""")
            assertThrows(android.database.sqlite.SQLiteException::class.java) { finish(db, message) }
            assertEquals(0, account(db).getInt("rounds_sealed"))
            assertEquals("running", account(db).getString("local_status"))
            db.writableDatabase.execSQL("DROP TRIGGER abort_terminal")
            assertTrue(finish(db, message))
            assertEquals(1, account(db).getInt("rounds_sealed"))
            assertEquals(250L, account(db).getLong("input_tokens"))
            assertEquals("unknown", account(db).getString("cost_kind"))
            assertFalse(account(db).has("cost_usd"))
            db.recordRequestEvent(chat, "request_completed", request, "openrouter", "gpt-test", "gpt-test",
                ModelCatalog.Match.EXACT, "completed", inputTokens = 100, reportedCostUsd = 0.1)
            val event = db.listRequestEvents(chat).single()
            assertEquals(250L, event.getLong("input_tokens"))
            assertEquals("unknown", event.getString("cost_kind"))
            assertFalse(event.has("cost_usd"))
        }
    }

    @Test fun stopAndReopenPreservesFullOpenRouterUsageBreakdownWithoutCountingSparseSnapshotsTwice() {
        val parentUsage = JSONObject().put("prompt_tokens", 100).put("completion_tokens", 10).put("cost", 0.01)
            .put("prompt_tokens_details", JSONObject().put("cached_tokens", 40).put("cache_write_tokens", 15))
            .put("completion_tokens_details", JSONObject().put("reasoning_tokens", 5))
            .put("cost_details", JSONObject().put("server_tool_cost", 0.002))
        ChatDatabase(context).use { db ->
            prepared(db, provider = "openrouter")
            assertTrue(db.observeOpenRouterRound(chat, request, 0, "gen-parent", parentUsage))
            repeat(2) {
                assertTrue(db.observeOpenRouterRound(chat, request, 0, "gen-parent", JSONObject().put("prompt_tokens", 100)))
                assertTrue(db.observeOpenRouterRound(chat, request, 0, "gen-parent", parentUsage))
            }
            assertTrue(db.observeOpenRouterRound(chat, request, 1, "gen-child", JSONObject()
                .put("prompt_tokens", 150).put("completion_tokens", 20).put("cost", 0.02)
                .put("prompt_tokens_details", JSONObject().put("cached_tokens", 60).put("cache_write_tokens", 0))
                .put("completion_tokens_details", JSONObject().put("reasoning_tokens", 8))))
            db.saveOpenRouterCheckpoint(chat, request, JSONObject().put("version", 1)
                .put("tail", JSONArray().put(JSONObject().put("role", "assistant").put("content", "Paid tool round"))))
            assertTrue(db.stopActiveRequestOnce(chat, request))
            assertNull(db.getOpenRouterCheckpoint(chat, request))
        }
        ChatDatabase(context).use { db ->
            val event = db.listRequestEvents(chat).single()
            for (row in listOf(account(db), event)) {
                assertEquals(250L, row.getLong("input_tokens"))
                assertEquals(30L, row.getLong("output_tokens"))
                assertEquals(100L, row.getLong("cached_tokens"))
                assertEquals(15L, row.getLong("cache_write_tokens"))
                assertEquals(13L, row.getLong("reasoning_tokens"))
                assertEquals("provider_reported", row.getString("cost_kind"))
                assertEquals(0.03, row.getString("cost_usd").toDouble(), 1e-12)
                assertEquals(0.002, RequestEventFormatter.parseTelemetry(row)!!.getJSONObject("cost_details")
                    .getJSONObject("cost_details").getDouble("server_tool_cost"), 1e-12)
            }
            val archivedUsage = db.getRequestRecoveryArchive(chat, request)!!.getJSONObject("usage")
            assertEquals(15L, archivedUsage.getJSONObject("prompt_tokens_details").getLong("cache_write_tokens"))
            assertEquals(13L, archivedUsage.getJSONObject("completion_tokens_details").getLong("reasoning_tokens"))
        }
    }

    @Test fun unknownSecondRoundRemovesPartialKnownCostFromRecoveredAssistantAndLateRows() {
        ChatDatabase(context).use { db ->
            prepared(db, provider = "openrouter")
            val partial = JSONObject().put("prompt_tokens", 100).put("completion_tokens", 10).put("cost", 0.01)
            assertTrue(db.observeOpenRouterRound(chat, request, 0, "gen-parent", partial))
            assertTrue(db.observeOpenRouterRound(chat, request, 1, "gen-child", JSONObject()
                .put("prompt_tokens", 150).put("completion_tokens", 20)))
            assertTrue(finish(db, assistant().put("provider", "openrouter").put("usage", partial)))
            val message = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(1)
            assertEquals(250L, message.getJSONObject("usage").getLong("prompt_tokens"))
            assertFalse(message.getJSONObject("usage").has("cost"))
            assertEquals("unknown", account(db).getString("cost_kind"))
            assertFalse(account(db).has("cost_usd"))
            assertTrue(db.recordRequestAccounting(chat, request, "completed", usage = partial, reportedCostUsd = 0.01))
            db.recordRequestEvent(chat, "request_completed", request, "openrouter", "gpt-test", "gpt-test",
                ModelCatalog.Match.EXACT, "completed", inputTokens = 100, reportedCostUsd = 0.01)
            db.recordEffortMetric(request, chat, "general", "high", "high", false,
                8, 1, 25, 100, 0, 0, 10, 0, true, null, estimatedCostUsd = 0.01, costKind = "provider_reported")
            for (row in listOf(account(db), db.listRequestEvents(chat).single())) {
                assertEquals(250L, row.getLong("input_tokens"))
                assertEquals("unknown", row.getString("cost_kind"))
                assertFalse(row.has("cost_usd"))
            }
            assertEquals(1L, db.usageTotals().getLong("unknown_cost_requests"))
        }
    }

    @Test fun directTerminalOpenRouterCostWithoutRoundRowsSurvivesAccountingEventAndMetricWrites() {
        ChatDatabase(context).use { db ->
            for (cost in listOf(0.0, 0.025)) {
                prepared(db, provider = "openrouter")
                val direct = JSONObject().put("prompt_tokens", 100).put("completion_tokens", 10).put("cost", cost)
                assertTrue(finish(db, assistant().put("provider", "openrouter").put("usage", direct)))
                assertTrue(db.listOpenRouterRounds(chat, request).isEmpty())
                assertTrue(db.recordRequestAccounting(chat, request, "completed", usage = direct, reportedCostUsd = cost))
                db.recordRequestEvent(chat, "request_completed", request, "openrouter", "gpt-test", "gpt-test",
                    ModelCatalog.Match.EXACT, "completed", inputTokens = 100, reportedCostUsd = cost)
                db.recordEffortMetric(request, chat, "general", "high", "high", false,
                    8, 1, 25, 100, 0, 0, 10, 0, true, null, estimatedCostUsd = cost, costKind = "provider_reported")
                for (row in listOf(account(db), db.listRequestEvents(chat).single())) {
                    assertEquals("provider_reported", row.getString("cost_kind"))
                    assertEquals(cost, row.getString("cost_usd").toDouble(), 1e-12)
                }
                assertEquals(1L, db.usageTotals().getLong("known_cost_requests"))
                assertEquals(cost, db.usageTotals().getDouble("provider_reported_cost_usd"), 1e-12)
                db.deleteChat(chat)
            }
        }
    }

    @Test fun malformedOptionalAccountingTelemetryDoesNotAbortStopLateAccountingOrReconciliation() {
        ChatDatabase(context).use { db ->
            prepared(db)
            assertTrue(db.saveOpenAiToolCheckpoint(chat, request, checkpoint()))
            fun corruptTelemetry() = db.writableDatabase.execSQL(
                "UPDATE request_accounting SET telemetry_summary='broken-optional-json' WHERE chat_id=? AND local_request_id=?",
                arrayOf(chat, request))
            corruptTelemetry()
            assertTrue(db.stopActiveRequestOnce(chat, request))
            assertNotNull(db.getRequestRecoveryArchive(chat, request))
            assertEquals(100L, account(db).getLong("input_tokens"))
            corruptTelemetry()
            assertTrue(db.recordRequestAccounting(chat, request, "cancelled", responseId = "resp_child",
                usage = usage(20)))
            assertEquals(120L, account(db).getLong("input_tokens"))
            corruptTelemetry()
            assertTrue(db.reconcileStoppedOpenAiResponse(chat, request, "resp_child",
                JSONObject().put("id", "resp_child").put("status", "cancelled").put("usage", usage(20))))
            assertEquals(120L, account(db).getLong("input_tokens"))
            assertEquals(120L, db.listRequestEvents(chat).single().getLong("input_tokens"))
            assertEquals("stopped", account(db).getString("local_status"))
            assertEquals(1, db.listRequestEvents(chat).size)
        }
    }

    @Test fun missingAccountingLedgerRestoresTerminalMessageAndValidNewerAccountingWinsLaterSnapshot() {
        ChatDatabase(context).use { db ->
            prepared(db)
            val saved = checkpoint()
            assertTrue(db.saveOpenAiToolCheckpoint(chat, request, saved))
            val combined = OpenAiUsageLedger.resolve(saved, raw("resp_child", "Answer", 20))
            val message = assistant().put(OpenAiUsageLedger.FIELD, combined)
                .put("usage", OpenAiUsageLedger.total(combined))
                .put("telemetry_summary", JSONObject().put(OpenAiUsageLedger.FIELD, combined))
            assertTrue(finish(db, message))
            assertNull(db.getRequestRecoveryArchive(chat, request))
            db.writableDatabase.execSQL("UPDATE request_accounting SET telemetry_summary=NULL WHERE chat_id=? AND local_request_id=?",
                arrayOf(chat, request))
            assertTrue(db.recordRequestAccounting(chat, request, "completed", responseId = "resp_child", usage = usage(25)))
            assertEquals(125L, account(db).getLong("input_tokens"))
            // A delayed marked total from before the latest terminal GET must not undo its child snapshot.
            assertTrue(db.recordRequestAccounting(chat, request, "completed", responseId = "resp_child",
                usage = OpenAiUsageLedger.total(combined),
                telemetrySummary = JSONObject().put(OpenAiUsageLedger.FIELD, combined)))
            assertEquals(125L, account(db).getLong("input_tokens"))
        }
    }

    @Test fun malformedPaidImageIsArchivedAsFailedWithoutAbortingStopOrLosingRawResult() {
        ChatDatabase(context).use { db ->
            prepared(db)
            // One base64 sextet cannot represent even a single byte.
            val malformedImage = JSONObject().put("type", "image_generation_call").put("id", "ig_bad").put("result", "A")
            val paidResponse = raw("resp_child", "Keep paid text", 150)
            paidResponse.getJSONArray("output").put(malformedImage)
            assertTrue(db.saveOpenAiToolCheckpoint(chat, request, checkpoint(legacy = true)
                .put("phase", "finished").put("final_response", paidResponse)))
            assertTrue(db.stopActiveRequestOnce(chat, request))
            assertNull(db.getOpenAiToolCheckpoint(chat, request))
            val archive = db.getRequestRecoveryArchive(chat, request)!!
            assertEquals("Keep paid text", archive.getString("text"))
            assertEquals("FAILED", archive.getJSONArray("generated_images").getJSONObject(0).getString("state"))
            assertEquals("A", archive.getJSONArray("openai_protocol_output").getJSONObject(1).getString("result"))
            assertEquals(150L, account(db).getLong("input_tokens"))
            assertEquals("stopped", account(db).getString("local_status"))
        }
    }
}
