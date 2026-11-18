package com.ashish.scanphone.scan

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.ashish.scanphone.model.MatchKind
import com.ashish.scanphone.model.ScanHit
import com.ashish.scanphone.model.ScanSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

class RootDatabaseReader(private val cacheDir: File) {

    suspend fun read(
        remotePath: String,
        matcher: TextMatcher,
        onHit: suspend (ScanHit) -> Unit,
    ): Int {
        val local = pull(remotePath) ?: return 0
        var found = 0
        try {
            SQLiteDatabase.openDatabase(
                local.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                for (table in tables(db)) {
                    coroutineContext.ensureActive()
                    if (found >= MAX_HITS_PER_DB) break
                    found += scanTable(db, table, remotePath, matcher, MAX_HITS_PER_DB - found, onHit)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return found
        } finally {
            local.delete()
            sidecarsFor(local).forEach(File::delete)
        }
        return found
    }

    private suspend fun scanTable(
        db: SQLiteDatabase,
        table: String,
        remotePath: String,
        matcher: TextMatcher,
        budget: Int,
        onHit: suspend (ScanHit) -> Unit,
    ): Int {
        var found = 0
        val cursor = try {
            db.rawQuery("SELECT * FROM \"$table\" LIMIT $ROW_LIMIT", null)
        } catch (_: Exception) {
            return 0
        }

        cursor.use {
            val textColumns = textColumnIndexes(it)
            if (textColumns.isEmpty()) return 0
            val messageColumn = messageColumnIndex(it)

            while (it.moveToNext() && found < budget) {
                coroutineContext.ensureActive()
                for (column in textColumns) {
                    val value = it.getString(column) ?: continue
                    val index = matcher.indexIn(value)
                    if (index < 0) continue

                    found++
                    onHit(
                        ScanHit(
                            id = "appdb:$remotePath:$table:${it.position}:$column",
                            source = ScanSource.APP_DATA,
                            matchKind = MatchKind.APP_DATABASE_ROW,
                            title = rowTitle(it, table, messageColumn, remotePath),
                            subtitle = "${owningPackage(remotePath)} · ${it.getColumnName(column)} · row ${it.position}",
                            snippet = matcher.snippet(value, index),
                            openTarget = null,
                            verifyRef = remotePath,
                        ),
                    )
                    break
                }
            }
        }
        return found
    }

    private fun rowTitle(cursor: Cursor, table: String, messageColumn: Int, remotePath: String): String {
        if (messageColumn >= 0) {
            val text = cursor.getString(messageColumn)?.trim().orEmpty()
            if (text.isNotEmpty()) {
                return "${remotePath.substringAfterLast('/')} · $table: ${text.take(TITLE_PREVIEW)}"
            }
        }
        return "${remotePath.substringAfterLast('/')} · $table"
    }

    private fun messageColumnIndex(cursor: Cursor): Int {
        for (name in MESSAGE_COLUMNS) {
            val index = cursor.getColumnIndex(name)
            if (index >= 0) return index
        }
        return -1
    }

    private fun tables(db: SQLiteDatabase): List<String> {
        val names = ArrayList<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) names.add(cursor.getString(0))
        }
        return names
    }

    private fun textColumnIndexes(cursor: Cursor): List<Int> {
        if (!cursor.moveToFirst()) return emptyList()
        val indexes = ArrayList<Int>()
        for (column in 0 until cursor.columnCount) {
            if (cursor.getType(column) == Cursor.FIELD_TYPE_STRING) indexes.add(column)
        }
        cursor.moveToPosition(-1)
        return indexes
    }

    private fun pull(remotePath: String): File? {
        val stem = "rootdb_${remotePath.hashCode()}"
        val local = File(cacheDir, "$stem.db")
        if (!copy(remotePath, local)) {
            local.delete()
            return null
        }
        for (suffix in SIDECAR_SUFFIXES) {
            copy(remotePath + suffix, File(cacheDir, "$stem.db$suffix"))
        }
        return local
    }

    private fun copy(remotePath: String, local: File): Boolean {
        val copied = RootShell.readOutput(
            "cat ${RootShell.quote(remotePath)} > ${RootShell.quote(local.absolutePath)} " +
                "&& chmod 600 ${RootShell.quote(local.absolutePath)} && echo ok",
        )
        return copied == "ok" && local.exists() && local.length() > 0
    }

    private fun sidecarsFor(local: File): List<File> =
        SIDECAR_SUFFIXES.map { File(local.parentFile, "${local.name}$it") }

    private fun owningPackage(path: String): String =
        path.removePrefix("/data/data/").substringBefore('/')

    private companion object {
        const val MAX_HITS_PER_DB = 200
        const val ROW_LIMIT = 5000
        const val TITLE_PREVIEW = 60

        val SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")

        val MESSAGE_COLUMNS = listOf(
            "text_data", "data", "body", "message", "content", "snippet", "text", "comment",
        )
    }
}
