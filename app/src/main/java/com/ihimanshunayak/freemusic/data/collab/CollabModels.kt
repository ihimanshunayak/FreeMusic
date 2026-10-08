package com.ihimanshunayak.freemusic.data.collab

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The wire forms of the playlist service.
 *
 * Field names match `backend/playlist/model.go` exactly, via `@SerialName` where
 * Kotlin would spell them differently, so a rename on either side fails a test
 * rather than silently dropping a field into its default.
 *
 * Everything here is tolerant of unknown keys and missing optional fields: the
 * server is free to add a field, and an older app must keep working. That is the
 * same posture [com.ihimanshunayak.freemusic.data.listentogether.ListenTogether]
 * takes with the party protocol.
 */

/** What kind of playlist this is. Distinguishes what the UI may offer. */
@Serializable
enum class CollabPlaylistType {
    @SerialName("PERSONAL")
    PERSONAL,

    @SerialName("COLLABORATIVE")
    COLLABORATIVE,

    @SerialName("BLEND")
    BLEND,
}

/** What the caller may do, resolved by the server from the credential. */
@Serializable
enum class CollabRole {
    @SerialName("OWNER")
    OWNER,

    @SerialName("COLLABORATOR")
    COLLABORATOR,
}

@Serializable
data class CollabMember(
    val userId: String = "",
    val displayName: String = "",
    val avatarUrl: String? = null,
    val role: CollabRole = CollabRole.COLLABORATOR,
    val joinedAtMs: Long = 0L,
    val lastActiveMs: Long = 0L,
)

/**
 * One entry in a playlist.
 *
 * [entryId] identifies this occurrence, not the song: the same video may appear
 * twice, added by two people, and removing one must not disturb the other. Every
 * mutation names an entry by it rather than by [videoId].
 */
@Serializable
data class CollabTrack(
    val entryId: String = "",
    val videoId: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val thumbnailUrl: String? = null,
    val durationMs: Long? = null,
    val addedByUserId: String = "",
    val addedByName: String = "",
    val addedAtMs: Long = 0L,
    val position: Int = 0,
) {
    /**
     * The playable form.
     *
     * The mirror of
     * [com.ihimanshunayak.freemusic.data.playlist.StoredSong.toSong], and for the
     * same reasons: no `queueEntryId`, because that identity is assigned when a
     * track actually enters the queue and stamping one here would collide with it;
     * and `queueTier` CONTEXT, because the track is being played *from* this
     * playlist, which is the context.
     *
     * `durationMs` is dropped rather than converted: every other source in the app
     * carries a duration as display text, and inventing one from milliseconds here
     * would produce a second format for the same field on the same screen.
     */
    fun toSong(): com.ihimanshunayak.freemusic.data.model.Song =
        com.ihimanshunayak.freemusic.data.model.Song(
            videoId = videoId,
            title = title,
            artist = artist,
            thumbnailUrl = thumbnailUrl,
            albumName = album,
            queueTier = com.ihimanshunayak.freemusic.data.model.QueueTier.CONTEXT,
        )
}

/** A flavour the server can attach to a generated playlist. */
@Serializable
data class CollabBlendMeta(
    val generatedAtMs: Long = 0L,
    val generationReason: String = "",
    val compatibility: Map<String, Double> = emptyMap(),
    val memberCount: Int = 0,
    val trackCount: Int = 0,
)

/** The full playlist, as returned by a read or a mutation. */
@Serializable
data class CollabSnapshot(
    val id: String = "",
    val playlistType: CollabPlaylistType = CollabPlaylistType.COLLABORATIVE,
    val name: String = "",
    val description: String = "",
    val coverUrl: String? = null,
    val ownerId: String = "",
    val revision: Long = 0L,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val members: List<CollabMember> = emptyList(),
    val tracks: List<CollabTrack> = emptyList(),
    val viewerRole: CollabRole = CollabRole.COLLABORATOR,
    val blend: CollabBlendMeta? = null,
) {
    val isOwner: Boolean get() = viewerRole == CollabRole.OWNER

    /**
     * True when this device may edit the track list.
     *
     * A Blend is generated and members only read it, which the server enforces;
     * hiding the controls as well is what stops someone discovering that by
     * tapping a delete that then refuses.
     */
    val isEditable: Boolean get() = playlistType != CollabPlaylistType.BLEND

    fun trackByEntryId(entryId: String): CollabTrack? = tracks.firstOrNull { it.entryId == entryId }

    /**
     * The card form, for the library list.
     *
     * Derived here rather than requested separately: the snapshot already holds
     * everything a card draws, and asking the server for a summary of something
     * the device has just been sent in full would be a round trip for nothing.
     */
    fun toSummary(): CollabSummary {
        val thumbs = tracks.asSequence()
            .mapNotNull { it.thumbnailUrl?.takeIf { url -> url.isNotBlank() } }
            .distinct()
            .take(4)
            .toList()
        return CollabSummary(
            id = id,
            playlistType = playlistType,
            name = name,
            description = description,
            coverUrl = coverUrl,
            ownerId = ownerId,
            revision = revision,
            updatedAtMs = updatedAtMs,
            trackCount = tracks.size,
            memberCount = members.size,
            viewerRole = viewerRole,
            coverThumbs = thumbs,
        )
    }
}

