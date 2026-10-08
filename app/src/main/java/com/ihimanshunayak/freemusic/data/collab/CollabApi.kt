package com.ihimanshunayak.freemusic.data.collab

import com.ihimanshunayak.freemusic.data.DebugLog as Log
import com.ihimanshunayak.freemusic.data.listentogether.ListenTogether
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The playlist service's HTTP client.
 *
 * A separate [HttpClient] from [ListenTogether]'s, because the tuning is
 * different in kind: a party socket must stay open for ten quiet minutes, while
 * every request here is a short round trip that should fail fast so the UI can
 * say something. Sharing the party client would mean either giving requests here
 * an unbounded read timeout, which hangs the UI, or giving the party socket a
 * bound, which drops it.
 *
 * Every failure is normalised into [CollabException] or [CollabTransportException]
 * before it leaves this class, so no caller has to know what Ktor throws or
 * whether a non-2xx response arrives as a value or an exception. The server's own
 * `error` code is preserved because that is what a caller switches on.
 */
object CollabApi {

    private const val TAG = "FreeMusicCollab"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val http = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                retryOnConnectionFailure(true)
            }
        }
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout)
        // Off, so a 409 or a 403 arrives as a body to read rather than as a
        // transport exception that has thrown the reason away.
        expectSuccess = false
    }

    /**
     * Where these requests go.
     *
     * Deliberately the same resolution [ListenTogether] uses for its idle
     * operations, so a playlist created against a custom server is found against
     * that same server and a device with no custom server uses the built-in
     * default. Resolving it per call rather than caching it means a server change
     * in settings takes effect on the next request, with no invalidation to get
     * wrong.
     */
    private fun baseUrl(): String = ListenTogether.effectiveIdleServerBase().trim().trimEnd('/')

    /**
     * Builds a full URL, tolerating a base with or without a scheme.
     *
     * The party client already normalises its servers, but this is reachable with
     * whatever that produced, and a base of `party.example.com` must not silently
     * become a request to a relative path.
     */
    private fun url(path: String, base: String = baseUrl()): String {
        if (base.isBlank()) {
            throw CollabTransportException(
                "No playlist server is configured. Open Listen Together and set one.",
            )
        }
        val qualified = if (base.startsWith("http://") || base.startsWith("https://")) base else "https://$base"
        return qualified + path
    }

    // ---- Response plumbing ----------------------------------------------

    /** The shape every failure response uses. */
    @Serializable
    private data class ApiError(val error: String = "", val message: String = "")

    /**
     * Turns a response into a value or throws the right exception.
     *
     * A non-2xx is read as an [ApiError] when it parses as one, and otherwise
     * reported with its status alone — a proxy or a load balancer can produce a
     * non-JSON error page, and reporting that as "the server refused" with a
     * status is more useful than a parse failure.
     */
    private suspend inline fun <reified T> decode(response: HttpResponse): T {
        if (response.status.value in 200..299) {
            return response.body()
        }
        val raw = runCatching { response.bodyAsText() }.getOrDefault("")
        val parsed = runCatching { json.decodeFromString<ApiError>(raw) }.getOrNull()
        val code = parsed?.error?.takeIf { it.isNotBlank() } ?: "http_${response.status.value}"
        val message = parsed?.message?.takeIf { it.isNotBlank() }
            ?: "The playlist server refused the request (${response.status.value})."
        Log.w(TAG, "request failed: $code (${response.status.value})")
        throw CollabException(code, response.status.value, message)
    }

    /**
     * Runs a request, converting anything that never reached the server into a
     * [CollabTransportException].
     *
     * The distinction matters downstream: a transport failure is worth retrying
     * and worth queueing, a server refusal is not.
     */
    private suspend fun <T> guarded(path: String, block: suspend () -> T): T {
        return try {
            block()
        } catch (e: CollabException) {
            throw e
        } catch (e: CollabTransportException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "request to $path did not reach the server: ${e.message}")
            throw CollabTransportException(
                e.message ?: "Could not reach the playlist server.",
                e,
            )
        }
    }

    /** Attaches the credential, when the call needs one. */
    private fun HttpRequestBuilder.bearer(token: String?) {
        if (!token.isNullOrBlank()) {
            header("Authorization", "Bearer $token")
        }
    }

    // ---- Reads -----------------------------------------------------------

    /**
     * Every playlist the presented credentials reach.
     *
     * The first token travels as the bearer and the rest as repeatable `token`
     * parameters, which is the shape the server accepts. Sent as one request
     * rather than one per credential: a device can hold dozens, and the union is
     * something the server can compute in a single pass over its set.
     */
    suspend fun list(credentials: List<CollabCredential>): List<CollabSummary> {
        val tokens = credentials.mapNotNull { it.token }.distinct()
        if (tokens.isEmpty()) return emptyList()
        return guarded("/api/playlists") {
            withContext(Dispatchers.IO) {
                val response = http.get(url("/api/playlists")) {
                    bearer(tokens.first())
                    tokens.drop(1).forEach { parameter("token", it) }
                }
                decode<ListResponse>(response).playlists
            }
        }
    }

    @Serializable
    private data class ListResponse(val playlists: List<CollabSummary> = emptyList(), val durable: Boolean = true)

    @Serializable
    private data class SnapshotResponse(val playlist: CollabSnapshot)

    @Serializable
    private data class CreateResponse(
        val playlist: CollabSnapshot,
        val ownerToken: String = "",
        val memberToken: String = "",
    )

    @Serializable
    private data class AddedResponse(val playlist: CollabSnapshot, val added: Int = 0)

    @Serializable
    private data class Created(val id: String = "", val deleted: Boolean = false, val left: Boolean = false, val removed: Boolean = false, val revoked: Boolean = false)

    /** Reads one playlist. */
    suspend fun get(playlistId: String, token: String, userId: String): CollabSnapshot {
        return guarded("/api/playlists/$playlistId") {
            withContext(Dispatchers.IO) {
                val response = http.get(url("/api/playlists/$playlistId")) {
                    bearer(token)
                    parameter("userId", userId)
                }
                decode<SnapshotResponse>(response).playlist
            }
        }
    }

    /**
     * What changed since [fromRevision].
     *
     * The server answers with either a complete run of changes or a snapshot to
     * replace everything, and never a partial run presented as current.
     */
    suspend fun deltas(playlistId: String, token: String, userId: String, fromRevision: Long): CollabDelta {
        return guarded("/api/playlists/$playlistId/deltas") {
            withContext(Dispatchers.IO) {
                val response = http.get(url("/api/playlists/$playlistId/deltas")) {
                    bearer(token)
                    parameter("userId", userId)
                    parameter("from", fromRevision)
                }
                decode<CollabDelta>(response)
            }
        }
    }

    // ---- Writes ----------------------------------------------------------

    /** Creates a playlist and returns the snapshot with its two tokens. */
    suspend fun create(
        name: String,
        description: String,
        userId: String,
        displayName: String,
        avatarUrl: String?,
        playlistType: CollabPlaylistType = CollabPlaylistType.COLLABORATIVE,
    ): CreatedPlaylist {
        val body = CreateRequest(
            playlistType = playlistType.name,
            name = name,
            description = description,
            userId = userId,
            displayName = displayName,
            avatarUrl = avatarUrl,
        )
        return guarded("/api/playlists") {
            withContext(Dispatchers.IO) {
                val response = http.post(url("/api/playlists")) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                val decoded = decode<CreateResponse>(response)
                CreatedPlaylist(
                    playlist = decoded.playlist,
                    credential = CollabCredential(
                        playlistId = decoded.playlist.id,
                        ownerToken = decoded.ownerToken.takeIf { it.isNotBlank() },
                        memberToken = decoded.memberToken.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    @Serializable
    private data class CreateRequest(
        val playlistType: String,
        val name: String,
        val description: String = "",
        val userId: String,
        val displayName: String = "",
        val avatarUrl: String? = null,
    )

    /**
     * Renames, re-describes, or re-covers a playlist. Owner-only, which the
     * server enforces; the UI hides it as well.
     */
    suspend fun updateMetadata(
        playlistId: String,
        token: String,
        userId: String,
        name: String? = null,
        description: String? = null,
        coverUrl: String? = null,
    ): CollabSnapshot {
        val body = MetadataRequest(name = name, description = description, coverUrl = coverUrl)
        return guarded("/api/playlists/$playlistId") {
            withContext(Dispatchers.IO) {
                val response = http.post(url("/api/playlists/$playlistId")) {
                    bearer(token)
                    parameter("userId", userId)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                decode<SnapshotResponse>(response).playlist
            }
        }
    }

    /**
     * A patch body where an absent field and an empty one mean different things.
     *
     * `explicitNulls = false` on the serializer is what makes that work: a null
     * here is omitted from the JSON entirely, so "leave the description alone"
     * and "clear the description" stay distinguishable on the wire. Sending `""`
     * is the second; sending nothing is the first.
     */
    @Serializable
    private data class MetadataRequest(
        val name: String? = null,
        val description: String? = null,
        val coverUrl: String? = null,
    )

    /** Deletes a playlist. Owner-only. */
    suspend fun delete(playlistId: String, token: String, userId: String) {
        guarded("/api/playlists/$playlistId") {
            withContext(Dispatchers.IO) {
                val response = http.delete(url("/api/playlists/$playlistId")) {
                    bearer(token)
                    parameter("userId", userId)
                }
                decode<Created>(response)
            }
        }
        Unit
    }

    /**
     * Appends tracks, claiming to be at [baseRevision].
     *
     * Returns the new snapshot and how many actually landed — the server drops
     * entries it cannot use rather than failing the whole batch, so a caller that
     * cares must read the count rather than assume it equalled the request.
     */
    suspend fun addTracks(
        playlistId: String,
        token: String,
        userId: String,
        baseRevision: Long,
        tracks: List<NewTrack>,
    ): AddResult {
        val body = AddTracksRequest(baseRevision = baseRevision, tracks = tracks)
        return guarded("/api/playlists/$playlistId/tracks") {
            withContext(Dispatchers.IO) {
                val response = http.post(url("/api/playlists/$playlistId/tracks")) {
                    bearer(token)
                    parameter("userId", userId)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                val decoded = decode<AddedResponse>(response)
                AddResult(playlist = decoded.playlist, added = decoded.added)
            }
        }
    }

    /**
     * A track as the client sends it.
     *
     * Every field the server accepts, all optional but [videoId], because a
     * playlist entry is built from whatever the source screen knew — a search
     * result has a thumbnail, a queued item may not.
     */
    @Serializable
    data class NewTrack(
        val videoId: String,
        val title: String = "",
        val artist: String = "",
        val album: String? = null,
        val thumbnailUrl: String? = null,
        val durationMs: Long? = null,
    )

    @Serializable
    private data class AddTracksRequest(val baseRevision: Long, val tracks: List<NewTrack>)

    data class AddResult(val playlist: CollabSnapshot, val added: Int)

    /** Removes one entry, named by its entry id. */
    suspend fun removeTrack(
        playlistId: String,
        token: String,
        userId: String,
        baseRevision: Long,
        entryId: String,
    ): CollabSnapshot {
        return guarded("/api/playlists/$playlistId/tracks/$entryId") {
            withContext(Dispatchers.IO) {
                val response = http.delete(url("/api/playlists/$playlistId/tracks/$entryId")) {
                    bearer(token)
                    parameter("userId", userId)
                    parameter("baseRevision", baseRevision)
                }
                decode<SnapshotResponse>(response).playlist
            }
        }
    }

    /** Moves one entry to an absolute index. */
    suspend fun moveTrack(
        playlistId: String,
        token: String,
        userId: String,
        baseRevision: Long,
        entryId: String,
        to: Int,
    ): CollabSnapshot {
        val body = MoveRequest(baseRevision = baseRevision, to = to)
        return guarded("/api/playlists/$playlistId/tracks/$entryId/move") {
            withContext(Dispatchers.IO) {
                val response = http.post(url("/api/playlists/$playlistId/tracks/$entryId/move")) {
                    bearer(token)
                    parameter("userId", userId)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                decode<SnapshotResponse>(response).playlist
            }
        }
    }

    @Serializable
    private data class MoveRequest(val baseRevision: Long, val to: Int)

    /** Empties the track list without deleting the playlist. */
    suspend fun clearTracks(
        playlistId: String,
        token: String,
        userId: String,
        baseRevision: Long,
    ): CollabSnapshot {
        val body = BaseRevisionRequest(baseRevision = baseRevision)
        return guarded("/api/playlists/$playlistId/tracks/clear") {
            withContext(Dispatchers.IO) {
                val response = http.post(url("/api/playlists/$playlistId/tracks/clear")) {
                    bearer(token)
                    parameter("userId", userId)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                decode<SnapshotResponse>(response).playlist
            }
        }
    }

    @Serializable
    private data class BaseRevisionRequest(val baseRevision: Long)

    // ---- Invitations -----------------------------------------------------

    /** The invitation, with the plaintext token that exists only in this answer. */
    data class CreatedInvite(
        val token: String,
        val expiresAtMs: Long,
        val maxUses: Int,
        val deepLink: String,
        val webLink: String,
    )

    suspend fun invite(
        playlistId: String,
        token: String,
        userId: String,
        maxUses: Int = 0,
        ttlMs: Long = 0L,
    ): CreatedInvite {
        val body = InviteRequest(
            maxUses = maxUses.takeIf { it > 0 },
            ttlMs = ttlMs.takeIf { it > 0 },
        )
        return guarded("/api/playlists/$playlistId/invites") {
            withContext(Dispatchers.IO) {
                val response = http.post(url("/api/playlists/$playlistId/invites")) {
                    bearer(token)
                    parameter("userId", userId)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                val decoded = decode<InviteResponse>(response)
                CreatedInvite(
                    token = decoded.inviteToken,
                    expiresAtMs = decoded.expiresAtMs,
                    maxUses = decoded.maxUses,
                    deepLink = decoded.deepLink,
                    webLink = decoded.webLink,
                )
            }
        }
    }

    @Serializable
    private data class InviteRequest(val maxUses: Int? = null, val ttlMs: Long? = null)

    @Serializable
    private data class InviteResponse(
        val inviteToken: String = "",
        val expiresAtMs: Long = 0L,
        val maxUses: Int = 0,
        val deepLink: String = "",
        val webLink: String = "",
    )

    /** Withdraws an outstanding invitation. */
    suspend fun revokeInvite(playlistId: String, token: String, userId: String, inviteToken: String) {
        val body = RevokeRequest(inviteToken = inviteToken)
        guarded("/api/playlists/$playlistId/invites") {
            withContext(Dispatchers.IO) {
                val response = http.delete(url("/api/playlists/$playlistId/invites")) {
                    bearer(token)
                    parameter("userId", userId)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                decode<Created>(response)
            }
        }
        Unit
    }

    @Serializable
    private data class RevokeRequest(val inviteToken: String)

    /**
     * What an invitation points at, before joining.
     *
     * Unauthenticated by design — the recipient does not have access yet, which
     * is the whole point of a link.
     */
    suspend fun previewInvite(inviteToken: String): CollabInvitePreview {
        return guarded("/api/playlist-invites/$inviteToken") {
            withContext(Dispatchers.IO) {
                val response = http.get(url("/api/playlist-invites/$inviteToken"))
                decode<CollabInvitePreview>(response)
            }
        }
    }

    /** Accepts an invitation, returning the joined snapshot and the new token. */
    suspend fun join(
        inviteToken: String,
        userId: String,
        displayName: String,
        avatarUrl: String?,
    ): JoinOutcome {
        val body = JoinRequest(userId = userId, displayName = displayName, avatarUrl = avatarUrl)
        return guarded("/api/playlist-invites/$inviteToken") {
            withContext(Dispatchers.IO) {
                val response = http.post(url("/api/playlist-invites/$inviteToken")) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                val decoded = decode<JoinResponse>(response)
                JoinOutcome(
                    playlist = decoded.playlist,
                    memberToken = decoded.memberToken,
                    alreadyMember = decoded.alreadyMember,
                )
            }
        }
    }

    @Serializable
    private data class JoinRequest(val userId: String, val displayName: String = "", val avatarUrl: String? = null)

    @Serializable
    private data class JoinResponse(
        val playlist: CollabSnapshot,
        val memberToken: String = "",
        val alreadyMember: Boolean = false,
    )

    data class JoinOutcome(val playlist: CollabSnapshot, val memberToken: String, val alreadyMember: Boolean)

    /** Leaves a playlist. The device should forget the credential afterwards. */
    suspend fun leave(playlistId: String, token: String, userId: String) {
        guarded("/api/playlists/$playlistId/leave") {
            withContext(Dispatchers.IO) {
                val response = http.post(url("/api/playlists/$playlistId/leave")) {
                    bearer(token)
                    parameter("userId", userId)
                }
                decode<Created>(response)
            }
        }
        Unit
    }

    /** Removes somebody else. Owner-only. */
    suspend fun removeMember(playlistId: String, token: String, userId: String, memberUserId: String) {
        guarded("/api/playlists/$playlistId/members/$memberUserId") {
            withContext(Dispatchers.IO) {
                val response = http.delete(url("/api/playlists/$playlistId/members/$memberUserId")) {
                    bearer(token)
                    parameter("userId", userId)
                }
                decode<Created>(response)
            }
        }
        Unit
    }

    /** Releases the engine, for a test or a shutdown. */
    fun close() {
        http.close()
    }
}

/** A playlist the server created, with the credential that reaches it. */
data class CreatedPlaylist(
    val playlist: CollabSnapshot,
    val credential: CollabCredential,
)
