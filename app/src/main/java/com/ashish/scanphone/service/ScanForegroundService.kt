package com.ashish.scanphone.service

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
import com.ashish.scanphone.MainActivity
import com.ashish.scanphone.R
import com.ashish.scanphone.ui.AppTab

class ScanForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
            }

            else -> {
                val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Scanning your device…"
                startForegroundCompat(text)
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(text: String) {
        ensureChannel()
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Scan Phone")
            .setContentText(text)
            .setSubText("Search in progress")
            .setSmallIcon(R.drawable.ic_stat_scan)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    MainActivity.openTabIntent(this, AppTab.SCAN),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Device scan", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "scan_progress"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_UPDATE = "com.ashish.scanphone.SCAN_UPDATE"
        private const val ACTION_STOP = "com.ashish.scanphone.SCAN_STOP"
        private const val EXTRA_TEXT = "text"

        fun start(context: Context, text: String) {
            val intent = Intent(context, ScanForegroundService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_TEXT, text)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScanForegroundService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
