package com.fishkingsin.manhuagui.bookmarks

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fishkingsin.manhuagui.App
import com.fishkingsin.manhuagui.settings.AppSettings
import java.util.concurrent.TimeUnit

class BookmarkUpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? App ?: return Result.failure()
        val updates = app.container.bookmarkUpdateChecker.checkAll()
        BookmarkUpdateNotification.postIfUpdated(applicationContext, updates)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "bookmark_update_periodic"

        fun schedulePeriodic(
            context: Context,
            intervalHours: Int,
            policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP,
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val interval = intervalHours.takeIf { it > 0 } ?: AppSettings.DEFAULT_CHECK_INTERVAL_HOURS
            val request = PeriodicWorkRequestBuilder<BookmarkUpdateWorker>(
                interval.toLong(),
                TimeUnit.HOURS,
            ).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, policy, request)
        }
    }
}
