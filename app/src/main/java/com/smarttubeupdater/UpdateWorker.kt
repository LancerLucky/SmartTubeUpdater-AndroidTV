package com.smarttubeupdater

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic background check of the SmartTube repository.
 * Notifies once whenever a newer stable version than the last seen one appears.
 */
class UpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = UpdaterPrefs(applicationContext)

        val release = try {
            GitHubClient.fetchLatestStable()
        } catch (t: Throwable) {
            // Usually no network: let WorkManager retry with backoff.
            return Result.retry()
        }

        val lastSeen = prefs.lastSeenStableVersion
        if (lastSeen.isNullOrBlank()) {
            // First ever run: remember the version, nothing to announce yet.
            prefs.lastSeenStableVersion = release.versionLabel
            return Result.success()
        }

        if (compareVersions(release.versionLabel, lastSeen) > 0) {
            prefs.lastSeenStableVersion = release.versionLabel
            Notifications.notifyNewRelease(applicationContext, release)
        }
        return Result.success()
    }
}

/** Schedules the recurring release check. */
object UpdateScheduler {

    private const val WORK_NAME = "smarttube-stable-release-check"
    private const val REPEAT_HOURS = 12L

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(REPEAT_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()

        try {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        } catch (t: Throwable) {
            // WorkManager is unavailable (e.g. direct boot) - in-app checks still work.
        }
    }
}
