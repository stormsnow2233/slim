package com.storm.shijuan.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.storm.shijuan.MainActivity

/**
 * 书籍与漫画导入后台前台服务：
 * 在用户选择「后台导入」或切换应用时保持后台运行，并在系统通知栏实时显示导入进度条与状态。
 */
class BookImportService : Service() {

    companion object {
        private const val TAG = "BookImportService"
        const val CHANNEL_ID = "shijuan_book_import"
        const val NOTIFICATION_ID = 6002

        const val ACTION_START = "com.storm.shijuan.action.START_IMPORT"
        const val ACTION_UPDATE = "com.storm.shijuan.action.UPDATE_IMPORT"
        const val ACTION_STOP = "com.storm.shijuan.action.STOP_IMPORT"
        const val ACTION_COMPLETE = "com.storm.shijuan.action.COMPLETE_IMPORT"

        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_STAGE = "extra_stage"
        const val EXTRA_SUCCESS = "extra_success"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_ERROR = "extra_error"

        fun start(context: Context, fileName: String, progress: Int, stage: String) {
            val intent = Intent(context, BookImportService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_STAGE, stage)
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                Log.e(TAG, "Failed to start BookImportService in foreground", it)
            }
        }

        fun updateProgress(context: Context, fileName: String, progress: Int, stage: String) {
            val intent = Intent(context, BookImportService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_STAGE, stage)
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                Log.e(TAG, "Failed to update BookImportService", it)
            }
        }

        fun notifyComplete(context: Context, fileName: String, label: String) {
            val intent = Intent(context, BookImportService::class.java).apply {
                action = ACTION_COMPLETE
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_SUCCESS, true)
                putExtra(EXTRA_LABEL, label)
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                Log.e(TAG, "Failed to notify complete in BookImportService", it)
            }
        }

        fun notifyError(context: Context, fileName: String, errorMessage: String) {
            val intent = Intent(context, BookImportService::class.java).apply {
                action = ACTION_COMPLETE
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_SUCCESS, false)
                putExtra(EXTRA_ERROR, errorMessage)
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                Log.e(TAG, "Failed to notify error in BookImportService", it)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BookImportService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                Log.e(TAG, "Failed to stop BookImportService", it)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "书籍导入",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "显示书籍与漫画导入后台进度"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun createPendingIntent(): PendingIntent {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(this, 0, launchIntent, flags)
    }

    private fun buildNotification(fileName: String, progress: Int, stage: String): Notification {
        val title = if (fileName.isNotBlank()) "正在导入《$fileName》" else "正在导入书籍"
        val contentText = "$stage ($progress%)"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(contentText)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(createPendingIntent())
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)

        when (intent?.action) {
            ACTION_START -> {
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: ""
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                val stage = intent.getStringExtra(EXTRA_STAGE) ?: "正在处理..."
                val notification = buildNotification(fileName, progress, stage)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot startForeground in BookImportService", e)
                }
            }
            ACTION_UPDATE -> {
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: ""
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                val stage = intent.getStringExtra(EXTRA_STAGE) ?: "正在处理..."
                val notification = buildNotification(fileName, progress, stage)
                manager.notify(NOTIFICATION_ID, notification)
            }
            ACTION_COMPLETE -> {
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: ""
                val success = intent.getBooleanExtra(EXTRA_SUCCESS, true)
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "书架"
                val error = intent.getStringExtra(EXTRA_ERROR) ?: "导入失败"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }

                val finalNotification = if (success) {
                    NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("《$fileName》导入完成")
                        .setContentText("已成功加入 $label，点击查看")
                        .setProgress(0, 0, false)
                        .setOngoing(false)
                        .setAutoCancel(true)
                        .setContentIntent(createPendingIntent())
                        .build()
                } else {
                    NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .setContentTitle("《$fileName》导入失败")
                        .setContentText(error)
                        .setProgress(0, 0, false)
                        .setOngoing(false)
                        .setAutoCancel(true)
                        .setContentIntent(createPendingIntent())
                        .build()
                }
                manager.notify(NOTIFICATION_ID + 1, finalNotification)
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }
}
