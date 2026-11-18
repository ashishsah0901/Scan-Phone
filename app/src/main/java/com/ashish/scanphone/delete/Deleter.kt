package com.ashish.scanphone.delete

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.BaseColumns
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import com.ashish.scanphone.model.ScanHit
import com.ashish.scanphone.model.ScanSource
import com.ashish.scanphone.recover.RecoveryEntry
import com.ashish.scanphone.recover.RecoveryJournal
import com.ashish.scanphone.recover.RecoverySource
import com.ashish.scanphone.recover.RecoveryType
import com.ashish.scanphone.scan.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

sealed interface DeleteResult {
    data object Success : DeleteResult

    data class Failure(val reason: String) : DeleteResult

    data object Unsupported : DeleteResult
}

data class DeleteSummary(
    val deletedIds: List<String>,
    val failed: Int,
    val unsupported: Int,
) {
    val deleted: Int get() = deletedIds.size
}

class Deleter(private val context: Context) {

    private val journal = RecoveryJournal.get(context)

    fun canDelete(hit: ScanHit): Boolean = when (hit.source) {
        ScanSource.FILES, ScanSource.MEDIA -> hit.exportPath != null || hit.verifyRef != null
        ScanSource.APP_DATA -> hit.verifyRef != null
        ScanSource.CONTACTS, ScanSource.CALL_LOG, ScanSource.CALENDAR -> hit.verifyRef != null
        ScanSource.MESSAGES -> hit.verifyRef != null
        ScanSource.APPS, ScanSource.NOTIFICATIONS, ScanSource.APP_USAGE -> false
    }

    fun requiredPermission(hit: ScanHit): String? = when (hit.source) {
        ScanSource.CONTACTS -> Manifest.permission.WRITE_CONTACTS
        ScanSource.CALL_LOG -> Manifest.permission.WRITE_CALL_LOG
        ScanSource.CALENDAR -> Manifest.permission.WRITE_CALENDAR
        else -> null
    }

    fun requiredPermissions(hits: List<ScanHit>): Set<String> =
        hits.mapNotNull { requiredPermission(it) }.toSet()

    suspend fun delete(hits: List<ScanHit>): DeleteSummary = withContext(Dispatchers.IO) {
        var failed = 0
        var unsupported = 0
        val deletedIds = ArrayList<String>()

        for (hit in hits) {
            when (deleteOne(hit)) {
                is DeleteResult.Success -> deletedIds.add(hit.id)
                is DeleteResult.Failure -> failed++
                is DeleteResult.Unsupported -> unsupported++
            }
        }
        DeleteSummary(deletedIds, failed, unsupported)
    }

    suspend fun quarantine(hits: List<ScanHit>): DeleteSummary = withContext(Dispatchers.IO) {
        var failed = 0
        var unsupported = 0
        val movedIds = ArrayList<String>()

        for (hit in hits) {
            val path = hit.exportPath ?: hit.verifyRef
            if (hit.source != ScanSource.FILES && hit.source != ScanSource.MEDIA || path == null) {
                unsupported++
                continue
            }
            when (quarantineFile(path)) {
                is DeleteResult.Success -> movedIds.add(hit.id)
                is DeleteResult.Failure -> failed++
                is DeleteResult.Unsupported -> unsupported++
            }
        }
        DeleteSummary(movedIds, failed, unsupported)
    }

    private fun quarantineFile(path: String): DeleteResult {
        val file = File(path)
        if (!file.exists()) return DeleteResult.Failure("file no longer exists")
        val isolated = copyToTrash(file) ?: return DeleteResult.Failure("file too large to quarantine")
        val deleted = file.delete() || (RootShell.isAvailable() && rootRemove(path))
        if (!deleted) {
            isolated.delete()
            return DeleteResult.Failure("could not move the file")
        }
        journal.add(
            baseEntry(RecoveryType.QUARANTINE, file.name, path).copy(
                originalPath = path,
                trashPath = isolated.absolutePath,
            ),
        )
        return DeleteResult.Success
    }

