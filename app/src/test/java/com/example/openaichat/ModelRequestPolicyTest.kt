package com.example.openaichat

import org.junit.Assert.*
import org.junit.Test

class ModelRequestPolicyTest {
    @Test fun manualEffortBadgeMatchesTheSelectedLevel() {
        for (effort in listOf("low", "medium", "high", "xhigh", "max")) {
            assertEquals("⚡ $effort", ModelRequestPolicy.effortBadge(effort))
        }
    }

    @Test fun autoBadgeShowsOnlyAResolvedRequestLevel() {
        assertEquals("⚡ auto", ModelRequestPolicy.effortBadge("auto"))
        assertEquals("⚡ auto", ModelRequestPolicy.effortBadge("auto", ""))
        assertEquals("⚡ auto → low", ModelRequestPolicy.effortBadge("auto", "low"))
        assertEquals("⚡ auto → xhigh", ModelRequestPolicy.effortBadge("auto", "xhigh"))
        assertEquals("⚡ auto → max", ModelRequestPolicy.effortBadge("auto", "max"))
        assertEquals("⚡ high", ModelRequestPolicy.effortBadge("high", "medium"))
    }
}
