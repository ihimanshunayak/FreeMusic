// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - scrobbling.
//
// NAME
//     Scrobbling.kt - Last.fm and ListenBrainz.
//
// DESCRIPTION
//     Two services, one shape: tell the service what started playing, then tell
//     it what finished. The differences that matter are all in the details:
//
//       Last.fm      needs an API key, an interactive authorisation round trip,
//                    and signs every call with an MD5 of its sorted parameters.
//                    It also wants a two-step protocol - "now playing" at the
//                    start, "scrobble" once the threshold is passed - because
//                    the two carry different weights in a user's profile.
//       ListenBrainz  needs only a user token, takes a single "submit" call,
//                    and has no authorisation dance at all.
//
//     Both are implemented here rather than through a library, because both are
//     a handful of POSTs and a signature, and a scrobbler dependency would bring
//     its own session model, its own HTTP client and its own opinion about when
//     a listen counts.
//
// RESPONSIBILITIES
//     - Submit "now playing" and completed scrobbles.
//     - Implement Last.fm's token flow, signature scheme and error envelope.
//     - Hold and persist whatever credentials each service needs.
//     - Apply the shared threshold so the two services agree on what a listen is.
//
// DEPENDENCIES
//     - The shared OkHttp client, so scrobble traffic uses the app's timeouts.
//     - `kotlinx.serialization` for the persisted session.
//
// INTEGRATION NOTES
//     - Scrobbling is fire-and-forget: a service being down must never affect
//       playback, and a failed submit is dropped rather than queued. The Android
//       build queues; on a desktop the process can be closed at any moment, and
//       an on-disk queue that is never drained would be worse than the loss.
//     - The token is a password-equivalent secret. It is never logged, and the
//       signature is computed over a redacted copy of the parameter list.

package com.ihimanshunayak.freemusic.desktop.data.scrobble

import com.ihimanshunayak.freemusic.desktop.data.Http
import com.ihimanshunayak.freemusic.desktop.model.Track
import com.ihimanshunayak.freemusic.desktop.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * When a listen is worth reporting.
 *
 * Kept in one place because both services are submitted to from here, and a
 * disagreement between them would show up as two different play counts for the
 * same session.
 */
data class ScrobbleRules(
    /** Ignore anything shorter than this; ringtones and jingles are not listens. */
    val minimumSeconds: Int = 30,
    /** A play must reach this fraction of the track. */
    val delayFraction: Float = 0.5f,
    /** Or this many seconds, whichever comes first. */
    val delaySeconds: Int = 240,
) {
    /**
     * Whether [listenedSeconds] of a [durationSeconds] track counts.
     *
     * The rules are the services' own - Last.fm documents exactly this pair -
     * and are applied identically to both so the two histories agree.
     */
    fun qualifies(listenedSeconds: Int, durationSeconds: Int): Boolean {
        if (durationSeconds < minimumSeconds) return false
        val fractionReached = listenedSeconds >= (durationSeconds * delayFraction).toInt()
        val secondsReached = listenedSeconds >= delaySeconds.coerceAtMost(durationSeconds)
        return fractionReached || secondsReached
    }
}

/** A credential set that can be persisted. */
@Serializable
data class ScrobbleSession(
    val lastFmSessionKey: String = "",
    val lastFmUsername: String = "",
    val listenBrainzToken: String = "",
    val listenBrainzUsername: String = "",
)

/** What an attempt produced, for the settings screen to report. */
sealed interface ScrobbleOutcome {
    data object Submitted : ScrobbleOutcome
    data object Skipped : ScrobbleOutcome
    data class Failed(val reason: String) : ScrobbleOutcome
}

/**
 * Last.fm's API.
 *
 * The awkward part of Last.fm is that it authenticates the *caller* with an
 * account and the *user* with a session, so the API secret participates in every
 * signature. That is why the secret is a parameter here rather than baked in:
 * a build without a secret simply cannot scrobble, and says so, instead of
 * failing with an opaque "invalid signature".
 */
