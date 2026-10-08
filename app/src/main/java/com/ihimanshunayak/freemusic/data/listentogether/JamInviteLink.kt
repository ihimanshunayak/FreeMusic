package com.ihimanshunayak.freemusic.data.listentogether

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class ParsedJamInvite(
    val code: String,
    val serverUrl: String? = null,
)

/**
 * An invitation to a shared playlist, as it arrived from a link.
 *
 * A separate type from [ParsedJamInvite] rather than an optional field on it,
 * because the two are not variations of one thing: a party code is a short
 * human-readable room name somebody may read aloud, and this token is a long
 * opaque secret that grants standing in a playlist. A nullable field would let
 * every existing caller that reads `code` compile unchanged while silently
 * holding a value that is not a code.
 */
data class ParsedPlaylistInvite(
    val token: String,
    val serverUrl: String? = null,
)

/** Relays a Free Music web or scheme invite from [com.ihimanshunayak.freemusic.MainActivity] to Compose. */
object JamInviteLink {

    const val ORIGIN = "https://freemusic.example.com"

    private const val EXTRA_CONSUMED = "freemusic.jamInviteConsumed"
    private const val HOST = "freemusic.example.com"
    private const val CUSTOM_SCHEME = "freemusic"
    private const val CUSTOM_HOST = "party"
    private const val PLAYLIST_HOST = "playlist"

    private val _pending = MutableStateFlow<ParsedJamInvite?>(null)
    val pending: StateFlow<ParsedJamInvite?> = _pending.asStateFlow()

    private val _pendingPlaylist = MutableStateFlow<ParsedPlaylistInvite?>(null)
    val pendingPlaylist: StateFlow<ParsedPlaylistInvite?> = _pendingPlaylist.asStateFlow()

    /**
     * Reads an incoming link from a cold launch or a new intent on the existing
     * task, for either kind of invitation.
     *
     * One entry point for both because the intent filter covers both and the
     * activity must not have to decide which parser to hand the URI to: it does
     * not know which kind of link it has until after parsing, and `consume`
     * returning false for the other kind would make the caller treat a valid
     * playlist link as "not ours".
     */
    fun consume(intent: Intent?): Boolean {
        if (
            intent == null ||
            intent.action != Intent.ACTION_VIEW ||
            intent.getBooleanExtra(EXTRA_CONSUMED, false)
        ) return false

        val data = intent.dataString ?: return false

        // Party first: it is the older link and the shorter check, and the two
        // shapes cannot overlap — a host of `party` is never a host of
        // `playlist`.
        parseInvite(data)?.let { invite ->
            intent.putExtra(EXTRA_CONSUMED, true)
            _pending.value = invite
            return true
        }
        parsePlaylistInvite(data)?.let { invite ->
            intent.putExtra(EXTRA_CONSUMED, true)
            _pendingPlaylist.value = invite
            return true
        }
        return false
    }

    fun handled() {
        _pending.value = null
    }

    fun handledPlaylist() {
        _pendingPlaylist.value = null
    }

    /** Returns the normalized party code only for the public invite URL shape. */
    fun parse(value: String?): String? = parseInvite(value)?.code

