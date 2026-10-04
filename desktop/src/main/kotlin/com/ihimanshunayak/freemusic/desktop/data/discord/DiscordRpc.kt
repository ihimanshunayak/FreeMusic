// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - Discord Rich Presence.
//
// NAME
//     DiscordRpc.kt - the IPC protocol that puts "Listening to ..." in a profile.
//
// DESCRIPTION
//     Discord's Rich Presence is not a web API. It is a local IPC protocol: the
//     desktop client listens on a socket whose name is `discord-ipc-{0..9}`, and
//     an application connects, performs a handshake, and sends framed JSON. That
//     is why this cannot be done with an HTTP call, and why it is written out
//     here rather than through a library - the protocol is a couple of hundred
//     lines, and every library adds either a background process or a native
//     dependency that a music player should not need.
//
//     The frame format is: a 32-bit little-endian opcode, a 32-bit little-endian
//     payload length, then that many bytes of UTF-8 JSON. Opcode 0 is a
//     handshake, 1 a normal frame, 2 a close, 3 a ping from Discord that must be
//     answered with opcode 4.
//
// RESPONSIBILITIES
//     - Find and connect to a running Discord client.
//     - Handshake, answer pings, and keep the connection alive.
//     - Push activity updates and clear them on stop.
//     - Reconnect after Discord restarts.
//
// DEPENDENCIES
//     Java NIO's Unix-domain socket support, which covers Windows named pipes.
//
// INTEGRATION NOTES
//     - Discord being closed is the normal case, not an error. Every path here
//       returns quietly and the presence simply does not appear.
//     - The reconnect loop backs off, so a machine without Discord installed is
//       not polled constantly.

package com.ihimanshunayak.freemusic.desktop.data.discord

import com.ihimanshunayak.freemusic.desktop.model.Track
import com.ihimanshunayak.freemusic.desktop.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Paths

/** The IPC opcodes Discord defines. */
private object Opcode {
    const val HANDSHAKE = 0
    const val FRAME = 1
    const val CLOSE = 2
    const val PING = 3
    const val PONG = 4
}

/** What the presence should say. */
data class DiscordActivity(
    val details: String,
    val state: String,
    val largeImageUrl: String? = null,
    val largeText: String? = null,
    /** Milliseconds since the epoch when the track started, for the elapsed timer. */
    val startedAtMs: Long? = null,
)

/**
 * A Discord IPC connection.
 *
 * One connection at a time; [connect] closes whatever came before, so a
 * reconnect after Discord restarts cannot leak the old socket.
 */
internal class DiscordIpcClient(private val applicationId: String) {

    private var channel: SocketChannel? = null
    private var reader: Job? = null

    val connected: Boolean get() = channel?.isOpen == true

    /**
     * Connects to a Discord client and performs the handshake.
     *
     * Pipes 0 through 9 are all tried, because which one Discord listens on
     * depends on how many instances have run - the normal client takes 0, but a
     * development or Canary build takes the next free number, and a user running
     * more than one needs the app to find either.
     */
    fun connect(scope: CoroutineScope): Boolean {
        disconnect()
        for (index in 0..9) {
            val opened = runCatching { openPipe(index) }.getOrNull() ?: continue
            channel = opened
            runCatching { writeFrame(opened, Opcode.HANDSHAKE, """{"v":1,"client_id":"$applicationId"}""") }
            reader = scope.launch(Dispatchers.IO) { readLoop(opened) }
            Log.i("Discord connected on pipe $index", tag = "discord")
            return true
        }
        return false
    }

    /**
     * Opens one Discord pipe.
     *
     * On Windows a Discord pipe is a named pipe, which NIO addresses through a
     * `UNIX` protocol socket whose path is the pipe's own; on Linux and macOS it
     * is a real unix socket under the runtime directory.
     */
    private fun openPipe(index: Int): SocketChannel? {
        val path = discordPipePath(index) ?: return null
        val address = java.net.UnixDomainSocketAddress.of(path)
        val opened = SocketChannel.open(StandardProtocolFamily.UNIX)
        opened.configureBlocking(true)
        return try {
            opened.connect(address)
            opened
        } catch (failure: Exception) {
            runCatching { opened.close() }
            null
        }
    }

    private fun discordPipePath(index: Int): String? {
        val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val name = "discord-ipc-$index"
        return if (isWindows) {
            // Windows named pipes are opened through this prefix. A path with a
            // forward slash instead of a backslash is silently treated as a
            // relative file name and fails, so the separator matters.
            "\\\\?\\pipe\\$name"
        } else {
            val base = System.getenv("XDG_RUNTIME_DIR")
                ?: System.getenv("TMPDIR")
                ?: System.getenv("TMP")
                ?: System.getenv("TEMP")
                ?: "/tmp"
            Paths.get(base, name).toString()
        }
    }

