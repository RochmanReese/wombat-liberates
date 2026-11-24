package com.techwombat.liberates

import android.content.Context
import java.io.InputStream
import kotlin.math.ln
import kotlin.math.max

/**
 * English word and word-pair frequencies (SymSpell lists from Google Books Ngrams, MIT licence,
 * see assets/spelling/LICENSE-SymSpell.txt). Word pairs are stored as 64-bit hashes in sorted
 * arrays to keep memory small (~3 MB for 243k pairs).
 */
class SpellingDictionary(
    private val words: Map<String, Long>,
    private val pairHashes: LongArray,
    private val pairCounts: LongArray,
) {
    fun frequency(word: String): Long = words[word] ?: 0L

    fun contains(word: String): Boolean = words.containsKey(word)

    /** How often "first second" appears in the reference books (0 if never). */
    fun pair(first: String?, second: String?): Long {
        if (first == null || second == null) return 0L
        val index = pairHashes.binarySearch(hash("$first $second"))
        return if (index >= 0) pairCounts[index] else 0L
    }

    companion object {
        @Volatile
        private var shared: SpellingDictionary? = null
        @Volatile
        private var loader: (() -> SpellingDictionary)? = null

        /** Registers the app's bundled lists; they are loaded the first time they are needed. */
        fun useAssets(context: Context) {
            if (loader != null) return
            val app = context.applicationContext
            loader = {
                app.assets.open("spelling/words-en.txt").use { words ->
                    app.assets.open("spelling/bigrams-en.txt").use { pairs -> load(words, pairs) }
                }
            }
        }

        /** For tests and tools: use an already-loaded dictionary. */
        fun use(dictionary: SpellingDictionary) {
            shared = dictionary
        }

        /** The shared dictionary, loading it on first use (blocking); null if none is available. */
        @Synchronized
        fun get(): SpellingDictionary? {
            shared?.let { return it }
            val load = loader ?: return null
            return runCatching(load).getOrNull()?.also { shared = it }
        }

        fun load(words: InputStream, pairs: InputStream): SpellingDictionary {
            val wordMap = HashMap<String, Long>(100_000)
            words.bufferedReader().forEachLine { line ->
                val space = line.lastIndexOf(' ')
                if (space > 0) line.substring(space + 1).toLongOrNull()?.let { wordMap[line.substring(0, space)] = it }
            }
            val entries = ArrayList<Pair<Long, Long>>(250_000)
            pairs.bufferedReader().forEachLine { line ->
                val space = line.lastIndexOf(' ')
                if (space > 0) line.substring(space + 1).toLongOrNull()?.let { entries += hash(line.substring(0, space)) to it }
            }
            entries.sortBy { it.first }
            return SpellingDictionary(wordMap, LongArray(entries.size) { entries[it].first }, LongArray(entries.size) { entries[it].second })
        }

        /** 64-bit FNV-1a. */
        private fun hash(text: String): Long {
            var h = -0x340d631b7bdddcdbL
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                h = h xor (byte.toLong() and 0xff)
                h *= 0x100000001b3L
            }
            return h
        }
    }
}

/**
 * Fixes OCR misreadings on the phone, without AI. Two sources of truth:
 *  - an English frequency dictionary with word-pair context ("up er bag" is never seen; "up her bag" is common);
 *  - the book itself: a word used many times ("will" ×400) beats a near-identical rare one ("wil" ×6),
 *    and names the book spells consistently ("Inaxidor", "WillCo") are left alone.
 * Conservative by design: when in doubt it changes nothing.
 */
class OcrSpelling(private val dictionary: SpellingDictionary, bookText: String) {
    data class Change(val from: String, val to: String)

    private val bookCounts: Map<String, Int>
    /** Words the book uses often and almost always capitalises: names such as "Hitomi" or "Everwood". */
    private val bookNames: Set<String>

