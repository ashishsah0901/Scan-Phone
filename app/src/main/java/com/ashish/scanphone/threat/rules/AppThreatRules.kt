package com.ashish.scanphone.threat.rules

import com.ashish.scanphone.threat.RuleMatch
import com.ashish.scanphone.threat.ThreatCategory
import com.ashish.scanphone.threat.ThreatSeverity
import java.util.Locale

data class AppFacts(
    val label: String,
    val packageName: String,
    val installer: String?,
    val isSystem: Boolean,
    val permissions: Set<String>,
    val hasAccessibilityService: Boolean,
    val hasDeviceAdmin: Boolean,
)

object AppThreatRules {

    fun analyze(facts: AppFacts): List<RuleMatch> {
        if (facts.isSystem) return emptyList()
        val matches = ArrayList<RuleMatch>()
        val perms = facts.permissions
        val sideloaded = facts.installer == null || facts.installer !in TRUSTED_INSTALLERS

        val impersonated = impersonatedBrand(facts)
        if (impersonated != null) {
            matches += RuleMatch(
                ThreatCategory.RISKY_APP,
                ThreatSeverity.CRITICAL,
                "Name impersonates \"$impersonated\" but package is ${facts.packageName}",
            )
        }

        val smsTrojan = (perms.contains(READ_SMS) || perms.contains(RECEIVE_SMS)) &&
            perms.contains(INTERNET) &&
            (perms.contains(SEND_SMS) || perms.contains(RECEIVE_BOOT))
        if (smsTrojan) {
            matches += RuleMatch(
                ThreatCategory.RISKY_APP,
                if (sideloaded) ThreatSeverity.HIGH else ThreatSeverity.MEDIUM,
                "Reads/sends SMS and has internet access (SMS-trojan pattern)",
            )
        }

        if (facts.hasAccessibilityService && perms.contains(OVERLAY)) {
            matches += RuleMatch(
                ThreatCategory.RISKY_APP,
                ThreatSeverity.HIGH,
                "Accessibility service + screen overlay (overlay-attack pattern)",
            )
        }

        if (facts.hasDeviceAdmin && sideloaded) {
            matches += RuleMatch(
                ThreatCategory.RISKY_APP,
                ThreatSeverity.HIGH,
                "Requests device-admin control and was sideloaded",
            )
        }

        if (perms.contains(INSTALL_PACKAGES) && perms.contains(INTERNET) && sideloaded) {
            matches += RuleMatch(
                ThreatCategory.RISKY_APP,
                ThreatSeverity.MEDIUM,
                "Can download and install other apps",
            )
        }

        if (matches.isEmpty() && sideloaded) {
            val dangerous = perms.count { it in DANGEROUS_PERMISSIONS }
            if (dangerous >= DANGEROUS_THRESHOLD) {
                matches += RuleMatch(
                    ThreatCategory.RISKY_APP,
                    ThreatSeverity.LOW,
                    "Sideloaded app requesting $dangerous sensitive permissions",
                )
            }
        }

        return matches
    }

    private fun impersonatedBrand(facts: AppFacts): String? {
        val label = facts.label.lowercase(Locale.US)
        for ((brand, officialPackages) in BRAND_PACKAGES) {
            if (label.contains(brand) && officialPackages.none { facts.packageName.equals(it, ignoreCase = true) }) {
                return brand.replaceFirstChar { it.uppercase() }
            }
        }
        return null
    }

    private const val READ_SMS = "android.permission.READ_SMS"
    private const val RECEIVE_SMS = "android.permission.RECEIVE_SMS"
    private const val SEND_SMS = "android.permission.SEND_SMS"
    private const val INTERNET = "android.permission.INTERNET"
    private const val RECEIVE_BOOT = "android.permission.RECEIVE_BOOT_COMPLETED"
    private const val OVERLAY = "android.permission.SYSTEM_ALERT_WINDOW"
    private const val INSTALL_PACKAGES = "android.permission.REQUEST_INSTALL_PACKAGES"

    private const val DANGEROUS_THRESHOLD = 4

    private val TRUSTED_INSTALLERS = setOf(
        "com.android.vending",
        "com.google.android.packageinstaller",
        "com.google.android.feedback",
        "com.samsung.android.galaxyapps",
        "com.amazon.venezia",
    )

    private val DANGEROUS_PERMISSIONS = setOf(
        READ_SMS, RECEIVE_SMS, SEND_SMS, OVERLAY, INSTALL_PACKAGES,
        "android.permission.READ_CONTACTS",
        "android.permission.READ_CALL_LOG",
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.READ_PHONE_STATE",
    )

    private val BRAND_PACKAGES: Map<String, List<String>> = mapOf(
        "hdfc" to listOf("com.snapwork.hdfc"),
        "icici" to listOf("com.csam.icici.bank.imobile"),
        "sbi" to listOf("com.sbi.lotusintouch", "com.sbi.SBIFreedomPlus"),
        "axis" to listOf("com.axis.mobile"),
        "paytm" to listOf("net.one97.paytm"),
        "phonepe" to listOf("com.phonepe.app"),
        "paypal" to listOf("com.paypal.android.p2pmobile"),
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
    )
}
