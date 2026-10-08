package com.ihimanshunayak.freemusic.playback.haptics

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.annotation.RequiresApi
import com.ihimanshunayak.freemusic.data.settings.AppSettings
import com.ihimanshunayak.freemusic.data.settings.MusicHapticsMode
import com.ihimanshunayak.freemusic.playback.smart.TrackAnalysis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays a track's vibration on the device motor, in time with the music.
 *
 * **What this is.** Apple's Music Haptics: the phone taps along with the song.
 * The taps are not a metronome laid over the top — they are derived from the
 * track's own beat grid and its low-band energy, so a kick drum is a firm
 * strike, a bar line is a firmer one, and a quiet passage is quieter under the
 * hand. [HapticScore] derives all of that from the stored analysis;
 * [HapticWaveform] turns it into amplitudes; this class is the part that has to
 * know what time it is and talk to hardware.
 *
 * **Why the analysis is reused rather than measured.** See [HapticScore]. The
 * short version: the app already decodes every track offline for Automix, and
 * that pass already produces exactly the two things vibration needs. Tapping
 * the live DSP chain instead would mean a second decode of audio that has
 * already been decoded, to answer a question that has already been answered.
 *
 * **The honest limitation.** Android gives an app no way to schedule vibration
 * sample-accurately in the future. An effect is submitted complete and the
 * motor renders it; there is no callback on the beat and no clock shared with
 * the audio path. So this is a *schedule-ahead approximation*: a coroutine
 * watches the playhead several times a second and submits short chunks of
 * pattern a fraction of a second before they are due. Strikes land on the beat
 * to within the accuracy of that loop — a few milliseconds in practice — rather
 * than to the microsecond Core Haptics manages on Apple's Taptic Engine. It
 * feels like the music; it is not sample-locked to it, and this class does not
 * pretend otherwise.
 *
 * **What it costs.** The motor runs for the whole of every track it is enabled
 * on, which is the single largest battery cost in the app. That is why the
 * feature is off by default and why [MusicHapticsMode] has an explicit [OFF],
 * rather than being a switch that is easy to leave on by accident.
 */
