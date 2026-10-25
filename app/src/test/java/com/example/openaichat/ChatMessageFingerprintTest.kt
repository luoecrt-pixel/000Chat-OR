package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageFingerprintTest {
    @Test fun legacyV1FingerprintIsStableForOpenRouterMessages() {
        val message = JSONObject().put("role", "assistant").put("text", "hello").put("provider", "openrouter")
        assertEquals("f161e32fc417c9264d6a177c8246e2c8d6bb7b23c5eda841f9cbe0b285621801",
            ChatMessageFingerprint.of(message))
    }

    @Test
    fun samePersistedContentHasSameFingerprint() {
        val a = JSONObject()
            .put("role", "assistant")
            .put("text", "hello")
            .put("request_id", "req-1")
            .put("tools", JSONArray().put("web"))
        val b = JSONObject()
            .put("role", "assistant")
            .put("text", "hello")
            .put("request_id", "req-1")
            .put("tools", JSONArray().put("web"))

        assertEquals(ChatMessageFingerprint.of(a), ChatMessageFingerprint.of(b))
    }

    @Test
    fun changingPersistedFieldChangesFingerprint() {
        val a = JSONObject().put("role", "assistant").put("text", "hello")
        val b = JSONObject().put("role", "assistant").put("text", "hello!")
        assertNotEquals(ChatMessageFingerprint.of(a), ChatMessageFingerprint.of(b))
    }

    @Test
    fun onlyAttachmentManifestChangingMustInvalidatePersistedRow() {
        val original = JSONObject().put("role", "user").put("text", "photo")
            .put("attachments", JSONArray().put(JSONObject().put("path", "/photo").put("wire_path", "/wire-A")))
        val changed = JSONObject(original.toString())
            .put("attachments", JSONArray().put(JSONObject().put("path", "/photo").put("wire_path", "/wire-B")))
        assertNotEquals(ChatMessageFingerprint.of(original), ChatMessageFingerprint.of(changed))
    }

    @Test
    fun metadataOnlyEditsRequireSqliteUpdate() {
        val original = JSONObject().put("role", "assistant").put("text", "hello")
        for (key in MessageMetadataCodec.textKeys) {
            val updated = JSONObject(original.toString()).put(key, "changed metadata")
            assertNotEquals("metadata key $key", ChatMessageFingerprint.of(original), ChatMessageFingerprint.of(updated))
        }
        val trace = JSONObject(original.toString()).put("tool_trace", JSONArray().put(JSONObject().put("name", "github_get_file")))
        val usage = JSONObject(original.toString()).put("usage", JSONObject().put("cost", 0.20))
        assertNotEquals(ChatMessageFingerprint.of(original), ChatMessageFingerprint.of(trace))
        assertNotEquals(ChatMessageFingerprint.of(original), ChatMessageFingerprint.of(usage))
    }

    @Test
    fun omittedOptionalFieldAndEmptyArrayAreDistinct() {
        val a = JSONObject().put("role", "assistant")
        val b = JSONObject().put("role", "assistant").put("tools", JSONArray())
        assertNotEquals(ChatMessageFingerprint.of(a), ChatMessageFingerprint.of(b))
    }
}
