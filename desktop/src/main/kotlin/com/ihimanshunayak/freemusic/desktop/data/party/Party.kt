// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - Listen Together.
//
// NAME
//     Party.kt - the synchronised listening room client.
//
// DESCRIPTION
//     Listen Together puts several people in one playback session: one host
//     drives, everyone else follows, and everyone sees the same queue and the
//     same lyrics. The hard part is not the network, it is the *clock*. Two
//     machines' wall clocks differ by seconds, and a guest that naively honours
//     `startedAt` from the host will be audibly out of sync - which is the one
//     failure a shared listening feature cannot survive.
//
//     So the protocol here is deliberately explicit about time: a guest
//     estimates its offset from the host by round-tripping a ping and taking the
//     midpoint, and only then maps the host's start instant onto its own clock.
//     Playback position is then derived from the local clock plus that offset,
//     never from the arrival time of a message, because a message that took
//     400 ms to arrive would otherwise put the guest 400 ms behind.
//
// RESPONSIBILITIES
//     - Join and leave a room over a WebSocket.
//     - Estimate and track the offset between the local and the host clock.
//     - Maintain the room's queue, members and now-playing state.
//     - Apply host-authoritative playback and expose guest control requests.
//
// DEPENDENCIES
//     - OkHttp's WebSocket for transport.
//     - kotlinx.serialization's JSON tree model for the wire format.
//
// INTEGRATION NOTES
//     - The protocol is a flat JSON envelope with a `type` discriminator rather
//       than a polymorphic sealed hierarchy, so a hand-written test client or a
//       different Free Music build can produce a valid message without this
//       file's model classes.
//     - A disconnected socket retries with exponential backoff, capped. Giving
//       up permanently would leave a guest silently out of the room with no
//       indication that it happened.

package com.ihimanshunayak.freemusic.desktop.data.party

import com.ihimanshunayak.freemusic.desktop.data.Http
import com.ihimanshunayak.freemusic.desktop.model.Track
import com.ihimanshunayak.freemusic.desktop.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Locale
import java.util.UUID

// ---------------------------------------------------------------------------
// ## SECTION: Model
// ---------------------------------------------------------------------------

/** Who is in the room and what they may do. */
enum class PartyRole(val label: String) {
    HOST("Host"), GUEST("Guest"), LISTENER("Listener")
}

/** One member of the room. */
data class PartyMember(
    val id: String,
    val name: String,
    val role: PartyRole = PartyRole.LISTENER,
    val isSelf: Boolean = false,
) {
    val initial: String get() = name.trim().take(1).uppercase(Locale.ROOT).ifBlank { "?" }
}

/** One entry in the room's queue. */
data class PartyQueueItem(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationSeconds: Int = 0,
    val addedBy: String = "",
) {
    fun toTrack(): Track = Track(
        id = "party:$videoId",
        title = title,
        artist = artist,
        durationSeconds = durationSeconds,
        thumbnailUrl = thumbnailUrl,
        videoId = videoId,
    )
}

/**
 * What the room is playing, expressed in the host's own clock.
 *
 * [startedAtHostMs] is the host's wall clock when the track started. A guest
 * converts it through [PartyState.clockOffsetMs] rather than trusting the
 * moment the message happened to arrive.
 */
data class PartyPlayback(
    val videoId: String? = null,
    val startedAtHostMs: Long = 0L,
    val playing: Boolean = false,
    val positionMsAtHost: Long = 0L,
)

/** The whole observable room. */
data class PartyState(
    val connected: Boolean = false,
    val roomCode: String = "",
    val selfName: String = "",
    val isHost: Boolean = false,
    val allowGuestControl: Boolean = true,
    val members: List<PartyMember> = emptyList(),
    val queue: List<PartyQueueItem> = emptyList(),
    val playback: PartyPlayback = PartyPlayback(),
    val clockOffsetMs: Long = 0L,
    val roundTripMs: Long = 0L,
    val lastError: String? = null,
) {
    /** Everyone but me, which is what the members strip shows. */
    val others: List<PartyMember> get() = members.filterNot { it.isSelf }

    /**
     * The track position to seek to, in the local clock.
     *
     * A guest is silent between host messages, so the position has to be
     * *derived* rather than received: the host's start instant, shifted onto the
     * local clock, plus however long ago that was.
     */
    fun positionNowMs(localNowMs: Long = System.currentTimeMillis()): Long {
        if (playback.videoId == null) return 0L
        if (!playback.playing) return playback.positionMsAtHost
        val startedLocal = playback.startedAtHostMs - clockOffsetMs
        return (localNowMs - startedLocal).coerceAtLeast(0L)
    }

    /** True when the local player is following the host rather than the user. */
    val hasRemotePlayback: Boolean get() = connected && !isHost && playback.videoId != null

    /** True when the clock estimate is good enough to correct drift. */
    val clockIsCalibrated: Boolean get() = roundTripMs > 0L && roundTripMs < 2_000L
}

