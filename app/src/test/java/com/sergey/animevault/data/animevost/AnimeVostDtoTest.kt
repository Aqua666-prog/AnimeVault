package com.sergey.animevault.data.animevost

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test

class AnimeVostDtoTest {
    @Test
    fun playlistArray_deserializesIntoEnvelope() {
        val value = Gson().fromJson(
            """[{"name":"1 серия","hd":"https://cdn/1-720.mp4","std":"https://cdn/1-480.mp4"}]""",
            AnimeVostPlaylistEnvelope::class.java,
        )

        assertThat(value.items).hasSize(1)
        assertThat(value.items.single().name).isEqualTo("1 серия")
    }
}
