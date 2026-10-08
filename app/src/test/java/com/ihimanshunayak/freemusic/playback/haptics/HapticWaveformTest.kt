package com.ihimanshunayak.freemusic.playback.haptics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic that turns intended strikes into amplitudes.
 *
 * This is the part of the feature where a mistake is inaudible on paper and
 * obvious in the hand — a chunk one beat short drifts the whole track early, a
 * missing lead fires every strike late, a gap folded into a strike turns a
 * rhythm into a buzz. None of that is visible from a screenshot, so it is
 * pinned here instead.
 */
class HapticWaveformTest {

    private fun pulse(time: Double, intensity: Double = 0.8, accented: Boolean = false) =
        HapticPulse(time = time, intensity = intensity, accented = accented)

    /**
     * A step is silence or a strike, and which one it is comes from its
     * amplitude rather than its position — a chunk with a lead opens on silence
     * but one without a lead opens directly on the beat, so index parity is not
     * a property this can assert. The arrays must still line up, because the
     * platform rejects a length mismatch outright.
     */
    @Test
    fun `strike-ness comes from the amplitude and the arrays line up`() {
        val wave = build(listOf(pulse(0.0), pulse(0.5)), from = 0.0, to = 1.0)

        assertEquals(wave.timings.size, wave.amplitudes.size)
        assertTrue(wave.amplitudes.any { it > 0 })
        assertTrue(wave.amplitudes.any { it == 0 })
        // Silence must never be asked to drive the motor, and a strike must never
        // be zero — a zero-amplitude "strike" is a dropped beat.
        wave.amplitudes.forEach { assertTrue(it == 0 || it > 0) }
    }

    /**
     * The lead is time between the effect being submitted and the window's
     * first instant, and it has to be encoded as silence or every strike lands
     * early by exactly that much.
     */
    @Test
    fun `the lead is silence before the first strike`() {
        val wave = build(listOf(pulse(0.0)), from = 0.0, to = 1.0, leadMs = 300L)

        assertEquals(300L, wave.timings[0])
        assertEquals(0, wave.amplitudes[0])
    }

    /**
     * A strike's offset has to be measured from the window, not from the
     * playhead — the engine builds chunks ahead of the music, so the same pulse
     * belongs at a different gap depending on where the window starts.
     */
    @Test
    fun `offsets are relative to the window`() {
        val wave = build(listOf(pulse(2.0)), from = 2.0, to = 2.5)

        // No lead, and the pulse is the window's own first instant, so there is
        // no gap at all before it and the chunk opens directly on the strike.
        assertTrue("the chunk opens on the strike", wave.amplitudes[0] > 0)
        assertEquals(500L, wave.durationMs)
    }

    @Test
    fun `a pulse later in the window gets a longer gap`() {
        val early = build(listOf(pulse(0.1)), from = 0.0, to = 1.0)
        val late = build(listOf(pulse(0.9)), from = 0.0, to = 1.0)

        assertEquals(100L, early.timings[0])
        assertEquals(900L, late.timings[0])
    }

    /**
     * The chunk has to occupy exactly the window it claims. Short, and the next
     * chunk starts before the music arrives; long, and the motor runs into
     * music the next chunk was supposed to describe. Either way the error
     * compounds over a track, and the loop re-derives its start from where the
     * last chunk ended — so this is asserted rather than eyeballed.
     */
    @Test
    fun `a chunk fills exactly its window`() {
        val wave = build(listOf(pulse(0.0), pulse(0.5)), from = 0.0, to = 1.0)

        assertEquals(1000L, wave.durationMs)
    }

    /**
     * With a lead the chunk is longer than the window by exactly the lead,
     * because it has to sit in the motor waiting for the music. Getting this
     * wrong is what made the first version of this class drift: the lead was
     * written into the array but the total was still computed as the window, so
     * every chunk came out a lead too short and the pattern crept forward.
     */
    @Test
    fun `a chunk with a lead occupies the lead plus its window`() {
        val wave = build(listOf(pulse(0.25)), from = 0.0, to = 1.0, leadMs = 150L)

        assertEquals(1150L, wave.durationMs)
        // And the first strike still lands 150 + 250 into it, not at 250.
        assertEquals(400L, wave.timings[0])
    }

