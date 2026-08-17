package com.sergey.animevault.ui.title

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MetadataSearchQueryTest {
    @Test
    fun onlineAlias_hasPriorityAndIsNormalized() {
        val query = chooseMetadataSearchQuery(
            titleName = "Локальное имя",
            onlineAliases = listOf("Kusuriya_no-Hitorigoto"),
            episodeFileNames = listOf("[SubsPlease] Frieren - 07 (1080p).mkv"),
        )

        assertThat(query).isEqualTo("Kusuriya no Hitorigoto")
    }

    @Test
    fun episodeTitleHint_isUsedWhenAliasMissing() {
        val query = chooseMetadataSearchQuery(
            titleName = "A",
            onlineAliases = emptyList(),
            episodeFileNames = listOf("[SubsPlease] Frieren - 07 (1080p).mkv"),
        )

        assertThat(query).isEqualTo("Frieren")
    }

    @Test
    fun localTitle_isFallbackWhenNoBetterHintExists() {
        val query = chooseMetadataSearchQuery(
            titleName = "Монолог фармацевта",
            onlineAliases = listOf("  "),
            episodeFileNames = listOf("03.mkv"),
        )

        assertThat(query).isEqualTo("Монолог фармацевта")
    }
}
