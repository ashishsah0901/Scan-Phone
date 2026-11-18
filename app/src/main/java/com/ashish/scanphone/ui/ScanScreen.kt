package com.ashish.scanphone.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ashish.scanphone.model.HitStatus
import com.ashish.scanphone.model.HitVerification
import com.ashish.scanphone.model.QueryMode
import com.ashish.scanphone.model.ScanHit
import com.ashish.scanphone.model.ScanOptions
import com.ashish.scanphone.model.ScanSource
import com.ashish.scanphone.permission.PermissionRequirement
import com.ashish.scanphone.permission.ScanPermissions
import com.ashish.scanphone.store.SavedSearch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(viewModel: ScanViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showCapabilities by remember { mutableStateOf(false) }
    var showSavedSearches by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<List<ScanHit>?>(null) }
    var pendingDeleteAfterPerms by remember { mutableStateOf<List<ScanHit>?>(null) }
    var permissionFlowMode by rememberSaveable { mutableStateOf<PermissionFlowMode?>(null) }

    val writePermsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        pendingDeleteAfterPerms?.let { viewModel.deleteHits(it) }
        pendingDeleteAfterPerms = null
    }

    LaunchedEffect(Unit) {
        if (PermissionFlowPrefs.shouldAutoShow(context)) {
            PermissionFlowPrefs.markShown(context)
            if (ScanPermissions.flowRequirements(context, state.options.sources).isNotEmpty()) {
                permissionFlowMode = PermissionFlowMode.SETUP
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissions()
        onPauseOrDispose { }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Scan Phone") },
                actions = {
                    OverflowMenu(
                        hasResults = state.hits.isNotEmpty(),
                        onCapabilities = { showCapabilities = true },
                        onSavedSearches = { showSavedSearches = true },
                        onSaveSearch = { showSaveDialog = true },
                        onExportCsv = { viewModel.exportResults(asJson = false) },
                        onExportJson = { viewModel.exportResults(asJson = true) },
                        onDeleteAll = {
                            val deletable = viewModel.deletableDisplayHits()
                            if (deletable.isEmpty()) {
                                viewModel.notifyUser("No deletable results")
                            } else {
                                deleteCandidate = deletable
                            }
                        },
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val displayHits = state.displayHits
        val grouped = remember(displayHits) { displayHits.groupBy { it.source } }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            item(key = "header") {
                Column {
                    if (state.status == ScanStatus.RUNNING) {
                        ScanProgressPanel(state = state, onStop = viewModel::cancelScan)
                        if (state.hits.isEmpty()) {
                            Text(
                                text = "Nothing found yet",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        QueryBar(
                            state = state,
                            onQueryChange = viewModel::onQueryChange,
                            onScan = {
                                keyboard?.hide()
                                if (state.permissions.missing.isEmpty()) {
                                    viewModel.startScan()
                                } else {
                                    permissionFlowMode = PermissionFlowMode.BEFORE_SCAN
                                }
                            },
                            onCancelScan = viewModel::cancelScan,
                            onVerify = {
                                keyboard?.hide()
                                viewModel.verifyResults()
                            },
                            onCancelVerify = viewModel::cancelVerify,
                        )

                        QueryModeChips(state.options.queryMode, viewModel::onQueryModeChange)

                        RootBanner(state.rootStatus)

                        SourceChips(
                            selected = state.options.sources,
                            blocked = state.permissions.missing.mapNotNull { it.source }.toSet(),
                            isEnabled = state::isSourceEnabled,
                            onToggle = viewModel::onToggleSource,
                        )

                        ScanOptionsPanel(
                            options = state.options,
                            rooted = state.isRooted,
                            onFileContentsChange = viewModel::onToggleFileContents,
                            onImageOcrChange = viewModel::onToggleImageOcr,
                            onAppMetadataChange = viewModel::onToggleAppMetadata,
                            onApkContentsChange = viewModel::onToggleApkContents,
                            onReadAppDataChange = viewModel::onToggleReadAppData,
                            onMediaImageOcrChange = viewModel::onToggleMediaImageOcr,
                            onMediaVideoTextChange = viewModel::onToggleMediaVideoText,
                            onCaseSensitiveChange = viewModel::onToggleCaseSensitive,
                        )

                        if (state.permissions.missing.isNotEmpty()) {
                            PermissionsSummaryCard(
                                missing = state.permissions.missing,
                                onSetUp = { permissionFlowMode = PermissionFlowMode.SETUP },
                            )
                        }

                        ScanStatusPanel(state)
                    }
                }
            }

            if (state.hits.isNotEmpty()) {
                item(key = "controls") {
                    ResultControls(
                        filter = state.resultFilter,
                        sortMode = state.sortMode,
                        onFilterChange = viewModel::onResultFilterChange,
                        onSortChange = viewModel::onSortModeChange,
                    )
                }
            }

            item(key = "divider") { HorizontalDivider() }

            resultItems(
                grouped = grouped,
                query = state.query,
                verifications = state.verifications,
                canDelete = viewModel::canDelete,
                onOpen = { hit ->
                    val target = hit.openTarget
                    val message = if (target == null) {
                        "Nothing available to open this result"
                    } else {
                        HitOpener.open(context, target)
                    }
                    if (message != null) viewModel.notifyUser(message)
                },
                onExport = viewModel::exportHit,
                onDelete = { deleteCandidate = listOf(it) },
            )
        }
    }

    if (showCapabilities) {
        CapabilitiesDialog(state = state, onDismiss = { showCapabilities = false })
    }

    permissionFlowMode?.let { mode ->
        PermissionFlowDialog(
            sources = state.options.sources,
            finishLabel = if (mode == PermissionFlowMode.BEFORE_SCAN) "Start search" else "Done",
            onFinished = {
                permissionFlowMode = null
                viewModel.refreshPermissions()
                if (mode == PermissionFlowMode.BEFORE_SCAN) viewModel.startScan()
            },
            onDismiss = {
                permissionFlowMode = null
                viewModel.refreshPermissions()
            },
        )
    }

    if (showSavedSearches) {
        SavedSearchesDialog(
            searches = state.savedSearches,
            onApply = {
                viewModel.applySavedSearch(it)
                showSavedSearches = false
            },
            onDelete = viewModel::deleteSavedSearch,
            onDismiss = { showSavedSearches = false },
        )
    }

    if (showSaveDialog) {
        SaveSearchDialog(
            defaultName = state.query,
            onConfirm = {
                viewModel.saveCurrentSearch(it)
                showSaveDialog = false
            },
            onDismiss = { showSaveDialog = false },
        )
    }

    deleteCandidate?.let { candidate ->
        DeleteConfirmDialog(
            count = candidate.size,
            onConfirm = {
                deleteCandidate = null
                val perms = viewModel.requiredDeletePermissions(candidate)
                if (perms.isEmpty()) {
                    viewModel.deleteHits(candidate)
                } else {
                    pendingDeleteAfterPerms = candidate
                    writePermsLauncher.launch(perms.toTypedArray())
                }
            },
            onDismiss = { deleteCandidate = null },
        )
    }
}

@Composable
private fun DeleteConfirmDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (count == 1) "Delete this result?" else "Delete $count results?") },
        text = {
            Text(
                "This removes the underlying file or record from your device. " +
                    "A copy is kept in the Recover tab so you can restore it.",
            )
        },
    )
}

