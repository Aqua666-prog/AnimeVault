package com.sergey.animevault.data.download

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sergey.animevault.data.db.AnimeVaultDatabase
import com.sergey.animevault.data.online.OnlineStreamType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogicalDownloadReplacementTest {
    @Test
    fun anotherVariantReplacesSameEpisode() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AnimeVaultDatabase::class.java).build()
        try {
            val store = DownloadStore(context, database.downloadDao(), CoroutineScope(SupervisorJob() + Dispatchers.IO))
            store.put(entry("old", 720))
            store.put(entry("new", 1080))
            assertEquals(listOf("new"), store.getAll().map(DownloadEntry::id))
        } finally { database.close() }
    }

    private fun entry(id: String, quality: Int) = DownloadEntry(
        id, "p", "P", "r", "R", "e", 1.0, null, quality, null, null, null, OnlineStreamType.HLS,
    )
}
