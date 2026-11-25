package com.techwombat.liberates

/**
 * Finds a book's chapters in its text so the EPUB gets a real table of contents.
 *
 * 1. Printed contents page: heading-like lines in the body that also appear, in the same order, in
 *    the first pages. The printed contents page itself is then left out of the EPUB text.
 *    Kindle books often print the heading in capitals glued onto the chapter's first sentence
 *    ("TREACHERY he heard them…", the decorative first letter lost): those are split off first.
 * 2. Otherwise heading patterns: "Chapter 12", "Chapter Twelve", "Prologue", "Part One", or a bare
 *    number / Roman numeral on its own line, with a title on the next line joined on.
 */
object ChapterDetector {
    data class Chapter(val paragraph: Int, val title: String, val headingParagraphs: Int = 1)

    enum class Source { CONTENTS_PAGE, HEADINGS, NONE }

    /** [paragraphs] is the text the chapter positions refer to (inline headings split into their own paragraphs). */
    data class Result(val chapters: List<Chapter>, val contentsPage: IntRange?, val source: Source, val paragraphs: List<String>)

    private data class Match(val index: Int, val position: Int, val text: String, val title: String)

    private const val MAX_HEADING_LENGTH = 80
    private const val MAX_HEADING_WORDS = 10
    private const val MIN_CONTENTS_MATCHES = 3
    private const val MIN_PATTERN_CHAPTERS = 2
    private const val NAME_MIN_COUNT = 3
    private const val MAX_STRAY_LINES = 3
    private const val MAX_GLUED_LINE_WORDS = 5
    private const val OPENER_WEIGHT = 4
    private const val MIN_PROSE_PARAGRAPH_WORDS = 12
    private const val MIN_PROSE_WORDS = 30
    private const val MIN_TRAILING_SPLIT_WORDS = 5

