package com.techwombat.liberates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrRulesParserTest {
    @Test
    fun `comments and blank lines are ignored while rule order is preserved`() {
        val result = OcrRulesParser.parse(
            """
            # defaults

            first => one
              # another comment
            second => two
            """.trimIndent(),
        )

        assertTrue(result.isValid)
        assertEquals(
            listOf(OcrLiteralRule("first", "one"), OcrLiteralRule("second", "two")),
            result.rules,
        )
    }

    @Test
    fun `only the first delimiter separates a literal rule`() {
        val result = OcrRulesParser.parse("left => right => still literal")

        assertTrue(result.isValid)
        assertEquals(listOf(OcrLiteralRule("left", "right => still literal")), result.rules)
    }

    @Test
    fun `surrounding delimiter whitespace is removed but literal special characters remain`() {
        val result = OcrRulesParser.parse("  [a-z]+?  =>  $1\\path  ")

        assertTrue(result.isValid)
        assertEquals(listOf(OcrLiteralRule("[a-z]+?", "$1\\path")), result.rules)
    }

    @Test
    fun `replacement may be empty`() {
        val result = OcrRulesParser.parse("remove-me =>")

        assertTrue(result.isValid)
        assertEquals(listOf(OcrLiteralRule("remove-me", "")), result.rules)
    }

    @Test
    fun `missing delimiter is reported with its line number`() {
        val result = OcrRulesParser.parse("valid => rule\nnot a rule")

        assertFalse(result.isValid)
        assertEquals(listOf(OcrLiteralRule("valid", "rule")), result.rules)
        assertEquals(OcrRulesParseError(2, "Use find => replace."), result.errors.single())
    }

    @Test
    fun `empty find is reported with its line number`() {
        val result = OcrRulesParser.parse("=> replacement")

        assertFalse(result.isValid)
        assertEquals(OcrRulesParseError(1, "Find text cannot be empty."), result.errors.single())
    }
}
