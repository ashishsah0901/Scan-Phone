package com.ashish.scanphone.scan

import com.ashish.scanphone.model.QueryMode

class TextMatcher(
    query: String,
    private val caseSensitive: Boolean,
    private val mode: QueryMode = QueryMode.SUBSTRING,
) {
    val rawQuery: String = query.trim()
    private val needle = rawQuery
    private val digits = needle.filter { it.isDigit() }

    private val terms: List<String> =
        if (mode == QueryMode.ALL_TERMS || mode == QueryMode.ANY_TERMS) {
            needle.split(WHITESPACE).filter { it.isNotEmpty() }
        } else {
            emptyList()
        }

    private val regex: Regex? =
        if (mode == QueryMode.REGEX && needle.isNotEmpty()) {
            runCatching {
                val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                Regex(needle, options)
            }.getOrNull()
        } else {
            null
        }

    val isUsable: Boolean = when (mode) {
        QueryMode.SUBSTRING -> needle.isNotEmpty()
        QueryMode.ALL_TERMS, QueryMode.ANY_TERMS -> terms.isNotEmpty()
        QueryMode.REGEX -> regex != null
    }

    val looksLikeNumber: Boolean =
        isUsable && mode != QueryMode.REGEX && digits.length >= MIN_NUMBER_DIGITS

    fun indexIn(haystack: String?): Int {
        if (!isUsable || haystack.isNullOrEmpty()) return -1
        return when (mode) {
            QueryMode.SUBSTRING -> haystack.indexOf(needle, ignoreCase = !caseSensitive)
            QueryMode.ALL_TERMS -> {
                val indexes = terms.map { haystack.indexOf(it, ignoreCase = !caseSensitive) }
                if (indexes.any { it < 0 }) -1 else indexes.min()
            }

            QueryMode.ANY_TERMS ->
                terms.map { haystack.indexOf(it, ignoreCase = !caseSensitive) }
                    .filter { it >= 0 }
                    .minOrNull() ?: -1

            QueryMode.REGEX -> regex?.find(haystack)?.range?.first ?: -1
        }
    }

    fun matches(haystack: String?): Boolean = indexIn(haystack) >= 0

    fun matchesNumber(candidate: String?): Boolean {
        if (!looksLikeNumber || candidate.isNullOrEmpty()) return false
        return candidate.filter { it.isDigit() }.contains(digits)
    }

    fun snippet(text: String, index: Int, radius: Int = SNIPPET_RADIUS): String {
        if (index < 0) return text.take(radius * 2).collapseWhitespace()
        val length = matchLengthAt(text, index)
        val start = (index - radius).coerceAtLeast(0)
        val end = (index + length + radius).coerceAtMost(text.length)
        val core = text.substring(start, end).collapseWhitespace()
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + core + suffix
    }

    private fun matchLengthAt(text: String, index: Int): Int = when (mode) {
        QueryMode.SUBSTRING -> needle.length
        QueryMode.ALL_TERMS, QueryMode.ANY_TERMS ->
            terms.firstOrNull { text.regionMatches(index, it, 0, it.length, ignoreCase = !caseSensitive) }
                ?.length
                ?: terms.maxOf { it.length }

        QueryMode.REGEX -> regex?.find(text, index)?.value?.length ?: DEFAULT_MATCH_LENGTH
    }

    private fun String.collapseWhitespace(): String = trim().replace(WHITESPACE, " ")

    private companion object {
        const val MIN_NUMBER_DIGITS = 3
        const val SNIPPET_RADIUS = 48
        const val DEFAULT_MATCH_LENGTH = 8
        val WHITESPACE = Regex("\\s+")
    }
}
