package com.ashish.scanphone.scan

import android.content.Context
import android.provider.CallLog
import com.ashish.scanphone.model.MatchKind
import com.ashish.scanphone.model.OpenTarget
import com.ashish.scanphone.model.ScanEvent
import com.ashish.scanphone.model.ScanHit
import com.ashish.scanphone.model.ScanRequest
import com.ashish.scanphone.model.ScanSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

class CallLogScanner(private val context: Context) : Scanner {

    override val source: ScanSource = ScanSource.CALL_LOG

    override fun scan(request: ScanRequest): Flow<ScanEvent> = flow {
        val matcher = TextMatcher(request.query, request.options.caseSensitive, request.options.queryMode)
        if (!matcher.isUsable) {
            emit(ScanEvent.Finished(source, 0))
            return@flow
        }

        val cursor = try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                PROJECTION,
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )
        } catch (security: SecurityException) {
            emit(ScanEvent.Failed(source, security.message ?: "Call log permission denied"))
            emit(ScanEvent.Finished(source, 0))
            return@flow
        }

        if (cursor == null) {
            emit(ScanEvent.Failed(source, "Call log provider unavailable"))
            emit(ScanEvent.Finished(source, 0))
            return@flow
        }

        var examined = 0
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(CallLog.Calls._ID)
            val numberColumn = it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val nameColumn = it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val dateColumn = it.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationColumn = it.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val typeColumn = it.getColumnIndexOrThrow(CallLog.Calls.TYPE)

            while (it.moveToNext()) {
                coroutineContext.ensureActive()
                examined++
                if (examined % PROGRESS_INTERVAL == 0) {
                    emit(ScanEvent.Progress(source, examined, null))
                }

                val number = it.getString(numberColumn).orEmpty()
                val name = it.getString(nameColumn).orEmpty()
                val kind = when {
                    matcher.matches(name) -> MatchKind.CALL_NAME
                    matcher.matches(number) || matcher.matchesNumber(number) -> MatchKind.CALL_NUMBER
                    else -> continue
                }

                emit(
                    ScanEvent.Found(
                        ScanHit(
                            id = "call:${it.getLong(dateColumn)}:$number",
                            source = source,
                            matchKind = kind,
                            title = name.ifBlank { number.ifBlank { "Unknown" } },
                            subtitle = "${typeLabel(it.getInt(typeColumn))} · " +
                                DATE_FORMAT.format(Date(it.getLong(dateColumn))),
                            snippet = "$number · ${it.getLong(durationColumn)}s",
                            openTarget = if (number.isNotBlank()) OpenTarget.Dialer(number) else null,
                            verifyRef = it.getLong(idColumn).toString(),
                        ),
                    ),
                )
            }
        }

        emit(ScanEvent.Progress(source, examined, null))
        emit(ScanEvent.Finished(source, examined))
    }.flowOn(Dispatchers.IO)

    private fun typeLabel(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        else -> "call"
    }

    private companion object {
        const val PROGRESS_INTERVAL = 100
        val DATE_FORMAT = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
        val PROJECTION = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
        )
    }
}
