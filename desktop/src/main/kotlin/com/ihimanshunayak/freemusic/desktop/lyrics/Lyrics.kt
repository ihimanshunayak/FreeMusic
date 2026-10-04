// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - lyrics timings.
//
// NAME
//     Lyrics.kt - the lyric line model, the LRC/TTML parsers and the source list.
//
// DESCRIPTION
//     Ports the Android app's lyric engine. The interesting part of that engine
//     is not fetching text - a dozen sites will hand text over - it is the
//     timing formats, which are three different problems wearing one name:
//
//       LRC          whole-line stamps only, one per line.
//       Enhanced LRC word stamps inside a line, `<mm:ss.xx>`, which is what
//                    makes karaoke highlighting possible.
//       TTML         a timed XML tree whose spans nest, so a word's end has to
//                    be recovered from a sibling or a parent rather than read.
//
//     All three are parsed here into one [LyricLine] list, because everything
//     downstream - the scrolling view, the offset control, the translation
//     layer - only ever wants "lines, in time order, optionally word-timed".
//
// RESPONSIBILITIES
//     - Parse LRC and Enhanced LRC, including the `<R>` alignment marker.
//     - Parse TTML into lines and words, resolving inherited timings.
//     - Model a line, its words, and the gap lines that separate them.
//     - Name the providers and their ordering.
//
// DEPENDENCIES
//     None beyond the standard library: this file is pure logic and is fully
//     unit-testable without a network or a UI.
//
// INTEGRATION NOTES
//     - Times are milliseconds throughout, matching the player's position clock.
//     - A line with an empty [LyricLine.words] is line-synced; a line with words
//       is word-synced. The UI branches on that rather than on the source, so a
//       provider that upgrades a track from line to word timing is handled
//       without any change upstream.

package com.ihimanshunayak.freemusic.desktop.lyrics

import java.util.Locale

/**
 * One timed fragment inside a line.
 *
 * [endMs] is stored rather than inferred on read because the last word of a
 * line has no successor to borrow an end from, and both Enhanced LRC and TTML
 * state the end explicitly in that case.
 */
data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
) {
    /** This word's share of the line's elapsed time, clamped to 0..1. */
    fun progressAt(positionMs: Long): Float {
        if (positionMs <= startMs) return 0f
        if (positionMs >= endMs) return 1f
        val span = (endMs - startMs).coerceAtLeast(1)
        return ((positionMs - startMs).toFloat() / span).coerceIn(0f, 1f)
    }
}

/** Which edge the text is anchored to, which is what makes RTL lyric files readable. */
enum class LyricAlignment { Start, End }

/**
 * One line of a song, with its own timing and optionally its words'.
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val alignment: LyricAlignment = LyricAlignment.Start,
) {
    /** Whether this line can highlight word by word rather than all at once. */
    val isWordSynced: Boolean get() = words.isNotEmpty()

    /**
     * A blank line whose only job is to hold a gap open.
     *
     * LRC files use a stamp with no text to mean "nothing is sung here"; the
     * scrolling view renders those as a rest so the lyrics do not jump.
     */
    val isGap: Boolean get() = text.isBlank()

    /** The end of this line: its last word's end, or null when unsynced. */
    val endMs: Long? get() = words.maxOfOrNull { it.endMs }
}

/** Everything one provider returned, plus how confident it is. */
data class LyricsResult(
    val lines: List<LyricLine>,
    val source: LyricsSource,
    /** True when the provider matched the exact recording rather than the name. */
    val exactMatch: Boolean = false,
)

/**
 * The lyric providers, in the order the repository asks them.
 *
 * Declaration order is the default priority, so this list *is* the out-of-the-box
 * experience. The three Apple-hosted providers lead because that catalogue is
 * the one with word-level timings in it; `LRCLIB` trails because it is whole-line
 * only but is the one that is essentially never down.
 *
 * The trade-offs are real and personal - one of these is geoblocked in some
 * countries, another runs on volunteer mirrors that come and go - so the order
 * and the enabled set are both user settings.
 */