@Composable
private fun OverflowMenu(
    hasResults: Boolean,
    onCapabilities: () -> Unit,
    onSavedSearches: () -> Unit,
    onSaveSearch: () -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) { Text("Menu") }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Capabilities") },
            onClick = { expanded = false; onCapabilities() },
        )
        DropdownMenuItem(
            text = { Text("Saved searches") },
            onClick = { expanded = false; onSavedSearches() },
        )
        DropdownMenuItem(
            text = { Text("Save this search") },
            onClick = { expanded = false; onSaveSearch() },
        )
        DropdownMenuItem(
            text = { Text("Export results (CSV)") },
            enabled = hasResults,
            onClick = { expanded = false; onExportCsv() },
        )
        DropdownMenuItem(
            text = { Text("Export results (JSON)") },
            enabled = hasResults,
            onClick = { expanded = false; onExportJson() },
        )
        DropdownMenuItem(
            text = { Text("Delete all results") },
            enabled = hasResults,
            onClick = { expanded = false; onDeleteAll() },
        )
    }
}

@Composable
private fun QueryBar(
    state: ScanUiState,
    onQueryChange: (String) -> Unit,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onVerify: () -> Unit,
    onCancelVerify: () -> Unit,
) {
    val isRunning = state.status == ScanStatus.RUNNING

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Text to look for") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (state.canScan && !isRunning) onScan() }),
        )

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isRunning) {
                OutlinedButton(onClick = onCancelScan, modifier = Modifier.weight(1f)) {
                    Text("Stop search")
                }
            } else {
                Button(onClick = onScan, enabled = state.canScan, modifier = Modifier.weight(1f)) {
                    Text("Search device")
                }
            }

            if (state.isVerifying) {
                OutlinedButton(onClick = onCancelVerify, modifier = Modifier.weight(1f)) {
                    Text("Stop checking")
                }
            } else {
                OutlinedButton(
                    onClick = onVerify,
                    enabled = state.canVerify,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Re-verify results")
                }
            }
        }
    }
}

