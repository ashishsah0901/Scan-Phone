package com.ashish.scanphone.threat.rules

import com.ashish.scanphone.threat.RuleMatch
import com.ashish.scanphone.threat.ThreatCategory
import com.ashish.scanphone.threat.ThreatSeverity

object NumberThreatRules {

    fun analyze(rawNumber: String): List<RuleMatch> {
        val number = rawNumber.trim()
        if (number.isBlank()) return emptyList()
        val matches = ArrayList<RuleMatch>()

        val hasLetters = number.any { it.isLetter() }
        if (hasLetters) {
            val header = number.substringBefore('-').uppercase()
            if (PROMO_HEADER_PREFIXES.any { header.startsWith(it) }) {
                matches += RuleMatch(
                    ThreatCategory.SPAM,
                    ThreatSeverity.LOW,
                    "Promotional sender ID ($number)",
                )
            }
            return matches
        }

        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) return matches

        if (PREMIUM_PATTERNS.any { digits.contains(it) }) {
            matches += RuleMatch(
                ThreatCategory.FRAUD_NUMBER,
                ThreatSeverity.MEDIUM,
                "Premium-rate / high-cost number pattern",
            )
        }

        val national = digits.trimStart('0').let { if (it.length > 10) it.takeLast(10) else it }
        if (national.length in 1..5) {
            matches += RuleMatch(
                ThreatCategory.SPAM,
                ThreatSeverity.LOW,
                "Short-code sender (${national.length} digits)",
            )
        }

        if (digits.length >= 6 && digits.toSet().size == 1) {
            matches += RuleMatch(
                ThreatCategory.FRAUD_NUMBER,
                ThreatSeverity.MEDIUM,
                "Repeated-digit spoof number",
            )
        }

        return matches
    }

    private val PROMO_HEADER_PREFIXES = listOf("AD", "VM", "VK", "TM", "BP", "BZ", "BT", "LM", "DM")

    private val PREMIUM_PATTERNS = listOf("1900", "1901", "1902", "0900", "976")
}
