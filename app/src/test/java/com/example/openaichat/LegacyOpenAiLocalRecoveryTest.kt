package com.example.openaichat

import android.content.ContentValues
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
import java.io.File

/** Imported paid records are read/retired locally, using the actual SQLite/CAS paths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegacyOpenAiLocalRecoveryTest {
    private lateinit var context: Context
    private val files = mutableListOf<File>()
    private val chat = "legacy-local"
    private val request = "local_legacy"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }
    @After fun cleanup() {
        files.forEach(File::delete)
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }
    private fun file(suffix: String = ".txt") = File.createTempFile("legacy-original-", suffix, context.filesDir)
        .apply { writeText("original bytes"); files += this }
    private fun prepare(db: ChatDatabase, provider: String = "openai", state: ResponseEventState? = null) {
        db.ensureChat(chat, "Imported history")
        db.upsertCurrentChat(chat, "Imported history", null, "high", listOf(JSONObject()
            .put("role", "user").put("text", "Original prompt").put("request_id", request).put("provider", provider)))
        db.startRequestAccounting(chat, request, provider, "legacy-model", "legacy")
        db.setActiveResponse(chat, state?.responseId ?: "resp_saved", request, "", "starting", 1L,
            "high", "high", false, 15, 0, true)
        if (state != null) db.updateActiveResponse(chat, state.responseId, state.text,
            state.terminalState?.wireStatus ?: "in_progress", 3, request, ResponseIndexedCheckpoint.serialize(state))
    }
    private fun signedState() = ResponseEventState(responseId = "resp_saved", reasoningSummary = "Readable received summary",
        typedOutputItems = mapOf(0 to JSONObject().put("type", "reasoning").put("id", "rs_saved")
            .put("encrypted_content", "opaque-signed-original"),
            1 to JSONObject().put("type", "web_search_call").put("id", "web_saved").put("status", "completed")))

    @Test fun signedOutputWithNoVisibleTextAndPaidUsageSurviveRetirementAndReopen() {
        val state = signedState()
        val usage = JSONObject().put("input_tokens", 7).put("output_tokens", 11)
        val received = JSONObject().put("id", "resp_saved").put("status", "completed").put("usage", usage)
            .put("output", JSONArray().put(state.typedOutputItems.getValue(0)).put(state.typedOutputItems.getValue(1)))
        val checkpoint = JSONObject().put("version", 1).put("phase", "waiting").put("response_id", "resp_saved")
            .put("received_response", received).put("usage", usage)
            .put(OpenAiUsageLedger.FIELD, OpenAiUsageLedger.observe(null, "resp_saved", usage, "completed"))
        ChatDatabase(context).use { db -> prepare(db, state = state); assertTrue(db.saveOpenAiToolCheckpoint(chat, request, checkpoint)) }
        ChatDatabase(context).use { db ->
            assertTrue(db.recoverLegacyOpenAiRequestLocally(chat, request))
            assertFalse(db.recoverLegacyOpenAiRequestLocally(chat, request))
            assertNull(db.getActiveResponse(chat))
            assertNull(db.getOpenAiToolCheckpoint(chat, request))
            val account = db.listRequestAccounting(chat).single()
            assertEquals("incomplete", account.getString("local_status"))
            assertEquals(7, account.getInt("input_tokens")); assertEquals(11, account.getInt("output_tokens"))
            assertEquals("unknown", account.getString("cost_kind"))
        }
        ChatDatabase(context).use { db ->
            val saved = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(1)
            assertEquals("openai", saved.getString("provider"))
            assertEquals("incomplete", saved.getString("status"))
            assertEquals("", saved.getString("text"))
            assertEquals("Readable received summary", saved.getString("reasoning_summary"))
            assertEquals("opaque-signed-original", saved.getJSONArray("openai_protocol_output").getJSONObject(0).getString("encrypted_content"))
            assertEquals("web_saved", saved.getJSONArray("openai_protocol_output").getJSONObject(1).getString("id"))
            val archive = db.getRequestRecoveryArchive(chat, request)!!
            assertEquals(checkpoint.toString(), archive.getString("legacy_tool_checkpoint_raw"))
            assertEquals(ResponseIndexedCheckpoint.serialize(state), archive.getJSONObject("legacy_active_snapshot").getString("indexed_checkpoint"))
        }
    }

    @Test fun provenToolFreeTerminalKeepsItsCompletedStatusAndUsage() {
        val terminal = JSONObject().put("id", "resp_saved").put("status", "completed")
            .put("usage", JSONObject().put("input_tokens", 13).put("output_tokens", 17))
            .put("output", JSONArray().put(JSONObject().put("type", "message").put("role", "assistant")
                .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "Paid answer")))))
        val state = ResponseEventState(responseId = "resp_saved", text = "Paid answer", unindexedText = "Paid answer",
            terminalState = ResponseTerminalState.COMPLETED, terminalResponse = terminal)
        ChatDatabase(context).use { db ->
            prepare(db, state = state)
            assertTrue(db.recoverLegacyOpenAiRequestLocally(chat, request))
            val saved = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(1)
            assertEquals("completed", saved.getString("status"))
            assertFalse(saved.optBoolean("incomplete"))
            assertFalse(saved.has("error"))
            assertEquals("Paid answer", saved.getString("text"))
            assertEquals(17, saved.getJSONObject("usage").getInt("output_tokens"))
            assertEquals("completed", db.listRequestAccounting(chat).single().getString("local_status"))
        }
    }

    @Test fun malformedIndexedAndToolSourcesAreArchivedVerbatimWithoutGuessingTheirOutput() {
        val indexed = "{broken indexed 😀"
        val tool = "{broken tools"
        ChatDatabase(context).use { db ->
            prepare(db)
            db.updateActiveResponse(chat, "resp_saved", "Visible partial", "in_progress", 2, request, indexed)
            db.writableDatabase.execSQL("INSERT INTO openai_tool_checkpoints(chat_id,request_id,request_checkpoint) VALUES (?,?,?)",
                arrayOf(chat, request, tool))
            assertTrue(db.recoverLegacyOpenAiRequestLocally(chat, request))
            assertNull(db.getActiveResponse(chat))
        }
        ChatDatabase(context).use { db ->
            val archive = db.getRequestRecoveryArchive(chat, request)!!
            assertEquals(indexed, archive.getJSONObject("legacy_active_snapshot").getString("indexed_checkpoint"))
            assertEquals(tool, archive.getString("legacy_tool_checkpoint_raw"))
            val saved = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(1)
            assertEquals("Visible partial", saved.getString("text"))
            assertFalse(saved.has("openai_protocol_output"))
            assertEquals("incomplete", db.listRequestAccounting(chat).single().getString("local_status"))
            try { db.listAllLocalFilePaths(); fail("Unknown archived ownership must stop cleanup") }
            catch (_: org.json.JSONException) { }
        }
    }

    @Test fun replacementDeletedAndOpenRouterOwnersCannotBeConsumedByLegacyRecovery() {
        ChatDatabase(context).use { db ->
            prepare(db, provider = "openrouter")
            assertFalse(db.recoverLegacyOpenAiRequestLocally(chat, request))
            db.setActiveResponse(chat, "resp_replacement", "local_replacement", "", "starting", 2L,
                "high", "high", false, 0, 0, true)
            assertFalse(db.recoverLegacyOpenAiRequestLocally(chat, request))
            assertEquals("local_replacement", db.getActiveResponse(chat)!!.getString("request_id"))
            assertNull(db.getRequestRecoveryArchive(chat, request))
            db.deleteChat(chat)
            assertFalse(db.recoverLegacyOpenAiRequestLocally(chat, "local_replacement"))
            assertNull(db.getChat(chat))
        }
    }

    @Test fun conflictingAndUnsupportedCapturedProvidersFailClosedInsideBothTerminalClaims() {
        for (provider in listOf("openrouter", "unknown", "unsupported-provider")) {
            ChatDatabase(context).use { db ->
                prepare(db, provider = provider, state = signedState())
                val before = db.getActiveResponse(chat)!!.getString("indexed_checkpoint")
                assertFalse(db.recoverLegacyOpenAiRequestLocally(chat, request))
                val assistant = JSONObject().put("role", "assistant").put("provider", "openrouter")
                    .put("request_id", request).put("text", "Wrong owner")
                assertFalse(db.finalizeCurrentChatIfActiveRequest(chat, "", null, null, listOf(assistant), request,
                    expectedProvider = "openrouter"))
                assertEquals(before, db.getActiveResponse(chat)!!.getString("indexed_checkpoint"))
                assertNull(db.getRequestRecoveryArchive(chat, request))
                assertEquals("running", db.listRequestAccounting(chat).single().getString("local_status"))
                db.deleteChat(chat)
            }
        }
    }

    @Test fun archiveWriteFailureRollsBackOwnerCheckpointAndAccounting() {
        ChatDatabase(context).use { db ->
            prepare(db, state = signedState())
            db.writableDatabase.execSQL("CREATE TRIGGER reject_legacy_archive BEFORE INSERT ON request_recovery_archive BEGIN SELECT RAISE(ABORT,'archive unavailable'); END")
            try { db.recoverLegacyOpenAiRequestLocally(chat, request); fail("Archive failure must abort retirement") }
            catch (error: RuntimeException) {
                assertTrue(error is android.database.sqlite.SQLiteException || error is IllegalStateException)
            }
            assertEquals(request, db.getActiveResponse(chat)!!.getString("request_id"))
            assertEquals("running", db.listRequestAccounting(chat).single().getString("local_status"))
            assertEquals(1, db.getChat(chat)!!.getJSONArray("messages").length())
            assertNull(db.getRequestRecoveryArchive(chat, request))
        }
    }

    @Test fun olderLocalArtifactsRemainOwnedWhenNewerCheckpointReplacesVisibleAnnotations() {
        val original = file()
        val image = file(".png")
        ChatDatabase(context).use { db ->
            prepare(db)
            db.upsertCurrentChat(chat, "Imported history", null, "high", listOf(
                JSONObject().put("role", "user").put("provider", "openai").put("request_id", request).put("text", "Original prompt"),
                JSONObject().put("role", "assistant").put("provider", "openai").put("request_id", request)
                    .put("text", "Earlier partial").put("status", "in_progress")
                    .put("annotations", JSONArray().put(JSONObject().put("type", "file_citation").put("file_id", "file_old")
                        .put("filename", original.name).put("local_path", original.absolutePath)))
                    .put("generated_images", JSONArray().put(JSONObject().put("local_path", image.absolutePath).put("state", "READY")))))
            val terminal = JSONObject().put("id", "resp_saved").put("status", "completed").put("output", JSONArray()
                .put(JSONObject().put("type", "message").put("role", "assistant")
                    .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "Newer partial")
                        .put("annotations", JSONArray().put(JSONObject().put("type", "file_citation").put("file_id", "file_new").put("filename", "remote-only.txt")))))))
            db.saveOpenAiToolCheckpoint(chat, request, JSONObject().put("version", 1).put("phase", "waiting")
                .put("response_id", "resp_saved").put("received_response", terminal))
            assertTrue(db.recoverLegacyOpenAiRequestLocally(chat, request))
        }
        ChatDatabase(context).use { db ->
            assertTrue(db.listAllLocalFilePaths().containsAll(listOf(original.absolutePath, image.absolutePath)))
            assertTrue(db.listLocalFilePaths(chat).contains(original.absolutePath))
            assertEquals("original bytes", original.readText())
            val archive = db.getRequestRecoveryArchive(chat, request)!!
            assertEquals(original.absolutePath, archive.getJSONObject("legacy_stored_message").getJSONArray("annotations").getJSONObject(0).getString("local_path"))
            db.deleteChat(chat)
            assertFalse(db.listAllLocalFilePaths().contains(original.absolutePath))
        }
    }

    @Test fun missingLegacyToolResultIsUnknownWhileSavedResultAndRawCheckpointRemainExact() {
        val answered = JSONObject().put("type", "function_call_output").put("call_id", "answered")
            .put("output", "{\"ok\":true,\"exact\":\"received result\"}")
        val checkpoint = JSONObject().put("version", 1).put("phase", "prepared")
            .put("parent_id", "resp_saved").put("prior_output", JSONArray()
                .put(JSONObject().put("type", "function_call").put("call_id", "answered").put("name", "read_attached_file"))
                .put(answered)
                .put(JSONObject().put("type", "function_call").put("call_id", "unknown").put("name", "patch_local_file")))
        ChatDatabase(context).use { db ->
            prepare(db)
            assertTrue(db.saveOpenAiToolCheckpoint(chat, request, checkpoint))
            assertTrue(db.recoverLegacyOpenAiRequestLocally(chat, request))
        }
        ChatDatabase(context).use { db ->
            val output = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(1).getJSONArray("openai_protocol_output")
            assertEquals(answered.toString(), output.getJSONObject(1).toString())
            val unknown = JSONObject(output.getJSONObject(3).getString("output"))
            assertEquals("outcome_unknown", unknown.getString("status"))
            assertFalse(unknown.has("cancelled"))
            assertEquals(checkpoint.toString(), db.getRequestRecoveryArchive(chat, request)!!.getString("legacy_tool_checkpoint_raw"))
        }
    }

    @Test fun malformedLegacyOwnershipShapesStopCleanupAfterRetirementAndReopen() {
        val original = file()
        val cases = listOf(
            JSONObject().put("attachments", JSONObject().put("path", original.absolutePath)),
            JSONObject().put("local_artifacts", "invalid"),
            JSONObject().put("attachments", JSONArray().put(JSONObject().put("path", 7))),
            JSONObject().put("attachments", JSONArray().put("invalid")),
            JSONObject().put("local_artifacts", JSONArray().put(JSONObject().put("source_path", JSONArray().put(original.absolutePath)))),
            JSONObject().put("local_artifacts", JSONArray().put(JSONObject().put("attachment", JSONObject().put("wire_path", 7))))
        )
        for (checkpoint in cases) {
            ChatDatabase(context).use { db ->
                prepare(db)
                assertTrue(db.saveOpenAiToolCheckpoint(chat, request, checkpoint))
                assertThrows(IllegalStateException::class.java) { db.listLegacyCheckpointAttachmentPaths() }
                assertTrue(db.recoverLegacyOpenAiRequestLocally(chat, request))
            }
            ChatDatabase(context).use { db ->
                assertEquals(checkpoint.toString(), db.getRequestRecoveryArchive(chat, request)!!.getString("legacy_tool_checkpoint_raw"))
                assertThrows(IllegalStateException::class.java) { db.listAllLocalFilePaths() }
                assertEquals("original bytes", original.readText())
                db.deleteChat(chat)
            }
        }
    }

    @Test fun legacyMetadataFingerprintAndReadableToolExportSurviveReopenAndExactSave() {
        val digest = "v2:" + "a".repeat(64) + ":" + "b".repeat(64)
        val output = JSONArray().put(JSONObject().put("type", "reasoning").put("encrypted_content", "signed-private"))
            .put(JSONObject().put("type", "function_call").put("call_id", "call_saved").put("name", "create_local_text_file"))
            .put(JSONObject().put("type", "function_call_output").put("call_id", "call_saved").put("output",
                JSONObject().put("ok", true).put("type", "local_file").put("filename", "report.md").toString()))
        val ledger = OpenAiUsageLedger.observe(null, "resp_saved", JSONObject().put("input_tokens", 9), "completed")
        val saved = JSONObject().put("role", "assistant").put("text", "Saved answer").put("provider", "openai")
            .put("request_id", request).put("status", "completed").put("openai_instructions_sha256", digest)
            .put("openai_protocol_output", output).put(OpenAiUsageLedger.FIELD, ledger)
        val signature = ChatMessageFingerprint.of(saved)
        ChatDatabase(context).use { it.upsertCurrentChat(chat, "Legacy", null, null, listOf(saved)) }
        ChatDatabase(context).use { db ->
            val restored = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(0)
            assertEquals(signature, ChatMessageFingerprint.of(restored))
            assertEquals(digest, restored.getString("openai_instructions_sha256"))
            assertEquals(output.toString(), restored.getJSONArray("openai_protocol_output").toString())
            assertEquals(ledger.toString(), restored.getJSONObject(OpenAiUsageLedger.FIELD).toString())
            db.upsertCurrentChat(chat, "Legacy", null, null, listOf(restored))
        }
        ChatDatabase(context).use { db ->
            val restored = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(0)
            for (format in DialogConversationExporter.Format.values()) {
                val exported = DialogConversationExporter.export("Legacy", listOf(restored), format = format)
                assertTrue(exported.contains("create_local_text_file — готово; файл: report.md"))
                assertFalse(exported.contains("signed-private"))
                assertFalse(exported.contains(digest))
            }
        }
    }
}
