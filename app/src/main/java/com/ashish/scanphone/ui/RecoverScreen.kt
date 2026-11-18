package com.ashish.scanphone.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ashish.scanphone.recover.RecoveryEntry
import com.ashish.scanphone.recover.RecoverySource

@Composable
fun RecoverScreen(
    viewModel: RecoverViewModel,
    onMessage: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingRestore by remember { mutableStateOf<RecoveryEntry?>(null) }

    val writePermsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        pendingRestore?.let { viewModel.restore(it) }
        pendingRestore = null
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.messages.collect(onMessage)
    }

    fun restore(entry: RecoveryEntry) {
        val permission = viewModel.requiredRestorePermission(entry)
        if (permission == null) {
            viewModel.restore(entry)
        } else {
            pendingRestore = entry
            writePermsLauncher.launch(arrayOf(permission))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = viewModel::deepScan,
                enabled = state.hasRoot && !state.isBusy,
            ) {
                Text(if (state.isDeepScanning) "Scanning…" else "Deep scan (root)")
            }
            if (state.carveRunning) {
                Button(onClick = viewModel::stopFullCarve) { Text("Stop carve") }
            } else {
                Button(
                    onClick = viewModel::startFullCarve,
                    enabled = state.hasRoot && !state.isBusy,
                ) {
                    Text("Full carve (root)")
                }
            }
            if (state.isBusy) {
                CircularProgressIndicator(modifier = Modifier.height(24.dp))
            }
            if (!state.hasRoot) {
                Text(
                    "root needed",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.carveRunning) {
            Text(
                text = "Carving whole partition · ${state.carveBytesScanned / (1024 * 1024)} MB scanned · " +
                    "${state.carveFilesCarved} files (runs in the background)",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Text(
            text = "Items deleted through Scan Phone are recoverable here. Deep scan finds trashed/orphaned media; " +
                "full carve streams the whole partition in the background reconstructing files by signature " +
                "(root, best-effort — may be partial, and yields nothing on encrypted storage).",
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (state.recycleBin.isNotEmpty()) {
                item(key = "recycle-header") { SectionHeader("Recycle bin · ${state.recycleBin.size}") }
                items(state.recycleBin, key = { it.id }) { entry ->
                    RecoverRow(entry) { restore(entry) }
                }
            }
            if (state.deepResults.isNotEmpty()) {
                item(key = "deep-header") { SectionHeader("Deep scan · ${state.deepResults.size}") }
                items(state.deepResults, key = { it.id }) { entry ->
                    RecoverRow(entry) { restore(entry) }
                }
            }
            if (state.recycleBin.isEmpty() && state.deepResults.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = if (state.deepScanRun) {
                            "Nothing recoverable found."
                        } else {
                            "Nothing to recover yet."
                        },
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun RecoverRow(entry: RecoveryEntry, onRestore: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${entry.type.name.lowercase().replace('_', ' ')} · " +
                    if (entry.source == RecoverySource.DEEP_SCAN) "deep scan" else "recycle bin",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onRestore) {
            Text(if (entry.source == RecoverySource.DEEP_SCAN) "Save" else "Restore")
        }
    }
}
