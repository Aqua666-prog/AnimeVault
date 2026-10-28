package com.sergey.animevault.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.sergey.animevault.ui.preferences.VaultThemeMode
import org.junit.Assert.*
import org.junit.Test

class VaultPalettesTest {
    @Test fun midnightIsDistinctAndOledRemainsBlack() {
        val vault = vaultStaticColorScheme(VaultThemeMode.VAULT)
        val midnight = vaultStaticColorScheme(VaultThemeMode.MIDNIGHT)
        val oled = vaultStaticColorScheme(VaultThemeMode.OLED)
        assertNotEquals(vault.background, midnight.background)
        assertNotEquals(vault.primary, midnight.primary)
        assertEquals(Color.Black, oled.background)
        assertNotEquals(oled.background, oled.surfaceContainer)
        assertEquals(vault.background, vaultStaticColorScheme(VaultThemeMode.DYNAMIC).background)
        assertEquals(vault.primary, vaultStaticColorScheme(VaultThemeMode.DYNAMIC).primary)
    }

    @Test fun staticPaletteTextPairsMeetNormalTextContrast() {
        fun contrast(a: Color, b: Color): Float {
            val x = a.luminance(); val y = b.luminance()
            return (maxOf(x, y) + .05f) / (minOf(x, y) + .05f)
        }
        VaultThemeMode.entries.forEach { mode ->
            val c = vaultStaticColorScheme(mode)
            listOf(c.onBackground to c.background, c.onSurface to c.surface,
                c.onSurfaceVariant to c.surfaceVariant, c.onPrimary to c.primary,
                c.onSecondary to c.secondary).forEach { (text, surface) ->
                assertTrue("$mode text contrast", contrast(text, surface) >= 4.5f)
            }
        }
    }
}
