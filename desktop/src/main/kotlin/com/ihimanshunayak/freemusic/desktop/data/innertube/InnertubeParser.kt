// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - YouTube Music parser.
//
// Turns the raw youtubei/v1 JSON YouTube Music returns into the desktop app's
// model types. The renderer shapes here match the Android build's InnertubeParser
// because they come from the same service; the difference is that this parser
// returns plain data with no Compose or Android dependency, so it is directly
// unit-testable.

package com.ihimanshunayak.freemusic.desktop.data.innertube

import com.ihimanshunayak.freemusic.desktop.model.ResultKind
import com.ihimanshunayak.freemusic.desktop.model.SearchResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Locale

/**
 * A shelf as the parser sees it: a heading and its rows.
 *
 * Deliberately not the UI's `HomeShelf`: the parser stays free of UI types so it
 * can be unit-tested against captured JSON with no Compose on the classpath.
 */
data class HomeShelfShape(
    val title: String,
    val items: List<SearchResult>,
)

/**
 * Reads YouTube Music's deeply nested renderer trees.
 *
 * The service nests results inconsistently - the same song appears under
 * `musicResponsiveListItemRenderer` in search but under
 * `musicTwoRowItemRenderer` in a carousel - so rather than model each shape this
 * walks the tree and classifies whatever it finds. That is also what makes it
 * resilient to YouTube adding a wrapper level without notice.
 */
object InnertubeParser {

    // ---- low-level accessors ------------------------------------------------

    private fun JsonElement.obj(): JsonObject? = this as? JsonObject

    private fun JsonObject.obj(key: String): JsonObject? = this[key]?.obj()
    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    /** `text` may be a plain string or a `runs` array; both appear in the wild. */
    private fun JsonObject.textValue(): String? {
        val simple = str("simpleText")
        if (simple != null) return simple
        val runs = arr("runs") ?: return null
        val joined = runs.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("")
        return joined.ifBlank { null }
    }

    /**
     * Every text fragment reachable from [element], in document order.
     *
     * A `text` object with `runs` is split into one entry per run rather than
     * joined, because the runs are individually meaningful: in a search row they
     * are `artist`, `" - "`, `album` and `duration`, and joining them destroys
     * the only copy of the duration.
     */
    private fun collectText(element: JsonElement, out: MutableList<String> = mutableListOf()): List<String> {
        when (element) {
            is JsonObject -> {
                val runs = element["runs"] as? JsonArray
                if (runs != null) {
                    runs.forEach { run -> (run as? JsonObject)?.str("text")?.let { out.add(it) } }
                } else {
                    element.str("simpleText")?.let { out.add(it) }
                    element.forEach { (key, value) -> if (key != "text") collectText(value, out) }
                }
            }
            is JsonArray -> element.forEach { collectText(it, out) }
            else -> Unit
        }
        return out
    }

