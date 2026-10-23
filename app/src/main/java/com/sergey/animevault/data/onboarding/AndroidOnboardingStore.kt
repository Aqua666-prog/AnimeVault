package com.sergey.animevault.data.onboarding

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import java.io.File

private val Context.firstLaunchDataStore by preferencesDataStore(name = "animevault_onboarding")

/** Call before AppContainer creates Room, preferences or background workers. */
@Suppress("DEPRECATION")
fun createOnboardingStore(context: Context): OnboardingStore {
    val app = context.applicationContext
    val info = app.packageManager.getPackageInfo(app.packageName, 0)
    val evidence = InstallationEvidence(
        firstInstallTime = info.firstInstallTime,
        lastUpdateTime = info.lastUpdateTime,
        hasLegacyData = app.getDatabasePath("anime_vault.db").exists() ||
            File(app.applicationInfo.dataDir, "shared_prefs/anime_vault_ui.xml").exists(),
    )
    return DataStoreOnboardingStore(app.firstLaunchDataStore, evidence.isExisting)
}
