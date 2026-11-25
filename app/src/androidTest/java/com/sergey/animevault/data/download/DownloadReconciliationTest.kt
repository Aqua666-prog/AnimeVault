package com.sergey.animevault.data.download

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sergey.animevault.data.db.AnimeVaultDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import com.sergey.animevault.data.db.LibraryFolderEntity
import java.io.File
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadReconciliationTest {
    @Test
    fun completedRowWithoutFile_becomesMissing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AnimeVaultDatabase::class.java).build()
        try {
            val store = DownloadStore(context, database.downloadDao(), CoroutineScope(SupervisorJob() + Dispatchers.IO))
            store.put(testEntry())
            store.initialize()
            assertEquals(DownloadStatus.MISSING, store.get("missing")?.status)
            assertEquals(testEntry().localFilePath, store.get("missing")?.localFilePath)
        } finally {
            database.close()
        }
    }

    @Test
    fun returningFileKeepsEpisodeIdentity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AnimeVaultDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File.createTempFile("download-test", ".mp4", context.cacheDir)
        try {
            check(file.delete())
            val store = DownloadStore(context, database.downloadDao(), scope)
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
            scope.cancel()
            database.close()
        }
    }

    @Test
    fun staleOrFailedCommitCannotPublishALibraryRow() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AnimeVaultDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = DownloadStore(context, database.downloadDao(), scope)
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
            scope.cancel()
            database.close()
        }
    }

    private fun testEntry() = DownloadEntry(
        id = "missing", providerId = "p", providerName = "P", releaseId = "r", releaseName = "R",
        episodeId = "e", episodeOrdinal = 1.0, episodeName = null, quality = 720, translation = null,
        translationKey = null, sourceName = null, streamType = com.sergey.animevault.data.online.OnlineStreamType.HLS,
        status = DownloadStatus.COMPLETED, localFilePath = "/definitely/missing/animevault.mp4",
    )
}
