package com.ashish.scanphone.scan

import java.io.Reader

object RootShell {

    fun isAvailable(): Boolean {
        val process = start("id") ?: return false
        return try {
            val output = process.inputStream.bufferedReader().use(Reader::readText)
            process.waitFor()
            output.contains("uid=0")
        } catch (_: Exception) {
            false
        } finally {
            process.destroy()
        }
    }

    fun start(command: String): Process? = try {
        ProcessBuilder("su", "-c", command).start()
    } catch (_: Exception) {
        null
    }

    fun readOutput(command: String): String? {
        val process = start(command) ?: return null
        return try {
            val output = process.inputStream.bufferedReader().use(Reader::readText)
            process.waitFor()
            output.trim()
        } catch (_: Exception) {
            null
        } finally {
            process.destroy()
        }
    }

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