    /**
     * Consecutive chunks have to tile: the second resumes where the first
     * stopped, with no beat double-counted at the seam and none dropped. A
     * half-open window is what makes that true, and this is the check that it
     * survives being turned into amplitudes.
     */
    @Test
    fun `consecutive windows tile without losing a beat`() {
        val pulses = (0..20).map { pulse(it * 0.25) }
        val first = HapticWaveform.build(pulses, 0.0, 1.0, 0L, 1.0)!!
        val second = HapticWaveform.build(pulses, 1.0, 2.0, 0L, 1.0)!!

        assertEquals(1000L, first.durationMs)
        assertEquals(1000L, second.durationMs)
        // 0.00 0.25 0.50 0.75 in the first: four beats, since the 1.00 beat is
        // the half-open window's exclusion.
        assertEquals("first window strikes", 4, first.amplitudes.count { it > 0 })
        assertEquals(4, second.amplitudes.count { it > 0 })
    }

    /**
     * A gap inside the motor's own settling time is not a pause the hardware
     * can render, so it is folded into the previous strike rather than
     * submitted as a step it will round away.
     */
    @Test
    fun `a sub-millisecond gap is merged into the strike before it`() {
        val wave = build(listOf(pulse(0.0), pulse(0.0345)), from = 0.0, to = 0.5)

        // The second beat is 34ms after the first, but the first strike already
        // runs for 34ms — so there is no pause at all and the two strikes end up
        // adjacent rather than separated by a zero-length gap.
        assertEquals(HapticWaveform.DEFAULT_PULSE_MS, wave.timings[0])
        assertEquals(HapticWaveform.DEFAULT_PULSE_MS, wave.timings[1])
        assertTrue("both steps drive the motor", wave.amplitudes[0] > 0)
        assertTrue("both steps drive the motor", wave.amplitudes[1] > 0)

        // 5ms later and the gap is real but still too short: it lengthens the
        // first strike instead of becoming a step of its own.
        val squeezed = build(listOf(pulse(0.0), pulse(0.039)), from = 0.0, to = 0.5)
        assertEquals(HapticWaveform.DEFAULT_PULSE_MS + 5L, squeezed.timings[0])
        assertEquals(2, squeezed.amplitudes.count { it > 0 })
    }

    /**
     * Strength has to reach the motor: a loud beat and a quiet one over the
     * same grid must come out as different amplitudes, or the dynamic half of
     * the feature simply does not exist.
     */
    @Test
    fun `intensity reaches the amplitudes`() {
        val wave = build(
            listOf(pulse(0.0, intensity = 1.0), pulse(0.5, intensity = 0.2)),
            from = 0.0,
            to = 1.0,
        )

        val strikes = wave.amplitudes.filter { it > 0 }
        assertEquals(2, strikes.size)
        assertTrue("loud should exceed quiet", strikes[0] > strikes[1])
    }

    @Test
    fun `the chosen strength scales every strike`() {
        val quiet = build(listOf(pulse(0.0, intensity = 1.0)), from = 0.0, to = 0.5, scale = 0.3)
        val loud = build(listOf(pulse(0.0, intensity = 1.0)), from = 0.0, to = 0.5, scale = 1.0)

        val quietStrike = quiet.amplitudes.first { it > 0 }
        val loudStrike = loud.amplitudes.first { it > 0 }
        assertTrue("a stronger setting must hit harder", loudStrike > quietStrike)
    }

    /**
     * A strike that cannot move the motor is not a quiet strike, it is a
     * missing one — the listener counts beats, so a dropped beat is worse than
     * a soft one. The floor is what stops a quiet passage from reading as a
     * broken grid.
     */
    @Test
    fun `a very quiet strike is still felt`() {
        val wave = build(listOf(pulse(0.0, intensity = 0.001)), from = 0.0, to = 0.5, scale = 0.1)

        assertTrue(wave.amplitudes.first { it > 0 } >= HapticWaveform.MIN_AMPLITUDE)
    }

    /** Full strength must not overflow what the platform accepts. */
    @Test
    fun `amplitude never exceeds the platform ceiling`() {
        val wave = build(
            listOf(pulse(0.0, intensity = 1.0), pulse(0.5, intensity = 1.0, accented = true)),
            from = 0.0,
            to = 1.0,
            scale = 1.0,
        )

        assertTrue(wave.amplitudes.all { it <= HapticWaveform.MAX_AMPLITUDE })
    }

    /**
     * A pulse from before the window is already past; submitting it would make
     * the motor strike for music the listener has moved on from.
     */
    @Test
    fun `a pulse before the window is dropped`() {
        val wave = build(listOf(pulse(0.0), pulse(0.5)), from = 0.25, to = 1.0)

        // Only the 0.5 pulse survives, so there is exactly one strike.
        assertEquals(1, wave.amplitudes.count { it > 0 })
    }

    @Test
    fun `nothing in the window means no effect at all`() {
        assertNull(HapticWaveform.build(emptyList(), 0.0, 1.0, 0L, 1.0))
        assertNull(HapticWaveform.build(listOf(pulse(5.0)), 0.0, 1.0, 0L, 1.0))
    }

