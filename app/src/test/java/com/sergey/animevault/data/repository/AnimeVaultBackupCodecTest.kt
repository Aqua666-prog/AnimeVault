package com.sergey.animevault.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import com.sergey.animevault.data.online.OnlineLibraryEntry
import com.sergey.animevault.data.online.OnlineWatchProgress

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
        )
        assertThat(AnimeVaultBackupCodec.decode(AnimeVaultBackupCodec.encode(source))).isEqualTo(source)
    }

    @Test fun legacyV1StillDecodes() {
        val decoded = AnimeVaultBackupCodec.decode(
            """{"formatVersion":1,"createdAt":1,"progress":[{"fileUri":"x","positionMs":5,"isCompleted":false,"lastWatchedAt":7}]}"""
        )
        assertThat(decoded.formatVersion).isEqualTo(1)
        assertThat(decoded.progress.single().playCount).isEqualTo(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun newerFormatIsRejected() {
        AnimeVaultBackupCodec.decode("{\"formatVersion\":999,\"createdAt\":1}")
    }
}
