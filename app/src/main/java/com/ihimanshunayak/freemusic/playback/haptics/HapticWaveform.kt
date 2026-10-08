package com.ihimanshunayak.freemusic.playback.haptics

/**
 * One chunk of motor output: a sequence of timed steps, each with the strength
 * the motor is driven at for its duration.
 *
 * `timings[i]` is how long step `i` lasts and `amplitudes[i]` is how hard the
 * motor is driven during it, which is the shape
 * [android.os.VibrationEffect.createWaveform] takes. A step at amplitude zero
 * is silence; anything above it is the motor turning.
 */
class HapticWave(
    val timings: LongArray,
    val amplitudes: IntArray,
) {
    /** How long this chunk occupies the motor, opening delay included. */
    val durationMs: Long get() = timings.sum()

    val isEmpty: Boolean get() = timings.isEmpty()

    /** Whether the chunk asks for anything above silence. */
    val isAudible: Boolean get() = amplitudes.any { it > 0 }

    override fun toString(): String = "HapticWave(${timings.size} steps, ${durationMs}ms)"
}

/**
 * Turns intended strikes into something a [android.os.Vibrator] will accept.
 *
 * Pure arithmetic — no Android types, no clock, no motor — so every rule here
 * is unit-tested rather than tuned by holding a phone in a quiet room. All the
 * coupling to the platform lives in [MusicHaptics], which does nothing with
 * what this produces but hand it to the vibrator.
 *
 * **Why silence and sound rather than a list of events.** The platform has no
 * way to schedule a strike for a moment that has not arrived; a vibration
 * begins when the vibrator receives it. So a rhythm has to be submitted as one
 * contiguous run of timed steps, with the beat spacing made explicit as silent
 * steps. That is also why [build] takes a lead: the caller knows how far ahead
 * of the music it is submitting, and the opening silence is what holds the
 * first strike back until its beat actually lands.
 */
object HapticWaveform {
    /**
     * The loudest a strike can be asked for. The platform takes 0..255, and
     * full scale is reserved for a genuine peak — see [HapticScore]'s intensity
     * floor, which is what keeps ordinary beats off the ceiling.
     */
    const val MAX_AMPLITUDE = 255

    /**
     * A motor driven by an amplitude waveform still has to physically move for
     * the amplitude to mean anything. Below roughly this the coil current does
     * not overcome the spring and the strike is simply not felt, which reads as
     * a dropped beat rather than a quiet one — worse than a quiet strike,
     * because the rhythm is the thing a listener notices.
     */
    const val MIN_AMPLITUDE = 22

    /**
     * How long a strike holds the motor on.
     *
     * Long enough for a linear-resonant actuator to complete its movement and
     * for an eccentric-rotating-mass motor to reach a felt fraction of its
     * speed; short enough that two beats of a fast track stay separate strikes
     * rather than merging into one buzz, which is the whole risk on a dense
     * beat grid.
     */
    const val DEFAULT_PULSE_MS = 34L

    /**
     * A motor with no amplitude control has one volume, so a short strike is
     * mostly spin-up and lands as nothing. Its strikes are stretched — see
     * [buildCoarse].
     */
    const val COARSE_PULSE_MS = 42L

    /** The longest a coarse strike may run, so two close beats cannot merge into one buzz. */
    const val COARSE_MAX_PULSE_MS = 60L

    /**
     * A pause shorter than this is inside the motor's own settling time, so it
     * is folded into the neighbouring step rather than submitted as a gap the
     * hardware would round away.
     */
    private const val MIN_GAP_MS = 6L

