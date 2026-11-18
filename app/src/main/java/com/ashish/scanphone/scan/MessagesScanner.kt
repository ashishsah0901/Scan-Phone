package com.ashish.scanphone.scan

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.ashish.scanphone.model.MatchKind
import com.ashish.scanphone.model.OpenTarget
import com.ashish.scanphone.model.ScanEvent
import com.ashish.scanphone.model.ScanHit
import com.ashish.scanphone.model.ScanRequest
import com.ashish.scanphone.model.ScanSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

class MessagesScanner(private val context: Context) : Scanner {

    override val source: ScanSource = ScanSource.MESSAGES

    override fun scan(request: ScanRequest): Flow<ScanEvent> = flow {
        val matcher = TextMatcher(request.query, request.options.caseSensitive, request.options.queryMode)
        if (!matcher.isUsable) {
            emit(ScanEvent.Finished(source, 0))
            return@flow
        }

        var examined = 0
        try {
            examined += scanSms(matcher, examined)
            examined += scanMms(matcher, examined)
        } catch (security: SecurityException) {
            emit(ScanEvent.Failed(source, security.message ?: "SMS permission denied"))
        }

        emit(ScanEvent.Progress(source, examined, null))
        emit(ScanEvent.Finished(source, examined))
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<ScanEvent>.scanSms(matcher: TextMatcher, offset: Int): Int {
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            SMS_PROJECTION,
            null,
            null,
            "${Telephony.Sms.DATE} DESC",
        ) ?: return 0

        var examined = 0
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val threadColumn = it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressColumn = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyColumn = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateColumn = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeColumn = it.getColumnIndexOrThrow(Telephony.Sms.TYPE)

            while (it.moveToNext()) {
                coroutineContext.ensureActive()
                examined++
                if ((offset + examined) % PROGRESS_INTERVAL == 0) {
                    emit(ScanEvent.Progress(source, offset + examined, null))
                }

                val address = it.getString(addressColumn).orEmpty()
                val body = it.getString(bodyColumn).orEmpty()
                val bodyIndex = matcher.indexIn(body)
                val kind = when {
                    bodyIndex >= 0 -> MatchKind.MESSAGE_BODY
                    matcher.matches(address) || matcher.matchesNumber(address) -> MatchKind.MESSAGE_ADDRESS
                    else -> continue
                }

                val direction = if (it.getInt(typeColumn) == Telephony.Sms.MESSAGE_TYPE_INBOX) "from" else "to"
                emit(
                    ScanEvent.Found(
                        ScanHit(
                            id = "sms:${it.getLong(idColumn)}",
                            source = source,
                            matchKind = kind,
                            title = "SMS $direction ${address.ifBlank { "unknown" }}",
                            subtitle = formatDate(it.getLong(dateColumn)),
                            snippet = matcher.snippet(body, bodyIndex),
                            openTarget = OpenTarget.MessageThread(it.getLong(threadColumn)),
                            verifyRef = "sms:${it.getLong(idColumn)}",
                        ),
                    ),
                )
            }
        }
        return examined
    }

    private suspend fun FlowCollector<ScanEvent>.scanMms(matcher: TextMatcher, offset: Int): Int {
        val threads = mmsThreads()
        val cursor = context.contentResolver.query(
            MMS_PART_URI,
            MMS_PART_PROJECTION,
            "${MMS_PART_CONTENT_TYPE} = ?",
            arrayOf("text/plain"),
            null,
        ) ?: return 0

        var examined = 0
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(MMS_PART_ID)
            val messageColumn = it.getColumnIndexOrThrow(MMS_PART_MESSAGE_ID)
            val textColumn = it.getColumnIndexOrThrow(MMS_PART_TEXT)

            while (it.moveToNext()) {
                coroutineContext.ensureActive()
                examined++
                if ((offset + examined) % PROGRESS_INTERVAL == 0) {
                    emit(ScanEvent.Progress(source, offset + examined, null))
                }

                val body = it.getString(textColumn).orEmpty()
                val index = matcher.indexIn(body)
                if (index < 0) continue

                val messageId = it.getLong(messageColumn)
                val thread = threads[messageId]
                emit(
                    ScanEvent.Found(
                        ScanHit(
                            id = "mms:${it.getLong(idColumn)}",
                            source = source,
                            matchKind = MatchKind.MESSAGE_BODY,
                            title = "MMS message",
                            subtitle = thread?.let { entry -> formatDate(entry.dateSeconds * 1000) } ?: "MMS",
                            snippet = matcher.snippet(body, index),
                            openTarget = thread?.let { entry -> OpenTarget.MessageThread(entry.threadId) },
                            verifyRef = "mms:$messageId",
                        ),
                    ),
                )
            }
        }
        return examined
    }

    private fun mmsThreads(): Map<Long, MmsThread> {
        val cursor = context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE),
            null,
            null,
            null,
        ) ?: return emptyMap()

        val threads = HashMap<Long, MmsThread>()
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(Telephony.Mms._ID)
            val threadColumn = it.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
            val dateColumn = it.getColumnIndexOrThrow(Telephony.Mms.DATE)
            while (it.moveToNext()) {
                threads[it.getLong(idColumn)] = MmsThread(it.getLong(threadColumn), it.getLong(dateColumn))
            }
        }
        return threads
    }

    private fun formatDate(millis: Long): String = DATE_FORMAT.format(Date(millis))

    private data class MmsThread(val threadId: Long, val dateSeconds: Long)

    private companion object {
        const val PROGRESS_INTERVAL = 200

        const val MMS_PART_ID = "_id"
        const val MMS_PART_MESSAGE_ID = "mid"
        const val MMS_PART_CONTENT_TYPE = "ct"
        const val MMS_PART_TEXT = "text"

        val MMS_PART_URI: Uri = Uri.parse("content://mms/part")
        val MMS_PART_PROJECTION = arrayOf(MMS_PART_ID, MMS_PART_MESSAGE_ID, MMS_PART_TEXT)

        val SMS_PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
        )

        val DATE_FORMAT = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
    }
}
