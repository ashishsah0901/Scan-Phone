package com.ashish.scanphone.threat.rules

import com.ashish.scanphone.threat.RuleMatch
import com.ashish.scanphone.threat.ThreatCategory
import com.ashish.scanphone.threat.ThreatSeverity

object TextThreatRules {

    fun analyze(text: String, sender: String? = null): List<RuleMatch> {
        if (text.isBlank()) return emptyList()
        val lower = text.lowercase()
        val matches = ArrayList<RuleMatch>()

        val urls = URL_REGEX.findAll(text).map { it.value }.toList()
        val suspiciousUrl = urls.firstOrNull { isSuspiciousUrl(it) }
        val hasCredentialAsk = CREDENTIAL_TERMS.any { lower.contains(it) }

        if (suspiciousUrl != null) {
            val severity = if (hasCredentialAsk) ThreatSeverity.CRITICAL else ThreatSeverity.HIGH
            matches += RuleMatch(ThreatCategory.PHISHING, severity, "Suspicious link: $suspiciousUrl")
        } else if (urls.isNotEmpty() && hasCredentialAsk) {
            matches += RuleMatch(
                ThreatCategory.PHISHING,
                ThreatSeverity.HIGH,
                "Link asks for credentials or verification",
            )
        }

        for ((label, terms, category, severity) in SCAM_RULES) {
            if (terms.any { lower.contains(it) }) {
                matches += RuleMatch(category, severity, label)
            }
        }

        val urgencyHit = URGENCY_TERMS.count { lower.contains(it) }
        if (urgencyHit > 0 && matches.isNotEmpty()) {
            matches += RuleMatch(ThreatCategory.SCAM, ThreatSeverity.MEDIUM, "Urgency / pressure language")
        }

        if (matches.isEmpty() && PROMO_TERMS.any { lower.contains(it) }) {
            matches += RuleMatch(ThreatCategory.SPAM, ThreatSeverity.LOW, "Promotional / bulk-message language")
        }

        return matches
    }

    private fun isSuspiciousUrl(url: String): Boolean {
        val lower = url.lowercase()
        val host = lower.substringAfter("://").substringBefore('/').substringBefore('?')
        return SHORTENERS.any { host == it || host.endsWith(".$it") } ||
            SUSPICIOUS_TLDS.any { host.endsWith(it) } ||
            host.startsWith("xn--") || host.contains(".xn--") ||
            IP_HOST_REGEX.matches(host) ||
            lower.contains("@") ||
            (lower.startsWith("http://") && !host.endsWith("localhost")) ||
            LOOKALIKE_BRANDS.any { brand -> host.contains(brand) && !host.endsWith(officialSuffix(brand)) }
    }

    private fun officialSuffix(brand: String): String = when (brand) {
        "paypal" -> "paypal.com"
        "amazon" -> "amazon.com"
        "apple" -> "apple.com"
        "netflix" -> "netflix.com"
        "whatsapp" -> "whatsapp.com"
        else -> "$brand.com"
    }

    private data class ScamRule(
        val label: String,
        val terms: List<String>,
        val category: ThreatCategory,
        val severity: ThreatSeverity,
    )

    private val SCAM_RULES = listOf(
        ScamRule(
            "Account / KYC verification scam",
            listOf("kyc", "verify your account", "account suspended", "account blocked", "account will be blocked", "update your pan", "re-kyc", "account has been locked"),
            ThreatCategory.PHISHING,
            ThreatSeverity.HIGH,
        ),
        ScamRule(
            "OTP / PIN harvesting",
            listOf("enter otp", "provide otp", "share your otp", "confirm your otp", "enter your pin", "share the code"),
            ThreatCategory.PHISHING,
            ThreatSeverity.CRITICAL,
        ),
        ScamRule(
            "Lottery / prize scam",
            listOf("you have won", "you've won", "lottery", "lucky draw", "claim your prize", "claim your reward", "won a prize", "congratulations you"),
            ThreatCategory.SCAM,
            ThreatSeverity.HIGH,
        ),
        ScamRule(
            "Refund / tax scam",
            listOf("income tax refund", "gst refund", "tax refund", "refund of", "claim your refund"),
            ThreatCategory.SCAM,
            ThreatSeverity.MEDIUM,
        ),
        ScamRule(
            "Courier / parcel scam",
            listOf("parcel", "customs", "delivery failed", "redelivery", "package is held", "shipment on hold", "pay customs"),
            ThreatCategory.SCAM,
            ThreatSeverity.MEDIUM,
        ),
        ScamRule(
            "Job / earning scam",
            listOf("work from home", "earn daily", "part time job", "earn per day", "no investment job", "earn from home"),
            ThreatCategory.SCAM,
            ThreatSeverity.MEDIUM,
        ),
        ScamRule(
            "Loan scam",
            listOf("instant loan", "pre-approved loan", "loan approved", "guaranteed loan", "quick loan"),
            ThreatCategory.SCAM,
            ThreatSeverity.MEDIUM,
        ),
        ScamRule(
            "Investment / crypto scam",
            listOf("guaranteed returns", "double your money", "investment opportunity", "high returns", "crypto profit"),
            ThreatCategory.SCAM,
            ThreatSeverity.HIGH,
        ),
        ScamRule(
            "Threat / extortion",
            listOf("legal action will be taken", "arrest warrant", "police case", "your number will be blocked", "court notice"),
            ThreatCategory.SCAM,
            ThreatSeverity.HIGH,
        ),
    )

    private val CREDENTIAL_TERMS = listOf(
        "password", "otp", "cvv", "card number", "pin", "login", "verify", "confirm your identity",
        "update your details", "net banking", "upi pin",
    )

    private val URGENCY_TERMS = listOf(
        "urgent", "immediately", "within 24 hours", "act now", "last warning", "expires today",
        "final notice", "right now",
    )

    private val PROMO_TERMS = listOf(
        "sale", "discount", "offer", "buy now", "limited time", "cashback", "coupon", "% off",
        "free recharge",
    )

    private val SHORTENERS = listOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly", "rebrand.ly",
        "cutt.ly", "shorturl.at", "rb.gy",
    )

    private val SUSPICIOUS_TLDS = listOf(
        ".zip", ".top", ".xyz", ".club", ".link", ".work", ".loan", ".gq", ".tk", ".ml", ".cf",
        ".rest", ".country", ".click",
    )

    private val LOOKALIKE_BRANDS = listOf(
        "paypal", "amazon", "apple", "netflix", "whatsapp", "sbi", "hdfc", "icici", "axis", "paytm",
    )

    private val URL_REGEX = Regex("(?i)\\b(?:https?://|www\\.)[^\\s]+")
    private val IP_HOST_REGEX = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
}
