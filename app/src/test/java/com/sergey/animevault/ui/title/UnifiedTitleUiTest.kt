package com.sergey.animevault.ui.title

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnifiedTitleUiTest {
    @Test fun localOnlyIsLocal() {
        assertThat(unifiedTitleOrigin(hasLocal = true, onlineSourceCount = 0))
            .isEqualTo(UnifiedTitleOrigin.LOCAL)
    }

    @Test fun onlineOnlyIsOnline() {
        assertThat(unifiedTitleOrigin(hasLocal = false, onlineSourceCount = 2))
            .isEqualTo(UnifiedTitleOrigin.ONLINE)
    }

    @Test fun linkedLocalAndOnlineIsHybrid() {
        assertThat(unifiedTitleOrigin(hasLocal = true, onlineSourceCount = 1))
            .isEqualTo(UnifiedTitleOrigin.HYBRID)
    }
}
