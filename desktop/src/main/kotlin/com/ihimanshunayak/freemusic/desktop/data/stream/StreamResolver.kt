// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - stream resolution.
//
// Android's player (androidx.media3) cannot run on Windows, but the part that
// actually finds an audio URL - NewPipeExtractor, which solves YouTube's
// signature cipher and the `n` throttling parameter - is pure Java and runs
// unchanged. This file connects that to a desktop HTTP client and caches the
// result, because a resolved URL is expensive to obtain and cheap to keep.

package com.ihimanshunayak.freemusic.desktop.data.stream

import com.ihimanshunayak.freemusic.desktop.data.AudioQuality
import com.ihimanshunayak.freemusic.desktop.data.Http
import com.ihimanshunayak.freemusic.desktop.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse as JavaHttpResponse

/**
 * A playable audio URL plus everything needed to keep it working.
 *
 * googlevideo rejects a request whose headers do not match the client that
 * minted the URL, so [headers] travels with the URL and is applied by the player.
 */
data class ResolvedStream(
    val videoId: String,
    val url: String,
    val mimeType: String,
    val bitrateKbps: Int,
    val headers: Map<String, String>,
    val expiresAtMillis: Long,
) {
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        expiresAtMillis in 1..nowMillis
}

/**
 * Resolves YouTube video ids to audio URLs.
 *
 * Resolved URLs are cached per video id and reused until shortly before they
 * expire. googlevideo URLs carry their own `expire` parameter (typically six
 * hours), and re-resolving one early costs a full extraction plus a Rhino run,
 * which is the single slowest operation in the app.
 */
