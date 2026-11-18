package com.ashish.scanphone.scan

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.ashish.scanphone.model.MatchKind
import com.ashish.scanphone.model.OpenTarget
import com.ashish.scanphone.model.ScanEvent
import com.ashish.scanphone.model.ScanHit
import com.ashish.scanphone.model.ScanRequest
import com.ashish.scanphone.model.ScanSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

class AppsScanner(private val context: Context) : Scanner {

    override val source: ScanSource = ScanSource.APPS

    override fun scan(request: ScanRequest): Flow<ScanEvent> = flow {
        val matcher = TextMatcher(request.query, request.options.caseSensitive, request.options.queryMode)
        if (!matcher.isUsable) {
            emit(ScanEvent.Finished(source, 0))
            return@flow
        }

        val packageManager = context.packageManager
        var examined = 0

        installedApplications(packageManager).forEach { info ->
            coroutineContext.ensureActive()
            examined++
            if (examined % PROGRESS_INTERVAL == 0) {
                emit(ScanEvent.Progress(source, examined, info.packageName))
            }

            val label = runCatching { packageManager.getApplicationLabel(info).toString() }
                .getOrDefault(info.packageName)

            identityHit(info, label, matcher)?.let { emit(ScanEvent.Found(it)) }

            if (request.options.searchAppMetadata) {
                metadataHits(packageManager, info, label, matcher).forEach { emit(ScanEvent.Found(it)) }
            }

            if (request.options.searchApkContents) {
                emit(ScanEvent.Progress(source, examined, "apk: ${info.packageName}"))
                scanApkFiles(info, label, matcher)
            }
        }

        emit(ScanEvent.Progress(source, examined, null))
        emit(ScanEvent.Finished(source, examined))
    }.flowOn(Dispatchers.IO)

    private fun identityHit(info: ApplicationInfo, label: String, matcher: TextMatcher): ScanHit? {
        val kind = when {
            matcher.matches(label) -> MatchKind.APP_LABEL
            matcher.matches(info.packageName) -> MatchKind.APP_PACKAGE
            else -> return null
        }
        return hit(info, label, kind, info.packageName, null)
    }

    private fun metadataHits(
        packageManager: PackageManager,
        info: ApplicationInfo,
        label: String,
        matcher: TextMatcher,
    ): List<ScanHit> {
        val packageInfo = packageInfo(packageManager, info.packageName) ?: return emptyList()
        val hits = ArrayList<ScanHit>()

        val version = "${packageInfo.versionName} (${versionCode(packageInfo)})"
        if (matcher.matches(packageInfo.versionName) || matcher.matches(versionCode(packageInfo).toString())) {
            hits += hit(info, label, MatchKind.APP_VERSION, version, null)
        }

        val installer = installerOf(packageManager, info.packageName)
        if (matcher.matches(installer)) {
            hits += hit(info, label, MatchKind.APP_INSTALLER, "installed by $installer", null)
        }

        listOfNotNull(info.sourceDir, info.dataDir, info.nativeLibraryDir)
            .firstOrNull { matcher.matches(it) }
            ?.let { hits += hit(info, label, MatchKind.APP_PATH, it, null) }

        packageInfo.requestedPermissions?.filter { matcher.matches(it) }?.take(MAX_METADATA_HITS)
            ?.forEach { hits += hit(info, label, MatchKind.APP_PERMISSION, it, null) }

        components(packageInfo).filter { matcher.matches(it) }.take(MAX_METADATA_HITS)
            .forEach { hits += hit(info, label, MatchKind.APP_COMPONENT, it, null) }

        val installed = DATE_FORMAT.format(Date(packageInfo.firstInstallTime))
        val updated = DATE_FORMAT.format(Date(packageInfo.lastUpdateTime))
        if (matcher.matches(installed) || matcher.matches(updated)) {
            hits += hit(info, label, MatchKind.APP_VERSION, "installed $installed · updated $updated", null)
        }

        return hits
    }

    private suspend fun FlowCollector<ScanEvent>.scanApkFiles(
        info: ApplicationInfo,
        label: String,
        matcher: TextMatcher,
    ) {
        val archives = buildList {
            info.sourceDir?.let(::add)
            info.splitSourceDirs?.filterNotNull()?.let(::addAll)
        }.distinct()

        var emitted = 0
        for (path in archives) {
            if (emitted >= MAX_APK_HITS) return
            val file = File(path)
            if (!file.isFile || !file.canRead()) continue
            emitted += scanArchive(file, info, label, matcher, MAX_APK_HITS - emitted)
        }
    }

