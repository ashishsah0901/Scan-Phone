package com.ashish.scanphone.ui

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.ashish.scanphone.model.OpenTarget
import java.io.File

object HitOpener {

    fun open(context: Context, target: OpenTarget): String? = try {
        val intent = intentFor(context, target)
        if (intent == null) {
            "Nothing available to open this result"
        } else {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        }
    } catch (_: ActivityNotFoundException) {
        "No app on this device can open that"
    } catch (error: Exception) {
        error.message ?: "Could not open that result"
    }

    private fun intentFor(context: Context, target: OpenTarget): Intent? = when (target) {
        is OpenTarget.LocalFile -> fileIntent(context, File(target.path))
        is OpenTarget.Contact -> Intent(
            Intent.ACTION_VIEW,
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, target.contactId),
        )

        is OpenTarget.MessageThread -> Intent(
            Intent.ACTION_VIEW,
            Uri.parse("content://mms-sms/conversations/${target.threadId}"),
        )

        is OpenTarget.App -> context.packageManager.getLaunchIntentForPackage(target.packageName)

        is OpenTarget.NotificationSettings -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, target.packageName)

        is OpenTarget.Dialer -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:${target.number}"))

        is OpenTarget.CalendarEvent -> Intent(
            Intent.ACTION_VIEW,
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, target.eventId),
        )
    }

    private fun fileIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeTypeOf(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun mimeTypeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "*/*"
}
