package com.ashish.scanphone.scan

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.ashish.scanphone.model.MatchKind
import com.ashish.scanphone.model.OpenTarget
import com.ashish.scanphone.model.ScanEvent
import com.ashish.scanphone.model.ScanHit
import com.ashish.scanphone.model.ScanRequest
import com.ashish.scanphone.model.ScanSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import kotlin.coroutines.coroutineContext

class MediaScanner(private val context: Context) : Scanner {

    override val source: ScanSource = ScanSource.MEDIA

    override fun scan(request: ScanRequest): Flow<ScanEvent> = flow {
        val matcher = TextMatcher(request.query, request.options.caseSensitive, request.options.queryMode)
        if (!matcher.isUsable) {
            emit(ScanEvent.Finished(source, 0))
            return@flow
        }

        val ocr = if (request.options.mediaImageOcr || request.options.mediaVideoText) {
            OcrEngine()
        } else {
            null
        }

        var examined = 0
        try {
            examined += scanAudio(matcher, examined)
            examined += scanImages(matcher, examined, if (request.options.mediaImageOcr) ocr else null)
            if (request.options.mediaVideoText) {
                examined += scanVideos(matcher, examined, ocr)
            }
        } finally {
            ocr?.close()
        }

        emit(ScanEvent.Progress(source, examined, null))
        emit(ScanEvent.Finished(source, examined))
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<ScanEvent>.scanAudio(matcher: TextMatcher, offset: Int): Int {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATA,
        )
        val cursor = query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection) ?: return 0