    private suspend fun FlowCollector<ScanEvent>.scanArchive(
        archive: File,
        info: ApplicationInfo,
        label: String,
        matcher: TextMatcher,
        budget: Int,
    ): Int {
        var emitted = 0
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements() && emitted < budget) {
                    coroutineContext.ensureActive()
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue

                    if (matcher.matches(entry.name)) {
                        emit(
                            ScanEvent.Found(
                                hit(info, label, MatchKind.APK_ENTRY, entry.name, null, archive.name),
                            ),
                        )
                        emitted++
                        continue
                    }

                    val snippet = entryMatch(zip, entry, matcher) ?: continue
                    emit(
                        ScanEvent.Found(
                            hit(info, label, MatchKind.APK_RESOURCE, entry.name, snippet, archive.name),
                        ),
                    )
                    emitted++
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return emitted
        }
        return emitted
    }

    private fun entryMatch(zip: ZipFile, entry: ZipEntry, matcher: TextMatcher): String? {
        val size = entry.size
        if (size in 1..MAX_ENTRY_BYTES && isPlainText(entry.name)) {
            return zip.getInputStream(entry).bufferedReader().use { reader ->
                reader.lineSequence().firstNotNullOfOrNull { line ->
                    val index = matcher.indexIn(line)
                    if (index >= 0) matcher.snippet(line, index) else null
                }
            }
        }
        if (!isBinaryOfInterest(entry.name)) return null

        var snippet: String? = null
        zip.getInputStream(entry).use { stream ->
            BinaryText.forEachRun(stream, MAX_BINARY_BYTES) { run ->
                val index = matcher.indexIn(run)
                if (index >= 0) {
                    snippet = matcher.snippet(run, index)
                    true
                } else {
                    false
                }
            }
        }
        return snippet
    }

    private fun hit(
        info: ApplicationInfo,
        label: String,
        kind: MatchKind,
        detail: String,
        snippet: String?,
        archiveName: String? = null,
    ): ScanHit = ScanHit(
        id = "app:${kind.name}:${info.packageName}:${archiveName.orEmpty()}:$detail",
        source = source,
        matchKind = kind,
        title = label,
        subtitle = if (archiveName == null) detail else "$archiveName · $detail",
        snippet = snippet,
        openTarget = OpenTarget.App(info.packageName),
        verifyRef = info.packageName,
        fingerprint = info.sourceDir?.let { FileFingerprint.of(File(it)) },
    )

    private fun components(packageInfo: PackageInfo): List<String> = buildList {
        packageInfo.activities?.mapNotNullTo(this, ComponentInfo::name)
        packageInfo.services?.mapNotNullTo(this, ComponentInfo::name)
        packageInfo.receivers?.mapNotNullTo(this, ComponentInfo::name)
        packageInfo.providers?.mapNotNullTo(this, ComponentInfo::name)
    }

    private fun packageInfo(packageManager: PackageManager, packageName: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(METADATA_FLAGS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, METADATA_FLAGS)
        }
    } catch (_: Exception) {
        null
    }

    private fun installerOf(packageManager: PackageManager, packageName: String): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(packageName).installingPackageName.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(packageName).orEmpty()
        }
    } catch (_: Exception) {
        ""
    }

    private fun versionCode(packageInfo: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

    private fun installedApplications(packageManager: PackageManager): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

    private fun isPlainText(name: String): Boolean =
        PLAIN_TEXT_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) } ||
            name.startsWith("assets/") && !name.endsWith(".png", ignoreCase = true)

    private fun isBinaryOfInterest(name: String): Boolean =
        BINARY_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    private companion object {
        const val PROGRESS_INTERVAL = 5
        const val MAX_METADATA_HITS = 5
        const val MAX_APK_HITS = 5
        const val MAX_ENTRY_BYTES = 4L * 1024 * 1024
        const val MAX_BINARY_BYTES = 32L * 1024 * 1024

        val METADATA_FLAGS = PackageManager.GET_PERMISSIONS or
            PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS

        val PLAIN_TEXT_EXTENSIONS = listOf(
            ".txt", ".json", ".csv", ".md", ".properties", ".js", ".css", ".html", ".htm",
            ".yml", ".yaml", ".sql", ".ini", ".cfg", ".conf", ".version", ".pem", ".proto",
        )

        val BINARY_EXTENSIONS = listOf(".dex", ".arsc", ".xml", ".so", ".bin", ".db", ".dat")

        val DATE_FORMAT = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    }
}
