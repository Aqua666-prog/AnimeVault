package com.sergey.animevault.data.online

import android.app.Application
import com.sergey.animevault.data.aniliberty.AniLibertyProvider
import com.sergey.animevault.data.aniliberty.createAniLibertyApi
import com.sergey.animevault.data.animelib.AnimeLibProvider
import com.sergey.animevault.data.animedia.AniMediaProvider
import com.sergey.animevault.data.animebest.AnimeBestProvider
import com.sergey.animevault.data.animeon.AnimeOnProvider
import com.sergey.animevault.data.animevost.AnimeVostProvider
import com.sergey.animevault.data.animevost.createAnimeVostApi
import com.sergey.animevault.data.kodik.KodikApi
import com.sergey.animevault.data.kodik.KodikProvider
import com.sergey.animevault.data.kodik.KodikStreamResolver
import com.sergey.animevault.data.sameband.SameBandProvider
import com.sergey.animevault.data.yummy.YummyAnimeProvider

/** Single construction point for online adapters and their provider-aware network clients. */
object OnlineProviderRegistry {
    fun create(
        application: Application,
        healthTracker: ProviderHealthTracker = ProviderHealthTracker(),
        endpointRegistry: ProviderEndpointRegistry = ProviderEndpointRegistry(application),
    ): List<OnlineProvider> {
        fun client(providerId: String) = endpointRegistry.clientFor(providerId)

        val direct = listOfNotNull(
            endpointRegistry.takeIf { it.isEnabled(OnlineProviderIds.ANI_LIBERTY) }?.let {
                AniLibertyProvider(createAniLibertyApi(client(OnlineProviderIds.ANI_LIBERTY)))
            },
            endpointRegistry.takeIf { it.isEnabled(OnlineProviderIds.KODIK) }?.let {
                val kodikClient = client(OnlineProviderIds.KODIK)
                KodikProvider(
                    application,
                    api = KodikApi(kodikClient),
                    streamResolver = KodikStreamResolver(kodikClient),
                )
            },
            endpointRegistry.takeIf { it.isEnabled(OnlineProviderIds.ANIME_LIB) }?.let {
                AnimeLibProvider(application, baseClient = client(OnlineProviderIds.ANIME_LIB))
            },
            endpointRegistry.takeIf { it.isEnabled(OnlineProviderIds.ANIME_VOST) }?.let {
                AnimeVostProvider(createAnimeVostApi(client(OnlineProviderIds.ANIME_VOST)))
            },
            endpointRegistry.takeIf { it.isEnabled(OnlineProviderIds.ANIMEDIA) }?.let {
                AniMediaProvider(client(OnlineProviderIds.ANIMEDIA))
            },
            endpointRegistry.takeIf { it.isEnabled(OnlineProviderIds.ANIME_ON) }?.let {
                AnimeOnProvider(client(OnlineProviderIds.ANIME_ON))
            },
            endpointRegistry.takeIf { it.isEnabled(OnlineProviderIds.SAMEBAND) }?.let {
                SameBandProvider(client(OnlineProviderIds.SAMEBAND))
            },
            endpointRegistry.takeIf { it.isEnabled(OnlineProviderIds.ANIME_BEST) }?.let {
                AnimeBestProvider(client(OnlineProviderIds.ANIME_BEST))
            },
            endpointRegistry.takeIf { it.isEnabled(OnlineProviderIds.YUMMY) }?.let {
                YummyAnimeProvider(application, client = client(OnlineProviderIds.YUMMY))
            },
        )
        validateProviderDescriptors(direct.map(OnlineProvider::descriptor))

        val preferredDefault = direct.firstOrNull { it.descriptor.id == OnlineProviderIds.ANI_LIBERTY }
            ?: direct.first()
        val all = buildList {
            add(preferredDefault)
            add(UnifiedOnlineProvider(direct, healthTracker))
            addAll(direct.filterNot { it === preferredDefault })
        }
        validateProviderDescriptors(all.map(OnlineProvider::descriptor))
        require(all.count { it.descriptor.id == OnlineProviderIds.UNIFIED } == 1) {
            "Unified provider must occur exactly once"
        }
        return all
    }
}

internal fun validateProviderDescriptors(descriptors: List<OnlineProviderDescriptor>) {
    require(descriptors.isNotEmpty()) { "At least one online provider is required" }
    val blank = descriptors.filter { it.id.isBlank() || it.name.isBlank() }
    require(blank.isEmpty()) { "Provider id and name must not be blank" }
    val duplicates = descriptors.groupingBy(OnlineProviderDescriptor::id)
        .eachCount()
        .filterValues { it > 1 }
        .keys
    require(duplicates.isEmpty()) { "Duplicate provider ids: ${duplicates.sorted().joinToString()}" }
    require(descriptors.none { it.minimumSearchLength < 1 }) {
        "Provider minimumSearchLength must be positive"
    }
}
