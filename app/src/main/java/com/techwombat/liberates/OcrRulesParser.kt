package com.techwombat.liberates

data class OcrLiteralRule(
    val find: String,
    val replace: String,
)

data class OcrRulesParseError(
    val lineNumber: Int,
    val message: String,
)

data class OcrRulesParseResult(
    val rules: List<OcrLiteralRule>,
    val errors: List<OcrRulesParseError>,
) {
    val isValid: Boolean get() = errors.isEmpty()
}

/** Parses editable, literal OCR replacements in `find => replace` format. */
object OcrRulesParser {
    fun parse(text: String): OcrRulesParseResult {
        val rules = mutableListOf<OcrLiteralRule>()
        val errors = mutableListOf<OcrRulesParseError>()

        text.lineSequence().forEachIndexed { index, line ->
            val lineNumber = index + 1
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) return@forEachIndexed

            val delimiterIndex = line.indexOf(DELIMITER)
            if (delimiterIndex < 0) {
                errors += OcrRulesParseError(lineNumber, "Use find => replace.")
                return@forEachIndexed
            }

            val find = line.substring(0, delimiterIndex).trim()
            if (find.isEmpty()) {
                errors += OcrRulesParseError(lineNumber, "Find text cannot be empty.")
                return@forEachIndexed
            }

            val replace = line.substring(delimiterIndex + DELIMITER.length).trim()
            rules += OcrLiteralRule(find, replace)
        }

        return OcrRulesParseResult(rules, errors)
    }

    private const val DELIMITER = "=>"
}