    /**
     * A window with no beats in it must come back empty rather than as a chunk
     * of silence, because silence is still an effect: submitting it would
     * cancel whatever the motor is currently working through, cutting a beat
     * that was already on its way.
     */
    @Test
    fun `a window with no beats is not a silent chunk`() {
        val wave = HapticWaveform.build(
            pulses = listOf(pulse(0.0), pulse(2.0)),
            fromSeconds = 1.0,
            toSeconds = 1.5,
        )

        assertNull(wave)
    }

    /** A window that is not a window is not worth submitting silence for. */
    @Test
    fun `a degenerate window produces nothing`() {
        assertNull(HapticWaveform.build(listOf(pulse(0.0)), 1.0, 1.0, 0L, 1.0))
        assertNull(HapticWaveform.build(listOf(pulse(0.0)), 2.0, 1.0, 0L, 1.0))
    }

    /**
     * A lead longer than the window is not a special case: the strikes are
     * still inside the window, they just sit further back in the submitted
     * effect. Silence for the length of the lead, then the pattern — which is
     * exactly what a strike at the window's own first instant should sound like
     * when the effect was handed over early.
     */
    @Test
    fun `a lead longer than the window keeps its strikes`() {
        val wave = HapticWaveform.build(listOf(pulse(0.0)), 0.0, 0.2, leadMs = 500L, scale = 1.0)!!

        assertTrue(wave.isAudible)
        // 500 of lead, 200 of window.
        assertEquals(700L, wave.durationMs)
        assertEquals(500L, wave.timings[0])
        assertEquals(0, wave.amplitudes[0])
    }

    /**
     * The lead belongs to the chunk's opening, so a long one on an otherwise
     * ordinary window still has to place its later strikes by the same
     * arithmetic — this is the shape the engine submits whenever it resumes
     * part-way through a window.
     */
    @Test
    fun `a long lead still places its strikes on the beats`() {
        val wave = build(listOf(pulse(0.0), pulse(0.5)), from = 0.0, to = 1.0, leadMs = 380L)

        assertEquals(380L, wave.timings[0])
        assertEquals(HapticWaveform.DEFAULT_PULSE_MS, wave.timings[1])
        // The second beat is 500ms into the window and the first strike ran
        // from 380 to 414, so the gap to it is the difference from the strike's
        // end — not from the window's start.
        assertEquals(466L, wave.timings[2])
        assertEquals(1380L, wave.durationMs)
    }

    /**
     * A device with one volume cannot express strength, so it gets timing only
     * — thinned, or every beat at full power is a continuous buzz with no
     * rhythm left in it.
     */
    @Test
    fun `the coarse pattern is thinned and full power`() {
        val pulses = (0..7).map { pulse(it * 0.25, accented = it == 0) }
        val wave = HapticWaveform.buildCoarse(pulses, 0.0, 2.0, 0L)!!

        val strikes = wave.amplitudes.filter { it > 0 }
        assertTrue("thinned below full density", strikes.size < pulses.size)
        assertTrue("coarse is one volume", strikes.all { it == HapticWaveform.MAX_AMPLITUDE })
    }

    /** A thin grid still has to produce something rather than nothing. */
    @Test
    fun `the coarse pattern survives a grid with no accents`() {
        val wave = HapticWaveform.buildCoarse((0..3).map { pulse(it * 0.5) }, 0.0, 2.0, 0L)

        assertNotNull(wave)
        assertTrue(wave!!.isAudible)
    }

    /**
     * A strike longer than the gap to the next one would run them together, so
     * the coarse pulse is capped — the whole reason the pattern is thinned in
     * the first place is to keep beats distinct.
     */
    @Test
    fun `coarse strikes stay short enough to stay distinct`() {
        val pulses = (0..7).map { pulse(it * 0.1) }
        val wave = HapticWaveform.buildCoarse(pulses, 0.0, 1.0, 0L)

        assertNotNull(wave)
        // Strike-ness is read from the amplitude, since a leading gap shifts
        // every index.
        wave!!.timings.filterIndexed { index, _ -> wave.amplitudes[index] > 0 }
            .forEach { assertTrue("a coarse strike must stay distinct", it <= 60L) }
    }

    @Test
    fun `the coarse pattern respects the window too`() {
        val wave = HapticWaveform.buildCoarse((0..7).map { pulse(it * 0.25) }, 0.0, 2.0, 0L)

        assertEquals(2000L, wave!!.durationMs)
    }

    private fun build(
        pulses: List<HapticPulse>,
        from: Double,
        to: Double,
        leadMs: Long = 0L,
        scale: Double = 1.0,
    ): HapticWave = HapticWaveform.build(pulses, from, to, leadMs, scale)!!
}
