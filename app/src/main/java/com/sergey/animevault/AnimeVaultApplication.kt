package com.sergey.animevault

import android.app.Application
import androidx.room.Room
import com.sergey.animevault.data.aniliberty.AniLibertyProvider
import com.sergey.animevault.data.aniliberty.createAniLibertyApi
import com.sergey.animevault.data.animelib.AnimeLibProvider
import com.sergey.animevault.data.animedia.AniMediaProvider
import com.sergey.animevault.data.animebest.AnimeBestProvider
import com.sergey.animevault.data.animeon.AnimeOnProvider
import com.sergey.animevault.data.animevost.AnimeVostProvider
import com.sergey.animevault.data.db.AnimeVaultDatabase
import com.sergey.animevault.data.kodik.KodikProvider
import com.sergey.animevault.data.metadata.AnimeThemeRepository
import com.sergey.animevault.data.repository.LibraryRepository
import com.sergey.animevault.data.scanner.LibraryScanner
import com.sergey.animevault.data.scanner.OfflineScanScheduler
import com.sergey.animevault.data.online.OnlineRepository
import com.sergey.animevault.data.online.UnifiedOnlineProvider
import com.sergey.animevault.data.sameband.SameBandProvider
import com.sergey.animevault.data.yummy.YummyAnimeProvider

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
    ).addMigrations(AnimeVaultDatabase.MIGRATION_1_2)
        .build()

    val animeThemeRepository = AnimeThemeRepository()

    val libraryRepository = LibraryRepository(
        context = application,
        database = database,
        scanner = LibraryScanner(application),
    )

    private val directOnlineProviders = listOf(
        AniLibertyProvider(createAniLibertyApi()),
        KodikProvider(application),
        AnimeLibProvider(application),
        AnimeVostProvider(),
        // Jut.su currently replaces public video URLs with pixel.png placeholders;
        // keeping it enabled falsely completes episodes as soon as playback starts.
        // DreamersCast currently responds with HTTP 404 even for release pages.
        // Their adapters stay in the project and can be re-enabled after upstream recovery.
        AniMediaProvider(),
        AnimeOnProvider(),
        SameBandProvider(),
        AnimeBestProvider(),
        YummyAnimeProvider(application),
    )

    val onlineRepository = OnlineRepository(
        context = application,
        providers = buildList {
            // Keep AniLiberty as the conservative default for this beta. The
            // virtual multi-source catalogue is opt-in from the source chips.
            add(directOnlineProviders.first())
            add(UnifiedOnlineProvider(directOnlineProviders))
            addAll(directOnlineProviders.drop(1))
        },
    )
}
