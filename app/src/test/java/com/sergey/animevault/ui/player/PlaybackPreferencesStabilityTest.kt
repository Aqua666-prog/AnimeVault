package com.sergey.animevault.ui.player

import com.sergey.animevault.testing.MemoryPreferencesContext
import com.sergey.animevault.ui.preferences.UiPreferences
import org.junit.Assert.*
import org.junit.Test

class PlaybackPreferencesStabilityTest {
    @Test fun explicitAutoAndManualCapSurviveRecreationWithoutChangingGlobalDefaults() {
        val context = MemoryPreferencesContext()
        UiPreferences(context).setOnlineQuality(1080)
        val first = PlayerPreferences(context, "fixture-release")
        assertEquals(1080, first.effectiveQuality)
        first.preferredQuality = null
        first.preferredTranslation = "Saved voice"
        first.preferredTranslationKey = "voice-stable"
        first.preferredProviderId = "fixture"
        val restoredAuto = PlayerPreferences(context, "fixture-release")
        assertNull(restoredAuto.effectiveQuality)
        assertEquals("voice-stable", restoredAuto.preferredTranslationKey)
        assertEquals("Saved voice", restoredAuto.preferredTranslation)
        restoredAuto.preferredQuality = 720
        assertEquals(720, PlayerPreferences(context, "fixture-release").effectiveQuality)
        assertEquals(1080, UiPreferences(context).playbackDefaults().onlineQuality)
    }
}
