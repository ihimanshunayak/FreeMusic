// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - StreamResolver tests.
//
// Two things here are load-bearing and worth pinning down. First, the expiry
// parse: googlevideo URLs carry a Unix-seconds `expire` parameter and getting it
// wrong means either re-resolving every song (slow) or handing the player a dead
// URL (a mid-song stop). Second, the header set: googlevideo refuses a request
// whose User-Agent or Referer does not match the client that minted the URL.

package com.ihimanshunayak.freemusic.desktop.data.stream

import com.ihimanshunayak.freemusic.desktop.data.AudioQuality
import com.ihimanshunayak.freemusic.desktop.data.Http
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamResolverTest {

    private val resolver = StreamResolver()

    // ---- expiry parsing ----------------------------------------------------

    @Test
    fun `expire is read from the query and converted to milliseconds`() {
        val url = "https://rr1.googlevideo.com/videoplayback?expire=1767225600&itag=251&mime=audio%2Fwebm"

        assertEquals(1_767_225_600_000L, resolver.expiryFrom(url))
    }

    @Test
    fun `a URL without an expire parameter yields null`() {
        assertNull(resolver.expiryFrom("https://rr1.googlevideo.com/videoplayback?itag=251"))
    }

    @Test
    fun `a malformed expire value yields null rather than throwing`() {
        assertNull(resolver.expiryFrom("https://x/videoplayback?expire=not-a-number"))
        assertNull(resolver.expiryFrom("not a url at all"))
        assertNull(resolver.expiryFrom(""))
    }

    @Test
    fun `an expire appearing after another parameter is still found`() {
        val url = "https://x/videoplayback?itag=251&expire=1800000000&mime=audio%2Fmp4"

        assertEquals(1_800_000_000_000L, resolver.expiryFrom(url))
    }

    // ---- stream choice -----------------------------------------------------

    @Test
    fun `the highest bitrate under the ceiling is chosen`() {
        val streams = listOf(audio(64), audio(128), audio(256), audio(320))

        assertEquals(256, resolver.effectiveBitrate(resolver.chooseStream(streams, AudioQuality.HIGH)))
        assertEquals(128, resolver.effectiveBitrate(resolver.chooseStream(streams, AudioQuality.MEDIUM)))
        assertEquals(64, resolver.effectiveBitrate(resolver.chooseStream(streams, AudioQuality.LOW)))
    }

    @Test
    fun `highest ignores the ceiling and takes the best offered`() {
        val streams = listOf(audio(64), audio(128), audio(320))

        assertEquals(320, resolver.effectiveBitrate(resolver.chooseStream(streams, AudioQuality.HIGHEST)))
    }

    @Test
    fun `a ceiling below every offer falls back to the lowest rather than failing`() {
        val streams = listOf(audio(256), audio(320))

        // Asking for 64 kbps when only 256+ exists must still play something.
        assertEquals(256, resolver.effectiveBitrate(resolver.chooseStream(streams, AudioQuality.LOW)))
    }

    @Test
    fun `a stream without a content URL is skipped`() {
        val streams = listOf(audio(320, content = null), audio(128))

        assertEquals(128, resolver.effectiveBitrate(resolver.chooseStream(streams, AudioQuality.HIGHEST)))
    }

    @Test
    fun `a single stream is returned for every quality`() {
        val streams = listOf(audio(192))

        AudioQuality.entries.forEach { quality ->
            assertEquals(192, resolver.effectiveBitrate(resolver.chooseStream(streams, quality)), "quality $quality")
        }
    }

    @Test
    fun `a stream whose itag was not matched falls back to its average bitrate`() {
        // NewPipe copies `bitrate` out of the itag table, so a format it does not
        // know reports 0. Without the fallback such a stream would sort as the
        // worst one and be picked for every quality setting.
        val unknownItag = audio(320)

        assertEquals(0, unknownItag.bitrate, "precondition: the itag table left bitrate unset")
        assertEquals(320, resolver.effectiveBitrate(unknownItag))
    }

    @Test
    fun `the fallback bitrate participates in quality selection`() {
        val streams = listOf(audio(64), audio(320))

        assertEquals(64, resolver.effectiveBitrate(resolver.chooseStream(streams, AudioQuality.LOW)))
        assertEquals(320, resolver.effectiveBitrate(resolver.chooseStream(streams, AudioQuality.HIGHEST)))
    }

    // ---- media headers -----------------------------------------------------

    @Test
    fun `media headers carry the agent referer and origin googlevideo requires`() {
        val headers = resolver.headersFor("ApXoWvfEYVU")

        assertEquals(Http.USER_AGENT, headers["User-Agent"])
        assertEquals("https://www.youtube.com/watch?v=ApXoWvfEYVU", headers["Referer"])
        assertEquals("https://www.youtube.com", headers["Origin"])
        assertEquals("*/*", headers["Accept"])
    }

    @Test
    fun `the referer is specific to the video being requested`() {
        assertTrue(resolver.headersFor("abc")["Referer"]!!.endsWith("v=abc"))
    }

    // ---- ResolvedStream expiry --------------------------------------------

    @Test
    fun `a stream is expired only once the deadline has passed`() {
        val stream = resolved(expiresAt = 1_000)

        assertTrue(stream.isExpired(nowMillis = 1_001))
        assertTrue(stream.isExpired(nowMillis = 2_000))
        assertTrue(!stream.isExpired(nowMillis = 999))
    }

    @Test
    fun `an unknown expiry is never treated as expired`() {
        // 0 means "no expiry was found in the URL", not "expired at the epoch";
        // treating it as expired would re-resolve on every single play.
        val stream = resolved(expiresAt = 0)

        assertTrue(!stream.isExpired(nowMillis = Long.MAX_VALUE / 2))
    }

    private fun audio(bitrate: Int, content: String? = "https://rr1.googlevideo.com/videoplayback?itag=x") =
        AudioStream.Builder()
            .setId("itag-$bitrate")
            .setContent(content ?: "", true)
            .setMediaFormat(MediaFormat.WEBMA_OPUS)
            .setAverageBitrate(bitrate)
            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
            .build()

    private fun resolved(expiresAt: Long) = ResolvedStream(
        videoId = "v",
        url = "https://x/videoplayback",
        mimeType = "audio/webm",
        bitrateKbps = 128,
        headers = emptyMap(),
        expiresAtMillis = expiresAt,
    )
}
