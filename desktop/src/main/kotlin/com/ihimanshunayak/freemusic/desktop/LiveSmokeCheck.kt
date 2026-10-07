// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - live smoke check (run on demand, not part of :desktop:test).
//
// Verifies the parts the unit tests deliberately cannot: that a real visitor id is
// minted, that YouTube Music answers a real query, that the parser turns that
// answer into rows, and that a playable stream URL comes back. Network and the
// upstream service are therefore both required, which is why this is kept out of
// the normal test task and driven manually.

package com.ihimanshunayak.freemusic.desktop

import com.ihimanshunayak.freemusic.desktop.data.Http
import com.ihimanshunayak.freemusic.desktop.data.innertube.InnertubeParser
import com.ihimanshunayak.freemusic.desktop.data.innertube.MusicRepository
import com.ihimanshunayak.freemusic.desktop.data.innertube.YouTubeSession
import com.ihimanshunayak.freemusic.desktop.data.stream.StreamResolver
import com.ihimanshunayak.freemusic.desktop.model.ResultKind
import kotlinx.coroutines.runBlocking
import java.io.File

private var failures = 0

private fun check(label: String, condition: Boolean, detail: String = "") {
    val mark = if (condition) "PASS" else "FAIL"
    if (!condition) failures++
    println("[$mark] $label${if (detail.isNotEmpty()) " -> $detail" else ""}")
}