    /** Sends an activity update, replacing whatever was set before. */
    fun setActivity(activity: DiscordActivity) {
        val target = channel ?: return
        runCatching { writeFrame(target, Opcode.FRAME, envelop(buildActivity(activity))) }
            .onFailure { Log.d("Discord activity failed: ${it.message}", tag = "discord") }
    }

    /** Clears the presence, which is what a stopped player should leave behind. */
    fun clearActivity() {
        val target = channel ?: return
        runCatching { writeFrame(target, Opcode.FRAME, envelop("null")) }
            .onFailure { Log.d("Discord clear failed: ${it.message}", tag = "discord") }
    }

    fun disconnect() {
        reader?.cancel()
        reader = null
        channel?.let { open ->
            runCatching {
                writeFrame(open, Opcode.CLOSE, "{}")
                open.close()
            }
        }
        channel = null
    }

    /**
     * Reads frames until the socket closes.
     *
     * Discord sends a ping that must be answered, and closes the socket when the
     * user quits. Both cases end the loop; the caller's reconnect logic decides
     * what happens next.
     */
    private suspend fun readLoop(target: SocketChannel) = withContext(Dispatchers.IO) {
        runCatching {
            val input: InputStream = Channels.newInputStream(target)
            while (!Thread.currentThread().isInterrupted && target.isOpen) {
                val header = readExactly(input, 8) ?: break
                val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val opcode = buffer.int
                val length = buffer.int
                if (length < 0 || length > MAX_FRAME_BYTES) break

                val payload = readExactly(input, length) ?: break

                if (opcode == Opcode.PING) {
                    runCatching {
                        writeFrame(target, Opcode.PONG, payload.toString(StandardCharsets.UTF_8))
                    }
                }
            }
        }.onFailure { Log.d("Discord read loop ended: ${it.message}", tag = "discord") }

        channel = null
        Log.d("Discord disconnected", tag = "discord")
    }

    /** Reads exactly [length] bytes, or null at end of stream. */
    private fun readExactly(input: InputStream, length: Int): ByteArray? {
        if (length == 0) return ByteArray(0)
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(buffer, read, length - read)
            if (count < 0) return null
            read += count
        }
        return buffer
    }

    /** Wraps an activity object in Discord's SET_ACTIVITY command envelope. */
    private fun envelop(activityJson: String): String =
        """{"cmd":"SET_ACTIVITY","args":{"pid":${ProcessHandle.current().pid()},"activity":$activityJson},"nonce":"${java.util.UUID.randomUUID()}"}"""

    /**
     * Writes one length-prefixed frame.
     *
     * Both fields are little-endian and the length is the payload's *byte* count,
     * not its character count: a track title with a non-ASCII character would
     * otherwise be truncated mid-character.
     */
    private fun writeFrame(target: SocketChannel, opcode: Int, payload: String) {
        val body = payload.toByteArray(StandardCharsets.UTF_8)
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(opcode)
            .putInt(body.size)
            .array()

        val output: OutputStream = Channels.newOutputStream(target)
        output.write(header)
        output.write(body)
        output.flush()
    }

    /** Builds the `activity` object from a [DiscordActivity]. */
    private fun buildActivity(activity: DiscordActivity): String = buildString {
        append("""{"details":""").append(quote(activity.details))
        append(""","state":""").append(quote(activity.state))
        // Type 2 is "Listening to", which is what a music player is.
        append(""","type":2""")
        activity.startedAtMs?.let { append(""","timestamps":{"start":$it}""") }
        activity.largeImageUrl?.let { url ->
            append(""","assets":{"large_image":""").append(quote(url)).append('"')
            activity.largeText?.let { append(""","large_text":""").append(quote(it)) }
            append('}')
        }
        append('}')
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n', '\r', '\t' -> append(' ')
                else -> if (character.code < 0x20) append(' ') else append(character)
            }
        }
        append('"')
    }

    private companion object {
        /** Discord closes the connection rather than accepting a hostile length. */
        const val MAX_FRAME_BYTES = 1 shl 20
    }
}

/**
 * Publishes what is playing to Discord.
 *
 * Owns the whole lifecycle: finding Discord, keeping the connection, and pushing
 * updates at a rate Discord tolerates. Discord's documented limit is one activity
 * update per fifteen seconds, and ignoring it gets the application rate-limited -
 * so updates are coalesced here rather than sent per position tick.
 */
