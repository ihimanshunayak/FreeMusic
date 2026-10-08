package com.ihimanshunayak.freemusic.data.collab

import com.ihimanshunayak.freemusic.data.DebugLog as Log
import com.ihimanshunayak.freemusic.data.listentogether.ListenTogether
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The app's view of the collaborative playlists it holds credentials for.
 *
 * Mirroring [com.ihimanshunayak.freemusic.data.listentogether.ListenTogether] in
 * shape rather than sharing code with it: that object is built around one live
 * socket and one active session, while this one holds many playlists that are
 * all dormant most of the time. What they share is the posture — one
 * `StateFlow` of state, a mutex around anything that talks to the server, and no
 * caller ever touching the client directly.
 *
 * Two rules shape the design:
 *
 * 1. **A credential is never assumed.** Every call resolves the token from
 *    [CollabCredentials] and lets the server decide. Nothing here checks
 *    ownership locally, because a local check that drifted from the server's
 *    would be a screen that offers an action the server refuses.
 * 2. **The server's revision is the only truth.** Mutations carry the revision
 *    the client believed it was at, and a mismatch is surfaced as
 *    [CollabException.isConcurrencyConflict] rather than retried blindly. A
 *    silent retry would overwrite somebody else's edit with a stale one, which is
 *    exactly what the revision exists to prevent.
 */
object CollabPlaylists {

    private const val TAG = "FreeMusicCollab"

