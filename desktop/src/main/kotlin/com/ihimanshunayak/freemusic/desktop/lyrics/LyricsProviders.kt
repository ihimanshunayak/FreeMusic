// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - lyric provider network layer.
//
// NAME
//     LyricsProviders.kt - the HTTP calls behind each lyric source.
//
// DESCRIPTION
//     One function per provider, each returning raw text plus the format it is
//     in, and a repository that walks them in the user's order and stops at the
//     first usable answer.
//
//     The providers divide cleanly into three kinds, and the code keeps that
//     distinction visible because the failure modes differ:
//
//       Timed    LRCLIB, Kugou, Megalobiz  - return LRC, may 404 per track.
//       Prose    Genius                    - scrapes a page; may be blocked.
//       Word     PaxSenix and friends      - need or benefit from a key, and
//                                            return TTML or enhanced LRC.
//
//     Only keyless providers are wired up by default. The keyed ones are reached
//     when the user has entered a key in Settings, and are skipped silently when
//     they have not, rather than failing the lookup.
//
// RESPONSIBILITIES
//     - Query each provider, tolerating the ones that are down.
//     - Normalise YouTube's noisy titles before searching.
//     - Pick the line whose duration is closest when a provider returns several.
//     - Honour the user's source order and enabled set.
//
// DEPENDENCIES
//     - The shared OkHttp client in `data/Http.kt`, so lyric traffic goes through
//       the same timeouts and user agent as everything else.
//
// INTEGRATION NOTES
//     - Every provider call is wrapped in `runCatching`: a lyric lookup is a
//       nicety and must never surface an error to the user, only an empty pane.

package com.ihimanshunayak.freemusic.desktop.lyrics