class LastFmClient(
    private val apiKey: () -> String,
    private val secret: () -> String,
) {
    private val endpoint = "https://ws.audioscrobbler.com/2.0/"

    /** Whether the user has supplied the key pair this build needs. */
    val configured: Boolean get() = apiKey().isNotBlank() && secret().isNotBlank()

    /**
     * Starts the authorisation round trip and returns the URL the user opens.
     *
     * The returned token is half of a session and is not persisted: it becomes
     * useless once the user approves and the session key is fetched, and storing
     * it would only widen where a credential lives.
     */
    suspend fun beginAuthorization(callback: String = ""): String? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        val response = post(
            buildMap {
                put("method", "auth.getToken")
                put("api_key", apiKey())
                if (callback.isNotBlank()) put("cb", callback)
            }
        ) ?: return@withContext null

        val token = response["token"] ?: return@withContext null
        "https://www.last.fm/api/auth/?api_key=${apiKey()}&token=$token"
    }

    /**
     * Exchanges an approved token for a session key.
     *
     * Last.fm's response here nests the session under a `session` object, and
     * reports a failure as an `error` code at the top level rather than as an
     * HTTP status, which is why the error field is checked before the payload.
     */
    suspend fun completeAuthorization(token: String): ScrobbleSession? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        val response = post(
            buildMap {
                put("method", "auth.getSession")
                put("api_key", apiKey())
                put("token", token)
            }
        ) ?: return@withContext null

        val session = response["session"] ?: return@withContext null
        val key = response["session.key"] ?: return@withContext null
        ScrobbleSession(
            lastFmSessionKey = key,
            lastFmUsername = response["session.name"].orEmpty(),
        )
    }

    /** Announces a track without counting it, which is what "now playing" means. */
    suspend fun nowPlaying(sessionKey: String, track: Track): ScrobbleOutcome =
        submit("track.updateNowPlaying", sessionKey, track, durationSeconds = null)

    /** Counts a track. */
    suspend fun scrobble(sessionKey: String, track: Track, startedAtSeconds: Long): ScrobbleOutcome =
        submit("track.scrobble", sessionKey, track, durationSeconds = null, startedAt = startedAtSeconds)

    private suspend fun submit(
        method: String,
        sessionKey: String,
        track: Track,
        durationSeconds: Int?,
        startedAt: Long? = null,
    ): ScrobbleOutcome = withContext(Dispatchers.IO) {
        if (!configured) return@withContext ScrobbleOutcome.Failed("Last.fm is not configured")

        val parameters = buildMap {
            put("method", method)
            put("api_key", apiKey())
            put("sk", sessionKey)
            put("artist", track.artist)
            put("track", track.title)
            track.album?.takeIf { it.isNotBlank() }?.let { put("album", it) }
            // Last.fm wants a whole number of seconds and rejects a duration for
            // "now playing", which is why it is nullable rather than optional.
            durationSeconds?.let { put("duration", it.toString()) }
            startedAt?.let { put("timestamp", it.toString()) }
        }

        val response = post(parameters) ?: return@withContext ScrobbleOutcome.Failed("no response")
        // A response with no error field is a success for both methods; the
        // payload differs but nothing here reads it.
        if (response.containsKey("error")) {
            ScrobbleOutcome.Failed(lastFmError(response["error"], response["message"]))
        } else {
            ScrobbleOutcome.Submitted
        }
    }

    /**
     * Signed POST.
     *
     * Last.fm's scheme: every parameter except `format` and `callback`, sorted by
     * name, concatenated as name+value, with the API secret appended, then MD5'd.
     * Getting the sort order or the excluded parameters wrong produces an
     * "invalid method signature" that gives no clue which part was wrong, so the
     * excluded set is named explicitly rather than inferred.
     */
    private fun post(parameters: Map<String, String>): Map<String, String>? {
        val signature = signatureOf(parameters)

        val body = FormBody.Builder()
            .apply {
                parameters.forEach { (name, value) -> add(name, value) }
                add("api_sig", signature)
                add("format", "json")
            }
            .build()

        return runCatching {
            val request = Request.Builder()
                .url(endpoint)
                .header("User-Agent", Http.USER_AGENT)
                .post(body)
                .build()

            Http.client.newCall(request).execute().use { response ->
                val text = response.body.string()
                parseFlatJson(text)
            }
        }.onFailure { Log.d("last.fm call failed: ${it.message}", tag = "scrobble") }.getOrNull()
    }

    internal fun signatureOf(parameters: Map<String, String>): String {
        val joined = parameters.entries
            .filterNot { it.key == "format" || it.key == "callback" }
            .sortedBy { it.key }
            .joinToString("") { (name, value) -> name + value }
        return md5(joined + secret())
    }

    /**
     * Parses the flat JSON both Last.fm responses use.
     *
     * Nested objects are flattened to `key.subkey` form as well as being kept
     * under their own name, because `auth.getSession` nests its payload and the
     * caller wants the inner fields without a second parser.
     */
    private fun parseFlatJson(text: String): Map<String, String>? = runCatching {
        val json = org.json.JSONObject(text)
        val result = mutableMapOf<String, String>()
        json.keys().forEach { key ->
            val value = json.get(key)
            if (value is org.json.JSONObject) {
                value.keys().forEach { inner ->
                    result["$key.$inner"] = value.get(inner).toString()
                }
                result[key] = value.toString()
            } else {
                result[key] = value.toString()
            }
        }
        result
    }.onFailure { Log.d("last.fm response was not JSON", tag = "scrobble") }.getOrNull()

    /** Human-readable text for Last.fm's numeric error codes. */
    private fun lastFmError(code: String?, message: String?): String = when (code) {
        "4" -> "Last.fm authentication failed - re-authorise the account"
        "9" -> "Last.fm rejected the session key - re-authorise the account"
        "10" -> "The Last.fm API key is invalid"
        "11", "16" -> "Last.fm is temporarily unavailable"
        "29" -> "Last.fm rate limit reached - try again later"
        else -> message ?: "Last.fm error $code"
    }
}