enum class LyricsSource(
    val label: String,
    val detail: String,
    /** Whether it can return per-word timings, or only whole lines. */
    val wordSynced: Boolean,
) {
    BINI_LYRICS("BiniLyrics", "The same Apple timings, matched on the recording itself", true),
    BETTER_LYRICS("BetterLyrics", "Apple Music timings, word by word", true),
    BETTER_LYRICS_PORTATO("BetterLyrics Portato", "QQ Music karaoke timings", true),
    PAXSENIX("PaxSenix", "Apple Music timings, keyless", true),
    PAXSENIX_SPOTIFY("PaxSenix: Spotify", "Spotify lyrics; API key required", false),
    PAXSENIX_MUSIXMATCH("PaxSenix: Musixmatch", "Musixmatch timings; API key required", true),
    LYRICS_PLUS("LyricsPlus", "Syllable by syllable, on community mirrors", true),
    SIMP_MUSIC("SimpMusic", "Matched on the video, so never the wrong edit", true),
    UNISON("Unison", "Contributed by listeners", true),
    YOUTUBE_TRANSCRIPT("YouTube captions", "Timed captions for the exact video", false),
    YOUTUBE_MUSIC("YouTube Music", "Plain lyrics from the Lyrics tab", false),
    MEGALOBIZ("Megalobiz", "Community-made, whole-line LRC", false),
    KUGOU("Kugou", "Whole lines, strong outside the English catalogue", false),
    LRCLIB("LRCLIB", "Whole lines only, and always up", false),
    MUSIXMATCH("Musixmatch", "Whole lines, from the biggest database", false),
    GENIUS("Genius", "Plain text fallback, massive web catalogue", false),
    ;

    companion object {
        /** The user's saved order, tolerant of a source having been removed since. */
        fun orderedBy(names: List<String>): List<LyricsSource> {
            if (names.isEmpty()) return entries.toList()
            val known = names.mapNotNull { name -> entries.firstOrNull { it.name == name } }
            // Anything added since the preference was written keeps its default
            // position at the end rather than disappearing.
            return known + entries.filter { it !in known }
        }
    }
}

/**
 * Parses an LRC file, plain or enhanced.
 *
 * Enhanced LRC is the same file with `<mm:ss.xx>` stamps inside a line, so both
 * are handled by one pass: a stamp at the start of a line is a line time, a
 * stamp inside a line introduces a word.
 *
 * Two details the Android implementation settled on through use and which are
 * preserved here because they are easy to get wrong:
 *
 *  - A run with no text is a *terminator*, not a word. Enhanced LRC names the
 *    end of the last word with a trailing bare stamp; treating that as a word
 *    would append an empty fragment and steal the real last word's end.
 *  - Nothing represents the intro, because an LRC file starts at the first sung
 *    word. A long run-up therefore gets a synthetic leading gap, or the first
 *    line would appear already scrolled to the top with nothing above it.
 */
object LrcParser {