    init {
        val counts = HashMap<String, Int>()
        val capitalised = HashMap<String, Int>()
        WORD.matchesIn(bookText).forEach { match ->
            val word = normalize(match.value)
            counts[word] = (counts[word] ?: 0) + 1
            if (match.value[0].isUpperCase()) capitalised[word] = (capitalised[word] ?: 0) + 1
        }
        bookCounts = counts
        bookNames = counts.filter { (word, count) -> count >= BOOK_KNOWN && (capitalised[word] ?: 0) >= count * 0.8 }.keys
    }
    val changes = mutableListOf<Change>()

    private fun bookCount(word: String) = bookCounts[word] ?: 0

    fun correct(text: String): String {
        val tokens = WORD.matchesIn(text)
        if (tokens.isEmpty()) return text
        val out = StringBuilder(text.length)
        var last = 0
        var index = 0
        while (index < tokens.size) {
            val match = tokens[index]
            val following = tokens.getOrNull(index + 1)
            // "rela tively", "charac ters": a word broken in two by OCR or a line break.
            val joined = following?.takeIf { onlySpaceBetween(text, match, it, allowLineBreak = true) }?.let { joinBrokenWord(match.value, it.value) }
            if (joined != null && following != null) {
                out.append(text, last, match.range.first).append(joined)
                changes += Change("${match.value} ${following.value}", joined)
                last = following.range.last + 1
                index += 2
                continue
            }
            val previous = tokens.getOrNull(index - 1)?.takeIf { onlySpaceBetween(text, it, match) }?.value?.let(::normalize)
            val next = following?.takeIf { onlySpaceBetween(text, match, it) }?.value?.let(::normalize)
            val before = text.getOrNull(match.range.first - 1)
            val after = text.getOrNull(match.range.last + 1)
            val inAddress = before == '@' || before == '/' || after == '@' ||
                (after == '.' && text.getOrNull(match.range.last + 2)?.isLetter() == true)
            val nextToHyphen = before in HYPHENS || after in HYPHENS || inAddress
            val fixed = CONTRACTIONS[match.value] ?: CONTRACTIONS[match.value.lowercase()]?.let { matchCase(it, match.value) }
                ?: fix(match.value, previous, next, nextToHyphen)
            out.append(text, last, match.range.first).append(fixed)
            if (fixed != match.value) changes += Change(match.value, fixed)
            last = match.range.last + 1
            index++
        }
        out.append(text, last, text.length)
        return out.toString()
    }

    /** Joins two pieces when together they make a known word and at least one piece is not a word by itself. */
    private fun joinBrokenWord(first: String, second: String): String? {
        if (first.any { it.isDigit() } || second.any { it.isDigit() } || second[0].isUpperCase()) return null
        val a = normalize(first)
        val b = normalize(second)
        if (a.contains('\'') || b.contains('\'')) return null
        // Judge the pieces against the dictionary only: a fragment can be common in the book ("ight" from "l ight").
        if (dictionary.contains(a) && dictionary.contains(b)) return null // "a way" stays two words
        val combined = a + b
        return if (dictionary.contains(combined) || combined in bookNames) matchCase(combined, first) else null
    }