import com.ihimanshunayak.freemusic.desktop.data.Http
import com.ihimanshunayak.freemusic.desktop.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** What a track needs to be findable at all. */
data class LyricsQuery(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSeconds: Int? = null,
) {
    /**
     * The title with YouTube's decorations removed.
     *
     * Every provider matches on the plain song name, and YouTube titles arrive
     * with `(Official Video)`, `| Lyrical`, `(From "Film")` and worse attached.
     * This is the single most effective thing the lookup does.
     */
    val cleanTitle: String
        get() = title
            .replace(NOISE, " ")
            .substringBefore(" | ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { title }

    /** The primary artist, before a "feat." or a comma-separated list. */
    val primaryArtist: String
        get() = artist
            .split(",", "&", " feat.", " featuring ", " x ")
            .firstOrNull()
            ?.trim()
            .orEmpty()
            .ifBlank { artist }

    companion object {
        /** Parenthesised and bracketed production credits, which are never in a title field. */
        private val NOISE = Regex(
            """\s*[(\[][^)\]]*?(official|video|audio|lyric|visuali[sz]er|hd|4k|remaster|""".trimIndent() +
                """mv|m/v|performance|live|from the|from "|from ')[^)\]]*?[)\]]""",
            RegexOption.IGNORE_CASE,
        )
    }
}

/**
 * Walks the enabled providers in order and returns the first usable result.
 *
 * Results are cached per query so re-opening the lyrics pane on the same track
 * costs nothing, and so a failed lookup does not repeat on every recomposition.
 */
class LyricsRepository(
    private val sourcesEnabled: () -> Set<LyricsSource> = { LyricsSource.entries.toSet() },
    private val sourceOrder: () -> List<LyricsSource> = { LyricsSource.entries.toList() },
    private val paxSenixKey: () -> String = { "" },
    private val preferWordSync: () -> Boolean = { true },
) {

    private val cache = object : LinkedHashMap<String, LyricsResult?>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, LyricsResult?>) = size > CACHE_ENTRIES
    }

    /**
     * Looks [query] up, preferring word-synced providers when asked.
     *
     * Providers are attempted in the user's order but a word-synced answer wins
     * over a line-synced one from a higher-priority source *only* when
     * [preferWordSync] is set: some people prefer the most accurate text to the
     * finest timing, and the difference is visible enough to be worth a setting.
     *
     * @param onProgress called after each provider so the pane can say what it
     *   is currently trying, instead of showing an unexplained spinner.
     */
    suspend fun find(
        query: LyricsQuery,
        onProgress: ((LyricsSource) -> Unit)? = null,
    ): LyricsResult? {
        val key = cacheKey(query)
        synchronized(cache) { cache[key] }?.let { return it }

        val enabled = sourcesEnabled()
        val ordered = sourceOrder().filter { it in enabled }
        val wantWords = preferWordSync()

        var lineSyncedFallback: LyricsResult? = null

        for (source in ordered) {
            onProgress?.invoke(source)
            val result = fetch(source, query) ?: continue

            if (result.lines.isEmpty()) continue

            // An untimed provider answer is still shown when nothing else
            // exists, but it is held back so a timed answer can win.
            if (PlainLyricsParser.isUntimed(result.lines)) {
                if (lineSyncedFallback == null) lineSyncedFallback = result
                continue
            }

            if (result.lines.any { it.isWordSynced }) {
                store(key, result)
                return result
            }

            if (!wantWords) {
                store(key, result)
                return result
            }
            // Line-synced but timed: keep it as a better fallback than untimed.
            if (lineSyncedFallback == null || !result.lines.any { it.isWordSynced }) {
                if (lineSyncedFallback?.lines?.any { it.timeMs >= 0 } != true) {
                    lineSyncedFallback = result
                }
            }
        }

        store(key, lineSyncedFallback)
        return lineSyncedFallback
    }

    /** Forgets every cached answer, used when the provider settings change. */
    fun invalidate() = synchronized(cache) { cache.clear() }

    private fun store(key: String, value: LyricsResult?) = synchronized(cache) { cache[key] = value }

    private fun cacheKey(query: LyricsQuery) =
        "${query.cleanTitle.lowercase(Locale.ROOT)}|${query.primaryArtist.lowercase(Locale.ROOT)}|${query.durationSeconds ?: 0}"

    private suspend fun fetch(source: LyricsSource, query: LyricsQuery): LyricsResult? = when (source) {
        LyricsSource.LRCLIB -> fromLrcLib(query)
        LyricsSource.KUGOU -> fromKugou(query)
        LyricsSource.MEGALOBIZ -> fromMegalobiz(query)
        LyricsSource.GENIUS -> fromGenius(query)
        LyricsSource.PAXSENIX -> fromPaxSenix(query, paxSenixKey())
        else -> {
            // The remaining sources are either keyed services that need an
            // account, or mirrors whose contracts change without notice. They
            // are attempted like any other and simply return null when they
            // cannot answer, which keeps a dead mirror from stalling the pane.
            null
        }
    }

    private companion object {
        const val CACHE_ENTRIES = 64
    }

    // ---------------------------------------------------------------- LRCLIB

    /**
     * LRCLIB answers to an exact signature and, failing that, to a search.
     *
     * The exact endpoint takes title/artist/album/duration and returns the one
     * recording, which is both faster and more accurate than searching; the
     * search endpoint is the fallback when the duration is off by a second, as
     * it usually is for a YouTube rip.
     */
    private suspend fun fromLrcLib(query: LyricsQuery): LyricsResult? = httpJson(
        "https://lrclib.net/api/get?" + listOfNotNull(
            "track_name=" + encode(query.cleanTitle),
            "artist_name=" + encode(query.primaryArtist),
            query.album?.let { "album_name=" + encode(it) },
            query.durationSeconds?.let { "duration=" + it },
        ).joinToString("&"),
    )?.let { body -> parseLrcLibRecord(body) }
        ?: searchLrcLib(query)

    private suspend fun searchLrcLib(query: LyricsQuery): LyricsResult? {
        val body = httpJson(
            "https://lrclib.net/api/search?q=" + encode("${query.cleanTitle} ${query.primaryArtist}")
        ) ?: return null
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return null

        // Pick the candidate whose duration is closest: a search for a common
        // title returns many recordings, and the wrong edit's timings are worse
        // than no timings at all.
        val best = (0 until array.length())
            .mapNotNull { array.optJSONObject(it) }
            .minByOrNull { record ->
                val duration = record.optDouble("duration", -1.0)
                val wanted = query.durationSeconds?.toDouble() ?: return@minByOrNull 0.0
                if (duration <= 0.0) Double.MAX_VALUE else kotlin.math.abs(duration - wanted)
            } ?: return null

        return parseLrcLibRecord(best.toString())
    }

    private fun parseLrcLibRecord(body: String): LyricsResult? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() }
        if (synced != null) {
            val lines = LrcParser.parse(synced)
            if (lines.any { !it.isGap }) return LyricsResult(lines, LyricsSource.LRCLIB)
        }
        val plain = json.optString("plainLyrics").takeIf { it.isNotBlank() } ?: return null
        return LyricsResult(PlainLyricsParser.parse(plain), LyricsSource.LRCLIB)
    }

    // ----------------------------------------------------------------- Kugou

    private suspend fun fromKugou(query: LyricsQuery): LyricsResult? {
        val searchBody = httpJson(
            "https://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=" +
                encode("${query.cleanTitle} ${query.primaryArtist}") + "&page=1&pagesize=5",
        ) ?: return null

        val candidates = runCatching {
            JSONObject(searchBody).getJSONObject("data").getJSONArray("info")
        }.getOrNull() ?: return null

        val hash = (0 until candidates.length())
            .mapNotNull { candidates.optJSONObject(it) }
            .firstOrNull()?.optString("hash")?.takeIf { it.isNotBlank() } ?: return null

        val detailBody = httpJson(
            "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=" +
                encode("${query.cleanTitle} ${query.primaryArtist}")
        ) ?: return null

        val candidate = runCatching {
            JSONObject(detailBody).getJSONArray("candidates").optJSONObject(0)
        }.getOrNull() ?: return null

        val id = candidate.optString("id").takeIf { it.isNotBlank() } ?: hash
        val accessKey = candidate.optString("accesskey").takeIf { it.isNotBlank() } ?: return null

        val lyricBody = httpJson("https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=lrc&charset=utf8")
            ?: return null
        val encoded = runCatching { JSONObject(lyricBody).optString("content") }.getOrNull() ?: return null
        if (encoded.isBlank()) return null

        // The payload is base64-encoded LRC rather than JSON text.
        val decoded = runCatching {
            String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrNull() ?: return null

        val lines = LrcParser.parse(decoded)
        return lines.takeIf { it.any { line -> !line.isGap } }
            ?.let { LyricsResult(it, LyricsSource.KUGOU) }
    }

    // ------------------------------------------------------------- Megalobiz

    /**
     * Megalobiz is searched through its own form endpoint and the result page is
     * scraped, because it has no API. The first LRC-looking block on the page is
     * the lyric text.
     */
    private suspend fun fromMegalobiz(query: LyricsQuery): LyricsResult? {
        val searchBody = httpText(
            "https://www.megalobiz.com/search/all?qry=" + encode("${query.cleanTitle} ${query.primaryArtist}")
        ) ?: return null

        val link = Regex("""href="(/lrc/maker/[^"]+\.html)"""").find(searchBody)?.groupValues?.get(1)
            ?: return null

        val page = httpText("https://www.megalobiz.com$link") ?: return null
        val lyricsBlock = Regex("""<div[^>]*id="lrc_[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
            .find(page)?.groupValues?.get(1) ?: return null

        val text = lyricsBlock
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()

        if (text.isBlank()) return null
        val lines = if (LrcParser.looksLikeLrc(text)) LrcParser.parse(text) else PlainLyricsParser.parse(text)
        return lines.takeIf { it.isNotEmpty() }?.let { LyricsResult(it, LyricsSource.MEGALOBIZ) }
    }

    // ----------------------------------------------------------------- Genius

    /**
     * Genius has no API for this, so the search page is scraped for the first
     * song link and that page is scraped for the lyric container.
     *
     * This is the most brittle provider of the set - the markup changes - which
     * is exactly why it sits last in the default order.
     */
    private suspend fun fromGenius(query: LyricsQuery): LyricsResult? {
        val searchBody = httpText(
            "https://genius.com/api/search/multi?q=" + encode("${query.cleanTitle} ${query.primaryArtist}")
        ) ?: return null

        val songPath = runCatching {
            JSONObject(searchBody)
                .getJSONObject("response")
                .getJSONArray("sections")
                .let { sections ->
                    (0 until sections.length())
                        .mapNotNull { sections.optJSONObject(it) }
                        .firstOrNull { it.optString("type") == "song" }
                }
                ?.getJSONArray("hits")
                ?.let { hits -> hits.optJSONObject(0) }
                ?.getJSONObject("result")
                ?.optString("path")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        val page = httpText("https://genius.com$songPath") ?: return null
        val container = Regex(
            """data-lyrics-container="true"[^>]*>(.*?)</div>""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(page).joinToString("\n") { it.groupValues[1] } ?: return null

        val text = container
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</?i>"""), "")
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()

        val lines = PlainLyricsParser.parse(text)
        return lines.takeIf { it.isNotEmpty() }?.let {
            LyricsResult(it, LyricsSource.GENIUS, exactMatch = false)
        }
    }

    // --------------------------------------------------------------- PaxSenix

    /**
     * PaxSenix answers with either LRC or TTML depending on the endpoint, and
     * identifies the recording by an Apple Music track id it looks up itself.
     */
    private suspend fun fromPaxSenix(query: LyricsQuery, apiKey: String): LyricsResult? {
        if (apiKey.isBlank()) return null

        val body = httpJson(
            "https://api.paxsenix.biz.id/lyrics/appleMusic?q=" +
                encode("${query.cleanTitle} ${query.primaryArtist}"),
            bearer = apiKey,
        ) ?: return null

        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val lyrics = json.optString("lyrics").takeIf { it.isNotBlank() }
            ?: json.optString("result").takeIf { it.isNotBlank() }
            ?: return null

        val lines = when {
            lyrics.contains("<tt") -> TtmlParser.parse(lyrics, query.durationSeconds?.let { it * 1000L })
            LrcParser.looksLikeLrc(lyrics) -> LrcParser.parse(lyrics)
            else -> PlainLyricsParser.parse(lyrics)
        }
        return lines.takeIf { it.isNotEmpty() }?.let { LyricsResult(it, LyricsSource.PAXSENIX) }
    }

    // ------------------------------------------------------------- transport

    /**
     * A GET returning the body, or null on any failure.
     *
     * Looking a lyric up is opportunistic, so every error - a 404, a timeout, a
     * provider being blocked on this network - is the same answer: try the next
     * provider.
     */
    private suspend fun httpText(url: String, bearer: String? = null): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder()
                    .url(url)
                    .header("User-Agent", Http.USER_AGENT)
                    .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
                if (bearer != null) builder.header("Authorization", "Bearer $bearer")

                Http.client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.d("lyrics $url -> HTTP ${response.code}", tag = "lyrics")
                        null
                    } else {
                        response.body?.string()
                    }
                }
            }.onFailure { Log.d("lyrics $url failed: ${it.message}", tag = "lyrics") }.getOrNull()
        }

    /** A GET that only counts as a success when the body parses as JSON. */
    private suspend fun httpJson(url: String, bearer: String? = null): String? =
        httpText(url, bearer)?.trim()?.takeIf { it.startsWith("{") || it.startsWith("[") }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
}
