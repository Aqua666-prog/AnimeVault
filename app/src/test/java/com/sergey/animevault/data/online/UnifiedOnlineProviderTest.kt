package com.sergey.animevault.data.online

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UnifiedOnlineProviderTest {
    @Test
    fun `catalog merges matching releases and keeps reconstructable source refs`() = runTest {
        val first = fakeProvider(
            id = "first",
            name = "Первый",
            card = card("first", "Атака титанов", "Shingeki no Kyojin", 16498),
            episode = directEpisode("first", "r1", "ep1", "AniDUB"),
        )
        val second = fakeProvider(
            id = "second",
            name = "Второй",
            card = card("second", "Вторжение гигантов", null, 16498),
            episode = lazyEpisode("second", "r2", "ep1"),
        )
        val unified = UnifiedOnlineProvider(listOf(first, second))

        val catalog = unified.getCatalog(page = 1, limit = 24, search = "титанов")

        assertThat(catalog.releases).hasSize(1)
        val merged = catalog.releases.single()
        assertThat(merged.providerId).isEqualTo(OnlineProviderIds.UNIFIED)
        val members = UnifiedReleaseReference.decode(merged.id).members
        assertThat(members)
            .containsExactly(
                UnifiedReleaseMember("first", "r1"),
                UnifiedReleaseMember("second", "r2"),
            )
    }

    @Test
    fun `unified search can continue when a child has unknown page count`() = runTest {
        val source = object : OnlineProvider {
            override val descriptor = OnlineProviderDescriptor(
                id = "unknown", name = "Unknown", description = "Unknown",
                capabilities = ProviderCapabilities(),
            )
            override suspend fun getCatalog(page: Int, limit: Int, search: String) = OnlineCatalogPage(
                releases = listOf(card("unknown", "Unknown", null, 400)),
                currentPage = page, totalPages = page, hasMore = true,
            )
            override suspend fun getRelease(id: String): OnlineReleaseDetails = error("Not used")
            override suspend fun resolveStreams(releaseId: String, episode: OnlineEpisode): List<OnlineStream> = emptyList()
        }
        val catalog = UnifiedOnlineProvider(listOf(source)).getCatalog(1, 24, "Unknown")
        assertThat(catalog.canLoadMore).isTrue()
    }

    @Test
    fun `release merges the same episode and playback delegates lazy resolution`() = runTest {
        val first = fakeProvider(
            id = "first",
            name = "Первый",
            card = card("first", "Тест", "Test", 42),
            episode = directEpisode("first", "r1", "ep1", "AniDUB"),
        )
        val second = fakeProvider(
            id = "second",
            name = "Второй",
            card = card("second", "Тест", "Test", 42),
            episode = lazyEpisode("second", "r2", "ep1"),
            resolved = listOf(
                OnlineStream(
                    id = "resolved",
                    quality = 720,
                    url = "https://second.example/ep1.m3u8",
                    type = OnlineStreamType.HLS,
                    translation = "Dream Cast",
                ),
            ),
        )
        val unified = UnifiedOnlineProvider(listOf(first, second))
        val catalog = unified.getCatalog(1, 24, "Test")
        val release = unified.getRelease(catalog.releases.single().id)

        assertThat(release.episodes).hasSize(1)
        val episode = release.episodes.single()
        assertThat(episode.sources).hasSize(2)
        assertThat(episode.hasStream).isTrue()

        val streams = unified.resolveStreams(release.id, episode)
        assertThat(streams.map(OnlineStream::translation))
            .containsExactly("AniDUB", "Dream Cast")
        assertThat(streams.mapNotNull(OnlineStream::sourceName))
            .containsAtLeast("Первый", "Второй")
        assertThat(streams.mapNotNull(OnlineStream::providerId))
            .containsExactly("first", "second")
    }

    @Test
    fun `blank unified catalog uses only browse-capable providers`() = runTest {
        val browse = fakeProvider(
            id = "browse",
            name = "Browse",
            card = card("browse", "Browse title", null, 1),
            episode = directEpisode("browse", "r2", "ep1", "Voice"),
        )
        val searchOnly = fakeProvider(
            id = "search",
            name = "Search",
            card = card("search", "Search only title", null, 2),
            episode = directEpisode("search", "r2", "ep1", "Voice"),
            capabilities = ProviderCapabilities(catalog = false),
        )

        val catalog = UnifiedOnlineProvider(listOf(browse, searchOnly))
            .getCatalog(page = 1, limit = 24, search = "")

        assertThat(catalog.releases.map(OnlineReleaseCard::name)).containsExactly("Browse title")
    }

    @Test
    fun `runtime disabled provider is excluded from unified catalog`() = runTest {
        val enabled = fakeProvider(
            id = "enabled",
            name = "Enabled",
            card = card("enabled", "Enabled result", null, 10),
            episode = directEpisode("enabled", "r2", "ep1", "Voice"),
        )
        val disabled = fakeProvider(
            id = "disabled",
            name = "Disabled",
            card = card("disabled", "Disabled result", null, 11),
            episode = directEpisode("disabled", "r2", "ep1", "Voice"),
        )

        val catalog = UnifiedOnlineProvider(
            providers = listOf(enabled, disabled),
            providerEnabled = { it != "disabled" },
        ).getCatalog(page = 1, limit = 24, search = "")

        assertThat(catalog.releases.map(OnlineReleaseCard::name)).containsExactly("Enabled result")
    }

    @Test
    fun `unified text search excludes url-or-slug adapters`() = runTest {
        val text = fakeProvider(
            id = "text",
            name = "Text",
            card = card("text", "Text result", null, 3),
            episode = directEpisode("text", "r2", "ep1", "Voice"),
        )
        val slug = fakeProvider(
            id = "slug",
            name = "Slug",
            card = card("slug", "Slug result", null, 4),
            episode = directEpisode("slug", "r2", "ep1", "Voice"),
            capabilities = ProviderCapabilities(
                catalog = false,
                searchMode = ProviderSearchMode.URL_OR_SLUG,
            ),
        )

        val catalog = UnifiedOnlineProvider(listOf(text, slug))
            .getCatalog(page = 1, limit = 24, search = "naruto")

        assertThat(catalog.releases.map(OnlineReleaseCard::name)).containsExactly("Text result")
    }

    @Test fun `manual split overrides shared ids without modifying source cards`() = runTest {
        val a = card("first", "Bleach", null, 269)
        val b = card("second", "Bleach", null, 269)
        val provider = UnifiedOnlineProvider(listOf(
            fakeProvider("first", "First", a, lazyEpisode("first", "r1", "1")),
            fakeProvider("second", "Second", b, lazyEpisode("second", "r2", "1")),
        ), keepReleaseSeparate = { source, _ -> source == "second" })
        assertThat(provider.getCatalog(1, 24, "Bleach").releases).hasSize(2)
        assertThat(a.providerId).isEqualTo("first")
        assertThat(b.id).isEqualTo("r2")
    }

    @Test fun `persisted group rejects conflicting current metadata`() = runTest {
        val first = fakeProvider("first", "First", card("first", "Title", null, 10), lazyEpisode("first", "r1", "ep1"))
        val second = fakeProvider("second", "Second", card("second", "Title", null, 11), lazyEpisode("second", "r2", "ep1"))
        val groupId = UnifiedReleaseReference(listOf(UnifiedReleaseMember("first", "r1"), UnifiedReleaseMember("second", "r2"))).encode()
        try {
            UnifiedOnlineProvider(listOf(first, second)).getRelease(groupId)
            org.junit.Assert.fail("Conflicting saved group must not expose merged episodes")
        } catch (expected: OnlineSourceException) {
            assertThat(expected.message).contains("Идентификаторы")
        }
        assertThat(first.getRelease("r1").id).isEqualTo("r1")
        assertThat(second.getRelease("r2").id).isEqualTo("r2")
    }

    @Test fun `one failing provider does not erase another providers results`() = runTest {
        val good = fakeProvider("first", "First", card("first", "Title", null, 10), lazyEpisode("first", "r1", "ep1"))
        val bad = object : OnlineProvider by good {
            override val descriptor = good.descriptor.copy(id = "second", name = "Unavailable")
            override suspend fun getCatalog(page: Int, limit: Int, search: String): OnlineCatalogPage =
                throw java.net.SocketTimeoutException("fixture timeout")
        }
        val result = UnifiedOnlineProvider(listOf(good, bad)).getCatalog(1, 24, "Title")
        assertThat(result.releases).hasSize(1)
        assertThat(UnifiedReleaseReference.decode(result.releases.single().id).members)
            .containsExactly(UnifiedReleaseMember("first", "r1"))
    }

    @Test fun `unified streams retain separate episode evidence without assuming cut equivalence`() = runTest {
        val first = fakeProvider("first", "First", card("first", "Title", null, 10), directEpisode("first", "r1", "a", "Voice"))
        val second = fakeProvider("second", "Second", card("second", "Title", null, 10), directEpisode("second", "r2", "b", "Voice"))
        val provider = UnifiedOnlineProvider(listOf(first, second))
        val id = UnifiedReleaseReference(listOf(UnifiedReleaseMember("first", "r1"), UnifiedReleaseMember("second", "r2"))).encode()
        val merged = provider.getRelease(id).episodes.single()
        val resolved = provider.resolveStreams(id, merged)
        assertThat(resolved.mapNotNull { it.episodeIdentity?.providerId }).containsAtLeast("first", "second")
        val one = resolved.first { it.episodeIdentity?.providerId == "first" }.episodeIdentity
        val two = resolved.first { it.episodeIdentity?.providerId == "second" }.episodeIdentity
        assertThat(EpisodeIdentityMatcher.compare(one, two).classification).isEqualTo(EpisodeIdentityClass.POSSIBLE)
        assertThat(first.getRelease("r1").episodes.single().id).isEqualTo("a")
        assertThat(second.getRelease("r2").episodes.single().id).isEqualTo("b")
    }

    @Test fun `special and shortened episode are not hidden inside a normal ordinal group`() = runTest {
        val normal = fakeProvider("first", "First", card("first", "Title", null, 10), directEpisode("first", "r1", "a", "Voice"))
        for (otherEpisode in listOf(directEpisode("second", "r2", "b", "Voice").copy(name = "Special 1"),
            directEpisode("second", "r2", "b", "Voice").copy(name = "Episodes 1-2"),
            directEpisode("second", "r2", "b", "Voice").copy(durationMs = 600000))) {
            val second = fakeProvider("second", "Second", card("second", "Title", null, 10), otherEpisode)
            val id = UnifiedReleaseReference(listOf(UnifiedReleaseMember("first", "r1"), UnifiedReleaseMember("second", "r2"))).encode()
            val episodes = UnifiedOnlineProvider(listOf(normal, second)).getRelease(id).episodes
            assertThat(episodes).hasSize(2)
            assertThat(episodes.flatMap { it.sources }.map { it.episodeId }).containsExactly("a", "b")
        }
    }

    private fun fakeProvider(
        id: String,
        name: String,
        card: OnlineReleaseCard,
        episode: OnlineEpisode,
        resolved: List<OnlineStream>? = null,
        capabilities: ProviderCapabilities = ProviderCapabilities(),
    ): OnlineProvider = object : OnlineProvider {
        override val descriptor = OnlineProviderDescriptor(
            id = id,
            name = name,
            description = name,
            capabilities = capabilities,
        )

        override suspend fun getCatalog(page: Int, limit: Int, search: String) = OnlineCatalogPage(
            releases = listOf(card),
            currentPage = page,
            totalPages = page,
        )

        override suspend fun getRelease(id: String) = OnlineReleaseDetails(
            providerId = descriptor.id,
            providerName = descriptor.name,
            id = card.id,
            alias = card.alias,
            name = card.name,
            englishName = card.englishName,
            posterUrl = card.posterUrl,
            year = card.year,
            type = card.type,
            season = card.season,
            episodeCount = 1,
            description = "Описание от ${descriptor.name}",
            notification = null,
            genres = listOf("Экшен"),
            isOngoing = false,
            isBlocked = false,
            episodes = listOf(episode),
            externalIds = card.externalIds,
        )

        override suspend fun resolveStreams(releaseId: String, episode: OnlineEpisode): List<OnlineStream> =
            resolved ?: episode.streams
    }

    private fun card(
        provider: String,
        name: String,
        english: String?,
        shikimoriId: Long,
    ) = OnlineReleaseCard(
        providerId = provider,
        providerName = provider,
        id = if (provider == "first") "r1" else "r2",
        alias = name,
        name = name,
        englishName = english,
        posterUrl = null,
        year = 2013,
        type = "TV",
        season = null,
        episodeCount = 1,
        isOngoing = false,
        externalIds = ExternalAnimeIds(shikimoriId = shikimoriId),
    )

    private fun directEpisode(
        provider: String,
        release: String,
        id: String,
        translation: String,
    ) = OnlineEpisode(
        providerId = provider,
        id = id,
        releaseId = release,
        ordinal = 1.0,
        name = "1 серия",
        previewUrl = null,
        durationMs = 1_400_000L,
        sortOrder = 1.0,
        streams = listOf(
            OnlineStream(
                id = "$provider:$id",
                quality = 1080,
                url = "https://$provider.example/$id.m3u8",
                type = OnlineStreamType.HLS,
                translation = translation,
            ),
        ),
    )

    private fun lazyEpisode(provider: String, release: String, id: String) = OnlineEpisode(
        providerId = provider,
        id = id,
        releaseId = release,
        ordinal = 1.0,
        name = "Episode 1",
        previewUrl = null,
        durationMs = 0L,
        sortOrder = 1.0,
        streams = emptyList(),
        sourceRef = "lazy:$id",
    )
}
