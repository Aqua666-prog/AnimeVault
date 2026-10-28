package com.sergey.animevault.data.online

import org.junit.Assert.*
import org.junit.Test

class EpisodeIdentityMatcherTest {
    private val a = EpisodeIdentity("one", "release", "ep", ExternalAnimeIds(malId = 10), 12.0, "Episode 12", 1_400_000,
        EpisodeEvidence("1", "api", true), EpisodeEvidence("1", "api", true), EpisodeEvidence("tv", "api", true))
    private val b = a.copy(providerId = "two", releaseId = "other", episodeId = "different")
    private fun classify(other: EpisodeIdentity?) = EpisodeIdentityMatcher.compare(a, other).classification
    @Test fun sharedTitleNumberNameAndDurationDoNotProveCut() {
        assertEquals(EpisodeIdentityClass.POSSIBLE, classify(b))
        assertEquals(EpisodeIdentityClass.UNKNOWN, classify(b.copy(titleIds = ExternalAnimeIds())))
        assertEquals(EpisodeIdentityClass.UNKNOWN, classify(null))
    }
    @Test fun sameOriginalRecordIsVerifiedAndMissingDurationIsNotInvented() {
        assertEquals(EpisodeIdentityClass.VERIFIED, classify(a))
        assertEquals(EpisodeIdentityClass.POSSIBLE, classify(b.copy(durationMs = 0)))
    }
    @Test fun conflictsAreNotOverriddenByManualApproval() {
        val conflicts = listOf(b.copy(season = EpisodeEvidence("2")), b.copy(part = EpisodeEvidence("2")),
            b.copy(kind = EpisodeEvidence("ova")), b.copy(kind = EpisodeEvidence("special")),
            b.copy(titleIds = ExternalAnimeIds(malId = 11)), b.copy(durationMs = 600_000),
            b.copy(edition = EpisodeEvidence("cut"), ordinal = 13.0))
        conflicts.forEach { assertEquals(EpisodeIdentityClass.CONFLICT, EpisodeIdentityMatcher.compare(a, it, true).classification) }
    }
    @Test fun namespaceNumbersNeverImplySameTitle() {
        assertEquals(EpisodeIdentityClass.UNKNOWN, classify(b.copy(titleIds = ExternalAnimeIds(anilistId = 10))))
    }
    @Test fun verifiedEpisodeAndCutNeedReliableEvidence() {
        val left = a.copy(edition = EpisodeEvidence("tv-cut", "catalogue", true), episodeReference = EpisodeEvidence("global:ep12", "metadata", true))
        val right = b.copy(edition = left.edition, episodeReference = left.episodeReference)
        assertEquals(EpisodeIdentityClass.VERIFIED, EpisodeIdentityMatcher.compare(left, right).classification)
        assertEquals(EpisodeIdentityClass.POSSIBLE, EpisodeIdentityMatcher.compare(left, right.copy(edition = right.edition.copy(reliable = false))).classification)
        assertEquals(EpisodeIdentityClass.CONFLICT, EpisodeIdentityMatcher.compare(left, right.copy(edition = EpisodeEvidence("bluray"))).classification)
    }
    @Test fun manualDecisionDoesNotModifyOriginalRecords() {
        val fingerprint = b.fingerprint()
        assertEquals(EpisodeIdentityClass.VERIFIED, EpisodeIdentityMatcher.compare(a, b, true).classification)
        assertEquals(EpisodeIdentityClass.CONFLICT, EpisodeIdentityMatcher.compare(a, b, false).classification)
        assertEquals(fingerprint, b.fingerprint())
        assertNotEquals(fingerprint, b.copy(ordinal = 13.0).fingerprint())
    }
    @Test fun durationChangeInvalidatesPersistedApprovalFingerprint() {
        assertNotEquals(b.fingerprint(), b.copy(durationMs = b.durationMs + 1).fingerprint())
        assertEquals(b.fingerprint(), b.copy(name = "Translated display label").fingerprint())
    }
    @Test fun explicitEditionStructureAndFormatsRemainConservative() {
        val tv = a.copy(edition = EpisodeEvidence("tv"), structure = EpisodeEvidence("single"))
        listOf(b.copy(kind = EpisodeEvidence("movie")), b.copy(kind = EpisodeEvidence("recap")),
            b.copy(edition = EpisodeEvidence("uncut")), b.copy(structure = EpisodeEvidence("combined:1-2")))
            .forEach { assertEquals(EpisodeIdentityClass.CONFLICT, EpisodeIdentityMatcher.compare(tv, it).classification) }
    }
}