    private val NUMBER_WORDS = listOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
        "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty",
        "sixty", "seventy", "eighty", "ninety", "hundred",
    ).joinToString("|")
    private val NUMBER = "(?:\\d{1,3}|[ivxlcdm]{1,7}|(?:$NUMBER_WORDS)(?:[- ](?:$NUMBER_WORDS))*)"
    private val CHAPTER_HEADING = Regex("(?i)^(?:chapter|ch\\.)\\s+$NUMBER\\b[\\s:.\\-–—]*(.*)$")
    private val NAMED_SECTION = Regex("(?i)^(?:prologue|epilogue|interlude|foreword|preface|introduction|afterword|part\\s+$NUMBER)\\b.{0,60}$")
    private val BARE_NUMBER = Regex("(?i)^$NUMBER$")
    private val TRAILING_CHAPTER_HEADING = Regex("(?is)^(.*\\S)\\s+(chapter\\s+$NUMBER|prologue|epilogue|afterword|interlude)$")
    private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")
    private val CONTENTS_HEADING = Regex("(?i)^(?:table of )?contents$")
    private val CHAPTER_OR_PART = Regex("(?i)^(?:chapter|part)$")
    private val LETTERS_AND_DIGITS = Regex("[A-Za-z0-9]+")
    private val WORD_WITH_APOSTROPHE = Regex("[A-Za-z0-9]+(?:['’][A-Za-z]+)?")
    /** Words that commonly open a chapter's first sentence; they get a head start when restoring a lost first letter. */
    private val PRONOUNS = setOf("i", "it", "she", "he", "they", "we", "you")
    private val SINGULAR = setOf("it", "she", "he")
    private val OPENERS = setOf("as", "at", "it", "in", "on", "for", "four", "the", "she", "he", "they", "her", "his", "when", "then", "there", "no", "but", "after", "before")
    private val LEADING_NUMBER = Regex("^\\d{1,3}\\s+")
    private val TRAILING_NUMBER = Regex("\\s+\\d{1,3}$")

    /**
     * During a scan: does this screen open a chapter? True when the screen starts with a chapter heading
     * (CHAPTER ONE, Chapter 3: Title, Prologue, Part Two, or a bare number) followed by real prose. The prose
     * requirement keeps a contents page ("Chapter 7 ... Chapter 8 ...") and title pages from counting.
     */
    fun isChapterStartPage(pageText: String): Boolean {
        val paragraphs = pageText.split(PARAGRAPH_BREAK).map { it.trim() }
            .filter { it.isNotEmpty() && !Illustrations.isMarker(it) }
        val headingParagraphs = leadingHeadingParagraphs(paragraphs)
        if (headingParagraphs == 0) return false
        val prose = paragraphs.drop(headingParagraphs)
        return prose.any { wordCount(it) >= MIN_PROSE_PARAGRAPH_WORDS } && prose.sumOf { wordCount(it) } >= MIN_PROSE_WORDS
    }

    /** Does this page start with a chapter heading line? Used when joining pages, so a heading is never glued on. */
    fun startsWithHeading(pageText: String): Boolean =
        leadingHeadingParagraphs(pageText.trim().split(PARAGRAPH_BREAK).map { it.trim() }.filter { it.isNotEmpty() }) > 0

    /** How many paragraphs at the start are a chapter heading (0, 1, or 2 for "CHAPTER" + "TWO"). */
    private fun leadingHeadingParagraphs(paragraphs: List<String>): Int {
        val first = paragraphs.firstOrNull() ?: return 0
        val second = paragraphs.getOrNull(1)
        return when {
            first.length <= MAX_HEADING_LENGTH && CHAPTER_HEADING.matches(first) -> 1
            NAMED_SECTION.matches(first) && isHeadingLike(first) -> 1
            BARE_NUMBER.matches(first) && first.length <= 12 -> 1
            CHAPTER_OR_PART.matches(first) && second != null && BARE_NUMBER.matches(second) -> 2
            else -> 0
        }
    }

    /**
     * "…let's ride out" Chapter Three, or "…example-author.com/ Epilogue": OCR dropped the full stop, so the next screen's heading was joined
     * onto the last paragraph of the previous screen. Splits such a heading back off into its own paragraph.
     */
    private fun splitTrailingHeadings(paragraphs: List<String>): List<String> {
        val out = ArrayList<String>(paragraphs.size + 8)
        paragraphs.forEachIndexed { index, paragraph ->
            val match = TRAILING_CHAPTER_HEADING.find(paragraph.trim())
            val before = match?.groupValues?.get(1)
            val heading = match?.groupValues?.get(2)
            if (match == null || before == null || heading == null || index == paragraphs.lastIndex ||
                !heading.first().isUpperCase() || wordCount(before) < MIN_TRAILING_SPLIT_WORDS
            ) {
                out += paragraph
            } else {
                out += before
                out += heading
            }
        }
        return out
    }

    fun detect(input: List<String>, dictionary: SpellingDictionary? = SpellingDictionary.get()): Result {
        val paragraphs = splitInlineHeadings(splitTrailingHeadings(input), dictionary)
        val headings = fromHeadings(paragraphs)
        fromContentsPage(paragraphs)?.let { listed ->
            // Add clear "Chapter …" headings the contents-page match missed (e.g. a garbled line).
            val extra = headings.filter { heading ->
                // Clear "Chapter …" headings, and sections such as a surprise Epilogue that the printed contents leaves out.
                (CHAPTER_HEADING.matches(heading.title.substringBefore(':').trim()) || NAMED_SECTION.matches(heading.title)) &&
                    listed.chapters.none { kotlin.math.abs(it.paragraph - heading.paragraph) <= 2 } &&
                    // After the printed contents (an early "Chapter One" can sit before the first matched entry),
                    // and not itself another contents entry.
                    heading.paragraph > (listed.contentsPage?.last ?: listed.chapters.first().paragraph) &&
                    paragraphs.getOrNull(heading.paragraph + heading.headingParagraphs)?.trim()
                        ?.let { CHAPTER_HEADING.matches(it) } != true
            }
            return removeStrayNumbers(listed.copy(chapters = (listed.chapters + extra).sortedBy { it.paragraph }))
        }
        return if (headings.size >= MIN_PATTERN_CHAPTERS) {
            removeStrayNumbers(Result(headings, null, Source.HEADINGS, paragraphs))
        } else {
            Result(emptyList(), null, Source.NONE, paragraphs)
        }
    }

    /** "16", "57", "I7" (OCR for 17), "1O": a printed chapter number. Or a lone letter: a lost decorative capital. */
    private fun isStrayNumber(paragraph: String): Boolean {
        val text = paragraph.trim()
        if (text.isEmpty() || text.length > 4) return false
        if (text.length == 1 && text[0].isLetter()) return true
        return text.any { it.isDigit() } && text.all { it.isDigit() || it in "IlOoSZ." } && text.count { it != '.' } <= 3
    }

    /** "FRIENDS & FOES 19" → "FRIENDS & FOES", "43 tan" → "tan": a short line with a chapter number glued on. */
    private fun withoutGluedNumber(paragraph: String): String {
        val words = paragraph.trim().split(' ').filter { it.isNotEmpty() }
        if (words.size < 2 || words.size > MAX_GLUED_LINE_WORDS) return paragraph
        return when {
            isStrayNumber(words.last()) && words.last().length > 1 -> words.dropLast(1).joinToString(" ")
            isStrayNumber(words.first()) && words.first().length > 1 -> words.drop(1).joinToString(" ")
            else -> paragraph
        }
    }

    /**
     * The chapter number printed above each title ends up as the last line of the previous chapter
     * ("…knowing it never could." / "4" / "HOME"). Such lines are removed from the end of every
     * section, including the front matter and the end of the book. Titles stay as printed.
     */
    private fun removeStrayNumbers(result: Result): Result {
        if (result.chapters.isEmpty()) return result
        val headingLines = result.chapters.flatMap { chapter -> (chapter.paragraph until chapter.paragraph + chapter.headingParagraphs).toList() }.toSet()
        val dropped = HashSet<Int>()
        (result.chapters.map { it.paragraph } + result.paragraphs.size).forEach { end ->
            var k = end - 1
            while (k >= 0 && end - k <= MAX_STRAY_LINES && k !in headingLines && result.contentsPage?.contains(k) != true &&
                isStrayNumber(result.paragraphs[k])
            ) {
                dropped += k
                k--
            }
        }
        // The line left at the end of each section may still have the number glued on.
        val trimmed = result.paragraphs.toMutableList()
        (result.chapters.map { it.paragraph } + result.paragraphs.size).forEach { end ->
            val last = (end - 1 downTo 0).firstOrNull { it !in dropped } ?: return@forEach
            if (last in headingLines || result.contentsPage?.contains(last) == true) return@forEach
            trimmed[last] = withoutGluedNumber(trimmed[last])
        }
        if (dropped.isEmpty() && trimmed == result.paragraphs) return result
        val newIndex = IntArray(result.paragraphs.size + 1)
        var next = 0
        for (i in 0..result.paragraphs.size) {
            newIndex[i] = next
            if (i < result.paragraphs.size && i !in dropped) next++
        }
        return result.copy(
            chapters = result.chapters.map { it.copy(paragraph = newIndex[it.paragraph]) },
            contentsPage = result.contentsPage?.let { newIndex[it.first]..newIndex[it.last] },
            paragraphs = trimmed.filterIndexed { i, _ -> i !in dropped },
        )
    }

    /** "CHAPTER" and "TWELVE" in separate paragraphs (as OCR often leaves them) read as one heading. */
    private fun splitHeading(paragraphs: List<String>, index: Int): String? {
        val word = paragraphs[index].trim()
        val number = paragraphs.getOrNull(index + 1)?.trim() ?: return null
        if (word.length != 4 && word.length != 7) return null // "part" / "chapter": skip the pattern for everything else
        return if (CHAPTER_OR_PART.matches(word) && BARE_NUMBER.matches(number)) "$word $number" else null
    }

    /** Lowercase letters and digits, everything else collapsed to single spaces (no regex: this runs per paragraph). */
    private fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var space = false
        for (c in text) {
            val lower = c.lowercaseChar()
            if (lower in 'a'..'z' || lower in '0'..'9') {
                if (space && out.isNotEmpty()) out.append(' ')
                out.append(lower)
                space = false
            } else {
                space = true
            }
        }
        return out.toString()
    }

    private fun wordCount(text: String): Int {
        var count = 0
        var inWord = false
        for (c in text) {
            if (c.isWhitespace()) inWord = false else if (!inWord) { inWord = true; count++ }
        }
        return count
    }

    private fun isHeadingLike(paragraph: String): Boolean {
        val text = paragraph.trim()
        if (text.length < 2 || text.length > MAX_HEADING_LENGTH || '\n' in text || Illustrations.isMarker(text)) return false
        if (wordCount(text) > MAX_HEADING_WORDS) return false
        if (text.last() in ".,;:\"”'’" || text.first() in "\"“‘'") return false
        return text.first().isUpperCase() || text.first().isDigit()
    }

    private fun frontWindow(size: Int) = maxOf(60, size / 12).coerceAtMost(size)

    /** A contents-page line without its page or chapter number: "Skin-Walker 11" → "Skin-Walker". */
    private fun stripNumbers(text: String) = text.trim().replace(LEADING_NUMBER, "").replace(TRAILING_NUMBER, "").trim()

    private fun isAllCaps(word: String) = word.any { it.isLetter() } && word.none { it.isLowerCase() }

    /**
     * "TREACHERY he heard them…" → "TREACHERY" + "She heard them…": contents-page titles found in capitals
     * inside a paragraph become paragraphs of their own. A title only counts if the next word is not in
     * capitals too, so the part title "SMOKE ON THE FRONTIER" is not mistaken for the chapter "Smoke".
     */
    private fun splitInlineHeadings(paragraphs: List<String>, dictionary: SpellingDictionary?): List<String> {
        val window = frontWindow(paragraphs.size)
        val titles = (0 until window).mapNotNull { i ->
            val entry = stripNumbers(paragraphs[i])
            val words = LETTERS_AND_DIGITS.matchesIn(entry).map { it.value.uppercase() }
            // Contents entries are in ordinary case; capitals in the front pages are title-page text.
            if (words.isEmpty() || !isHeadingLike(entry) || isAllCaps(entry) || (words.size == 1 && words[0].length < 3)) null else words
        }.distinct()
        if (titles.size < MIN_CONTENTS_MATCHES) return paragraphs
        val byFirstWord = titles.groupBy { it.first() }
        // Capitalised words the book uses, so a lost first letter of a name can be put back ("eiyn" → "Leiyn").
        val bookCapitalised = HashMap<String, Int>()
        paragraphs.forEach { paragraph ->
            LETTERS_AND_DIGITS.matchesIn(paragraph).forEach { token ->
                if (token.value[0].isUpperCase() && token.value.length > 2) {
                    val key = token.value.lowercase()
                    bookCapitalised[key] = (bookCapitalised[key] ?: 0) + 1
                }
            }
        }
        val out = ArrayList<String>(paragraphs.size + 64)
        paragraphs.forEach { paragraph ->
            val tokens = LETTERS_AND_DIGITS.matchesIn(paragraph)
            val headings = mutableListOf<IntRange>()
            var j = 0
            while (j < tokens.size) {
                val title = if (isAllCaps(tokens[j].value)) {
                    byFirstWord[tokens[j].value]?.filter { words ->
                        j + words.size <= tokens.size &&
                            words.indices.all { k -> tokens[j + k].value == words[k] && isAllCaps(tokens[j + k].value) } &&
                            tokens.getOrNull(j + words.size)?.value.let { next -> next == null || next == "A" || next == "I" || !isAllCaps(next) }
                    }?.maxByOrNull { it.size }
                } else {
                    null
                }
                if (title == null || (tokens.size == title.size && paragraph.trim().length == tokens.last().range.last + 1 - tokens.first().range.first)) {
                    j++ // no title here, or the paragraph already is just the heading
                    continue
                }
                headings += tokens[j].range.first..tokens[j + title.size - 1].range.last
                j += title.size
            }
            if (headings.isEmpty()) {
                out += paragraph
                return@forEach
            }
            var last = 0
            headings.forEach { range ->
                paragraph.substring(last, range.first).trim().takeIf { it.isNotEmpty() }?.let { out += it }
                out += paragraph.substring(range.first, range.last + 1)
                last = range.last + 1
            }
            paragraph.substring(last).trim().takeIf { it.isNotEmpty() }?.let { out += restoreDropCap(it, dictionary, bookCapitalised) }
        }
        return out
    }

    /**
     * A chapter's decorative first letter is usually lost by OCR ("he heard them" for "She heard them").
     * Puts back the capital that makes a real word fitting best with the next word; unchanged if none does.
     */
    private fun restoreDropCap(text: String, dictionary: SpellingDictionary?, bookCapitalised: Map<String, Int>): String {
        if (dictionary == null || !text[0].isLowerCase()) return text
        val words = WORD_WITH_APOSTROPHE.matchesIn(text.take(80))
        val firstMatch = words.firstOrNull()?.takeIf { it.range.first == 0 } ?: return text
        val first = firstMatch.value.replace('’', '\'')
        val next = words.getOrNull(1)?.value?.lowercase()?.replace('’', '\'')
        val options = ('a'..'z').map { it + first }
        // 1. A name the book capitalises but the dictionary doesn't know: "eiyn" → "Leiyn".
        val name = options.map { it.substringBefore('\'') }
            .filter { !dictionary.contains(it) && (bookCapitalised[it] ?: 0) >= NAME_MIN_COUNT }
            .maxByOrNull { bookCapitalised[it] ?: 0 }
            ?.let { it + first.substring(it.length - 1) } // keep "'s": "eiyn's" → "leiyn's"
        // 2. A real word that goes with the next word, favouring usual sentence openers: "s the" → "As the".
        val fitting = next?.let {
            options.filter { option -> dictionary.contains(option) && dictionary.pair(option, it) > 0 }
                .maxByOrNull { option -> dictionary.pair(option, it) * (if (option in OPENERS) OPENER_WEIGHT else 1) }
        }
        // 3. Before "hadn't", "wasn't", "don't": a pronoun ("t wasn't" → "It wasn't", "he hadn't" → "She hadn't").
        val plural = next in setOf("don't", "aren't", "weren't", "haven't") // not "he don't"
        val pronoun = if (next?.endsWith("n't") == true) {
            options.filter { it in PRONOUNS && !(plural && it in SINGULAR) }.maxByOrNull { dictionary.frequency(it) }
        } else {
            null
        }
        // 4. Otherwise a usual opener ("er bow" → "Her bow"), or for longer pieces the most common real word.
        val opener = options.filter { it in OPENERS }.maxByOrNull { dictionary.frequency(it) }
        val common = if (first.length >= 3) options.filter { dictionary.contains(it) }.maxByOrNull { dictionary.frequency(it) } else null
        val best = name ?: pronoun ?: fitting ?: opener ?: common ?: return text
        return best.replaceFirstChar { it.uppercase() } + text.substring(firstMatch.value.length)
    }

    private fun fromContentsPage(paragraphs: List<String>): Result? {
        val window = frontWindow(paragraphs.size)
        val front = paragraphs.subList(0, window).map(::normalize)
        // Where each front paragraph starts in frontText, to turn a match position back into a paragraph.
        val starts = IntArray(front.size)
        for (i in 1 until front.size) starts[i] = starts[i - 1] + front[i - 1].length + 1
        // Heading-like body paragraphs whose text appears in the front pages.
        val found = (0 until paragraphs.size).mapNotNull { index ->
            val text = splitHeading(paragraphs, index) ?: paragraphs[index].trim()
            if (!isHeadingLike(text)) return@mapNotNull null
            val key = normalize(text)
            if (key.length < 3 || BARE_NUMBER.matches(text)) return@mapNotNull null
            // Only a contents-style line counts as a listing, and only for the title as whole words:
            // "plan" inside "explained" or a sentence that happens to use the word is not a contents entry.
            val frontParagraph = front.indices.firstOrNull { i -> i != index && isListLine(paragraphs[i]) && wordIndexOf(front[i], key) >= 0 }
                ?: return@mapNotNull null
            val position = starts[frontParagraph] + wordIndexOf(front[frontParagraph], key)
            // Show the title as the contents page prints it ("Pride & Shame", not "PRIDE && SHAME").
            val listed = stripNumbers(paragraphs[frontParagraph]).takeIf { normalize(it) == key && isAllCaps(text) }
            // A heading in capitals must be exactly a contents entry ("THE" from a map is not "The Writ").
            if (isAllCaps(text) && listed == null) return@mapNotNull null
            Match(index, position, text, listed ?: text)
        }.filter { it.index >= window / 4 } // body headings come after the contents page
        val ordered = longestIncreasingByPosition(found)
        if (ordered.size < MIN_CONTENTS_MATCHES) return null

        // The printed contents page: front paragraphs listing these titles (plus a "Contents" heading).
        val keys = ordered.map { normalize(it.text) }
        val listing = front.indices.filter { i ->
            i < ordered.first().index && isListLine(paragraphs[i]) && keys.any { wordIndexOf(front[i], it) >= 0 }
        }
        if (listing.isEmpty()) return null
        var startIndex = listing.first()
        if (startIndex > 0 && CONTENTS_HEADING.matches(paragraphs[startIndex - 1].trim())) startIndex--
        // The listing continues while the lines stay list-like (entries for chapters not matched in the body).
        var endIndex = listing.last()
        while (endIndex + 1 < ordered.first().index && isListLine(paragraphs[endIndex + 1])) endIndex++
        // Safety net: the skipped range never includes real text. It ends before the first paragraph that is
        // not a contents line (a sentence, a picture), so a wrong match can't drop part of the story.
        val firstNonList = (startIndex + 1..endIndex).firstOrNull { !isListLine(paragraphs[it]) }
        if (firstNonList != null) endIndex = firstNonList - 1
        val chapters = ordered.map { Chapter(it.index, it.title, headingParagraphs = if (splitHeading(paragraphs, it.index) != null) 2 else 1) }
        return Result(chapters, startIndex..endIndex, Source.CONTENTS_PAGE, paragraphs)
    }

    /** Where [key] appears in [text] as whole words (both already normalized), or -1. */
    private fun wordIndexOf(text: String, key: String): Int {
        var from = 0
        while (true) {
            val at = text.indexOf(key, from)
            if (at < 0) return -1
            val startOk = at == 0 || text[at - 1] == ' '
            val end = at + key.length
            if (startOk && (end == text.length || text[end] == ' ')) return at
            from = at + 1
        }
    }

    /** A contents-page line: a short heading-like entry, possibly several run together ("Chapter 25 Chapter 26"). */
    private fun isListLine(paragraph: String): Boolean {
        val text = paragraph.trim()
        return text.length <= 2 * MAX_HEADING_LENGTH && '\n' !in text && text.isNotEmpty() &&
            text.last() !in ".,;\"”" && (text.first().isUpperCase() || text.first().isDigit())
    }

    /** Keeps the largest set of matches whose order in the book matches their order on the contents page. */
    private fun longestIncreasingByPosition(items: List<Match>): List<Match> {
        if (items.isEmpty()) return items
        val length = IntArray(items.size) { 1 }
        val previous = IntArray(items.size) { -1 }
        for (i in items.indices) for (j in 0 until i) {
            if (items[j].position < items[i].position && items[j].index < items[i].index && length[j] + 1 > length[i]) {
                length[i] = length[j] + 1
                previous[i] = j
            }
        }
        var at = length.indices.maxByOrNull { length[it] } ?: return emptyList()
        val chain = mutableListOf<Match>()
        while (at >= 0) {
            chain += items[at]
            at = previous[at]
        }
        return chain.reversed()
    }

    private fun fromHeadings(paragraphs: List<String>): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        val bareNumbers = mutableListOf<Chapter>()
        var index = 0
        while (index < paragraphs.size) {
            val split = splitHeading(paragraphs, index)
            val text = split ?: paragraphs[index].trim()
            val offset = if (split != null) 1 else 0
            val next = paragraphs.getOrNull(index + 1 + offset)?.trim()
            val nextIsTitle = next != null && isHeadingLike(next) && !CHAPTER_HEADING.matches(next) && !BARE_NUMBER.matches(next)
            when {
                text.length <= MAX_HEADING_LENGTH && CHAPTER_HEADING.matches(text) -> {
                    val ownTitle = CHAPTER_HEADING.find(text)?.groupValues?.get(1).orEmpty().trim()
                    if (ownTitle.isEmpty() && nextIsTitle) {
                        chapters += Chapter(index, "$text: $next", headingParagraphs = 2 + offset)
                        index += 1 + offset
                    } else {
                        chapters += Chapter(index, text, headingParagraphs = 1 + offset)
                        index += offset
                    }
                }
                NAMED_SECTION.matches(text) && isHeadingLike(text) -> chapters += Chapter(index, text)
                BARE_NUMBER.matches(text) && text.length <= 12 -> {
                    if (nextIsTitle) {
                        bareNumbers += Chapter(index, "$text: $next", headingParagraphs = 2)
                        index++
                    } else {
                        bareNumbers += Chapter(index, text)
                    }
                }
            }
            index++
        }
        // Bare numbers are only trusted when there are several of them.
        if (bareNumbers.size >= 3) chapters += bareNumbers
        val sorted = chapters.sortedBy { it.paragraph }
        // A heading followed straight away by another heading, with no text between, is a contents listing.
        return sorted.filterIndexed { i, chapter ->
            val next = sorted.getOrNull(i + 1)
            next == null || next.paragraph > chapter.paragraph + chapter.headingParagraphs
        }
    }

    /** A short description for the Review screen. */
    fun describe(result: Result): String = when (result.source) {
        Source.CONTENTS_PAGE -> "${result.chapters.size} chapters, matched to the book's own contents page (left out of the EPUB text)."
        Source.HEADINGS -> "${result.chapters.size} chapters, found from chapter headings."
        Source.NONE -> "No chapters found: the EPUB will be one continuous section."
    }
}
