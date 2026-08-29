package com.sergey.animevault.data.online

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sergey.animevault.data.db.AnimeVaultDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnlineStateRoomTest {
    @Test
    fun progressAndLibraryShareTransactionalDatabase() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AnimeVaultDatabase::class.java).build()
        try {
            val dao = database.onlineStateDao()
            dao.upsertProgress(OnlineWatchProgress(positionMs = 42L, durationMs = 100L).toEntity("p", "e"))
            dao.upsertLibrary(OnlineLibraryEntry("p", "P", "r", "Release", isFavorite = true).toEntity())
            assertEquals(42L, dao.getProgress("p", "e")?.positionMs)
            assertEquals(true, dao.getLibrary("p", "r")?.isFavorite)
        } finally { database.close() }
    }
}