class MusicHaptics(
    context: Context,
    private val scope: CoroutineScope,
    /** Where the music is right now, in milliseconds, or null when nothing is playing. */
    private val positionMs: () -> Long?,
    /** Whether playback is actually advancing, so a paused player does not get tapped along with. */
    private val isPlaying: () -> Boolean,
    /**
     * What is known about the track playing right now.
     *
     * Polled rather than pushed because analysis arrives whenever it arrives —
     * seconds after the track starts, or never for one that cannot be decoded —
     * and the alternative is a second set of callbacks threaded through the
     * analyze path purely to tell this class something that is already stored.
     * Must be a cheap lookup and never a computation or a disk read: see
     * [com.ihimanshunayak.freemusic.playback.smart.TrackAnalyzer.analysisFor],
     * which is written to be exactly that.
     */
    private val analysisFor: () -> TrackAnalysis? = { null },
) {
    private val device = Motor.of(context.applicationContext)

    /** True when this phone has a motor worth driving. False disables the feature entirely. */
    val isSupported: Boolean get() = device != null

    private val _score = MutableStateFlow(HapticScore.EMPTY)

    /** The score currently loaded, for callers that want to show progress or diagnostics. */
    val score: StateFlow<HapticScore> = _score.asStateFlow()

    private var ticker: Job? = null
    private var settingsWatch: Job? = null
    private var scoreRefresh: Job? = null

    /**
     * The instant the playhead was last known, and where it was, so the audible
     * position can be extrapolated between polls.
     *
     * `currentPosition` is a binder call into the player and asking for it
     * several times a second for an hour is not free; more importantly it is
     * quantised to the player's own update cadence, which is coarser than the
     * schedule-ahead window this class needs. Reading it occasionally and
     * advancing [anchorPositionMs] by [SystemClock] in between gives a smoother
     * and cheaper estimate of "where is the music right now" than polling it.
     */
    private var anchorElapsedMs = 0L
    private var anchorPositionMs = 0L

    /**
     * Absolute track time the last submitted chunk was built to cover, so the
     * next one resumes exactly where it left off instead of re-deriving it from
     * the (quantised) playhead and drifting.
     */
    private var emittedThroughMs = 0L

    /** Which track [score] describes, so a stale score is not played over a new song. */
    private var loadedTrackId: String? = null

    /**
     * Starts following playback. Safe to call more than once.
     *
     * The engine owns its own ticker rather than hanging off the crossfade
     * controller's: that controller only ticks while a transition is being
     * considered, and only exists at all when crossfading is available. Haptics
     * has to run for every track regardless of whether the next one is being
     * mixed into it.
     */
    fun start() {
        if (device == null) return
        if (ticker?.isActive == true) return

        // The mode can change while the app is open — the settings sheet writes
        // it directly — so the ticker follows the flow rather than being
        // started and stopped by whoever owns the UI. collectLatest cancels the
        // previous tick loop on every change, which is what makes turning the
        // feature off stop the motor immediately rather than at the next
        // track boundary.
        settingsWatch = scope.launch {
            AppSettings.musicHapticsMode.collectLatest { mode ->
                if (mode == MusicHapticsMode.OFF) {
                    stopVibrating()
                    return@collectLatest
                }
                runTicker(mode)
            }
        }
    }

    /**
     * Starts watching for the current track's analysis, picking it up whenever
     * it lands.
     *
     * Called when a new track becomes current, not once at startup: the whole
     * point is that analysis trails the track by seconds, so no single moment
     * exists at which it is known to be ready. This polls for it instead, which
     * costs a map lookup a few times a second until it arrives and nothing at
     * all before that.
     */
    fun followCurrentTrack(trackId: String?) {
        if (device == null) return
        // Starting the ticker here rather than only at service creation means
        // the engine comes up on the same path that gives it something to play,
        // so there is no window in which it is running with no track and no
        // window in which it has a track but is not running.
        start()
        scoreRefresh?.cancel()
        if (trackId.isNullOrBlank()) {
            clear()
            return
        }

        // A result may already be in hand — a restored one, or a track played
        // earlier in the session — in which case there is nothing to wait for.
        adoptIfReady(trackId)
        if (loadedTrackId == trackId) return

        scoreRefresh = scope.launch {
            while (isActive) {
                delay(ANALYSIS_POLL_MS)
                if (adoptIfReady(trackId)) return@launch
            }
        }
    }

    /** Loads [trackId]'s analysis if one is ready. True when it was adopted. */
    private fun adoptIfReady(trackId: String): Boolean {
        if (loadedTrackId == trackId) return true
        val analysis = analysisFor() ?: return false
        if (analysis.trackId.isBlank() || analysis.trackId != trackId) return false
        if (!analysis.isUsable) return false
        // Deriving here rather than handing the analysis to a coroutine keeps
        // the poll loop's own termination condition honest: it returns only
        // once the score is actually in place. The derivation is bounded work
        // over a few hundred beats, and the alternative — signalling out of the
        // loop and racing the load — would leave [loadedTrackId] briefly
        // claiming a track whose score has not arrived.
        val derived = HapticScore.of(analysis)
        if (derived.isEmpty) {
            // Usable analysis but no grid worth vibrating: recorded so the poll
            // stops asking, because the answer will not change.
            loadedTrackId = trackId
            _score.value = HapticScore.EMPTY
            Log.d(TAG, "No vibration for $trackId (confidence=${analysis.beatConfidence})")
            return true
        }
        loadedTrackId = trackId
        _score.value = derived
        emittedThroughMs = 0L
        resetAnchor()
        Log.d(
            TAG,
            "Loaded ${derived.pulses.size} pulses for $trackId " +
                "(confidence=${analysis.beatConfidence})",
        )
        return true
    }

    /** Forgets the current track, e.g. because it changed into something unanalysed. */
    fun clear() {
        loadedTrackId = null
        _score.value = HapticScore.EMPTY
        emittedThroughMs = 0L
        stopVibrating()
    }

    /** Releases the ticker and stops the motor. Safe to call more than once. */
    fun release() {
        settingsWatch?.cancel()
        settingsWatch = null
        scoreRefresh?.cancel()
        scoreRefresh = null
        ticker?.cancel()
        ticker = null
        loadedTrackId = null
        _score.value = HapticScore.EMPTY
        stopVibrating()
    }

    /**
     * Called when the playhead jumps — a seek, a skip, a track restart.
     *
     * The schedule-ahead window is meaningless across a discontinuity: a chunk
     * built for the old position would be submitted and felt as the motor
     * running through a pattern that belongs to different music. Dropping the
     * through-marker makes the next tick rebuild from wherever the listener
     * actually is.
     */
    fun onPositionDiscontinuity() {
        emittedThroughMs = 0L
        resetAnchor()
        stopVibrating()
    }

    private suspend fun runTicker(mode: MusicHapticsMode) {
        try {
            while (scope.isActive) {
                tick(mode)
                delay(TICK_MS)
            }
        } finally {
            // Reached when the mode changes, when the scope is cancelled, or on
            // release — and the motor must not be left running in any of them.
            stopVibrating()
            ticker = null
        }
    }

    private fun tick(mode: MusicHapticsMode) {
        val current = positionMs()
        if (current == null) {
            // Nothing to follow. Drop the through-marker so playback resuming
            // does not make the next tick try to cover the gap it was paused for.
            emittedThroughMs = 0L
            stopVibrating()
            return
        }
        if (!isPlaying()) {
            emittedThroughMs = 0L
            stopVibrating()
            return
        }

        syncAnchor(current)
        val score = _score.value
        if (score.isEmpty) return

        val now = audiblePositionMs()
        // A submitted effect is replaced by the next one, so nothing may be
        // submitted while the motor is still working through the last chunk.
        // Without this gate the tick would replace its own effect every 45ms —
        // and because every chunk opens with its lead as silence, the motor
        // would be reset to silence long before any pattern had a chance to
        // play. The margin is small so that a refill happens just as the
        // previous effect runs out: it is the amount of the previous chunk
        // allowed to be cut, and that cut falls on its trailing silence.
        val outstanding = emittedThroughMs - now
        if (emittedThroughMs > 0L && outstanding > REFILL_AHEAD_MS) return

        val from = emittedThroughMs.coerceAtLeast(now)
        val to = from + LOOKAHEAD_MS

        // Lead is how far ahead of the music the effect is being submitted: the
        // vibrator begins counting when it receives the effect, so everything is
        // pushed back by that much to land the strikes on their beats.
        val leadMs = (from - now).coerceAtLeast(0L)

        val pulses = score.pulsesBetween(from / 1000.0, to / 1000.0)
        if (pulses.isEmpty()) {
            emittedThroughMs = to
            return
        }

        val accurate = device?.canScaleAmplitude == true
        val wave = if (accurate) {
            HapticWaveform.build(
                pulses = pulses,
                fromSeconds = from / 1000.0,
                toSeconds = to / 1000.0,
                leadMs = leadMs,
                scale = mode.scale,
            )
        } else {
            HapticWaveform.buildCoarse(
                pulses = pulses,
                fromSeconds = from / 1000.0,
                toSeconds = to / 1000.0,
                leadMs = leadMs,
            )
        }

        if (wave == null || wave.isEmpty || !wave.isAudible) {
            emittedThroughMs = to
            return
        }

        device?.send(wave)
        emittedThroughMs = to
    }

    /**
     * Re-reads the playhead and re-anchors the extrapolation.
     *
     * Only re-read when the estimate has had time to drift from the truth —
     * see [ANCHOR_REFRESH_MS] — because the estimate is cheap and the read is
     * not.
     */
    private fun syncAnchor(currentMs: Long) {
        if (anchorElapsedMs != 0L) {
            val elapsed = SystemClock.elapsedRealtime() - anchorElapsedMs
            if (elapsed < ANCHOR_REFRESH_MS) return
        }
        anchorElapsedMs = SystemClock.elapsedRealtime()
        anchorPositionMs = currentMs
    }

    /**
     * Where the music is *now*, as opposed to where the player last said it was.
     *
     * A small lookback is subtracted because the motor takes a moment to react
     * to being driven and the audio path takes a moment to reach the speaker;
     * submitting slightly early is what makes a strike land with the beat rather
     * than after it. There is no measured output latency to subtract — the app
     * never queries it — so this is a modest fixed correction, and it is the one
     * number here most likely to want tuning on a real device.
     */
    private fun audiblePositionMs(): Long {
        val elapsed = SystemClock.elapsedRealtime() - anchorElapsedMs
        return anchorPositionMs + elapsed - LATENCY_LEAD_MS
    }

    private fun resetAnchor() {
        anchorElapsedMs = 0L
        anchorPositionMs = 0L
    }

    private fun stopVibrating() {
        device?.cancel()
    }

    companion object {
        private const val TAG = "MusicHaptics"

        /**
         * How often the playhead is checked. Comfortably faster than a fast
         * track's beat — 220bpm is 273ms — so no beat can be missed between
         * ticks, while still leaving the CPU idle for most of every interval.
         */
        private const val TICK_MS = 45L

        /**
         * How far ahead of the playhead each chunk is built.
         *
         * Two competing costs. Too short and the loop's own jitter shows up as
         * uneven spacing, because a late tick has nothing left to fall back on.
         * Too long and a pause or seek is served by a chunk that keeps playing
         * for the rest of the window before anything can cancel it. Roughly
         * 400ms is several ticks of slack and still short enough that a stop is
         * felt as immediate.
         */
        private const val LOOKAHEAD_MS = 400L

        /**
         * How much of the previous chunk may be cut short by a refill.
         *
         * Each chunk ends with silence long enough to reach its window's end,
         * so cutting this much off the end costs nothing audible while letting
         * the next chunk be submitted a tick or two early — which is what keeps
         * the loop's own jitter from showing up as uneven spacing.
         */
        private const val REFILL_AHEAD_MS = 30L

        /** How often `currentPosition` is actually read, rather than extrapolated. */
        private const val ANCHOR_REFRESH_MS = 250L

        /**
         * How often the current track is asked whether its analysis has landed.
         *
         * The pass itself takes seconds, so there is nothing to gain from
         * asking more often than a couple of times a second — and the cost of
         * asking is a map lookup, which is why it can be a poll at all.
         */
        private const val ANALYSIS_POLL_MS = 400L

        /**
         * Correction for motor reaction time and audio output latency, both of
         * which make a strike felt or heard later than it was submitted.
         */
        private const val LATENCY_LEAD_MS = 40L

        /**
         * The strongest strike a waveform may ask for, matching
         * [HapticWaveform.MAX_AMPLITUDE]. Public so callers can reason about
         * the value space without importing the waveform object.
         */
        const val AMPLITUDE_CEILING = HapticWaveform.MAX_AMPLITUDE
    }
}