    /** Every object in the tree that carries [key]. */
    private fun collectRenderers(root: JsonElement, key: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    (node[key] as? JsonObject)?.let { out.add(it) }
                    node.forEach { (_, value) -> walk(value) }
                }
                is JsonArray -> node.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(root)
        return out
    }

    // ---- public surface -----------------------------------------------------

    /**
     * Search rows.
     *
     * Two renderer shapes carry search hits: `musicResponsiveListItemRenderer`
     * (songs, albums, artists as a list) and `musicTwoRowItemRenderer` (the
     * carousel tiles that precede them). Both are read, and duplicates are
     * dropped because the carousel often repeats the first list entry.
     */
    fun parseSearchResults(root: JsonElement): List<SearchResult> {
        val out = mutableListOf<SearchResult>()
        val seenIds = mutableSetOf<String>()

        fun add(result: SearchResult) {
            val key = result.videoId ?: result.browseId ?: result.playlistId ?: return
            if (seenIds.add(key)) out.add(result)
        }

        collectRenderers(root, "musicResponsiveListItemRenderer").forEach { renderer ->
            parseResponsiveItem(renderer)?.let(::add)
        }
        collectRenderers(root, "musicTwoRowItemRenderer").forEach { renderer ->
            parseTwoRowItem(renderer)?.let(::add)
        }
        return out
    }

    /** The `search` endpoint answers with shelf contents that may nest one more level. */
    fun parseSearchResultsFromRaw(json: JsonElement): List<SearchResult> =
        parseSearchResults(json)

    /**
     * Home and browse shelves.
     *
     * A browse response is a list of `musicShelfRenderer` / `musicCarouselShelfRenderer`
     * containers, each with a title and its own rows. They are returned as
     * ordered shelves rather than one flat list because the Home screen draws
     * them as separate rails, and the order YouTube returns is the order the app
     * is meant to show.
     */
    fun parseShelves(root: JsonElement): List<HomeShelfShape> {
        val out = mutableListOf<HomeShelfShape>()
        val seenTitles = mutableSetOf<String>()

        /*
         * Both container types put their rows in a `contents` array and their
         * label in a `title` object, but they nest differently: shelves hang off
         * `musicShelfRenderer`, carousels off `musicCarouselShelfRenderer`, and
         * a browse page may also wrap them in `musicShelfRenderer` inside
         * `sectionListRenderer` with an extra `musicCarouselShelfRenderer` level.
         * Walking for the container keys and parsing rows from each one's own
         * subtree handles all three shapes without special-casing them.
         */
        val containers = mutableListOf<Pair<String, JsonObject>>()
        collectRenderers(root, "musicCarouselShelfRenderer").forEach { containers.add("carousel" to it) }
        collectRenderers(root, "musicShelfRenderer").forEach { containers.add("shelf" to it) }

        containers.forEach { (_, container) ->
            val title = shelfTitle(container) ?: return@forEach
            if (!seenTitles.add(title)) return@forEach
            val items = mutableListOf<SearchResult>()
            collectRenderers(container, "musicResponsiveListItemRenderer").forEach { renderer ->
                parseResponsiveItem(renderer)?.let { items.add(it) }
            }
            collectRenderers(container, "musicTwoRowItemRenderer").forEach { renderer ->
                parseTwoRowItem(renderer)?.let { items.add(it) }
            }
            if (items.isNotEmpty()) {
                out.add(HomeShelfShape(title, items.distinctBy { it.videoId ?: it.browseId ?: it.title }))
            }
        }
        return out
    }

    /**
     * A shelf's heading.
     *
     * It lives under `title` on a carousel and under `header` on a numbered
     * shelf, and YouTube has shipped both spellings of the header renderer, so
     * all three are tried in order rather than picking one.
     */
    private fun shelfTitle(container: JsonObject): String? =
        container.obj("title")?.textValue()
            ?: container.obj("header")?.obj("musicShelfRendererHeader")?.obj("title")?.textValue()
            ?: container.obj("header")?.obj("musicCarouselShelfBasicHeaderRenderer")?.obj("title")?.textValue()

    private fun parseResponsiveItem(renderer: JsonObject): SearchResult? {
        val columns = renderer.arr("flexColumns") ?: return null
        val texts = columns.flatMap { column ->
            column.obj()?.obj("musicResponsiveListItemFlexColumnRenderer")
                ?.obj("text")?.let { collectText(it) }.orEmpty()
        }
        if (texts.isEmpty()) return null

        val title = texts.firstOrNull()?.trim() ?: return null
        // Remaining runs are artist / album / duration / plays, in a stable order
        // that is easier to read positionally than to label from the schema.
        val subtitleParts = texts.drop(1)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isSeparator(it) }
            .filter { !isDurationLike(it) && !isYearLike(it) }

        val duration = texts.firstNotNullOfOrNull { secondsFromLabel(it) } ?: 0
        val endpoint = findEndpoint(renderer)
        val kind = classifyRow(renderer, subtitles = subtitleParts, endpoint = endpoint)

        return SearchResult(
            title = title,
            subtitle = subtitleParts.take(3).joinToString(" - "),
            videoId = endpoint?.videoId,
            browseId = endpoint?.browseId,
            playlistId = endpoint?.playlistId,
            thumbnailUrl = findThumbnail(renderer),
            durationSeconds = duration,
            kind = kind,
        )
    }

    private fun parseTwoRowItem(renderer: JsonObject): SearchResult? {
        val title = renderer.obj("title")?.textValue()?.trim() ?: return null
        val subtitle = renderer.obj("subtitle")?.textValue()?.trim().orEmpty()
        val endpoint = findEndpoint(renderer)
        return SearchResult(
            title = title,
            subtitle = subtitle,
            videoId = endpoint?.videoId,
            browseId = endpoint?.browseId,
            playlistId = endpoint?.playlistId,
            thumbnailUrl = findThumbnail(renderer),
            durationSeconds = secondsFromLabel(subtitle) ?: 0,
            kind = classifyRow(
                renderer,
                subtitles = listOf(subtitle).filter { it.isNotBlank() },
                endpoint = endpoint,
            ),
        )
    }

    private data class Endpoint(val videoId: String? = null, val browseId: String? = null, val playlistId: String? = null)

    /**
     * Pulls the identifiers out of whichever navigation endpoint the renderer
     * carries. `watchEndpoint` is a song, `browseEndpoint` an album or artist,
     * and `watchPlaylistEndpoint` a playlist or radio.
     */
    private fun findEndpoint(renderer: JsonObject): Endpoint? {
        var found: Endpoint? = null

        fun walk(node: JsonElement) {
            if (found != null) return
            when (node) {
                is JsonObject -> {
                    node.obj("navigationEndpoint")?.let { nav ->
                        val watch = nav.obj("watchEndpoint")
                        val browse = nav.obj("browseEndpoint")
                        val watchPlaylist = nav.obj("watchPlaylistEndpoint")
                        if (watch != null) {
                            found = Endpoint(videoId = watch.str("videoId"))
                            return
                        }
                        if (browse != null) {
                            found = Endpoint(browseId = browse.str("browseId"))
                            return
                        }
                        if (watchPlaylist != null) {
                            found = Endpoint(playlistId = watchPlaylist.str("playlistId"))
                            return
                        }
                    }
                    node.forEach { (_, value) -> walk(value) }
                }
                is JsonArray -> node.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(renderer)
        if (found == null) {
            // Some rows only expose a bare videoId.
            val bare = renderer.str("videoId") ?: searchNestedString(renderer, "videoId")
            if (bare != null) found = Endpoint(videoId = bare)
        }
        return found
    }

    private fun searchNestedString(element: JsonElement, key: String): String? = when (element) {
        is JsonObject -> element.str(key) ?: element.values.firstNotNullOfOrNull { searchNestedString(it, key) }
        is JsonArray -> element.firstNotNullOfOrNull { searchNestedString(it, key) }
        else -> null
    }

    /**
     * Thumbnails come as a size list; the largest entry is what a desktop window
     * actually wants.
     *
     * The search cannot stop at the first `thumbnail` object: on a search row
     * that key holds a `musicThumbnailRenderer` wrapper whose own `thumbnails`
     * list is one level further down, so every candidate is tried until one of
     * them actually carries the array.
     */
    private fun findThumbnail(renderer: JsonObject): String? {
        val candidates = collectRenderers(renderer, "thumbnail")
        for (candidate in candidates) {
            val list = candidate.arr("thumbnails") ?: continue
            val url = list.asReversed().firstNotNullOfOrNull { (it as? JsonObject)?.str("url") }
            if (url != null) return url
        }
        return null
    }

    /**
     * Classifies a parse of a row.
     *
     * The priority is by how directly each signal refers to the row itself, which
     * is not the same as how authoritative each signal looks in isolation:
     *
     *  1. **The row's own subtitle.** YouTube Music labels every search row with
     *     its type - `Song - 3.3B plays`, `Album`, `Artist - 66.8M monthly
     *     audience` - and this is the only signal that describes the row rather
     *     than something the row happens to link to.
     *  2. **`musicVideoType`**, present on every playable row, which separates an
     *     uploaded video from a licensed song.
     *  3. **The navigation endpoint**, which proves what activating the row opens.
     *
     * `pageType` is deliberately not consulted anywhere in this file. It looks
     * like the obvious signal, but it is carried by the *overflow-menu entries*
     * inside a row ("Go to album", "Go to artist"), not by the row itself, so a
     * subtree search for it returns whichever menu entry the serialiser happened
     * to emit first. That is how a song row ends up classified as an album, which
     * an end-to-end check against the live service showed happening on most rows
     * of a real result page.
     */
    private fun classifyRow(
        renderer: JsonObject,
        subtitles: List<String>,
        endpoint: Endpoint?,
    ): ResultKind =
        fromSubtitleLabel(subtitles)
            ?: fromMusicVideoType(renderer)
            ?: fromEndpoint(endpoint)

    /**
     * Reads the type out of the row's own subtitle.
     *
     * Every part is examined rather than only the first, because the label's
     * position is not guaranteed across every surface, but the accepted tokens
     * are exact words so that an artist whose name merely starts with one of them
     * cannot be mistaken for a label.
     */
    private fun fromSubtitleLabel(subtitles: List<String>): ResultKind? {
        for (part in subtitles) {
            val head = part.trim().lowercase(Locale.ROOT).substringBefore(' ').trimEnd('-', ':', '.')
            when (head) {
                "song", "songs" -> return ResultKind.SONG
                "video", "videos" -> return ResultKind.VIDEO
                "album", "albums", "single", "singles", "ep" -> return ResultKind.ALBUM
                "artist", "artists" -> return ResultKind.ARTIST
                "playlist", "playlists" -> return ResultKind.PLAYLIST
                "episode", "episodes", "podcast", "podcasts" -> return ResultKind.EPISODE
            }
        }
        return null
    }

    private fun fromMusicVideoType(renderer: JsonObject): ResultKind? =
        when (searchNestedString(renderer, "musicVideoType")?.uppercase(Locale.ROOT)) {
            "MUSIC_VIDEO_TYPE_ATV" -> ResultKind.SONG
            "MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC" -> ResultKind.VIDEO
            else -> null
        }

    /**
     * Falls back to what the row opens.
     *
     * YouTube Music encodes the destination type into the id prefix: `MPREb_` is
     * an album, `UC` an artist channel, `VL` a playlist. A bare video id is the
     * weakest signal here and is therefore checked last.
     */
    private fun fromEndpoint(endpoint: Endpoint?): ResultKind = when {
        endpoint == null -> ResultKind.UNKNOWN
        endpoint.browseId?.startsWith("MPREb_") == true -> ResultKind.ALBUM
        endpoint.browseId?.startsWith("UC") == true -> ResultKind.ARTIST
        endpoint.browseId?.startsWith("VL") == true -> ResultKind.PLAYLIST
        endpoint.playlistId != null -> ResultKind.PLAYLIST
        endpoint.videoId != null -> ResultKind.SONG
        else -> ResultKind.UNKNOWN
    }

    // ---- small text helpers -------------------------------------------------

    private val DURATION = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")
    private val YEAR = Regex("""^(19|20)\d{2}$""")

    fun isDurationLike(text: String): Boolean = DURATION.matches(text.trim())
    fun isYearLike(text: String): Boolean = YEAR.matches(text.trim())

    /** The `" - "` glue between runs, which is never part of a subtitle. */
    fun isSeparator(text: String): Boolean = text.trim().matches(Regex("""^[-–—•|,]+$"""))

    /** `3:07` becomes 187. A bare year is not a duration and returns null. */
    fun secondsFromLabel(label: String): Int? {
        val trimmed = label.trim()
        if (!DURATION.matches(trimmed)) return null
        val parts = trimmed.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }

    /**
     * The continuation token for the next page of results, wherever it sits.
     * Present only when YouTube decided more pages exist.
     */
    fun findContinuation(root: JsonElement): String? =
        collectRenderers(root, "nextContinuationData").firstOrNull()?.str("continuation")
            ?: collectRenderers(root, "continuationCommand").firstOrNull()?.str("token")

    /** Search suggestions, which arrive as `searchSuggestionsSectionRenderer` rows. */
    fun parseSuggestions(root: JsonElement): List<String> {
        val out = mutableListOf<String>()
        collectRenderers(root, "searchSuggestionRenderer").forEach { renderer ->
            renderer.obj("suggestion")?.textValue()?.let { out.add(it) }
        }
        if (out.isEmpty()) {
            collectRenderers(root, "musicResponsiveListItemRenderer").forEach { renderer ->
                renderer.arr("flexColumns")?.let { columns ->
                    columns.firstOrNull()?.obj()
                        ?.obj("musicResponsiveListItemFlexColumnRenderer")
                        ?.obj("text")?.textValue()?.let { out.add(it) }
                }
            }
        }
        return out.distinct()
    }
}
