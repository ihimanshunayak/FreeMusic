package com.ihimanshunayak.freemusic.playback.haptics

import com.ihimanshunayak.freemusic.playback.smart.EnergySample
import com.ihimanshunayak.freemusic.playback.smart.TrackAnalysis
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One beat, as something the motor should do: when to strike and how hard.
 *
 * [time] is in the analysed track's own timeline, which for every source this
 * app plays is the same timeline `currentPosition` counts on. [intensity] is
 * 0..1 and is deliberately *not* scaled by the user's chosen strength — that
 * belongs to the engine, so changing the setting re-renders the motor without
 * rebuilding the score.
 */
data class HapticPulse(
    val time: Double,
    val intensity: Double,
    /** True on a bar line, where the pattern is allowed to hit harder. */
    val accented: Boolean,
)

/**
 * A track's vibration, derived once from its stored analysis.
 *
 * **Why this is derived rather than measured.** The app already decodes every
 * track offline to build a beat grid and a low-band energy curve — see
 * [TrackAnalysis] and the native analyzer behind it. Vibration wants exactly
 * those two things and nothing else, so the honest move is to read the analysis
 * that already exists rather than to tap the DSP chain and spend a second
 * decode finding out what is already on disk.
 *
 * **What it can and cannot promise.** Android's [android.os.Vibrator] has no
 * sample-accurate scheduling API — an effect is submitted complete and the
 * motor renders it. So a score is not a waveform handed to the hardware; it is
 * a list of *intended* strikes that [MusicHaptics] slices into short chunks and
 * submits ahead of the playhead. Beat-locked and accent-aware, yes; bit-exact
 * and drift-free for an hour, no. That is a property of the platform, not of
 * this class.
 *
 * Instances are immutable and safe to share, and the class has no Android
 * dependency at all, so the derivation is unit-tested on the JVM.
 */