    /** What the library screen needs to draw itself. */
    data class State(
        val summaries: List<CollabSummary> = emptyList(),
        /** Ids this device holds a credential for, whether or not they loaded. */
        val knownIds: Set<String> = emptySet(),
        val loading: Boolean = false,
        /** Set when the last refresh could not reach the server. */
        val offline: Boolean = false,
        /** A message worth showing, already written for a person. */
        val error: String? = null,
        /** Whether the server can persist anything across a restart. */
        val durable: Boolean = true,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /**
     * The prefix that carries a shared playlist's id through the app's generic
     * browsing paths.
     *
     * A fourth namespace alongside the downloads, device-playlist and remote
     * prefixes, and it has to be its own for the same reason the other three are:
     * the drawer, the "Show all" page and the back stack all pass an opaque id
     * around, and a shared id that collided with a device id would open whichever
     * of the two happened to be checked first. Ids here are also minted by the
     * server, so they must never be handed to anything that would try to parse
     * them as YouTube ids.
     */
    const val BROWSE_PREFIX = "shared:"

    /** The page id for the shared playlist stored under [id]. */
    fun pageIdFor(id: String): String = BROWSE_PREFIX + id

    /** The playlist id [pageIdFor] built [browseId] from, or null if it didn't. */
    fun idOf(browseId: String?): String? =
        browseId?.removePrefix(BROWSE_PREFIX)?.takeIf { it != browseId && it.isNotEmpty() }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * The full snapshots currently open, keyed by playlist id.
     *
     * Kept here rather than in the screen so a background refresh, a delta, and a
     * local edit all land in the same place, and so two screens showing the same
     * playlist cannot disagree.
     */
    private val _open = MutableStateFlow<Map<String, CollabSnapshot>>(emptyMap())
    val open: StateFlow<Map<String, CollabSnapshot>> = _open.asStateFlow()

    fun snapshot(playlistId: String): CollabSnapshot? = _open.value[playlistId]

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** Records a failure in a form the UI can show without inspecting it. */
    private fun report(e: Throwable, fallback: String) {
        val message = when (e) {
            is CollabException -> e.message
            is CollabTransportException -> e.message
            else -> fallback
        }
        _state.update { it.copy(error = message, offline = e is CollabTransportException) }
    }

    // ---- Reads -----------------------------------------------------------

    /**
     * Reloads the library.
     *
     * The credential set is the input, not a query: a device with no credentials
     * has no playlists and does not ask the server anything, which is both faster
     * and the only correct answer.
     */
    suspend fun refresh() {
        val credentials = CollabCredentials.all.value
        val known = CollabCredentials.playlistIds.value
        if (credentials.isEmpty()) {
            _state.update { it.copy(summaries = emptyList(), knownIds = known, loading = false, offline = false) }
            return
        }
        _state.update { it.copy(loading = true) }
        try {
            val summaries = CollabApi.list(credentials)
            _state.update {
                it.copy(
                    summaries = summaries,
                    knownIds = known,
                    loading = false,
                    offline = false,
                    error = null,
                )
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // The list is left as it was: showing an empty library because the
            // network blipped would look like every playlist had been deleted.
            _state.update { it.copy(loading = false) }
            report(e, "Could not load your playlists.")
        }
    }

    /**
     * Loads one playlist and holds it open.
     *
     * Returns the snapshot so a screen can render immediately without waiting to
     * observe the flow.
     */
    suspend fun open(playlistId: String, force: Boolean = false): CollabSnapshot? {
        val existing = _open.value[playlistId]
        if (existing != null && !force) return existing

        val credential = CollabCredentials.credentialFor(playlistId) ?: run {
            Log.w(TAG, "no credential for $playlistId")
            return null
        }
        val token = credential.token ?: return null

        return try {
            val snapshot = withContext(Dispatchers.IO) {
                CollabApi.get(playlistId, token, selfUserId())
            }
            publish(snapshot)
            snapshot
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // A revoked or deleted playlist must stop being offered, rather than
            // staying in the library as a card that opens into an error.
            if (e is CollabException && e.isCredentialRejected) {
                CollabCredentials.forget(playlistId)
                _state.update { it.copy(knownIds = CollabCredentials.playlistIds.value) }
            }
            report(e, "Could not open that playlist.")
            null
        }
    }

    /** Reloads one playlist in the background. */
    fun openAsync(playlistId: String, force: Boolean = false) {
        scope.launch { open(playlistId, force) }
    }

    /** Stops holding a playlist open. The credential stays. */
    fun close(playlistId: String) {
        _open.update { it - playlistId }
    }

    // ---- Mutations -------------------------------------------------------

    /**
     * Every mutation goes through here.
     *
     * One place, so the credential resolution, the revision argument, the
     * published result and the error normalisation cannot drift between the eight
     * operations. [block] receives the token, this device's userId, and the
     * revision currently held, and returns the new snapshot.
     */
    private suspend fun mutate(
        playlistId: String,
        fallbackMessage: String,
        block: suspend (token: String, userId: String, baseRevision: Long) -> CollabSnapshot,
    ): CollabSnapshot? {
        val credential = CollabCredentials.credentialFor(playlistId)
        val token = credential?.token
        if (token == null) {
            _state.update { it.copy(error = "This device no longer has access to that playlist.") }
            return null
        }
        return mutex.withLock {
            try {
                val base = _open.value[playlistId]?.revision ?: 0L
                val snapshot = withContext(Dispatchers.IO) {
                    block(token, selfUserId(), base)
                }
                publish(snapshot)
                snapshot
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is CollabException && e.isConcurrencyConflict) {
                    // The server refused because somebody else changed the list
                    // between the read and the write. Reloading is the fix, so do
                    // it rather than leaving the user staring at a stale screen.
                    Log.i(TAG, "$playlistId changed elsewhere; reloading")
                    runCatching { open(playlistId, force = true) }
                }
                if (e is CollabException && e.isCredentialRejected) {
                    CollabCredentials.forget(playlistId)
                }
                report(e, fallbackMessage)
                null
            }
        }
    }

    /** Publishes a snapshot to the open map and refreshes the card on the list. */
    private fun publish(snapshot: CollabSnapshot) {
        _open.update { it + (snapshot.id to snapshot) }
        _state.update { current ->
            val index = current.summaries.indexOfFirst { it.id == snapshot.id }
            val card = snapshot.toSummary()
            val updated = if (index >= 0) {
                current.summaries.toMutableList().also { it[index] = card }
            } else {
                current.summaries + card
            }
            // Newest first, matching the server's own ordering, so a write lands
            // the playlist where a refresh would have put it.
            current.copy(summaries = updated.sortedByDescending { it.updatedAtMs })
        }
    }

    suspend fun addTracks(playlistId: String, tracks: List<CollabApi.NewTrack>): Int? {
        if (tracks.isEmpty()) return 0
        var added: Int? = null
        mutate(playlistId, "Could not add those songs.") { token, userId, base ->
            val result = CollabApi.addTracks(playlistId, token, userId, base, tracks)
            added = result.added
            result.playlist
        }
        return added
    }

    suspend fun removeTrack(playlistId: String, entryId: String): Boolean =
        mutate(playlistId, "Could not remove that song.") { token, userId, base ->
            CollabApi.removeTrack(playlistId, token, userId, base, entryId)
        } != null

    suspend fun moveTrack(playlistId: String, entryId: String, to: Int): Boolean =
        mutate(playlistId, "Could not reorder the playlist.") { token, userId, base ->
            CollabApi.moveTrack(playlistId, token, userId, base, entryId, to)
        } != null

    suspend fun clearTracks(playlistId: String): Boolean =
        mutate(playlistId, "Could not clear the playlist.") { token, userId, base ->
            CollabApi.clearTracks(playlistId, token, userId, base)
        } != null

    suspend fun rename(playlistId: String, name: String): Boolean =
        mutate(playlistId, "Could not rename the playlist.") { token, userId, _ ->
            CollabApi.updateMetadata(playlistId, token, userId, name = name)
        } != null

    suspend fun setDescription(playlistId: String, description: String): Boolean =
        mutate(playlistId, "Could not update the description.") { token, userId, _ ->
            CollabApi.updateMetadata(playlistId, token, userId, description = description)
        } != null

    suspend fun setCover(playlistId: String, coverUrl: String): Boolean =
        mutate(playlistId, "Could not update the cover.") { token, userId, _ ->
            CollabApi.updateMetadata(playlistId, token, userId, coverUrl = coverUrl)
        } != null

    /**
     * Creates a playlist and remembers its credential.
     *
     * The token is persisted before this returns anything. A create that reached
     * the server but whose token was dropped locally would leave a playlist
     * nobody can open and that occupies a slot against the server's cap, so the
     * write is not something to do lazily in the background.
     */
    suspend fun create(
        name: String,
        description: String = "",
        displayName: String = "",
        avatarUrl: String? = null,
        playlistType: CollabPlaylistType = CollabPlaylistType.COLLABORATIVE,
    ): CollabSnapshot? {
        return try {
            val created = withContext(Dispatchers.IO) {
                CollabApi.create(
                    name = name,
                    description = description,
                    userId = selfUserId(),
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    playlistType = playlistType,
                )
            }
            CollabCredentials.save(created.credential)
            _state.update { it.copy(knownIds = CollabCredentials.playlistIds.value) }
            publish(created.playlist)
            created.playlist
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            report(e, "Could not create the playlist.")
            null
        }
    }

    suspend fun delete(playlistId: String): Boolean {
        val credential = CollabCredentials.credentialFor(playlistId) ?: return false
        val token = credential.token ?: return false
        return try {
            withContext(Dispatchers.IO) { CollabApi.delete(playlistId, token, selfUserId()) }
            CollabCredentials.forget(playlistId)
            _open.update { it - playlistId }
            _state.update { current ->
                current.copy(
                    summaries = current.summaries.filterNot { it.id == playlistId },
                    knownIds = CollabCredentials.playlistIds.value,
                )
            }
            true
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            report(e, "Could not delete the playlist.")
            false
        }
    }

    /** Leaves a playlist, or forgets it when this device is the owner. */
    suspend fun leave(playlistId: String): Boolean {
        val credential = CollabCredentials.credentialFor(playlistId) ?: return false
        val token = credential.token ?: return false
        return try {
            withContext(Dispatchers.IO) { CollabApi.leave(playlistId, token, selfUserId()) }
            CollabCredentials.forget(playlistId)
            _open.update { it - playlistId }
            _state.update { current ->
                current.copy(
                    summaries = current.summaries.filterNot { it.id == playlistId },
                    knownIds = CollabCredentials.playlistIds.value,
                )
            }
            true
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            report(e, "Could not leave the playlist.")
            false
        }
    }

    // ---- Invitations -----------------------------------------------------

    /** Mints an invitation. The token exists only in the returned value. */
    suspend fun invite(playlistId: String, maxUses: Int = 0, ttlMs: Long = 0L): CollabApi.CreatedInvite? {
        val token = CollabCredentials.tokenFor(playlistId) ?: return null
        return try {
            withContext(Dispatchers.IO) {
                CollabApi.invite(playlistId, token, selfUserId(), maxUses, ttlMs)
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            report(e, "Could not create an invitation.")
            null
        }
    }

    suspend fun revokeInvite(playlistId: String, inviteToken: String): Boolean {
        val token = CollabCredentials.tokenFor(playlistId) ?: return false
        return try {
            withContext(Dispatchers.IO) {
                CollabApi.revokeInvite(playlistId, token, selfUserId(), inviteToken)
            }
            true
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            report(e, "Could not withdraw that invitation.")
            false
        }
    }

    /** What an invitation points at, before joining. Needs no credential. */
    suspend fun previewInvite(inviteToken: String): CollabInvitePreview? {
        return try {
            withContext(Dispatchers.IO) { CollabApi.previewInvite(inviteToken) }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            report(e, "That invitation could not be opened.")
            null
        }
    }

    /**
     * Accepts an invitation and remembers the membership token.
     *
     * The token is saved before returning, for the same reason a create's is: the
     * join has already happened server-side by the time this returns, and a token
     * that is not on disk is a seat the user cannot use.
     */
    suspend fun join(
        inviteToken: String,
        displayName: String = "",
        avatarUrl: String? = null,
    ): CollabSnapshot? {
        return try {
            val outcome = withContext(Dispatchers.IO) {
                CollabApi.join(
                    inviteToken = inviteToken,
                    userId = selfUserId(),
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                )
            }
            CollabCredentials.mergeMemberToken(outcome.playlist.id, outcome.memberToken)
            _state.update { it.copy(knownIds = CollabCredentials.playlistIds.value) }
            publish(outcome.playlist)
            outcome.playlist
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            report(e, "Could not join that playlist.")
            null
        }
    }

    suspend fun removeMember(playlistId: String, memberUserId: String): Boolean {
        val token = CollabCredentials.tokenFor(playlistId) ?: return false
        return try {
            withContext(Dispatchers.IO) {
                CollabApi.removeMember(playlistId, token, selfUserId(), memberUserId)
            }
            // Reload, because the server owns the member list and the removal also
            // bumps the revision that another screen may be holding.
            open(playlistId, force = true)
            true
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            report(e, "Could not remove that member.")
            false
        }
    }

    // ---- Identity --------------------------------------------------------

    /**
     * This device's playlist pseudonym.
     *
     * Read from [ListenTogether] rather than derived here, so a party and a
     * playlist agree on who somebody is. Two independent derivations would drift
     * the moment either changed, and the drift would show up as one user
     * appearing twice in a member list.
     */
    private fun selfUserId(): String = ListenTogether.selfUserIdForPlaylists()

    /** Refreshes in the background, for a caller that cannot suspend. */
    fun refreshAsync() {
        scope.launch { refresh() }
    }

    /**
     * Runs a suspend mutation from a composable, which cannot suspend.
     *
     * Deliberately not `rememberCoroutineScope()`: a screen that launched these
     * on its own scope would cancel an in-flight write when the user navigated
     * away — exactly when a write is most likely to be happening, since adding a
     * song and leaving is one gesture. This scope outlives the screen, so a write
     * that has been sent is allowed to finish and land in [open].
     */
    fun scopeLaunch(block: suspend () -> Unit) {
        scope.launch { block() }
    }
}
