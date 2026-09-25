package com.wenku8.epubstudio.task

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import com.wenku8.epubstudio.MainActivity
import com.wenku8.epubstudio.R

@CapacitorPlugin(name = "TaskGuard")
class TaskGuardPlugin : Plugin() {
    @PluginMethod
    fun start(call: PluginCall) {
        val jobId = call.getString("jobId")
        val title = call.getString("title") ?: "导出任务"
        val message = call.getString("message") ?: "正在处理"
        if (jobId.isNullOrBlank()) {
            call.reject("缺少任务编号。", "INVALID_JOB")
            return
        }
        ContextCompat.startForegroundService(context, ExportForegroundService.intent(context, ExportForegroundService.ACTION_START, jobId, title, message, 0))
        call.resolve()
    }

    @PluginMethod
    fun update(call: PluginCall) {
        val jobId = call.getString("jobId")
        val title = call.getString("title") ?: "导出任务"
        val message = call.getString("message") ?: "正在处理"
        val percent = call.getInt("percent") ?: 0
        if (jobId.isNullOrBlank()) {
            call.reject("缺少任务编号。", "INVALID_JOB")
            return
        }
        context.startService(ExportForegroundService.intent(context, ExportForegroundService.ACTION_UPDATE, jobId, title, message, percent))
        call.resolve()
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        val jobId = call.getString("jobId")
        if (!jobId.isNullOrBlank()) context.stopService(Intent(context, ExportForegroundService::class.java))
        call.resolve()
    }

    @Permission(permissions = [Manifest.permission.POST_NOTIFICATIONS])
    @PluginMethod
    fun requestNotificationPermission(call: PluginCall) {
        if (Build.VERSION.SDK_INT < 33) {
            call.resolve(JSObject().put("granted", true))
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            call.resolve(JSObject().put("granted", true))
            return
        }
        requestPermissionForAlias("notification", call, "notificationPermissionCallback")
    }

    @PermissionCallback
    private fun notificationPermissionCallback(call: PluginCall) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        call.resolve(JSObject().put("granted", granted))
    }
}

class ExportForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val jobId = intent?.getStringExtra(EXTRA_JOB_ID) ?: "export"
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "导出任务"
        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: "正在处理"
        val percent = intent?.getIntExtra(EXTRA_PERCENT, 0) ?: 0
        startForeground(NOTIFICATION_ID, buildNotification(jobId, title, message, percent))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(jobId: String, title: String, message: String, percent: Int) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_book)
        .setContentTitle(title)
        .setContentText(message)
        .setContentIntent(PendingIntent.getActivity(this, jobId.hashCode(), Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(100, percent.coerceIn(0, 100), false)
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "EPUB 导出任务", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        const val ACTION_START = "com.wenku8.epubstudio.task.START"
        const val ACTION_UPDATE = "com.wenku8.epubstudio.task.UPDATE"
        const val ACTION_STOP = "com.wenku8.epubstudio.task.STOP"
        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_PERCENT = "percent"
        private const val CHANNEL_ID = "wenku8_export"
        private const val NOTIFICATION_ID = 4210

        fun intent(context: android.content.Context, action: String, jobId: String, title: String, message: String, percent: Int): Intent =
            Intent(context, ExportForegroundService::class.java).apply {
                this.action = action
                putExtra(EXTRA_JOB_ID, jobId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_PERCENT, percent)
            }
    }
}
