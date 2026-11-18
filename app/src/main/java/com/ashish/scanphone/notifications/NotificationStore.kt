package com.ashish.scanphone.notifications

import android.content.Context
import org.json.JSONArray
import java.io.File

class NotificationStore private constructor(private val file: File) {

    private val lock = Any()
    private val records = ArrayDeque<NotificationRecord>()
    private var loaded = false

    fun record(record: NotificationRecord) {
        synchronized(lock) {
            ensureLoaded()
            records.addLast(record)
            while (records.size > MAX_RECORDS) records.removeFirst()
            persist()
        }
    }

    fun snapshot(): List<NotificationRecord> = synchronized(lock) {
        ensureLoaded()
        records.toList()
    }

    fun clear() {
        synchronized(lock) {
            records.clear()
            loaded = true
            persist()
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        runCatching {
            val array = JSONArray(file.readText())
            for (index in 0 until array.length()) {
                records.addLast(NotificationRecord.fromJson(array.getJSONObject(index)))
            }
            while (records.size > MAX_RECORDS) records.removeFirst()
        }
    }

    private fun persist() {
        runCatching {
            val array = JSONArray()
            records.forEach { array.put(it.toJson()) }
            file.writeText(array.toString())
        }
    }

    companion object {
        private const val MAX_RECORDS = 5000

        @Volatile
        private var instance: NotificationStore? = null

        fun get(context: Context): NotificationStore =
            instance ?: synchronized(this) {
                instance ?: NotificationStore(
                    File(context.applicationContext.filesDir, "notifications.json"),
                ).also { instance = it }
            }
    }
}
