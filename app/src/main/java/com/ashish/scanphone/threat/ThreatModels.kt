package com.ashish.scanphone.threat

import com.ashish.scanphone.model.OpenTarget

enum class ThreatSeverity(val label: String, val rank: Int) {
    LOW("Low", 0),
    MEDIUM("Medium", 1),
    HIGH("High", 2),
    CRITICAL("Critical", 3),
}

enum class ThreatCategory(val label: String) {
    PHISHING("Phishing"),
    SCAM("Scam"),
    SPAM("Spam"),
    FRAUD_NUMBER("Fraud number"),
    RISKY_APP("Risky app"),
    MALICIOUS_FILE("Malicious file"),
    MALWARE_SIGNATURE("Malware signature"),
}

enum class ThreatCheck(val label: String) {
    TEXT("Messages & notifications"),
    NUMBERS("Calls & contacts"),
    APPS("Apps"),
    FILES("Files"),
}

enum class ThreatArea(val label: String) {
    MESSAGES("Messages"),
    NOTIFICATIONS("Notifications"),
    CALLS("Calls"),
    CONTACTS("Contacts"),
    APPS("Apps"),
    FILES("Files"),
}

data class RuleMatch(
    val category: ThreatCategory,
    val severity: ThreatSeverity,
    val reason: String,
)

data class ThreatFinding(
    val id: String,
    val category: ThreatCategory,
    val severity: ThreatSeverity,
    val area: ThreatArea,
    val title: String,
    val subtitle: String,
    val reasons: List<String>,
    val evidence: String?,
    val openTarget: OpenTarget?,
    val deleteRef: String? = null,
    val packageName: String? = null,
) {
    companion object {
        fun from(
            id: String,
            area: ThreatArea,
            title: String,
            subtitle: String,
            evidence: String?,
            openTarget: OpenTarget?,
            matches: List<RuleMatch>,
            deleteRef: String? = null,
            packageName: String? = null,
        ): ThreatFinding? {
            if (matches.isEmpty()) return null
            val top = matches.maxBy { it.severity.rank }
            return ThreatFinding(
                id = id,
                category = top.category,
                severity = top.severity,
                area = area,
                title = title,
                subtitle = subtitle,
                reasons = matches.map { it.reason }.distinct(),
                evidence = evidence,
                openTarget = openTarget,
                deleteRef = deleteRef,
                packageName = packageName,
            )
        }
    }
}

data class ThreatScanOptions(
    val checks: Set<ThreatCheck> = ThreatCheck.entries.toSet(),
)

sealed interface ThreatEvent {
    data class Found(val finding: ThreatFinding) : ThreatEvent

    data class Progress(val area: ThreatArea, val examined: Int, val current: String?) : ThreatEvent

    data class Failed(val area: ThreatArea, val reason: String) : ThreatEvent

    data class Finished(val area: ThreatArea, val examined: Int) : ThreatEvent
}
