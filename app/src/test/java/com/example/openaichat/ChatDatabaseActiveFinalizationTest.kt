package com.example.openaichat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatDatabaseActiveFinalizationTest {
    private lateinit var context: Context

    @Before fun before() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }

    @After fun after() {
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }

    private fun prepare(db: ChatDatabase, chatId: String = "chat", requestId: String = "local_1") {
        db.ensureChat(chatId, "Original")
        db.upsertCurrentChat(chatId, "Original", null, "high", listOf(
            JSONObject().put("role", "user").put("text", "original")
                .put("request_id", requestId).put("requested_model", "gpt-test")
        ))
        db.setActiveResponse(
            chatId = chatId, responseId = "resp_1", requestId = requestId,
            text = "partial", status = "in_progress",
            startedAt = 123L, effortPref = "high", effectiveEffort = "high",
            autoSelected = false, promptChars = 8, attachments = 0, backgroundEnabled = true
        )
        assertTrue(db.startRequestAccounting(chatId, requestId, "openai", "gpt-test", "openai_api_key"))
    }

    private fun finalizedMessages(requestId: String, content: String): List<JSONObject> =
        listOf(
            JSONObject().put("role", "user").put("text", "original").put("request_id", requestId),
            JSONObject().put("role", "assistant").put("text", content)
                .put("request_id", requestId).put("response_id", "resp_1")
                .put("status", "completed")
        )

    private fun commitOnce(db: ChatDatabase, chat: String = "chat", request: String = "local_1", text: String = "answer"): Boolean {
        val committed = db.finalizeCurrentChatIfActiveRequest(
            chat, "Original", "resp_1", "high",
            finalizedMessages(request, text), request
        )
        if (committed) {
            db.recordRequestAccounting(chat, request, "completed",
                responseId = "resp_1", usage = JSONObject().put("input_tokens", 15))
            db.recordRequestEvent(
                chat, "request_completed", request, "openai", "gpt-test", "gpt-test",
                ModelCatalog.Match.EXACT, "completed", inputTokens = 15
            )
        }
        return committed
    }

    @Test fun terminalClaimPublishesLedgerAndClearsNoncompletedChainBeforeEnrichment() {
        listOf("completed", "failed", "incomplete", "cancelled").forEach { status ->
            val chatId = "terminal-$status"
            ChatDatabase(context).use { db ->
                prepare(db, chatId)
                db.upsertCurrentChat(chatId, "Original", "previous-chain", "high",
                    listOf(db.getChat(chatId)!!.getJSONArray("messages").getJSONObject(0)))
                val assistant = finalizedMessages("local_1", "paid partial").last()
                    .put("provider", "openai").put("status", status)
                assertTrue(db.finalizeCurrentChatIfActiveRequest(chatId, "Original",
                    "resp_1".takeIf { status == "completed" }, null, listOf(assistant), "local_1"))
            }
            ChatDatabase(context).use { db ->
                assertNull(db.getActiveResponse(chatId))
                assertEquals("Ledger outcome is part of the terminal transaction", status,
                    db.listRequestAccounting(chatId).single().getString("local_status"))
                assertEquals(if (status == "completed") "resp_1" else null,
                    JsonProtocol.nonBlank(db.getChat(chatId)!!, "response_id"))
                assertEquals("unknown", db.listRequestAccounting(chatId).single().getString("cost_kind"))
                assertTrue(db.recordRequestAccounting(chatId, "local_1", status,
                    usage = JSONObject().put("input_tokens", 15)))
                assertEquals(15, db.listRequestAccounting(chatId).single().getInt("input_tokens"))
                assertFalse(commitOnce(db, chatId))
                assertEquals(status, db.listRequestAccounting(chatId).single().getString("local_status"))
            }
        }
    }

    @Test fun stopEventUsesCurrentIdentityInsteadOfPreviousCostOrLateCallback() {
        ChatDatabase(context).use { db ->
            prepare(db)
            db.recordRequestEvent("chat", "request_completed", "previous",
                "openai", "old", "old", ModelCatalog.Match.EXACT,
                "completed", reportedCostUsd = 0.20)
            assertTrue(db.stopActiveRequestOnce("chat", "local_1"))
            val events = db.listRequestEvents("chat")
            assertEquals(2, events.size)
            assertEquals("local_1", events.first().getString("request_id"))
            assertEquals("stopped", events.first().getString("status"))
            assertTrue(RequestEventFormatter.summary(events.first()).contains("Расход неизвестен"))
            assertTrue(db.recordRequestAccounting("chat", "local_1", "completed",
                reportedCostUsd = 0.0))
            db.recordRequestEvent("chat", "request_completed", "local_1",
                "openai", "gpt-test", "gpt-test", ModelCatalog.Match.EXACT,
                "completed", reportedCostUsd = 4.0)
            assertEquals(2, db.listRequestEvents("chat").size)
            val enriched = RequestStopEventPolicy.enrich(
                db.listRequestEvents("chat").first(), db.listRequestAccounting("chat").single()
            )
            assertEquals("stopped", enriched.getString("status"))
            assertTrue(RequestEventFormatter.summary(enriched).contains("Расход $0.0000"))
        }
    }

    @Test fun duplicateRecoveryCallbackOnlyOneWinnerAndOneEvent() {
        ChatDatabase(context).use { db ->
            prepare(db)
            assertTrue(commitOnce(db, text = "worker answer"))
            assertFalse(commitOnce(db, text = "activity duplicate"))
            assertNull(db.getActiveResponse("chat"))
            val messages = db.getChat("chat")!!.getJSONArray("messages")
            assertEquals(2, messages.length())
            assertEquals("worker answer", messages.getJSONObject(1).getString("text"))
            assertEquals(1, db.listRequestEvents("chat").size)
            assertEquals(1, db.listRequestAccounting("chat").size)
            assertEquals("completed", db.listRequestAccounting("chat").single().getString("local_status"))
        }
    }

    @Test fun stopAndFinalizationAreMutuallyExclusive() {
        ChatDatabase(context).use { db ->
            prepare(db)
            assertTrue(db.stopActiveRequestOnce("chat", "local_1"))
            assertNull(db.getActiveResponse("chat"))
            assertFalse(commitOnce(db))
            assertFalse(db.stopActiveRequestOnce("chat", "local_1"))
            assertEquals(1, db.getChat("chat")!!.getJSONArray("messages").length())
            assertEquals("stopped", db.listRequestAccounting("chat").single().getString("local_status"))
            assertEquals("stopped", db.listRequestEvents("chat").single().getString("status"))
        }
    }

    @Test fun replacedOrDeletedChatCannotResurrectOldRequest() {
        ChatDatabase(context).use { db ->
            prepare(db)
            db.setActiveResponse("chat", "resp_2", "local_2", "", "in_progress", 124L,
                "medium", "medium", false, 0, 0, true)
            assertFalse(commitOnce(db))
            assertFalse(db.stopActiveRequestOnce("chat", "local_1"))
            assertEquals("local_2", db.getActiveResponse("chat")!!.getString("request_id"))
            db.deleteChat("chat")
            assertFalse(commitOnce(db))
            assertFalse(db.stopActiveRequestOnce("chat", "local_2"))
            assertNull(db.getChat("chat"))
        }
    }

    @Test fun offlineLegacyRecoveryClaimsBeforeLateFinalization() {
        ChatDatabase(context).use { db ->
            prepare(db)
            assertTrue(db.recoverLegacyOpenAiRequestLocally("chat", "local_1"))
            assertFalse(commitOnce(db))
            assertFalse(db.recoverLegacyOpenAiRequestLocally("chat", "local_1"))
            assertEquals(1, db.listRequestAccounting("chat").size)
            assertNotNull(db.getRequestRecoveryArchive("chat", "local_1"))
            val assistant = db.getChat("chat")!!.getJSONArray("messages").getJSONObject(1)
            assertEquals("partial", assistant.getString("text"))
            assertEquals("incomplete", assistant.getString("status"))
        }
    }

    @Test fun simultaneousWorkerActivityClaimsAreSerialized() {
        ChatDatabase(context).use { db ->
            prepare(db)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val first = pool.submit<Boolean> {
                    start.await()
                    commitOnce(db, text = "worker")
                }
                val second = pool.submit<Boolean> {
                    start.await()
                    commitOnce(db, text = "activity")
                }
                start.countDown()
                val winners = listOf(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))
                assertEquals(1, winners.count { it })
                assertEquals(1, db.listRequestEvents("chat").size)
                assertEquals(1, db.listRequestAccounting("chat").size)
                assertEquals(2, db.getChat("chat")!!.getJSONArray("messages").length())
                assertNull(db.getActiveResponse("chat"))
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test fun stopRacingTerminalOnlyOneOwnerCanFinish() {
        ChatDatabase(context).use { db ->
            prepare(db)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val stop = pool.submit<Boolean> {
                    start.await()
                    db.stopActiveRequestOnce("chat", "local_1")
                }
                val finish = pool.submit<Boolean> {
                    start.await()
                    commitOnce(db)
                }
                start.countDown()
                val stopWon = stop.get(15, TimeUnit.SECONDS)
                val finishWon = finish.get(15, TimeUnit.SECONDS)
                assertTrue(stopWon.xor(finishWon))
                assertNull(db.getActiveResponse("chat"))
                assertEquals(if (finishWon) 2 else 1, db.getChat("chat")!!.getJSONArray("messages").length())
                assertEquals(1, db.listRequestEvents("chat").size)
                assertEquals(if (stopWon) "stopped" else "completed",
                    db.listRequestAccounting("chat").single().getString("local_status"))
            } finally {
                pool.shutdownNow()
            }
        }
    }
    @Test fun newRequestAfterWorkerTerminalIsAppendedDespiteEqualSnapshotCount() {
        ChatDatabase(context).use { db ->
            prepare(db)
            assertTrue(commitOnce(db, text="old assistant"))
            val freshUser = JSONObject().put("role","user").put("text","new prompt")
                .put("request_id","local_2")
            assertTrue(db.upsertUiSnapshotPreservingTail("chat","Original",null,null,
                listOf(JSONObject().put("role","user").put("text","old prompt")
                    .put("request_id","local_1"),freshUser)))
            val rows = db.getChat("chat")!!.getJSONArray("messages")
            assertEquals(3, rows.length())
            assertEquals("old assistant",rows.getJSONObject(1).getString("text"))
            assertEquals("new prompt",rows.getJSONObject(2).getString("text"))
        }
    }

    @Test fun sameLengthStaleUiSnapshotCannotOverwriteCommittedMetadata() {
        ChatDatabase(context).use { db ->
            prepare(db)
            val enriched = db.getChat("chat")!!.getJSONArray("messages").getJSONObject(0)
            enriched.put("feedback", -1).put("text", "latest persisted text")
            db.upsertCurrentChat("chat", "Original", null, "high", listOf(enriched))
            val oldSnapshot = listOf(
                JSONObject().put("role","user").put("request_id","local_1")
                    .put("text","stale").put("feedback",1))
            assertTrue(db.upsertUiSnapshotPreservingTail("chat","Original",null,"high",oldSnapshot))
            val actual = db.getChat("chat")!!.getJSONArray("messages").getJSONObject(0)
            assertEquals(-1, actual.getInt("feedback"))
            assertEquals("latest persisted text",actual.getString("text"))
        }
    }

    @Test fun terminalClaimPreservesDbRowsAndMetadataAddedAfterStaleSnapshot() {
        ChatDatabase(context).use { db ->
            prepare(db)
            val original = db.getChat("chat")!!.getJSONArray("messages").getJSONObject(0)
            original.put("text", "text edited by another owner")
            original.put("feedback", 1)
            db.upsertCurrentChat("chat","Original",null,"high",listOf(original))
            assertTrue(commitOnce(db, text="terminal from worker"))
            val rows = db.getChat("chat")!!.getJSONArray("messages")
            assertEquals(2, rows.length())
            assertEquals("text edited by another owner", rows.getJSONObject(0).getString("text"))
            assertEquals(1, rows.getJSONObject(0).getInt("feedback"))
            assertEquals("terminal from worker", rows.getJSONObject(1).getString("text"))
            assertFalse(db.upsertUiSnapshotPreservingTail("chat","Original",null,"high",
                listOf(JSONObject().put("role","user").put("text","stale old user").put("request_id","local_1"))))
            val reopened = db.getChat("chat")!!.getJSONArray("messages")
            assertEquals(2, reopened.length())
            assertEquals("terminal from worker", reopened.getJSONObject(1).getString("text"))
            assertEquals("text edited by another owner", reopened.getJSONObject(0).getString("text"))
        }
    }

    @Test fun concurrentStopAfterTerminalCannotEraseCommittedAssistant() {
        ChatDatabase(context).use { db ->
            prepare(db)
            assertTrue(db.finalizeCurrentChatIfActiveRequest(
                "chat","Original","resp_1","high", finalizedMessages("local_1","winner"),"local_1"))
            assertFalse(db.stopActiveRequestOnce("chat","local_1"))
            assertEquals("winner",db.getChat("chat")!!.getJSONArray("messages")
                .getJSONObject(1).getString("text"))
        }
    }
}
