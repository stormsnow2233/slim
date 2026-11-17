package com.storm.shijuan.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Keeps in-process comic/novel cache workers foreground while at least one task runs. */
class CacheDownloadService : Service() {
    companion object {
        private const val TAG = "CacheDownloadService"
        private const val CHANNEL_ID = "shijuan_cache_foreground"
        private const val NOTIFICATION_ID = 6001
        @Volatile private var pausingAfterFailure = false

        /** Called after downloader task state changes; a paused task does not require a foreground service. */
        fun sync(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, CacheDownloadService::class.java)
            val active = ComicDownloader.tasks.any { it.state == STATE_RUNNING } ||
                NovelDownloader.tasks.any { it.state == STATE_RUNNING }
            runCatching {
                if (active) ContextCompat.startForegroundService(appContext, intent)
                else appContext.stopService(intent)
            }.onFailure {
                Log.e(TAG, "Cannot synchronize foreground download service", it)
                if (active) pauseRunningTasks()
            }
        }

        private fun pauseRunningTasks() {
            if (pausingAfterFailure) return
            pausingAfterFailure = true
            try {
                ComicDownloader.tasks.filter { it.state == STATE_RUNNING }.map { it.key }
                    .forEach(ComicDownloader::pause)
                NovelDownloader.tasks.filter { it.state == STATE_RUNNING }.map { it.key }
                    .forEach(NovelDownloader::pause)
            } finally {
                pausingAfterFailure = false
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val activeCount = ComicDownloader.tasks.count { it.state == STATE_RUNNING } +
            NovelDownloader.tasks.count { it.state == STATE_RUNNING }
        // Even if the final task stopped while the start request was queued, Android requires
        // startForeground promptly after startForegroundService before we stop the service.
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "离线缓存后台任务", NotificationManager.IMPORTANCE_LOW),
                )
            }
            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("离线缓存进行中")
                .setContentText("正在缓存 $activeCount 项任务")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Cannot enter foreground for cache downloads; pausing active downloads", e)
            pauseRunningTasks()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (activeCount == 0) stopSelfResult(startId)
        // Workers live in the current process; a restarted service must not claim to resume them.
        return START_NOT_STICKY
    }

    // Android 15 limits dataSync foreground service time across the app's background usage.
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "dataSync foreground service timed out; pausing active downloads")
        pauseRunningTasks()
        stopSelf()
    }
}
