package com.ashish.scanphone

import com.ashish.scanphone.scan.TextMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatcherTest {

    @Test
    fun blankQueryIsNotUsable() {
        assertFalse(TextMatcher("   ", caseSensitive = false).isUsable)
    }

    @Test
    fun matchesIgnoringCaseByDefault() {
        val matcher = TextMatcher("Invoice", caseSensitive = false)
        assertTrue(matcher.matches("2024-invoice-final.pdf"))
    }

    @Test
    fun respectsCaseSensitivity() {
        val matcher = TextMatcher("Invoice", caseSensitive = true)
        assertFalse(matcher.matches("2024-invoice-final.pdf"))
        assertTrue(matcher.matches("2024-Invoice-final.pdf"))
    }

    @Test
    fun matchesPhoneNumberIgnoringFormatting() {
        val matcher = TextMatcher("9876543210", caseSensitive = false)
        assertTrue(matcher.matchesNumber("+91 98765-43210"))
        assertFalse(matcher.matchesNumber("+91 12345-67890"))
    }

    @Test
    fun shortQueriesAreNotTreatedAsNumbers() {
        assertFalse(TextMatcher("ab", caseSensitive = false).looksLikeNumber)
    }

    @Test
    fun snippetIsTrimmedAroundTheMatch() {
        val matcher = TextMatcher("needle", caseSensitive = false)
        val text = "x".repeat(200) + " needle " + "y".repeat(200)
        val snippet = matcher.snippet(text, matcher.indexIn(text))
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.contains("needle"))
    }

    @Test
    fun snippetCollapsesWhitespace() {
        val matcher = TextMatcher("alpha", caseSensitive = false)
        val text = "alpha\t\t   beta"
        assertEquals("alpha beta", matcher.snippet(text, matcher.indexIn(text)))
    }
}
