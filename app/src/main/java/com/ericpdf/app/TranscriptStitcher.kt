package com.ericpdf.app

import java.util.Locale

/**
 * Removes repeated words introduced by overlapped speech-recognition windows.
 * Only strips strongly matching word sequences. It does not rewrite technical
 * vocabulary, equations, units or uncertain speech.
 */
object TranscriptStitcher {
    private val whitespace = Regex("\\s+")
    private val edges = Regex("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$")

    private fun words(value: String) = value.trim().split(whitespace).filter { it.isNotBlank() }
    private fun key(value: String): String =
        value.lowercase(Locale.ROOT).replace(edges, "")

    fun removeOverlap(previous: String, incoming: String): String {
        val current = words(incoming)
        if (current.isEmpty()) return ""
        val old = words(previous)
        if (old.isEmpty()) return current.joinToString(" ")
        val before = old.map(::key)
        val after = current.map(::key)
        val limit = minOf(24, before.size, after.size)
        for (length in limit downTo 2) {
            val preceding = before.takeLast(length)
            val following = after.take(length)
            val matches = preceding.zip(following).count { (a, b) -> a.isNotEmpty() && a == b }
            // For 5+ words, tolerate one misheard token in overlapping speech.
            val acceptable = matches == length || (length >= 5 && matches >= length - 1)
            if (acceptable) return current.drop(length).joinToString(" ")
        }
        // A single long word is useful when a chunk ends mid-sentence,
        // but avoid deleting common short words that may genuinely repeat.
        if (before.lastOrNull() == after.firstOrNull() &&
            after.first().length >= 7) {
            return current.drop(1).joinToString(" ")
        }
        return current.joinToString(" ")
    }
}
