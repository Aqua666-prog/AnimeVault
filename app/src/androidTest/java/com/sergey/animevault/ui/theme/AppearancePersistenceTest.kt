package com.sergey.animevault.ui.theme

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.sergey.animevault.ui.preferences.*
import org.junit.*
import org.junit.Assert.*

class AppearancePersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val disk = context.getSharedPreferences("anime_vault_ui", Context.MODE_PRIVATE)
    private val keys = setOf("theme_mode", "accent_mode", "blur_enabled", "motion_mode", "dynamic_artwork", "reduced_motion", "decorative_animations")
    private var original: Map<String, Any?> = emptyMap()
    @Before fun save() { original = disk.all.filterKeys { it in keys } }
    @After fun restore() {
        disk.edit().apply {
            keys.forEach { remove(it) }
            original.forEach { (key, value) -> when(value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
            } }
        }.commit()
    }
    @Test fun everyEditionAppliesImmediatelyAndSurvivesNewReader() {
        val store = UiPreferences(context)
        VaultThemeMode.entries.forEach { mode ->
            store.setThemeMode(mode)
            assertEquals(mode, store.appearance.value.theme)
            assertTrue(disk.edit().commit())
            assertEquals(mode, UiPreferences(context).appearance.value.theme)
        }
    }
    @Test fun unknownValueKeepsOtherPreferences() {
        disk.edit().putString("theme_mode", "future").putBoolean("dynamic_artwork", true).commit()
        val restored = UiPreferences(context).appearance.value
        assertEquals(VaultThemeMode.VAULT, restored.theme)
        assertTrue(restored.dynamicArtwork)
    }
    @Test fun disabledDecorationPersists() {
        val store = UiPreferences(context)
        store.setThemeMode(VaultThemeMode.MUSOU)
        store.setDecorativeAnimations(false)
        val restored = UiPreferences(context).appearance.value
        assertEquals(VaultThemeMode.MUSOU, restored.theme)
        assertFalse(restored.allowDecorativeMotion(true))
    }
}
