package com.ashish.scanphone.scan

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.text.format.DateUtils
import android.text.format.Formatter
import com.ashish.scanphone.model.HitStatus
import com.ashish.scanphone.model.HitVerification
import com.ashish.scanphone.model.ScanHit
import com.ashish.scanphone.model.ScanSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class HitVerifier(private val context: Context) {

    suspend fun verify(hit: ScanHit): HitVerification = withContext(Dispatchers.IO) {
        val reference = hit.verifyRef
            ?: return@withContext HitVerification(HitStatus.UNVERIFIABLE, "nothing to re-check")

        try {
            when (hit.source) {
                ScanSource.FILES -> verifyFile(hit, reference)
                ScanSource.MEDIA -> verifyFile(hit, reference)
                ScanSource.CONTACTS -> verifyContact(hit, reference)
                ScanSource.MESSAGES -> verifyMessage(reference)
                ScanSource.CALL_LOG ->
                    HitVerification(HitStatus.UNCHANGED, "call log entry")
                ScanSource.CALENDAR ->
                    HitVerification(HitStatus.UNCHANGED, "calendar event")
                ScanSource.APPS -> verifyApp(hit, reference)
                ScanSource.APP_USAGE -> verifyApp(hit, reference)
                ScanSource.NOTIFICATIONS ->
                    HitVerification(HitStatus.UNCHANGED, "kept in the captured log")
                ScanSource.APP_DATA -> verifyRootPath(hit, reference)
            }
        } catch (security: SecurityException) {
            HitVerification(HitStatus.UNVERIFIABLE, security.message ?: "permission revoked")
        }
    }

    private fun verifyFile(hit: ScanHit, path: String): HitVerification {
        val file = File(path)
        if (!file.exists()) return HitVerification(HitStatus.GONE, "file no longer exists")

        val current = FileFingerprint.of(file)
        val size = Formatter.formatShortFileSize(context, file.length())
        return if (hit.fingerprint == null || hit.fingerprint == current) {
            HitVerification(HitStatus.UNCHANGED, size)
        } else {
            HitVerification(HitStatus.CHANGED, "$size · modified ${relativeTime(file.lastModified())}")
        }
    }

    private fun verifyContact(hit: ScanHit, contactId: String): HitVerification {
        val id = contactId.toLongOrNull()
            ?: return HitVerification(HitStatus.UNVERIFIABLE, "unknown contact reference")

        val cursor = context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.DISPLAY_NAME_PRIMARY, ContactsContract.Data.DATA1),
            "${ContactsContract.Data.CONTACT_ID} = ?",
            arrayOf(contactId),
            null,
        ) ?: return HitVerification(HitStatus.UNVERIFIABLE, "contacts provider unavailable")

        val fingerprints = HashSet<String>()
        var name: String? = null
        cursor.use {
            while (it.moveToNext()) {
                val displayName = it.getString(0).orEmpty()
                name = displayName
                fingerprints += "$displayName|${it.getString(1).orEmpty()}"
                fingerprints += "$displayName|contact"
            }
        }

        if (fingerprints.isEmpty()) {
            return HitVerification(HitStatus.GONE, "contact deleted")
        }
        if (hit.fingerprint == null || hit.fingerprint in fingerprints) {
            return HitVerification(HitStatus.UNCHANGED, contactUri(id))
        }
        return HitVerification(HitStatus.CHANGED, "now saved as ${name.orEmpty()}")
    }

    private fun verifyMessage(reference: String): HitVerification {
        val id = reference.substringAfter(':').toLongOrNull()
            ?: return HitVerification(HitStatus.UNVERIFIABLE, "unknown message reference")

        val uri = if (reference.startsWith("mms:")) {
            ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id)
        } else {
            ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id)
        }

        val cursor = context.contentResolver.query(uri, arrayOf(Telephony.Sms._ID), null, null, null)
            ?: return HitVerification(HitStatus.UNVERIFIABLE, "messages provider unavailable")

        return cursor.use {
            if (it.moveToFirst()) {
                HitVerification(HitStatus.UNCHANGED, "still in the message store")
            } else {
                HitVerification(HitStatus.GONE, "message deleted")
            }
        }
    }

    private fun verifyApp(hit: ScanHit, packageName: String): HitVerification {
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(packageName, 0)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return HitVerification(HitStatus.GONE, "app uninstalled")
        }

        val archive = info.sourceDir?.let(::File)
        if (archive == null || !archive.exists()) {
            return HitVerification(HitStatus.GONE, "app no longer installed")
        }

        val current = FileFingerprint.of(archive)
        return if (hit.fingerprint == null || hit.fingerprint == current) {
            HitVerification(HitStatus.UNCHANGED, "still installed")
        } else {
            HitVerification(HitStatus.CHANGED, "updated ${relativeTime(archive.lastModified())}")
        }
    }

    private fun verifyRootPath(hit: ScanHit, path: String): HitVerification {
        if (!RootShell.isAvailable()) {
            return HitVerification(HitStatus.UNVERIFIABLE, "needs root access")
        }

        val output = RootShell.readOutput("stat -c %s:%Y ${RootShell.quote(path)} 2>/dev/null")
        if (output.isNullOrBlank()) {
            return HitVerification(HitStatus.GONE, "file no longer exists")
        }

        val size = output.substringBefore(':').toLongOrNull()
        val modified = output.substringAfter(':').toLongOrNull()
        if (size == null || modified == null) {
            return HitVerification(HitStatus.UNVERIFIABLE, "could not stat the file")
        }

        val current = "$size:${modified * MILLIS_PER_SECOND}"
        return if (hit.fingerprint == null || hit.fingerprint == current) {
            HitVerification(HitStatus.UNCHANGED, Formatter.formatShortFileSize(context, size))
        } else {
            HitVerification(
                HitStatus.CHANGED,
                "${Formatter.formatShortFileSize(context, size)} · modified " +
                    relativeTime(modified * MILLIS_PER_SECOND),
            )
        }
    }

    private fun contactUri(id: Long): String = "contact #$id"

    private fun relativeTime(millis: Long): String =
        DateUtils.getRelativeTimeSpanString(
            millis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
