package com.ashish.scanphone.ui

import android.Manifest
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ashish.scanphone.recover.CarveController
import com.ashish.scanphone.recover.RecoveryEntry
import com.ashish.scanphone.recover.RecoveryManager
import com.ashish.scanphone.recover.RecoveryType
import com.ashish.scanphone.recover.RestoreResult
import com.ashish.scanphone.scan.RootShell
import com.ashish.scanphone.service.CarveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RecoverUiState(
    val recycleBin: List<RecoveryEntry> = emptyList(),
    val deepResults: List<RecoveryEntry> = emptyList(),
    val isDeepScanning: Boolean = false,
    val carveRunning: Boolean = false,
    val carveBytesScanned: Long = 0,
    val carveFilesCarved: Int = 0,
    val carveMessage: String? = null,
    val hasRoot: Boolean = false,
    val deepScanRun: Boolean = false,
) {
    val isBusy: Boolean get() = isDeepScanning || carveRunning
}

class RecoverViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = RecoveryManager(application)

    private val _uiState = MutableStateFlow(RecoverUiState())
    val uiState: StateFlow<RecoverUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        refresh()
        viewModelScope.launch {
            val root = withContext(Dispatchers.IO) { RootShell.isAvailable() }
            _uiState.update { it.copy(hasRoot = root) }
        }
        observeCarve()
    }

    private fun observeCarve() {
        viewModelScope.launch {
            var wasRunning = false
            CarveController.status.collect { status ->
                _uiState.update {
                    it.copy(
                        carveRunning = status.running,
                        carveBytesScanned = status.bytesScanned,
                        carveFilesCarved = status.filesCarved,
                        carveMessage = status.message,
                    )
                }
                if (status.running || wasRunning != status.running) {
                    loadCarved()
                }
                if (wasRunning && !status.running) {
                    status.message?.let { _messages.tryEmit(it) }
                }
                wasRunning = status.running
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { manager.entries() }
            _uiState.update { it.copy(recycleBin = entries) }
            loadCarved()
        }
    }

    private fun loadCarved() {
        viewModelScope.launch {
            val carved = withContext(Dispatchers.IO) { manager.carvedEntries() }
            _uiState.update { state ->
                val discovered = state.deepResults.filter { it.id.startsWith("deep:") }
                state.copy(deepResults = discovered + carved)
            }
        }
    }

    fun requiredRestorePermission(entry: RecoveryEntry): String? = when (entry.type) {
        RecoveryType.CONTACT -> Manifest.permission.WRITE_CONTACTS
        RecoveryType.CALENDAR -> Manifest.permission.WRITE_CALENDAR
        RecoveryType.CALL_LOG -> Manifest.permission.WRITE_CALL_LOG
        else -> null
    }

    fun restore(entry: RecoveryEntry) {
        viewModelScope.launch {
            val result = manager.restore(entry)
            when (result) {
                is RestoreResult.Success -> {
                    if (entry.type == RecoveryType.CARVED) {
                        withContext(Dispatchers.IO) { manager.forgetCarved(entry.id) }
                    }
                    _messages.tryEmit(result.detail)
                    _uiState.update { state ->
                        state.copy(
                            recycleBin = state.recycleBin.filterNot { it.id == entry.id },
                            deepResults = state.deepResults.filterNot { it.id == entry.id },
                        )
                    }
                }

                is RestoreResult.Failure -> _messages.tryEmit(result.reason)
            }
        }
    }

    fun deepScan() {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(isDeepScanning = true) }
        viewModelScope.launch {
            val results = manager.deepScan()
            _uiState.update { state ->
                state.copy(
                    deepResults = merge(state.deepResults, results),
                    isDeepScanning = false,
                    deepScanRun = true,
                )
            }
            _messages.tryEmit(
                if (results.isEmpty()) "Deep scan found nothing recoverable" else "Deep scan found ${results.size} files",
            )
        }
    }

    fun startFullCarve() {
        if (_uiState.value.carveRunning) return
        CarveService.start(getApplication())
        _uiState.update { it.copy(deepScanRun = true) }
    }

    fun stopFullCarve() {
        CarveService.stop(getApplication())
    }

    fun clearCarved() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { manager.clearCarved() }
            _uiState.update { state ->
                state.copy(deepResults = state.deepResults.filterNot { it.type == RecoveryType.CARVED })
            }
        }
    }

    private fun merge(existing: List<RecoveryEntry>, added: List<RecoveryEntry>): List<RecoveryEntry> {
        val ids = existing.map { it.id }.toHashSet()
        return existing + added.filter { it.id !in ids }
    }
}
