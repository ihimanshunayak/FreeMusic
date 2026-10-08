package com.ihimanshunayak.freemusic.data.collab

import android.content.Context
import android.content.SharedPreferences
import com.ihimanshunayak.freemusic.data.DebugLog as Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The credentials this device holds, one per playlist.
 *
 * A capability, not an account, and that is why this store is the single most
 * important piece of state in the feature: these tokens are the *only* proof of
 * access that will ever exist. The server keeps SHA-256 hashes and cannot
 * reissue one, so a device that loses a token loses the playlist permanently —
 * there is no recovery flow because there is nothing to recover from.
 *
 * Held in `SharedPreferences` rather than the JSON-file pattern the rest of the
 * app uses for larger documents, because this is a small, hot, always-present
 * set: every request needs a token, so it must be readable without a disk round
 * trip, and it must be written the moment one is minted so a crash between
 * "playlist created" and "screen shown" does not orphan a playlist nobody can
 * ever open again.
 *
 * A token is never logged. `DebugLog` calls below name a playlist id and never a
 * credential, which is the same rule the server follows.
 */
object CollabCredentials {

    private const val TAG = "FreeMusicCollab"
    private const val PREFS = "freemusic_collab"
    private const val KEY_ENTRIES = "credentials"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** One stored credential: the playlist it opens, and the tokens for it. */
    @Serializable
    private data class Stored(
        val playlistId: String,
        val ownerToken: String? = null,
        val memberToken: String? = null,
        /** When this device first held it. Used only to order the list. */
        val addedAtMs: Long = 0L,
    ) {
        fun toCredential(): CollabCredential = CollabCredential(
            playlistId = playlistId,
            ownerToken = ownerToken,
            memberToken = memberToken,
        )
    }

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _all = MutableStateFlow<List<CollabCredential>>(emptyList())

    /** Every credential held, for a caller that needs to present them all. */
    val all: StateFlow<List<CollabCredential>> = _all.asStateFlow()

    /**
     * Ids only, so a screen can decide what to show without holding tokens in
     * composition state — where a token could reach a `remember` key, a log, or
     * a saved instance state bundle.
     */
    val playlistIds: StateFlow<Set<String>> get() = _ids.asStateFlow()

    private val _ids = MutableStateFlow<Set<String>>(emptySet())

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val loaded = read()
        _all.value = loaded.map { it.toCredential() }
        _ids.value = loaded.map { it.playlistId }.toSet()
        if (loaded.isNotEmpty()) {
            Log.i(TAG, "loaded credentials for ${loaded.size} playlist(s)")
        }
    }

    val isReady: Boolean get() = prefs != null

    /** The credential for one playlist, or null when this device has none. */
    fun credentialFor(playlistId: String): CollabCredential? =
        _all.value.firstOrNull { it.playlistId == playlistId }

    /** The token to present when acting on a playlist. */
    fun tokenFor(playlistId: String): String? = credentialFor(playlistId)?.token

    /** Whether this device holds the owner secret for a playlist. */
    fun isOwnerOf(playlistId: String): Boolean = credentialFor(playlistId)?.isOwner == true

    /**
     * Records a credential.
     *
     * Written through to disk before this returns, and the write is checked: a
     * silent failure here would mean the app believed it could open a playlist it
     * had just created and could not, which is worse than the create failing.
     */
    fun save(credential: CollabCredential) {
        if (credential.playlistId.isBlank() || credential.token == null) return
        val current = read().filterNot { it.playlistId == credential.playlistId }
        val merged = current + Stored(
            playlistId = credential.playlistId,
            ownerToken = credential.ownerToken,
            memberToken = credential.memberToken,
            addedAtMs = System.currentTimeMillis(),
        )
        if (!write(merged)) return
        _all.value = merged.map { it.toCredential() }
        _ids.value = merged.map { it.playlistId }.toSet()
    }

    /**
     * Adds a membership token for a playlist this device already knows, keeping
     * any owner token.
     *
     * Reached when somebody who created a playlist on this device also joins it
     * by link, or when a member upgrades — the two tokens are independent and one
     * must not erase the other.
     */
    fun mergeMemberToken(playlistId: String, memberToken: String) {
        val existing = read().firstOrNull { it.playlistId == playlistId }
        if (existing == null) {
            save(CollabCredential(playlistId = playlistId, memberToken = memberToken))
            return
        }
        save(
            CollabCredential(
                playlistId = playlistId,
                ownerToken = existing.ownerToken,
                memberToken = memberToken,
            ),
        )
    }

    /**
     * Forgets a playlist.
     *
     * Called on leave, on removal, and on delete — in every case the token is
     * already worthless server-side, and keeping it would mean the app kept
     * offering a playlist that refuses everything.
     */
    fun forget(playlistId: String) {
        val current = read()
        val remaining = current.filterNot { it.playlistId == playlistId }
        if (remaining.size == current.size) return
        if (!write(remaining)) return
        _all.value = remaining.map { it.toCredential() }
        _ids.value = remaining.map { it.playlistId }.toSet()
        Log.i(TAG, "forgot credentials for $playlistId")
    }

    /** Forgets everything. Used by the settings reset and by tests. */
    fun clear() {
        prefs?.edit()?.remove(KEY_ENTRIES)?.apply()
        _all.value = emptyList()
        _ids.value = emptySet()
    }

    // ---- Storage ---------------------------------------------------------

    private fun read(): List<Stored> {
        val raw = prefs?.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<Stored>>(raw)
        }.getOrElse {
            // A corrupt document is dropped rather than repaired: it can only mean
            // the app was interrupted mid-write, and a partial token is worthless.
            // Said out loud because it is a real, unrecoverable loss.
            Log.w(TAG, "credential document is unreadable; ${raw.length} chars dropped")
            emptyList()
        }
    }

    /** Returns whether the write landed. */
    private fun write(entries: List<Stored>): Boolean {
        val store = prefs
        if (store == null) {
            Log.w(TAG, "credential store is not ready; refusing to drop a token")
            return false
        }
        val encoded = runCatching { json.encodeToString(entries) }.getOrElse {
            Log.w(TAG, "could not encode credentials: ${it.message}")
            return false
        }
        return runCatching { store.edit().putString(KEY_ENTRIES, encoded).commit() }
            .getOrElse { false }
    }
}