class HapticScore private constructor(
    val trackId: String,
    /** Ordered by [HapticPulse.time], one entry per beat in the analysed range. */
    val pulses: List<HapticPulse>,
    /** The analysis's own [TrackAnalysis.beatConfidence], passed through for callers to gate on. */
    val confidence: Double,
) {
    val isEmpty: Boolean get() = pulses.isEmpty()

    /**
     * The pulses landing in `[fromSeconds, toSeconds)`.
     *
     * Half-open at the end so that consecutive windows tile without a beat
     * being emitted twice — the engine hands this a fresh window several times
     * a second, and a duplicated strike at every seam would be heard as a
     * stutter.
     */
    fun pulsesBetween(fromSeconds: Double, toSeconds: Double): List<HapticPulse> {
        if (pulses.isEmpty() || toSeconds <= fromSeconds) return emptyList()
        val first = lowerBound(fromSeconds)
        if (first >= pulses.size || pulses[first].time >= toSeconds) return emptyList()

        val out = ArrayList<HapticPulse>(16)
        var index = first
        while (index < pulses.size && pulses[index].time < toSeconds) {
            out.add(pulses[index])
            index++
        }
        return out
    }

    /** Index of the first pulse at or after [time], or [pulses] size when there is none. */
    private fun lowerBound(time: Double): Int {
        var low = 0
        var high = pulses.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (pulses[mid].time < time) low = mid + 1 else high = mid
        }
        return low
    }

    override fun toString(): String =
        "HapticScore($trackId, ${pulses.size} pulses, confidence=$confidence)"

    companion object {
        /** A score for a track with no usable analysis. Never null, so callers need no branch. */
        val EMPTY = HapticScore(trackId = "", pulses = emptyList(), confidence = 0.0)

        /**
         * Below this the grid is more noise than rhythm. The analyzer gates the
         * whole transition policy far higher than this, but vibration only has
         * to be *rhythmic*, not *correct*: a beat grid that is a little ragged
         * still feels better than silence, so the bar is deliberately lower
         * than the one Automix uses.
         */
        private const val MIN_CONFIDENCE = 0.25

        /**
         * Plausible seconds-per-beat. The analyzer only ever emits 40–220bpm
         * (0.27–1.5s), so this is a corruption guard rather than a musical
         * judgement — it is what keeps a malformed analysis from asking for
         * several thousand beats over a three-minute track.
         */
        private const val MIN_INTERVAL = 0.15
        private const val MAX_INTERVAL = 3.0

        /**
         * Two strikes closer than this cannot both be rendered — the motor
         * needs roughly this long to move and come back. Only reachable when a
         * bar anchor and the tempo disagree, which is exactly the case worth
         * dropping a beat for.
         */
        private const val MIN_SPACING = 0.09

        /**
         * A beat is accented when it sits within this fraction of a beat of a
         * downbeat or phrase boundary. Tolerance rather than exact equality,
         * because the boundary times and the walked grid are computed from
         * different sources and agreeing to the millisecond is not something
         * either promises.
         */
        private const val ACCENT_TOLERANCE_BEATS = 0.22

        /**
         * A curve value of this or more is a full-strength strike.
         *
         * The native analyzer normalises the low band against the 90th
         * percentile of its own nonzero frames and caps the result at 1.5, so 1
         * is "as loud as this track's bass usually gets" and the region above
         * it is a genuine peak that should saturate rather than scale.
         */
        private const val ENERGY_REFERENCE = 1.0

        /**
         * The share of full strength every beat gets before the curve is
         * consulted. Without it a quiet passage fades to nothing and the rhythm
         * — the entire point — disappears with it.
         */
        private const val INTENSITY_FLOOR = 0.16

        /**
         * Intended strength when there is no curve at all: a head-only
         * analysis, or one stored before the curves existed. The beat grid is
         * still real, so the rhythm is still real; only the dynamics are
         * missing.
         */
        private const val INTENSITY_WITHOUT_CURVE = 0.55

        private const val DOWNBEAT_GAIN = 1.32
        private const val PHRASE_GAIN = 1.15

        /** Enough beats to be a grid rather than a couple of stray hits. */
        private const val MIN_BEATS = 4

        /**
         * Derives the vibration for [analysis], or [EMPTY] when it does not
         * describe a track that can be felt.
         *
         * Pure: no I/O, no Android, no clock. Everything it needs is in the
         * argument, which is what makes the whole derivation testable and what
         * lets the engine call it from any thread.
         */
        fun of(analysis: TrackAnalysis): HapticScore {
            if (!analysis.isUsable) return EMPTY
            if (analysis.beatConfidence < MIN_CONFIDENCE) return EMPTY

            val interval = analysis.beatInterval
            if (interval.isNaN() || interval < MIN_INTERVAL || interval > MAX_INTERVAL) return EMPTY

            val beats = beatGrid(analysis, interval)
            if (beats.size < MIN_BEATS) return EMPTY

            val tolerance = interval * ACCENT_TOLERANCE_BEATS
            val downbeats = analysis.downbeats
            val phrases = analysis.phraseBoundaries
            val lowCurve = analysis.lowEnergyCurve
            val fullCurve = analysis.energyCurve

            val pulses = beats.map { time ->
                val downbeat = downbeats.near(time, tolerance)
                val base = INTENSITY_FLOOR +
                    (1.0 - INTENSITY_FLOOR) * levelAt(time, lowCurve, fullCurve)
                val gain = when {
                    downbeat -> DOWNBEAT_GAIN
                    phrases.near(time, tolerance) -> PHRASE_GAIN
                    else -> 1.0
                }
                HapticPulse(
                    time = time,
                    intensity = (base * gain).coerceIn(0.0, 1.0),
                    accented = downbeat,
                )
            }

            return HapticScore(
                trackId = analysis.trackId,
                pulses = pulses,
                confidence = analysis.beatConfidence,
            )
        }

        /**
         * The beat times, walked from [TrackAnalysis.firstBeat] to the end of the
         * content.
         *
         * Stepping a fixed [interval] across a whole track is the obvious
         * approach and the wrong one: a measured interval carries a little
         * error, and a three-minute track has a few hundred beats for that error
         * to accumulate over. The grid would start locked and finish visibly —
         * audibly, feelably — late.
         *
         * So the walk is re-anchored at every downbeat. Downbeats are predicted
         * beat times in their own right, they are never more than a bar apart,
         * and a bar is too short for the error to matter, so each segment is
         * walked at *its own* interval — measured from the two anchors that
         * bound it — rather than at the track's average. Drift is then bounded
         * by one bar instead of by the whole track.
         */
        private fun beatGrid(analysis: TrackAnalysis, interval: Double): List<Double> {
            val end = analysis.contentEndTime.takeIf { it > 0.0 } ?: analysis.duration
            val start = analysis.firstBeat
            if (end <= start) return emptyList()

            val anchors = ArrayList<Double>(analysis.downbeats.size + 2)
            anchors.add(start)
            analysis.downbeats.asSequence()
                .filter { it > start && it < end }
                .sorted()
                .forEach { anchor ->
                    // The analyzer already maps downbeats onto beats and
                    // deduplicates them, but a corrupt or hand-edited file is
                    // not worth trusting with an invariant this class relies on.
                    if (anchor - anchors.last() > MIN_SPACING) anchors.add(anchor)
                }
            anchors.add(end)

            val beats = ArrayList<Double>(1024)
            for (index in 0 until anchors.size - 1) {
                val from = anchors[index]
                val span = anchors[index + 1] - from
                val count = max(1, (span / interval).roundToInt())
                val step = span / count
                for (beat in 0 until count) {
                    val time = from + beat * step
                    if (beats.isEmpty() || time - beats.last() >= MIN_SPACING) beats.add(time)
                }
            }
            // Every interior anchor is emitted as the first beat of the segment
            // that follows it, so only the final one — the content end — has
            // nothing to emit it and has to be added here. Without this the
            // grid stops one beat early, which is a beat the listener would
            // still have tapped.
            if (end - beats.last() >= MIN_SPACING) beats.add(end)
            return beats
        }

        /**
         * How hard the music is hitting at [time], 0..1.
         *
         * The low band drives this, not the broadband curve: a kick drum *is*
         * the low band, and it is what a hand on a speaker cone feels. The
         * broadband curve is the fallback for tracks the analyzer band-split
         * nothing for, and a constant is the fallback for analyses stored
         * before either curve existed.
         */
        private fun levelAt(
            time: Double,
            lowCurve: List<EnergySample>,
            fullCurve: List<EnergySample>,
        ): Double {
            val energy = nearestSample(lowCurve, time) ?: nearestSample(fullCurve, time)
                ?: return INTENSITY_WITHOUT_CURVE
            if (energy.isNaN()) return INTENSITY_WITHOUT_CURVE
            return (energy / ENERGY_REFERENCE).coerceIn(0.0, 1.0)
        }

        /**
         * The curve sample closest in time to [time].
         *
         * Nearest rather than indexed-on-purpose: the native analyzer caps a
         * curve at 240 points and strides whatever it measured to fit, so on a
         * four-minute track consecutive samples are commonly a second apart and
         * the spacing is not part of the contract. Indexing by second would
         * read the wrong sample — or past the end — on exactly the tracks where
         * it mattered.
         */
        private fun nearestSample(curve: List<EnergySample>, time: Double): Double? {
            if (curve.isEmpty()) return null
            val found = curve.binarySearch { sample ->
                when {
                    sample.time < time -> -1
                    sample.time > time -> 1
                    else -> 0
                }
            }
            if (found >= 0) return curve[found].energy

            val insertion = -found - 1
            val before = curve.getOrNull(insertion - 1) ?: return curve.getOrNull(insertion)?.energy
            val after = curve.getOrNull(insertion) ?: return before.energy
            return if (time - before.time <= after.time - time) before.energy else after.energy
        }

        /**
         * Whether any entry sits within [tolerance] of [time].
         *
         * Linear from a binary-search start rather than a scan: a long track
         * has a few thousand boundaries and a few hundred beats, and walking
         * both in full would be quadratic in the length of the song for a
         * question whose answer is almost always "the next one".
         */
        private fun List<Double>.near(time: Double, tolerance: Double): Boolean {
            if (isEmpty()) return false
            var index = binarySearch { candidate ->
                when {
                    candidate < time - tolerance -> -1
                    candidate > time + tolerance -> 1
                    else -> 0
                }
            }
            if (index < 0) index = -index - 1
            if (index >= size) index = size - 1
            return abs(this[index] - time) <= tolerance
        }
    }
}