    private fun fix(token: String, previous: String?, next: String?, nextToHyphen: Boolean): String {
        if (token.any { it.isDigit() } && token.none { it.isLetter() }) return token
        val word = normalize(token)
        if (word.length < 2 || word.contains('\'') || nextToHyphen) return token
        val capitalised = token[0].isUpperCase()
        val interiorCapitals = token.drop(1).count { it.isUpperCase() }
        val lowercase = token.count { it.isLowerCase() }
        // "stOod", "acceSS": stray capitals among lowercase letters (not "HOTs" or other abbreviations).
        val onlyCapitalI = interiorCapitals > 0 && token.drop(1).filter { it.isUpperCase() }.all { it == 'I' }
        val mixedCase = interiorCapitals > 0 && lowercase >= 1 && (onlyCapitalI || (lowercase >= 2 && lowercase > interiorCapitals))

        // "stOod", "yOu", "AIl": stray capitals inside a word. A capital I is often a misread l.
        if (mixedCase) {
            // Swap only the letters that were a capital I in the original.
            val asL = buildString {
                append(word[0])
                token.drop(1).forEachIndexed { i, c -> append(if (c == 'I') 'l' else word[i + 1]) }
            }
            val choice = listOf(word, asL).filter { dictionary.contains(it) }.maxByOrNull { dictionary.frequency(it) }
            if (choice != null) return matchCase(choice, if (capitalised) token.take(1) + token.drop(1).lowercase() else token.lowercase())
        }

        val inDictionary = dictionary.contains(word)
        if (inDictionary || bookCount(word) >= BOOK_KNOWN) {
            return if (!capitalised && inDictionary && word.length <= SHORT_WORD) realWordFix(token, word, previous, next) else token
        }

        val scored = candidates(word)
            .filterNot { isInflectionOf(word, it) }
            .map { candidate -> candidate to score(candidate, word, previous, next) }
            .sortedByDescending { it.second }
        // The book is the best evidence for its own words: if it clearly uses one candidate and not the
        // others ("stallion" ×16, "station" ×1), that one wins whatever general English prefers.
        val bookChoice = scored.firstOrNull { (candidate, _) ->
            bookCount(candidate) >= BOOK_KNOWN &&
                scored.all { (other, _) -> other == candidate || bookCount(candidate) >= BOOK_RATIO * max(1, bookCount(other)) }
        }
        val (candidate, score) = bookChoice ?: scored.firstOrNull() ?: return token
        val runnerUp = scored.firstOrNull { it.first != candidate }?.second
        if (bookChoice == null && runnerUp != null && score - runnerUp < AMBIGUITY_MARGIN) return token // e.g. "fom": from or form?
        val supportedByBook = bookCount(candidate) >= BOOK_KNOWN && bookCount(candidate) >= BOOK_RATIO * max(1, bookCount(word))
        val firstLetterChanged = candidate[0] != word[0]
        val accept = when {
            capitalised -> word.length >= MIN_NAME_FIX_LENGTH && supportedByBook // possible name: only if the book spells the alternative
            // Short pieces and first-letter changes need the words on both sides to agree; very common English
            // words ("wil" → "will") qualify even before the book has used them often.
            word.length <= SHORT_WORD || firstLetterChanged ->
                (supportedByBook || (!firstLetterChanged && dictionary.frequency(candidate) >= VERY_COMMON_WORD)) &&
                    fitsBothSides(candidate, previous, next) && hasNeighbour(previous, next)
            // Long words are rarely ambiguous, and the word-pair list only covers common pairs, so only
            // medium-length words need a neighbour to agree.
            else -> (supportedByBook || (dictionary.frequency(candidate) >= COMMON_WORD && bookCount(word) <= 2)) &&
                (word.length >= LONG_WORD || fitsOneSide(candidate, previous, next))
        }
        return if (accept) matchCase(candidate, token) else token
    }

    /**
     * "er" → "her", "al" → "all": a real but uncommon word where a much more common one fits the words on
     * both sides far better — and the book doesn't use the original often (so "elf" in a fantasy novel stays).
     */
    private fun realWordFix(token: String, word: String, previous: String?, next: String?): String {
        if (previous == null || next == null) return token
        val ownFrequency = dictionary.frequency(word)
        if (ownFrequency >= REAL_WORD_MAX_FREQUENCY) return token // common words ("he", "an") are not OCR slips
        val best = insertions(word)
            .filter { dictionary.frequency(it) >= REAL_WORD_RATIO * max(1L, ownFrequency) }
            .filter { bookCount(it) >= REAL_WORD_BOOK_RATIO * max(1, bookCount(word)) || bookCount(word) <= 2 }
            .filter { candidate ->
                val before = dictionary.pair(previous, candidate)
                val after = dictionary.pair(candidate, next)
                before > 0 && after > 0 &&
                    before >= REAL_WORD_CONTEXT_RATIO * max(1L, dictionary.pair(previous, word)) &&
                    after >= REAL_WORD_CONTEXT_RATIO * max(1L, dictionary.pair(word, next))
            }
            .maxByOrNull { context(it, previous, next) }
        return best?.let { matchCase(it, token) } ?: token
    }

