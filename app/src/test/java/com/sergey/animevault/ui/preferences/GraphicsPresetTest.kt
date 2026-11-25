package com.sergey.animevault.ui.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GraphicsPresetTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun eachPresetIsPersistedAndDefaultIsBalanced() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(folder.root, "graphics.preferences_pb")
        }
        val store = GraphicsPresetStore(dataStore, backgroundScope)
        assertEquals(GraphicsPreset.BALANCED, store.preset.filterNotNull().first())

        for (preset in GraphicsPreset.entries) {
            store.setPreset(preset)
            assertEquals(preset, store.preset.first { it == preset })
        }
        val anotherObserver = GraphicsPresetStore(dataStore, backgroundScope)
        assertEquals(GraphicsPreset.CINEMATIC_ULTRA, anotherObserver.preset.filterNotNull().first())
    }

    @Test
    fun powerSaveTemporarilyOverridesButDoesNotChangeSelection() {
        for (preset in GraphicsPreset.entries) {
            assertEquals(preset, preset.effectivePreset(powerSaveMode = false))
            assertEquals(GraphicsPreset.PERFORMANCE, preset.effectivePreset(powerSaveMode = true))
        }
        assertEquals(GraphicsPreset.BALANCED, GraphicsPreset.fromStorage("future-preset"))
    }

    @Test
    fun motionRespectsSystemAndUserSettings() {
        val appearance = AppearanceSettings()
        assertEquals(VaultMotionMode.MINIMAL, GraphicsPreset.PERFORMANCE.motionMode(appearance, true))
        assertEquals(VaultMotionMode.OFF, GraphicsPreset.CINEMATIC_ULTRA.motionMode(appearance, false))
        assertEquals(VaultMotionMode.OFF, GraphicsPreset.PERFORMANCE.motionMode(appearance.copy(motion = VaultMotionMode.OFF), true))
        assertEquals(VaultMotionMode.REDUCED, GraphicsPreset.CINEMATIC_ULTRA.motionMode(appearance.copy(reducedMotion = true), true))
    }
}
