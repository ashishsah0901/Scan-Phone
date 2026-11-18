package com.ashish.scanphone.recover

import android.content.Context
import org.json.JSONArray
import java.io.File

class CarveResultStore private constructor(private val file: File) {

    private val lock = Any()

    fun add(entry: RecoveryEntry) {
        synchronized(lock) {
            val entries = readAll().toMutableList()
            if (entries.none { it.id == entry.id }) {
                entries.add(0, entry)
                persist(entries)
            }
        }
    }

    fun list(): List<RecoveryEntry> = synchronized(lock) { readAll() }

    fun remove(id: String) {
        synchronized(lock) { persist(readAll().filterNot { it.id == id }) }
    }

    fun clear() {
        synchronized(lock) { runCatching { file.delete() } }
    }

    private fun readAll(): List<RecoveryEntry> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).map { RecoveryEntry.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun persist(entries: List<RecoveryEntry>) {
        runCatching {
            val array = JSONArray()
            entries.forEach { array.put(it.toJson()) }
            file.writeText(array.toString())
        }
    }

    companion object {
        fun get(context: Context): CarveResultStore =
            CarveResultStore(File(context.applicationContext.filesDir, "carve_results.json"))
    }
}