@Composable
private fun QueryModeChips(mode: QueryMode, onChange: (QueryMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QueryMode.entries.forEach { entry ->
            FilterChip(
                selected = entry == mode,
                onClick = { onChange(entry) },
                label = { Text(entry.label) },
            )
        }
    }
}

@Composable
private fun RootBanner(rootStatus: RootStatus) {
    if (rootStatus == RootStatus.CHECKING) return
    val rooted = rootStatus == RootStatus.AVAILABLE
    Text(
        text = if (rooted) {
            "Root access detected · deep app-data scan enabled"
        } else {
            "No root · app private data is sandboxed and cannot be read"
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (rooted) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun SourceChips(
    selected: Set<ScanSource>,
    blocked: Set<ScanSource>,
    isEnabled: (ScanSource) -> Boolean,
    onToggle: (ScanSource) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScanSource.entries.forEach { source ->
            FilterChip(
                selected = source in selected,
                enabled = isEnabled(source),
                onClick = { onToggle(source) },
                label = {
                    Text(if (source in blocked && source in selected) "${source.label} !" else source.label)
                },
            )
        }
    }
}

@Composable
private fun ScanOptionsPanel(
    options: ScanOptions,
    rooted: Boolean,
    onFileContentsChange: (Boolean) -> Unit,
    onImageOcrChange: (Boolean) -> Unit,
    onAppMetadataChange: (Boolean) -> Unit,
    onApkContentsChange: (Boolean) -> Unit,
    onReadAppDataChange: (Boolean) -> Unit,
    onMediaImageOcrChange: (Boolean) -> Unit,
    onMediaVideoTextChange: (Boolean) -> Unit,
    onCaseSensitiveChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        OptionSwitch("Search inside text files", options.searchFileContents, true, onFileContentsChange)
        OptionSwitch("Read text in images (slow)", options.runImageOcr, true, onImageOcrChange)
        OptionSwitch("Media: text in images (OCR)", options.mediaImageOcr, true, onMediaImageOcrChange)
        OptionSwitch("Media: text in videos (slow)", options.mediaVideoText, true, onMediaVideoTextChange)
        OptionSwitch("Search app metadata", options.searchAppMetadata, true, onAppMetadataChange)
        OptionSwitch("Search inside apks (slow)", options.searchApkContents, true, onApkContentsChange)
        OptionSwitch("Read app data & databases (root)", options.readAppData, rooted, onReadAppDataChange)
        OptionSwitch("Case sensitive", options.caseSensitive, true, onCaseSensitiveChange)
    }
}

@Composable
private fun OptionSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PermissionsSummaryCard(missing: List<PermissionRequirement>, onSetUp: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (missing.size == 1) "1 permission needed" else "${missing.size} permissions needed",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = missing.joinToString { it.title },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSetUp) { Text("Set up") }
        }
    }
}

