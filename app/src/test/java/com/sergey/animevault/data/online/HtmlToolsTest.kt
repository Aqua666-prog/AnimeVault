package com.sergey.animevault.data.online

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HtmlToolsTest {
    @Test
    fun attributes_supportSingleDoubleAndUnquotedValues() {
        val attributes = parseHtmlAttributes(
            "<button data-player='//player.test/embed/1' data-id=42 title=\"A &amp; B\">",
        )

        assertThat(attributes["data-player"]).isEqualTo("//player.test/embed/1")
        assertThat(attributes["data-id"]).isEqualTo("42")
        assertThat(attributes["title"]).isEqualTo("A & B")
    }

    @Test
    fun htmlText_decodesEntitiesAndStripsMarkup() {
        assertThat(htmlText("<b>Серия&nbsp;1</b> &amp; <i>OVA</i>"))
            .isEqualTo("Серия 1 & OVA")
    }
}
