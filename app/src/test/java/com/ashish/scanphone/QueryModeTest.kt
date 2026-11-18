package com.ashish.scanphone

import com.ashish.scanphone.model.QueryMode
import com.ashish.scanphone.scan.TextMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryModeTest {

    @Test
    fun allTermsRequiresEveryWord() {
        val matcher = TextMatcher("invoice march", caseSensitive = false, mode = QueryMode.ALL_TERMS)
        assertTrue(matcher.matches("march invoice final"))
        assertFalse(matcher.matches("invoice final"))
    }

    @Test
    fun anyTermsMatchesEitherWord() {
        val matcher = TextMatcher("invoice receipt", caseSensitive = false, mode = QueryMode.ANY_TERMS)
        assertTrue(matcher.matches("annual receipt"))
        assertFalse(matcher.matches("annual statement"))
    }

    @Test
    fun regexMatchesPattern() {
        val matcher = TextMatcher("inv[0-9]{3}", caseSensitive = false, mode = QueryMode.REGEX)
        assertTrue(matcher.matches("file inv042 here"))
        assertFalse(matcher.matches("file invoice here"))
    }

    @Test
    fun invalidRegexIsNotUsable() {
        assertFalse(TextMatcher("inv[0-9", caseSensitive = false, mode = QueryMode.REGEX).isUsable)
    }

    @Test
    fun regexSnippetWrapsTheMatch() {
        val matcher = TextMatcher("nee.le", caseSensitive = false, mode = QueryMode.REGEX)
        val text = "x".repeat(120) + " needle " + "y".repeat(120)
        val snippet = matcher.snippet(text, matcher.indexIn(text))
        assertTrue(snippet.contains("needle"))
    }

    @Test
    fun allTermsSnippetStartsAtFirstTerm() {
        val matcher = TextMatcher("beta alpha", caseSensitive = false, mode = QueryMode.ALL_TERMS)
        val text = "alpha then some words then beta"
        val index = matcher.indexIn(text)
        assertEquals(text.indexOf("alpha"), index)
    }
}
