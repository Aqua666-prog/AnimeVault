package com.sergey.animevault.data.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OnboardingStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun dataStore(file: File = File(temporary.newFolder(), "launch.preferences_pb")): DataStore<Preferences> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes += scope
        return PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    }

    @After fun tearDown() = runBlocking {
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun freshInstallationShowsOnboarding() = runTest {
        val evidence = InstallationEvidence(100, 100, false)
        assertThat(evidence.isExisting).isFalse()
        val store = DataStoreOnboardingStore(dataStore(), evidence.isExisting)
        assertThat(store.shouldShow()).isTrue()
        assertThat(store.shouldShow()).isTrue()
    }

    @Test fun updateFromOlderVersionSkipsAndPersistsCompletion() = runTest {
        val evidence = InstallationEvidence(100, 200, false)
        val data = dataStore()
        assertThat(evidence.isExisting).isTrue()
        assertThat(DataStoreOnboardingStore(data, evidence.isExisting).shouldShow()).isFalse()
        assertThat(DataStoreOnboardingStore(data, false).shouldShow()).isFalse()
    }

    @Test fun restoredLegacyLibrarySkipsEvenWhenInstallTimesMatch() = runTest {
        val evidence = InstallationEvidence(100, 100, true)
        assertThat(evidence.isExisting).isTrue()
        assertThat(DataStoreOnboardingStore(dataStore(), evidence.isExisting).shouldShow()).isFalse()
    }

    @Test fun unknownInstallTimestampsAloneDoNotSkipFirstLaunch() = runTest {
        assertThat(InstallationEvidence(0, 0, false).isExisting).isFalse()
        assertThat(DataStoreOnboardingStore(dataStore(), false).shouldShow()).isTrue()
    }

    @Test fun pendingFirstLaunchIsNotMistakenForAnUpgradeOnRestart() = runTest {
        val data = dataStore()
        assertThat(DataStoreOnboardingStore(data, false).shouldShow()).isTrue()
        // Room now exists or another APK has been installed, but the flow was not completed.
        assertThat(DataStoreOnboardingStore(data, true).shouldShow()).isTrue()
    }

    @Test fun completionSurvivesClosingAndReopeningDataStore() = runTest {
        val file = File(temporary.newFolder(), "launch.preferences_pb")
        val original = DataStoreOnboardingStore(dataStore(file), false)
        assertThat(original.shouldShow()).isTrue()
        original.complete()
        assertThat(file.length()).isGreaterThan(0L)
        scopes.last().coroutineContext.job.cancelAndJoin()
        val reopened = DataStoreOnboardingStore(dataStore(file), false)
        assertThat(reopened.shouldShow()).isFalse()
    }

    @Test fun repeatedInitializationCannotOverwriteCompletion() = runTest {
        val data = dataStore()
        val store = DataStoreOnboardingStore(data, false)
        listOf(
            async { store.shouldShow() },
            async { store.complete(); false },
            async { DataStoreOnboardingStore(data, false).shouldShow() },
        ).awaitAll()
        assertThat(store.shouldShow()).isFalse()
    }
}
