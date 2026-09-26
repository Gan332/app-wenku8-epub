package com.wenku8.epubstudio.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.wenku8.epubstudio.MainActivity
import com.wenku8.epubstudio.R
import com.wenku8.epubstudio.model.JobStatus
import com.wenku8.epubstudio.ui.NotificationRoute
import com.wenku8.epubstudio.ui.StudioViewModel
import com.wenku8.epubstudio.ui.isOngoing

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
        val status = intent?.getStringExtra(EXTRA_STATUS)
            ?.let { raw -> JobStatus.entries.firstOrNull { it.name == raw } }
        val finished = intent?.getBooleanExtra(EXTRA_FINISHED, false) ?: false
        val route = intent?.getStringExtra(EXTRA_ROUTE)
            ?: NotificationRoute.defaultFor(percent, status)
        val ongoing = !finished && percent < 100 && (status == null || status.isOngoing())
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(openIntent(this, jobId, route))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)
            .setProgress(100, percent.coerceIn(0, 100), ongoing && percent <= 0)
            .build()
        if (ongoing) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            // 任务已经结束：普通通知，点击进入书架的「导出记录」，不再占用前台服务。
            stopForeground(STOP_FOREGROUND_DETACH)
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
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
        private const val EXTRA_STATUS = "status"
        private const val EXTRA_FINISHED = "finished"
        private const val EXTRA_ROUTE = "route"

        fun start(context: Context, jobId: String, title: String, message: String, percent: Int = 0) {
            ContextCompat.startForegroundService(context, intent(context, jobId, title, message, percent, null, false))
        }

        fun update(context: Context, jobId: String, title: String, message: String, percent: Int) {
            context.startService(intent(context, jobId, title, message, percent, null, false))
        }

        /**
         * 任务收尾：把前台通知换成一次性通知，点击直接进入书架的「导出记录」。
         * 建议由 [ExportJobManager] 在 completed / failed / canceled 之后调用，
         * 替代直接 [stop]。
         */
        fun finish(context: Context, jobId: String, title: String, message: String, status: JobStatus) {
            runCatching {
                context.startService(intent(context, jobId, title, message, 100, status, true))
            }.onFailure {
                NotificationManagerCompat.from(context).notify(
                    NOTIFICATION_ID,
                    NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_stat_book)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setContentIntent(openIntent(context, jobId, NotificationRoute.defaultFor(100, status)))
                        .setAutoCancel(true)
                        .setOnlyAlertOnce(true)
                        .build(),
                )
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ExportNotificationService::class.java))
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }

        /**
         * 每个 jobId 一个 requestCode，避免 PendingIntent 被复用串到别的任务上。
         */
        private fun openIntent(context: Context, jobId: String, route: String): PendingIntent = PendingIntent.getActivity(
            context,
            NotificationRoute.requestCodeFor(jobId),
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(StudioViewModel.EXTRA_ROUTE, route)
                putExtra(StudioViewModel.EXTRA_JOB_ID, jobId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun intent(
            context: Context,
            jobId: String,
            title: String,
            message: String,
            percent: Int,
            status: JobStatus?,
            finished: Boolean,
        ) = Intent(context, ExportNotificationService::class.java).apply {
            putExtra(EXTRA_JOB_ID, jobId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_PERCENT, percent)
            if (finished) putExtra(EXTRA_ROUTE, NotificationRoute.defaultFor(percent, status))
            putExtra(EXTRA_FINISHED, finished)
            status?.let { putExtra(EXTRA_STATUS, it.name) }
        }
    }
}
