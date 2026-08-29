package com.sergey.animevault.data.download

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sergey.animevault.data.db.AnimeVaultDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
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
        } finally {
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
