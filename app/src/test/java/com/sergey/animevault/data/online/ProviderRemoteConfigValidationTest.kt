package com.sergey.animevault.data.online

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRemoteConfigValidationTest {
    private val known = setOf("a", "b")
    private val now = 1_800_000_000_000L

    private fun config(providers: List<ProviderEndpointConfig>, version: Long = 1L) = ProviderRemoteConfig(
        configVersion = version,
        issuedAt = now - 1_000L,
        expiresAt = now + 60_000L,
        providers = providers,
    )

    @Test
    fun acceptsKnownEnabledProvider() {
        assertTrue(
            validateRemoteConfigShape(
                config(listOf(ProviderEndpointConfig("a", enabled = true))),
                known,
                now,
            ),
        )
    }

    @Test
    fun rejectsConfigThatDisablesEveryKnownProvider() {
        assertFalse(
            validateRemoteConfigShape(
                config(listOf(ProviderEndpointConfig("a", enabled = false))),
                known,
                now,
            ),
        )
    }

    @Test
    fun rejectsDuplicateProviderIdsAndOversizedEndpointLists() {
        assertFalse(
            validateRemoteConfigShape(
                config(
                    listOf(
                        ProviderEndpointConfig("a"),
                        ProviderEndpointConfig("a"),
                    ),
                ),
                known,
                now,
            ),
        )
        assertFalse(
            validateRemoteConfigShape(
                config(
                    listOf(
                        ProviderEndpointConfig(
                            "a",
                            endpoints = List(ProviderEndpointRegistry.MAX_ENDPOINTS_PER_PROVIDER + 1) {
                                "https://example$it.test"
                            },
                        ),
                    ),
                ),
                known,
                now,
            ),
        )
    }

    @Test
    fun rejectsExpiredAndRollbackConfigs() {
        assertFalse(validateRemoteConfigShape(config(listOf(ProviderEndpointConfig("a"))), known, now, 1L))
        val expired = config(listOf(ProviderEndpointConfig("a"))).copy(expiresAt = now - 1L)
        assertFalse(validateRemoteConfigShape(expired, known, now))
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
