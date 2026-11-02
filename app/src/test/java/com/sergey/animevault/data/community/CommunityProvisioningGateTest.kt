package com.sergey.animevault.data.community

import org.junit.Assert.*
import org.junit.Test

class CommunityProvisioningGateTest {
    @Test fun repeatedRefreshCannotCreatePollingLoop() {
        var now = 0L
        val gate = CommunityProvisioningGate { now }
        assertTrue(gate.claim("source:212"))
        repeat(20) { assertFalse(gate.claim("source:212")) }
        now = 299_999L
        assertFalse(gate.claim("source:212"))
        now = 300_000L
        assertTrue(gate.claim("source:212"))
    }
    @Test fun seasonsAndProvidersHaveIndependentCooldowns() {
        val gate = CommunityProvisioningGate { 0L }
        assertTrue(gate.claim("kodik:shiki:212"))
        assertTrue(gate.claim("kodik:shiki:213"))
        assertTrue(gate.claim("animetka:608"))
        assertFalse(gate.claim("kodik:shiki:212"))
    }
    @Test fun episodesAndSyntheticSourcesNeverAutoCreate() {
        val gate = CommunityProvisioningGate { 0L }
        assertFalse(gate.supports(CommunityIdentity(provider="all", external="212")))
        assertFalse(gate.supports(CommunityIdentity(provider="kodik", external="shiki:212", episodeExternal="12")))
        assertFalse(gate.supports(CommunityIdentity(provider="kodik", external="")))
        assertTrue(gate.supports(CommunityIdentity(provider="kodik", external="shiki:212")))
    }
    @Test fun longBrowsingSessionHasBoundedAttemptCache() {
        val gate = CommunityProvisioningGate { 0L }
        repeat(129) { assertTrue(gate.claim("title:$it")) }
        assertTrue(gate.claim("title:0")) // oldest entry was evicted, not an ever-growing history
        assertFalse(gate.claim("title:128"))
    }
}
