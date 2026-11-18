package com.ashish.scanphone.ui

import android.Manifest
import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ashish.scanphone.delete.DeleteSummary
import com.ashish.scanphone.delete.Deleter
import com.ashish.scanphone.model.MatchKind
import com.ashish.scanphone.model.ScanHit
import com.ashish.scanphone.model.ScanSource
import com.ashish.scanphone.threat.ThreatArea
import com.ashish.scanphone.threat.ThreatCheck
import com.ashish.scanphone.threat.ThreatEngine
import com.ashish.scanphone.threat.ThreatEvent
import com.ashish.scanphone.threat.ThreatFinding
import com.ashish.scanphone.threat.ThreatScanOptions
import com.ashish.scanphone.threat.ThreatSeverity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ThreatStatus { IDLE, RUNNING, COMPLETED, CANCELLED }

data class ThreatUiState(
    val status: ThreatStatus = ThreatStatus.IDLE,
    val checks: Set<ThreatCheck> = ThreatCheck.entries.toSet(),
    val findings: List<ThreatFinding> = emptyList(),
    val examinedByArea: Map<ThreatArea, Int> = emptyMap(),
    val errors: Map<ThreatArea, String> = emptyMap(),
    val currentLocation: String? = null,
) {
    val examinedTotal: Int get() = examinedByArea.values.sum()

    val sortedFindings: List<ThreatFinding>
        get() = findings.sortedWith(
            compareByDescending<ThreatFinding> { it.severity.rank }.thenBy { it.area.ordinal },
        )

    val countsBySeverity: Map<ThreatSeverity, Int>
        get() = findings.groupingBy { it.severity }.eachCount()

    val canScan: Boolean get() = checks.isNotEmpty()
}

class ThreatViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = ThreatEngine.create(application)
    private val deleter = Deleter(application)

    private val _uiState = MutableStateFlow(ThreatUiState())
    val uiState: StateFlow<ThreatUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var scanJob: Job? = null

    fun onToggleCheck(check: ThreatCheck) {
        _uiState.update { state ->
            val checks = state.checks.toMutableSet()
            if (!checks.remove(check)) checks.add(check)
            state.copy(checks = checks)
        }
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
                status = ThreatStatus.RUNNING,
                findings = emptyList(),
                examinedByArea = emptyMap(),
                errors = emptyMap(),
                currentLocation = null,
            )
        }

        val options = ThreatScanOptions(checks = state.checks)
        scanJob = viewModelScope.launch {
            val collected = ArrayList<ThreatFinding>()
            val seen = HashSet<String>()
            val examined = HashMap<ThreatArea, Int>()
            val errors = HashMap<ThreatArea, String>()
            var location: String? = null
            var lastPublish = 0L

            fun publish(force: Boolean) {
                val now = SystemClock.uptimeMillis()
                if (!force && now - lastPublish < PUBLISH_INTERVAL_MS) return
                lastPublish = now
                _uiState.update {
                    it.copy(
                        findings = ArrayList(collected),
                        examinedByArea = HashMap(examined),
                        errors = HashMap(errors),
                        currentLocation = location,
                    )
                }
            }

            engine.scan(options).collect { event ->
                when (event) {
                    is ThreatEvent.Found -> if (seen.add(event.finding.id)) collected.add(event.finding)
                    is ThreatEvent.Progress -> {
                        examined[event.area] = event.examined
                        event.current?.let { location = it }
                    }

                    is ThreatEvent.Failed -> errors[event.area] = event.reason
                    is ThreatEvent.Finished -> examined[event.area] = event.examined
                }
                publish(force = event is ThreatEvent.Finished)
            }

            location = null
            publish(force = true)
            _uiState.update { it.copy(status = ThreatStatus.COMPLETED) }
        }
    }

    fun canDelete(finding: ThreatFinding): Boolean =
        finding.deleteRef != null && finding.area in DELETABLE_AREAS

    fun canQuarantine(finding: ThreatFinding): Boolean =
        finding.deleteRef != null && finding.area == ThreatArea.FILES

    fun requiredDeletePermission(finding: ThreatFinding): String? =
        if (finding.area == ThreatArea.CONTACTS) Manifest.permission.WRITE_CONTACTS else null

    fun deleteFinding(finding: ThreatFinding) {
        val hit = hitFor(finding) ?: return
        viewModelScope.launch {
            val summary = deleter.delete(listOf(hit))
            finishAction(finding, summary, "Deleted", "Delete")
        }
    }

    fun quarantineFinding(finding: ThreatFinding) {
        val hit = hitFor(finding) ?: return
        viewModelScope.launch {
            val summary = deleter.quarantine(listOf(hit))
            finishAction(finding, summary, "Quarantined", "Quarantine")
        }
    }

    private fun finishAction(finding: ThreatFinding, summary: DeleteSummary, past: String, verb: String) {
        if (summary.deleted > 0) {
            _uiState.update { state -> state.copy(findings = state.findings.filterNot { it.id == finding.id }) }
            _messages.tryEmit("$past \"${finding.title}\"")
        } else {
            _messages.tryEmit("$verb failed for \"${finding.title}\"")
        }
    }

    private fun hitFor(finding: ThreatFinding): ScanHit? {
        val ref = finding.deleteRef ?: return null
        val source = when (finding.area) {
            ThreatArea.FILES -> ScanSource.FILES
            ThreatArea.MESSAGES -> ScanSource.MESSAGES
            ThreatArea.CONTACTS -> ScanSource.CONTACTS
            else -> return null
        }
        return ScanHit(
            id = finding.id,
            source = source,
            matchKind = MatchKind.FILE_NAME,
            title = finding.title,
            subtitle = finding.subtitle,
            snippet = null,
            openTarget = finding.openTarget,
            verifyRef = ref,
            exportPath = if (source == ScanSource.FILES) ref else null,
        )
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _uiState.update {
            if (it.status == ThreatStatus.RUNNING) it.copy(status = ThreatStatus.CANCELLED, currentLocation = null) else it
        }
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
    }

    private companion object {
        const val PUBLISH_INTERVAL_MS = 150L
        val DELETABLE_AREAS = setOf(ThreatArea.FILES, ThreatArea.MESSAGES, ThreatArea.CONTACTS)
    }
}