        var examined = 0
        cursor.use {
            val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val nameColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val dataColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (it.moveToNext()) {
                coroutineContext.ensureActive()
                examined++
                if ((offset + examined) % PROGRESS_INTERVAL == 0) {
                    emit(ScanEvent.Progress(source, offset + examined, "audio"))
                }

                val title = it.getString(titleColumn).orEmpty()
                val artist = it.getString(artistColumn).orEmpty()
                val album = it.getString(albumColumn).orEmpty()
                val tags = listOf(title, artist, album).filter { tag -> tag.isNotBlank() }
                val hitTag = tags.firstOrNull { tag -> matcher.matches(tag) } ?: continue

                val path = it.getString(dataColumn).orEmpty()
                emit(
                    ScanEvent.Found(
                        ScanHit(
                            id = "media:audio:$path",
                            source = source,
                            matchKind = MatchKind.MEDIA_TAG,
                            title = it.getString(nameColumn).orEmpty().ifBlank { title },
                            subtitle = tags.joinToString(" · "),
                            snippet = "matched \"$hitTag\"",
                            openTarget = if (path.isNotBlank()) OpenTarget.LocalFile(path) else null,
                            verifyRef = path.ifBlank { null },
                            fingerprint = path.takeIf { p -> p.isNotBlank() }?.let { p -> FileFingerprint.of(File(p)) },
                            exportPath = path.ifBlank { null },
                        ),
                    ),
                )
            }
        }
        return examined
    }

    private suspend fun FlowCollector<ScanEvent>.scanImages(
        matcher: TextMatcher,
        offset: Int,
        ocr: OcrEngine?,
    ): Int {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
        )
        val cursor = query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection) ?: return 0

        var examined = 0
        cursor.use {
            val nameColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dataColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

            while (it.moveToNext()) {
                coroutineContext.ensureActive()
                examined++
                if ((offset + examined) % PROGRESS_INTERVAL == 0) {
                    emit(ScanEvent.Progress(source, offset + examined, "images"))
                }

                val path = it.getString(dataColumn).orEmpty()
                if (path.isBlank()) continue
                val name = it.getString(nameColumn).orEmpty().ifBlank { File(path).name }

                val exif = exifText(path)
                val exifIndex = exif?.let(matcher::indexIn) ?: -1
                if (exif != null && exifIndex >= 0) {
                    emit(ScanEvent.Found(imageHit(path, name, MatchKind.MEDIA_EXIF, matcher.snippet(exif, exifIndex))))
                    continue
                }

                if (ocr != null) {
                    val text = MediaFrames.decodeDownsampled(path)?.let { bitmap ->
                        try {
                            ocr.textIn(bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    val ocrIndex = text?.let(matcher::indexIn) ?: -1
                    if (text != null && ocrIndex >= 0) {
                        emit(
                            ScanEvent.Found(
                                imageHit(path, name, MatchKind.MEDIA_IMAGE_TEXT, matcher.snippet(text, ocrIndex)),
                            ),
                        )
                    }
                }
            }
        }
        return examined
    }

    private fun imageHit(path: String, name: String, kind: MatchKind, snippet: String): ScanHit = ScanHit(
        id = "media:image:${kind.name}:$path",
        source = source,
        matchKind = kind,
        title = name,
        subtitle = File(path).parent.orEmpty(),
        snippet = snippet,
        openTarget = OpenTarget.LocalFile(path),
        verifyRef = path,
        fingerprint = FileFingerprint.of(File(path)),
        exportPath = path,
    )

    private suspend fun FlowCollector<ScanEvent>.scanVideos(
        matcher: TextMatcher,
        offset: Int,
        ocr: OcrEngine?,
    ): Int {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION,
        )
        val cursor = query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection) ?: return 0

        var examined = 0
        cursor.use {
            val nameColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dataColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val durationColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

            while (it.moveToNext()) {
                coroutineContext.ensureActive()
                examined++
                val path = it.getString(dataColumn).orEmpty()
                if (path.isBlank() || !File(path).canRead()) continue

                val name = it.getString(nameColumn).orEmpty().ifBlank { File(path).name }
                emit(ScanEvent.Progress(source, offset + examined, "video: $name"))

                val caption = MediaFrames.readCaptions(path)
                val captionIndex = caption?.let(matcher::indexIn) ?: -1
                if (caption != null && captionIndex >= 0) {
                    emit(ScanEvent.Found(videoHit(path, name, MatchKind.VIDEO_CAPTION, matcher.snippet(caption, captionIndex))))
                    continue
                }

                if (ocr == null) continue
                val durationMs = it.getLong(durationColumn)
                val frameHit = MediaFrames.forEachFrame(path, durationMs) { bitmap ->
                    val text = try {
                        ocr.textIn(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                    val index = text?.let(matcher::indexIn) ?: -1
                    if (text != null && index >= 0) matcher.snippet(text, index) else null
                }
                if (frameHit != null) {
                    emit(ScanEvent.Found(videoHit(path, name, MatchKind.VIDEO_TEXT, frameHit)))
                }
            }
        }
        return examined
    }

    private fun videoHit(path: String, name: String, kind: MatchKind, snippet: String): ScanHit = ScanHit(
        id = "media:video:${kind.name}:$path",
        source = source,
        matchKind = kind,
        title = name,
        subtitle = File(path).parent.orEmpty(),
        snippet = snippet,
        openTarget = OpenTarget.LocalFile(path),
        verifyRef = path,
        fingerprint = FileFingerprint.of(File(path)),
        exportPath = path,
    )

    private fun exifText(path: String): String? = runCatching {
        val exif = ExifInterface(path)
        val parts = EXIF_TAGS.mapNotNull { tag -> exif.getAttribute(tag)?.takeIf { it.isNotBlank() } }
        val latLong = FloatArray(2)
        val gps = if (exif.getLatLong(latLong)) "gps ${latLong[0]},${latLong[1]}" else null
        (parts + listOfNotNull(gps)).joinToString(" · ").ifBlank { null }
    }.getOrNull()

    private fun query(uri: Uri, projection: Array<String>) = try {
        context.contentResolver.query(uri, projection, null, null, null)
    } catch (_: SecurityException) {
        null
    }

    private companion object {
        const val PROGRESS_INTERVAL = 200
        val EXIF_TAGS = listOf(
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_SOFTWARE,
        )
    }
}
