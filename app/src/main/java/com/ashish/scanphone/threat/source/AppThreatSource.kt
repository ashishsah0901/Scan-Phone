package com.ashish.scanphone.threat.source

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.ashish.scanphone.model.OpenTarget
import com.ashish.scanphone.threat.ThreatArea
import com.ashish.scanphone.threat.ThreatCheck
import com.ashish.scanphone.threat.ThreatEvent
import com.ashish.scanphone.threat.ThreatFinding
import com.ashish.scanphone.threat.ThreatScanOptions
import com.ashish.scanphone.threat.rules.AppFacts
import com.ashish.scanphone.threat.rules.AppThreatRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.coroutineContext

class AppThreatSource(private val context: Context) : ThreatSource {

    override val check: ThreatCheck = ThreatCheck.APPS

    override fun scan(options: ThreatScanOptions): Flow<ThreatEvent> = flow {
        val packageManager = context.packageManager
        var examined = 0

        installedApplications(packageManager).forEach { info ->
            coroutineContext.ensureActive()
            examined++
            if (examined % PROGRESS_INTERVAL == 0) {
                emit(ThreatEvent.Progress(ThreatArea.APPS, examined, info.packageName))
            }
            if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) return@forEach

            val packageInfo = packageInfo(packageManager, info.packageName) ?: return@forEach
            val facts = factsFor(packageManager, info, packageInfo)
            val matches = AppThreatRules.analyze(facts)
            val finding = ThreatFinding.from(
                id = "threat:app:${info.packageName}",
                area = ThreatArea.APPS,
                title = facts.label,
                subtitle = "${info.packageName} · ${facts.installer ?: "sideloaded"}",
                evidence = facts.permissions.joinToString(", ").take(EVIDENCE),
                openTarget = OpenTarget.App(info.packageName),
                matches = matches,
                packageName = info.packageName,
            ) ?: return@forEach
            emit(ThreatEvent.Found(finding))
        }

        emit(ThreatEvent.Finished(ThreatArea.APPS, examined))
    }.flowOn(Dispatchers.IO)

    private fun factsFor(
        packageManager: PackageManager,
        info: ApplicationInfo,
        packageInfo: PackageInfo,
    ): AppFacts {
        val permissions = packageInfo.requestedPermissions?.toSet().orEmpty()
        val accessibility = packageInfo.services?.any {
            it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE"
        } ?: false
        val deviceAdmin = packageInfo.receivers?.any {
            it.permission == "android.permission.BIND_DEVICE_ADMIN"
        } ?: false

        return AppFacts(
            label = runCatching { packageManager.getApplicationLabel(info).toString() }
                .getOrDefault(info.packageName),
            packageName = info.packageName,
            installer = installerOf(packageManager, info.packageName),
            isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            permissions = permissions,
            hasAccessibilityService = accessibility,
            hasDeviceAdmin = deviceAdmin,
        )
    }

    private fun packageInfo(packageManager: PackageManager, packageName: String): PackageInfo? = try {
        val flags = PackageManager.GET_PERMISSIONS or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, flags)
        }
    } catch (_: Exception) {
        null
    }

    private fun installerOf(packageManager: PackageManager, packageName: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(packageName)
        }
    } catch (_: Exception) {
        null
    }

    private fun installedApplications(packageManager: PackageManager): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

    private companion object {
        const val PROGRESS_INTERVAL = 25
        const val EVIDENCE = 200
    }
}
