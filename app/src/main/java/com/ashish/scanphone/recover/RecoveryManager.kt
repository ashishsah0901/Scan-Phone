package com.ashish.scanphone.recover

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import com.ashish.scanphone.scan.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext

class RecoveryManager(private val context: Context) {

    private val journal = RecoveryJournal.get(context)
    private val carveStore = CarveResultStore.get(context)

    fun entries(): List<RecoveryEntry> = journal.entries()

    fun carvedEntries(): List<RecoveryEntry> = carveStore.list()

    fun forgetCarved(id: String) = carveStore.remove(id)

    fun clearCarved() = carveStore.clear()

    suspend fun carveFullPartition(onProgress: suspend (Long, Int) -> Unit): Int =
        withContext(Dispatchers.IO) {
            if (!RootShell.isAvailable()) return@withContext 0
            val source = locateCarveSource(fullScan = true) ?: return@withContext 0

            val carver = SignatureCarver(File(context.cacheDir, "carved"))
            carver.carve(
                sourceCommand = source,
                maxScanBytes = Long.MAX_VALUE,
                maxFiles = MAX_FULL_CARVE_FILES,
                onProgress = onProgress,
            ) { carved ->
                carveStore.add(
                    RecoveryEntry(
                        id = "carve:${carved.file.absolutePath}",
                        type = RecoveryType.CARVED,
                        title = carved.file.name,
                        subtitle = "${carved.type} · ${carved.file.length() / 1024} KB",
                        deletedAt = System.currentTimeMillis(),
                        source = RecoverySource.DEEP_SCAN,
                        originalPath = carved.file.absolutePath,
                        trashPath = carved.file.absolutePath,
                        viaRoot = false,
                    ),
                )
            }
        }

    suspend fun restore(entry: RecoveryEntry): RestoreResult = withContext(Dispatchers.IO) {
        val result = try {
            when (entry.type) {
                RecoveryType.FILE, RecoveryType.QUARANTINE -> restoreLocalFile(entry)
                RecoveryType.CARVED -> restoreCarved(entry)
                RecoveryType.APP_DATA_FILE -> restoreAppDataFile(entry)
                RecoveryType.CONTACT -> restoreContact(entry)
                RecoveryType.CALENDAR -> restoreCalendar(entry)
                RecoveryType.CALL_LOG -> restoreCallLog(entry)
                RecoveryType.MESSAGE -> restoreMessage(entry)
            }
        } catch (security: SecurityException) {
            RestoreResult.Failure(security.message ?: "permission denied")
        } catch (error: Exception) {
            RestoreResult.Failure(error.message ?: "restore failed")
        }
        if (result is RestoreResult.Success && entry.source == RecoverySource.RECYCLE_BIN) {
            journal.remove(entry.id)
        }
        result
    }

    suspend fun signatureCarve(): List<RecoveryEntry> = withContext(Dispatchers.IO) {
        if (!RootShell.isAvailable()) return@withContext emptyList()
        val source = locateCarveSource() ?: return@withContext emptyList()

        val carver = SignatureCarver(File(context.cacheDir, "carved"))
        val entries = ArrayList<RecoveryEntry>()
        carver.carve(source, MAX_CARVE_SCAN_BYTES, MAX_CARVE_FILES) { carved ->
            entries.add(
                RecoveryEntry(
                    id = "carve:${carved.file.absolutePath}",
                    type = RecoveryType.CARVED,
                    title = carved.file.name,
                    subtitle = "${carved.type} · ${carved.file.length() / 1024} KB",
                    deletedAt = 0,
                    source = RecoverySource.DEEP_SCAN,
                    originalPath = carved.file.absolutePath,
                    trashPath = carved.file.absolutePath,
                    viaRoot = false,
                ),
            )
        }
        entries
    }

    private fun locateCarveSource(fullScan: Boolean = false): String? {
        for (candidate in USERDATA_LINKS) {
            val resolved = RootShell.readOutput("readlink -f ${RootShell.quote(candidate)} 2>/dev/null")
            if (!resolved.isNullOrBlank() && resolved.startsWith("/dev/")) {
                return if (fullScan) {
                    "dd if=${RootShell.quote(resolved)} bs=1048576 2>/dev/null"
                } else {
                    val countMb = MAX_CARVE_SCAN_BYTES / (1024 * 1024)
                    "dd if=${RootShell.quote(resolved)} bs=1048576 count=$countMb 2>/dev/null"
                }
            }
        }
        return null
    }

    suspend fun deepScan(): List<RecoveryEntry> = withContext(Dispatchers.IO) {
        if (!RootShell.isAvailable()) return@withContext emptyList()

        val found = LinkedHashMap<String, RecoveryEntry>()
        for (pattern in DEEP_SCAN_PATTERNS) {
            coroutineContext.ensureActive()
            if (found.size >= MAX_DEEP_RESULTS) break
            val output = RootShell.readOutput(
                "find $DEEP_SCAN_ROOTS $pattern -type f 2>/dev/null | head -n $MAX_DEEP_RESULTS",
            ) ?: continue
            output.lineSequence().filter { it.isNotBlank() }.forEach { path ->
                if (found.size >= MAX_DEEP_RESULTS || found.containsKey(path)) return@forEach
                found[path] = RecoveryEntry(
                    id = "deep:$path",
                    type = RecoveryType.CARVED,
                    title = path.substringAfterLast('/'),
                    subtitle = path,
                    deletedAt = 0,
                    source = RecoverySource.DEEP_SCAN,
                    originalPath = path,
                    trashPath = path,
                    viaRoot = true,
                )
            }
        }
        found.values.toList()
    }

