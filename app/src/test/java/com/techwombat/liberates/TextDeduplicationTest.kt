package com.techwombat.liberates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

class TextDeduplicationTest {

    private fun computeHash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun isSimilarContent(s1: String, s2: String): Boolean {
        if (s1.isEmpty() || s2.isEmpty()) return false
        val minLen = minOf(s1.length, s2.length).toDouble()
        val maxLen = maxOf(s1.length, s2.length).toDouble()
        if (minLen / maxLen < 0.8) return false

        var matchingChars = 0
        val checkLen = minLen.toInt()
        for (i in 0 until checkLen) {
            if (s1[i] == s2[i]) matchingChars++
        }
        return (matchingChars / maxLen) > 0.85
    }

    @Test
    fun testExactHashMatchDeduplication() {
        val page1Text = "Chapter 1\nIt was the best of times, it was the worst of times."
        val page2Text = "Chapter 1\nIt was the best of times, it was the worst of times."

        val hash1 = computeHash(page1Text)
        val hash2 = computeHash(page2Text)

        assertEquals("Hashes should be identical for exact same text content", hash1, hash2)
    }

    @Test
    fun testDistinctPagesHaveDifferentHashes() {
        val page1Text = "Chapter 1\nIt was the best of times."
        val page2Text = "Chapter 2\nIt was the age of wisdom."

        val hash1 = computeHash(page1Text)
        val hash2 = computeHash(page2Text)

        assertNotEquals("Different pages must have distinct hashes", hash1, hash2)
    }

    @Test
    fun testSimilarityDetectionOnMinorRedraw() {
        val originalText = "Page 42\nThis is a long paragraph on screen that gets re-rendered."
        val minorRedraw = "Page 42\nThis is a long paragraph on screen that gets re-rendered."

        val isSimilar = isSimilarContent(originalText, minorRedraw)
        assertEquals(true, isSimilar)
    }
}
