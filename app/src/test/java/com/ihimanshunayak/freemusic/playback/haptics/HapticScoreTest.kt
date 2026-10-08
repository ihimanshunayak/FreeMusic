package com.ihimanshunayak.freemusic.playback.haptics

import com.ihimanshunayak.freemusic.playback.smart.EnergySample
import com.ihimanshunayak.freemusic.playback.smart.TrackAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The derivation, against analyses written by hand rather than measured.
 *
 * Every case here is one a real track produces: a stready four-bar grid, a grid
 * whose curve is strided a second apart, one saved before the curves existed at
 * all, and the corrupt ones the guards exist for. What is being pinned down is
 * the *shape* of the answer — where the strikes land, how hard, and which
 * inputs produce none — because that shape is the contract [MusicHaptics]
 * schedules against.
 */
class HapticScoreTest {

    private fun analysis(
        bpm: Double = 120.0,
        interval: Double = 0.5,
        firstBeat: Double = 0.0,
        contentEnd: Double = 8.0,
        duration: Double = 10.0,
        confidence: Double = 0.8,
        downbeats: List<Double> = emptyList(),
        phrases: List<Double> = emptyList(),
        low: List<EnergySample> = emptyList(),
        energy: List<EnergySample> = emptyList(),
    ) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = "track",
        duration = duration,
        bpm = bpm,
        beatInterval = interval,
        beatConfidence = confidence,
        firstBeat = firstBeat,
        contentEndTime = contentEnd,
        downbeats = downbeats,
        phraseBoundaries = phrases,
        lowEnergyCurve = low,
        energyCurve = energy,
    )

    /** Every strike at an even 0.5s from zero, stopping where the content does. */
    @Test
    fun `walks the beat grid from first beat to content end`() {
        val score = HapticScore.of(analysis(firstBeat = 2.0, contentEnd = 6.0))

        val times = score.pulses.map { it.time }
        assertEquals(listOf(2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0, 5.5, 6.0), times)
    }

    /**
     * The end that matters is the content's, not the file's. Trailing silence
     * vibrated along to is worse than nothing, because it is a rhythm for music
     * that is not playing.
     */
    @Test
    fun `stops at content end rather than the track duration`() {
        val score = HapticScore.of(analysis(contentEnd = 4.0, duration = 30.0))

        assertEquals(4.0, score.pulses.last().time, 1e-9)
        assertTrue(score.pulses.none { it.time > 4.0 })
    }

    /** A file with no content end recorded falls back to its duration. */
    @Test
    fun `falls back to the duration when content end is unknown`() {
        val score = HapticScore.of(analysis(contentEnd = 0.0, duration = 3.0))

        assertEquals(3.0, score.pulses.last().time, 1e-9)
    }

    /**
     * Downbeats are bar lines, and a bar line is where a listener's foot lands
     * hardest. They must read as stronger than the beat before them, which is
     * the whole of what "accent" means here.
     */
    @Test
    fun `accents downbeats above the surrounding beats`() {
        val score = HapticScore.of(
            analysis(
                contentEnd = 4.0,
                downbeats = listOf(0.0, 2.0),
                low = flat(0.5, from = 0.0, to = 4.0),
            ),
        )

        val byTime = score.pulses.associateBy { it.time }
        val accent = byTime.getValue(2.0)
        val plain = byTime.getValue(2.5)

        assertTrue(accent.accented)
        assertTrue("accent should out-hit a plain beat", accent.intensity > plain.intensity)
        assertFalse(plain.accented)
    }

    /**
     * Bar lines and the walked grid come from different sources, so they never
     * agree to the millisecond and the accent has to be applied by tolerance
     * rather than by equality.
     *
     * A downbeat that anchors a segment lands on the grid exactly, so the case
     * the tolerance actually exists for is the one where the spacing guard
     * folds a downbeat into the anchor before it. Then no beat equals it, and
     * the beat it belongs to still has to be accented.
     */
    @Test
    fun `accents a downbeat the spacing guard folded onto the previous anchor`() {
        // The bar line lands on the first beat, which is already an anchor; all
        // that is left between the two is the sub-frame difference between two
        // independently predicted peaks.
        val score = HapticScore.of(
            analysis(contentEnd = 4.0, downbeats = listOf(0.02), low = flat(0.5, 0.0, 4.0)),
        )

        val first = score.pulses.first()
        assertEquals(0.0, first.time, 1e-9)
        assertTrue("the bar line belongs to the first beat", first.accented)
        // Folding it away must not disturb the walk: with a single anchor left
        // it is the track's own interval that spaces the grid.
        assertEquals(0.5, score.pulses[1].time, 1e-9)
    }

    /**
     * The other side of the same rule: a bar line far enough from a beat to be
     * a genuinely different bar must not drag an accent onto a beat that is not
     * on it.
     */
    @Test
    fun `does not accent a beat far from any downbeat`() {
        val score = HapticScore.of(
            analysis(contentEnd = 4.0, downbeats = listOf(1.5), low = flat(0.5, 0.0, 4.0)),
        )

        val byTime = score.pulses.associateBy { it.time }
        assertTrue("the beat on the bar line is accented", byTime.getValue(1.5).accented)
        assertFalse("the next beat is a different bar", byTime.getValue(2.0).accented)
    }

    /** Phrase boundaries lift a beat without claiming it is a bar line. */
    @Test
    fun `phrases sit between a plain beat and a downbeat`() {
        val score = HapticScore.of(
            analysis(
                contentEnd = 4.0,
                downbeats = listOf(0.0),
                phrases = listOf(2.0),
                low = flat(0.5, 0.0, 4.0),
            ),
        )

        val byTime = score.pulses.associateBy { it.time }
        assertTrue(byTime.getValue(2.0).intensity > byTime.getValue(2.5).intensity)
        assertTrue(byTime.getValue(2.0).intensity <= byTime.getValue(0.0).intensity)
    }

    /**
     * The low band is what a hand on a speaker feels, so it drives the
     * strength: the same grid over quiet bass and loud bass must not feel the
     * same.
     */
    @Test
    fun `loud bass hits harder than quiet bass`() {
        val loud = HapticScore.of(
            analysis(contentEnd = 4.0, low = flat(1.2, 0.0, 4.0)),
        )
        val quiet = HapticScore.of(
            analysis(contentEnd = 4.0, low = flat(0.04, 0.0, 4.0)),
        )

        assertTrue(loud.pulses.first().intensity > quiet.pulses.first().intensity)
    }

    /**
     * Silence has to leave *something* behind, or the rhythm — the entire point
     * of the feature — vanishes exactly when the music drops. The floor is what
     * keeps a quiet passage tactile instead of empty.
     */
    @Test
    fun `a silent passage still produces strikes`() {
        val score = HapticScore.of(
            analysis(contentEnd = 4.0, low = flat(0.0, 0.0, 4.0)),
        )

        assertFalse(score.isEmpty)
        assertTrue(score.pulses.all { it.intensity > 0.0 })
    }

    /**
     * Curves are strided by the analyzer to a 240-point cap, so on a long track
     * samples are commonly a second apart. Reading by second would take the
     * wrong value, so the lookup has to be by nearest time.
     */
    @Test
    fun `reads a strided curve by nearest sample`() {
        val curve = listOf(
            EnergySample(0.0, 0.0),
            EnergySample(1.0, 1.0),
            EnergySample(2.0, 0.0),
        )
        val score = HapticScore.of(
            analysis(firstBeat = 1.0, contentEnd = 3.0, low = curve),
        )

        val byTime = score.pulses.associateBy { it.time }
        // The 1.0 beat sits on the only loud sample and the 2.5 beat is nearest
        // the silent one at 2.0 — reading the curve by second instead of by
        // nearest time would give both of them the same value.
        assertTrue(
            "the beat on the loud sample must out-hit the one on the silent sample",
            byTime.getValue(1.0).intensity > byTime.getValue(2.5).intensity,
        )
    }

    /**
     * An analysis stored before the band split existed has a broadband curve
     * and no low-band one. The grid is still real, so the rhythm is still real;
     * only the dynamics come from the coarser source.
     */
    @Test
    fun `falls back to the broadband curve when there is no low band`() {
        val score = HapticScore.of(
            analysis(contentEnd = 2.0, energy = flat(1.0, 0.0, 2.0)),
        )

        assertFalse(score.isEmpty)
        assertTrue(score.pulses.first().intensity > 0.0)
    }

    /**
     * A head-only analysis carries a grid but no curves at all, and a track
     * analysed before either curve was stored is the same shape. The beat grid
     * is enough to vibrate to, so this must not come back empty.
     */
    @Test
    fun `produces a uniform pattern when there are no curves`() {
        val score = HapticScore.of(analysis(contentEnd = 4.0))

        assertFalse(score.isEmpty)
        val distinct = score.pulses.map { it.intensity }.distinct()
        assertEquals("no curve should mean no dynamics", 1, distinct.size)
    }

    @Test
    fun `is empty without a usable analysis`() {
        val notReady = TrackAnalysis(status = "", trackId = "track", bpm = 120.0, beatInterval = 0.5)
        assertTrue(HapticScore.of(notReady).isEmpty)

        val noTempo = analysis(bpm = 0.0, interval = 0.5)
        assertTrue(HapticScore.of(noTempo).isEmpty)
    }

    /**
     * The confidence gate exists so a grid that is little better than noise
     * produces silence rather than a random rattle. It sits below the bar the
     * transition planner uses, because vibration only has to be rhythmic.
     */
    @Test
    fun `is empty when the grid is not trustworthy`() {
        assertTrue(HapticScore.of(analysis(confidence = 0.1)).isEmpty)
        assertFalse(HapticScore.of(analysis(confidence = 0.3)).isEmpty)
    }

    /** A corrupt interval must not be walked as if it meant several thousand beats. */
    @Test
    fun `is empty for an implausible interval`() {
        assertTrue(HapticScore.of(analysis(interval = 0.0)).isEmpty)
        assertTrue(HapticScore.of(analysis(interval = 0.01)).isEmpty)
        assertTrue(HapticScore.of(analysis(interval = 9.0)).isEmpty)
        assertTrue(HapticScore.of(analysis(interval = Double.NaN)).isEmpty)
    }

    /**
     * A measured interval carries a little error, and a three-minute track has
     * hundreds of beats for it to accumulate over. Re-anchoring at each downbeat
     * is what bounds that drift to one bar, so the *last* bar of a long grid has
     * to still be on the downbeat rather than visibly late.
     */
    @Test
    fun `re-anchors at downbeats so drift does not accumulate`() {
        // 120bpm nominally, but the bar lines say 0.51s per beat — a real
        // disagreement between a measured average and the grid's own bar.
        val score = HapticScore.of(
            analysis(
                interval = 0.5,
                firstBeat = 0.0,
                contentEnd = 8.16,
                downbeats = listOf(0.0, 2.04, 4.08, 6.12, 8.16),
                low = flat(0.8, 0.0, 10.0),
            ),
        )

        val times = score.pulses.map { it.time }
        // Each downbeat must appear as a strike, not be passed over by a walk
        // that has drifted away from it.
        listOf(2.04, 4.08, 6.12).forEach { anchor ->
            assertTrue(
                "no strike at the $anchor downbeat: ${times.filter { it > anchor - 0.2 && it < anchor + 0.2 }}",
                times.any { kotlin.math.abs(it - anchor) < 0.02 },
            )
        }
        // And no strike may be so close to another that the motor cannot render
        // both.
        times.zipWithNext().forEach { (left, right) ->
            assertTrue("strikes $left and $right are too close", right - left >= 0.09 - 1e-9)
        }
    }

    /** The window query is half-open, so consecutive windows tile without a repeat. */
    @Test
    fun `windows are half open at both ends`() {
        val score = HapticScore.of(analysis(firstBeat = 0.0, contentEnd = 4.0))

        val first = score.pulsesBetween(0.0, 1.0)
        val second = score.pulsesBetween(1.0, 2.0)

        assertEquals(listOf(0.0, 0.5), first.map { it.time })
        assertEquals(listOf(1.0, 1.5), second.map { it.time })
    }

    @Test
    fun `windows return nothing outside the grid`() {
        val score = HapticScore.of(analysis(firstBeat = 2.0, contentEnd = 4.0))

        assertTrue(score.pulsesBetween(0.0, 1.0).isEmpty())
        assertTrue(score.pulsesBetween(10.0, 12.0).isEmpty())
        assertTrue(score.pulsesBetween(2.0, 2.0).isEmpty())
        assertTrue(score.pulsesBetween(3.0, 2.0).isEmpty())
    }

    @Test
    fun `an empty score answers every window with nothing`() {
        assertTrue(HapticScore.EMPTY.pulsesBetween(0.0, 60.0).isEmpty())
        assertTrue(HapticScore.EMPTY.isEmpty)
    }

    /** A quiet-track curve at a fixed level, sampled every `step` seconds. */
    private fun flat(level: Double, from: Double, to: Double, step: Double = 0.25): List<EnergySample> {
        val out = ArrayList<EnergySample>()
        var time = from
        while (time <= to + 1e-9) {
            out.add(EnergySample(time, level))
            time += step
        }
        return out
    }
}
