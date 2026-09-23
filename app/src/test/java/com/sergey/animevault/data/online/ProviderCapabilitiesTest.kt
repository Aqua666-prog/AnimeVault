package com.sergey.animevault.data.online

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderCapabilitiesTest {
    @Test
    fun urlOrSlugSearch_isNotUsedByUnifiedTextSearch() {
        val capabilities = ProviderCapabilities(
            catalog = false,
            search = true,
            searchMode = ProviderSearchMode.URL_OR_SLUG,
        )

        assertThat(capabilities.supportsUnifiedTextSearch).isFalse()
        assertThat(capabilities.compactLabel()).contains("ссылка/slug")
    }

    @Test
    fun aggregate_exposesUnionButOnlyTextSearch() {
        val result = ProviderCapabilities.aggregate(
            listOf(
                ProviderCapabilities(catalog = true, search = false, searchMode = ProviderSearchMode.NONE),
                ProviderCapabilities(catalog = false, searchMode = ProviderSearchMode.URL_OR_SLUG),
                ProviderCapabilities(catalog = false, subtitles = true),
            ),
        )

        assertThat(result.catalog).isTrue()
        assertThat(result.search).isTrue()
        assertThat(result.searchMode).isEqualTo(ProviderSearchMode.TEXT)
        assertThat(result.subtitles).isTrue()
    }
    @Test(expected = OnlineSourceException::class)
    fun searchOnlyProvider_rejectsBlankBrowseRequest() {
        OnlineProviderDescriptor(
            id = "search-only",
            name = "Search only",
            description = "",
            capabilities = ProviderCapabilities(catalog = false),
        ).requireCatalogCapability("")
    }

    @Test(expected = OnlineSourceException::class)
    fun minimumSearchLength_isPartOfProviderContract() {
        OnlineProviderDescriptor(
            id = "four",
            name = "Four",
            description = "",
            minimumSearchLength = 4,
        ).requireCatalogCapability("abc")
    }

}