class StreamResolver(
    private val quality: () -> AudioQuality = { AudioQuality.HIGH },
) {

    class ResolutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val cache = LinkedHashMap<String, ResolvedStream>()
    private val lock = Mutex()

    /**
     * Resolves [videoId], preferring a cached URL.
     *
     * [forceRefresh] is used when the player reports a failure mid-playback: the
     * cached URL is then known bad and must not be handed back again.
     */
    suspend fun resolve(videoId: String, forceRefresh: Boolean = false): ResolvedStream =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (!forceRefresh) {
                    cache[videoId]?.let { cached ->
                        if (!cached.isExpired(NOW + EXPIRY_MARGIN_MS)) {
                            Log.d("reusing resolved stream for $videoId", tag = "stream")
                            return@withContext cached
                        }
                        cache.remove(videoId)
                    }
                } else {
                    cache.remove(videoId)
                }

                ensureNewPipeInitialised()

                val started = System.currentTimeMillis()
                val info = try {
                    StreamInfo.getInfo(ServiceList.YouTube, WATCH_URL + videoId)
                } catch (e: ExtractionException) {
                    throw ResolutionException("YouTube refused $videoId: ${e.message}", e)
                } catch (e: Throwable) {
                    throw ResolutionException("could not resolve $videoId: ${e.message}", e)
                }

                if (info.audioStreams.isEmpty()) {
                    throw ResolutionException("no audio streams offered for ${info.name}")
                }

                val chosen = chooseStream(info.audioStreams, quality())
                val bitrate = effectiveBitrate(chosen)
                val elapsed = System.currentTimeMillis() - started
                Log.i(
                    "resolved ${info.name} in ${elapsed}ms - itag=${chosen.itagItem?.id} " +
                        "${bitrate}kbps ${chosen.getFormat()?.name}",
                    tag = "stream",
                )

                val resolved = ResolvedStream(
                    videoId = videoId,
                    url = chosen.content,
                    mimeType = chosen.getFormat()?.mimeType ?: "audio/webm",
                    bitrateKbps = bitrate,
                    headers = mediaHeaders(videoId),
                    expiresAtMillis = expiryFrom(chosen.content) ?: (System.currentTimeMillis() + DEFAULT_TTL_MS),
                )
                cache[videoId] = resolved
                trimCache()
                resolved
            }
        }

    /**
     * Picks the stream that best matches the requested quality.
     *
     * Preference is: the highest bitrate that does not exceed the ceiling, or -
     * when the ceiling is [AudioQuality.HIGHEST] - simply the highest available.
     * A ceiling below every offered stream still returns the lowest one rather
     * than failing, because refusing to play at all is worse than playing at a
     * quality the user asked to avoid.
     */
    internal fun chooseStream(streams: List<AudioStream>, quality: AudioQuality): AudioStream {
        val usable = streams.filter { !it.content.isNullOrBlank() }
        if (usable.isEmpty()) throw ResolutionException("no usable audio stream in the list")

        if (quality == AudioQuality.HIGHEST) return usable.maxByOrNull { effectiveBitrate(it) }!!

        val ceiling = quality.approxKbps
        val withinCeiling = usable.filter { effectiveBitrate(it) <= ceiling }
        return withinCeiling.maxByOrNull { effectiveBitrate(it) }
            ?: usable.minByOrNull { effectiveBitrate(it) }!!
    }

    /**
     * A stream's bitrate, whichever of the two the extractor filled in.
     *
     * `AudioStream.bitrate` is copied from the itag table, so it is 0 whenever
     * NewPipe could not match an itag - which happens for newly added formats.
     * Falling back to the average bitrate the player reported keeps such a stream
     * comparable instead of making it look like a 0 kbps one.
     */
    internal fun effectiveBitrate(stream: AudioStream): Int =
        if (stream.bitrate > 0) stream.bitrate else stream.averageBitrate.coerceAtLeast(0)

    /**
     * Headers a googlevideo media request must carry.
     *
     * The URL is bound to the client that minted it; presenting a different
     * User-Agent or omitting Range support makes googlevideo answer 403.
     */
    private fun mediaHeaders(videoId: String): Map<String, String> = mapOf(
        "User-Agent" to Http.USER_AGENT,
        "Referer" to "https://www.youtube.com/watch?v=$videoId",
        "Origin" to "https://www.youtube.com",
        "Accept" to "*/*",
    )

    /** Exposed for the tests that assert the header set a media request needs. */
    internal fun headersFor(videoId: String): Map<String, String> = mediaHeaders(videoId)

    private fun trimCache() {
        while (cache.size > MAX_CACHED) {
            val oldest = cache.keys.firstOrNull() ?: break
            cache.remove(oldest)
        }
    }

    /**
     * Reads `expire` out of the URL query.
     *
     * The margin matters: a URL that expires while the track is buffering fails
     * mid-song, so anything within [EXPIRY_MARGIN_MS] is treated as already gone.
     */
    internal fun expiryFrom(url: String): Long? = runCatching {
        val query = URI.create(url).rawQuery ?: return null
        query.split("&").firstOrNull { it.startsWith("expire=") }
            ?.substringAfter("=")
            ?.toLongOrNull()
            ?.times(1000)
    }.getOrNull()

    companion object {
        private const val WATCH_URL = "https://www.youtube.com/watch?v="
        private const val MAX_CACHED = 64
        private const val DEFAULT_TTL_MS = 30L * 60 * 1000
        private const val EXPIRY_MARGIN_MS = 3L * 60 * 1000
        private val NOW: Long get() = System.currentTimeMillis()

        @Volatile
        private var newPipeReady = false

        /**
         * NewPipeExtractor is a global service locator, so it is initialised once.
         * The double-check keeps a concurrent first search from initialising it twice.
         */
        @Synchronized
        private fun ensureNewPipeInitialised() {
            if (newPipeReady) return
            NewPipe.init(DesktopDownloader())
            newPipeReady = true
            Log.i("NewPipeExtractor initialised on the desktop JVM", tag = "stream")
        }
    }
}

/**
 * Bridges NewPipeExtractor to this app's HTTP client.
 *
 * NewPipe asks for a Java-API downloader rather than an OkHttp one, so the stock
 * JDK client is used here. Sharing OkHttp would buy little: these calls are the
 * extractor's own (watch pages, player JS), not the app's API traffic, and the
 * JDK client already pools and reuses connections per host by default.
 */
class DesktopDownloader : Downloader() {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun execute(request: Request): Response {
        val builder = HttpRequest.newBuilder(URI.create(request.url()))
            .timeout(java.time.Duration.ofSeconds(30))

        request.headers().forEach { (name, values) ->
            values.forEach { value ->
                // Restricted headers (Host, Content-Length, Connection) are set by
                // the client itself; passing them through throws.
                runCatching { builder.header(name, value) }
            }
        }

        when (request.httpMethod().uppercase()) {
            "GET" -> builder.GET()
            "HEAD" -> builder.method("HEAD", HttpRequest.BodyPublishers.noBody())
            "POST" -> builder.POST(
                HttpRequest.BodyPublishers.ofByteArray(request.dataToSend() ?: ByteArray(0))
            )
            else -> builder.method(request.httpMethod(), HttpRequest.BodyPublishers.noBody())
        }

        val response = http.send(builder.build(), JavaHttpResponse.BodyHandlers.ofString())
        return Response(
            response.statusCode(),
            "",
            response.headers().map(),
            response.body(),
            request.url(),
        )
    }
}
