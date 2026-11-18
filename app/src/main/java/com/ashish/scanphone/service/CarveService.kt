package com.ashish.scanphone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.ashish.scanphone.R
import com.ashish.scanphone.recover.CarveController
import com.ashish.scanphone.recover.RecoveryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.os.SystemClock

class CarveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var carveJob: Job? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCarve()
                return START_NOT_STICKY
            }

            else -> startCarve()
        }
        return START_NOT_STICKY
    }

    private fun startCarve() {
        if (carveJob?.isActive == true) return
        startForegroundCompat("Starting full-partition carve…")
        CarveController.onStart()

        carveJob = scope.launch {
            val manager = RecoveryManager(applicationContext)
            var carved = 0
            runCatching {
                carved = manager.carveFullPartition { bytes, files ->
                    CarveController.onProgress(bytes, files)
                    val now = SystemClock.uptimeMillis()
                    if (now - lastNotify >= NOTIFY_INTERVAL_MS) {
                        lastNotify = now
                        updateNotification("${bytes / (1024 * 1024)} MB scanned · $files files")
                    }
                }
            }
            val message = if (carved == 0) {
                "Carve finished — nothing recovered (partition may be encrypted)"
            } else {
                "Carve finished — $carved files (many may be partial)"
            }
            CarveController.onFinished(carved, message)
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun stopCarve() {
        carveJob?.cancel()
        carveJob = null
        CarveController.onFinished(CarveController.status.value.filesCarved, "Carve stopped")
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun startForegroundCompat(text: String) {
        ensureChannel()
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Recovering files")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

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
                NotificationChannel(CHANNEL_ID, "File recovery", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "carve_progress"
        private const val NOTIFICATION_ID = 43
        private const val NOTIFY_INTERVAL_MS = 1500L
        private const val ACTION_START = "com.ashish.scanphone.CARVE_START"
        private const val ACTION_STOP = "com.ashish.scanphone.CARVE_STOP"

        fun start(context: Context) {
            val intent = Intent(context, CarveService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CarveService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
