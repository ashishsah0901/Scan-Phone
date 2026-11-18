package com.ashish.scanphone.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.ashish.scanphone.model.ScanSource
import com.ashish.scanphone.permission.PermissionRequirement
import com.ashish.scanphone.permission.ScanPermissions

enum class PermissionFlowMode {
    SETUP,
    BEFORE_SCAN,
}

object PermissionFlowPrefs {
    private const val FILE = "permission_flow"
    private const val KEY_SHOWN = "shown"

    fun shouldAutoShow(context: Context): Boolean = !prefs(context).getBoolean(KEY_SHOWN, false)

    fun markShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_SHOWN, true).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

@Composable
fun PermissionFlowDialog(
    sources: Set<ScanSource>,
    finishLabel: String,
    onFinished: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val steps = remember { ScanPermissions.flowRequirements(context, sources) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var attempted by remember { mutableStateOf(false) }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        attempted = true
        revision++
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        attempted = true
        revision++
    }

    LifecycleResumeEffect(Unit) {
        revision++
        onPauseOrDispose { }
    }

    LaunchedEffect(steps) {
        if (steps.isEmpty()) onFinished()
    }

    val step = steps.getOrNull(index)
    val granted = remember(step, revision) { step != null && ScanPermissions.isSatisfied(context, step) }

    LaunchedEffect(index, granted) {
        if (granted) {
            index++
            attempted = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Permissions",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Spacer(Modifier.height(16.dp))

                if (step == null) {
                    val stillMissing = remember(revision) {
                        steps.filterNot { ScanPermissions.isSatisfied(context, it) }
                    }
                    SummaryStep(stillMissing = stillMissing, finishLabel = finishLabel, onFinish = onFinished)
                } else {
                    RequirementStep(
                        step = step,
                        position = index + 1,
                        total = steps.size,
                        attempted = attempted,
                        onGrant = {
                            if (step.needsSpecialAccess) {
                                runCatching {
                                    settingsLauncher.launch(ScanPermissions.specialAccessIntent(context, step.special))
                                }
                            } else {
                                runtimeLauncher.launch(step.runtimePermissions.toTypedArray())
                            }
                        },
                        onOpenAppSettings = {
                            runCatching { settingsLauncher.launch(ScanPermissions.appSettingsIntent(context)) }
                        },
                        onSkip = {
                            index++
                            attempted = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RequirementStep(
    step: PermissionRequirement,
    position: Int,
    total: Int,
    attempted: Boolean,
    onGrant: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LinearProgressIndicator(
            progress = { (position - 1).toFloat() / total },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Step $position of $total",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Text(step.title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(step.rationale, style = MaterialTheme.typography.bodyMedium)
        step.source?.let { source ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Used by: ${source.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (step.needsSpecialAccess) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "This opens a system settings page. Turn on access for Scan Phone, then come back here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (attempted) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Not granted yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.weight(1f))

        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
            Text(if (step.needsSpecialAccess) "Open settings" else "Grant")
        }
        if (attempted && !step.needsSpecialAccess) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenAppSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Open app settings")
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("Skip for now")
        }
    }
}

@Composable
private fun SummaryStep(
    stillMissing: List<PermissionRequirement>,
    finishLabel: String,
    onFinish: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = if (stillMissing.isEmpty()) "All set" else "Almost there",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        if (stillMissing.isEmpty()) {
            Text(
                text = "Everything you selected can be searched.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text("Still missing:", style = MaterialTheme.typography.bodyMedium)
            stillMissing.forEach { requirement ->
                Text("• ${requirement.title}", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "You can grant these later from the Scan tab.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.weight(1f))

        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
            Text(finishLabel)
        }
    }
}
