package com.ashish.scanphone.recover

import android.content.Context
import org.json.JSONArray
import java.io.File

class RecoveryJournal private constructor(
    private val file: File,
    val trashDir: File,
) {
    private val lock = Any()

    fun add(entry: RecoveryEntry) {
        synchronized(lock) {
            val entries = readAll().toMutableList()
            entries.add(0, entry)
            while (entries.size > MAX_ENTRIES) {
                val removed = entries.removeAt(entries.size - 1)
                removed.trashPath?.let { path -> File(path).takeIf { it.startsWith(trashDir) }?.delete() }
            }
            persist(entries)
        }
    }

    fun entries(): List<RecoveryEntry> = synchronized(lock) { readAll() }

    fun remove(id: String) {
        synchronized(lock) {
            val entries = readAll()
            entries.firstOrNull { it.id == id }?.trashPath?.let { path ->
                File(path).takeIf { it.startsWith(trashDir) }?.delete()
            }
            persist(entries.filterNot { it.id == id })
        }
    }

    fun newTrashFile(): File {
        trashDir.mkdirs()
        return File(trashDir, "trash_${System.currentTimeMillis()}_${(0..Int.MAX_VALUE).random()}")
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
        private const val MAX_ENTRIES = 1000

        fun get(context: Context): RecoveryJournal {
            val app = context.applicationContext
            return RecoveryJournal(
                file = File(app.filesDir, "recovery_journal.json"),
                trashDir = File(app.filesDir, "trash"),
            )
        }
    }
}
