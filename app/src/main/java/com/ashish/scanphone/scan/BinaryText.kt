package com.ashish.scanphone.scan

import java.io.ByteArrayOutputStream
import java.io.InputStream

object BinaryText {

    fun forEachRun(input: InputStream, maxBytes: Long, onRun: (String) -> Boolean) {
        val buffer = ByteArray(BUFFER_SIZE)
        val run = ByteArrayOutputStream(MAX_RUN_BYTES)
        var consumed = 0L

        while (consumed < maxBytes) {
            val read = input.read(buffer)
            if (read <= 0) break
            consumed += read

            for (index in 0 until read) {
                val byte = buffer[index]
                if (isTextByte(byte)) {
                    run.write(byte.toInt())
                    if (run.size() >= MAX_RUN_BYTES && emit(run, onRun, keepTail = true)) return
                } else if (emit(run, onRun, keepTail = false)) {
                    return
                }
            }
        }

        emit(run, onRun, keepTail = false)
    }

    private fun emit(
        run: ByteArrayOutputStream,
        onRun: (String) -> Boolean,
        keepTail: Boolean,
    ): Boolean {
        if (run.size() < MIN_RUN_BYTES) {
            run.reset()
            return false
        }
        val bytes = run.toByteArray()
        run.reset()
        if (keepTail) run.write(bytes, (bytes.size - OVERLAP_BYTES).coerceAtLeast(0), minOf(OVERLAP_BYTES, bytes.size))
        return onRun(String(bytes, Charsets.UTF_8))
    }

    private fun isTextByte(byte: Byte): Boolean {
        val value = byte.toInt() and 0xFF
        return value == TAB || value in PRINTABLE_START..PRINTABLE_END || value >= HIGH_BYTE_START
    }

    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_RUN_BYTES = 8 * 1024
    private const val MIN_RUN_BYTES = 3
    private const val OVERLAP_BYTES = 128
    private const val TAB = 0x09
    private const val PRINTABLE_START = 0x20
    private const val PRINTABLE_END = 0x7E
    private const val HIGH_BYTE_START = 0x80
}
