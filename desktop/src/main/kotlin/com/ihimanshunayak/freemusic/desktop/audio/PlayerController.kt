// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - player controller.
//
// Owns the queue and the rules for what plays next, and is the only thing that
// ties the audio engine to the stream resolver. Every recovery path lives here:
// an expired URL, a refused URL and a track that simply ended all look the same
// from the engine's side, and this is where they are told apart.

package com.ihimanshunayak.freemusic.desktop.audio

import com.ihimanshunayak.freemusic.desktop.data.OutputBackend
import com.ihimanshunayak.freemusic.desktop.data.stream.ResolvedStream
import com.ihimanshunayak.freemusic.desktop.data.stream.StreamResolver
import com.ihimanshunayak.freemusic.desktop.model.PlaybackState
import com.ihimanshunayak.freemusic.desktop.model.RepeatMode
import com.ihimanshunayak.freemusic.desktop.model.SourceKind
import com.ihimanshunayak.freemusic.desktop.model.Track
import com.ihimanshunayak.freemusic.desktop.util.Log
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

/**
 * Turns user intent ("play this", "next", "shuffle") into resolved URLs and
 * engine calls, and keeps the UI's snapshot truthful while it happens.
 */
class PlayerController(
    private val engine: AudioEngine,
    private val resolver: StreamResolver,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val transition = Mutex()

    /**
     * How many times the current track may be re-resolved before the controller
     * gives up on it. googlevideo refuses URLs for two reasons - expiry (fixable
     * by re-resolving) and a genuine block (not fixable) - and this is what stops
     * a blocked track from looping forever.
     */
    private var recoveryAttempts = 0

    init {
        engine.onTrackFinished = { scope.launch { advance(automatic = true) } }
        engine.onPlaybackError = { scope.launch { recoverFromError() } }
    }

    /** Everything the UI binds to, in the app's own vocabulary. */
    data class PlayerState(
        val playback: PlaybackSnapshot = PlaybackSnapshot(),
        val isResolving: Boolean = false,
    )

    val snapshot: StateFlow<PlaybackSnapshot> get() = _snapshotExposed

    private val _snapshotExposed: StateFlow<PlaybackSnapshot> = engine.snapshot

    // ---- queue --------------------------------------------------------------

    /** Replaces the queue and starts at [startIndex]. */
    fun playAll(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        engine.setShuffle(shuffle)
        val ordered = if (shuffle) tracks.shuffled() else tracks
        val index = if (shuffle) {
            // Shuffling must not lose the track the user actually clicked.
            val clicked = tracks.getOrNull(startIndex)
            val rest = ordered.filter { it.id != clicked?.id }
            val reordered = if (clicked != null) listOf(clicked) + rest else ordered
            engine.setQueue(reordered, 0, shuffle = false)
            0
        } else {
            engine.setQueue(ordered, startIndex, shuffle = false)
            startIndex
        }
        scope.launch { playIndex(index) }
    }

    fun playSingle(track: Track) = playAll(listOf(track), 0)

    /** Appends without interrupting playback. */
    fun enqueue(track: Track) {
        engine.setQueue(engine.snapshot.value.queue + track, engine.snapshot.value.queueIndex)
    }

    /** Inserts directly after the current track, which is what "play next" means. */
    fun playNext(track: Track) {
        val current = engine.snapshot.value
        val insertAt = (current.queueIndex + 1).coerceIn(0, current.queue.size)
        val queue = current.queue.toMutableList().apply { add(insertAt, track) }
        engine.setQueue(queue, current.queueIndex)
    }

    fun removeFromQueue(trackId: String) {
        val current = engine.snapshot.value
        val index = current.queue.indexOfFirst { it.id == trackId }
        if (index < 0) return
        val queue = current.queue.toMutableList().apply { removeAt(index) }
        val newIndex = when {
            index < current.queueIndex -> current.queueIndex - 1
            index == current.queueIndex -> current.queueIndex
            else -> current.queueIndex
        }
        engine.setQueue(queue, newIndex, current.shuffle)
        if (index == current.queueIndex && queue.isNotEmpty()) {
            scope.launch { playIndex(newIndex.coerceIn(0, queue.lastIndex)) }
        }
    }

    fun clearQueue() {
        engine.stop()
        engine.setQueue(emptyList(), -1)
    }

    // ---- transport ----------------------------------------------------------

    fun togglePlayPause() {
        val state = engine.snapshot.value.state
        when (state) {
            // Something is loaded but never started - the queue index was set
            // before an error, so start it rather than doing nothing.
            PlaybackState.IDLE, PlaybackState.STOPPED, PlaybackState.ENDED, PlaybackState.ERROR -> {
                val index = engine.snapshot.value.queueIndex
                if (index >= 0) scope.launch { playIndex(index) }
            }
            else -> engine.togglePlayPause()
        }
    }

    fun next() = scope.launch { advance(automatic = false) }

    fun previous() {
        // Conventional behaviour: a press within the first few seconds goes back
        // a track, later it restarts the current one.
        val snap = engine.snapshot.value
        if (snap.positionMillis > 4_000) {
            engine.seekTo(0)
            return
        }
        scope.launch { advance(automatic = false, backwards = true) }
    }

    fun seekToFraction(fraction: Float) = engine.seekToFraction(fraction)
    fun skipBy(millis: Long) = engine.skipBy(millis)
    fun setVolume(volume: Float) = engine.setVolume(volume)
    fun toggleMute() = engine.toggleMute()
    fun setPlaybackSpeed(speed: Float) = engine.setPlaybackSpeed(speed)
    fun setRepeatMode(mode: RepeatMode) = engine.setRepeatMode(mode)
    fun setShuffle(enabled: Boolean) = engine.setShuffle(enabled)
    fun stop() = engine.stop()

    /**
     * Chooses the audio output module.
     *
     * Takes effect on the next media load, because libVLC resolves its output
     * when a media starts. That is also why this is not a hard requirement: an
     * unsupported module name makes libVLC fall back to its own default, so a
     * wrong choice costs the user nothing but the setting not applying.
     */
    fun setOutputBackend(backend: OutputBackend) = engine.setOutputBackend(backend)

    /**
     * Stops playback but keeps the queue and the current position.
     *
     * Distinct from [stop], which clears what is loaded. The sleep timer pauses
     * rather than stops: the user fell asleep mid-album, and they expect the
     * album to still be there - at the point it stopped - when they wake up.
     */
    fun pause() = engine.pause()

    // ---- the interesting part ----------------------------------------------

    private suspend fun advance(automatic: Boolean, backwards: Boolean = false) {
        transition.withLock {
            val snap = engine.snapshot.value
            if (snap.queue.isEmpty()) return

            when (snap.repeatMode) {
                RepeatMode.ONE -> {
                    if (automatic) {
                        val index = snap.queueIndex.coerceAtLeast(0)
                        playIndex(index, forceRefresh = false)
                    } else {
                        nextIndex(snap, backwards)?.let { playIndex(it, forceRefresh = false) }
                    }
                }
                RepeatMode.ALL -> playIndex(nextIndexWrapping(snap, backwards))
                RepeatMode.OFF -> {
                    val target = nextIndex(snap, backwards)
                    if (target == null) {
                        engine.stop()
                        Log.d("queue finished", tag = "player")
                    } else {
                        playIndex(target)
                    }
                }
            }
        }
    }

    private fun nextIndex(snap: PlaybackSnapshot, backwards: Boolean): Int? {
        val result = if (backwards) snap.queueIndex - 1 else snap.queueIndex + 1
        return result.takeIf { it in snap.queue.indices }
    }

    private fun nextIndexWrapping(snap: PlaybackSnapshot, backwards: Boolean): Int {
        val size = snap.queue.size
        val result = if (backwards) snap.queueIndex - 1 else snap.queueIndex + 1
        return ((result % size) + size) % size
    }

    /** Resolves and starts the track at [index]. */
    private suspend fun playIndex(index: Int, forceRefresh: Boolean = false) {
        val snap = engine.snapshot.value
        val track = snap.queue.getOrNull(index) ?: return

        engine.setQueue(snap.queue, index, snap.shuffle)

        // A local file needs no resolution; go straight to the engine.
        if (track.source == SourceKind.LOCAL_FILE) {
            val path = track.localPath
            if (path == null) {
                Log.w("local track ${track.title} has no path", tag = "player")
                return
            }
            engine.play(track, path)
            return
        }

        val videoId = track.videoId
        if (videoId == null) {
            Log.w("track ${track.title} has no video id", tag = "player")
            return
        }

        _state.update { it.copy(isResolving = true) }
        val outcome = runCatching { resolver.resolve(videoId, forceRefresh = forceRefresh) }
            .onFailure { Log.w("resolve failed for $videoId: ${it.message}", tag = "player") }
        _state.update { it.copy(isResolving = false) }

        val stream = outcome.getOrNull()
        if (stream == null) {
            val message = "could not find an audio stream for ${track.title}"
            Log.e(message, tag = "player")
            return
        }

        when (val result = engine.play(track, stream.url, stream.headers)) {
            is PlaybackOutcome.Started -> {
                recoveryAttempts = 0
                Log.i("playing ${track.title} - ${track.artist}", tag = "player")
            }
            is PlaybackOutcome.Failed -> {
                Log.e("playback failed for ${track.title}: ${result.message}", tag = "player")
            }
        }
    }

    /**
     * A URL stopped working mid-track.
     *
     * The overwhelmingly common cause is expiry, so the first response is to
     * resolve a fresh URL for the same track. If that fails repeatedly the track
     * is genuinely unavailable and is skipped, which is better than stalling the
     * queue on it.
     */
    private suspend fun recoverFromError() {
        transition.withLock {
            val snap = engine.snapshot.value
            val track = snap.currentTrack ?: return
            val videoId = track.videoId ?: return

            recoveryAttempts++
            if (recoveryAttempts > MAX_RECOVERY_ATTEMPTS) {
                Log.w("giving up on ${track.title} after $MAX_RECOVERY_ATTEMPTS attempts", tag = "player")
                recoveryAttempts = 0
                // Move on, but without letting a broken track end the session.
                val next = nextIndex(snap, backwards = false)
                if (next != null) playIndex(next)
                return
            }

            Log.i("re-resolving ${track.title} (attempt $recoveryAttempts)", tag = "player")
            val fresh: ResolvedStream? = runCatching { resolver.resolve(videoId, forceRefresh = true) }
                .getOrNull()
            if (fresh == null) {
                Log.w("re-resolve failed for ${track.title}", tag = "player")
                return
            }
            engine.play(track, fresh.url, fresh.headers)
        }
    }

    /** Injected so tests can drive finishing without a real engine. */
    fun simulateTrackFinished() = scope.launch { advance(automatic = true) }

    fun dispose() {
        engine.dispose()
        // Mutex unlock is owner-restricted; only release it if this thread
        // actually holds it, otherwise shutdown throws instead of cleaning up.
        if (transition.isLocked) runCatching { transition.unlock() }
    }

    private companion object {
        const val MAX_RECOVERY_ATTEMPTS = 3
    }
}

/** Convenience for creating an engine + controller pair with the standard wiring. */
fun createPlayer(
    resolver: StreamResolver,
    scope: CoroutineScope? = null,
): PlayerController {
    val engine = AudioEngine(scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default))
    engine.initialise()
    return PlayerController(engine, resolver, scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default))
}
