package com.ashish.scanphone.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class ScanNotificationListenerService : NotificationListenerService() {

    private val store by lazy { NotificationStore.get(applicationContext) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching { activeNotifications }.getOrNull()?.forEach(::capture)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let(::capture)
    }

    private fun capture(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras ?: return
        val title = extras.charSequence(Notification.EXTRA_TITLE)
        val text = extras.charSequence(Notification.EXTRA_TEXT)
            ?: extras.charSequence(Notification.EXTRA_BIG_TEXT)
        if (title.isBlank() && text.isBlank()) return

        store.record(
            NotificationRecord(
                key = sbn.key ?: "${sbn.packageName}:${sbn.postTime}",
                packageName = sbn.packageName.orEmpty(),
                appLabel = labelOf(sbn.packageName),
                title = title,
                text = text,
                postedAt = sbn.postTime,
            ),
        )
    }

    private fun labelOf(packageName: String?): String {
        if (packageName == null) return ""
        return runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    private fun android.os.Bundle.charSequence(key: String): String =
        getCharSequence(key)?.toString().orEmpty()

}