// ---------------------------------------------------------------------------
// ## SECTION: Wire format
// ---------------------------------------------------------------------------

/**
 * The message types the protocol uses.
 *
 * Kept as string constants rather than an enum so an unknown type from a newer
 * server is ignored with a log line instead of throwing during deserialisation.
 */
internal object PartyWire {
    const val HELLO = "hello"
    const val WELCOME = "welcome"
    const val STATE = "state"
    const val QUEUE = "queue"
    const val MEMBERS = "members"
    const val PLAYBACK = "playback"
    const val PING = "ping"
    const val PONG = "pong"
    const val REQUEST = "request"
    const val ERROR = "error"

    /** Guest-initiated actions carried inside a [REQUEST]. */
    const val ACTION_PLAY = "play"
    const val ACTION_PAUSE = "pause"
    const val ACTION_SKIP = "skip"
    const val ACTION_SEEK = "seek"
    const val ACTION_ENQUEUE = "enqueue"
}

// ---------------------------------------------------------------------------
// ## SECTION: The client
// ---------------------------------------------------------------------------

/**
 * The synchronised listening room.
 *
 * Both roles run the same client: a host applies every local transport change to
 * its own state and broadcasts it, a guest applies every received change to its
 * own state. There is no separate "server" object, because the host's own player
 * *is* the server.
 */
