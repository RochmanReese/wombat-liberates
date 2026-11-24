package com.techwombat.liberates

/**
 * Removes Kindle's footer/header text, applies built-in contextual OCR fixes, then ordered literal rules.
 * Literal rules match whole words only: "mne => me" fixes "mne" but leaves "damned" alone.
 */
object OcrCleanup {
    fun clean(text: String, literalRules: List<OcrLiteralRule>): String {
        var cleaned = correctContextualPipes(KindleChrome.strip(text))
        literalRules.forEach { rule ->
            cleaned = replaceWholeWords(cleaned, rule.find, rule.replace)
        }
        return cleaned
    }

    /**
     * Replaces [find] where it is not part of a longer word: if it starts (or ends) with a letter or
     * digit, the character before (or after) it must not be one. Rules like "4| => “I" or ". => !"
     * that start or end with punctuation still match anywhere on that side.
     */
    fun replaceWholeWords(text: String, find: String, replace: String): String {
        if (find.isEmpty()) return text
        val checkStart = find.first().isLetterOrDigit()
        val checkEnd = find.last().isLetterOrDigit()
        val out = StringBuilder(text.length)
        var last = 0
        var index = text.indexOf(find)
        while (index >= 0) {
            val end = index + find.length
            val startOk = !checkStart || index == 0 || !text[index - 1].isLetterOrDigit()
            val endOk = !checkEnd || end == text.length || !text[end].isLetterOrDigit()
            if (startOk && endOk) {
                out.append(text, last, index).append(replace)
                last = end
                index = text.indexOf(find, end)
            } else {
                index = text.indexOf(find, index + 1)
            }
        }
        if (last == 0) return text
        return out.append(text, last, text.length).toString()
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
