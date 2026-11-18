package com.ashish.scanphone.threat.rules

import com.ashish.scanphone.threat.RuleMatch
import com.ashish.scanphone.threat.ThreatCategory
import com.ashish.scanphone.threat.ThreatSeverity
import java.util.Locale

object FileThreatRules {

    const val EICAR_SIGNATURE =
        "X5O!P%@AP[4\\PZX54(P^)7CC)7}\$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!\$H+H*"

    fun analyzeName(name: String, parentPath: String): List<RuleMatch> {
        val lower = name.lowercase(Locale.US)
        val extension = lower.substringAfterLast('.', "")
        val matches = ArrayList<RuleMatch>()

        val doubleExtension = DOUBLE_EXTENSION_REGEX.find(lower)
        if (doubleExtension != null && extension in DANGEROUS_EXTENSIONS) {
            matches += RuleMatch(
                ThreatCategory.MALICIOUS_FILE,
                ThreatSeverity.HIGH,
                "Disguised double extension (.${doubleExtension.groupValues[1]}.$extension)",
            )
        }

        if (extension in DANGEROUS_EXTENSIONS) {
            val inDownload = DOWNLOAD_HINTS.any { parentPath.lowercase(Locale.US).contains(it) }
            val severity = if (inDownload) ThreatSeverity.MEDIUM else ThreatSeverity.LOW
            val where = if (inDownload) " in a downloads/chat folder" else ""
            matches += RuleMatch(
                ThreatCategory.MALICIOUS_FILE,
                severity,
                "Executable/installer file (.$extension)$where",
            )
        }

        if (name.startsWith('.') && extension in DANGEROUS_EXTENSIONS) {
            matches += RuleMatch(
                ThreatCategory.MALICIOUS_FILE,
                ThreatSeverity.MEDIUM,
                "Hidden executable file",
            )
        }

        return matches
    }

    fun analyzeContent(content: String): List<RuleMatch> {
        val matches = ArrayList<RuleMatch>()
        if (content.contains(EICAR_SIGNATURE)) {
            matches += RuleMatch(
                ThreatCategory.MALWARE_SIGNATURE,
                ThreatSeverity.CRITICAL,
                "EICAR anti-virus test signature",
            )
        }
        TextThreatRules.analyze(content)
            .filter { it.category == ThreatCategory.PHISHING }
            .forEach { matches += it.copy(reason = "Phishing content: ${it.reason}") }
        return matches
    }

    fun shouldReadContent(name: String, sizeBytes: Long): Boolean {
        if (sizeBytes !in 1..MAX_CONTENT_BYTES) return false
        val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
        return extension in TEXT_EXTENSIONS || sizeBytes <= SMALL_FILE_BYTES
    }

    private const val MAX_CONTENT_BYTES = 2L * 1024 * 1024
    private const val SMALL_FILE_BYTES = 4L * 1024

    val DANGEROUS_EXTENSIONS = setOf(
        "apk", "apkm", "xapk", "exe", "dex", "elf", "jar", "bat", "cmd", "sh", "ps1", "scr",
        "vbs", "msi", "com", "bin", "run", "app",
    )

    private val TEXT_EXTENSIONS = setOf(
        "txt", "log", "md", "json", "csv", "xml", "html", "htm", "js", "vcf", "eml", "ini", "cfg",
    )

    private val DOWNLOAD_HINTS = listOf("download", "telegram", "whatsapp", "bluetooth", "/tmp")

    private val DOUBLE_EXTENSION_REGEX = Regex("\\.(pdf|jpg|jpeg|png|doc|docx|xls|xlsx|mp4|mp3|txt)\\.[a-z0-9]+$")
}