class DiscordPresence(
    private val applicationId: String,
    private val scope: CoroutineScope,
) {
    private val client = DiscordIpcClient(applicationId)
    private val mutex = Mutex()
    private var reconnectJob: Job? = null
    private var lastUpdateAt = 0L
    private var pending: DiscordActivity? = null

    private var enabled = false
    private var showQuality = true
    private var audioQualityLine: String? = null

    /** Whether a Discord client was found, for the settings screen to report. */
    val connected: Boolean get() = client.connected

    /**
     * Turns presence on or off.
     *
     * Off closes the connection outright rather than leaving it idle, so a user
     * who disabled the feature is not left with a socket to Discord they did not
     * ask for.
     */
    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) {
            ensureConnected()
        } else {
            reconnectJob?.cancel()
            reconnectJob = null
            client.clearActivity()
            client.disconnect()
        }
    }

    /** Sets the line appended to the artist, such as the codec and bitrate. */
    fun setQualityLine(line: String?) {
        audioQualityLine = line
    }

    fun setShowQuality(value: Boolean) {
        showQuality = value
    }

    /** Publishes a track, or clears the presence when given null. */
    fun publish(track: Track?, startedAtMs: Long?) {
        if (!enabled) return
        if (track == null) {
            client.clearActivity()
            return
        }

        val state = buildString {
            append(track.artist.ifBlank { "Unknown artist" })
            if (showQuality) {
                audioQualityLine?.takeIf { it.isNotBlank() }?.let { append(" - ").append(it) }
            }
        }

        update(
            DiscordActivity(
                details = track.title,
                state = state,
                largeImageUrl = track.thumbnailUrl,
                largeText = track.album?.takeIf { it.isNotBlank() } ?: track.title,
                startedAtMs = startedAtMs,
            )
        )
    }

    /**
     * Sends an update, respecting Discord's rate limit.
     *
     * When an update arrives too soon after the last one it is held and sent on
     * a timer instead of dropped: the point of the limit is to reduce traffic,
     * not to lose the most recent state.
     */
    private fun update(activity: DiscordActivity) {
        scope.launch {
            mutex.withLock {
                val now = System.currentTimeMillis()
                val since = now - lastUpdateAt
                if (since < MIN_UPDATE_INTERVAL_MS) {
                    pending = activity
                    delay(MIN_UPDATE_INTERVAL_MS - since)
                    val queued = pending ?: return@withLock
                    pending = null
                    client.setActivity(queued)
                    lastUpdateAt = System.currentTimeMillis()
                } else {
                    client.setActivity(activity)
                    lastUpdateAt = now
                }
            }
        }
    }

    /**
     * Keeps a connection alive, retrying on a slow schedule.
     *
     * The retry interval is long because the common case for a failed connection
     * is "the user does not run Discord", and probing a named pipe every second
     * forever would be a pointless waste on every machine without it.
     */
    private fun ensureConnected() {
        if (client.connected) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            while (isActive && enabled) {
                if (client.connect(this)) return@launch
                delay(RECONNECT_INTERVAL_MS)
            }
        }
    }

    fun close() {
        enabled = false
        reconnectJob?.cancel()
        reconnectJob = null
        client.clearActivity()
        client.disconnect()
    }

    private companion object {
        /** Discord's documented ceiling: one activity update per fifteen seconds. */
        const val MIN_UPDATE_INTERVAL_MS = 15_000L

        /** Long enough that a machine without Discord is not polled constantly. */
        const val RECONNECT_INTERVAL_MS = 30_000L
    }
}

/**
 * Builds the presence's second line: the codec and bitrate currently playing.
 *
 * Kept separate from the presence itself so the player can compute it once per
 * track rather than on every position tick.
 */
fun discordQualityLine(codec: String?, kbps: Int?): String? {
    if (codec.isNullOrBlank()) return null
    val label = when {
        codec.contains("flac", ignoreCase = true) -> "FLAC"
        codec.contains("alac", ignoreCase = true) -> "ALAC"
        codec.contains("opus", ignoreCase = true) -> "Opus"
        codec.contains("mp4a", ignoreCase = true) || codec.contains("aac", ignoreCase = true) -> "AAC"
        codec.contains("mp3", ignoreCase = true) -> "MP3"
        else -> codec
    }
    return if (kbps != null && kbps > 0) "$label ${kbps}kbps" else label
}