    /** The candidate is seen next to both neighbours (where there are neighbours). */
    private fun fitsBothSides(candidate: String, previous: String?, next: String?): Boolean =
        (previous == null || dictionary.pair(previous, candidate) > 0) && (next == null || dictionary.pair(candidate, next) > 0)

    /**
     * Words still worth a second look by AI after the automatic fixes: not a dictionary word, not used
     * regularly in the book, not a name the book capitalises; or a rare short word that is one letter
     * away from a far more common one ("er" for "her", "al" for "all").
     */
    fun needsReview(token: String): Boolean {
        if (token.none { it.isLetter() }) return false
        val word = normalize(token)
        if (word.length < 2 || word.contains('\'')) return false
        if (!dictionary.contains(word)) return bookCount(word) < BOOK_KNOWN && word !in bookNames
        if (word.length > SHORT_WORD || token[0].isUpperCase()) return false
        val frequency = dictionary.frequency(word)
        return frequency < REAL_WORD_MAX_FREQUENCY && bookCount(word) < BOOK_KNOWN &&
            insertions(word).any { dictionary.frequency(it) >= REAL_WORD_RATIO * max(1L, frequency) }
    }

    private fun hasNeighbour(previous: String?, next: String?) = previous != null || next != null

    /** Seen next to at least one neighbour (or there are no neighbours to check against). */
    private fun fitsOneSide(candidate: String, previous: String?, next: String?): Boolean =
        (previous == null && next == null) || dictionary.pair(previous, candidate) > 0 || dictionary.pair(candidate, next) > 0

    /** "warchiefs" vs "warchief": the word is just an inflected form of the candidate, not a misreading. */
    private fun isInflectionOf(word: String, candidate: String): Boolean =
        listOf("s", "es", "ed", "d", "ing", "er", "ers", "ly", "y").any { word == candidate + it }

    private fun context(word: String, previous: String?, next: String?): Long =
        dictionary.pair(previous, word) + dictionary.pair(word, next)

    /**
     * General English frequency, how often the book itself uses the word, and the neighbouring words.
     * A dropped double letter ("stalion", "smal") is OCR's most common slip, so restoring one gets a bonus,
     * and a word the book uses regularly beats a more common English word it hardly uses ("stallion" ×16
     * over "station" ×1).
     */
    private fun score(candidate: String, word: String, previous: String?, next: String?): Double {
        var score = ln(dictionary.frequency(candidate) + 1.0) + 3 * ln(bookCount(candidate) + 1.0) +
            0.5 * ln(context(candidate, previous, next) + 1.0)
        if (restoresDoubleLetter(word, candidate)) score += DOUBLE_LETTER_BONUS
        if (bookCount(candidate) >= BOOK_KNOWN) score += BOOK_WORD_BONUS
        return score
    }

    /** "stalion" → "stallion": the candidate is the word with one letter doubled. */
    private fun restoresDoubleLetter(word: String, candidate: String): Boolean {
        if (candidate.length != word.length + 1) return false
        for (i in 1 until candidate.length) {
            if (candidate[i] == candidate[i - 1] && candidate.removeRange(i, i + 1) == word) return true
        }
        return false
    }

