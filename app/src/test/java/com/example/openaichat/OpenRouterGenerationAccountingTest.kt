package com.example.openaichat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenRouterGenerationAccountingTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }
    @After fun teardown() {
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }

    private fun started(db: ChatDatabase, chat: String = "chat", request: String = "local_1") {
        db.ensureChat(chat)
        assertTrue(db.startRequestAccounting(chat, request, "openrouter", "model",
            "openrouter_api_key"))
    }
    private fun usage(input: Int, output: Int, cost: Double? = null) =
        JSONObject().put("prompt_tokens", input).put("completion_tokens", output).apply {
            cost?.let { put("cost", it) }
        }

    @Test fun twoRoundsRequireAllKnownCostsAndIdempotentReopen() {
        ChatDatabase(context).use { db ->
            started(db)
            assertTrue(db.observeOpenRouterRound("chat", "local_1", 0, "gen-one", usage(4, 2, 0.0)))
            assertTrue(db.observeOpenRouterRound("chat", "local_1", 1, "gen-two", usage(8, 5)))
            assertTrue(db.sealOpenRouterRounds("chat", "local_1"))
            val first = db.listRequestAccounting("chat").single()
            assertEquals("unknown", first.getString("cost_kind"))
            assertFalse(first.has("cost_usd"))
        }
        ChatDatabase(context).use { db ->
            val metadata = JSONObject("""{"id":"gen-two","total_cost":0.00000002,"tokens_prompt":8,"tokens_completion":5}""")
            assertTrue(db.reconcileOpenRouterGeneration("chat", "local_1", "gen-two", metadata))
            assertTrue(db.reconcileOpenRouterGeneration("chat", "local_1", "gen-two", metadata))
            val row = db.listRequestAccounting("chat").single()
            assertEquals("provider_reported", row.getString("cost_kind"))
            assertEquals(0.00000002, row.getString("cost_usd").toDouble(), 0.0)
            assertEquals(2, db.listOpenRouterRounds("chat", "local_1").size)
            assertEquals(1, db.requestAccountingTotals().getInt("known_cost_requests"))
            assertEquals(0.00000002, db.requestAccountingTotals().getDouble("provider_reported_cost_usd"), 1e-12)
            assertFalse(db.sealOpenRouterRounds("chat", "local_1"))
        }
    }

    @Test fun duplicateSseUsageIsSnapshotNotBillableDelta() {
        ChatDatabase(context).use { db ->
            started(db)
            db.observeOpenRouterRound("chat", "local_1", 0)
            repeat(3) { db.observeOpenRouterRound("chat", "local_1", 0, "gen-once", usage(10, 3, 0.012)) }
            db.sealOpenRouterRounds("chat", "local_1")
            val round = db.listOpenRouterRounds("chat", "local_1").single()
            assertEquals("gen-once", round.getString("generation_id"))
            assertEquals(10, round.getInt("input_tokens"))
            assertEquals(0.012, round.getString("cost_usd").toDouble(), 0.0)
            assertEquals(0.012, db.listRequestAccounting("chat").single().getString("cost_usd").toDouble(), 0.0)
            assertFalse(db.observeOpenRouterRound("chat", "local_1", 0, "gen-other", usage(90, 9, 3.0)))
            assertEquals(0.012, db.listRequestAccounting("chat").single().getString("cost_usd").toDouble(), 0.0)
        }
    }

    @Test fun stopAllowsOnlyPreviouslyObservedRoundToReconcile() {
        ChatDatabase(context).use { db ->
            started(db)
            db.observeOpenRouterRound("chat", "local_1", 0, "gen-stop")
            db.markRequestAccountingStopped("chat", "local_1")
            db.sealOpenRouterRounds("chat", "local_1")
            assertFalse(db.observeOpenRouterRound("chat", "local_1", 1, "gen-new", usage(7, 1, 2.0)))
            assertTrue(db.reconcileOpenRouterGeneration("chat", "local_1", "gen-stop",
                JSONObject("""{"id":"gen-stop","total_cost":0}""")))
            val row = db.listRequestAccounting("chat").single()
            assertEquals("stopped", row.getString("local_status"))
            assertEquals("provider_reported", row.getString("cost_kind"))
            assertEquals(0.0, row.getString("cost_usd").toDouble(), 0.0)
            assertEquals(1, db.listOpenRouterRounds("chat", "local_1").size)
            assertEquals(0, db.listRequestEvents("chat").size)
        }
    }

    @Test fun noGenerationIdAndMissingRoundCostCannotBeInferredFromOtherRound() {
        ChatDatabase(context).use { db ->
            started(db)
            db.observeOpenRouterRound("chat", "local_1", 0, "gen-one", usage(20, 5, 0.5))
            db.observeOpenRouterRound("chat", "local_1", 1, null, usage(10, 2))
            db.sealOpenRouterRounds("chat", "local_1")
            assertEquals("unknown", db.listRequestAccounting("chat").single().getString("cost_kind"))
            assertFalse(db.listRequestAccounting("chat").single().has("cost_usd"))
        }
    }

    @Test fun deleteAndRecreateNeverRestoreOldGenerationOrCost() {
        ChatDatabase(context).use { db ->
            started(db)
            db.observeOpenRouterRound("chat", "local_1", 0, "gen-old")
            db.deleteChat("chat")
            assertFalse(db.reconcileOpenRouterGeneration("chat", "local_1", "gen-old",
                JSONObject("""{"id":"gen-old","total_cost":100}""")))
            db.ensureChat("chat")
            assertTrue(db.listOpenRouterRounds("chat", "local_1").isEmpty())
            assertEquals(0, db.requestAccountingTotals().getInt("requests"))
            assertFalse(db.observeOpenRouterRound("chat", "local_1", 0, "gen-old", usage(1, 1, 3.0)))
        }
    }

    @Test fun v19UpgradeKeepsUnknownHistoricalAccountingRows() {
        ChatDatabase(context).use { db ->
            started(db)
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath("astra_chats.db").path,
            null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
            sqlite.execSQL("DROP TABLE openrouter_request_rounds")
            sqlite.version = 19
        }
        ChatDatabase(context).use { db ->
            assertTrue(db.listOpenRouterRounds("chat", "local_1").isEmpty())
            assertEquals("unknown", db.listRequestAccounting("chat").single().getString("cost_kind"))
            assertEquals(0, db.listRequestAccounting("chat").single().getInt("rounds_sealed"))
            assertTrue(db.sealOpenRouterRounds("chat", "local_1"))
            assertEquals("unknown", db.listRequestAccounting("chat").single().getString("cost_kind"))
        }
    }

    private fun enqueue(server: MockWebServer, vararg frames: String) {
        server.enqueue(MockResponse().setResponseCode(200)
            .addHeader("Content-Type", "text/event-stream")
            .setBody(frames.joinToString("") { "data: $it\n\n" }))
    }

    @Test fun multipleSseUsageFramesDoNotDoubleCountTokensOrCost() {
        MockWebServer().use { server ->
            enqueue(server,
                """{"id":"gen-stream","choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""",
                """{"id":"gen-stream","choices":[],"usage":{"prompt_tokens":4,"completion_tokens":2,"cost":0.00000003}}""",
                """{"id":"gen-stream","choices":[],"usage":{"prompt_tokens":4,"completion_tokens":2,"cost":0.00000003}}""",
                "[DONE]")
            ChatDatabase(context).use { db ->
                started(db)
                val client = OpenRouterChatClient(server.url("/api/v1").toString())
                try {
                    val result = client.complete(
                        apiKey = "test-key", model = "test-model",
                        messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hey")),
                        tools = null, maxTokens = 12, reasoningEffort = null,
                        verbosity = null, parallelToolCalls = false,
                        executeTool = { _, _ -> "" },
                        onRoundObserved = { idx, id, data ->
                            db.observeOpenRouterRound("chat", "local_1", idx, id, data)
                        }
                    )
                    assertEquals(4L, result.usage!!.getLong("prompt_tokens"))
                    assertEquals(2L, result.usage!!.getLong("completion_tokens"))
                    assertEquals(0.00000003, result.usage!!.getDouble("cost"), 0.0)
                    db.sealOpenRouterRounds("chat", "local_1")
                    assertEquals("gen-stream", db.listOpenRouterRounds("chat", "local_1").single().getString("generation_id"))
                    assertEquals(0.00000003, db.listRequestAccounting("chat").single().getString("cost_usd").toDouble(), 0.0)
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test fun metadataGetBindsGenerationIdAndRemainsBounded() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("""{"data":{"id":"gen-one","total_cost":0.01,"tokens_prompt":4}}"""))
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("""{"data":{"id":"wrong-id","total_cost":42}}"""))
            ChatDatabase(context).use { db ->
                started(db)
                db.observeOpenRouterRound("chat", "local_1", 0, "gen-one")
                db.observeOpenRouterRound("chat", "local_1", 1, "gen-two")
                db.sealOpenRouterRounds("chat", "local_1")
                val hits = OpenRouterGenerationReconciler(OkHttpClient(), server.url("/api/v1/"))
                    .reconcile(db.listOpenRouterRounds("chat", "local_1"), "test-key") { gen, metadata ->
                        db.reconcileOpenRouterGeneration("chat", "local_1", gen, metadata)
                    }
                assertEquals(2, hits)
                assertEquals("unknown", db.listRequestAccounting("chat").single().getString("cost_kind"))
                assertEquals(0.01, db.listOpenRouterRounds("chat", "local_1")[0].getString("cost_usd").toDouble(), 0.0)
                assertFalse(db.listOpenRouterRounds("chat", "local_1")[1].has("cost_usd"))
            }
            assertEquals("/api/v1/generation?id=gen-one", server.takeRequest().path)
            assertEquals("/api/v1/generation?id=gen-two", server.takeRequest().path)
        }
    }

    @Test fun authErrorStopsLookupsAndLeavesUnknownCost() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            ChatDatabase(context).use { db ->
                started(db)
                db.observeOpenRouterRound("chat", "local_1", 0, "gen-one")
                db.observeOpenRouterRound("chat", "local_1", 1, "gen-two")
                db.sealOpenRouterRounds("chat", "local_1")
                val count = OpenRouterGenerationReconciler(OkHttpClient(), server.url("/api/v1/"))
                    .reconcile(db.listOpenRouterRounds("chat", "local_1"), "test-key") { gen, metadata ->
                        db.reconcileOpenRouterGeneration("chat", "local_1", gen, metadata)
                    }
                assertEquals(1, count)
                assertEquals("unknown", db.listRequestAccounting("chat").single().getString("cost_kind"))
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun moreThanFourToolRoundsReconcileEveryGenerationAndRemainIdempotentAfterReopen() {
        val generationIds = (0 until 9).map { "gen-long-$it" }
        ChatDatabase(context).use { db ->
            started(db)
            generationIds.forEachIndexed { index, id -> db.observeOpenRouterRound("chat", "local_1", index, id) }
            db.sealOpenRouterRounds("chat", "local_1")
        }
        MockWebServer().use { server ->
            generationIds.forEach { id -> server.enqueue(MockResponse().setBody(
                JSONObject().put("data", JSONObject().put("id", id).put("total_cost", 0.01)).toString())) }
            ChatDatabase(context).use { db ->
                val count = OpenRouterGenerationReconciler(OkHttpClient(), server.url("/api/v1/"))
                    .reconcile(db.listOpenRouterRounds("chat", "local_1"), "synthetic-key") { id, metadata ->
                        assertTrue(db.reconcileOpenRouterGeneration("chat", "local_1", id, metadata))
                    }
                assertEquals(9, count)
            }
            ChatDatabase(context).use { db ->
                val rounds = db.listOpenRouterRounds("chat", "local_1")
                assertEquals(generationIds, rounds.map { it.getString("generation_id") })
                assertTrue(rounds.all { it.getString("cost_kind") == "provider_reported" })
                assertEquals(0.09, db.listRequestAccounting("chat").single().getString("cost_usd").toDouble(), 1e-12)
                assertEquals(0, OpenRouterGenerationReconciler(OkHttpClient(), server.url("/api/v1/"))
                    .reconcile(rounds, "synthetic-key") { _, _ -> fail("Known cost must not be queried again") })
            }
            repeat(9) { assertEquals("/api/v1/generation?id=${generationIds[it]}", server.takeRequest().path) }
            assertEquals(9, server.requestCount)
        }
    }

    @Test fun explicitReconciliationTimeBudgetLeavesDurableQueueForNextPassAfterReopen() {
        val ids = (0 until 7).map { "gen-queue-$it" }
        ChatDatabase(context).use { db ->
            started(db)
            ids.forEachIndexed { index, id -> db.observeOpenRouterRound("chat", "local_1", index, id) }
            db.sealOpenRouterRounds("chat", "local_1")
        }
        MockWebServer().use { server ->
            ids.forEach { id -> server.enqueue(MockResponse().setBody(
                JSONObject().put("data", JSONObject().put("id", id).put("total_cost", 0.0)).toString())) }
            val clock = AtomicLong(1L)
            ChatDatabase(context).use { db ->
                assertEquals(2, OpenRouterGenerationReconciler(OkHttpClient(), server.url("/api/v1/"),
                    maxDurationMillis = 1_000, monotonicNanos = clock::get)
                    .reconcile(db.listOpenRouterRounds("chat", "local_1"), "synthetic-key") { id, metadata ->
                        assertTrue(db.reconcileOpenRouterGeneration("chat", "local_1", id, metadata))
                        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(500))
                    })
                assertEquals("unknown", db.listRequestAccounting("chat").single().getString("cost_kind"))
                assertFalse(db.listRequestAccounting("chat").single().has("cost_usd"))
            }
            ChatDatabase(context).use { db ->
                assertEquals(5, OpenRouterGenerationReconciler(OkHttpClient(), server.url("/api/v1/"))
                    .reconcile(db.listOpenRouterRounds("chat", "local_1"), "synthetic-key") { id, metadata ->
                        assertTrue(db.reconcileOpenRouterGeneration("chat", "local_1", id, metadata))
                    })
                val accounting = db.listRequestAccounting("chat").single()
                assertEquals("provider_reported", accounting.getString("cost_kind"))
                assertEquals(0.0, accounting.getString("cost_usd").toDouble(), 0.0)
            }
            repeat(7) { assertEquals("GET", server.takeRequest().method) }
            assertEquals(7, server.requestCount)
        }
    }
}