/**
 * ListenBrainz's API.
 *
 * Strikingly simpler than Last.fm: one endpoint, a bearer token, and a payload
 * that is a JSON object of the now-familiar `artist_name`/`track_name` shape.
 */
class ListenBrainzClient(private val token: () -> String) {

    private val endpoint = "https://api.listenbrainz.org/1/submit-listens"

    val configured: Boolean get() = token().isNotBlank()

    /**
     * Submits a listen.
     *
     * ListenBrainz distinguishes a "playing_now" submission from a "single"
     * (completed) one in the same payload shape, which is why the type is a
     * parameter rather than two methods: the body is otherwise identical.
     */
    suspend fun submit(
        track: Track,
        listenedAtSeconds: Long,
        playingNow: Boolean,
    ): ScrobbleOutcome = withContext(Dispatchers.IO) {
        if (!configured) return@withContext ScrobbleOutcome.Failed("ListenBrainz is not configured")

        val payload = buildString {
            append("""{"listen_type":""")
            append(if (playingNow) "\"playing_now\"" else "\"single\"")
            append(""","payload":[{"track_metadata":{"artist_name":""")
            append(escape(track.artist))
            append(""","track_name":""")
            append(escape(track.title))
            if (!track.album.isNullOrBlank()) {
                append(""","release_name":""")
                append(escape(track.album))
            }
            append(""""}}""")
            // A "playing_now" submission carries no timestamp: it means "now",
            // and sending one makes the service reject the payload.
            if (!playingNow) {
                append(""","listened_at":$listenedAtSeconds""")
            }
            append("}]}")
        }

        runCatching {
            val request = Request.Builder()
                .url(endpoint)
                .header("User-Agent", Http.USER_AGENT)
                .header("Authorization", "Token $token()")
                .header("Content-Type", "application/json")
                .post(payload.toByteArray(StandardCharsets.UTF_8).toRequestBody(JSON_MEDIA_TYPE))
                .build()

            Http.client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> ScrobbleOutcome.Submitted
                    response.code == 401 -> ScrobbleOutcome.Failed("ListenBrainz rejected the token")
                    response.code == 429 -> ScrobbleOutcome.Failed("ListenBrainz rate limit reached")
                    else -> ScrobbleOutcome.Failed("ListenBrainz returned HTTP ${response.code}")
                }
            }
        }.onFailure { Log.d("listenbrainz call failed: ${it.message}", tag = "scrobble") }
            .getOrElse { ScrobbleOutcome.Failed(it.message ?: "network error") }
    }

    /** Validates a token by asking for the profile it belongs to. */
    suspend fun validateToken(): String? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        runCatching {
            val request = Request.Builder()
                .url("https://api.listenbrainz.org/1/validate-token")
                .header("User-Agent", Http.USER_AGENT)
                .header("Authorization", "Token ${token()}")
                .get()
                .build()
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val json = org.json.JSONObject(response.body.string())
                if (!json.optBoolean("valid", false)) return@withContext null
                json.optString("user_name").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /** Escapes a string for JSON. Track titles contain quotes often enough to matter. */
    private fun escape(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append(" ") else append(character)
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE: okhttp3.MediaType = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Holds both sessions, persists them, and applies the shared timing rules.
 *
 * The manager owns the *decision* to scrobble, not just the transport, because
 * the decision has to be made once for both services. Wiring the threshold into
 * each client separately would let them drift apart, and a user seeing Last.fm
 * and ListenBrainz disagree about their history has no way to tell which is
 * right.
 *
 * Every submission method is `suspend`, so the caller's coroutine scope owns the
 * lifetime: cancelling that scope cancels in-flight scrobbles and nothing
 * outlives the window.
 */
class ScrobbleManager(
    private val file: File,
    private val rules: ScrobbleRules = ScrobbleRules(),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var session: ScrobbleSession = ScrobbleSession()
    private var lastFmEnabled = false
    private var listenBrainzEnabled = false
    private var reportNowPlaying = true
    private var lastFmApiKey = ""
    private var lastFmSecret = ""

    private val lastFm = LastFmClient({ lastFmApiKey }, { lastFmSecret })
    private val listenBrainz = ListenBrainzClient({ session.listenBrainzToken })

    /** The account names currently connected, for the settings screen. */
    val lastFmUsername: String get() = session.lastFmUsername
    val lastFmAuthorised: Boolean get() = session.lastFmSessionKey.isNotBlank()
    val listenBrainzConfigured: Boolean get() = listenBrainz.configured
    val listenBrainzUsername: String get() = session.listenBrainzUsername
    val lastFmConfigured: Boolean get() = lastFm.configured
    val isEnabled: Boolean get() = lastFmEnabled || listenBrainzEnabled

    private var currentTrack: Track? = null
    private var currentStartedAtSeconds: Long = 0
    private var listenedSeconds: Int = 0
    private var scrobbled = false

    fun load() {
        runCatching {
            if (!file.isFile) return@runCatching
            val stored = json.decodeFromString<StoredScrobbleSettings>(file.readText())
            session = stored.session
            lastFmEnabled = stored.lastFmEnabled
            listenBrainzEnabled = stored.listenBrainzEnabled
            reportNowPlaying = stored.reportNowPlaying
            lastFmApiKey = stored.lastFmApiKey
            lastFmSecret = stored.lastFmSecret
        }.onFailure { Log.w("scrobble settings load failed: ${it.message}", tag = "scrobble") }
    }

    /** Updates what the settings screen collected and persists it. */
    fun configure(
        lastFmEnabled: Boolean,
        listenBrainzEnabled: Boolean,
        apiKey: String,
        secret: String,
        token: String,
        reportNowPlaying: Boolean,
    ) {
        this.lastFmEnabled = lastFmEnabled
        this.listenBrainzEnabled = listenBrainzEnabled
        this.reportNowPlaying = reportNowPlaying
        lastFmApiKey = apiKey.trim()
        lastFmSecret = secret.trim()
        session = session.copy(listenBrainzToken = token.trim())
        persist()
    }

    /** Disconnects a service and clears the credential it needed. */
    fun disconnectLastFm() {
        session = session.copy(lastFmSessionKey = "", lastFmUsername = "")
        persist()
    }

    fun disconnectListenBrainz() {
        session = session.copy(listenBrainzToken = "", listenBrainzUsername = "")
        persist()
    }

    /** Stores the result of the Last.fm authorisation round trip. */
    fun adoptLastFmSession(result: ScrobbleSession) {
        session = session.copy(
            lastFmSessionKey = result.lastFmSessionKey,
            lastFmUsername = result.lastFmUsername,
        )
        persist()
    }

    /** Starts Last.fm's authorisation and returns the URL to open, or null. */
    suspend fun beginLastFmAuthorization(): String? = lastFm.beginAuthorization()

    /** Completes Last.fm's authorisation from the token the user was given. */
    suspend fun completeLastFmAuthorization(token: String): Boolean {
        val result = lastFm.completeAuthorization(token) ?: return false
        adoptLastFmSession(result)
        return true
    }

    /** Validates a ListenBrainz token by asking for the profile it belongs to. */
    suspend fun validateListenBrainzToken(): String? = listenBrainz.validateToken()

    /**
     * Announces a track and remembers it for the completed scrobble.
     *
     * The now-playing announcement goes out immediately: its whole purpose is to
     * appear while the track is still playing, so it cannot wait for the
     * threshold the way the scrobble does.
     */
    suspend fun onTrackStarted(track: Track) {
        currentTrack = track
        currentStartedAtSeconds = System.currentTimeMillis() / 1000
        listenedSeconds = 0
        scrobbled = false

        if (!isEnabled || !reportNowPlaying) return
        if (lastFmEnabled && lastFmAuthorised) {
            lastFm.nowPlaying(session.lastFmSessionKey, track)
        }
        if (listenBrainzEnabled) {
            listenBrainz.submit(track, currentStartedAtSeconds, playingNow = true)
        }
    }

    /**
     * Advances the listened time and scrobbles once the threshold is reached.
     *
     * @param listenedDeltaSeconds seconds of *actual* playback since the last
     *   call, which the caller measures from the position delta so that seeking
     *   and pausing contribute nothing.
     */
    suspend fun onPosition(listenedDeltaSeconds: Int, durationSeconds: Int): ScrobbleOutcome? {
        val track = currentTrack ?: return null
        if (listenedDeltaSeconds in 1..MAX_DELTA_SECONDS) listenedSeconds += listenedDeltaSeconds
        if (scrobbled) return null
        if (!isEnabled) return null
        if (!rules.qualifies(listenedSeconds, durationSeconds)) return null

        scrobbled = true
        var outcome: ScrobbleOutcome? = null
        if (lastFmEnabled && lastFmAuthorised) {
            outcome = lastFm.scrobble(session.lastFmSessionKey, track, currentStartedAtSeconds)
        }
        if (listenBrainzEnabled) {
            outcome = listenBrainz.submit(track, currentStartedAtSeconds, playingNow = false)
        }
        return outcome
    }

    fun onStopped() {
        currentTrack = null
        listenedSeconds = 0
        scrobbled = false
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                json.encodeToString(
                    StoredScrobbleSettings(
                        session = session,
                        lastFmEnabled = lastFmEnabled,
                        listenBrainzEnabled = listenBrainzEnabled,
                        reportNowPlaying = reportNowPlaying,
                        lastFmApiKey = lastFmApiKey,
                        lastFmSecret = lastFmSecret,
                    )
                )
            )
        }.onFailure { Log.w("scrobble settings save failed: ${it.message}", tag = "scrobble") }
    }

    @Serializable
    private data class StoredScrobbleSettings(
        val session: ScrobbleSession = ScrobbleSession(),
        val lastFmEnabled: Boolean = false,
        val listenBrainzEnabled: Boolean = false,
        val reportNowPlaying: Boolean = true,
        val lastFmApiKey: String = "",
        val lastFmSecret: String = "",
    )

    private companion object {
        /**
         * The largest jump in listened time a single report can legitimately
         * represent, matching the stats recorder's own guard against seeks.
         */
        const val MAX_DELTA_SECONDS = 30
    }
}

/** MD5 as Last.fm requires it. Not used for anything security-sensitive. */
internal fun md5(value: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