    private fun restoreCarved(entry: RecoveryEntry): RestoreResult {
        val path = entry.originalPath ?: entry.trashPath ?: return RestoreResult.Failure("unknown path")
        val direct = File(path)
        if (direct.canRead()) return restoreToDownloads(direct, entry.title)
        if (!RootShell.isAvailable()) return RestoreResult.Failure("needs root to read this file")

        val temp = File(context.cacheDir, "carve_${path.hashCode()}")
        val ok = RootShell.readOutput(
            "cat ${RootShell.quote(path)} > ${RootShell.quote(temp.absolutePath)} && echo ok",
        )
        return if (ok == "ok" && temp.exists() && temp.length() > 0) {
            restoreToDownloads(temp, entry.title).also { temp.delete() }
        } else {
            temp.delete()
            RestoreResult.Failure("could not read the file")
        }
    }

    private fun restoreLocalFile(entry: RecoveryEntry): RestoreResult {
        val trash = entry.trashPath?.let(::File)
            ?: return RestoreResult.Failure("no recoverable copy")
        if (!trash.exists()) return RestoreResult.Failure("recoverable copy is gone")

        val original = entry.originalPath?.let(::File)
        if (original != null && original.parentFile?.let { it.exists() || it.mkdirs() } == true) {
            return runCatching {
                trash.copyTo(original, overwrite = true)
                RestoreResult.Success("Restored to ${original.absolutePath}")
            }.getOrElse { restoreToDownloads(trash, entry.title) }
        }
        return restoreToDownloads(trash, entry.title)
    }

    private fun restoreAppDataFile(entry: RecoveryEntry): RestoreResult {
        val trash = entry.trashPath?.let(::File) ?: return RestoreResult.Failure("no recoverable copy")
        if (!trash.exists()) return RestoreResult.Failure("recoverable copy is gone")
        val original = entry.originalPath ?: return RestoreResult.Failure("unknown original path")
        if (!RootShell.isAvailable()) return restoreToDownloads(trash, entry.title)

        val ok = RootShell.readOutput(
            "cat ${RootShell.quote(trash.absolutePath)} > ${RootShell.quote(original)} && echo ok",
        )
        return if (ok == "ok") {
            RestoreResult.Success("Restored to $original")
        } else {
            restoreToDownloads(trash, entry.title)
        }
    }

    private fun restoreToDownloads(source: File, name: String): RestoreResult {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ScanPhone/Recovered")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return RestoreResult.Failure("could not create the recovered file")
        resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
            ?: return RestoreResult.Failure("could not write the recovered file")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return RestoreResult.Success("Recovered to Downloads/ScanPhone/Recovered/$name")
    }

    private fun restoreContact(entry: RecoveryEntry): RestoreResult {
        val rows = entry.payloadArray() ?: return RestoreResult.Failure("no contact snapshot")
        val ops = ArrayList<android.content.ContentProviderOperation>()
        ops.add(
            android.content.ContentProviderOperation
                .newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build(),
        )
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val builder = android.content.ContentProviderOperation
                .newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            row.keys().forEach { key ->
                builder.withValue(key, row.getString(key))
            }
            ops.add(builder.build())
        }
        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        return RestoreResult.Success("Contact restored")
    }

    private fun restoreCalendar(entry: RecoveryEntry): RestoreResult {
        val row = entry.payloadObject() ?: return RestoreResult.Failure("no event snapshot")
        val values = contentValuesFrom(row)
        if (!values.containsKey(CalendarContract.Events.CALENDAR_ID)) {
            return RestoreResult.Failure("original calendar is unknown")
        }
        context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return RestoreResult.Failure("could not re-create the event")
        return RestoreResult.Success("Event restored")
    }

    private fun restoreCallLog(entry: RecoveryEntry): RestoreResult {
        val row = entry.payloadObject() ?: return RestoreResult.Failure("no call snapshot")
        context.contentResolver.insert(CallLog.Calls.CONTENT_URI, contentValuesFrom(row))
            ?: return RestoreResult.Failure("could not re-create the call")
        return RestoreResult.Success("Call log entry restored")
    }

    private fun restoreMessage(entry: RecoveryEntry): RestoreResult {
        val row = entry.payloadObject() ?: return RestoreResult.Failure("no message snapshot")
        return try {
            context.contentResolver.insert(
                android.provider.Telephony.Sms.CONTENT_URI,
                contentValuesFrom(row),
            ) ?: return RestoreResult.Failure("blocked — must be the default SMS app")
            RestoreResult.Success("Message restored")
        } catch (security: SecurityException) {
            RestoreResult.Failure("blocked — must be the default SMS app")
        }
    }

    private fun contentValuesFrom(row: JSONObject): ContentValues {
        val values = ContentValues()
        row.keys().forEach { key -> values.put(key, row.getString(key)) }
        return values
    }

    private companion object {
        const val MAX_DEEP_RESULTS = 300
        const val MAX_CARVE_FILES = 300
        const val MAX_FULL_CARVE_FILES = 20_000
        const val MAX_CARVE_SCAN_BYTES = 2L * 1024 * 1024 * 1024
        const val DEEP_SCAN_ROOTS = "/data/media/0 /sdcard /data/data"
        val USERDATA_LINKS = listOf(
            "/dev/block/by-name/userdata",
            "/dev/block/bootdevice/by-name/userdata",
            "/dev/block/mapper/userdata",
        )
        val DEEP_SCAN_PATTERNS = listOf(
            "-name '.trashed-*'",
            "-path '*/.Trash*'",
            "-path '*/LostDir/*'",
            "\\( -name '*.jpg' -o -name '*.jpeg' -o -name '*.mp4' \\) -path '*cache*'",
        )
    }
}
