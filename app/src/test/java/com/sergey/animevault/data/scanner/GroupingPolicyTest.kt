package com.sergey.animevault.data.scanner

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GroupingPolicyTest {
    @Test
    fun `global library keeps nested season as its own card`() {
        val title = GroupingPolicy.chooseTitle(
            rootName = "Anime",
            relativeDirectories = listOf("Frieren", "Season 1"),
            parsedTitleHint = "Frieren",
        )

        assertThat(title).isEqualTo("Frieren — Season 1")
    }

    @Test
    fun `season directories stay separate cards`() {
        val title = GroupingPolicy.chooseTitle(
            rootName = "Monster",
            relativeDirectories = listOf("Season 2"),
            parsedTitleHint = null,
        )

        assertThat(title).isEqualTo("Monster — Season 2")
    }

    @Test
    fun `nested season directory is appended to anime title`() {
        val title = GroupingPolicy.chooseTitle(
            rootName = "Anime",
            relativeDirectories = listOf("Monster", "Season 2"),
            parsedTitleHint = null,
        )

        assertThat(title).isEqualTo("Monster — Season 2")
    }

    @Test
    fun `numeric episode directories stay inside selected title`() {
        val title = GroupingPolicy.chooseTitle(
            rootName = "Steins Gate",
            relativeDirectories = listOf("001 0"),
            parsedTitleHint = null,
        )

        assertThat(title).isEqualTo("Steins Gate")
        assertThat(GroupingPolicy.titleDirectoryKey(listOf("001 0"))).isEmpty()
    }

    @Test
    fun `short numeric anime name is not treated as episode folder`() {
        val title = GroupingPolicy.chooseTitle(
            rootName = "Anime",
            relativeDirectories = listOf("86"),
            parsedTitleHint = null,
        )

        assertThat(title).isEqualTo("86")
    }

    @Test
    fun `Russian named season is cleaned without merging`() {
        val title = GroupingPolicy.chooseTitle(
            rootName = "Anime",
            relativeDirectories = listOf(
                "Монолог фармацевта (второй сезон) – Kusuriya_no_Hitorigoto",
            ),
            parsedTitleHint = null,
        )

        assertThat(title).isEqualTo("Монолог фармацевта — сезон 2")
    }

    @Test
    fun `TV suffix remains a separate title`() {
        val title = GroupingPolicy.chooseTitle(
            rootName = "Anime",
            relativeDirectories = listOf("Когда плачут цикады [TV-2]"),
            parsedTitleHint = null,
        )

        assertThat(title).isEqualTo("Когда плачут цикады [TV-2]")
    }

    @Test
    fun `root level releases use parsed title`() {
        val title = GroupingPolicy.chooseTitle(
            rootName = "Downloads",
            relativeDirectories = emptyList(),
            parsedTitleHint = "Pluto",
        )

        assertThat(title).isEqualTo("Pluto")
    }

    @Test
    fun `root level season releases stay on separate cards`() {
        val seasonOne = GroupingPolicy.chooseTitle(
            rootName = "Downloads",
            relativeDirectories = emptyList(),
            parsedTitleHint = "Frieren",
            parsedSeasonNumber = 1,
        )
        val seasonTwo = GroupingPolicy.chooseTitle(
            rootName = "Downloads",
            relativeDirectories = emptyList(),
            parsedTitleHint = "Frieren",
            parsedSeasonNumber = 2,
        )

        assertThat(seasonOne).isEqualTo("Frieren — сезон 1")
        assertThat(seasonTwo).isEqualTo("Frieren — сезон 2")
        assertThat(GroupingPolicy.keyFor(seasonOne)).isNotEqualTo(GroupingPolicy.keyFor(seasonTwo))
    }

    @Test
    fun `explicit season directory does not get duplicate parsed season`() {
        val title = GroupingPolicy.chooseTitle(
            rootName = "Frieren",
            relativeDirectories = listOf("Season 2"),
            parsedTitleHint = "Frieren",
            parsedSeasonNumber = 2,
        )

        assertThat(title).isEqualTo("Frieren — Season 2")
    }
}
