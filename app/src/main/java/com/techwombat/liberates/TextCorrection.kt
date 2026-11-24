package com.techwombat.liberates


/** Splits text into paragraph-aligned chunks small enough for a correction model. */
object TextChunker {
    const val CHUNK_CHAR_LIMIT = 2_500

    fun chunk(text: String, limit: Int = CHUNK_CHAR_LIMIT): List<String> {
        val paragraphs = text.split(Regex("\\n\\s*\\n")).map(String::trim).filter(String::isNotBlank)
        val chunks = mutableListOf<String>()
        var chunk = ""
        paragraphs.forEach { paragraph ->
            if (paragraph.length > limit) {
                if (chunk.isNotBlank()) {
                    chunks += chunk
                    chunk = ""
                }
                paragraph.chunked(limit).forEach(chunks::add)
            } else if (chunk.isBlank()) {
                chunk = paragraph
            } else if (chunk.length + paragraph.length + 2 <= limit) {
                chunk += "\n\n$paragraph"
            } else {
                chunks += chunk
                chunk = paragraph
            }
        }
        if (chunk.isNotBlank()) chunks += chunk
        return chunks
    }
}

/** Conservative OCR proofreading prompt for on-device (Gemini Nano) correction. The cleanup server has its own copy. */
object CorrectionPrompt {
    fun build(chunk: String): String = """
        You are a conservative OCR proofreader.

        Correct only unmistakable OCR mistakes: confused characters, capitalization errors, broken words, stray punctuation, and word-break errors.
        Preserve the author's exact wording, sentence order, paragraph breaks, dialogue, chapter headings, names, formatting, and meaning.
        Do not rewrite for style. Do not summarize. Do not explain your edits. Do not add or remove sentences.
        If a possible correction is uncertain, leave it unchanged.
        Lines like [[image:img-0001.jpg]] mark pictures: copy them exactly, on their own line, in the same place.
        Return only the corrected text, with no heading, commentary, Markdown, or quotes around the response.

        OCR TEXT START
        $chunk
        OCR TEXT END
    """.trimIndent()
}
