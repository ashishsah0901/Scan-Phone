package com.ashish.scanphone.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

object MediaFrames {

    fun decodeDownsampled(path: String): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        if (largest <= 0) return null
        var sample = 1
        while (largest / sample > MAX_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(path, options)
    }.getOrNull()

    fun readCaptions(path: String): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            val builder = StringBuilder()
            for (track in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME).orEmpty()
                if (!isTextTrack(mime)) continue
                extractor.selectTrack(track)
                val buffer = ByteBuffer.allocate(MAX_SAMPLE_BYTES)
                while (builder.length < MAX_CAPTION_CHARS) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size <= 0) break
                    val bytes = ByteArray(size)
                    buffer.get(bytes, 0, size)
                    builder.append(String(bytes, Charsets.UTF_8)).append(' ')
                    if (!extractor.advance()) break
                }
                extractor.unselectTrack(track)
            }
            builder.toString().trim().ifBlank { null }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    suspend fun forEachFrame(
        path: String,
        durationMs: Long,
        onFrame: suspend (Bitmap) -> String?,
    ): String? {
        if (durationMs <= 0) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val stepMs = maxOf(durationMs / (MAX_FRAMES + 1), MIN_STEP_MS)
            var timeMs = stepMs
            var count = 0
            while (timeMs < durationMs && count < MAX_FRAMES) {
                coroutineContext.ensureActive()
                count++
                val bitmap = frameAt(retriever, timeMs * 1000)
                if (bitmap != null) {
                    val hit = onFrame(bitmap)
                    if (hit != null) return hit
                }
                timeMs += stepMs
            }
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun frameAt(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                MAX_DIMENSION,
                MAX_DIMENSION,
            )
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    }.getOrNull()

    private fun isTextTrack(mime: String): Boolean =
        mime.startsWith("text/") ||
            mime == "application/x-subrip" ||
            mime == "application/ttml+xml" ||
            mime == "application/x-quicktime-tx3g"

    private const val MAX_DIMENSION = 1280
    private const val MAX_FRAMES = 12
    private const val MIN_STEP_MS = 3000L
    private const val MAX_CAPTION_CHARS = 200_000
    private const val MAX_SAMPLE_BYTES = 256 * 1024
}
