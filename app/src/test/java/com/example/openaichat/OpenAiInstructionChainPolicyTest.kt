package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiInstructionChainPolicyTest {
    private fun fingerprint(instructions: GenerationInstructions, key: String = "sk-chain-fixture",
                            model: String = "gpt-6-astra") =
        OpenAiInstructionChainPolicy.fingerprint(instructions, key, model)

    private fun instructions(system: String? = "system", developer: String? = "developer",
                             presentation: String? = "format") =
        GenerationInstructions(system, developer, presentation)

    private fun completed(id: String, signature: String) = JSONObject()
        .put("role", "assistant")
        .put("provider", "openai")
        .put("status", "completed")
        .put("response_id", id)
        .put(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD, signature)

    @Test fun reuseOnlyForMatchingCompletedImmediateAssistant() {
        val signature = fingerprint(instructions())
        val previous = completed("resp_1", signature)
        assertTrue(OpenAiInstructionChainPolicy.canReuse("resp_1", listOf(previous), signature))
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_2", listOf(previous), signature))
        assertFalse(OpenAiInstructionChainPolicy.canReuse(null, listOf(previous), signature))
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_1", listOf(previous), signature, regenerating = true))
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_1",
            listOf(previous, JSONObject().put("role", "user")), signature))
    }

    @Test fun changingEitherInstructionRoleOrPresentationBreaksChain() {
        val current = instructions()
        val signature = fingerprint(current)
        val previous = completed("resp_1", signature)
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_1", listOf(previous),
            fingerprint(instructions(system = "changed"))))
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_1", listOf(previous),
            fingerprint(instructions(developer = "changed"))))
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_1", listOf(previous),
            fingerprint(instructions(presentation = null))))
        assertEquals(signature, fingerprint(
            instructions(system = " system ", developer = " developer ", presentation = " format ")))
        // Same words in different privileged roles must not reuse the chain.
        assertFalse(signature == fingerprint(
            instructions(system = "developer", developer = "system")))
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_1", listOf(previous), ""))
    }

    @Test fun credentialModelAndLegacyInstructionOnlyHashCannotInheritChain() {
        val signature = fingerprint(instructions())
        val previous = completed("resp_1", signature)
        assertTrue(OpenAiInstructionChainPolicy.matchesCredential(signature, "sk-chain-fixture"))
        assertFalse(OpenAiInstructionChainPolicy.matchesCredential(signature, "sk-other-account"))
        assertFalse(OpenAiInstructionChainPolicy.matchesCredential(signature, ""))
        assertFalse(OpenAiInstructionChainPolicy.matchesCredential(null, "sk-chain-fixture"))
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_1", listOf(previous),
            fingerprint(instructions(), key = "sk-other-account")))
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_1", listOf(previous),
            fingerprint(instructions(), model = "gpt-6-sol")))
        assertEquals(signature, fingerprint(instructions(), model = " gpt-6-astra "))
        assertFalse(signature == fingerprint(instructions(), model = "GPT-6-ASTRA"))
        assertFalse(signature.contains("sk-chain-fixture"))
        // Even identical old instruction-only hashes never acquire a binding.
        val legacy = "f".repeat(64)
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_1",
            listOf(completed("resp_1", legacy)), legacy))
        assertFalse(OpenAiInstructionChainPolicy.matchesCredential(legacy, "sk-chain-fixture"))
        assertEquals("", fingerprint(instructions(), key = ""))
    }

    @Test fun legacyIncompleteFailedAndForeignTurnsForceReplay() {
        val signature = fingerprint(instructions())
        val previous = completed("resp_1", signature)
        val mutations = listOf(
            JSONObject(previous.toString()).apply { remove(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD) },
            JSONObject(previous.toString()).put("status", "incomplete"),
            JSONObject(previous.toString()).put("status", "failed"),
            JSONObject(previous.toString()).put("status", "cancelled"),
            JSONObject(previous.toString()).put("provider", "openrouter"),
            JSONObject(previous.toString()).put("response_id", "resp_other")
        )
        mutations.forEach { last ->
            assertFalse(last.toString(), OpenAiInstructionChainPolicy.canReuse("resp_1",
                listOf(last), signature))
        }
    }

}
