package com.sergey.animevault.data.download

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType
import org.junit.Test

class SeasonDownloadPolicyTest {
    private fun mp4(quality: Int, voice: String = "A"): OnlineStream = OnlineStream(
        id = "$voice-$quality", quality = quality,
        url = "https://example.com/$voice/$quality.mp4", type = OnlineStreamType.MP4,
        translation = voice, translationKey = voice,
    )

    @Test fun exact720IsSelectedBeforeFallback() {
        val stream = chooseSeasonDownloadStream(
            listOf(mp4(1080), mp4(480), mp4(720)), null, 720, SeasonQualityPolicy.LOWER,
        )
        assertThat(stream?.quality).isEqualTo(720)
    }

    @Test fun lowerQualityNeverChooses1080() {
        val stream = chooseSeasonDownloadStream(
            listOf(mp4(1080), mp4(480), mp4(360)), null, 720, SeasonQualityPolicy.LOWER,
        )
        assertThat(stream?.quality).isEqualTo(480)
    }

    @Test fun missingLowerQualityIsNotReplacedWithHigher() {
        assertThat(chooseSeasonDownloadStream(
            listOf(mp4(1080)), null, 720, SeasonQualityPolicy.LOWER,
        )).isNull()
    }

    @Test fun strictMissing720Skips() {
        assertThat(chooseSeasonDownloadStream(
            listOf(mp4(1080), mp4(480)), null, 720, SeasonQualityPolicy.STRICT,
        )).isNull()
    }

    @Test fun anyQualityPicksClosestKnownResolution() {
        val stream = chooseSeasonDownloadStream(
            listOf(mp4(1080), mp4(480)), null, 720, SeasonQualityPolicy.ANY,
        )
        assertThat(stream?.quality).isEqualTo(480)
    }

    @Test fun selectedTranslationIsNotSilentlyChanged() {
        val stream = chooseSeasonDownloadStream(
            listOf(mp4(720, "A"), mp4(480, "B")), "B", 720, SeasonQualityPolicy.LOWER,
        )
        assertThat(stream?.translation).isEqualTo("B")
        assertThat(stream?.quality).isEqualTo(480)
    }

    @Test fun unknownMasterHlsMayContainRequestedRendition() {
        val master = OnlineStream(
            id = "master", quality = null, url = "https://example.com/master.m3u8",
            type = OnlineStreamType.HLS,
        )
        assertThat(chooseSeasonDownloadStream(
            listOf(master), null, 720, SeasonQualityPolicy.STRICT,
        )).isEqualTo(master)
    }

    @Test fun seasonOrderIsStableWithoutDuplicates() {
        assertThat(orderedSeasonEpisodeIds(
            listOf("3", "1", "2", "2"), listOf("1", "2", "3", "4"),
        )).containsExactly("1", "2", "3").inOrder()
    }

    private fun hlsVariant(height: Int) = HlsVariant(
        uri = "https://example.com/$height.m3u8", bandwidth = height.toLong(),
        width = height * 16 / 9, height = height, codecs = null,
    )

    @Test fun hlsCeilingSkipsHigherVariants() {
        val variants = listOf(hlsVariant(1080), hlsVariant(800), hlsVariant(480))
        assertThat(chooseHlsVariant(variants, 720, SeasonQualityPolicy.LOWER)?.height).isEqualTo(480)
    }

    @Test fun strictHlsSkipsIfExactRenditionMissing() {
        val variants = listOf(hlsVariant(1080), hlsVariant(480))
        assertThat(chooseHlsVariant(variants, 720, SeasonQualityPolicy.STRICT)).isNull()
    }
}
