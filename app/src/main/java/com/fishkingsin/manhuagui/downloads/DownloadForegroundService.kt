package com.fishkingsin.manhuagui.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.fishkingsin.manhuagui.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class DownloadForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: OfflineDownloadManager
    private var progressJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        manager = (application as App).container.downloadManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(manager.progress.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (progressJob?.isActive != true) {
            progressJob = serviceScope.launch {
                manager.progress.collect(::updateNotification)
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        manager.cancelAllActive()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(progress: Map<Long, ChapterDownloadProgress>) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(progress))
    }

    private fun buildNotification(progress: Map<Long, ChapterDownloadProgress>) =
        progress.values.firstOrNull { it.status == ChapterDownloadStatus.DOWNLOADING }.let { active ->
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Offline downloads")
                .setContentText(progressText(progress))
                .setProgress(
                    active?.totalPages ?: 0,
                    active?.downloadedPages ?: 0,
                    active == null || active.totalPages == 0,
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }

    private fun progressText(progress: Map<Long, ChapterDownloadProgress>): String {
        val active = progress.values.firstOrNull { it.status == ChapterDownloadStatus.DOWNLOADING }
        return if (active == null) {
            "${progress.values.count { it.status == ChapterDownloadStatus.QUEUED }} chapters queued"
        } else {
            "Chapter ${active.chapterId}: ${active.downloadedPages}/${active.totalPages} pages"
        }
    }

    companion object {
        const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DownloadForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadForegroundService::class.java))
        }
    }
}