    private fun deleteOne(hit: ScanHit): DeleteResult = try {
        when (hit.source) {
            ScanSource.FILES, ScanSource.MEDIA -> deleteFile(hit)
            ScanSource.APP_DATA -> deleteRootFile(hit)
            ScanSource.CONTACTS -> deleteContact(hit)
            ScanSource.CALL_LOG -> deleteCallLog(hit)
            ScanSource.CALENDAR -> deleteCalendar(hit)
            ScanSource.MESSAGES -> deleteMessage(hit)
            else -> DeleteResult.Unsupported
        }
    } catch (security: SecurityException) {
        DeleteResult.Failure(security.message ?: "permission denied")
    } catch (error: Exception) {
        DeleteResult.Failure(error.message ?: "delete failed")
    }

    private fun deleteFile(hit: ScanHit): DeleteResult {
        val path = hit.exportPath ?: hit.verifyRef ?: return DeleteResult.Unsupported
        val file = File(path)
        if (!file.exists()) return DeleteResult.Success

        val trashCopy = copyToTrash(file)
        val deleted = file.delete() || (RootShell.isAvailable() && rootRemove(path))
        if (!deleted) {
            trashCopy?.delete()
            return DeleteResult.Failure("could not delete the file")
        }
        journalFile(RecoveryType.FILE, path, trashCopy, viaRoot = false)
        return DeleteResult.Success
    }

    private fun deleteRootFile(hit: ScanHit): DeleteResult {
        val path = hit.verifyRef ?: return DeleteResult.Unsupported
        if (!RootShell.isAvailable()) return DeleteResult.Failure("needs root")

        val trashCopy = copyToTrashViaRoot(path)
        if (!rootRemove(path)) {
            trashCopy?.delete()
            return DeleteResult.Failure("root delete failed")
        }
        journalFile(RecoveryType.APP_DATA_FILE, path, trashCopy, viaRoot = true)
        return DeleteResult.Success
    }

