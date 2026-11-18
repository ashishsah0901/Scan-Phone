package com.ashish.scanphone.scan

import android.content.Context
import android.provider.CalendarContract
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

class CalendarScanner(private val context: Context) : Scanner {

    override val source: ScanSource = ScanSource.CALENDAR

    override fun scan(request: ScanRequest): Flow<ScanEvent> = flow {
        val matcher = TextMatcher(request.query, request.options.caseSensitive, request.options.queryMode)
        if (!matcher.isUsable) {
            emit(ScanEvent.Finished(source, 0))
            return@flow
        }

        val cursor = try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                PROJECTION,
                null,
                null,
                "${CalendarContract.Events.DTSTART} DESC",
            )
        } catch (security: SecurityException) {
            emit(ScanEvent.Failed(source, security.message ?: "Calendar permission denied"))
            emit(ScanEvent.Finished(source, 0))
            return@flow
        }

        if (cursor == null) {
            emit(ScanEvent.Failed(source, "Calendar provider unavailable"))
            emit(ScanEvent.Finished(source, 0))
            return@flow
        }

        var examined = 0
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(CalendarContract.Events._ID)
            val titleColumn = it.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
            val descColumn = it.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION)
            val locationColumn = it.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)
            val startColumn = it.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)

            while (it.moveToNext()) {
                coroutineContext.ensureActive()
                examined++
                if (examined % PROGRESS_INTERVAL == 0) {
                    emit(ScanEvent.Progress(source, examined, null))
                }

                val title = it.getString(titleColumn).orEmpty()
                val description = it.getString(descColumn).orEmpty()
                val location = it.getString(locationColumn).orEmpty()

                val titleIndex = matcher.indexIn(title)
                val descIndex = matcher.indexIn(description)
                val kind = when {
                    titleIndex >= 0 -> MatchKind.CALENDAR_TITLE
                    descIndex >= 0 || matcher.matches(location) -> MatchKind.CALENDAR_DETAIL
                    else -> continue
                }

                val snippet = when {
                    descIndex >= 0 -> matcher.snippet(description, descIndex)
                    matcher.matches(location) -> "at $location"
                    else -> null
                }

                val start = it.getLong(startColumn)
                emit(
                    ScanEvent.Found(
                        ScanHit(
                            id = "calendar:${it.getLong(idColumn)}",
                            source = source,
                            matchKind = kind,
                            title = title.ifBlank { "(untitled event)" },
                            subtitle = if (start > 0) DATE_FORMAT.format(Date(start)) else "no date",
                            snippet = snippet,
                            openTarget = OpenTarget.CalendarEvent(it.getLong(idColumn)),
                            verifyRef = it.getLong(idColumn).toString(),
                        ),
                    ),
                )
            }
        }

        emit(ScanEvent.Progress(source, examined, null))
        emit(ScanEvent.Finished(source, examined))
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val PROGRESS_INTERVAL = 100
        val DATE_FORMAT = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
        val PROJECTION = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
        )
    }
}
