package com.sergey.animevault.ui.online

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.online.ExternalAnimeIds
import org.junit.Test

class NextSeasonResolverTest {
    @Test fun requiresExactExternalIdsBeforeAutoplay() {
        val sequel = AniListSequel(12345L, 98765L, listOf("Next season"))
        assertThat(sequelIdsMatch(ExternalAnimeIds(anilistId = 12345L), sequel)).isTrue()
        assertThat(sequelIdsMatch(ExternalAnimeIds(malId = 98765L), sequel)).isTrue()
        assertThat(sequelIdsMatch(ExternalAnimeIds(shikimoriId = 98765L), sequel)).isTrue()
        assertThat(sequelIdsMatch(ExternalAnimeIds(shikimoriId = 98766L), sequel)).isFalse()
        assertThat(sequelIdsMatch(ExternalAnimeIds(), sequel)).isFalse()
    }
}
