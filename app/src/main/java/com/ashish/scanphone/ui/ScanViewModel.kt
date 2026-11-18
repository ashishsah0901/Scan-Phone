package com.ashish.scanphone.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ashish.scanphone.delete.DeleteSummary
import com.ashish.scanphone.delete.Deleter
import com.ashish.scanphone.export.ExportResult
import com.ashish.scanphone.export.Exporter
import com.ashish.scanphone.model.HitStatus
import com.ashish.scanphone.model.HitVerification
import com.ashish.scanphone.model.QueryMode
import com.ashish.scanphone.model.ScanEvent
import com.ashish.scanphone.model.ScanHit
import com.ashish.scanphone.model.ScanOptions
import com.ashish.scanphone.model.ScanRequest
import com.ashish.scanphone.model.ScanSource
import com.ashish.scanphone.permission.PermissionSnapshot
import com.ashish.scanphone.permission.ScanPermissions
import com.ashish.scanphone.scan.HitVerifier
import com.ashish.scanphone.scan.RootShell
import com.ashish.scanphone.scan.ScanEngine
import com.ashish.scanphone.service.ScanForegroundService
import com.ashish.scanphone.store.ResultStore
import com.ashish.scanphone.store.SavedSearch
import com.ashish.scanphone.store.SavedSearchStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ScanStatus { IDLE, RUNNING, COMPLETED, CANCELLED }

enum class RootStatus { CHECKING, AVAILABLE, UNAVAILABLE }

enum class SortMode(val label: String) {
    SOURCE("Source"),
    TITLE("Title"),
    MATCH("Match type"),
}

enum class SourceAvailability { AVAILABLE, NEEDS_PERMISSION, NEEDS_ROOT, NOT_SELECTED }