/**
 * The phone's motor, resolved once and then rendered into the best form it
 * supports.
 *
 * Two shapes are available and they are not equally good. A phone that can be
 * told *how hard* to hit gets the real thing: one amplitude waveform per chunk,
 * every beat present, strength following the music. A phone that cannot has a
 * single volume, so the pattern is thinned and stretched instead — see
 * [HapticWaveform.buildCoarse]. The probe is done once per process because
 * `hasAmplitudeControl` is a fixed property of the hardware, and the answer
 * decides the shape of every chunk for the rest of the session.
 *
 * Kept separate from [MusicHaptics] so all the hardware knowledge and all the
 * version-gated API live in one place, and so the engine reads as scheduling
 * logic rather than as a pile of platform checks.
 */
private class Motor private constructor(
    private val vibrator: Vibrator,
    /** False when the device has one volume, which changes the pattern itself. */
    val canScaleAmplitude: Boolean,
) {
    fun send(wave: HapticWave) {
        val effect = VibrationEffect.createWaveform(wave.timings, wave.amplitudes, /* repeat = */ -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            MediaVibration.send(vibrator, effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, LEGACY_ATTRIBUTES)
        }
    }

    fun cancel() {
        runCatching { vibrator.cancel() }
    }

    companion object {
        /**
         * Music is not touch feedback. Without this the system is entitled to
         * treat the vibration as a UI response and scale it down or mute it
         * along with the keyboard, which would silently break the feature for
         * anyone who has turned touch feedback off. [USAGE_MEDIA] is the
         * declaration that this is part of the audio experience.
         */
        private val LEGACY_ATTRIBUTES by lazy {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        }

        @Volatile
        private var instance: Motor? = null

        @Volatile
        private var probed = false

        /** Null on a phone with no motor at all, which is a legitimate answer. */
        fun of(app: Context): Motor? {
            instance?.let { return it }
            synchronized(this) {
                if (probed) return instance
                probed = true
                instance = probe(app)
                return instance
            }
        }

        private fun probe(app: Context): Motor? {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                app.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                app.getSystemService(Vibrator::class.java)
            }
            if (vibrator == null || !vibrator.hasVibrator()) return null

            // `hasAmplitudeControl` is the whole question. A phone that answers
            // false ignores the amplitude array silently — the effect still
            // plays, but every strike lands at the motor's one volume, which
            // would turn a crescendo into a flat rattle. Asking first is what
            // lets the pattern be built for the hardware that will play it.
            return Motor(
                vibrator = vibrator,
                canScaleAmplitude = runCatching { vibrator.hasAmplitudeControl() }
                    .getOrDefault(false),
            )
        }
    }
}

/**
 * Tells the platform this vibration belongs to the media being played, so it is
 * governed by media rules rather than by the touch-feedback preference.
 *
 * Held in an object of its own for the same reason the touch haptics file does
 * it: [VibrationAttributes] arrived in API 30 and the two-argument `vibrate` in
 * 33, so neither type may be named anywhere that loads on an older phone. An
 * object initialises on first access, which makes it its own cache.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object MediaVibration {
    private val attributes: VibrationAttributes = VibrationAttributes.Builder()
        .setUsage(VibrationAttributes.USAGE_MEDIA)
        .build()

    fun send(vibrator: Vibrator, effect: VibrationEffect) {
        vibrator.vibrate(effect, attributes)
    }
}
