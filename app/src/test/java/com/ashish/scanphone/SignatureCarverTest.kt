package com.ashish.scanphone

import com.ashish.scanphone.recover.CarvedFile
import com.ashish.scanphone.recover.SignatureCarver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SignatureCarverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun jpeg(payloadSize: Int): ByteArray {
        val head = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val body = ByteArray(payloadSize) { 0x41 }
        val tail = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        return head + body + tail
    }

    @Test
    fun carvesJpegEmbeddedInNoise() = runTest {
        val jpeg = jpeg(4096)
        val noise = ByteArray(5000) { 0x00 }
        val stream = (noise + jpeg + noise).inputStream()

        val carver = SignatureCarver(tempFolder.newFolder("out"))
        val carved = ArrayList<CarvedFile>()
        val count = carver.carveStream(stream, maxScanBytes = 1_000_000, maxFiles = 10) { carved.add(it) }

        assertEquals(1, count)
        assertEquals("JPEG image", carved.first().type)
        val bytes = carved.first().file.readBytes()
        assertEquals(jpeg.size, bytes.size)
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
        assertEquals(0xD9.toByte(), bytes.last())
    }

    @Test
    fun carvesMultipleFilesAndHonoursTheFileCap() = runTest {
        val stream = (jpeg(2000) + ByteArray(100) + jpeg(2000) + ByteArray(100) + jpeg(2000)).inputStream()
        val carver = SignatureCarver(tempFolder.newFolder("out"))
        var seen = 0
        val count = carver.carveStream(stream, maxScanBytes = 1_000_000, maxFiles = 2) { seen++ }
        assertEquals(2, count)
        assertEquals(2, seen)
    }

    @Test
    fun ignoresStreamsWithoutSignatures() = runTest {
        val stream = ByteArray(20_000) { 0x2A }.inputStream()
        val carver = SignatureCarver(tempFolder.newFolder("out"))
        val count = carver.carveStream(stream, maxScanBytes = 1_000_000, maxFiles = 10) { }
        assertEquals(0, count)
    }

    @Test
    fun carvesSignatureSpanningChunkBoundary() = runTest {
        val prefix = ByteArray((256 * 1024) - 1) { 0x00 }
        val stream = (prefix + jpeg(1024)).inputStream()
        val carver = SignatureCarver(tempFolder.newFolder("out"))
        var seen = 0
        val count = carver.carveStream(stream, maxScanBytes = 2_000_000, maxFiles = 10) { seen++ }
        assertTrue(count == 1)
        assertEquals(1, seen)
    }
}
