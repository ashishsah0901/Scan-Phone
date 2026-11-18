package com.ashish.scanphone.export

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.ashish.scanphone.model.ScanHit
import com.ashish.scanphone.scan.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface ExportResult {
    data class Success(val displayName: String) : ExportResult

    data class Failure(val reason: String) : ExportResult
}

class Exporter(private val context: Context) {

    suspend fun exportFile(hit: ScanHit): ExportResult = withContext(Dispatchers.IO) {
        val path = hit.exportPath ?: return@withContext ExportResult.Failure("This result has no file to export")
        val name = path.substringAfterLast('/')
        try {
            val bytes = if (hit.exportViaRoot) {
                readViaRoot(path) ?: return@withContext ExportResult.Failure("Root read failed")
            } else {
                val file = File(path)
                if (!file.canRead()) return@withContext ExportResult.Failure("Cannot read the file")
                file.readBytes()
            }
            writeToDownloads(name, mimeFor(name), bytes)
        } catch (error: Exception) {
            ExportResult.Failure(error.message ?: "Export failed")
        }
    }

    suspend fun exportResults(hits: List<ScanHit>, asJson: Boolean): ExportResult =
        withContext(Dispatchers.IO) {
            if (hits.isEmpty()) return@withContext ExportResult.Failure("No results to export")
            val stamp = FILE_STAMP.format(Date())
            try {
                if (asJson) {
                    writeToDownloads("scan_results_$stamp.json", "application/json", toJson(hits).toByteArray())
                } else {
                    writeToDownloads("scan_results_$stamp.csv", "text/csv", toCsv(hits).toByteArray())
                }
            } catch (error: Exception) {
                ExportResult.Failure(error.message ?: "Export failed")
            }
        }

    private fun readViaRoot(path: String): ByteArray? {
        val temp = File(context.cacheDir, "export_${path.hashCode()}")
        val copied = RootShell.readOutput(
            "cat ${RootShell.quote(path)} > ${RootShell.quote(temp.absolutePath)} && echo ok",
        )
        return if (copied == "ok" && temp.exists()) {
            temp.readBytes().also { temp.delete() }
        } else {
            temp.delete()
            null
        }
    }

    private fun writeToDownloads(name: String, mime: String, bytes: ByteArray): ExportResult {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ScanPhone")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return ExportResult.Failure("Could not create the download")

        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: return ExportResult.Failure("Could not open the download for writing")

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return ExportResult.Success("Downloads/ScanPhone/$name")
    }

    private fun toJson(hits: List<ScanHit>): String = buildString {
        append("[")
        hits.forEachIndexed { index, hit ->
            if (index > 0) append(",")
            append("{")
            append("\"source\":\"${hit.source.label}\",")
            append("\"match\":\"${hit.matchKind.label}\",")
            append("\"title\":${quote(hit.title)},")
            append("\"subtitle\":${quote(hit.subtitle)},")
            append("\"snippet\":${quote(hit.snippet.orEmpty())}")
            append("}")
        }
        append("]")
    }

    private fun toCsv(hits: List<ScanHit>): String = buildString {
        append("source,match,title,subtitle,snippet\n")
        hits.forEach { hit ->
            append(csvCell(hit.source.label)).append(',')
            append(csvCell(hit.matchKind.label)).append(',')
            append(csvCell(hit.title)).append(',')
            append(csvCell(hit.subtitle)).append(',')
            append(csvCell(hit.snippet.orEmpty())).append('\n')
        }
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", " ").replace("\r", " ") + "\""

    private fun csvCell(value: String): String =
        "\"" + value.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ") + "\""

    private fun mimeFor(name: String): String =
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    private companion object {
        val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
