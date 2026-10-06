package com.sergey.animevault.ui.startup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VaultLaunchGateTest {
    @Test
    fun coldLauncherGetsRevealAndWarmLauncherDoesNot() {
        val gate = VaultLaunchGate()
        assertThat(gate.claim(launcherLaunch = true, restoredState = false)).isTrue()
        assertThat(gate.claim(launcherLaunch = true, restoredState = false)).isFalse()
    }

    @Test
    fun deepLinkEntryPreventsFullRevealOnLaterWarmLauncher() {
        val gate = VaultLaunchGate()
        assertThat(gate.claim(launcherLaunch = false, restoredState = false)).isFalse()
        assertThat(gate.claim(launcherLaunch = true, restoredState = false)).isFalse()
    }

    @Test
    fun restoringProcessSkipsReveal() {
        assertThat(VaultLaunchGate().claim(launcherLaunch = true, restoredState = true)).isFalse()
    }
}
