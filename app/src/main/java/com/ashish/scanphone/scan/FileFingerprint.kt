package com.ashish.scanphone.scan

import java.io.File

object FileFingerprint {

    fun of(file: File): String = "${file.length()}:${file.lastModified()}"
}
