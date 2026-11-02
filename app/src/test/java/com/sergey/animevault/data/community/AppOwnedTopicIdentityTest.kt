package com.sergey.animevault.data.community

import com.sergey.animevault.data.online.ExternalAnimeIds
import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.data.online.OnlineReleaseDetails
import org.junit.Assert.*
import org.junit.Test

class AppOwnedTopicIdentityTest {
    private fun release(
        provider: String, name: String, english: String? = null, year: Int? = 2026,
        ids: ExternalAnimeIds = ExternalAnimeIds(),
    ) = OnlineReleaseDetails(
        providerId = provider, providerName = provider, id = "provider-specific-$provider",
        alias = "unused", name = name, englishName = english, posterUrl = null,
        year = year, type = "anime", season = null, episodeCount = null,
        description = null, notification = null, genres = emptyList(), isOngoing = false,
        isBlocked = false, episodes = emptyList(), externalIds = ids,
    )

    private fun episode(provider: String, ordinal: Double?, name: String, id: String = "$provider-episode") =
        OnlineEpisode(providerId = provider, id = id, releaseId = "rel", ordinal = ordinal,
            name = name, previewUrl = null, durationMs = 0, sortOrder = ordinal, streams = emptyList())

    @Test fun sameMalIdDoesNotDependOnVideoSourceOrLocalizedTitle() {
        val kodik = appCommunityIdentity(release("kodik", "Русское название",
            ids = ExternalAnimeIds(shikimoriId = 61967)))
        val yummy = appCommunityIdentity(release("yummy", "Different Title",
            ids = ExternalAnimeIds(malId = 61967, anilistId = 195604)))
        assertEquals("av1:mal:61967", kodik.appKey)
        assertEquals(kodik.appKey, yummy.appKey)
        assertEquals(null, kodik.provider)
        assertEquals(null, kodik.external)
    }

    @Test fun anilistIdentityIsStableAcrossProviders() {
        val first = appCommunityIdentity(release("animetka", "Аниме", ids=ExternalAnimeIds(anilistId=100)))
        val second = appCommunityIdentity(release("aniliberty", "Anime", ids=ExternalAnimeIds(anilistId=100)))
        assertEquals("av1:anilist:100", first.appKey)
        assertEquals(first.appKey, second.appKey)
    }

    @Test fun fallbackIsNormalizedAndSeasonSensitiveWithoutReleaseId() {
        val first=appCommunityIdentity(release("kodik", "Оригинал", english="  Example   Season 2  "))
        val second=appCommunityIdentity(release("animevost", "Another", english="example season 2"))
        assertTrue(first.appKey!!.matches(Regex("av1:title:[a-f0-9]{64}")))
        assertEquals(first.appKey,second.appKey)
        assertNotEquals(first.appKey, appCommunityIdentity(release("kodik","Example Season 3")).appKey)
        assertNotEquals(first.appKey, appCommunityIdentity(release("kodik","Example Season 2",year=2025)).appKey)
    }

    @Test fun episodeOrdinalsShareStableKeyAndFractionalIsDistinct() {
        val topic = appCommunityIdentity(release("kodik","Test",ids=ExternalAnimeIds(malId=77)))
        val one = appCommunityEpisodeIdentity(topic,episode("kodik",12.0,"Серия 12"))
        val another = appCommunityEpisodeIdentity(topic,episode("animevost",12.0,"Episode 12"))
        val half = appCommunityEpisodeIdentity(topic,episode("kodik",12.5,"Half"))
        assertEquals("regular:12",one.episodeKey)
        assertEquals(12,one.episodeNumber)
        assertEquals(one.episodeKey, another.episodeKey)
        assertEquals("fractional:12.5",half.episodeKey)
        assertEquals(null,half.episodeNumber)
    }

    @Test fun unknownEpisodeNumbersDoNotCollapseUnrelatedEpisodes() {
        val topic=appCommunityIdentity(release("kodik","Test"))
        val a=appCommunityEpisodeIdentity(topic,episode("kodik",null,"OVA One"))
        val b=appCommunityEpisodeIdentity(topic,episode("kodik",null,"OVA Two"))
        assertTrue(a.episodeKey!!.startsWith("special:"))
        assertNotEquals(a.episodeKey,b.episodeKey)
    }
}
