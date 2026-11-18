package com.ashish.scanphone.scan

import android.content.Context
import com.ashish.scanphone.model.MatchKind
import com.ashish.scanphone.model.OpenTarget
import com.ashish.scanphone.model.ScanEvent
import com.ashish.scanphone.model.ScanHit
import com.ashish.scanphone.model.ScanRequest
import com.ashish.scanphone.model.ScanSource
import com.ashish.scanphone.notifications.NotificationRecord
import com.ashish.scanphone.notifications.NotificationStore
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

class NotificationsScanner(private val context: Context) : Scanner {

    override val source: ScanSource = ScanSource.NOTIFICATIONS

    override fun scan(request: ScanRequest): Flow<ScanEvent> = flow {
        val matcher = TextMatcher(request.query, request.options.caseSensitive, request.options.queryMode)
        if (!matcher.isUsable) {
            emit(ScanEvent.Finished(source, 0))
            return@flow
        }

        val records = NotificationStore.get(context).snapshot()
        var examined = 0
        records.asReversed().forEach { record ->
            coroutineContext.ensureActive()
            examined++
            if (examined % PROGRESS_INTERVAL == 0) {
                emit(ScanEvent.Progress(source, examined, record.appLabel))
            }
            hitFor(record, matcher)?.let { emit(ScanEvent.Found(it)) }
        }

        val historyCount = scanRootHistory(matcher, examined)
        examined += historyCount

        if (records.isEmpty() && historyCount == 0) {
            emit(
                ScanEvent.Failed(
                    source,
                    "No notifications captured yet — grant notification access, then let some arrive",
                ),
            )
        }

        emit(ScanEvent.Progress(source, examined, null))
        emit(ScanEvent.Finished(source, examined))
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<ScanEvent>.scanRootHistory(matcher: TextMatcher, offset: Int): Int {
        if (!RootShell.isAvailable()) return 0
        val dump = RootShell.readOutput("dumpsys notification --noredact 2>/dev/null") ?: return 0

        var examined = 0
        var found = 0
        val seen = HashSet<String>()
        HISTORY_LINE.findAll(dump).forEach { match ->
            if (found >= MAX_HISTORY_HITS) return@forEach
            val pkg = match.groupValues[1]
            val text = sanitize(match.groupValues[2])
            examined++
            val index = matcher.indexIn(text)
            if (index < 0 && !matcher.matches(pkg)) return@forEach
            if (!seen.add("$pkg:$text")) return@forEach

            found++
            emit(
                ScanEvent.Found(
                    ScanHit(
                        id = "notification:history:${offset + examined}",
                        source = source,
                        matchKind = MatchKind.NOTIFICATION_HISTORY,
                        title = "$pkg (history)",
                        subtitle = "from system notification log (root)",
                        snippet = if (index >= 0) matcher.snippet(text, index) else text.take(SNIPPET_CAP),
                        openTarget = OpenTarget.NotificationSettings(pkg),
                    ),
                ),
            )
        }
        return examined
    }

    private fun sanitize(value: String): String =
        value.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()

    private fun hitFor(record: NotificationRecord, matcher: TextMatcher): ScanHit? {
        val titleIndex = matcher.indexIn(record.title)
        val textIndex = matcher.indexIn(record.text)
        val kind = when {
            textIndex >= 0 -> MatchKind.NOTIFICATION_TEXT
            titleIndex >= 0 -> MatchKind.NOTIFICATION_TITLE
            matcher.matches(record.appLabel) || matcher.matches(record.packageName) ->
                MatchKind.NOTIFICATION_APP
            else -> return null
        }

        val body = when (kind) {
            MatchKind.NOTIFICATION_TEXT -> matcher.snippet(record.text, textIndex)
            MatchKind.NOTIFICATION_TITLE -> matcher.snippet(record.title, titleIndex.coerceAtLeast(0))
            else -> listOf(record.title, record.text).filter { it.isNotBlank() }.joinToString(" — ")
        }

        val heading = record.title.ifBlank { record.appLabel }
        return ScanHit(
            id = "notification:${record.key}:${record.postedAt}:${kind.name}",
            source = source,
            matchKind = kind,
            title = "${record.appLabel}: $heading",
            subtitle = DATE_FORMAT.format(Date(record.postedAt)),
            snippet = body.ifBlank { null },
            openTarget = OpenTarget.NotificationSettings(record.packageName),
        )
    }

    private companion object {
        const val PROGRESS_INTERVAL = 100
        const val MAX_HISTORY_HITS = 500
        const val SNIPPET_CAP = 96
        val HISTORY_LINE = Regex("pkg=(\\S+).*?(?:android\\.text|tickerText)=([^\\n]*)")
        val DATE_FORMAT = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
    }
}