    /**
     * Builds the chunk covering `[fromSeconds, toSeconds)` of a track.
     *
     * `leadMs` is how far ahead of the music the caller is submitting: it
     * becomes the chunk's opening silence, so the first strike still lands on
     * its beat. The chunk's total length is therefore `leadMs` plus the window,
     * and the caller must not submit another chunk before it has elapsed — the
     * platform renders one effect at a time, and a new one replaces the old.
     * This is why the timeline below is anchored to `fromSeconds` rather than to
     * the moment of submission: a strike's distance from the start of the chunk
     * is `leadMs + onset`, so nothing drifts as the lead varies from tick to
     * tick.
     *
     * [scale] is the strength the listener chose, applied on top of whatever
     * the music asked for. Returns null when nothing in the window is worth
     * vibrating, so the caller leaves the motor alone rather than submitting
     * silence — which would still cancel whatever is currently playing.
     */
    fun build(
        pulses: List<HapticPulse>,
        fromSeconds: Double,
        toSeconds: Double,
        leadMs: Long = 0L,
        scale: Double = 1.0,
        pulseMs: Long = DEFAULT_PULSE_MS,
    ): HapticWave? {
        if (pulses.isEmpty() || toSeconds <= fromSeconds) return null

        val spanMs = ((toSeconds - fromSeconds) * 1000.0).toLong()
        if (spanMs <= 0) return null
        val lead = leadMs.coerceAtLeast(0L)

        val steps = ArrayList<Long>(pulses.size * 2 + 2)
        val levels = ArrayList<Int>(pulses.size * 2 + 2)
        // Measured against `fromSeconds`, but the effect is submitted `lead`
        // early, so a strike at window offset `onsetMs` sits at `onsetMs + lead`
        // on the motor's own clock. Anchoring the cursor at `-lead` makes every
        // gap below fall out of the same subtraction and keeps the lead from
        // being lost or double-counted.
        var cursorMs = -lead
        var strikes = 0

        for (pulse in pulses) {
            val onsetMs = ((pulse.time - fromSeconds) * 1000.0).toLong()
            // A strike that begins before the window is already past, and one
            // that begins after it belongs to a later chunk. Driving the motor
            // for either would be playing music the listener is not on.
            if (onsetMs < 0 || onsetMs >= spanMs) continue

            val gap = onsetMs - cursorMs
            // A strike that lands inside the previous one is not a rhythm the
            // motor could render even if it were submitted; dropping it keeps
            // the strikes that did fit.
            if (gap < 0) continue

            if (gap > 0) {
                if (gap < MIN_GAP_MS && steps.isNotEmpty()) {
                    // Too short to be a pause, so it is folded into the strike
                    // before it — which lengthens that strike slightly rather
                    // than inventing a gap the hardware would round away.
                    steps[steps.size - 1] = steps[steps.size - 1] + gap
                } else {
                    steps.add(gap)
                    levels.add(0)
                }
            }
            cursorMs = onsetMs

            val amplitude = (pulse.intensity * scale * MAX_AMPLITUDE)
                .toInt()
                .coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE)
            steps.add(pulseMs)
            levels.add(amplitude)
            cursorMs += pulseMs
            strikes++
        }

        if (strikes == 0) return null

        // Trailing silence, so the chunk occupies exactly the window it claims
        // on top of the lead. Without it the last strike would leave the total
        // short of the window and the caller would submit the next chunk before
        // the music reached it, pulling every following strike progressively
        // early.
        val tail = spanMs - cursorMs
        if (tail > 0) {
            steps.add(tail)
            levels.add(0)
        } else if (tail < 0) {
            // The window overran: strikes close together took longer than the
            // window has. Trimmed to the window rather than left to overhang
            // the next chunk, because the platform would cut it anyway, just
            // less predictably — and a systematic overhang would be felt as the
            // whole pattern drifting late.
            return trim(steps, levels, lead + spanMs)
        }

        return HapticWave(steps.toLongArray(), levels.toIntArray())
    }

    /**
     * The same chunk for a motor that cannot be told how hard to hit.
     *
     * Only timing survives, so the pattern is thinned to the strikes that carry
     * the rhythm — accents, plus every other beat — and the strikes are
     * stretched until they can actually be felt. A full-density pattern on this
     * hardware is a continuous buzz with no discernible beat in it, which is
     * exactly what the thinning avoids.
     */
    fun buildCoarse(
        pulses: List<HapticPulse>,
        fromSeconds: Double,
        toSeconds: Double,
        leadMs: Long = 0L,
    ): HapticWave? {
        if (pulses.isEmpty()) return null
        // A grid whose accents all fell outside the window would thin to
        // nothing, so an empty result falls back to the full grid rather than
        // to silence.
        val thinned = pulses.filterIndexed { index, pulse -> pulse.accented || index % 2 == 0 }
        val source = thinned.ifEmpty { pulses }
        val built = build(
            pulses = source,
            fromSeconds = fromSeconds,
            toSeconds = toSeconds,
            leadMs = leadMs,
            scale = 1.0,
            pulseMs = COARSE_PULSE_MS,
        ) ?: return null

        val timings = LongArray(built.timings.size)
        val amplitudes = IntArray(built.amplitudes.size)
        for (index in built.timings.indices) {
            // Strike-ness comes from the amplitude, not the index: a lead adds
            // an opening gap, so index parity would call the first strike a gap
            // and silence it.
            val strike = built.amplitudes[index] > 0
            timings[index] = if (strike) {
                built.timings[index].coerceAtMost(COARSE_MAX_PULSE_MS)
            } else {
                built.timings[index]
            }
            amplitudes[index] = if (strike) MAX_AMPLITUDE else 0
        }
        return HapticWave(timings, amplitudes)
    }

    /**
     * Cuts a chunk down to [spanMs], dropping whatever no longer fits.
     *
     * A strike straddling the boundary is shortened rather than kept whole,
     * because the next chunk is submitted at that instant — so an overhanging
     * strike would be cut by the platform anyway, just less predictably.
     */
    private fun trim(steps: List<Long>, levels: List<Int>, spanMs: Long): HapticWave {
        val timings = ArrayList<Long>(steps.size)
        val amplitudes = ArrayList<Int>(levels.size)
        var remaining = spanMs
        for (index in steps.indices) {
            if (remaining <= 0) break
            val step = steps[index].coerceAtMost(remaining)
            if (step <= 0) continue
            timings.add(step)
            amplitudes.add(levels[index])
            remaining -= step
        }
        return HapticWave(timings.toLongArray(), amplitudes.toIntArray())
    }
}
