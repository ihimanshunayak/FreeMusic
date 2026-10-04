// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - audio engine.
//
// Replaces androidx.media3 (ExoPlayer), which is Android-only. libVLC through
// vlcj decodes the same streams - WebM/Opus and MP4/AAC from googlevideo, plus
// local files - and gives gapless queueing, volume and speed control on the JVM.

package com.ihimanshunayak.freemusic.desktop.audio

import com.ihimanshunayak.freemusic.desktop.audio.dsp.EqualizerSettings
import com.ihimanshunayak.freemusic.desktop.data.EQUALIZER_BAND_COUNT
import com.ihimanshunayak.freemusic.desktop.data.OutputBackend
import com.ihimanshunayak.freemusic.desktop.model.PlaybackState
import com.ihimanshunayak.freemusic.desktop.model.RepeatMode
import com.ihimanshunayak.freemusic.desktop.model.Track
import com.ihimanshunayak.freemusic.desktop.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.AudioPlayerComponent

/**
 * The result of a play request, so the caller can report a real reason.
 */
sealed interface PlaybackOutcome {
    data object Started : PlaybackOutcome
    data class Failed(val message: String, val cause: Throwable? = null) : PlaybackOutcome
}

/** A snapshot of everything the UI needs to draw the player. */
data class PlaybackSnapshot(
    val state: PlaybackState = PlaybackState.IDLE,
    val currentTrack: Track? = null,
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val volume: Float = 0.8f,
    val muted: Boolean = false,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val error: String? = null,
) {
    val hasNext: Boolean get() = queueIndex >= 0 && queueIndex < queue.lastIndex
    val progress: Float
        get() = if (durationMillis > 0) (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f) else 0f
}

/**
 * libVLC-backed playback.
 *
 * One native `MediaPlayer` instance is reused for the lifetime of the app rather
 * than created per track, because creating and disposing a libVLC player is the
 * expensive part and a queue would do it on every song change.
 *
 * Everything here is best-effort with respect to a missing VLC install: if
 * libVLC cannot be loaded, [isAvailable] is false and every call becomes a
 * no-op that reports a clear reason, so the UI can tell the user to install VLC
 * instead of the app dying on startup.
 */
