package com.sergey.animevault.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import com.sergey.animevault.data.online.OnlineLibraryEntry
import com.sergey.animevault.data.online.OnlineWatchProgress

class AnimeVaultBackupCodecTest {
    @Test fun roundTripPreservesCoreState() {
        val source = AnimeVaultBackup(
            createdAt = 123L,
            progress = listOf(BackupProgress("content://episode", 500L, false, 700L)),
            metadata = listOf(BackupMetadata("key", "anilist", 42L, canonicalTitle = "Title", updatedAt = 9L)),
            onlineLinks = listOf(BackupOnlineLink("key", "provider", "rel", "Release", linkedAt = 5L)),
            groupingOverrides = listOf(BackupGroupingOverride("content://episode", "content://tree", "key", "Title", 4L)),
            onlineLibrary = listOf(OnlineLibraryEntry("p", "Provider", "release", "Title", isFavorite = true)),
            onlineProgress = mapOf("p|episode" to OnlineWatchProgress(positionMs = 5L, durationMs = 10L)),
        )
        assertThat(AnimeVaultBackupCodec.decode(AnimeVaultBackupCodec.encode(source))).isEqualTo(source)
    }

    @Test(expected = IllegalArgumentException::class)
    fun newerFormatIsRejected() {
        AnimeVaultBackupCodec.decode("{\"formatVersion\":999,\"createdAt\":1}")
    }
}