    /**
     * Parses an incoming invite:
     * 1. freemusic://party/<CODE>?server=<SERVER>
     * 2. https://freemusic.example.com/invite/<CODE>?server=<SERVER>
     */
    fun parseInvite(value: String?): ParsedJamInvite? {
        val uri = runCatching { URI(value ?: return null) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val query = uri.rawQuery
        val server = extractQueryParam(query, "server")?.let { sanitizeServerUrl(it) }

        // 1. Custom scheme: freemusic://party/<CODE> or freemusic://party?code=<CODE>
        if (scheme == CUSTOM_SCHEME && host == CUSTOM_HOST) {
            val pathPart = uri.path.orEmpty().trim('/').takeIf { it.isNotBlank() }
            val candidate = pathPart ?: extractQueryParam(query, "code") ?: return null
            val code = cleanCode(candidate) ?: return null
            return ParsedJamInvite(code = code, serverUrl = server)
        }

        // 2. Official web domain: https://freemusic.example.com/invite/<CODE>
        if (scheme == "https" && host == HOST) {
            val match = INVITE_PATH.matchEntire(uri.path.orEmpty()) ?: return null
            val code = match.groupValues[1].uppercase()
            return ParsedJamInvite(code = code, serverUrl = server)
        }

        return null
    }

    /**
     * Parses a shared-playlist invitation:
     * 1. freemusic://playlist/invite/<TOKEN>?server=<SERVER>
     * 2. https://freemusic.example.com/playlist/invite/<TOKEN>
     *
     * The token is not normalised the way a party code is. A party code is
     * uppercased and stripped of punctuation because somebody might have read it
     * off a screen; this one was only ever copied or tapped, and it is a secret
     * compared byte-for-byte by the server — so filtering it would not clean it
     * up, it would corrupt it.
     */
    fun parsePlaylistInvite(value: String?): ParsedPlaylistInvite? {
        val uri = runCatching { URI(value ?: return null) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val query = uri.rawQuery
        val server = extractQueryParam(query, "server")?.let { sanitizeServerUrl(it) }
        val path = uri.path.orEmpty()

        if (scheme == CUSTOM_SCHEME && host == PLAYLIST_HOST) {
            val match = PLAYLIST_PATH.matchEntire(path) ?: return null
            return ParsedPlaylistInvite(match.groupValues[1], server)
        }

        if (scheme == "https" && host == HOST) {
            val match = PLAYLIST_WEB_PATH.matchEntire(path) ?: return null
            return ParsedPlaylistInvite(match.groupValues[1], server)
        }

        return null
    }

    fun url(code: String, customServer: String? = null): String {
        val base = customServer?.trim()?.trimEnd('/')
        return if (!base.isNullOrBlank() && !base.equals(ORIGIN, ignoreCase = true)) {
            "$base/invite/${code.uppercase()}"
        } else {
            "$ORIGIN/invite/${code.uppercase()}"
        }
    }

    fun schemeUrl(code: String, customServer: String? = null): String {
        val normalizedCode = code.uppercase()
        val base = customServer?.trim()?.trimEnd('/')
        return if (!base.isNullOrBlank()) {
            val encoded = runCatching { URLEncoder.encode(base, "UTF-8") }.getOrDefault(base)
            "freemusic://party/$normalizedCode?server=$encoded"
        } else {
            "freemusic://party/$normalizedCode"
        }
    }

    private fun cleanCode(raw: String): String? {
        val cleaned = raw.filter { it.isLetterOrDigit() }.uppercase()
        return if (cleaned.length == ListenTogether.CODE_LENGTH) cleaned else null
    }

    private fun extractQueryParam(query: String?, paramName: String): String? {
        if (query.isNullOrBlank()) return null
        return query.split('&').asSequence()
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.isNotEmpty() && it[0].equals(paramName, ignoreCase = true) }
            ?.getOrNull(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
    }

    private fun sanitizeServerUrl(raw: String?): String? {
        val trimmed = raw?.trim()?.trimEnd('/') ?: return null
        if (trimmed.isBlank()) return null
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank()) return null
        return withScheme
    }

    private val INVITE_PATH = Regex("""/invite/([A-Za-z0-9]{${ListenTogether.CODE_LENGTH}})""")

    /**
     * The playlist path, which carries a token rather than a code.
     *
     * Bounded rather than open: the server mints 32 random bytes as 64 hex
     * characters, so a link whose tail is a different length is not one of ours
     * and should not be held onto and sent. The bound also keeps a malformed or
     * hostile link from putting an unbounded string into a state flow that a
     * screen then renders.
     *
     * Two spellings, because the custom scheme puts the type in the authority
     * (`freemusic://playlist/invite/<token>`) while the web form puts it in the
     * path (`https://<host>/playlist/invite/<token>`). They differ by the
     * leading segment, so they cannot share one expression without the custom
     * form also accepting the web one — which would mean a link to
     * `freemusic://party/playlist/invite/...` parsing as a playlist invitation.
     */
    private val PLAYLIST_PATH = Regex("""/invite/([A-Za-z0-9_-]{16,256})""")

    /** As [PLAYLIST_PATH], for the web form whose path carries the type. */
    private val PLAYLIST_WEB_PATH = Regex("""/playlist/invite/([A-Za-z0-9_-]{16,256})""")
}

