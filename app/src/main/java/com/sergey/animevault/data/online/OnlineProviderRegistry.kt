package com.sergey.animevault.data.online

import android.app.Application
import com.sergey.animevault.data.aniliberty.AniLibertyProvider
import com.sergey.animevault.data.aniliberty.createAniLibertyApi
import com.sergey.animevault.data.animelib.AnimeLibProvider
import com.sergey.animevault.data.animedia.AniMediaProvider
import com.sergey.animevault.data.animebest.AnimeBestProvider
import com.sergey.animevault.data.animeon.AnimeOnProvider
import com.sergey.animevault.data.animevost.AnimeVostProvider
import com.sergey.animevault.data.kodik.KodikProvider
import com.sergey.animevault.data.sameband.SameBandProvider
import com.sergey.animevault.data.yummy.YummyAnimeProvider

/**
 * Single construction point for online adapters.
 *
 * Keeping provider ordering and validation out of AppContainer makes adding or
 * temporarily disabling an adapter a local change instead of an application-wide
 * dependency edit. The unified provider receives exactly the same direct list.
 */
object OnlineProviderRegistry {
    fun create(application: Application): List<OnlineProvider> {
        val direct = listOf(
            AniLibertyProvider(createAniLibertyApi()),
            KodikProvider(application),
            AnimeLibProvider(application),
            AnimeVostProvider(),
            // Jut.su and DreamersCast remain deliberately disabled until their
            // public endpoints become reliable again.
            AniMediaProvider(),
            AnimeOnProvider(),
            SameBandProvider(),
            AnimeBestProvider(),
            YummyAnimeProvider(application),
        )
        validateProviderDescriptors(direct.map(OnlineProvider::descriptor))

        val all = buildList {
            add(direct.first()) // conservative default: AniLiberty
            add(UnifiedOnlineProvider(direct))
            addAll(direct.drop(1))
        }
        validateProviderDescriptors(all.map(OnlineProvider::descriptor))
        require(all.first().descriptor.id == OnlineProviderIds.ANI_LIBERTY) {
            "AniLiberty must remain the default provider"
        }
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
}
