package com.sergey.animevault

import com.sergey.animevault.data.download.SharedDownloadStorage
import com.sergey.animevault.data.notifications.EpisodeAlertScheduler
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
import com.sergey.animevault.data.metadata.CharacterLoadDiagnostic
import com.sergey.animevault.data.metadata.CharacterHttpDiagnosticsInterceptor
import com.sergey.animevault.data.metadata.CharacterSource
import com.sergey.animevault.data.repository.LibraryRepository
import com.sergey.animevault.data.titles.*
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import okhttp3.OkHttpClient

class AnimeVaultApplication : Application(), coil3.SingletonImageLoader.Factory {
    override fun newImageLoader(context: android.content.Context): coil3.ImageLoader =
        coil3.ImageLoader.Builder(context)
            .diskCache {
                coil3.disk.DiskCache.Builder()
                    .directory(context.cacheDir.resolve("artwork").toOkioPath())
                    .maxSizeBytes(128L * 1024 * 1024)
                    .build()
            }
            .components {
                add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = {
                    OkHttpClient.Builder()
                        .addInterceptor(CharacterHttpDiagnosticsInterceptor(CharacterSource.SHIKIMORI_IO, ::logCharacterDiagnostic))
                        .addInterceptor { chain ->
                            val request = chain.request()
                            val imageRequest = if (request.url.host == "shikimori.io") {
                                request.newBuilder()
                                    .header("User-Agent", com.sergey.animevault.data.online.animeVaultUserAgent())
                                    .header("Referer", "https://shikimori.one/")
                                    .build()
                            } else request
                            chain.proceed(imageRequest)
                        }.build()
                }))
            }
            .build()

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.offlineScanScheduler.ensureScheduled()
        EpisodeAlertScheduler.reconcile(this, container.uiPreferences)
    }
}

class AppContainer(application: Application) {
    // Capture upgrade evidence before any new-install database/preferences can be created.
    val onboardingStore = com.sergey.animevault.data.onboarding.createOnboardingStore(application)
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val accountRepository by lazy {
        com.sergey.animevault.data.auth.FirebaseAccountRepository(application)
    }
    val uiPreferences = UiPreferences(application)
    val animeTitles = AnimeTitleStore(application, applicationScope)
    val baseHttpClient: OkHttpClient = OkHttpClient.Builder().build()
    val updateRepository by lazy {
        com.sergey.animevault.data.update.GitHubUpdateRepository(application, baseHttpClient)
    }
    val providerEndpointRegistry = ProviderEndpointRegistry(application, baseClient = baseHttpClient)
    val clipPreferenceStore = ClipPreferenceStore(application)
    val animeThemesClipRepository = AnimeThemesClipRepository(client = baseHttpClient)
    val aniListMetadataRepository = AniListMetadataRepository(client = baseHttpClient, onAnime = { animeTitles.accept(it) })
    val tenraiResponseCache = TenraiFileResponseCache(application)
    val tenraiClient = TenraiClient(
        baseClient = baseHttpClient,
        responseCache = tenraiResponseCache,
    )
    val tenraiExtrasRepository = TenraiExtrasRepository(client = tenraiClient)
    val tenraiMetadataRepository = TenraiMetadataRepository(
        client = tenraiClient,
        fallback = AniListTenraiMetadataFallback(aniListMetadataRepository),
        onTitles = { malId, names -> animeTitles.put("mal:$malId", names) },
    )
    val shikimoriExtrasRepository = ShikimoriExtrasRepository(client = baseHttpClient, diagnosticSink = ::logCharacterDiagnostic, onAnime = { animeTitles.accept(it) })
    private val animetkaExtrasRepository = AnimetkaExtrasRepository(
        api = createAnimetkaApi(
            providerEndpointRegistry.clientFor(com.sergey.animevault.data.online.OnlineProviderIds.ANIMETKA),
            diagnosticSink = ::logCharacterDiagnostic,
        ),
        enabled = { providerEndpointRegistry.isEnabled(com.sergey.animevault.data.online.OnlineProviderIds.ANIMETKA) },
    )
    val titleExtrasRepository = TitleExtrasRepository(
        animeThemes = animeThemesClipRepository,
        tenrai = tenraiExtrasRepository,
        shikimori = shikimoriExtrasRepository,
        animetka = animetkaExtrasRepository,
        tenraiMetadata = tenraiMetadataRepository,
        characterDiagnosticSink = ::logCharacterDiagnostic,
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
        titleStore = animeTitles,
        titleQueryLookup = { query ->
            // Independent failures must not prevent the other metadata source from helping search.
            com.sergey.animevault.util.runCatchingCancellable { shikimoriExtrasRepository.searchTitles(query) }
            if (animeTitles.index.value.searchQueries(query).isEmpty()) {
                com.sergey.animevault.util.runCatchingCancellable { aniListMetadataRepository.searchAnime(query) }
            }
            Unit
        },
    )

    init {
        applicationScope.launch {
            kotlinx.coroutines.flow.combine(libraryRepository.observeLibrary(), libraryRepository.observeAllTitleMetadata()) { titles, metadata ->
                titles to metadata
            }.collect { (titles, rows) ->
                val byId = rows.associateBy { it.titleId }
                val names = titles.associate { title ->
                    val row = byId[title.id]
                    localTitleKey(title.id) to if (row == null) AnimeNames.original(title.name) else
                        AnimeNames(name = row.canonicalTitle, english = row.englishTitle, japanese = row.nativeTitle, synonyms = listOf(title.name))
                }
                val refs = titles.associate { title ->
                    val row = byId[title.id]
                    localTitleKey(title.id) to if (row == null) emptyList() else
                        listOfNotNull(animeIdKey("mal", row.malId), animeIdKey(row.provider, row.externalId))
                }
                animeTitles.putBatch(names, refs, titles.map { it.id }.toSet())
            }
        }
        applicationScope.launch {
            com.sergey.animevault.ui.shikimori.ShikimoriCollectionStore(application, animeTitles).read()
        }
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

    val backupRepository = AnimeVaultBackupRepository(application, database, onlineRepository, uiPreferences)

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

private fun logCharacterDiagnostic(value: CharacterLoadDiagnostic) {
    Log.i("AnimeVaultCharacters", value.logLine())
}
