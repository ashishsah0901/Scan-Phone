package com.ashish.scanphone.threat.source

import android.content.Context
import android.os.Environment
import com.ashish.scanphone.model.OpenTarget
import com.ashish.scanphone.threat.ThreatArea
import com.ashish.scanphone.threat.ThreatCheck
import com.ashish.scanphone.threat.ThreatEvent
import com.ashish.scanphone.threat.ThreatFinding
import com.ashish.scanphone.threat.ThreatScanOptions
import com.ashish.scanphone.threat.rules.FileThreatRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import kotlin.coroutines.coroutineContext

class FileThreatSource(private val context: Context) : ThreatSource {

    override val check: ThreatCheck = ThreatCheck.FILES

    override fun scan(options: ThreatScanOptions): Flow<ThreatEvent> = flow {
        val roots = storageRoots()
        if (roots.isEmpty()) {
            emit(ThreatEvent.Failed(ThreatArea.FILES, "No readable storage volume found"))
            emit(ThreatEvent.Finished(ThreatArea.FILES, 0))
            return@flow
        }

        val pending = ArrayDeque(roots)
        val visited = HashSet<String>()
        var examined = 0

        while (pending.isNotEmpty() && examined < MAX_FILES) {
            coroutineContext.ensureActive()
            val current = pending.removeLast()
            val canonical = runCatching { current.canonicalPath }.getOrNull() ?: current.absolutePath
            if (!visited.add(canonical)) continue

            if (current.isDirectory) {
                if (isSkippedDirectory(current)) continue
                current.listFiles()?.forEach(pending::addLast)
                continue
            }

            examined++
            if (examined % PROGRESS_INTERVAL == 0) {
                emit(ThreatEvent.Progress(ThreatArea.FILES, examined, current.parent))
            }

            val parent = current.parent.orEmpty()
            val matches = ArrayList(FileThreatRules.analyzeName(current.name, parent))
            if (FileThreatRules.shouldReadContent(current.name, current.length())) {
                matches += contentMatches(current)
            }

            val finding = ThreatFinding.from(
                id = "threat:file:${current.absolutePath}",
                area = ThreatArea.FILES,
                title = current.name,
                subtitle = parent,
                evidence = current.absolutePath,
                openTarget = OpenTarget.LocalFile(current.absolutePath),
                matches = matches,
                deleteRef = current.absolutePath,
            ) ?: continue
            emit(ThreatEvent.Found(finding))
        }

        emit(ThreatEvent.Finished(ThreatArea.FILES, examined))
    }.flowOn(Dispatchers.IO)

    private fun contentMatches(file: File) = try {
        if (!file.canRead()) {
            emptyList()
        } else {
            FileThreatRules.analyzeContent(file.readText(Charsets.ISO_8859_1))
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        emptyList()
    }

    private fun storageRoots(): List<File> {
        val roots = LinkedHashSet<File>()
        Environment.getExternalStorageDirectory()?.let(roots::add)
        context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
            var volume: File? = dir
            repeat(VOLUME_DEPTH) { volume = volume?.parentFile }
            volume?.let(roots::add)
        }
        return roots.filter { it.exists() && it.isDirectory && it.canRead() }
    }

    private fun isSkippedDirectory(dir: File): Boolean {
        val path = dir.absolutePath
        return SKIPPED_SEGMENTS.any { path.endsWith(it) }
    }

    private companion object {
        const val MAX_FILES = 200_000
        const val PROGRESS_INTERVAL = 200
        const val VOLUME_DEPTH = 4
        val SKIPPED_SEGMENTS = listOf("/Android/data", "/Android/obb", "/.thumbnails")
    }
}
