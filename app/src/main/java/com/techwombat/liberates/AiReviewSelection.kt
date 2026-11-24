package com.techwombat.liberates

import org.json.JSONArray
import org.json.JSONObject

/**
 * Chooses the few sentences worth sending to the AI cleanup server: those that still contain a
 * doubtful word after the phone's own spelling fixes. Typically 1–3% of a book, so the server
 * needs minutes rather than hours. The corrected sentences are then put back into the full text.
 */
object AiReviewSelection {
    /** One sentence to review: where it is (chunk and character offset) and its original text. */
    data class Piece(val chunk: Int, val start: Int, val text: String)

    private val WORD = Regex("[A-Za-z0-9'’]*[A-Za-z][A-Za-z0-9'’]*")
    /** A sentence ends at . ! ? or … (plus closing quotes/brackets) followed by space, or at a line break. */
    private val SENTENCE = Regex("""[^\n]+?(?:[.!?…]+["”’)\]]*(?=\s)|$)""", RegexOption.MULTILINE)

    fun select(chunks: List<String>, needsReview: (String) -> Boolean): List<Piece> {
        val pieces = mutableListOf<Piece>()
        chunks.forEachIndexed { index, chunk ->
            SENTENCE.matchesIn(chunk).forEach { match ->
                val raw = match.value
                val text = raw.trim()
                if (text.isEmpty() || Illustrations.isMarker(text)) return@forEach
                if (WORD.matchesIn(text).any { needsReview(it.value) }) {
                    pieces += Piece(index, match.range.first + raw.indexOf(text), text)
                }
            }
        }
        return pieces
    }

    /**
     * Puts corrected sentences back. A correction is skipped if its sentence can no longer be found, or
     * if the model changed the length so much that it probably rewrote rather than proofread.
     */
    fun apply(chunks: List<String>, pieces: List<Piece>, corrected: List<String>): List<String> {
        val result = chunks.toMutableList()
        // Work backwards within each chunk so earlier offsets stay valid.
        pieces.indices.sortedWith(compareBy<Int>({ pieces[it].chunk }, { -pieces[it].start })).forEach { i ->
            val piece = pieces[i]
            val fixed = corrected.getOrNull(i)?.trim().orEmpty()
            val chunk = result.getOrNull(piece.chunk) ?: return@forEach
            if (!plausible(piece.text, fixed)) return@forEach
            val at = if (chunk.startsWith(piece.text, piece.start)) piece.start else chunk.indexOf(piece.text)
            if (at < 0) return@forEach
            result[piece.chunk] = chunk.substring(0, at) + fixed + chunk.substring(at + piece.text.length)
        }
        return result
    }

    private fun plausible(original: String, fixed: String): Boolean {
        if (fixed.isBlank() || fixed.contains('\n')) return false
        val ratio = fixed.length.toDouble() / original.length.coerceAtLeast(1)
        return ratio in 0.7..1.4
    }

    fun toJson(pieces: List<Piece>): String = JSONArray().apply {
        pieces.forEach { put(JSONObject().put("chunk", it.chunk).put("start", it.start).put("text", it.text)) }
    }.toString()

    fun fromJson(json: String): List<Piece> {
        val array = JSONArray(json)
        return List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Piece(o.getInt("chunk"), o.getInt("start"), o.getString("text"))
        }
    }
}
