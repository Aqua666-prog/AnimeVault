package com.sergey.animevault.data.online

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.titles.AnimeNames
import org.junit.Test

class OnlineSearchRankingTest {
    private fun card(id: String, name: String) = OnlineReleaseCard(
        "source", "Source", id, name, name, null, null, null, null, null, null, false,
    )
    @Test fun exactTitlePrecedesPartialAndTiesHaveStableIds() {
        val cards = listOf(card("b", "Bleach"), card("c", "Bleach season 2"), card("a", "Bleach"))
        assertThat(OnlineSearchRanking.rank(cards, "Bleach").map { it.id }).containsExactly("a", "b", "c").inOrder()
        assertThat(OnlineSearchRanking.rank(cards.reversed(), "Bleach")).isEqualTo(OnlineSearchRanking.rank(cards, "Bleach"))
    }
    @Test fun localizedNamesAndAliasesAreRankedWithoutChangingOriginal() {
        val card = card("1", "Блич").copy(titleNames = AnimeNames(name = "Bleach", japanese = "ブリーチ", synonyms = listOf("Buriichi")))
        listOf("Блич", "Bleach", "ブリーチ", "Buriichi").forEach { query ->
            assertThat(OnlineSearchRanking.score(card, query)).isAtLeast(90)
        }
        assertThat(card.name).isEqualTo("Блич")
    }
    @Test fun normalizationAndBoundedTypoMatching() {
        assertThat(OnlineSearchRanking.normalize("  ＢＬＥＡＣＨ:  Ё  ")).isEqualTo("bleach е")
        assertThat(OnlineSearchRanking.score(card("1", "Bleach"), "Bleaach")).isEqualTo(30)
        assertThat(OnlineSearchRanking.score(card("1", "Cat"), "Bat")).isEqualTo(0)
        assertThat(OnlineSearchRanking.rank(listOf(card("1", "null")), "!!!")).hasSize(1)
    }
}
