package com.sergey.animevault.data.anilist

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AniListOAuthStateTest {
    @Test
    fun stateMustExistAndMatchExactly() {
        assertThat(oauthStateMatches("abc123", "abc123")).isTrue()
        assertThat(oauthStateMatches("abc123", "other")).isFalse()
        assertThat(oauthStateMatches(null, "abc123")).isFalse()
        assertThat(oauthStateMatches("", "")).isFalse()
    }
}
