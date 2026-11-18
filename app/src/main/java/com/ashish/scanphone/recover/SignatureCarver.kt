package com.ashish.scanphone.recover

import com.ashish.scanphone.scan.RootShell
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.coroutines.coroutineContext

data class CarvedFile(val file: File, val type: String)

class SignatureCarver(private val outputDir: File) {

    private data class Signature(
        val type: String,
        val extension: String,
        val start: ByteArray,
        val end: ByteArray?,
        val maxBytes: Int,
        val leadIn: Int = 0,
    )

    suspend fun carve(
        sourceCommand: String,
        maxScanBytes: Long,
        maxFiles: Int,
        onProgress: suspend (Long, Int) -> Unit = { _, _ -> },
        onCarved: suspend (CarvedFile) -> Unit,
    ): Int {
        outputDir.mkdirs()
        val process = RootShell.start(sourceCommand) ?: return 0
        val disposable = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { process.destroy() }
        return try {
            carveStream(process.inputStream, maxScanBytes, maxFiles, onProgress, onCarved)
        } finally {
            disposable?.dispose()
            process.destroy()
        }
    }

    suspend fun carveStream(
        input: InputStream,
        maxScanBytes: Long,
        maxFiles: Int,
        onProgress: suspend (Long, Int) -> Unit = { _, _ -> },
        onCarved: suspend (CarvedFile) -> Unit,
    ): Int {
        outputDir.mkdirs()

        var carved = 0
        val ring = ByteArray(RING)
        var ringLen = 0
        var capturing: Signature? = null
        var out: ByteArrayOutputStream? = null
        val capTail = ByteArray(END_TAIL)
        var capTailLen = 0
        var scanned = 0L
        var nextProgress = PROGRESS_STEP

        run {
            val buffer = ByteArray(CHUNK)
            input@ while (scanned < maxScanBytes && carved < maxFiles) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                scanned += read
                if (scanned >= nextProgress) {
                    nextProgress = scanned + PROGRESS_STEP
                    onProgress(scanned, carved)
                }

                for (index in 0 until read) {
                    val byte = buffer[index]
                    val current = capturing
                    if (current == null) {
                        ring[ringLen % RING] = byte
                        ringLen++
                        val match = matchStart(ring, ringLen)
                        if (match != null) {
                            capturing = match
                            out = ByteArrayOutputStream(INITIAL_CAPACITY)
                            capTailLen = 0
                            writeLeadIn(out!!, ring, ringLen, match)
                        }
                    } else {
                        val stream = out!!
                        stream.write(byte.toInt())
                        capTail[capTailLen % END_TAIL] = byte
                        capTailLen++

                        val end = current.end
                        val done = when {
                            end != null && endMatches(capTail, capTailLen, end) -> true
                            stream.size() >= current.maxBytes -> true
                            else -> false
                        }
                        if (done) {
                            val file = writeCarved(stream.toByteArray(), current)
                            if (file != null) {
                                carved++
                                onCarved(CarvedFile(file, current.type))
                            }
                            capturing = null
                            out = null
                            ringLen = 0
                            if (carved >= maxFiles) break@input
                        }
                    }
                }
            }
        }
        onProgress(scanned, carved)
        return carved
    }

    private fun matchStart(ring: ByteArray, ringLen: Int): Signature? {
        for (signature in SIGNATURES) {
            val marker = signature.start
            if (ringLen < marker.size) continue
            var ok = true
            for (offset in marker.indices) {
                val ringIndex = (ringLen - marker.size + offset) % RING
                if (ring[ringIndex] != marker[offset]) {
                    ok = false
                    break
                }
            }
            if (ok) return signature
        }
        return null
    }

    private fun writeLeadIn(out: ByteArrayOutputStream, ring: ByteArray, ringLen: Int, sig: Signature) {
        val total = sig.leadIn + sig.start.size
        val available = minOf(total, ringLen, RING)
        for (offset in 0 until available) {
            val ringIndex = (ringLen - available + offset) % RING
            out.write(ring[ringIndex].toInt())
        }
    }

    private fun endMatches(capTail: ByteArray, capTailLen: Int, end: ByteArray): Boolean {
        if (capTailLen < end.size) return false
        for (offset in end.indices) {
            val index = (capTailLen - end.size + offset) % END_TAIL
            if (capTail[index] != end[offset]) return false
        }
        return true
    }

    private fun writeCarved(bytes: ByteArray, sig: Signature): File? {
        if (bytes.size < MIN_BYTES) return null
        return runCatching {
            val file = File(outputDir, "carved_${System.nanoTime()}.${sig.extension}")
            file.writeBytes(bytes)
            file
        }.getOrNull()
    }

    private companion object {
        const val CHUNK = 256 * 1024
        const val RING = 16
        const val END_TAIL = 16
        const val INITIAL_CAPACITY = 256 * 1024
        const val MIN_BYTES = 1024
        const val PROGRESS_STEP = 32L * 1024 * 1024

        val SIGNATURES = listOf(
            Signature(
                type = "JPEG image",
                extension = "jpg",
                start = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
                end = byteArrayOf(0xFF.toByte(), 0xD9.toByte()),
                maxBytes = 30 * 1024 * 1024,
            ),
            Signature(
                type = "PNG image",
                extension = "png",
                start = byteArrayOf(
                    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                ),
                end = byteArrayOf(0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte()),
                maxBytes = 60 * 1024 * 1024,
            ),
            Signature(
                type = "PDF document",
                extension = "pdf",
                start = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D),
                end = byteArrayOf(0x25, 0x25, 0x45, 0x4F, 0x46),
                maxBytes = 60 * 1024 * 1024,
            ),
            Signature(
                type = "GIF image",
                extension = "gif",
                start = byteArrayOf(0x47, 0x49, 0x46, 0x38),
                end = byteArrayOf(0x00, 0x3B),
                maxBytes = 20 * 1024 * 1024,
            ),
            Signature(
                type = "MP4 video",
                extension = "mp4",
                start = byteArrayOf(0x66, 0x74, 0x79, 0x70),
                end = null,
                maxBytes = 80 * 1024 * 1024,
                leadIn = 4,
            ),
        )
    }
}
