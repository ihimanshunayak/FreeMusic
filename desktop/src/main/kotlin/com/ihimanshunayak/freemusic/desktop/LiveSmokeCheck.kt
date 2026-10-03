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
    val shelves = music.home()
    check("home payload arrives", shelves.toString().length > 200, "${shelves.toString().length} chars of JSON")

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
                if (!response.isSuccessful) {
                    val body = response.body?.string()?.take(400).orEmpty()
                    println()
                    println("  itag=${stream.bitrateKbps}kbps  mime=${stream.mimeType}")
                    println("  $body")
                    println()
                }
                check("audio request succeeded", response.isSuccessful, "HTTP ${response.code}")
                response.body.byteStream().readNBytes(65_536).size
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
