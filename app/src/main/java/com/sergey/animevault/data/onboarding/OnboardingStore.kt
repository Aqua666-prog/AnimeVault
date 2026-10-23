package com.sergey.animevault.data.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

interface OnboardingStore {
    suspend fun shouldShow(): Boolean
    suspend fun complete()
}

/** Only this preference belongs to onboarding. Signing out never writes it. */
class DataStoreOnboardingStore(
    private val dataStore: DataStore<Preferences>,
    private val existingInstallation: Boolean,
) : OnboardingStore {
    override suspend fun shouldShow(): Boolean {
        val current = dataStore.data.first()
        current[COMPLETED]?.let { return !it }
        // Store false for a fresh install too. A later update must not skip an unfinished flow.
        val initialized = dataStore.edit { preferences ->
            if (preferences[COMPLETED] == null) preferences[COMPLETED] = existingInstallation
        }
        return initialized[COMPLETED] != true
    }

    override suspend fun complete() {
        dataStore.edit { it[COMPLETED] = true }
    }

    private companion object {
        val COMPLETED = booleanPreferencesKey("completed_v1")
    }
}

data class InstallationEvidence(
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val hasLegacyData: Boolean,
) {
    val isExisting: Boolean
        get() = hasLegacyData || (firstInstallTime > 0 && lastUpdateTime > firstInstallTime)
}
