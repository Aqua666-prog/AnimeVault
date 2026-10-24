package com.sergey.animevault.data.notifications

import com.sergey.animevault.data.titles.*
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sergey.animevault.AnimeVaultApplication
import com.sergey.animevault.MainActivity
import com.sergey.animevault.R
import com.sergey.animevault.ui.preferences.UiPreferences
import com.sergey.animevault.ui.preferences.VaultTitleList
import com.sergey.animevault.ui.preferences.VaultPersonalTitle
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Only new episode IDs after a successful initial baseline can trigger an alert. */
/** Explicit title subscription wins, then an opt-in whole-list subscription. */
internal fun alertSubscriptions(
    personal: Map<String, VaultPersonalTitle>,
    statuses: Map<String, VaultTitleList>,
    trackedLists: Set<VaultTitleList>,
): Map<String, VaultPersonalTitle> {
    val direct = personal.filterKeys { it.startsWith("online:") }.filterValues { it.notifyEpisodes }
    val byList = statuses.filter { (key, status) -> key.startsWith("online:") && status in trackedLists }
        .mapValues { (key, _) -> personal[key] ?: VaultPersonalTitle() }
    return (byList + direct).toList().take(50).toMap()
}

internal fun unseenEpisodeIds(previous: VaultPersonalTitle, current: List<String>): List<String> {
    if (!previous.baselineReady) return emptyList()
    val known = previous.seenEpisodeIds.toSet()
    return current.distinct().filterNot(known::contains)
}

class EpisodeAlertWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as AnimeVaultApplication
        val prefs = app.container.uiPreferences
        val repo = app.container.onlineRepository
        val subscriptions = alertSubscriptions(prefs.personalTitles.value,
            prefs.titleLists.value, prefs.episodeAlertLists.value)
        if (subscriptions.isEmpty()) return Result.success()
        var failed = false
        for ((key, snapshot) in subscriptions) {
            val parts = key.split(':', limit = 3)
            if (parts.size != 3 || parts[0] != "online") continue
            try {
                val details = snapshot.translationKey?.let { selected ->
                    repo.getReleaseForTranslation(parts[1], parts[2], selected)
                } ?: repo.getRelease(parts[1], parts[2])
                val episodeIds = details.episodes.map { it.id }.filter { it.isNotBlank() }.distinct()
                // Empty/temporary provider replies must not erase a previous baseline.
                if (episodeIds.isEmpty()) continue
                // A concurrent unsubscribed/changed translation must never be re-enabled by this worker.
                val current = prefs.personalTitles.value[key] ?: VaultPersonalTitle()
                val chosenByList = prefs.titleLists.value[key] in prefs.episodeAlertLists.value
                if ((!current.notifyEpisodes && !chosenByList) || current.translationKey != snapshot.translationKey) continue
                val newEpisodes = unseenEpisodeIds(current, episodeIds)
                val mergedIds = (current.seenEpisodeIds + episodeIds).distinct().takeLast(500)
                prefs.updateEpisodeBaseline(key, mergedIds)
                if (newEpisodes.isNotEmpty()) notifyNewEpisodes(key, AnimeTitleResolver.resolve(
                    app.container.animeTitles.index.value.namesFor(listOf(providerTitleKey(details.providerId, details.id)), AnimeNames.original(details.name, details.englishName)),
                    details.name, prefs.titleLanguage.value), newEpisodes.size)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true // A failing source must not block checks of other sources.
            }
        }
        return if (failed && runAttemptCount < 2) Result.retry() else Result.success()
    }

    @android.annotation.SuppressLint("MissingPermission") // POST_NOTIFICATIONS checked below at runtime.
    private fun notifyNewEpisodes(key: String, title: String, count: Int) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Новые серии", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(if (count == 1) "Появилась новая серия" else "Новые серии: $count")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(key.hashCode(), notification)
    }

    companion object { private const val CHANNEL_ID = "vault-new-episodes-v1" }
}

/** Android schedules periodic work approximately, not at a precise release time. */
object EpisodeAlertScheduler {
    private const val WORK_NAME = "vault-episode-alerts-v1"
    fun reconcile(context: Context, preferences: UiPreferences) {
        val work = WorkManager.getInstance(context.applicationContext)
        if (alertSubscriptions(preferences.personalTitles.value, preferences.titleLists.value,
                preferences.episodeAlertLists.value).isEmpty()) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<EpisodeAlertWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
