package com.sergey.animevault.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VaultArtworkTest {
    @Test
    fun bannerAndMetadataPrecedeProviderFallback() {
        val sources = vaultTitleArtwork("banner", "tenrai", "provider")
        assertThat(sources.map(VaultArtworkSource::url)).containsExactly("banner", "tenrai", "provider").inOrder()
        assertThat(sources.first().isBanner).isTrue()
        assertThat(sources.drop(1).any(VaultArtworkSource::isBanner)).isFalse()
    }

    @Test
    fun blankOrRepeatedImagesDoNotHideValidFallback() {
        assertThat(vaultTitleArtwork(" ", " same ", "same")).containsExactly(VaultArtworkSource("same"))
        assertThat(vaultTitleArtwork(null, "", " provider ")).containsExactly(VaultArtworkSource("provider"))
        assertThat(vaultTitleArtwork()).isEmpty()
    }
}
