package com.sergey.animevault.data.download

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.net.toUri
import com.sergey.animevault.data.db.AnimeVaultDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import com.sergey.animevault.data.db.LibraryFolderEntity
import java.io.File
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadReconciliationTest {
    @Test
    fun completedRowWithoutFile_becomesMissing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AnimeVaultDatabase::class.java).build()
        val job = SupervisorJob()
        try {
            val store = DownloadStore(context, database.downloadDao(), CoroutineScope(job + Dispatchers.IO))
            store.put(testEntry())
            store.initialize()
            assertEquals(DownloadStatus.MISSING, store.get("missing")?.status)
            assertEquals(testEntry().localFilePath, store.get("missing")?.localFilePath)
        } finally {
            job.cancelAndJoin()
            database.close()
        }
    }

    @Test
    fun returningFileKeepsEpisodeIdentity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AnimeVaultDatabase::class.java).build()
        val job = SupervisorJob()
        val file = File.createTempFile("download-test", ".mp4", context.cacheDir)
        try {
            check(file.delete())
            val store = DownloadStore(context, database.downloadDao(), CoroutineScope(job + Dispatchers.IO))
            store.put(testEntry().copy(localFilePath = file.absolutePath, localEpisodeId = 42L))
            store.initialize()
            assertEquals(DownloadStatus.MISSING, store.get("missing")?.status)
            file.writeBytes(byteArrayOf(1))
            store.initialize()
            val recovered = store.get("missing")
            assertEquals(DownloadStatus.COMPLETED, recovered?.status)
            assertEquals(42L, recovered?.localEpisodeId)
        } finally {
            file.delete()
            job.cancelAndJoin()
            database.close()
        }
    }

    @Test
    fun staleOrFailedCommitCannotPublishALibraryRow() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AnimeVaultDatabase::class.java).build()
        val job = SupervisorJob()
        try {
            val store = DownloadStore(context, database.downloadDao(), CoroutineScope(job + Dispatchers.IO))
            store.put(testEntry().copy(status = DownloadStatus.VERIFYING, operationToken = "current"))
            val stale = store.commitVerified(database, "missing", "stale") {
                throw AssertionError("Stale operation reached import")
            }
            assertNull(stale)
            val failed = runCatching {
                store.commitVerified(database, "missing", "current") {
                    database.libraryDao().upsertFolder(
                        LibraryFolderEntity("animevault://test", "Test", 1L),
                    )
                    error("Rollback test")
                }
            }
            assertTrue(failed.isFailure)
            assertNull(database.libraryDao().getFolder("animevault://test"))
            assertEquals(DownloadStatus.VERIFYING, store.get("missing")?.status)
        } finally {
            job.cancelAndJoin()
            database.close()
        }
    }

    @Test
    fun distinctEpisodesMayShareAnOrdinal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AnimeVaultDatabase::class.java).build()
        val first = File.createTempFile("episode-a", ".mp4", context.cacheDir)
        val second = File.createTempFile("episode-b", ".mp4", context.cacheDir)
        try {
            val importer = DownloadedMediaImporter(database)
            val idA = importer.import(
                testEntry().copy(id = "first", episodeId = "one"),
                NativeDownloadResult(first.apply { writeBytes(byteArrayOf(1)) }, "video/mp4", null, 1),
                SharedDownloadStorage.legacy(first, "video/mp4"),
            )
            val idB = importer.import(
                testEntry().copy(id = "second", episodeId = "two"),
                NativeDownloadResult(second.apply { writeBytes(byteArrayOf(2)) }, "video/mp4", null, 1),
                SharedDownloadStorage.legacy(second, "video/mp4"),
            )
            assertTrue(idA != idB)
            assertEquals(setOf(idA, idB), database.libraryDao().getAllEpisodeEntities().map { it.id }.toSet())
        } finally {
            first.delete()
            second.delete()
            database.close()
        }
    }

    @Test
    fun publicationPreservesTheVerifiedFileLength() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File.createTempFile("shared-download", ".mp4", context.cacheDir)
        val bytes = byteArrayOf(0, 0, 0, 8, 102, 116, 121, 112)
        try {
            source.writeBytes(bytes)
            val published = SharedDownloadStorage.publish(
                context,
                testEntry().copy(releaseName = "AnimeVault publication test"),
                NativeDownloadResult(source, "video/mp4", null, 1),
            )
            try {
                assertEquals(bytes.size.toLong(), published.sizeBytes)
                val stored = if (published.isShared) {
                    context.contentResolver.openInputStream(published.location.toUri())!!.use { it.readBytes() }
                } else {
                    File(published.location).readBytes()
                }
                assertArrayEquals(bytes, stored)
            } finally {
                SharedDownloadStorage.delete(context, published.location)
            }
        } finally {
            source.delete()
        }
    }

    private fun testEntry() = DownloadEntry(
        id = "missing", providerId = "p", providerName = "P", releaseId = "r", releaseName = "R",
        episodeId = "e", episodeOrdinal = 1.0, episodeName = null, quality = 720, translation = null,
        translationKey = null, sourceName = null, streamType = com.sergey.animevault.data.online.OnlineStreamType.HLS,
        status = DownloadStatus.COMPLETED, localFilePath = "/definitely/missing/animevault.mp4",
    )
}
