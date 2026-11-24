package com.techwombat.liberates

/** Word-level diff used to show what a cleanup pass changed. Keep inputs chunk-sized (a few thousand words). */
object WordDiff {
    enum class Kind { SAME, REMOVED, ADDED }

    data class Piece(val kind: Kind, val text: String)

    data class Summary(val removedWords: Int, val addedWords: Int, val changeRegions: Int) {
        val unchanged: Boolean get() = removedWords == 0 && addedWords == 0
    }

    const val PARAGRAPH = "\n\n"

    fun tokenize(text: String): List<String> =
        Regex("\\n\\s*\\n|\\S+").findAll(text.trim()).map { if (it.value.isBlank()) PARAGRAPH else it.value }.toList()

    fun diff(before: String, after: String): List<Piece> {
        val a = tokenize(before)
        val b = tokenize(after)
        // Trim the common prefix and suffix so the LCS table only covers the changed middle.
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++

        val midA = a.subList(prefix, a.size - suffix)
        val midB = b.subList(prefix, b.size - suffix)
        val lcs = Array(midA.size + 1) { IntArray(midB.size + 1) }
        for (i in midA.indices.reversed()) {
            for (j in midB.indices.reversed()) {
                lcs[i][j] = if (midA[i] == midB[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }

        val tokens = mutableListOf<Pair<Kind, String>>()
        a.subList(0, prefix).forEach { tokens += Kind.SAME to it }
        var i = 0
        var j = 0
        while (i < midA.size && j < midB.size) {
            when {
                midA[i] == midB[j] -> { tokens += Kind.SAME to midA[i]; i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { tokens += Kind.REMOVED to midA[i]; i++ }
                else -> { tokens += Kind.ADDED to midB[j]; j++ }
            }
        }
        while (i < midA.size) tokens += Kind.REMOVED to midA[i++]
        while (j < midB.size) tokens += Kind.ADDED to midB[j++]
        a.subList(a.size - suffix, a.size).forEach { tokens += Kind.SAME to it }

        return merge(tokens)
    }

    /** Joins consecutive tokens of the same kind into pieces, restoring spaces and paragraph breaks. */
    private fun merge(tokens: List<Pair<Kind, String>>): List<Piece> {
        val pieces = mutableListOf<Piece>()
        tokens.forEach { (kind, token) ->
            val last = pieces.lastOrNull()
            if (last != null && last.kind == kind) {
                pieces[pieces.lastIndex] = Piece(kind, joinToken(last.text, token))
            } else {
                pieces += Piece(kind, token)
            }
        }
        return pieces
    }

    private fun joinToken(existing: String, token: String): String = when {
        token == PARAGRAPH || existing.endsWith(PARAGRAPH) -> existing + token
        else -> "$existing $token"
    }

    /** Splits a diff into paragraphs and keeps only those that contain a change. */
    fun changedParagraphs(pieces: List<Piece>): List<List<Piece>> {
        val paragraphs = mutableListOf<MutableList<Piece>>(mutableListOf())
        pieces.forEach { piece ->
            piece.text.split(PARAGRAPH).forEachIndexed { index, part ->
                if (index > 0) paragraphs += mutableListOf<Piece>()
                if (part.isNotBlank()) paragraphs.last() += Piece(piece.kind, part.trim())
            }
        }
        return paragraphs.filter { paragraph -> paragraph.any { it.kind != Kind.SAME } }
    }

    fun summarize(pieces: List<Piece>): Summary {
        fun words(text: String) = tokenize(text).count { it != PARAGRAPH }
        return Summary(
            removedWords = pieces.filter { it.kind == Kind.REMOVED }.sumOf { words(it.text) },
            addedWords = pieces.filter { it.kind == Kind.ADDED }.sumOf { words(it.text) },
            changeRegions = pieces.withIndex().count { (index, piece) ->
                piece.kind != Kind.SAME && (index == 0 || pieces[index - 1].kind == Kind.SAME)
            },
        )
    }
}
