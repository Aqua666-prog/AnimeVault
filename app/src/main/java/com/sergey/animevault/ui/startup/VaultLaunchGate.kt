package com.sergey.animevault.ui.startup

/** One reveal per process. Deep links and restored activities also consume the gate. */
internal class VaultLaunchGate {
    private var entered = false

    @Synchronized
    fun claim(launcherLaunch: Boolean, restoredState: Boolean): Boolean {
        val firstEntry = !entered
        entered = true
        return firstEntry && launcherLaunch && !restoredState
    }
}

private val launchGate = VaultLaunchGate()
internal fun claimVaultReveal(launcherLaunch: Boolean, restoredState: Boolean): Boolean =
    launchGate.claim(launcherLaunch, restoredState)
