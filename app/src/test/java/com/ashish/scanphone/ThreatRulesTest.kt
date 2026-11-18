package com.ashish.scanphone

import com.ashish.scanphone.threat.ThreatCategory
import com.ashish.scanphone.threat.ThreatSeverity
import com.ashish.scanphone.threat.rules.AppFacts
import com.ashish.scanphone.threat.rules.AppThreatRules
import com.ashish.scanphone.threat.rules.FileThreatRules
import com.ashish.scanphone.threat.rules.NumberThreatRules
import com.ashish.scanphone.threat.rules.TextThreatRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreatRulesTest {

    @Test
    fun flagsPhishingLinkAskingForOtp() {
        val matches = TextThreatRules.analyze(
            "Your account is blocked. Verify KYC and enter OTP at http://sbi-verify.top/login",
        )
        assertTrue(matches.any { it.category == ThreatCategory.PHISHING })
        assertEquals(ThreatSeverity.CRITICAL, matches.maxByOrNull { it.severity.rank }!!.severity)
    }

    @Test
    fun flagsLotteryScam() {
        val matches = TextThreatRules.analyze("Congratulations you have won a lottery! Claim your prize now.")
        assertTrue(matches.any { it.category == ThreatCategory.SCAM })
    }

    @Test
    fun leavesOrdinaryMessageClean() {
        val matches = TextThreatRules.analyze("Hey, are we still meeting for lunch at 1pm?")
        assertTrue(matches.isEmpty())
    }

    @Test
    fun flagsShortenerLink() {
        val matches = TextThreatRules.analyze("check this out https://bit.ly/abc123")
        assertTrue(matches.any { it.category == ThreatCategory.PHISHING })
    }

    @Test
    fun flagsPromotionalSenderId() {
        val matches = NumberThreatRules.analyze("AD-OFFERS")
        assertTrue(matches.any { it.category == ThreatCategory.SPAM })
    }

    @Test
    fun ignoresNormalMobileNumber() {
        assertTrue(NumberThreatRules.analyze("+91 98765 43210").isEmpty())
    }

    @Test
    fun flagsDisguisedDoubleExtension() {
        val matches = FileThreatRules.analyzeName("invoice.pdf.apk", "/sdcard/Download")
        assertTrue(matches.any { it.severity == ThreatSeverity.HIGH })
    }

    @Test
    fun flagsEicarSignature() {
        val matches = FileThreatRules.analyzeContent("prefix ${FileThreatRules.EICAR_SIGNATURE} suffix")
        assertTrue(matches.any { it.category == ThreatCategory.MALWARE_SIGNATURE })
        assertEquals(ThreatSeverity.CRITICAL, matches.first { it.category == ThreatCategory.MALWARE_SIGNATURE }.severity)
    }

    @Test
    fun flagsBankImpersonationApp() {
        val facts = AppFacts(
            label = "HDFC Bank Rewards",
            packageName = "com.evil.fake",
            installer = null,
            isSystem = false,
            permissions = setOf("android.permission.READ_SMS", "android.permission.INTERNET"),
            hasAccessibilityService = false,
            hasDeviceAdmin = false,
        )
        val matches = AppThreatRules.analyze(facts)
        assertTrue(matches.any { it.severity == ThreatSeverity.CRITICAL })
    }

    @Test
    fun flagsSmsTrojanPermissionCombo() {
        val facts = AppFacts(
            label = "Free Games",
            packageName = "com.games.free",
            installer = null,
            isSystem = false,
            permissions = setOf(
                "android.permission.READ_SMS",
                "android.permission.SEND_SMS",
                "android.permission.INTERNET",
            ),
            hasAccessibilityService = false,
            hasDeviceAdmin = false,
        )
        val matches = AppThreatRules.analyze(facts)
        assertTrue(matches.any { it.category == ThreatCategory.RISKY_APP })
    }

    @Test
    fun ignoresSystemApp() {
        val facts = AppFacts(
            label = "Settings",
            packageName = "com.android.settings",
            installer = null,
            isSystem = true,
            permissions = setOf("android.permission.READ_SMS", "android.permission.INTERNET", "android.permission.SEND_SMS"),
            hasAccessibilityService = false,
            hasDeviceAdmin = false,
        )
        assertTrue(AppThreatRules.analyze(facts).isEmpty())
    }
}
