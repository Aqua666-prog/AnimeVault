package com.sergey.animevault.data.online

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRemoteConfigValidationTest {
    private val known = setOf("a", "b")

    @Test
    fun acceptsKnownEnabledProvider() {
        assertTrue(
            validateRemoteConfigShape(
                ProviderRemoteConfig(providers = listOf(ProviderEndpointConfig("a", enabled = true))),
                known,
            ),
        )
    }

    @Test
    fun rejectsConfigThatDisablesEveryKnownProvider() {
        assertFalse(
            validateRemoteConfigShape(
                ProviderRemoteConfig(providers = listOf(ProviderEndpointConfig("a", enabled = false))),
                known,
            ),
        )
    }

    @Test
    fun rejectsDuplicateProviderIdsAndOversizedEndpointLists() {
        assertFalse(
            validateRemoteConfigShape(
                ProviderRemoteConfig(
                    providers = listOf(
                        ProviderEndpointConfig("a"),
                        ProviderEndpointConfig("a"),
                    ),
                ),
                known,
            ),
        )
        assertFalse(
            validateRemoteConfigShape(
                ProviderRemoteConfig(
                    providers = listOf(
                        ProviderEndpointConfig(
                            "a",
                            endpoints = List(ProviderEndpointRegistry.MAX_ENDPOINTS_PER_PROVIDER + 1) {
                                "https://example$it.test"
                            },
                        ),
                    ),
                ),
                known,
            ),
        )
    }

    @Test
    fun trustedEndpointHostRejectsUnrelatedDomain() {
        val trusted = setOf("api.example.org")
        assertTrue(isTrustedProviderEndpointHost("api.example.org", trusted))
        assertTrue(isTrustedProviderEndpointHost("edge.api.example.org", trusted))
        assertFalse(isTrustedProviderEndpointHost("example.org.evil.test", trusted))
        assertFalse(isTrustedProviderEndpointHost("evil.test", trusted))
    }
}