    /** `<mm:ss.xx>` or `<mm:ss.xxx>`, the only stamp shape encountered in the wild. */
    private val LINE_STAMP = Regex("""^\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val WORD_STAMP = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")

    /** `[ar:Artist]`-style metadata, which is not a timing and must be skipped. */
    private val METADATA_STAMP = Regex("""^\[[a-zA-Z#]+:.*]$""")

    /** The RTL marker written by [LrcWriter]; never appears in genuine A2 output. */
    private const val ALIGNMENT_MARKER = "<R>"

    /** Below this, two stamps are the same musical event rather than a rest. */
    private const val MIN_GAP_MS = 1_200L

    /**
     * Parses [body], returning lines in time order.
     *
     * A blank result means the text was not LRC at all, which lets the caller
     * fall through to a plain-text provider rather than showing an empty pane.
     */
    fun parse(body: String): List<LyricLine> {
        val all = mutableListOf<LyricLine>()

        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || METADATA_STAMP.matches(line)) continue

            // One physical line can carry several stamps when a chorus repeats;
            // each stamp starts its own line sharing the same text.
            val stamps = LINE_STAMP.findAll(line).toList()
            if (stamps.isEmpty()) continue

            val bodyStart = stamps.last().range.last + 1
            val rawBody = line.substring(bodyStart)
            val (text, alignment) = stripAlignmentMarker(rawBody)
            val cleanText = WORD_STAMP.replace(text, "").trim()
            val words = parseWordRuns(text)

            for (stamp in stamps) {
                all += LyricLine(
                    timeMs = msOf(stamp),
                    text = cleanText,
                    words = words,
                    alignment = alignment,
                )
            }
        }

        val sorted = all.sortedBy { it.timeMs }
        return addGaps(sorted)
    }

    /**
     * Drops redundant rests and adds a leading one when the intro is long.
     *
     * A written gap that is immediately followed by another line is not a rest,
     * it is a stray stamp; keeping it would put a blank row in the middle of a
     * verse.
     */
    private fun addGaps(lines: List<LyricLine>): List<LyricLine> {
        val kept = lines.filterIndexed { index, line ->
            if (!line.isGap) return@filterIndexed true
            val next = lines.getOrNull(index + 1) ?: return@filterIndexed true
            next.timeMs - line.timeMs >= MIN_GAP_MS
        }

        val first = kept.firstOrNull() ?: return kept
        return if (!first.isGap && first.timeMs >= MIN_GAP_MS) {
            listOf(LyricLine(0L, "")) + kept
        } else {
            kept
        }
    }

    /** Recovers the alignment marker and returns the body without it. */
    private fun stripAlignmentMarker(body: String): Pair<String, LyricAlignment> =
        if (body.startsWith(ALIGNMENT_MARKER)) {
            body.substringAfter(ALIGNMENT_MARKER) to LyricAlignment.End
        } else {
            body to LyricAlignment.Start
        }

    /**
     * Turns the `<mm:ss.xx>` runs of an enhanced line into words.
     *
     * Each run ends where the next one starts, and a trailing bare stamp is what
     * gives the final word an end at all.
     */
    private fun parseWordRuns(body: String): List<LyricWord> {
        val marks = WORD_STAMP.findAll(body).toList()
        if (marks.isEmpty()) return emptyList()

        val runs = marks.mapIndexed { index, mark ->
            val until = marks.getOrNull(index + 1)?.range?.first ?: body.length
            msOf(mark) to body.substring(mark.range.last + 1, until)
        }
        return runs.mapIndexedNotNull { index, (startMs, text) ->
            if (text.isBlank()) return@mapIndexedNotNull null
            val endMs = runs.getOrNull(index + 1)?.first ?: startMs
            LyricWord(startMs = startMs, endMs = maxOf(endMs, startMs), text = text.trim())
        }
    }

    /** Reads a stamp's time in milliseconds. Two fraction digits mean centiseconds. */
    private fun msOf(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
        val fraction = match.groupValues.getOrNull(3).orEmpty()
        val fractionMs = when (fraction.length) {
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            3 -> fraction.toLong()
            else -> 0L
        }
        return minutes * 60_000 + seconds * 1_000 + fractionMs
    }

    /** Whether [body] looks like LRC at all, used to pick a parser. */
    fun looksLikeLrc(body: String): Boolean =
        body.lineSequence().take(20).any { LINE_STAMP.containsMatchIn(it.trim()) }
}

/**
 * Parses TTML, the timed-text XML that Apple and QQ Music emit.
 *
 * The shape that matters: a `<p>` is a line, its `begin`/`end` are the line's
 * timing, and `<span>` children are words whose ends are frequently *not*
 * stated - a span runs until its next sibling starts, or until its line ends.
 * Recovering that is the whole job, and it is why this cannot be a regex.
 */
object TtmlParser {

