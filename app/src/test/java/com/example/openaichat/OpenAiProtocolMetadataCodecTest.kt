package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenAiProtocolMetadataCodecTest {
    @Test
    fun exactTypedOutputAndDigestRoundTripThroughMetadataCodec() {
        val signed = "opaque-encrypted-reasoning".repeat(10000)
        val digest = "abcdef0123456789".repeat(4)
        val item = JSONObject().put("type", "reasoning").put("id", "rsn")
            .put("encrypted_content", signed)
        val original = JSONObject()
            .put("openai_protocol_output", JSONArray().put(item))
            .put("openai_instructions_sha256", digest)
        assertTrue(MessageMetadataCodec.allKeys.contains("openai_protocol_output"))
        assertTrue(MessageMetadataCodec.allKeys.contains("openai_instructions_sha256"))
        val restored = JSONObject()
        for (key in listOf("openai_protocol_output",
            "openai_instructions_sha256")) {
            val serialized = MessageMetadataCodec.serialized(original, key)
            assertNotNull(serialized)
            MessageMetadataCodec.decodeInto(restored, key, serialized)
        }
        assertEquals(digest, restored.getString("openai_instructions_sha256"))
        assertEquals(signed, restored.getJSONArray("openai_protocol_output")
            .getJSONObject(0).getString("encrypted_content"))
        assertEquals(original.getJSONArray("openai_protocol_output").toString(),
            restored.getJSONArray("openai_protocol_output").toString())
    }
}