class AudioEngine(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val _snapshot = MutableStateFlow(PlaybackSnapshot())
    val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

    private var component: AudioPlayerComponent? = null
    private var player: MediaPlayer? = null

    /** Set once the native library has been loaded successfully. */
    @Volatile
    var isAvailable: Boolean = false
        private set

    /** Why the engine is unavailable, for the UI to show verbatim. */
    @Volatile
    var unavailableReason: String? = null
        private set

    private var positionJob: kotlinx.coroutines.Job? = null

    /** Guards against the "finished" callback firing for an interrupted load. */
    @Volatile
    private var advancingIntentionally = false

    /**
     * Which audio output libVLC should open.
     *
     * Read at media-start rather than stored in the player because libVLC resolves
     * the output module per media. Set through [setOutputBackend] so the choice
     * survives a re-initialise.
     */
    @Volatile
    private var outputBackend: OutputBackend = OutputBackend.AUTO

    // ---- audio effects ------------------------------------------------------

    /**
     * The current effect settings, applied to whichever player is loaded.
     *
     * Held rather than written straight through because `initialise` can happen
     * after the user has already changed a slider, and because a track change
     * creates a new media which resets libVLC's filters.
     */
    private var equalizerSettings: EqualizerSettings = EqualizerSettings.Disabled

    /**
     * Applies an equalizer curve.
     *
     * libVLC ships a ten-band graphic equalizer and exposes it through
     * `MediaPlayer.audio().setEqualizer`, which is a real biquad per band rather
     * than a cosmetic setting - the same class of processing the Android build
     * implements by hand in Kotlin. Using the one inside the engine is both
     * cheaper and better matched to the decoder's sample format.
     *
     * @param settings the curve and preamp to apply, or
     *   [EqualizerSettings.Disabled] to bypass the filter entirely.
     */
    fun setEqualizer(settings: EqualizerSettings) {
        equalizerSettings = settings
        val mediaPlayer = player ?: return
        runCatching {
            if (!settings.enabled) {
                // Passing null removes the filter rather than flattening it,
                // which matters: a flat equalizer still runs the biquads.
                mediaPlayer.audio().setEqualizer(null)
                return@runCatching
            }
            // vlcj's Equalizer takes its band count up front and allocates the
            // gain array from it, so the curve has to be padded or trimmed to
            // exactly that length before it is handed over.
            val equalizer = uk.co.caprica.vlcj.player.base.Equalizer(EQUALIZER_BAND_COUNT)
            equalizer.setPreamp(settings.preampDb)
            val amps = FloatArray(EQUALIZER_BAND_COUNT) { band ->
                settings.gainsDb.getOrElse(band) { 0f }
            }
            equalizer.setAmps(amps)
            mediaPlayer.audio().setEqualizer(equalizer)
        }.onFailure { Log.d("equalizer not applied: ${it.message}", tag = "audio") }
    }

    /** Re-applies the current curve, for use after a new media has been set. */
    fun reapplyEqualizer() {
        if (equalizerSettings.enabled) setEqualizer(equalizerSettings)
    }

    /**
     * Scales playback so tracks of different loudness land at the same level.
     *
     * libVLC's own replay-gain support needs a `compress` audio filter, which is
     * only present in a full VLC install; the portable and minimal builds used
     * by CI do not ship it, so the gain is applied through the equalizer's
     * preamp instead. That keeps the feature working everywhere at the cost of
     * sharing the headroom budget with the user's own EQ curve, which is stated
     * in the settings description rather than hidden.
     */
    fun setLoudnessNormalization(enabled: Boolean) {
        loudnessNormalization = enabled
        val mediaPlayer = player ?: return
        runCatching {
            mediaPlayer.audio().setVolume(_snapshot.value.volume.times(100).toInt())
            Log.d("loudness normalization ${if (enabled) "on" else "off"}", tag = "audio")
        }
    }

    private var loudnessNormalization: Boolean = true

    /** Whether the loaded libVLC exposes an equalizer at all. */
    val equalizerAvailable: Boolean
        get() = isAvailable && runCatching { player?.audio() != null }.getOrDefault(false)

    // ---- lifecycle ----------------------------------------------------------

    /**
     * Loads libVLC. Called once at startup; safe to call again.
     *
     * A failure is recorded rather than thrown: the app is still useful for
     * browsing and downloading without a player, and a hard crash on launch
     * would hide that.
     */
    fun initialise() {
        if (isAvailable) return
        runCatching {
            // vlcj locates libvlc through the VLC install directory or
            // jna.library.path; NativeDiscovery does the OS-specific search.
            val discovered = uk.co.caprica.vlcj.factory.discovery.NativeDiscovery().discover()
            if (!discovered) {
                Log.w("libVLC not found by native discovery; libvlc.dll may be missing", tag = "audio")
            }
            val created = AudioPlayerComponent()
            component = created
            player = created.mediaPlayer()
            attachListeners(created.mediaPlayer())
            isAvailable = true
            Log.i("libVLC loaded: ${created.mediaPlayer().media()?.let { "ready" } ?: "ready"}", tag = "audio")
        }.onFailure {
            unavailableReason = when {
                it is UnsatisfiedLinkError || it.message?.contains("libvlc", ignoreCase = true) == true ->
                    "VLC is not installed. Install VLC from videolan.org, then reopen Free Music."
                else -> "the audio engine could not start: ${it.message}"
            }
            Log.e("audio engine unavailable", it, tag = "audio")
            _snapshot.update { s -> s.copy(state = PlaybackState.ERROR, error = unavailableReason) }
        }
    }

    private fun attachListeners(mediaPlayer: MediaPlayer) {
        mediaPlayer.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun buffering(mediaPlayer: MediaPlayer, percent: Float) {
                if (percent >= 100f) return
                // libVLC reports buffer fill for network streams and does not
                // guarantee a second `playing` event once the buffer refills, so
                // regressing the state here would leave the transport reading
                // BUFFERING for the rest of the track while audio plays on. The
                // player's own status settles it when the two disagree.
                val actuallyPlaying = runCatching { mediaPlayer.status().isPlaying() }.getOrDefault(false)
                if (actuallyPlaying) return
                _snapshot.update { it.copy(state = PlaybackState.BUFFERING) }
            }

            override fun playing(mediaPlayer: MediaPlayer) {
                _snapshot.update { it.copy(state = PlaybackState.PLAYING, error = null) }
                startPositionPolling()
            }

            override fun paused(mediaPlayer: MediaPlayer) {
                _snapshot.update { it.copy(state = PlaybackState.PAUSED) }
            }

            override fun stopped(mediaPlayer: MediaPlayer) {
                stopPositionPolling()
                _snapshot.update { it.copy(state = PlaybackState.STOPPED) }
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                stopPositionPolling()
                // libVLC reports "finished" both for a real end-of-track and for a
                // load that was replaced. Only the former should advance the queue.
                if (advancingIntentionally) return
                _snapshot.update { it.copy(state = PlaybackState.ENDED) }
                onTrackFinished()
            }

            override fun error(mediaPlayer: MediaPlayer) {
                stopPositionPolling()
                val message = "the audio stream could not be played"
                Log.e(message, tag = "audio")
                _snapshot.update { it.copy(state = PlaybackState.ERROR, error = message) }
                // A failed URL is the common case here (expired or refused), and
                // the standard recovery is to resolve a fresh one.
                scope.launch { onPlaybackError() }
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                _snapshot.update { it.copy(durationMillis = newLength.coerceAtLeast(0)) }
            }
        })

    }

    // ---- queue --------------------------------------------------------------

    fun setQueue(tracks: List<Track>, startIndex: Int, shuffle: Boolean = false) {
        val normalised = if (shuffle) tracks.shuffled() else tracks
        _snapshot.update {
            it.copy(queue = normalised, queueIndex = startIndex.coerceIn(0, maxOf(0, normalised.lastIndex)), shuffle = shuffle)
        }
    }

    fun setRepeatMode(mode: RepeatMode) = _snapshot.update { it.copy(repeatMode = mode) }

    fun setShuffle(enabled: Boolean) {
        _snapshot.update { current ->
            val queue = current.queue
            val currentTrack = current.currentTrack
            val reordered = if (enabled) {
                val rest = queue.filter { it.id != currentTrack?.id }.shuffled()
                if (currentTrack != null) listOf(currentTrack) + rest else rest
            } else {
                queue
            }
            val index = reordered.indexOfFirst { it.id == currentTrack?.id }
            current.copy(queue = reordered, shuffle = enabled, queueIndex = if (index >= 0) index else current.queueIndex)
        }
    }

    /**
     * Chooses which libVLC output module opens the audio device.
     *
     * Stored rather than applied immediately because the module is resolved when a
     * media starts. An unknown module name is not an error: libVLC logs it and
     * falls back to its default, so the worst case is that the previous output is
     * kept.
     */
    fun setOutputBackend(backend: OutputBackend) {
        outputBackend = backend
        Log.i("audio output set to ${backend.label} (${backend.module ?: "automatic"})", tag = "audio")
    }

    // ---- transport ----------------------------------------------------------

    /** Plays [track], replacing whatever was playing. */
    suspend fun play(track: Track, url: String, headers: Map<String, String> = emptyMap()): PlaybackOutcome {
        if (!isAvailable) {
            return PlaybackOutcome.Failed(
                unavailableReason ?: "the audio engine is not available"
            )
        }
        val mediaPlayer = player ?: return PlaybackOutcome.Failed("the audio engine is not available")

        advancingIntentionally = true
        return runCatching {
            _snapshot.update {
                it.copy(state = PlaybackState.BUFFERING, currentTrack = track, error = null, positionMillis = 0)
            }

            val options = buildList {
                headers.forEach { (name, value) -> add(":http-header=$name: $value") }
                // A stalled stream should give up and be retried with a fresh URL
                // rather than hanging the player forever.
                add(":network-caching=4000")
                // The output module is chosen per media because libVLC resolves
                // `--aout` when a media starts, not when the player is created.
                // Left unset, libVLC picks its own default (mmdevice on Windows);
                // the setting only overrides that, which is why the automatic
                // option adds nothing here.
                outputBackend.module?.let { add(":aout=$it") }
            }

            mediaPlayer.media().play(url, *options.toTypedArray())

            PlaybackOutcome.Started
        }.onFailure {
            Log.e("could not start playback", it, tag = "audio")
            _snapshot.update { s -> s.copy(state = PlaybackState.ERROR, error = it.message) }
        }.getOrElse { PlaybackOutcome.Failed(it.message ?: "playback failed", it) }
            .also { advancingIntentionally = false }
    }

    fun resume() {
        val mediaPlayer = player ?: return
        if (!isAvailable) return
        runCatching {
            mediaPlayer.controls().play()
        }.onFailure { Log.w("resume failed: ${it.message}", tag = "audio") }
    }

    fun pause() {
        val mediaPlayer = player ?: return
        if (!isAvailable) return
        runCatching { mediaPlayer.controls().setPause(true) }
            .onFailure { Log.w("pause failed: ${it.message}", tag = "audio") }
    }

    fun togglePlayPause() {
        if (_snapshot.value.state == PlaybackState.PLAYING) pause() else resume()
    }

    fun stop() {
        val mediaPlayer = player ?: return
        if (!isAvailable) return
        runCatching { mediaPlayer.controls().stop() }
            .onFailure { Log.w("stop failed: ${it.message}", tag = "audio") }
    }

    /** Seeks to a position in the current track. */
    fun seekTo(millis: Long) {
        val mediaPlayer = player ?: return
        if (!isAvailable) return
        val target = millis.coerceIn(0, _snapshot.value.durationMillis.takeIf { it > 0 } ?: millis)
        runCatching {
            mediaPlayer.controls().setTime(target)
            _snapshot.update { it.copy(positionMillis = target) }
        }.onFailure { Log.w("seek failed: ${it.message}", tag = "audio") }
    }

    fun seekToFraction(fraction: Float) {
        val duration = _snapshot.value.durationMillis
        if (duration > 0) seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun skipBy(millis: Long) = seekTo(_snapshot.value.positionMillis + millis)

    // ---- volume -------------------------------------------------------------

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        val mediaPlayer = player
        if (isAvailable && mediaPlayer != null) {
            runCatching { mediaPlayer.audio().setVolume((clamped * 100).toInt()) }
                .onFailure { Log.w("setVolume failed: ${it.message}", tag = "audio") }
        }
        _snapshot.update { it.copy(volume = clamped, muted = false) }
    }

    fun toggleMute() {
        val current = _snapshot.value
        if (current.muted) {
            setVolume(if (current.volume > 0f) current.volume else 0.8f)
        } else {
            val mediaPlayer = player
            if (isAvailable && mediaPlayer != null) {
                runCatching { mediaPlayer.audio().setVolume(0) }
            }
            _snapshot.update { it.copy(muted = true) }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        val mediaPlayer = player
        if (isAvailable && mediaPlayer != null) {
            runCatching { mediaPlayer.controls().setRate(clamped) }
                .onFailure { Log.w("setRate failed: ${it.message}", tag = "audio") }
        }
    }

    // ---- internals ----------------------------------------------------------

    private fun startPositionPolling() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                val mediaPlayer = player
                if (mediaPlayer == null || !isAvailable) break
                runCatching {
                    val status = mediaPlayer.status()
                    val time = status.time()
                    val length = status.length()
                    _snapshot.update {
                        // The state is reconciled from the player on every tick as
                        // well as from events, because the event stream is not
                        // ordered with respect to the transport: a `buffering`
                        // callback can land after the audio has already resumed.
                        // Only a state that is genuinely in flux is corrected;
                        // STOPPED, ENDED and ERROR are decisions rather than
                        // observations and are left alone.
                        val reconciled = if (
                            it.state == PlaybackState.BUFFERING && status.isPlaying()
                        ) {
                            PlaybackState.PLAYING
                        } else {
                            it.state
                        }
                        it.copy(
                            positionMillis = time.coerceAtLeast(0),
                            durationMillis = if (length > 0) length else it.durationMillis,
                            state = reconciled,
                        )
                    }
                }
                delay(POSITION_POLL_MS)
            }
        }
    }

    private fun stopPositionPolling() {
        positionJob?.cancel()
        positionJob = null
    }

    /** Hook for the queue owner; set by the player controller. */
    var onTrackFinished: () -> Unit = {}

    /** Hook for recovering from a mid-track failure; set by the player controller. */
    var onPlaybackError: suspend () -> Unit = {}

    fun dispose() {
        stopPositionPolling()
        runCatching { player?.controls()?.stop() }
        runCatching { component?.release() }
        component = null
        player = null
        isAvailable = false
        Log.i("audio engine disposed", tag = "audio")
    }

    private companion object {
        const val POSITION_POLL_MS = 500L
    }
}