class PartyClient(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val _state = MutableStateFlow(PartyState())
    val state: StateFlow<PartyState> = _state.asStateFlow()

    private var socket: WebSocket? = null
    private var endpoint: String = ""
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var attempt = 0

    /** Non-fatal problems surface here rather than as a dialog. */
    var onNotice: ((String) -> Unit)? = null

    /**
     * Called when the host's playback changes in a way a guest must obey.
     *
     * The client holds no reference to the player, which keeps this class
     * testable and the dependency one-directional: the app wires this callback
     * to `PlayerController`.
     */
    var onApplyPlayback: ((PartyPlayback, Long) -> Unit)? = null

    /** True once a room has been joined, whether or not the socket is up. */
    val isInRoom: Boolean get() = endpoint.isNotBlank()

    // -- ## SUBSECTION: Joining and leaving ---------------------------------

    /**
     * Connects to [roomCode] on [serverUrl].
     *
     * [asHost] makes this client the authority: its own transport drives the
     * room and it ignores inbound playback. That check lives here rather than on
     * the server so a host that loses its connection still plays locally instead
     * of stalling.
     */
    fun connect(serverUrl: String, roomCode: String, displayName: String, asHost: Boolean) {
        disconnect()
        endpoint = normaliseEndpoint(serverUrl, roomCode)
        if (endpoint.isBlank()) {
            _state.value = _state.value.copy(lastError = "Enter a room server address")
            return
        }
        _state.value = _state.value.copy(
            roomCode = roomCode,
            selfName = displayName.ifBlank { "Listener" },
            isHost = asHost,
            lastError = null,
        )
        open()
    }

    /** Leaves the room and closes the socket. */
    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        pingJob?.cancel()
        pingJob = null
        runCatching { socket?.close(1000, "leaving") }
        socket = null
        endpoint = ""
        attempt = 0
        _state.value = PartyState()
    }

    fun close() {
        disconnect()
        scope.cancel()
    }

    /**
     * Turns a server address and room code into a WebSocket URL.
     *
     * Accepts a bare host so a user can type `example.com:8080` rather than
     * having to know that the scheme becomes `wss` for a public host and `ws`
     * for a loopback one - and that distinction is load-bearing, because a
     * localhost address with `wss` fails the TLS handshake before anything can
     * report why.
     */
    internal fun normaliseEndpoint(serverUrl: String, roomCode: String): String {
        val trimmed = serverUrl.trim()
        if (trimmed.isBlank() || roomCode.isBlank()) return ""
        val base = when {
            trimmed.startsWith("ws://", true) || trimmed.startsWith("wss://", true) -> trimmed
            trimmed.startsWith("http://", true) -> "ws://" + trimmed.substring(7)
            trimmed.startsWith("https://", true) -> "wss://" + trimmed.substring(8)
            isLoopback(trimmed) -> "ws://$trimmed"
            else -> "wss://$trimmed"
        }.trimEnd('/')
        return "$base/room/${roomCode.trim()}"
    }

    private fun isLoopback(host: String): Boolean {
        // The bracketed IPv6 form has to be unwrapped *before* splitting on the
        // colon, because `substringBefore(':')` on `[::1]:9000` yields just `[`.
        // Missing that sent a localhost room through `wss://`, which fails the TLS
        // handshake before anything can report why.
        val bracketed = host.startsWith("[")
        val name = if (bracketed) {
            host.substringAfter('[').substringBefore(']')
        } else {
            host.substringBefore(':').substringBefore('/')
        }
        return when (name.lowercase(Locale.ROOT)) {
            "localhost", "127.0.0.1", "::1" -> true
            else -> false
        }
    }

    // -- ## SUBSECTION: Socket lifecycle ------------------------------------

    private fun open() {
        val request = Request.Builder().url(endpoint).header("User-Agent", Http.USER_AGENT).build()
        socket = Http.client.newWebSocket(request, Listener())
    }

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            _state.value = _state.value.copy(connected = true, lastError = null)
            send(
                buildJsonObject {
                    put("type", PartyWire.HELLO)
                    put("name", _state.value.selfName)
                    put("role", if (_state.value.isHost) PartyRole.HOST.name else PartyRole.GUEST.name)
                }
            )
            startClockSync()
            Log.i("joined room ${_state.value.roomCode}", tag = "party")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { handle(text) }
                .onFailure { Log.d("ignored a party message: ${it.message}", tag = "party") }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _state.value = _state.value.copy(connected = false)
            pingJob?.cancel()
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _state.value = _state.value.copy(connected = false, lastError = t.message ?: "Connection lost")
            pingJob?.cancel()
            Log.w("party socket failed: ${t.message}", tag = "party")
            scheduleReconnect()
        }
    }

    /**
     * Reconnects with exponential backoff.
     *
     * Capped at 30 seconds rather than growing without limit: a guest whose Wi-Fi
     * blips should be back in the room within half a minute, and an uncapped
     * backoff would turn a two-second dropout into a ten-minute absence.
     */
    private fun scheduleReconnect() {
        if (endpoint.isBlank()) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val waitMs = (1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
            attempt++
            delay(waitMs)
            if (isActive && endpoint.isNotBlank()) {
                Log.d("reconnecting to the room in ${waitMs}ms", tag = "party")
                open()
            }
        }
    }

    private fun startClockSync() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && _state.value.connected) {
                send(
                    buildJsonObject {
                        put("type", PartyWire.PING)
                        put("clientTime", System.currentTimeMillis())
                    }
                )
                delay(PING_INTERVAL_MS)
            }
        }
    }

    // -- ## SUBSECTION: Inbound --------------------------------------------

    private fun handle(text: String) {
        val message = Http.json.parseToJsonElement(text).jsonObject
        when (message["type"]?.jsonPrimitive?.contentOrNull) {
            PartyWire.WELCOME -> {
                _state.value = _state.value.copy(
                    roomCode = message["room"]?.stringOrNull() ?: _state.value.roomCode,
                    allowGuestControl = message["allowGuestControl"]?.jsonPrimitive?.booleanOrNull
                        ?: _state.value.allowGuestControl,
                )
            }

            PartyWire.STATE -> applyFullState(message)

            PartyWire.MEMBERS -> {
                val members = message["members"]?.jsonArray?.mapNotNull { element ->
                    runCatching {
                        val obj = element.jsonObject
                        PartyMember(
                            id = obj["id"]?.stringOrNull().orEmpty(),
                            name = obj["name"]?.stringOrNull().orEmpty(),
                            role = obj["role"]?.stringOrNull()
                                ?.let { runCatching { PartyRole.valueOf(it) }.getOrNull() }
                                ?: PartyRole.LISTENER,
                            isSelf = obj["self"]?.jsonPrimitive?.booleanOrNull ?: false,
                        )
                    }.getOrNull()
                }.orEmpty()
                if (members.isNotEmpty()) _state.value = _state.value.copy(members = members)
            }

            PartyWire.QUEUE -> {
                val queue = message["items"]?.jsonArray?.mapNotNull { element ->
                    runCatching { element.toQueueItem() }.getOrNull()
                }.orEmpty()
                _state.value = _state.value.copy(queue = queue)
            }

            PartyWire.PLAYBACK -> {
                // A host ignores inbound playback: its own player is the
                // authority, and obeying a stale echo of its own message would
                // rewind the track on every completed round trip.
                if (_state.value.isHost) return
                val playback = PartyPlayback(
                    videoId = message["videoId"]?.stringOrNull(),
                    startedAtHostMs = message["startedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    playing = message["playing"]?.jsonPrimitive?.booleanOrNull ?: false,
                    positionMsAtHost = message["position"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
                _state.value = _state.value.copy(playback = playback)
                onApplyPlayback?.invoke(playback, _state.value.clockOffsetMs)
            }

            PartyWire.PONG -> applyPong(message)

            PartyWire.ERROR -> {
                val reason = message["message"]?.stringOrNull() ?: "The room refused the request"
                _state.value = _state.value.copy(lastError = reason)
                onNotice?.invoke(reason)
            }
        }
    }

    /**
     * Folds a time reply into the clock estimate.
     *
     * The offset comes from the round trip's *midpoint*, not its end: the reply
     * was generated when the host received the ping, which is half a round trip
     * after the ping was sent. Using the arrival time instead biases the estimate
     * by the full latency and leaves every guest half a round trip behind.
     *
     * The result is a running mean rather than the latest sample, because a
     * single reply delayed by a garbage-collection pause would otherwise throw
     * the clock off by hundreds of milliseconds until the next ping corrected it.
     */
    private fun applyPong(message: JsonObject) {
        val sentAt = message["clientTime"]?.jsonPrimitive?.longOrNull ?: return
        val hostTime = message["hostTime"]?.jsonPrimitive?.longOrNull ?: return
        val receivedAt = System.currentTimeMillis()

        val roundTrip = receivedAt - sentAt
        val localAtHostReply = sentAt + roundTrip / 2
        val offset = hostTime - localAtHostReply

        val current = _state.value
        val blended = if (current.roundTripMs == 0L) offset else (current.clockOffsetMs * 3 + offset) / 4
        _state.value = current.copy(clockOffsetMs = blended, roundTripMs = roundTrip)
    }

    private fun applyFullState(message: JsonObject) {
        val allowControl = message["allowGuestControl"]?.jsonPrimitive?.booleanOrNull
        if (allowControl != null) _state.value = _state.value.copy(allowGuestControl = allowControl)

        message["queue"]?.jsonArray?.let { array ->
            val queue = array.mapNotNull { runCatching { it.toQueueItem() }.getOrNull() }
            _state.value = _state.value.copy(queue = queue)
        }

        // `state` carries a nested playback object, but a member snapshot may
        // arrive first; both are optional and handled independently so a partial
        // message never clears state it did not mention.
        message["playback"]?.jsonObjectOrNull()?.let { nested ->
            if (_state.value.isHost) return@let
            val playback = PartyPlayback(
                videoId = nested["videoId"]?.stringOrNull(),
                startedAtHostMs = nested["startedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                playing = nested["playing"]?.jsonPrimitive?.booleanOrNull ?: false,
                positionMsAtHost = nested["position"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
            _state.value = _state.value.copy(playback = playback)
            onApplyPlayback?.invoke(playback, _state.value.clockOffsetMs)
        }
    }

    // -- ## SUBSECTION: Outbound -------------------------------------------

    /**
     * Sends a message, reporting rather than throwing when the room is closed.
     *
     * A transport control must never fail loudly because the network is down:
     * pressing play with no connection should still start local playback.
     */
    private fun send(payload: JsonObject): Boolean {
        val socket = this.socket ?: return false
        val sent = runCatching { socket.send(payload.toString()) }.getOrDefault(false)
        if (!sent) Log.d("party message dropped (socket busy)", tag = "party")
        return sent
    }

    /**
     * Publishes the local playback state to the room.
     *
     * Called by the host's player on every transport change. A guest calling this
     * is ignored by the server, which is the right place to enforce it: a
     * client-side check would be trivially bypassed and would not survive a
     * protocol revision.
     */
    fun publishPlayback(playback: PartyPlayback) {
        if (!_state.value.connected) return
        _state.value = _state.value.copy(playback = playback)
        send(
            buildJsonObject {
                put("type", PartyWire.PLAYBACK)
                put("videoId", playback.videoId ?: "")
                put("startedAt", playback.startedAtHostMs)
                put("playing", playback.playing)
                put("position", playback.positionMsAtHost)
            }
        )
    }

    /** Publishes the room's queue. */
    fun publishQueue(items: List<PartyQueueItem>) {
        if (!_state.value.connected) return
        _state.value = _state.value.copy(queue = items)
        send(
            buildJsonObject {
                put("type", PartyWire.QUEUE)
                put("items", JsonArray(items.map { it.toJson() }))
            }
        )
    }

    /** Publishes the member list. */
    fun publishMembers(members: List<PartyMember>) {
        if (!_state.value.connected) return
        _state.value = _state.value.copy(members = members)
        send(
            buildJsonObject {
                put("type", PartyWire.MEMBERS)
                put(
                    "members",
                    JsonArray(
                        members.map { member ->
                            buildJsonObject {
                                put("id", member.id)
                                put("name", member.name)
                                put("role", member.role.name)
                                put("self", member.isSelf)
                            }
                        }
                    )
                )
            }
        )
    }

    /** Asks the host to do something. A host calling this acts directly instead. */
    fun request(action: String, positionMs: Long? = null, item: PartyQueueItem? = null) {
        if (_state.value.isHost) return
        if (!_state.value.allowGuestControl && action != PartyWire.ACTION_ENQUEUE) {
            onNotice?.invoke("The host is not accepting requests")
            return
        }
        send(
            buildJsonObject {
                put("type", PartyWire.REQUEST)
                put("action", action)
                put("from", _state.value.selfName)
                positionMs?.let { put("position", it) }
                item?.let { put("item", it.toJson()) }
            }
        )
    }

    /** Generates a short, readable room code. */
    fun newRoomCode(): String = UUID.randomUUID()
        .toString()
        .replace("-", "")
        // Ambiguous glyphs are excluded because a code is read aloud and typed:
        // O/0 and I/1 are the two pairs users get wrong.
        .filter { it !in "oiOI01" }
        .take(6)
        .uppercase(Locale.ROOT)

    private companion object {
        /** Frequent enough that drift never exceeds a beat, cheap enough to ignore. */
        const val PING_INTERVAL_MS = 5_000L
    }
}

// ---------------------------------------------------------------------------
// ## SECTION: JSON conveniences
// ---------------------------------------------------------------------------

/** A string, treated as absent when empty so `""` and a missing key agree. */
internal fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
    jsonPrimitive.contentOrNull?.takeIf { it.isNotEmpty() }

/** The JSON object behind an element, or null when it is not an object. */
internal fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
    runCatching { jsonObject }.getOrNull()

/** One queue entry, in the wire shape both directions share. */
private fun PartyQueueItem.toJson(): JsonObject = buildJsonObject {
    put("videoId", videoId)
    put("title", title)
    put("artist", artist)
    put("thumbnail", thumbnailUrl ?: "")
    put("duration", durationSeconds)
    put("by", addedBy)
}

/** Reads one queue entry, tolerating any missing field but the id. */
private fun kotlinx.serialization.json.JsonElement.toQueueItem(): PartyQueueItem = jsonObject.let { obj ->
    PartyQueueItem(
        videoId = obj["videoId"]?.stringOrNull().orEmpty(),
        title = obj["title"]?.stringOrNull() ?: "Unknown title",
        artist = obj["artist"]?.stringOrNull() ?: "Unknown artist",
        thumbnailUrl = obj["thumbnail"]?.stringOrNull(),
        durationSeconds = obj["duration"]?.jsonPrimitive?.intOrNull ?: 0,
        addedBy = obj["by"]?.stringOrNull().orEmpty(),
    )
}