    private val PARAGRAPH = Regex("""<p\b([^>]*)>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
    private val SPAN = Regex("""<span\b([^>]*)>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
    private val ATTRIBUTE = Regex("""(\w+)\s*=\s*"([^"]*)"""")
    private val TAG = Regex("""<[^>]+>""")

    /**
     * Parses [body] into lines.
     *
     * @param totalMs the playing track's duration, used to close the final line
     *   when the document does not. Null leaves the last line's words ending
     *   where they start, which the UI renders as "highlight on arrival".
     */
    fun parse(body: String, totalMs: Long? = null): List<LyricLine> {
        val paragraphs = PARAGRAPH.findAll(body)
        val lines = mutableListOf<LyricLine>()

        for ((index, paragraph) in paragraphs.withIndex()) {
            val attributes = attributesOf(paragraph.groupValues[1])
            val begin = attributes["begin"]?.let { timestamp(it) } ?: continue
            val inner = paragraph.groupValues[2]

            val spans = SPAN.findAll(inner).toList()
            val lineEnd = attributes["end"]?.let { timestamp(it) }
                ?: spans.lastOrNull()?.let { attributesOf(it.groupValues[1])["end"]?.let(::timestamp) }
                ?: run {
                    val nextBegin = PARAGRAPH.findAll(body).toList().getOrNull(index + 1)
                        ?.let { attributesOf(it.groupValues[1])["begin"]?.let(::timestamp) }
                    nextBegin ?: totalMs
                }

            if (spans.isEmpty()) {
                val text = plainText(inner)
                lines += LyricLine(begin, text)
                continue
            }

            val words = spans.mapIndexedNotNull { spanIndex, span ->
                val spanAttributes = attributesOf(span.groupValues[1])
                val wordStart = spanAttributes["begin"]?.let(::timestamp) ?: begin
                val next = spans.getOrNull(spanIndex + 1)
                val wordEnd = spanAttributes["end"]?.let(::timestamp)
                    ?: next?.let { attributesOf(it.groupValues[1])["begin"]?.let(::timestamp) }
                    ?: lineEnd
                    ?: wordStart
                val text = plainText(span.groupValues[2])
                if (text.isBlank()) null
                else LyricWord(wordStart, maxOf(wordEnd, wordStart), text)
            }

            val joined = words.joinToString("") { it.text }.trim()
            lines += LyricLine(
                timeMs = begin,
                text = joined.ifBlank { plainText(inner) },
                words = if (words.size > 1) words else emptyList(),
            )
        }

        return lines.sortedBy { it.timeMs }
    }

    /** The stripped text of a fragment, with entities resolved. */
    private fun plainText(fragment: String): String = TAG
        .replace(fragment, "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace(Regex("""&nbsp;?"""), " ")
        .trim()

    private fun attributesOf(raw: String): Map<String, String> =
        ATTRIBUTE.findAll(raw).associate { it.groupValues[1] to it.groupValues[2] }

    /**
     * Reads a TTML time expression into milliseconds.
     *
     * Three forms are legal and all three appear in real files:
     *   `12.5s`        seconds with a fraction
     *   `1:30.5`       clock time
     *   `500ms`        milliseconds
     */
    internal fun timestamp(value: String): Long? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.endsWith("ms")) {
            return trimmed.dropLast(2).toDoubleOrNull()?.toLong()
        }
        if (trimmed.endsWith("s")) {
            return trimmed.dropLast(1).toDoubleOrNull()?.let { (it * 1000).toLong() }
        }
        if (trimmed.endsWith("m")) {
            return trimmed.dropLast(1).toDoubleOrNull()?.let { (it * 60_000).toLong() }
        }
        if (trimmed.endsWith("h")) {
            return trimmed.dropLast(1).toDoubleOrNull()?.let { (it * 3_600_000).toLong() }
        }

        // Clock form: `hh:mm:ss.fff`, `mm:ss.fff` or `ss.fff`.
        val parts = trimmed.split(':')
        return when (parts.size) {
            3 -> {
                val hours = parts[0].toDoubleOrNull() ?: return null
                val minutes = parts[1].toDoubleOrNull() ?: return null
                val seconds = parts[2].toDoubleOrNull() ?: return null
                (hours * 3600_000 + minutes * 60_000 + seconds * 1000).toLong()
            }
            2 -> {
                val minutes = parts[0].toDoubleOrNull() ?: return null
                val seconds = parts[1].toDoubleOrNull() ?: return null
                (minutes * 60_000 + seconds * 1000).toLong()
            }
            else -> trimmed.toDoubleOrNull()?.let { (it * 1000).toLong() }
        }
    }
}

/**
 * Parses the plain-text lyric formats that carry no timings at all.
 *
 * Used as the last resort, and by the providers whose entire answer is prose:
 * Genius pages, the YouTube Music Lyrics tab and most of the community sites.
 */
object PlainLyricsParser {

    /** Section headers like `[Chorus]`, meaningful as structure but not as lyrics. */
    private val SECTION_HEADER = Regex("""^\[[^\d\]][^\]]*]$""")

    /**
     * Splits [body] into un-timed lines, dropping site chrome.
     *
     * The heuristics mirror the Android implementation, which needed all of
     * them in practice: scraped pages put contributor credits, "Read more"
     * links and advertisement markers inside the same element as the lyrics.
     */
    fun parse(body: String, dropSectionHeaders: Boolean = true): List<LyricLine> {
        val noise = listOf(
            "you might also like",
            "read more",
            "embed",
            "advertisement",
            "lyrics powered by",
            "share",
            "contributors",
            "translations",
        )

        return body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { line -> noise.any { line.lowercase(Locale.ROOT).startsWith(it) } }
            .filterNot { line -> line.matches(Regex("""^\d+$""")) }
            .filterNot { dropSectionHeaders && SECTION_HEADER.matches(it) }
            .filterNot { it.startsWith("<") && it.endsWith(">") }
            .map { LyricLine(timeMs = -1L, text = it) }
            .toList()
    }

    /**
     * Whether [lines] came from a plain-text parse and so cannot scroll in time.
     *
     * The UI shows these as a static sheet instead of a follow-along view, which
     * is honest about what the source can do.
     */
    fun isUntimed(lines: List<LyricLine>): Boolean = lines.isNotEmpty() && lines.all { it.timeMs < 0L }
}
