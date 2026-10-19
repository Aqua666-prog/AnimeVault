package com.sergey.animevault.ui.library

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.ui.preferences.VaultTitleList
import org.junit.Test

class LibraryCsvExportTest {
    @Test fun escapesQuotesNewlinesAndFormulaInjection() {
        val title = LibraryHubItem(key = "online:p:1", title = "=A1;\"опасно\"", poster = null,
            metadata = "", favorite = true, list = VaultTitleList.WATCHING,
            rating = 4, note = "Строка 1\nСтрока 2")
        val csv = libraryItemsToCsv(listOf(title))
        assertThat(csv).startsWith("\uFEFFНазвание;")
        assertThat(csv).contains("\"'=A1;\"\"опасно\"\"\"")
        assertThat(csv).contains("\"Смотрю\";\"4\";\"Да\"")
        assertThat(csv).contains("\"Строка 1\nСтрока 2\"")
    }
}