    private fun deleteContact(hit: ScanHit): DeleteResult {
        val id = hit.verifyRef?.toLongOrNull() ?: return DeleteResult.Unsupported
        val snapshot = snapshotContact(id)
        val rows = context.contentResolver.delete(
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id),
            null,
            null,
        )
        if (rows <= 0) return DeleteResult.Failure("nothing deleted")
        journal.add(
            baseEntry(RecoveryType.CONTACT, hit.title, hit.subtitle).copy(payload = snapshot.toString()),
        )
        return DeleteResult.Success
    }

    private fun deleteCallLog(hit: ScanHit): DeleteResult {
        val id = hit.verifyRef ?: return DeleteResult.Unsupported
        val snapshot = snapshotRow(CallLog.Calls.CONTENT_URI, id, CALL_LOG_COLUMNS)
        val rows = context.contentResolver.delete(
            CallLog.Calls.CONTENT_URI,
            "${BaseColumns._ID} = ?",
            arrayOf(id),
        )
        if (rows <= 0) return DeleteResult.Failure("nothing deleted")
        journal.add(baseEntry(RecoveryType.CALL_LOG, hit.title, hit.subtitle).copy(payload = snapshot?.toString()))
        return DeleteResult.Success
    }

    private fun deleteCalendar(hit: ScanHit): DeleteResult {
        val id = hit.verifyRef?.toLongOrNull() ?: return DeleteResult.Unsupported
        val snapshot = snapshotRow(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            null,
            CALENDAR_COLUMNS,
        )
        val rows = context.contentResolver.delete(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            null,
            null,
        )
        if (rows <= 0) return DeleteResult.Failure("nothing deleted")
        journal.add(baseEntry(RecoveryType.CALENDAR, hit.title, hit.subtitle).copy(payload = snapshot?.toString()))
        return DeleteResult.Success
    }

    private fun deleteMessage(hit: ScanHit): DeleteResult {
        val ref = hit.verifyRef ?: return DeleteResult.Unsupported
        val id = ref.substringAfter(':').toLongOrNull() ?: return DeleteResult.Unsupported
        val isMms = ref.startsWith("mms:")
        val base = if (isMms) Telephony.Mms.CONTENT_URI else Telephony.Sms.CONTENT_URI
        val uri = ContentUris.withAppendedId(base, id)
        val snapshot = if (isMms) null else snapshotRow(uri, null, SMS_COLUMNS)

        val rows = context.contentResolver.delete(uri, null, null)
        if (rows <= 0) return DeleteResult.Failure("blocked — Scan Phone must be the default SMS app")
        journal.add(baseEntry(RecoveryType.MESSAGE, hit.title, hit.subtitle).copy(payload = snapshot?.toString()))
        return DeleteResult.Success
    }

    private fun journalFile(type: RecoveryType, originalPath: String, trashCopy: File?, viaRoot: Boolean) {
        if (trashCopy == null) return
        journal.add(
            baseEntry(type, File(originalPath).name, originalPath).copy(
                originalPath = originalPath,
                trashPath = trashCopy.absolutePath,
                viaRoot = viaRoot,
            ),
        )
    }

    private fun baseEntry(type: RecoveryType, title: String, subtitle: String): RecoveryEntry = RecoveryEntry(
        id = UUID.randomUUID().toString(),
        type = type,
        title = title,
        subtitle = subtitle,
        deletedAt = System.currentTimeMillis(),
        source = RecoverySource.RECYCLE_BIN,
    )

    private fun copyToTrash(file: File): File? {
        if (file.length() > MAX_TRASH_BYTES || !file.canRead()) return null
        return runCatching {
            val target = journal.newTrashFile()
            file.copyTo(target, overwrite = true)
            target
        }.getOrNull()
    }

    private fun copyToTrashViaRoot(path: String): File? {
        val target = journal.newTrashFile()
        val ok = RootShell.readOutput(
            "cat ${RootShell.quote(path)} > ${RootShell.quote(target.absolutePath)} && " +
                "chmod 600 ${RootShell.quote(target.absolutePath)} && echo ok",
        )
        return if (ok == "ok" && target.exists() && target.length() in 1..MAX_TRASH_BYTES) {
            target
        } else {
            target.delete()
            null
        }
    }

    private fun snapshotContact(contactId: Long): JSONArray {
        val array = JSONArray()
        val cursor = context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            null,
            "${ContactsContract.Data.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null,
        ) ?: return array

        cursor.use {
            val mimeIndex = it.getColumnIndex(ContactsContract.Data.MIMETYPE)
            while (it.moveToNext()) {
                val row = JSONObject()
                row.put(ContactsContract.Data.MIMETYPE, it.getString(mimeIndex))
                for (column in 1..DATA_COLUMN_COUNT) {
                    val name = "data$column"
                    val index = it.getColumnIndex(name)
                    if (index >= 0) it.getString(index)?.let { value -> row.put(name, value) }
                }
                array.put(row)
            }
        }
        return array
    }

    private fun snapshotRow(uri: Uri, id: String?, columns: Array<String>): JSONObject? {
        val selection = id?.let { "${BaseColumns._ID} = ?" }
        val args = id?.let { arrayOf(it) }
        val cursor = context.contentResolver.query(uri, columns, selection, args, null) ?: return null
        return cursor.use {
            if (!it.moveToFirst()) return null
            val row = JSONObject()
            columns.forEach { column ->
                val index = it.getColumnIndex(column)
                if (index >= 0 && !it.isNull(index)) row.put(column, it.getString(index))
            }
            row
        }
    }

    private fun rootRemove(path: String): Boolean =
        RootShell.readOutput("rm -f ${RootShell.quote(path)} && echo ok") == "ok"

    private companion object {
        const val MAX_TRASH_BYTES = 200L * 1024 * 1024
        const val DATA_COLUMN_COUNT = 15

        val CALL_LOG_COLUMNS = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NEW,
        )

        val CALENDAR_COLUMNS = arrayOf(
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.DURATION,
        )

        val SMS_COLUMNS = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
        )
    }
}
