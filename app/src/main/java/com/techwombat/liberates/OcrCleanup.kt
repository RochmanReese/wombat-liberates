package com.techwombat.liberates

/** Applies built-in contextual OCR fixes followed by ordered literal user rules. */
object OcrCleanup {
    fun clean(text: String, literalRules: List<OcrLiteralRule>): String {
        var cleaned = correctContextualPipes(text)
        literalRules.forEach { rule ->
            cleaned = cleaned.replace(rule.find, rule.replace)
        }
        return cleaned
    }

    private fun correctContextualPipes(text: String): String {
        val result = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == '4' && text.getOrNull(index + 1) == '|' && text.getOrNull(index + 2)?.isWhitespace() == true) {
                result.append("“I")
                index += 2
                continue
            }
            if (character == '|' && text.getOrNull(index + 1) == 'I' && isDialogueStart(text, index)) {
                result.append("“I")
                index += 2
                continue
            }
            if (character != '|') {
                result.append(character)
                index += 1
                continue
            }

            val previous = text.getOrNull(index - 1)
            val next = text.getOrNull(index + 1)
            val following = text.getOrNull(index + 2)
            result.append(
                when {
                    previous?.isLetter() == true || previous.isApostrophe() -> 'l'
                    next?.isLetter() == true || next.isApostrophe() -> 'I'
                    next?.isWhitespace() == true && following?.isLetter() == true -> 'I'
                    previous?.isWhitespace() == true && next?.isWhitespace() == true -> 'I'
                    else -> '|'
                },
            )
            index += 1
        }
        return result.toString()
    }

    private fun isDialogueStart(text: String, pipeIndex: Int): Boolean =
        pipeIndex == 0 || text.getOrNull(pipeIndex - 1)?.isWhitespace() == true

    private fun Char?.isApostrophe(): Boolean = this == '\'' || this == '’'
}
