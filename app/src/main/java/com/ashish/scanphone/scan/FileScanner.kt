package com.ashish.scanphone.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import com.ashish.scanphone.model.MatchKind
import com.ashish.scanphone.model.OpenTarget
import com.ashish.scanphone.model.ScanEvent
import com.ashish.scanphone.model.ScanHit
import com.ashish.scanphone.model.ScanRequest
import com.ashish.scanphone.model.ScanSource
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.DecimalFormat
import kotlin.coroutines.coroutineContext

class FileScanner(private val context: Context) : Scanner {

    override val source: ScanSource = ScanSource.FILES

    override fun scan(request: ScanRequest): Flow<ScanEvent> = flow {
        val matcher = TextMatcher(request.query, request.options.caseSensitive, request.options.queryMode)
        if (!matcher.isUsable) {
            emit(ScanEvent.Finished(source, 0))
            return@flow
        }

        val roots = storageRoots()
        if (roots.isEmpty()) {
            emit(ScanEvent.Failed(source, "No readable storage volume found"))
            emit(ScanEvent.Finished(source, 0))
            return@flow
        }

        val recognizer: TextRecognizer? =
            if (request.options.runImageOcr) {
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            } else {
                null
            }

        val pending = ArrayDeque(roots)
        val visited = HashSet<String>()
        var examined = 0

        try {
            while (pending.isNotEmpty()) {
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
                    emit(ScanEvent.Progress(source, examined, current.parent))
                }

                emitNameOrPathHit(current, matcher)?.let { emit(ScanEvent.Found(it)) }

                if (request.options.searchFileContents && isTextFile(current)) {
                    contentHit(current, matcher)?.let { emit(ScanEvent.Found(it)) }
                }

                if (recognizer != null && isImageFile(current)) {
                    imageTextHit(current, matcher, recognizer)?.let { emit(ScanEvent.Found(it)) }
                }
            }
        } finally {
            recognizer?.close()
        }

        emit(ScanEvent.Progress(source, examined, null))
        emit(ScanEvent.Finished(source, examined))
    }.flowOn(Dispatchers.IO)

    private fun emitNameOrPathHit(file: File, matcher: TextMatcher): ScanHit? {
        val nameIndex = matcher.indexIn(file.name)
        val parent = file.parent.orEmpty()
        val kind = when {
            nameIndex >= 0 -> MatchKind.FILE_NAME
            matcher.matches(parent) -> MatchKind.FILE_PATH
            else -> return null
        }
        return ScanHit(
            id = "file:${kind.name}:${file.absolutePath}",
            source = source,
            matchKind = kind,
            title = file.name,
            subtitle = "${readableSize(file.length())} · $parent",
            snippet = null,
            openTarget = OpenTarget.LocalFile(file.absolutePath),
            verifyRef = file.absolutePath,
            fingerprint = fingerprintOf(file),
        )
    }

    private suspend fun contentHit(file: File, matcher: TextMatcher): ScanHit? {
        if (file.length() > MAX_TEXT_BYTES || !file.canRead()) return null
        var hit: ScanHit? = null
        try {
            file.bufferedReader().use { reader ->
                var lineNumber = 0
                while (hit == null) {
                    coroutineContext.ensureActive()
                    val line = reader.readLine() ?: break
                    lineNumber++
                    val index = matcher.indexIn(line)
                    if (index >= 0) {
                        hit = ScanHit(
                            id = "file:content:${file.absolutePath}",
                            source = source,
                            matchKind = MatchKind.FILE_CONTENT,
                            title = file.name,
                            subtitle = "line $lineNumber · ${file.parent.orEmpty()}",
                            snippet = matcher.snippet(line, index),
                            openTarget = OpenTarget.LocalFile(file.absolutePath),
                            verifyRef = file.absolutePath,
                            fingerprint = fingerprintOf(file),
                        )
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return null
        }
        return hit
    }

    private suspend fun imageTextHit(
        file: File,
        matcher: TextMatcher,
        recognizer: TextRecognizer,
    ): ScanHit? {
        if (file.length() > MAX_IMAGE_BYTES || !file.canRead()) return null
        val bitmap = decodeDownsampled(file) ?: return null
        val text = try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        } finally {
            bitmap.recycle()
        } ?: return null

        val index = matcher.indexIn(text)
        if (index < 0) return null
        return ScanHit(
            id = "file:ocr:${file.absolutePath}",
            source = source,
            matchKind = MatchKind.IMAGE_TEXT,
            title = file.name,
            subtitle = "${readableSize(file.length())} · ${file.parent.orEmpty()}",
            snippet = matcher.snippet(text, index),
            openTarget = OpenTarget.LocalFile(file.absolutePath),
            verifyRef = file.absolutePath,
            fingerprint = fingerprintOf(file),
        )
    }

    private fun decodeDownsampled(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        if (largest <= 0) return null
        var sample = 1
        while (largest / sample > MAX_OCR_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.absolutePath, options)
    }.getOrNull()

    private fun storageRoots(): List<File> {
        val roots = LinkedHashSet<File>()
        Environment.getExternalStorageDirectory()?.let { external ->
            roots.add(external)
            roots.add(File(external, "Android/media"))
        }
        context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
            var volume: File? = dir
            repeat(VOLUME_DEPTH) { volume = volume?.parentFile }
            volume?.let {
                roots.add(it)
                roots.add(File(it, "Android/media"))
            }
        }
        return roots.filter { it.exists() && it.isDirectory && it.canRead() }
    }

    private fun isSkippedDirectory(dir: File): Boolean {
        val path = dir.absolutePath
        return SKIPPED_SEGMENTS.any { path.endsWith(it) }
    }

    private fun fingerprintOf(file: File): String = FileFingerprint.of(file)

    private fun isTextFile(file: File): Boolean =
        file.extension.lowercase() in TEXT_EXTENSIONS

    private fun isImageFile(file: File): Boolean =
        file.extension.lowercase() in IMAGE_EXTENSIONS

    private fun readableSize(bytes: Long): String {
        if (bytes < BYTES_PER_UNIT) return "$bytes B"
        var value = bytes.toDouble()
        var unitIndex = -1
        while (value >= BYTES_PER_UNIT && unitIndex < UNITS.lastIndex) {
            value /= BYTES_PER_UNIT
            unitIndex++
        }
        return "${SIZE_FORMAT.format(value)} ${UNITS[unitIndex]}"
    }

    private companion object {
        const val PROGRESS_INTERVAL = 50
        const val VOLUME_DEPTH = 4
        const val MAX_TEXT_BYTES = 8L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 24L * 1024 * 1024
        const val MAX_OCR_DIMENSION = 1600
        const val BYTES_PER_UNIT = 1024
        val SIZE_FORMAT = DecimalFormat("#.#")
        val UNITS = listOf("KB", "MB", "GB", "TB")

        val SKIPPED_SEGMENTS = listOf("/Android/data", "/Android/obb", "/.thumbnails")

        val TEXT_EXTENSIONS = setOf(
            "txt", "log", "md", "json", "csv", "tsv", "xml", "html", "htm", "css", "js", "ts",
            "kt", "kts", "java", "py", "c", "cc", "cpp", "h", "hpp", "rs", "go", "rb", "php",
            "sh", "bat", "yml", "yaml", "ini", "cfg", "conf", "properties", "sql", "srt", "vtt",
            "gradle", "gitignore", "env", "toml",
        )

        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif")
    }
}
