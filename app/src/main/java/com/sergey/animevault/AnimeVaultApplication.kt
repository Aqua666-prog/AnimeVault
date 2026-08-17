package com.sergey.animevault

import android.app.Application
import androidx.room.Room
import com.sergey.animevault.data.anilist.AniListSyncRepository
import com.sergey.animevault.data.db.AnimeVaultDatabase
import com.sergey.animevault.data.metadata.AnimeThemeRepository
import com.sergey.animevault.data.metadata.AniListFranchiseRepository
import com.sergey.animevault.data.metadata.AniListMetadataRepository
import com.sergey.animevault.data.repository.LibraryRepository
import com.sergey.animevault.data.repository.AnimeVaultBackupRepository
import com.sergey.animevault.data.scanner.LibraryScanner
import com.sergey.animevault.data.scanner.OfflineScanScheduler
import com.sergey.animevault.data.online.OnlineRepository
import com.sergey.animevault.data.online.OnlineProviderRegistry

class AnimeVaultApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.offlineScanScheduler.ensureScheduled()
    }
}

class AppContainer(application: Application) {
    val offlineScanScheduler = OfflineScanScheduler(application)
    private val database = Room.databaseBuilder(
        application,
        AnimeVaultDatabase::class.java,
        "anime_vault.db",
    ).addMigrations(AnimeVaultDatabase.MIGRATION_1_2, AnimeVaultDatabase.MIGRATION_2_3)
        .build()

    val animeThemeRepository = AnimeThemeRepository()
    val aniListMetadataRepository = AniListMetadataRepository()
    val aniListFranchiseRepository = AniListFranchiseRepository()
    val aniListSyncRepository = AniListSyncRepository(application, aniListMetadataRepository)

    val libraryRepository = LibraryRepository(
        context = application,
        database = database,
        scanner = LibraryScanner(application),
    )

    val onlineRepository = OnlineRepository(
        context = application,
        providers = OnlineProviderRegistry.create(application),
    )

    val backupRepository = AnimeVaultBackupRepository(application, database, onlineRepository)
}