@Composable
private fun ScanProgressPanel(state: ScanUiState, onStop: () -> Unit) {
    val sources = state.options.sources.sortedBy { it.ordinal }
    val (finished, active) = sources.partition { it in state.finishedSources }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Searching for \"${state.query.trim()}\"",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onStop) { Text("Stop") }
        }

        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))

        Text(
            text = "${state.examinedTotal} items checked · ${state.hits.size} found so far",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (state.hitLimitReached) {
            Text(
                text = "Result limit reached — narrow the search text for the rest",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            active.forEach { source ->
                SourceProgressRow(
                    source = source,
                    examined = state.examinedBySource[source] ?: 0,
                    current = state.currentBySource[source],
                )
            }

            if (finished.isNotEmpty()) {
                Text(
                    text = "Finished: " + finished.joinToString { "${it.label} (${state.examinedBySource[it] ?: 0})" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.errors.forEach { (source, reason) ->
                Text(
                    text = "${source.label}: $reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SourceProgressRow(source: ScanSource, examined: Int, current: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = source.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "$examined checked",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = current ?: if (examined > 0) "Checking…" else "Starting…",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScanStatusPanel(state: ScanUiState) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        val headline = when (state.status) {
            ScanStatus.IDLE -> if (state.restoredFromCache) {
                "Showing your last results · ${state.hits.size} matches"
            } else {
                "Enter text and tap Search"
            }

            ScanStatus.RUNNING -> "Scanning · ${state.examinedTotal} items checked · ${state.hits.size} matches"
            ScanStatus.COMPLETED -> "Done · ${state.examinedTotal} items checked · ${state.hits.size} matches"
            ScanStatus.CANCELLED -> "Stopped · ${state.examinedTotal} items checked · ${state.hits.size} matches"
        }
        Text(headline, style = MaterialTheme.typography.bodyMedium)

        if (state.hitLimitReached) {
            Text(
                text = "Result limit reached — narrow the search text for the rest",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (state.isVerifying) {
            Text(
                text = "Re-checking ${state.verifiedCount} of ${state.hits.size} results",
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (state.verifications.isNotEmpty()) {
            val counts = state.statusCounts
            val breakdown = HitStatus.entries
                .mapNotNull { status -> counts[status]?.let { "$it ${status.label}" } }
                .joinToString(" · ")
            Text(
                text = "${state.verifications.size} results checked · $breakdown",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        state.errors.forEach { (source, reason) ->
            Text(
                text = "${source.label}: $reason",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultControls(
    filter: String,
    sortMode: SortMode,
    onFilterChange: (String) -> Unit,
    onSortChange: (SortMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = filter,
            onValueChange = onFilterChange,
            modifier = Modifier.weight(1f),
            label = { Text("Filter results") },
            singleLine = true,
        )

        var expanded by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { expanded = true }) { Text("Sort") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortMode.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text("Sort by ${entry.label}" + if (entry == sortMode) " ✓" else "") },
                    onClick = { expanded = false; onSortChange(entry) },
                )
            }
        }
    }
}

private fun LazyListScope.resultItems(
    grouped: Map<ScanSource, List<ScanHit>>,
    query: String,
    verifications: Map<String, HitVerification>,
    canDelete: (ScanHit) -> Boolean,
    onOpen: (ScanHit) -> Unit,
    onExport: (ScanHit) -> Unit,
    onDelete: (ScanHit) -> Unit,
) {
    grouped.forEach { (source, sourceHits) ->
        item(key = "header-${source.name}") {
            SourceHeader(source, sourceHits.size)
        }
        items(sourceHits, key = { it.id }) { hit ->
            HitRow(
                hit = hit,
                query = query,
                verification = verifications[hit.id],
                canDelete = canDelete(hit),
                onOpen = { onOpen(hit) },
                onExport = { onExport(hit) },
                onDelete = { onDelete(hit) },
            )
        }
    }
}

@Composable
private fun SourceHeader(source: ScanSource, count: Int) {
    Text(
        text = "${source.label} · $count",
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HitRow(
    hit: ScanHit,
    query: String,
    verification: HitVerification?,
    canDelete: Boolean,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = highlight(hit.title, query),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            verification?.let {
                Spacer(Modifier.width(8.dp))
                StatusBadge(it.status)
            }
        }
        Text(
            text = hit.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        hit.snippet?.let { snippet ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = highlight(snippet, query),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        verification?.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = statusColors(verification.status).first,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "matched in ${hit.matchKind.label}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (hit.exportPath != null) {
                TextButton(onClick = onExport) { Text("Export") }
            }
            if (canDelete) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: HitStatus) {
    val (content, container) = statusColors(status)
    Text(
        text = status.label,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = content,
    )
}

@Composable
private fun statusColors(status: HitStatus): Pair<Color, Color> = when (status) {
    HitStatus.UNCHANGED -> MaterialTheme.colorScheme.onSecondaryContainer to
        MaterialTheme.colorScheme.secondaryContainer
    HitStatus.CHANGED -> MaterialTheme.colorScheme.onTertiaryContainer to
        MaterialTheme.colorScheme.tertiaryContainer
    HitStatus.GONE -> MaterialTheme.colorScheme.onErrorContainer to
        MaterialTheme.colorScheme.errorContainer
    HitStatus.UNVERIFIABLE -> MaterialTheme.colorScheme.onSurfaceVariant to
        MaterialTheme.colorScheme.surfaceVariant
}

@Composable
private fun CapabilitiesDialog(state: ScanUiState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Source capabilities") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                ScanSource.entries.forEach { source ->
                    val availability = state.availabilityOf(source)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = source.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = availabilityLabel(availability),
                            style = MaterialTheme.typography.labelMedium,
                            color = availabilityColor(availability),
                        )
                    }
                }
            }
        },
    )
}

private fun availabilityLabel(availability: SourceAvailability): String = when (availability) {
    SourceAvailability.AVAILABLE -> "ready"
    SourceAvailability.NEEDS_PERMISSION -> "needs permission"
    SourceAvailability.NEEDS_ROOT -> "needs root"
    SourceAvailability.NOT_SELECTED -> "not selected"
}

@Composable
private fun availabilityColor(availability: SourceAvailability): Color = when (availability) {
    SourceAvailability.AVAILABLE -> MaterialTheme.colorScheme.primary
    SourceAvailability.NEEDS_PERMISSION -> MaterialTheme.colorScheme.error
    SourceAvailability.NEEDS_ROOT -> MaterialTheme.colorScheme.error
    SourceAvailability.NOT_SELECTED -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun SavedSearchesDialog(
    searches: List<SavedSearch>,
    onApply: (SavedSearch) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Saved searches") },
        text = {
            if (searches.isEmpty()) {
                Text("No saved searches yet.")
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    searches.forEach { search ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onApply(search) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(search.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = "\"${search.query}\" · ${search.options.sources.size} sources",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onDelete(search.name) }) { Text("Delete") }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun SaveSearchDialog(
    defaultName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Save this search") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
    )
}

@Composable
private fun highlight(text: String, query: String) = buildAnnotatedString {
    val needle = query.trim()
    if (needle.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    var cursor = 0
    val style = SpanStyle(
        background = MaterialTheme.colorScheme.primaryContainer,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        fontWeight = FontWeight.Bold,
    )
    while (cursor < text.length) {
        val index = text.indexOf(needle, cursor, ignoreCase = true)
        if (index < 0) {
            append(text.substring(cursor))
            break
        }
        append(text.substring(cursor, index))
        withStyle(style) { append(text.substring(index, index + needle.length)) }
        cursor = index + needle.length
    }
}
