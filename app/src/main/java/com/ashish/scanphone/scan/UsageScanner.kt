package com.ashish.scanphone.scan

import android.app.usage.UsageStatsManager
import android.content.Context
import android.text.format.DateUtils
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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class UsageScanner(private val context: Context) : Scanner {

    override val source: ScanSource = ScanSource.APP_USAGE

    override fun scan(request: ScanRequest): Flow<ScanEvent> = flow {
        val matcher = TextMatcher(request.query, request.options.caseSensitive, request.options.queryMode)
        if (!matcher.isUsable) {
            emit(ScanEvent.Finished(source, 0))
            return@flow
        }

        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (manager == null) {
            emit(ScanEvent.Failed(source, "Usage stats service unavailable"))
            emit(ScanEvent.Finished(source, 0))
            return@flow
        }

        val now = System.currentTimeMillis()
        val stats = manager.queryUsageStats(
            UsageStatsManager.INTERVAL_MONTHLY,
            now - TimeUnit.DAYS.toMillis(WINDOW_DAYS),
            now,
        )

        if (stats.isNullOrEmpty()) {
            emit(
                ScanEvent.Failed(
                    source,
                    "No usage data — grant Usage access to search app activity",
                ),
            )
            emit(ScanEvent.Finished(source, 0))
            return@flow
        }

        val packageManager = context.packageManager
        val strongest = stats
            .groupBy { it.packageName }
            .mapValues { (_, entries) -> entries.maxBy { it.lastTimeUsed } }

        var examined = 0
        strongest.values.forEach { usage ->
            coroutineContext.ensureActive()
            examined++
            if (examined % PROGRESS_INTERVAL == 0) {
                emit(ScanEvent.Progress(source, examined, usage.packageName))
            }

            val label = labelOf(packageManager, usage.packageName)
            if (!matcher.matches(label) && !matcher.matches(usage.packageName)) return@forEach

            val lastUsed = if (usage.lastTimeUsed > 0) {
                DateUtils.getRelativeTimeSpanString(
                    usage.lastTimeUsed,
                    now,
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString()
            } else {
                "never"
            }
            val foreground = DateUtils.formatElapsedTime(
                TimeUnit.MILLISECONDS.toSeconds(usage.totalTimeInForeground),
            )

            emit(
                ScanEvent.Found(
                    ScanHit(
                        id = "usage:${usage.packageName}",
                        source = source,
                        matchKind = MatchKind.USAGE_APP,
                        title = label,
                        subtitle = usage.packageName,
                        snippet = "last used $lastUsed · ${foreground} in foreground (30d)",
                        openTarget = OpenTarget.App(usage.packageName),
                        verifyRef = usage.packageName,
                    ),
                ),
            )
        }

        emit(ScanEvent.Progress(source, examined, null))
        emit(ScanEvent.Finished(source, examined))
    }.flowOn(Dispatchers.IO)

    private fun labelOf(packageManager: android.content.pm.PackageManager, packageName: String): String =
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)

    private companion object {
        const val PROGRESS_INTERVAL = 20
        const val WINDOW_DAYS = 30L
    }
}
