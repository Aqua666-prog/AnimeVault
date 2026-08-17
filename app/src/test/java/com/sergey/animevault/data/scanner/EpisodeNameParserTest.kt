package com.sergey.animevault.data.scanner

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpisodeNameParserTest {
    @Test
    fun `parses dash release with group and quality tags`() {
        val parsed = EpisodeNameParser.parse("[SubsPlease] Frieren - 07 (1080p).mkv")

        assertThat(parsed.titleHint).isEqualTo("Frieren")
        assertThat(parsed.episodeNumber).isEqualTo(7.0)
        assertThat(parsed.seasonNumber).isNull()
    }

    @Test
    fun `parses season and episode notation`() {
        val parsed = EpisodeNameParser.parse("Attack.on.Titan.S02E03.1080p.mkv")

        assertThat(parsed.titleHint).isEqualTo("Attack on Titan")
        assertThat(parsed.seasonNumber).isEqualTo(2)
        assertThat(parsed.episodeNumber).isEqualTo(3.0)
    }

    @Test
    fun `parses cross season and episode notation`() {
        val parsed = EpisodeNameParser.parse("Mushoku Tensei 2x05 [720p].mkv")

        assertThat(parsed.titleHint).isEqualTo("Mushoku Tensei")
        assertThat(parsed.seasonNumber).isEqualTo(2)
        assertThat(parsed.episodeNumber).isEqualTo(5.0)
    }

    @Test
    fun `parses bracketed episode revision`() {
        val parsed = EpisodeNameParser.parse("[Group] Dungeon Meshi [05v2] [1080p].mkv")

        assertThat(parsed.titleHint).isEqualTo("Dungeon Meshi")
        assertThat(parsed.episodeNumber).isEqualTo(5.0)
    }

    @Test
    fun `parses Russian trailing episode wording`() {
        val parsed = EpisodeNameParser.parse("Подземелье вкусностей 05 серия.mkv")

        assertThat(parsed.titleHint).isEqualTo("Подземелье вкусностей")
        assertThat(parsed.episodeNumber).isEqualTo(5.0)
    }

    @Test
    fun `uses first episode of a release range for sorting`() {
        val parsed = EpisodeNameParser.parse("Frieren - 01-02 [1080p].mkv")

        assertThat(parsed.titleHint).isEqualTo("Frieren")
        assertThat(parsed.episodeNumber).isEqualTo(1.0)
    }

    @Test
    fun `parses Russian episode wording`() {
        val parsed = EpisodeNameParser.parse("Монолог фармацевта серия 12.5.mkv")

        assertThat(parsed.titleHint).isEqualTo("Монолог фармацевта")
        assertThat(parsed.episodeNumber).isEqualTo(12.5)
    }

    @Test
    fun `parses Russian season and episode words`() {
        val parsed = EpisodeNameParser.parse("Монолог фармацевта сезон 2 серия 4.mkv")

        assertThat(parsed.titleHint).isEqualTo("Монолог фармацевта сезон 2")
        assertThat(parsed.seasonNumber).isEqualTo(2)
        assertThat(parsed.episodeNumber).isEqualTo(4.0)
    }

    @Test
    fun `parses a numeric filename using parent title later`() {
        val parsed = EpisodeNameParser.parse("03.mkv")

        assertThat(parsed.titleHint).isNull()
        assertThat(parsed.episodeNumber).isEqualTo(3.0)
    }

    @Test
    fun `leaves an OVA without a number sortable by name`() {
        val parsed = EpisodeNameParser.parse("Violet Evergarden OVA.mkv")

        assertThat(parsed.titleHint).isEqualTo("Violet Evergarden OVA")
        assertThat(parsed.episodeNumber).isNull()
    }
}
