package com.sergey.animevault

import com.sergey.animevault.data.download.SharedDownloadStorage
import com.sergey.animevault.data.animetka.AnimetkaExtrasRepository
import com.sergey.animevault.data.animetka.createAnimetkaApi

import android.app.Application
import android.util.Log
import androidx.room.Room
import com.sergey.animevault.data.anilist.AniListSyncRepository
import com.sergey.animevault.data.clips.ClipPreferenceStore
import com.sergey.animevault.data.download.DownloadRepository
import com.sergey.animevault.data.download.DownloadRouteHealthTracker
import com.sergey.animevault.data.download.DownloadStore
import com.sergey.animevault.data.download.DownloadedMediaImporter
import com.sergey.animevault.data.download.NativeDownloadResult
import com.sergey.animevault.data.db.AnimeVaultDatabase
import com.sergey.animevault.data.metadata.AnimeThemesClipRepository
import com.sergey.animevault.data.metadata.AnimeThemeRepository
import com.sergey.animevault.data.metadata.AniListFranchiseRepository
import com.sergey.animevault.data.metadata.AniListMetadataRepository
import com.sergey.animevault.data.metadata.AniListTenraiMetadataFallback
import com.sergey.animevault.data.metadata.ShikimoriExtrasRepository
import com.sergey.animevault.data.metadata.TenraiClient
import com.sergey.animevault.data.metadata.TenraiExtrasRepository
import com.sergey.animevault.data.metadata.TenraiFileResponseCache
import com.sergey.animevault.data.metadata.TenraiMetadataRepository
import com.sergey.animevault.data.metadata.TitleExtrasRepository
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
import okhttp3.OkHttpClient

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
    val baseHttpClient: OkHttpClient = OkHttpClient.Builder().build()
    val providerEndpointRegistry = ProviderEndpointRegistry(application, baseClient = baseHttpClient)
    val clipPreferenceStore = ClipPreferenceStore(application)
    val animeThemesClipRepository = AnimeThemesClipRepository(client = baseHttpClient)
    val aniListMetadataRepository = AniListMetadataRepository(client = baseHttpClient)
    val tenraiResponseCache = TenraiFileResponseCache(application)
    val tenraiClient = TenraiClient(
        baseClient = baseHttpClient,
        responseCache = tenraiResponseCache,
    )
    val tenraiExtrasRepository = TenraiExtrasRepository(client = tenraiClient)
    val tenraiMetadataRepository = TenraiMetadataRepository(
        client = tenraiClient,
        fallback = AniListTenraiMetadataFallback(aniListMetadataRepository),
    )
    val shikimoriExtrasRepository = ShikimoriExtrasRepository(client = baseHttpClient)
    private val animetkaExtrasRepository = AnimetkaExtrasRepository(
        api = createAnimetkaApi(providerEndpointRegistry.clientFor(com.sergey.animevault.data.online.OnlineProviderIds.ANIMETKA)),
        enabled = { providerEndpointRegistry.isEnabled(com.sergey.animevault.data.online.OnlineProviderIds.ANIMETKA) },
    )
    val titleExtrasRepository = TitleExtrasRepository(
        animeThemes = animeThemesClipRepository,
        tenrai = tenraiExtrasRepository,
        shikimori = shikimoriExtrasRepository,
        animetka = animetkaExtrasRepository,
        tenraiMetadata = tenraiMetadataRepository,
    )
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
        AnimeVaultDatabase.MIGRATION_5_6,
        AnimeVaultDatabase.MIGRATION_6_7,
        AnimeVaultDatabase.MIGRATION_7_8,
    )
        .build()
    val downloadStore = DownloadStore(application, database.downloadDao(), applicationScope)
    val downloadRouteHealthTracker = DownloadRouteHealthTracker()
    val downloadedMediaImporter = DownloadedMediaImporter(database)
    val downloadRepository = DownloadRepository(application, downloadStore)

    val animeThemeRepository = AnimeThemeRepository()
    val aniListFranchiseRepository = AniListFranchiseRepository()
    val aniListSyncRepository = AniListSyncRepository(application, aniListMetadataRepository)

    val libraryRepository = LibraryRepository(
        context = application,
        database = database,
        scanner = LibraryScanner(application),
        metadataRepository = aniListMetadataRepository,
    )

    private val providerHealthTracker = ProviderHealthTracker()
    private val providerRemoteConfigRepository = ProviderRemoteConfigRepository(
        providerEndpointRegistry,
        client = baseHttpClient.newBuilder().build(),
    )
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
        onlineStateDao = database.onlineStateDao(),
        scope = applicationScope,
    )

    init {
        applicationScope.launch {
            onlineRepository.initializeState()
        }
        applicationScope.launch {
            providerRemoteConfigRepository.refresh()
        }
        applicationScope.launch {
            downloadRepository.initialize()
            downloadStore.getAll().forEach { entry ->
                if (entry.status == com.sergey.animevault.data.download.DownloadStatus.MISSING) {
                    runCatching { downloadedMediaImporter.remove(entry) }
                    return@forEach
                }
                if (
                    entry.isPlayableOffline &&
                    entry.localFilePath != null &&
                    !entry.localFilePath!!.startsWith("content://")
                ) {
                    runCatching {
                        val file = java.io.File(entry.localFilePath!!)
                        val result = NativeDownloadResult(
                            file = file,
                            mimeType = entry.localMimeType ?: "video/mp4",
                            selectedQuality = entry.quality,
                            totalItems = entry.totalItems.coerceAtLeast(1),
                        )
                        val published = SharedDownloadStorage.legacy(file, result.mimeType)
                        val localEpisodeId = downloadedMediaImporter.import(
                            entry,
                            result,
                            published,
                        )
                        if (entry.localEpisodeId != localEpisodeId) {
                            downloadStore.update(entry.id) { it.copy(localEpisodeId = localEpisodeId) }
                        }
                    }.onFailure { Log.w("AnimeVaultDownload", "Legacy library import failed: ${entry.id}", it) }
                }
            }
        }
    }

    val backupRepository = AnimeVaultBackupRepository(application, database, onlineRepository)

    suspend fun recordDownloadedPlayback(
        entry: com.sergey.animevault.data.download.DownloadEntry,
        positionMs: Long,
        durationMs: Long,
        ended: Boolean,
    ) {
        onlineRepository.recordDownloadedPlayback(entry, positionMs, durationMs, ended)
        entry.localEpisodeId?.let { episodeId ->
            libraryRepository.savePlaybackProgress(episodeId, positionMs, durationMs, ended)
        }
    }
}
