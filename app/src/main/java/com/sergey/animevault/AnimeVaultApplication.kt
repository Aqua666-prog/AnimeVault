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
import com.sergey.animevault.data.online.ProviderHealthTracker
import com.sergey.animevault.data.online.ProviderEndpointRegistry
import com.sergey.animevault.data.online.ProviderRemoteConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
    ).addMigrations(AnimeVaultDatabase.MIGRATION_1_2, AnimeVaultDatabase.MIGRATION_2_3, AnimeVaultDatabase.MIGRATION_3_4)
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

    private val providerHealthTracker = ProviderHealthTracker()
    val providerEndpointRegistry = ProviderEndpointRegistry(application)
    private val providerConfigScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val providerRemoteConfigRepository = ProviderRemoteConfigRepository(providerEndpointRegistry)
    private val onlineProviders = OnlineProviderRegistry.create(
        application = application,
        healthTracker = providerHealthTracker,
        endpointRegistry = providerEndpointRegistry,
    )

    val onlineRepository = OnlineRepository(
        context = application,
        providers = onlineProviders,
        healthTracker = providerHealthTracker,
        endpointRegistry = providerEndpointRegistry,
    )

    init {
        providerConfigScope.launch {
            providerRemoteConfigRepository.refresh()
        }
    }

    val backupRepository = AnimeVaultBackupRepository(application, database, onlineRepository)
}
