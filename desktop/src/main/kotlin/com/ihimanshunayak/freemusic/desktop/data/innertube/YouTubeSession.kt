// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - YouTube Music session.
//
// Wraps InnerTubeX for desktop use. The library is pure JVM (verified: its
// bytecode references no Android or AndroidX types), so this file is the whole
// platform-specific surface - a desktop Chrome User-Agent, a visitor id minted
// from youtube.com, and a Ktor client the app already owns.

package com.ihimanshunayak.freemusic.desktop.data.innertube

import com.ihimanshunayak.freemusic.desktop.data.Http
import com.ihimanshunayak.freemusic.desktop.util.Log
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogEvent
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration

/**
 * A YouTube Music session that works on a desktop JVM.
 *
 * Two things have to be true before YouTube answers a WEB_REMIX request, and
 * both are handled here because getting either wrong looks identical from the
 * outside - an HTTP 400 with no explanation:
 *
 *  1. The request must carry a visitor id. It is minted from the web player's
 *     own bootstrap (`/sw.js_data`) rather than invented, because YouTube ties
 *     the id it hands back to the session it will accept.
 *  2. The session must not be re-created per request. The refresh callback
 *     InnerTubeX exposes is for renewing an expired session, and passing a
 *     no-op is correct for anonymous use - it is only called when renewal is
 *     actually needed.
 */
class YouTubeSession {

    private val logger = object : InnerTubeLogger {
        override fun log(event: InnerTubeLogEvent) {
            val line = "${event.tag}: ${event.message}"
            when (event.level) {
                InnerTubeLogLevel.ERROR -> Log.e(line, tag = "innertube")
                InnerTubeLogLevel.WARN -> Log.w(line, tag = "innertube")
                InnerTubeLogLevel.INFO -> Log.i(line, tag = "innertube")
                InnerTubeLogLevel.DEBUG -> Log.d(line, tag = "innertube")
            }
        }
    }

    /** Renewal is not needed for anonymous desktop use; see the class comment. */
    private val onSessionExpired: suspend (Duration) -> Unit = { }

    val innerTube = InnerTube(Http.ktor, onSessionExpired, logger)

    @Volatile
    private var visitorReady = false

    /**
     * Ensures a visitor id exists, minting one if necessary.
     *
     * Safe to call before every request: the id is remembered once obtained, and
     * a failure is logged rather than thrown, because some endpoints (suggestions,
     * for instance) answer without one and should not be blocked by this.
     */
    suspend fun ensureVisitorData(): String? {
        if (visitorReady && !innerTube.visitorData.isNullOrBlank()) {
            return innerTube.visitorData
        }
        return runCatching {
            val body = Http.ktor.get("https://www.youtube.com/sw.js_data") {
                header("User-Agent", Http.USER_AGENT)
            }.bodyAsText()

            // The payload is an anti-hijacking prefix followed by nested arrays;
            // the id is found by shape because the path is not documented and
            // changes without notice.
            val payload = Http.json.parseToJsonElement(body.substringAfter("\n", body.drop(5)))
            val id = findVisitorData(payload)
            if (id != null) {
                innerTube.visitorData = id
                visitorReady = true
                Log.i("visitor id ready (${id.length} chars)", tag = "innertube")
            } else {
                Log.w("no visitor id in /sw.js_data response", tag = "innertube")
            }
            id
        }.onFailure {
            Log.w("could not mint a visitor id: ${it.message}", tag = "innertube")
        }.getOrNull()
    }

    /**
     * The first request of a session often answers with the visitor id YouTube
     * decided to use. Adopting it keeps later requests consistent with the one
     * that succeeded, instead of mixing two identities.
     */
    fun adoptVisitorDataFrom(response: JsonElement) {
        runCatching {
            val fromResponse = response.jsonObject["responseContext"]
                ?.jsonObject?.get("visitorData")
                ?.jsonPrimitive?.contentOrNull
            if (!fromResponse.isNullOrBlank() && fromResponse != innerTube.visitorData) {
                innerTube.visitorData = fromResponse
                visitorReady = true
            }
        }
    }
}

/**
 * Protobuf-in-base64; nothing else in the bootstrap has this shape.
 *
 * Matches the pattern the Android build uses, so an id accepted by one is
 * accepted by the other.
 */
private val VISITOR_DATA = Regex("""Cg[A-Za-z0-9_%-]{40,}""")

private fun findVisitorData(element: JsonElement): String? = when (element) {
    is JsonArray -> element.firstNotNullOfOrNull { findVisitorData(it) }
    is JsonPrimitive -> element.contentOrNull?.takeIf { VISITOR_DATA.matches(it) }
    else -> null
}