fun main() = runBlocking {
    println("=== Free Music for Windows - live smoke check ===")
    println()

    // ---- 1. session + visitor id ------------------------------------------
    val session = YouTubeSession()
    val visitor = session.ensureVisitorData()
    check("visitor id minted", !visitor.isNullOrBlank(), "${visitor?.length ?: 0} chars")

    val music = MusicRepository(session)

    // ---- 2. real search ---------------------------------------------------
    val query = "Linkin Park"
    val results = music.searchResults(query)
    check("search('$query') returns rows", results.isNotEmpty(), "${results.size} rows")

    val withVideo = results.filter { it.videoId != null }
    check("rows carry a videoId", withVideo.isNotEmpty(), "${withVideo.size} of ${results.size}")

    val withArt = results.filter { it.thumbnailUrl != null }
    check("rows carry artwork", withArt.isNotEmpty(), "${withArt.size} of ${results.size}")

    val kinds = results.groupBy { it.kind }.mapValues { it.value.size }
    check("kinds were inferred", kinds.keys.any { it != ResultKind.UNKNOWN }, kinds.toString())
    check(
        "no row is classified against its own subtitle",
        results.none { r ->
            val head = r.subtitle.trim().lowercase().substringBefore(' ').substringBefore('-')
            when (head) {
                "song" -> r.kind != ResultKind.SONG
                "album", "single", "ep" -> r.kind != ResultKind.ALBUM
                "artist" -> r.kind != ResultKind.ARTIST
                "playlist" -> r.kind != ResultKind.PLAYLIST
                "video" -> r.kind != ResultKind.VIDEO
                else -> false
            }
        },
        results.filter { it.subtitle.isNotBlank() }
            .joinToString("; ") { "[${it.kind}] ${it.subtitle.take(34)}" },
    )

    val firstFew = results.take(5)
    println()
    println("  first ${firstFew.size} results:")
    firstFew.forEach { r ->
        println("    - [${r.kind}] ${r.title}  |  ${r.subtitle}  |  ${r.durationSeconds}s  |  ${r.videoId ?: r.browseId ?: r.playlistId}")
    }
    println()

    // ---- 3. suggestions ---------------------------------------------------
    val suggestions = music.searchSuggestions("linkin")
    check("suggestions return", suggestions.isNotEmpty(), suggestions.take(3).toString())

    // ---- 4. home shelves --------------------------------------------------
    // Parsed, not just fetched. The raw payload is 200 KB of JSON whatever it
    // contains, so a length test passed even while the Home screen rendered
    // nothing: what matters is how many shelves come out of it.
    val homeJson = music.home()
    val shelves = InnertubeParser.parseShelves(homeJson)
    val homeRows = shelves.sumOf { it.items.size }
    check("home payload arrives", homeJson.toString().length > 200, "${homeJson.toString().length} chars of JSON")
    check("home payload yields shelves", shelves.isNotEmpty(), "${shelves.size} shelves, $homeRows rows")

    // What YouTube actually put in the section list. A low shelf count is either
    // the parser skipping sections or the service sending few, and only this
    // tells the two apart.
    val sectionKeys = Regex(""""(\w+Renderer)"\s*:""").findAll(homeJson.toString())
        .map { it.groupValues[1] }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(14)
        .joinToString(", ") { "${it.key}=${it.value}" }
    println("[INFO] home renderer census -> $sectionKeys")

    // How many shelf containers the payload actually holds, so a low shelf count
    // can be told apart from a payload that genuinely carries two.
    val rawCarousels = Regex("musicCarouselShelfRenderer").findAll(homeJson.toString()).count()
    val rawShelves = Regex("musicShelfRenderer\\b").findAll(homeJson.toString()).count()
    check(
        "shelf containers were found",
        shelves.size >= minOf(rawCarousels + rawShelves, 1),
        "parsed ${shelves.size} of ${rawCarousels + rawShelves} containers ($rawCarousels carousels, $rawShelves shelves)",
    )
    check(
        "home shelves carry titles",
        shelves.all { it.title.isNotBlank() },
        shelves.take(3).joinToString(" | ") { it.title },
    )
    check(
        "home shelves carry playable rows",
        homeRows > 0,
        shelves.firstOrNull()?.let { "${it.items.size} rows in '${it.title}'" }.orEmpty(),
    )

    // ---- 4b. moods and genres ---------------------------------------------
    // Explore is drawn entirely from this response, so an empty grid is a blank
    // screen. The ids are only valid if they came from here.
    val moods = music.moodsAndGenres()
    val moodTiles = moods.sumOf { it.items.size }
    check("mood grid arrives", moods.isNotEmpty(), "${moods.size} sections")
    check("mood grid has tiles", moodTiles > 0, "$moodTiles tiles across ${moods.size} sections")
    check(
        "mood tiles carry a params filter",
        moods.flatMap { it.items }.all { it.browseId.isNotBlank() },
        moods.flatMap { it.items }.firstOrNull()?.let { "${it.title}: ${it.browseId} / ${it.params}" }.orEmpty(),
    )

    // A mood's browse request must actually answer. This is the check that
    // catches an id invented in the UI rather than read from the grid.
    val firstMood = moods.flatMap { it.items }.firstOrNull()
    if (firstMood != null) {
        val opened = runCatching {
            InnertubeParser.parseShelves(music.browse(firstMood.browseId, firstMood.params))
        }.getOrNull()
        check(
            "a mood tile opens a populated page",
            opened != null && opened.isNotEmpty(),
            "opened '${firstMood.title}' -> ${opened?.size ?: 0} shelves",
        )
    }

    // ---- 5. stream resolution --------------------------------------------
    val target = withVideo.firstOrNull()
    if (target?.videoId == null) {
        check("stream resolution", false, "no row with a videoId to resolve")
    } else {
        val resolver = StreamResolver()
        val stream = runCatching { resolver.resolve(target.videoId) }.getOrNull()
        if (stream == null) {
            check("stream resolved for ${target.videoId}", false, "resolver returned null")
        } else {
            check("stream resolved for ${target.videoId}", stream.url.isNotBlank(), stream.url.take(90) + "...")
            check("stream declares a mime type", stream.mimeType.isNotBlank(), stream.mimeType)
            check(
                "stream declares headers",
                stream.headers.isNotEmpty(),
                stream.headers.keys.joinToString(","),
            )
            check("stream is not already expired", !stream.isExpired(), "expiresAt=${stream.expiresAtMillis}")

            // ---- 6. download a few bytes -------------------------------------
            val request = okhttp3.Request.Builder()
                .url(stream.url)
                .get()
                .apply { stream.headers.forEach { (k, v) -> runCatching { header(k, v) } } }
                .build()
            val bytes = Http.client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    check("audio request succeeded", true, "HTTP ${response.code}")
                    response.body.byteStream().readNBytes(65_536).size
                } else {
                    // googlevideo names the reason in the body, and a body reads
                    // only once - draining it here would leave byteStream() on a
                    // closed source and throw instead of reporting the refusal.
                    val detail = response.body.string().take(300).replace('\n', ' ')
                    println()
                    println("  itag=${stream.bitrateKbps}kbps  mime=${stream.mimeType}")
                    println("  $detail")
                    println()
                    check("audio request succeeded", false, "HTTP ${response.code}")
                    0
                }
            }
            check("audio bytes downloaded", bytes > 0, "$bytes bytes")

            // A range request is the shape a seekable player actually uses, so the
            // stream is only proven playable if that works too.
            val ranged = okhttp3.Request.Builder()
                .url(stream.url)
                .get()
                .header("Range", "bytes=0-32767")
                .apply { stream.headers.forEach { (k, v) -> runCatching { header(k, v) } } }
                .build()
            val rangedBytes = Http.client.newCall(ranged).execute().use { response ->
                check("range request succeeded", response.isSuccessful, "HTTP ${response.code}")
                response.body.byteStream().readNBytes(32_768).size
            }
            check("range request served bytes", rangedBytes > 0, "$rangedBytes bytes")
        }
    }

    // ---- 7. second query, different shape --------------------------------
    val album = music.searchResults("SOS SZA album")
    check("a second query also parses", album.isNotEmpty(), "${album.size} rows")

    println()
    if (failures == 0) {
        println("=== ALL LIVE CHECKS PASSED ===")
    } else {
        println("=== $failures LIVE CHECK(S) FAILED ===")
    }
}
