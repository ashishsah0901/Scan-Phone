package com.ashish.scanphone.scan

import android.content.Context
import com.ashish.scanphone.model.ScanEvent
import com.ashish.scanphone.model.ScanRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge

class ScanEngine(private val scanners: List<Scanner>) {

    fun scan(request: ScanRequest): Flow<ScanEvent> {
        if (request.query.isBlank()) return emptyFlow()
        val active = scanners.filter { it.source in request.options.sources }
        if (active.isEmpty()) return emptyFlow()

        return active
            .map { scanner ->
                scanner.scan(request)
                    .catch { error ->
                        emitAll(
                            flowOf(
                                ScanEvent.Failed(scanner.source, error.message ?: error::class.java.simpleName),
                                ScanEvent.Finished(scanner.source, 0),
                            ),
                        )
                    }
                    .buffer(BUFFER_CAPACITY)
            }
            .merge()
    }

    companion object {
        private const val BUFFER_CAPACITY = 64

        fun create(context: Context): ScanEngine = ScanEngine(
            listOf(
                FileScanner(context),
                MediaScanner(context),
                ContactsScanner(context),
                MessagesScanner(context),
                CallLogScanner(context),
                CalendarScanner(context),
                AppsScanner(context),
                NotificationsScanner(context),
                UsageScanner(context),
                RootAppDataScanner(context.cacheDir),
            ),
        )
    }
}
