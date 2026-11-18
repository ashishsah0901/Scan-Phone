package com.ashish.scanphone

import com.ashish.scanphone.scan.BinaryText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryTextTest {

    @Test
    fun findsTextRunsBetweenBinaryNoise() {
        val payload = byteArrayOf(0x00, 0x01, 0x02) +
            "https://example.com/track".toByteArray() +
            byteArrayOf(0x00, 0x03)

        val runs = collectRuns(payload)
        assertTrue(runs.any { it.contains("https://example.com/track") })
    }

    @Test
    fun dropsRunsShorterThanTheMinimum() {
        val runs = collectRuns(byteArrayOf(0x00, 'a'.code.toByte(), 0x00, 'b'.code.toByte(), 0x00))
        assertTrue(runs.isEmpty())
    }

    @Test
    fun stopsEarlyWhenTheCallbackReturnsTrue() {
        val payload = "first-token".toByteArray() + byteArrayOf(0x00) + "second-token".toByteArray()
        val seen = mutableListOf<String>()
        BinaryText.forEachRun(payload.inputStream(), payload.size.toLong()) { run ->
            seen += run
            true
        }
        assertTrue(seen.size == 1)
        assertFalse(seen.first().contains("second-token"))
    }

    private fun collectRuns(payload: ByteArray): List<String> {
        val runs = mutableListOf<String>()
        BinaryText.forEachRun(payload.inputStream(), payload.size.toLong()) { run ->
            runs += run
            false
        }
        return runs
    }
}
