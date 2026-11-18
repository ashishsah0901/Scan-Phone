package com.ashish.scanphone.threat

import android.content.Context
import com.ashish.scanphone.threat.source.AppThreatSource
import com.ashish.scanphone.threat.source.FileThreatSource
import com.ashish.scanphone.threat.source.NumberThreatSource
import com.ashish.scanphone.threat.source.TextThreatSource
import com.ashish.scanphone.threat.source.ThreatSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge

class ThreatEngine(private val sources: List<ThreatSource>) {

    fun scan(options: ThreatScanOptions): Flow<ThreatEvent> {
        val active = sources.filter { it.check in options.checks }
        if (active.isEmpty()) return emptyFlow()
        return active
            .map { source ->
                source.scan(options)
                    .catch { error -> emit(ThreatEvent.Failed(source.check.toArea(), error.message ?: "failed")) }
                    .buffer(BUFFER_CAPACITY)
            }
            .merge()
    }

    private fun ThreatCheck.toArea(): ThreatArea = when (this) {
        ThreatCheck.TEXT -> ThreatArea.MESSAGES
        ThreatCheck.NUMBERS -> ThreatArea.CALLS
        ThreatCheck.APPS -> ThreatArea.APPS
        ThreatCheck.FILES -> ThreatArea.FILES
    }

    companion object {
        private const val BUFFER_CAPACITY = 64

        fun create(context: Context): ThreatEngine = ThreatEngine(
            listOf(
                TextThreatSource(context),
                NumberThreatSource(context),
                AppThreatSource(context),
                FileThreatSource(context),
            ),
        )
    }
}
