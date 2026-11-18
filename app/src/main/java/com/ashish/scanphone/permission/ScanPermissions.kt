package com.ashish.scanphone.permission

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ashish.scanphone.model.ScanSource

enum class SpecialAccess {
    NONE,
    ALL_FILES,
    NOTIFICATION_LISTENER,
    USAGE_ACCESS,
}

data class PermissionRequirement(
    val source: ScanSource?,
    val title: String,
    val rationale: String,
    val runtimePermissions: List<String>,
    val special: SpecialAccess,
) {
    val needsSpecialAccess: Boolean get() = special != SpecialAccess.NONE
}

data class PermissionSnapshot(
    val missing: List<PermissionRequirement>,
) {
    val isFullyGranted: Boolean get() = missing.isEmpty()

    fun isMissingFor(source: ScanSource): Boolean = missing.any { it.source == source }
}

object ScanPermissions {

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun specialAccessIntent(context: Context, special: SpecialAccess): Intent = when (special) {
        SpecialAccess.ALL_FILES -> Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        )

        SpecialAccess.NOTIFICATION_LISTENER -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        SpecialAccess.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        SpecialAccess.NONE -> appSettingsIntent(context)
    }

    fun appSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )

    fun snapshot(context: Context, sources: Set<ScanSource>): PermissionSnapshot =
        PermissionSnapshot(
            missing = sources.mapNotNull { source -> requirementFor(context, source) },
        )

    fun flowRequirements(context: Context, sources: Set<ScanSource>): List<PermissionRequirement> =
        listOfNotNull(notificationsRequirement(context)) + snapshot(context, sources).missing

    fun isSatisfied(context: Context, requirement: PermissionRequirement): Boolean =
        when (requirement.special) {
            SpecialAccess.NONE -> requirement.runtimePermissions.all { isGranted(context, it) }
            SpecialAccess.ALL_FILES -> hasAllFilesAccess()
            SpecialAccess.NOTIFICATION_LISTENER -> hasNotificationAccess(context)
            SpecialAccess.USAGE_ACCESS -> hasUsageAccess(context)
        }

    private fun notificationsRequirement(context: Context): PermissionRequirement? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        if (isGranted(context, Manifest.permission.POST_NOTIFICATIONS)) return null
        return PermissionRequirement(
            source = null,
            title = "Notifications",
            rationale = "Shows search progress while a scan keeps running in the background.",
            runtimePermissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
            special = SpecialAccess.NONE,
        )
    }

    private fun requirementFor(context: Context, source: ScanSource): PermissionRequirement? =
        when (source) {
            ScanSource.FILES -> filesRequirement(context)
            ScanSource.CONTACTS -> runtimeRequirement(
                context = context,
                source = source,
                title = "Contacts access",
                rationale = "Needed to match names, phone numbers and emails in your address book.",
                permissions = listOf(Manifest.permission.READ_CONTACTS),
            )

            ScanSource.MESSAGES -> runtimeRequirement(
                context = context,
                source = source,
                title = "SMS access",
                rationale = "Needed to search the text of your SMS and MMS messages.",
                permissions = listOf(Manifest.permission.READ_SMS),
            )

            ScanSource.CALL_LOG -> runtimeRequirement(
                context = context,
                source = source,
                title = "Call log access",
                rationale = "Needed to search numbers, caller names and call history.",
                permissions = listOf(Manifest.permission.READ_CALL_LOG),
            )

            ScanSource.CALENDAR -> runtimeRequirement(
                context = context,
                source = source,
                title = "Calendar access",
                rationale = "Needed to search event titles, notes and locations.",
                permissions = listOf(Manifest.permission.READ_CALENDAR),
            )

            ScanSource.MEDIA -> null

            ScanSource.NOTIFICATIONS -> specialRequirement(
                granted = hasNotificationAccess(context),
                source = source,
                title = "Notification access",
                rationale = "Needed to capture notifications from WhatsApp, SMS and other apps as they arrive.",
                special = SpecialAccess.NOTIFICATION_LISTENER,
            )

            ScanSource.APP_USAGE -> specialRequirement(
                granted = hasUsageAccess(context),
                source = source,
                title = "Usage access",
                rationale = "Needed to search app activity — last used time and time spent in each app.",
                special = SpecialAccess.USAGE_ACCESS,
            )

            ScanSource.APPS -> null
            ScanSource.APP_DATA -> null
        }

    private fun filesRequirement(context: Context): PermissionRequirement? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (hasAllFilesAccess()) return null
            return PermissionRequirement(
                source = ScanSource.FILES,
                title = "All files access",
                rationale = "Needed to walk every folder on internal storage and SD cards.",
                runtimePermissions = emptyList(),
                special = SpecialAccess.ALL_FILES,
            )
        }
        return runtimeRequirement(
            context = context,
            source = ScanSource.FILES,
            title = "Storage access",
            rationale = "Needed to read files on shared storage.",
            permissions = listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
        )
    }

    private fun specialRequirement(
        granted: Boolean,
        source: ScanSource,
        title: String,
        rationale: String,
        special: SpecialAccess,
    ): PermissionRequirement? {
        if (granted) return null
        return PermissionRequirement(
            source = source,
            title = title,
            rationale = rationale,
            runtimePermissions = emptyList(),
            special = special,
        )
    }

    private fun runtimeRequirement(
        context: Context,
        source: ScanSource,
        title: String,
        rationale: String,
        permissions: List<String>,
    ): PermissionRequirement? {
        val denied = permissions.filterNot { isGranted(context, it) }
        if (denied.isEmpty()) return null
        return PermissionRequirement(
            source = source,
            title = title,
            rationale = rationale,
            runtimePermissions = denied,
            special = SpecialAccess.NONE,
        )
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
