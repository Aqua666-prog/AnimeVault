package com.sergey.animevault.data.scanner

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.core.content.edit
import com.sergey.animevault.AnimeVaultApplication
import com.sergey.animevault.util.runCatchingCancellable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OfflineLibraryScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatchingCancellable {
        val application = applicationContext as AnimeVaultApplication
        application.container.libraryRepository.scanAllFolders()
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { error ->
            when {
                error is SecurityException -> Result.failure()
                runAttemptCount < MAX_RETRIES -> Result.retry()
                else -> Result.failure()
            }
        },
    )

    private companion object {
        const val MAX_RETRIES = 3
    }
}

class OfflineScanScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun ensureScheduled() {
        if (_enabled.value) schedule() else cancel()
    }

    fun setEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_ENABLED, enabled) }
        _enabled.value = enabled
        if (enabled) schedule() else cancel()
    }

    private fun schedule() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<OfflineLibraryScanWorker>(
            REPEAT_INTERVAL_HOURS,
            TimeUnit.HOURS,
        ).setConstraints(constraints)
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun cancel() {
        WorkManager.getInstance(appContext).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    private companion object {
        const val PREFERENCES_NAME = "offline_scan_settings"
        const val KEY_ENABLED = "periodic_scan_enabled"
        const val UNIQUE_WORK_NAME = "offline-library-periodic-scan"
        const val REPEAT_INTERVAL_HOURS = 24L
    }
}