    /**
     * Real words (in the dictionary or used often in the book) one OCR-style edit away: a missing or extra
     * letter, two letters swapped, or a look-alike letter (l/i/1, o/0/c/e, rn/m, h/b, u/v, f/t, g/q).
     * Short words only get a missing letter or a look-alike fix.
     */
    private fun candidates(word: String): Set<String> {
        val edits = HashSet<String>()
        val short = word.length <= SHORT_WORD
        for (i in 0..word.length) {
            for (c in ALPHABET) edits += word.substring(0, i) + c + word.substring(i) // missing letter
            if (short || i >= word.length) continue
            // OCR rarely invents a first letter, so never remove one ("drider" stays).
            if (i > 0 && word[i].isLetter()) edits += word.removeRange(i, i + 1) // extra letter
            LOOK_ALIKES[word[i]]?.forEach { c -> edits += word.substring(0, i) + c + word.substring(i + 1) }
            if (i < word.length - 1) edits += word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2)
        }
        OCR_CONFUSIONS.forEach { (from, to) ->
            var index = word.indexOf(from)
            while (index >= 0) {
                edits += word.substring(0, index) + to + word.substring(index + from.length)
                index = word.indexOf(from, index + 1)
            }
        }
        edits.remove(word)
        return edits.filterTo(HashSet()) { it.length >= 2 && (dictionary.contains(it) || it in bookNames) }
    }

    private fun insertions(word: String): List<String> =
        (0..word.length).flatMap { i -> ALPHABET.map { c -> word.substring(0, i) + c + word.substring(i) } }

    private fun matchCase(word: String, original: String): String = when {
        original.length > 1 && original.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        original[0].isUpperCase() -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }

    private fun onlySpaceBetween(text: String, first: TextMatch, second: TextMatch, allowLineBreak: Boolean = false): Boolean =
        text.substring(first.range.last + 1, second.range.first).let { gap ->
            gap.isNotEmpty() && gap.isBlank() && gap.count { it == '\n' } <= (if (allowLineBreak) 1 else 0)
        }

    private fun normalize(token: String) = token.lowercase().replace('’', '\'')

    private companion object {
        val WORD = Regex("[A-Za-z0-9'’]*[A-Za-z][A-Za-z0-9'’]*")
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyz"
        const val MIN_NAME_FIX_LENGTH = 5
        /** Letters OCR commonly mistakes for each other. */
        val LOOK_ALIKES: Map<Char, List<Char>> = listOf("li1t", "o0cea", "hbn", "uvn", "ft", "gq", "s5", "mn", "rn", "z2")
            .flatMap { group -> group.map { c -> c to group.filter { it != c }.toList() } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, lists) -> lists.flatten().distinct() }
        /** Contractions OCR loses the apostrophe from (or misreads "I'll" as "IIl"). */
        val CONTRACTIONS: Map<String, String> = mapOf(
            "Im" to "I'm", "Ive" to "I've", "IIl" to "I'll", "Il" to "I'll", "I1l" to "I'll", "Ild" to "I'd",
            "dont" to "don't", "didnt" to "didn't", "doesnt" to "doesn't", "isnt" to "isn't", "wasnt" to "wasn't",
            "werent" to "weren't", "arent" to "aren't", "couldnt" to "couldn't", "wouldnt" to "wouldn't",
            "shouldnt" to "shouldn't", "havent" to "haven't", "hasnt" to "hasn't", "hadnt" to "hadn't",
            "youre" to "you're", "theyre" to "they're", "youve" to "you've", "theyve" to "they've",
            "weve" to "we've", "thats" to "that's", "theres" to "there's", "whats" to "what's",
        )
        val OCR_CONFUSIONS = listOf("rn" to "m", "m" to "rn", "cl" to "d", "vv" to "w", "1" to "l", "1" to "i", "0" to "o", "5" to "s", "ii" to "u")
        const val BOOK_KNOWN = 3
        const val BOOK_RATIO = 5
        const val COMMON_WORD = 1_000_000L
        const val VERY_COMMON_WORD = 100_000_000L
        const val AMBIGUITY_MARGIN = 1.0
        const val DOUBLE_LETTER_BONUS = 2.0
        const val BOOK_WORD_BONUS = 2.0
        const val SHORT_WORD = 3
        const val LONG_WORD = 7
        const val REAL_WORD_RATIO = 10
        const val REAL_WORD_CONTEXT_RATIO = 20
        const val REAL_WORD_MAX_FREQUENCY = 100_000_000L
        const val REAL_WORD_BOOK_RATIO = 20
        val HYPHENS = setOf('-', '–', '—', '‐')
    }
}
