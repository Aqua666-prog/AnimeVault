package com.sergey.animevault.data.online

import com.sergey.animevault.testing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPersistenceStabilityTest {
    private val episode = OnlineEpisode("fixture", "ep", "release", 1.0, "Episode 1", null, 100_000, 1.0, emptyList())
    private val release = OnlineReleaseDetails("fixture", "Fixture", "release", "release", "Title", null, null,
        null, null, null, 12, null, null, emptyList(), false, false, listOf(episode))
    private val provider = object : OnlineProvider {
        override val descriptor = OnlineProviderDescriptor("fixture", "Fixture", "Fixture only")
        override suspend fun getCatalog(page: Int, limit: Int, search: String): OnlineCatalogPage = error("No network")
        override suspend fun getRelease(id: String) = release
    }

    @Test fun suspendedLibrarySaveCannotBeOvertakenByNewerProgressSave() = runTest {
        val dao = MemoryOnlineStateDao()
        val repository = OnlineRepository(MemoryPreferencesContext(), listOf(provider), onlineStateDao = dao, scope = backgroundScope)
        val blocked = CompletableDeferred<Unit>()
        var reads = 0
        dao.beforeLibraryRead = { if (++reads == 1) blocked.await() }
        val old = launch { repository.recordPlayback(release, episode, 10_000, 100_000, false) }
        runCurrent()
        val newer = launch { repository.recordPlayback(release, episode, 20_000, 100_000, false) }
        runCurrent()
        assertEquals(10_000L, dao.getProgress("fixture", "ep")?.positionMs)
        blocked.complete(Unit)
        old.join(); newer.join()
        assertEquals(20_000L, dao.getProgress("fixture", "ep")?.positionMs)
        assertEquals(20_000L, dao.getLibrary("fixture", "release")?.lastPositionMs)
    }
    @Test fun preferencesAndProgressSurviveRepositoryRecreationWithoutReassigningIds() = runTest {
        val context = MemoryPreferencesContext()
        val dao = MemoryOnlineStateDao()
        val first = OnlineRepository(context, listOf(provider), onlineStateDao = dao, scope = backgroundScope)
        first.setPreferredQuality("fixture", "release", null) // Explicit Auto
        first.setPreferredTranslation("fixture", "release", "voice-stable")
        first.recordPlayback(release, episode, 40_000, 100_000, false)
        val second = OnlineRepository(context, listOf(provider), onlineStateDao = dao, scope = backgroundScope)
        runCurrent()
        assertNull(second.preferredQuality("fixture", "release"))
        assertEquals("voice-stable", second.preferredTranslation("fixture", "release"))
        assertEquals(40_000L, second.episodeProgress("fixture", "ep").positionMs)
        second.setPreferredQuality("fixture", "release", 720)
        val third = OnlineRepository(context, listOf(provider), onlineStateDao = dao, scope = backgroundScope)
        assertEquals(720, third.preferredQuality("fixture", "release"))
        assertEquals("ep", dao.getLibrary("fixture", "release")?.lastEpisodeId)
    }
}