/** The card form used by a list, so a list does not fetch every track list. */
@Serializable
data class CollabSummary(
    val id: String = "",
    val playlistType: CollabPlaylistType = CollabPlaylistType.COLLABORATIVE,
    val name: String = "",
    val description: String = "",
    val coverUrl: String? = null,
    val ownerId: String = "",
    val revision: Long = 0L,
    val updatedAtMs: Long = 0L,
    val trackCount: Int = 0,
    val memberCount: Int = 0,
    val viewerRole: CollabRole = CollabRole.COLLABORATOR,
    val coverThumbs: List<String> = emptyList(),
)

/** What an invitation link shows before anybody has joined. */
@Serializable
data class CollabInvitePreview(
    val playlistId: String = "",
    val playlistType: CollabPlaylistType = CollabPlaylistType.COLLABORATIVE,
    val name: String = "",
    val description: String = "",
    val coverUrl: String? = null,
    val memberCount: Int = 0,
    val trackCount: Int = 0,
    val coverThumbs: List<String> = emptyList(),
    val ownerName: String = "",
    val expiresAtMs: Long = 0L,
    val joinable: Boolean = false,
    /** Why not, when [joinable] is false. Shown verbatim rather than guessed. */
    val reason: String = "",
)

/** One server-recorded change, as a reconnecting client receives them. */
@Serializable
data class CollabRevision(
    val revision: Long = 0L,
    val kind: String = "",
    val atMs: Long = 0L,
    val byUserId: String = "",
    val payload: Map<String, JsonElement> = emptyMap(),
)

/**
 * The answer to "what did I miss".
 *
 * Either a run of [changes] or [snapshot], never both — the server guarantees
 * that structurally, and this type is the reason the client can rely on it: when
 * [resync] is true there is no change list to accidentally render as current.
 */
@Serializable
data class CollabDelta(
    val playlistId: String = "",
    val fromRevision: Long = 0L,
    val toRevision: Long = 0L,
    val changes: List<CollabRevision> = emptyList(),
    val resync: Boolean = false,
    val snapshot: CollabSnapshot? = null,
)

/**
 * The credential a device holds for one playlist.
 *
 * Both tokens are stored because they authorise different things: the owner
 * token is what allows a rename, an invitation, or a deletion, and the member
 * token is what allows everything else. Holding only the member token is what a
 * collaborator's device has, and it is enough for them.
 */
@Serializable
data class CollabCredential(
    val playlistId: String,
    val ownerToken: String? = null,
    val memberToken: String? = null,
) {
    /** The token to present. The owner token is preferred when both exist. */
    val token: String? get() = ownerToken ?: memberToken

    /** True when this device holds the owner secret. */
    val isOwner: Boolean get() = ownerToken != null
}

/**
 * A failure the server explained.
 *
 * Carries [code] so a caller can react to a specific one — a stale revision
 * needs a reload, a spent invitation does not — without matching on prose, and
 * [message] so whatever it does not recognise can still be shown.
 */
class CollabException(
    val code: String,
    val httpStatus: Int,
    override val message: String,
) : Exception(message) {

    /** True when the fix is to reload and try again, not to give up. */
    val isConcurrencyConflict: Boolean get() = code == CODE_REVISION_CONFLICT

    /** True when this device's credential no longer authorises the playlist. */
    val isCredentialRejected: Boolean get() = code == CODE_FORBIDDEN || code == CODE_NOT_FOUND

    companion object {
        const val CODE_REVISION_CONFLICT = "revision_conflict"
        const val CODE_FORBIDDEN = "forbidden"
        const val CODE_NOT_FOUND = "no_such_playlist"
        const val CODE_BAD_REQUEST = "bad_request"
        const val CODE_INVITE_EXPIRED = "invite_expired"
        const val CODE_INVITE_REVOKED = "invite_revoked"
        const val CODE_INVITE_USED = "invite_used"
        const val CODE_INVITE_LIMIT = "invite_limit"
        const val CODE_FULL = "playlist_full"
        const val CODE_TRACK_LIMIT = "track_limit"
        const val CODE_PLAYLIST_LIMIT = "playlist_limit"
        const val CODE_CREATE_RATE_LIMITED = "create_rate_limited"
    }
}

/**
 * The app's own wrapper for anything that never reached the server.
 *
 * Distinguishing this from a [CollabException] is what lets the UI say "you are
 * offline, this will sync" rather than showing a server's refusal for something
 * the server never saw.
 */
class CollabTransportException(
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause)
