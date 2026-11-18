package com.ashish.scanphone.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ashish.scanphone.threat.ThreatArea
import com.ashish.scanphone.threat.ThreatCheck
import com.ashish.scanphone.threat.ThreatFinding
import com.ashish.scanphone.threat.ThreatSeverity

@Composable
fun ThreatScreen(
    viewModel: ThreatViewModel,
    onMessage: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var deleteCandidate by remember { mutableStateOf<ThreatFinding?>(null) }
    var pendingDeleteAfterPerms by remember { mutableStateOf<ThreatFinding?>(null) }

    val writePermsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        pendingDeleteAfterPerms?.let { viewModel.deleteFinding(it) }
        pendingDeleteAfterPerms = null
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.messages.collect(onMessage)
    }

    fun requestDelete(finding: ThreatFinding) {
        val permission = viewModel.requiredDeletePermission(finding)
        if (permission == null) {
            viewModel.deleteFinding(finding)
        } else {
            pendingDeleteAfterPerms = finding
            writePermsLauncher.launch(arrayOf(permission))
        }
    }

    fun openApp(finding: ThreatFinding, uninstall: Boolean) {
        val pkg = finding.packageName ?: return
        val intent = if (uninstall) {
            Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { viewModel.notifyUser("Could not open the app page") }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.status == ThreatStatus.RUNNING) {
                OutlinedButton(onClick = viewModel::cancelScan, modifier = Modifier.weight(1f)) {
                    Text("Stop")
                }
            } else {
                Button(
                    onClick = viewModel::startScan,
                    enabled = state.canScan,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Scan for threats")
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThreatCheck.entries.forEach { check ->
                FilterChip(
                    selected = check in state.checks,
                    onClick = { viewModel.onToggleCheck(check) },
                    label = { Text(check.label) },
                )
            }
        }

        ThreatStatusPanel(state)

        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.sortedFindings, key = { it.id }) { finding ->
                ThreatRow(
                    finding = finding,
                    canDelete = viewModel.canDelete(finding),
                    canQuarantine = viewModel.canQuarantine(finding),
                    isApp = finding.area == ThreatArea.APPS && finding.packageName != null,
                    onOpen = {
                        val target = finding.openTarget
                        val message = if (target == null) {
                            "Nothing to open for this finding"
                        } else {
                            HitOpener.open(context, target)
                        }
                        if (message != null) viewModel.notifyUser(message)
                    },
                    onQuarantine = { viewModel.quarantineFinding(finding) },
                    onDelete = { deleteCandidate = finding },
                    onUninstall = { openApp(finding, uninstall = true) },
                    onAppInfo = { openApp(finding, uninstall = false) },
                )
            }
            if (state.findings.isEmpty() && state.status == ThreatStatus.COMPLETED) {
                item(key = "clean") {
                    Text(
                        text = "No threats flagged in the areas scanned.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    deleteCandidate?.let { finding ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            confirmButton = {
                TextButton(onClick = {
                    deleteCandidate = null
                    requestDelete(finding)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") } },
            title = { Text("Delete this item?") },
            text = {
                Text(
                    "This removes the underlying ${finding.area.label.lowercase()} item. " +
                        "A copy is kept in the Recover tab so you can restore it.",
                )
            },
        )
    }
}

@Composable
private fun ThreatStatusPanel(state: ThreatUiState) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (state.status == ThreatStatus.RUNNING) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
        }

        val headline = when (state.status) {
            ThreatStatus.IDLE -> "Runs local heuristic checks — no data leaves the device"
            ThreatStatus.RUNNING -> "Scanning · ${state.examinedTotal} items checked · ${state.findings.size} flagged"
            ThreatStatus.COMPLETED -> "Done · ${state.examinedTotal} items checked · ${state.findings.size} flagged"
            ThreatStatus.CANCELLED -> "Stopped · ${state.findings.size} flagged"
        }
        Text(headline, style = MaterialTheme.typography.bodyMedium)

        val counts = state.countsBySeverity
        if (counts.isNotEmpty()) {
            val breakdown = ThreatSeverity.entries.reversed()
                .mapNotNull { severity -> counts[severity]?.let { "$it ${severity.label}" } }
                .joinToString(" · ")
            Text(breakdown, style = MaterialTheme.typography.bodySmall)
        }

        state.currentLocation?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.errors.forEach { (area, reason) ->
            Text(
                text = "${area.label}: $reason",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ThreatRow(
    finding: ThreatFinding,
    canDelete: Boolean,
    canQuarantine: Boolean,
    isApp: Boolean,
    onOpen: () -> Unit,
    onQuarantine: () -> Unit,
    onDelete: () -> Unit,
    onUninstall: () -> Unit,
    onAppInfo: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SeverityBadge(finding.severity)
            Spacer(Modifier.width(8.dp))
            Text(
                text = finding.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "${finding.category.label} · ${finding.area.label} · ${finding.subtitle}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        finding.reasons.forEach { reason ->
            Text(
                text = "• $reason",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (canQuarantine) {
                TextButton(onClick = onQuarantine) { Text("Quarantine") }
            }
            if (isApp) {
                TextButton(onClick = onAppInfo) { Text("App info") }
                TextButton(
                    onClick = onUninstall,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Uninstall") }
            }
            if (canDelete) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun SeverityBadge(severity: ThreatSeverity) {
    val (content, container) = severityColors(severity)
    Text(
        text = severity.label,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = content,
    )
}

@Composable
private fun severityColors(severity: ThreatSeverity): Pair<Color, Color> = when (severity) {
    ThreatSeverity.CRITICAL -> MaterialTheme.colorScheme.onError to MaterialTheme.colorScheme.error
    ThreatSeverity.HIGH -> MaterialTheme.colorScheme.onErrorContainer to MaterialTheme.colorScheme.errorContainer
    ThreatSeverity.MEDIUM -> MaterialTheme.colorScheme.onTertiaryContainer to
        MaterialTheme.colorScheme.tertiaryContainer
    ThreatSeverity.LOW -> MaterialTheme.colorScheme.onSecondaryContainer to
        MaterialTheme.colorScheme.secondaryContainer
}
