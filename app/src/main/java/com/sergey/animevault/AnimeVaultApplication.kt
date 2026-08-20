package com.sergey.animevault

import android.app.Application
import android.util.Log
import androidx.room.Room
import com.sergey.animevault.data.anilist.AniListSyncRepository
import com.sergey.animevault.data.download.DownloadRepository
import com.sergey.animevault.data.download.DownloadStore
import com.sergey.animevault.data.download.DownloadedMediaImporter
import com.sergey.animevault.data.download.NativeDownloadResult
import com.sergey.animevault.data.db.AnimeVaultDatabase
import com.sergey.animevault.data.metadata.AnimeThemeRepository
import com.sergey.animevault.data.metadata.AniListFranchiseRepository
import com.sergey.animevault.data.metadata.AniListMetadataRepository
import com.sergey.animevault.data.repository.LibraryRepository
import com.sergey.animevault.ui.preferences.UiPreferences
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
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val uiPreferences = UiPreferences(application)
    val offlineScanScheduler = OfflineScanScheduler(application)
    private val database = Room.databaseBuilder(
        application,
        AnimeVaultDatabase::class.java,
        "anime_vault.db",
    ).addMigrations(
        AnimeVaultDatabase.MIGRATION_1_2,
        AnimeVaultDatabase.MIGRATION_2_3,
        AnimeVaultDatabase.MIGRATION_3_4,
        AnimeVaultDatabase.MIGRATION_4_5,
    )
        .build()
    val downloadStore = DownloadStore(application, database.downloadDao())
    val downloadedMediaImporter = DownloadedMediaImporter(database)
    val downloadRepository = DownloadRepository(application, downloadStore)

    val animeThemeRepository = AnimeThemeRepository()
    val aniListMetadataRepository = AniListMetadataRepository()
    val aniListFranchiseRepository = AniListFranchiseRepository()
    val aniListSyncRepository = AniListSyncRepository(application, aniListMetadataRepository)

    val libraryRepository = LibraryRepository(
        context = application,
        database = database,
        scanner = LibraryScanner(application),
        metadataRepository = aniListMetadataRepository,
    )

    private val providerHealthTracker = ProviderHealthTracker()
    val providerEndpointRegistry = ProviderEndpointRegistry(application)
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
        applicationScope.launch {
            providerRemoteConfigRepository.refresh()
        }
        applicationScope.launch {
            downloadStore.entries.value
                .filter { it.isPlayableOffline && it.localFilePath != null }
                .forEach { entry ->
                    runCatching {
                        val file = java.io.File(entry.localFilePath!!)
                        downloadedMediaImporter.import(
                            entry,
                            NativeDownloadResult(
                                file = file,
                                mimeType = entry.localMimeType ?: "video/mp4",
                                selectedQuality = entry.quality,
                                totalItems = entry.totalItems.coerceAtLeast(1),
                            ),
                        )
                    }.onFailure { Log.w("AnimeVaultDownload", "Legacy library import failed: ${entry.id}", it) }
                }
        }
    }

    val backupRepository = AnimeVaultBackupRepository(application, database, onlineRepository)
}
