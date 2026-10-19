package com.sergey.animevault.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import com.sergey.animevault.data.online.OnlineLibraryEntry
import com.sergey.animevault.data.online.OnlineWatchProgress
import com.sergey.animevault.ui.preferences.CatalogPreset
import com.sergey.animevault.ui.preferences.VaultPersonalTitle

class AnimeVaultBackupCodecTest {
    @Test fun roundTripPreservesCoreState() {
        val source = AnimeVaultBackup(
            createdAt = 123L,
            progress = listOf(BackupProgress("content://episode", 500L, false, 700L, firstPlayedAt = 100L, playCount = 2)),
            metadata = listOf(BackupMetadata("key", "anilist", 42L, canonicalTitle = "Title", updatedAt = 9L)),
            onlineLinks = listOf(BackupOnlineLink("key", "provider", "rel", "Release", linkedAt = 5L)),
            groupingOverrides = listOf(BackupGroupingOverride("content://episode", "content://tree", "key", "Title", 4L)),
            onlineLibrary = listOf(OnlineLibraryEntry("p", "Provider", "release", "Title", isFavorite = true)),
            onlineProgress = mapOf("p|episode" to OnlineWatchProgress(positionMs = 5L, durationMs = 10L)),
            personalTitles = mapOf("online:p:release" to VaultPersonalTitle(rating = 5, note = "Отлично")),
            catalogPresets = listOf(CatalogPreset("Романтика", "p")),
            titleLists = mapOf("online:p:release" to "WATCHING", "local:42" to "ON_HOLD"),
            localFavorites = listOf(42L),
            localTitleListsBySourceKey = mapOf("folder:anime" to "ON_HOLD"),
            localFavoriteSourceKeys = listOf("folder:anime"),
        )
        assertThat(AnimeVaultBackupCodec.decode(AnimeVaultBackupCodec.encode(source))).isEqualTo(source)
    }

    @Test fun legacyV1StillDecodes() {
        val decoded = AnimeVaultBackupCodec.decode(
            """{"formatVersion":1,"createdAt":1,"progress":[{"fileUri":"x","positionMs":5,"isCompleted":false,"lastWatchedAt":7}]}"""
        )
        assertThat(decoded.formatVersion).isEqualTo(1)
        assertThat(decoded.progress.single().playCount).isEqualTo(0)
        assertThat(decoded.personalTitles).isNull()
        assertThat(decoded.catalogPresets).isNull()
        assertThat(decoded.titleLists).isNull()
        assertThat(decoded.localFavorites).isNull()
    }

    @Test fun legacyV2WithoutListFieldsDoesNotInventPersonalData() {
        val decoded = AnimeVaultBackupCodec.decode("""{"formatVersion":2,"createdAt":1}""")
        assertThat(decoded.titleLists).isNull()
        assertThat(decoded.localFavorites).isNull()
    }

    @Test(expected = IllegalArgumentException::class)
    fun newerFormatIsRejected() {
        AnimeVaultBackupCodec.decode("{\"formatVersion\":999,\"createdAt\":1}")
    }
}