data class ScanUiState(
    val query: String = "",
    val options: ScanOptions = ScanOptions(),
    val status: ScanStatus = ScanStatus.IDLE,
    val rootStatus: RootStatus = RootStatus.CHECKING,
    val hits: List<ScanHit> = emptyList(),
    val examinedBySource: Map<ScanSource, Int> = emptyMap(),
    val finishedSources: Set<ScanSource> = emptySet(),
    val errors: Map<ScanSource, String> = emptyMap(),
    val currentBySource: Map<ScanSource, String> = emptyMap(),
    val hitLimitReached: Boolean = false,
    val permissions: PermissionSnapshot = PermissionSnapshot(emptyList()),
    val verifications: Map<String, HitVerification> = emptyMap(),
    val isVerifying: Boolean = false,
    val verifiedCount: Int = 0,
    val resultFilter: String = "",
    val hiddenSources: Set<ScanSource> = emptySet(),
    val sortMode: SortMode = SortMode.SOURCE,
    val savedSearches: List<SavedSearch> = emptyList(),
    val restoredFromCache: Boolean = false,
) {
    val isRooted: Boolean get() = rootStatus == RootStatus.AVAILABLE

    fun isSourceEnabled(source: ScanSource): Boolean =
        source != ScanSource.APP_DATA || isRooted

    val examinedTotal: Int get() = examinedBySource.values.sum()

    val countsBySource: Map<ScanSource, Int>
        get() = hits.groupingBy { it.source }.eachCount()

    val statusCounts: Map<HitStatus, Int>
        get() = verifications.values.groupingBy { it.status }.eachCount()

    val canScan: Boolean get() = query.isNotBlank() && options.sources.isNotEmpty()

    val canVerify: Boolean
        get() = hits.isNotEmpty() && status != ScanStatus.RUNNING && !isVerifying

    val displayHits: List<ScanHit>
        get() {
            val filter = resultFilter.trim()
            val filtered = hits.asSequence()
                .filter { it.source !in hiddenSources }
                .filter {
                    filter.isEmpty() ||
                        it.title.contains(filter, ignoreCase = true) ||
                        it.subtitle.contains(filter, ignoreCase = true) ||
                        it.snippet?.contains(filter, ignoreCase = true) == true
                }
            return when (sortMode) {
                SortMode.SOURCE -> filtered.sortedBy { it.source.ordinal }
                SortMode.TITLE -> filtered.sortedBy { it.title.lowercase() }
                SortMode.MATCH -> filtered.sortedBy { it.matchKind.ordinal }
            }.toList()
        }

    fun availabilityOf(source: ScanSource): SourceAvailability = when {
        source == ScanSource.APP_DATA && !isRooted -> SourceAvailability.NEEDS_ROOT
        source !in options.sources -> SourceAvailability.NOT_SELECTED
        permissions.isMissingFor(source) -> SourceAvailability.NEEDS_PERMISSION
        else -> SourceAvailability.AVAILABLE
    }
}

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = ScanEngine.create(application)

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private val verifier = HitVerifier(application)
    private val exporter = Exporter(application)
    private val deleter = Deleter(application)
    private val resultStore = ResultStore.get(application)
    private val savedSearchStore = SavedSearchStore.get(application)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var scanJob: Job? = null
    private var verifyJob: Job? = null

    init {
        refreshPermissions()
        detectRoot()
        loadSavedSearches()
        restoreLastResults()
    }

    private fun loadSavedSearches() {
        viewModelScope.launch {
            val searches = withContext(Dispatchers.IO) { savedSearchStore.list() }
            _uiState.update { it.copy(savedSearches = searches) }
        }
    }

    private fun restoreLastResults() {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { resultStore.load() } ?: return@launch
            _uiState.update { state ->
                if (state.status != ScanStatus.IDLE || state.hits.isNotEmpty()) {
                    state
                } else {
                    state.copy(
                        query = saved.query,
                        hits = saved.hits,
                        restoredFromCache = true,
                    )
                }
            }
        }
    }

    private fun detectRoot() {
        viewModelScope.launch {
            val available = withContext(Dispatchers.IO) { RootShell.isAvailable() }
            _uiState.update { state ->
                if (available) {
                    state.copy(
                        rootStatus = RootStatus.AVAILABLE,
                        options = state.options.copy(
                            sources = state.options.sources + ScanSource.APP_DATA,
                            readAppData = true,
                        ),
                    )
                } else {
                    state.copy(
                        rootStatus = RootStatus.UNAVAILABLE,
                        options = state.options.copy(
                            sources = state.options.sources - ScanSource.APP_DATA,
                            readAppData = false,
                        ),
                    )
                }
            }
            refreshPermissions()
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun onToggleSource(source: ScanSource) {
        if (!_uiState.value.isSourceEnabled(source)) return
        _uiState.update { state ->
            val sources = state.options.sources.toMutableSet()
            if (!sources.remove(source)) sources.add(source)
            state.copy(options = state.options.copy(sources = sources))
        }
        refreshPermissions()
    }

    fun onToggleFileContents(enabled: Boolean) {
        _uiState.update { it.copy(options = it.options.copy(searchFileContents = enabled)) }
    }

    fun onToggleImageOcr(enabled: Boolean) {
        _uiState.update { it.copy(options = it.options.copy(runImageOcr = enabled)) }
    }

    fun onToggleAppMetadata(enabled: Boolean) {
        _uiState.update { it.copy(options = it.options.copy(searchAppMetadata = enabled)) }
    }

    fun onToggleApkContents(enabled: Boolean) {
        _uiState.update { it.copy(options = it.options.copy(searchApkContents = enabled)) }
    }

    fun onToggleReadAppData(enabled: Boolean) {
        if (enabled && !_uiState.value.isRooted) return
        _uiState.update { it.copy(options = it.options.copy(readAppData = enabled)) }
    }

    fun onToggleMediaImageOcr(enabled: Boolean) {
        _uiState.update { it.copy(options = it.options.copy(mediaImageOcr = enabled)) }
    }

    fun onToggleMediaVideoText(enabled: Boolean) {
        _uiState.update { it.copy(options = it.options.copy(mediaVideoText = enabled)) }
    }

    fun onToggleCaseSensitive(enabled: Boolean) {
        _uiState.update { it.copy(options = it.options.copy(caseSensitive = enabled)) }
    }

    fun onQueryModeChange(mode: QueryMode) {
        _uiState.update { it.copy(options = it.options.copy(queryMode = mode)) }
    }

    fun onResultFilterChange(filter: String) {
        _uiState.update { it.copy(resultFilter = filter) }
    }

    fun onSortModeChange(mode: SortMode) {
        _uiState.update { it.copy(sortMode = mode) }
    }

    fun onToggleSourceVisibility(source: ScanSource) {
        _uiState.update { state ->
            val hidden = state.hiddenSources.toMutableSet()
            if (!hidden.remove(source)) hidden.add(source)
            state.copy(hiddenSources = hidden)
        }
    }

    fun saveCurrentSearch(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val state = _uiState.value
        val search = SavedSearch(trimmed, state.query, state.options)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { savedSearchStore.save(search) }
            val searches = withContext(Dispatchers.IO) { savedSearchStore.list() }
            _uiState.update { it.copy(savedSearches = searches) }
            _messages.tryEmit("Saved search \"$trimmed\"")
        }
    }

    fun applySavedSearch(search: SavedSearch) {
        _uiState.update { it.copy(query = search.query, options = search.options) }
        refreshPermissions()
    }

    fun deleteSavedSearch(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { savedSearchStore.delete(name) }
            val searches = withContext(Dispatchers.IO) { savedSearchStore.list() }
            _uiState.update { it.copy(savedSearches = searches) }
        }
    }

    fun exportHit(hit: ScanHit) {
        viewModelScope.launch {
            _messages.tryEmit(messageFor(exporter.exportFile(hit)))
        }
    }

    fun exportResults(asJson: Boolean) {
        val hits = _uiState.value.displayHits
        viewModelScope.launch {
            _messages.tryEmit(messageFor(exporter.exportResults(hits, asJson)))
        }
    }

    private fun messageFor(result: ExportResult): String = when (result) {
        is ExportResult.Success -> "Saved to ${result.displayName}"
        is ExportResult.Failure -> result.reason
    }

    fun canDelete(hit: ScanHit): Boolean = deleter.canDelete(hit)

    fun deletableDisplayHits(): List<ScanHit> = _uiState.value.displayHits.filter(deleter::canDelete)

    fun requiredDeletePermissions(hits: List<ScanHit>): Set<String> = deleter.requiredPermissions(hits)

    fun deleteHits(hits: List<ScanHit>) {
        if (hits.isEmpty()) return
        viewModelScope.launch {
            val summary = deleter.delete(hits)
            if (summary.deletedIds.isNotEmpty()) {
                val removed = summary.deletedIds.toSet()
                val remaining = _uiState.value.hits.filterNot { it.id in removed }
                _uiState.update { state ->
                    state.copy(
                        hits = remaining,
                        verifications = state.verifications - removed,
                    )
                }
                withContext(Dispatchers.IO) { resultStore.save(_uiState.value.query, remaining) }
            }
            _messages.tryEmit(deleteMessage(summary))
        }
    }

    private fun deleteMessage(summary: DeleteSummary): String {
        val parts = buildList {
            if (summary.deleted > 0) add("${summary.deleted} deleted")
            if (summary.failed > 0) add("${summary.failed} failed")
            if (summary.unsupported > 0) add("${summary.unsupported} not deletable")
        }
        return if (parts.isEmpty()) "Nothing to delete" else parts.joinToString(" · ")
    }

    fun refreshPermissions() {
        val state = _uiState.value
        val snapshot = ScanPermissions.snapshot(getApplication(), state.options.sources)
        _uiState.update { it.copy(permissions = snapshot) }
    }

    fun notifyUser(message: String) {
        _messages.tryEmit(message)
    }

    fun startScan() {
        val state = _uiState.value
        if (!state.canScan) return

        scanJob?.cancel()
        _uiState.update {
            it.copy(
                status = ScanStatus.RUNNING,
                hits = emptyList(),
                examinedBySource = emptyMap(),
                finishedSources = emptySet(),
                errors = emptyMap(),
                currentBySource = emptyMap(),
                hitLimitReached = false,
                verifications = emptyMap(),
                isVerifying = false,
                verifiedCount = 0,
                restoredFromCache = false,
            )
        }
        ScanForegroundService.start(getApplication(), "Scanning your device…")

        val request = ScanRequest(query = state.query.trim(), options = state.options)
        scanJob = viewModelScope.launch {
            val collected = ArrayList<ScanHit>()
            val seenIds = HashSet<String>()
            val examined = HashMap<ScanSource, Int>()
            val finished = HashSet<ScanSource>()
            val errors = HashMap<ScanSource, String>()
            val current = HashMap<ScanSource, String>()
            var limitReached = false
            var lastPublish = 0L
            var lastServiceUpdate = 0L

            fun publish(force: Boolean) {
                val now = SystemClock.uptimeMillis()
                if (!force && now - lastPublish < PUBLISH_INTERVAL_MS) return
                lastPublish = now
                _uiState.update {
                    it.copy(
                        hits = ArrayList(collected),
                        examinedBySource = HashMap(examined),
                        finishedSources = HashSet(finished),
                        errors = HashMap(errors),
                        currentBySource = HashMap(current),
                        hitLimitReached = limitReached,
                    )
                }
            }

            engine.scan(request).collect { event ->
                when (event) {
                    is ScanEvent.Found -> {
                        if (collected.size >= MAX_HITS) {
                            limitReached = true
                        } else if (seenIds.add(event.hit.id)) {
                            collected.add(event.hit)
                        }
                    }

                    is ScanEvent.Progress -> {
                        examined[event.source] = event.examined
                        event.current?.let { current[event.source] = it }
                        val now = SystemClock.uptimeMillis()
                        if (now - lastServiceUpdate >= SERVICE_UPDATE_MS) {
                            lastServiceUpdate = now
                            ScanForegroundService.start(
                                getApplication(),
                                "${examined.values.sum()} items · ${collected.size} matches",
                            )
                        }
                    }

                    is ScanEvent.Failed -> errors[event.source] = event.reason
                    is ScanEvent.Finished -> {
                        examined[event.source] = event.examined
                        finished.add(event.source)
                        current.remove(event.source)
                    }
                }
                publish(force = event is ScanEvent.Finished)
            }

            current.clear()
            publish(force = true)
            _uiState.update { it.copy(status = ScanStatus.COMPLETED) }
            ScanForegroundService.stop(getApplication())
            val finalQuery = request.query
            val finalHits = ArrayList(collected)
            withContext(Dispatchers.IO) { resultStore.save(finalQuery, finalHits) }
        }
    }

    fun verifyResults() {
        val hits = _uiState.value.hits
        if (hits.isEmpty()) return

        verifyJob?.cancel()
        _uiState.update { it.copy(isVerifying = true, verifiedCount = 0, verifications = emptyMap()) }

        verifyJob = viewModelScope.launch {
            val results = LinkedHashMap<String, HitVerification>()
            var lastPublish = 0L

            hits.forEachIndexed { index, hit ->
                results[hit.id] = verifier.verify(hit)
                val now = SystemClock.uptimeMillis()
                if (index == hits.lastIndex || now - lastPublish >= PUBLISH_INTERVAL_MS) {
                    lastPublish = now
                    _uiState.update {
                        it.copy(verifications = LinkedHashMap(results), verifiedCount = results.size)
                    }
                }
            }

            _uiState.update {
                it.copy(verifications = results, verifiedCount = results.size, isVerifying = false)
            }
        }
    }

    fun cancelVerify() {
        verifyJob?.cancel()
        verifyJob = null
        _uiState.update { it.copy(isVerifying = false) }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        ScanForegroundService.stop(getApplication())
        _uiState.update {
            if (it.status == ScanStatus.RUNNING) {
                it.copy(status = ScanStatus.CANCELLED, currentBySource = emptyMap())
            } else {
                it
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
        verifyJob?.cancel()
        ScanForegroundService.stop(getApplication())
    }

    private companion object {
        const val MAX_HITS = 5000
        const val PUBLISH_INTERVAL_MS = 150L
        const val SERVICE_UPDATE_MS = 1000L
    }
}
