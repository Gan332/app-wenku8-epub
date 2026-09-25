package com.wenku8.epubstudio.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.wenku8.epubstudio.MainActivity
import com.wenku8.epubstudio.R

class ExportNotificationService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "EPUB 导出任务", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobId = intent?.getStringExtra(EXTRA_JOB_ID) ?: "export"
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "导出任务"
        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: "正在处理"
        val percent = intent?.getIntExtra(EXTRA_PERCENT, 0) ?: 0
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(PendingIntent.getActivity(this, jobId.hashCode(), Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .build())
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "wenku8_export"
        private const val NOTIFICATION_ID = 4210
        private const val EXTRA_JOB_ID = "job_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_PERCENT = "percent"

        fun start(context: Context, jobId: String, title: String, message: String, percent: Int = 0) {
            ContextCompat.startForegroundService(context, intent(context, jobId, title, message, percent))
        }

        fun update(context: Context, jobId: String, title: String, message: String, percent: Int) {
            context.startService(intent(context, jobId, title, message, percent))
        }

        fun stop(context: Context) = context.stopService(Intent(context, ExportNotificationService::class.java))

        private fun intent(context: Context, jobId: String, title: String, message: String, percent: Int) = Intent(context, ExportNotificationService::class.java).apply {
            putExtra(EXTRA_JOB_ID, jobId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_PERCENT, percent)
        }
    }
}
