// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - YouTube Music repository.
//
// The one place that talks to YouTube Music on behalf of the UI. It owns the
// session, the request shapes and the retry policy, and hands back model types
// only - so a screen never sees a JsonElement and never builds a request.

package com.ihimanshunayak.freemusic.desktop.data.innertube

import com.ihimanshunayak.freemusic.desktop.data.Http
import com.ihimanshunayak.freemusic.desktop.model.SearchResult
import com.ihimanshunayak.freemusic.desktop.model.Track
import com.ihimanshunayak.freemusic.desktop.util.Log
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.YouTubeLocale
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Search and browse against YouTube Music.
 *
 * Errors are surfaced as [MusicException] with the status code attached, because
 * a 400 from this API almost always means the session was built wrongly and the
 * raw code is the only clue that distinguishes that from a network problem.
 */
class MusicRepository(
    private val session: YouTubeSession,
    private val language: () -> String = { "en" },
    private val region: () -> String = { "US" },
) {

    class MusicException(message: String, val status: Int? = null, cause: Throwable? = null) :
        Exception(message, cause)

    /**
     * A WEB_REMIX browse call.
     *
     * `browseId` is what selects the page: `FEmusic_home` for the home shelf,
     * `VL<playlistId>` for a playlist, `MPREb_...` for an album, and a search is
     * done through [search] instead because it has its own endpoint.
     */
    suspend fun browse(
        browseId: String,
        params: String? = null,
    ): JsonElement = withRetry("browse $browseId") {
        session.ensureVisitorData()
        session.innerTube.locale = YouTubeLocale(gl = region(), hl = language())
        val response = session.innerTube.browse(
            client = YouTubeClient.WEB_REMIX,
            browseId = browseId,
            params = params,
            continuation = null,
            setLogin = false,
        )
        readJson("browse $browseId", response)
    }

    suspend fun search(query: String, params: String? = null): JsonElement =
        withRetry("search") {
            session.ensureVisitorData()
            session.innerTube.locale = YouTubeLocale(gl = region(), hl = language())
            val response = session.innerTube.search(
                client = YouTubeClient.WEB_REMIX,
                query = query,
                params = params,
            )
            readJson("search", response)
        }

    suspend fun searchSuggestions(input: String): List<String> = withRetry("suggestions") {
        session.ensureVisitorData()
        session.innerTube.locale = YouTubeLocale(gl = region(), hl = language())
        val response = session.innerTube.getSearchSuggestions(
            client = YouTubeClient.WEB_REMIX,
            input = input,
        )
        InnertubeParser.parseSuggestions(readJson("suggestions", response))
    }

    /** Search, parsed. The UI's primary entry point. */
    suspend fun searchResults(query: String): List<SearchResult> =
        InnertubeParser.parseSearchResults(search(query))

    /**
     * The next page of a search.
     *
     * YouTube Music pages search with a continuation token rather than an offset,
     * so the token from the previous response is the only way forward.
     */
    suspend fun searchNextPage(continuation: String): List<SearchResult> =
        InnertubeParser.parseSearchResults(
            withRetry("search continuation") {
                session.innerTube.locale = YouTubeLocale(gl = region(), hl = language())
                val response = session.innerTube.search(
                    client = YouTubeClient.WEB_REMIX,
                    continuation = continuation,
                )
                readJson("search continuation", response)
            }
        )

    /**
     * Home and library shelves.
     *
     * `FEmusic_home` is the anonymous feed. It is the cheapest way to prove the
     * session works, because it needs no query and returns a large payload.
     */
    suspend fun home(): JsonElement = browse(HOME_BROWSE_ID)

    /** Turns a search row into something playable. */
    fun toTrack(result: SearchResult): Track? {
        val videoId = result.videoId ?: return null
        val artist = result.subtitle.split(" - ").firstOrNull()?.trim().orEmpty()
        return Track(
            id = videoId,
            title = result.title,
            artist = artist.ifBlank { "Unknown artist" },
            durationSeconds = result.durationSeconds,
            thumbnailUrl = result.thumbnailUrl,
            videoId = videoId,
        )
    }

    // ---- plumbing -----------------------------------------------------------

    /**
     * Retries transport failures only.
     *
     * An HTTP status is an answer, and repeating the question will not change it;
     * a socket torn down mid-flight is weather, and asking again usually works.
     * Cancellation is never caught, so a search the user has typed past stops
     * here instead of retrying on behalf of a query nobody is waiting for.
     */
    private suspend fun <T> withRetry(
        what: String,
        attempts: Int = 3,
        block: suspend () -> T,
    ): T {
        var backoff = 400L
        var last: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MusicException) {
                // Status codes are not retried; they are the service's answer.
                throw e
            } catch (e: Throwable) {
                last = e
                if (attempt < attempts - 1) {
                    Log.w("$what failed on attempt ${attempt + 1}/$attempts (${e::class.simpleName}), retrying in ${backoff}ms", tag = "music")
                    delay(backoff)
                    backoff *= 2
                }
            }
        }
        throw MusicException("$what failed after $attempts attempts", cause = last)
    }

    private suspend fun readJson(what: String, response: HttpResponse): JsonElement {
        val status = response.status.value
        val body = response.bodyAsText()
        if (status !in 200..299) {
            throw MusicException("$what failed with HTTP $status: ${body.take(200)}", status = status)
        }
        val json = runCatching { Http.json.parseToJsonElement(body) }.getOrElse {
            throw MusicException("$what returned unparseable JSON", status = status, cause = it)
        }
        session.adoptVisitorDataFrom(json)
        return json
    }

    /** Convenience for callers that only need a sub-object. */
    fun JsonElement.objectAt(vararg path: String): JsonElement? {
        var current: JsonElement = this
        for (key in path) {
            current = current.jsonObject[key] ?: return null
        }
        return current
    }

    companion object {
        /** The anonymous home feed. Needs no cookie and no query. */
        const val HOME_BROWSE_ID = "FEmusic_home"

        /** Anonymous "new releases" shelf. Cheaper than home when only a probe is needed. */
        const val EXPLORE_BROWSE_ID = "FEmusic_explore"
    }
}

/**
 * Runs a block off the UI thread.
 *
 * Every network call here blocks on sockets, and a Compose click handler runs on
 * the UI dispatcher - so this is not optional, it is what stops the window
 * freezing when a search is slow.
 */
suspend fun <T> onIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

/** Adds the headers YouTube expects on every API call, for callers that build requests directly. */
fun HttpRequestBuilder.youTubeMusicHeaders(userAgent: String, clientVersion: String) {
    header("User-Agent", userAgent)
    header("X-YouTube-Client-Name", WEB_REMIX_CLIENT_ID)
    header("X-YouTube-Client-Version", clientVersion)
    parameter("prettyPrint", "false")
}

const val WEB_REMIX_CLIENT_ID = "67"
