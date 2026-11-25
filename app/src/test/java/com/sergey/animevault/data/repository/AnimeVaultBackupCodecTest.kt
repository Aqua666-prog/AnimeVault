package com.sergey.animevault.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import com.sergey.animevault.data.online.OnlineLibraryEntry
import com.sergey.animevault.data.online.OnlineWatchProgress
import com.sergey.animevault.ui.preferences.CatalogPreset
import com.sergey.animevault.ui.preferences.VaultPersonalTitle
import java.io.IOException
import java.io.StringWriter

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

    @Test fun legacyOmittedCollectionsAreSafeToRestore() {
        val backup = AnimeVaultBackupCodec.decode("""{"formatVersion":1,"createdAt":1}""")
        assertThat(backup.metadata).isEmpty()
        assertThat(backup.onlineProgress).isEmpty()
    }

    @Test fun rejectsOversizedTrailingDataAndMalformedRows() {
        val json = """{"formatVersion":1,"createdAt":1}"""
        org.junit.Assert.assertThrows(com.google.gson.JsonParseException::class.java) {
            AnimeVaultBackupCodec.decode((json + " ".repeat(10)).reader(), characterLimit = json.length.toLong())
        }
        for (body in listOf(json.dropLast(1) + ""","progress":[null]}""", json + "{}", "{formatVersion:1}")) {
            org.junit.Assert.assertThrows(com.google.gson.JsonParseException::class.java) {
                AnimeVaultBackupCodec.decode(body)
            }
        }
    }

    @Test fun cancellationIsNotReportedAsDamagedBackup() {
        org.junit.Assert.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            AnimeVaultBackupCodec.decode("{}".reader(), checkCancellation = {
                throw kotlinx.coroutines.CancellationException()
            })
        }
    }

    @Test fun exportUsesTheSameCharacterBudgetAsImport() {
        val source = AnimeVaultBackup(createdAt = 1, personalTitles = mapOf("key" to VaultPersonalTitle(note = "愛\n")))
        val encoded = AnimeVaultBackupCodec.encode(source)
        org.junit.Assert.assertThrows(IOException::class.java) {
            AnimeVaultBackupCodec.encode(source, StringWriter(), characterLimit = encoded.length - 1L)
        }
        val writer = StringWriter()
        AnimeVaultBackupCodec.encode(source, writer, characterLimit = encoded.length.toLong())
        assertThat(AnimeVaultBackupCodec.decode(writer.toString().reader(), characterLimit = encoded.length.toLong()))
            .isEqualTo(source)
    }

    @Test fun exportHonorsCancellationWhileEncoding() {
        org.junit.Assert.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            AnimeVaultBackupCodec.encode(AnimeVaultBackup(createdAt = 1), StringWriter()) {
                throw kotlinx.coroutines.CancellationException()
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun newerFormatIsRejected() {
        AnimeVaultBackupCodec.decode("{\"formatVersion\":999,\"createdAt\":1}")
    }
}
