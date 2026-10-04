// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - the equalizer model.
//
// NAME
//     Equalizer.kt - band layout, presets and preamp computation.
//
// DESCRIPTION
//     The Android build implements its equalizer by hand: seven centres, two of
//     them shelves rather than bells, plus a tone-pad "dynamic" mode that tilts
//     and contours the curve by moving two dials instead of seven sliders. On
//     the desktop the filtering itself is done by libVLC's own ten-band
//     equalizer, which is a better filter than anything worth writing here.
//
//     What has to be reimplemented is everything *around* the filter: the band
//     layout, the preset table, and the preamp. The preamp is the part that
//     actually matters. A graphic equalizer that only boosts will clip, and
//     clipping in a music player is the single worst failure mode there is, so
//     [preampFor] computes exactly the headroom the requested curve spends and
//     takes it back before the bands are applied.
//
// RESPONSIBILITIES
//     - Name the ten ISO centres libVLC's equalizer filters at.
//     - Provide the preset table, in the same spirit as the Android one.
//     - Convert a tone-pad position into a curve.
//     - Compute a preamp that prevents clipping for any curve.
//
// DEPENDENCIES
//     None. This file is pure arithmetic and fully unit-testable.

package com.ihimanshunayak.freemusic.desktop.audio.dsp

import com.ihimanshunayak.freemusic.desktop.data.EQUALIZER_BAND_COUNT
import kotlin.math.abs
import kotlin.math.cos

/**
 * The centre frequency of each band [IsoBand], in hertz.
 *
 * These are the ISO octave centres, matching libVLC's own equalizer, so a preset
 * written for one behaves identically on the other and a curve exported from
 * Free Music means the same thing in VLC.
 */
val ISO_CENTRES_HZ: List<Float> = listOf(
    31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f,
)

/** One band of the graphic equalizer. */
enum class IsoBand(val label: String, val frequencyHz: Float) {
    B31("31 Hz", 31f),
    B62("62 Hz", 62f),
    B125("125 Hz", 125f),
    B250("250 Hz", 250f),
    B500("500 Hz", 500f),
    B1K("1 kHz", 1_000f),
    B2K("2 kHz", 2_000f),
    B4K("4 kHz", 4_000f),
    B8K("8 kHz", 8_000f),
    B16K("16 kHz", 16_000f),
    ;

    companion object {
        val indices: IntRange get() = 0..B16K.ordinal
    }
}

/** How many bands libVLC's equalizer has; the gains array is always this long. */
// `EQUALIZER_BAND_COUNT` lives in the `data` package, next to the settings model
// that sizes its stored band list with it, so the two cannot drift apart.

/**
 * A curve, and whether it should be applied at all.
 *
 * [preampDb] is stored rather than derived on every use because libVLC takes it
 * as a separate value and the UI shows it: seeing that a +6 dB bass boost costs
 * -6 dB of overall level is what makes the trade-off legible, and it is why the
 * control is on screen instead of hidden inside the filter.
 */
data class EqualizerSettings(
    val enabled: Boolean,
    val gainsDb: List<Float>,
    val preampDb: Float,
    val balance: Float = 0f,
) {
    companion object {
        /** The bypass state: no filter is loaded at all when this is current. */
        val Disabled = EqualizerSettings(
            enabled = false,
            gainsDb = List(EQUALIZER_BAND_COUNT) { 0f },
            preampDb = 0f,
        )
    }
}

/**
 * The starting points offered as presets.
 *
 * Deliberately modest - nothing reaches the ±12 dB the sliders allow. A preset
 * is a place to start from, and every decibel of boost is a decibel of headroom
 * the preamp has to take back, so a "loud" preset that costs 10 dB of overall
 * level is a worse preset than a gentle one that costs two.
 *
 * [CUSTOM] carries no gains; it is what the UI reports once a slider has moved,
 * so the row stops naming a preset the bands no longer match.
 */
enum class EqualizerPreset(vararg val bandsDb: Float) {
    FLAT(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    ACOUSTIC(3.5f, 3f, 2f, 0.5f, 1f, 1f, 2f, 2.5f, 2.5f, 1.5f),
    BASS_BOOST(6f, 5f, 3.5f, 1.5f, 0f, 0f, 0f, 0f, 0f, 0f),
    BASS_CUT(-6f, -5f, -3.5f, -1.5f, 0f, 0f, 0f, 0f, 0f, 0f),
    VOCAL(-3.5f, -3f, -1f, 1.5f, 3.5f, 4f, 3f, 1.5f, 0f, -1.5f),
    TREBLE_BOOST(0f, 0f, 0f, 0f, 0f, 0.5f, 1.5f, 3f, 4.5f, 5.5f),
    TREBLE_CUT(0f, 0f, 0f, 0f, 0f, -0.5f, -1.5f, -3f, -4.5f, -5.5f),
    LOUDNESS(6.5f, 5f, 2.5f, 0f, -1.5f, -1f, 0.5f, 2f, 4.5f, 5.5f),
    SPOKEN_WORD(-6f, -4.5f, -2f, 1f, 3.5f, 4f, 3f, 1.5f, 0f, -2.5f),
    ELECTRONIC(5.5f, 4.5f, 2f, 0f, -1f, 0f, 1.5f, 3f, 4f, 4.5f),
    ROCK(5f, 4f, 2f, -1f, -1.5f, 0.5f, 2.5f, 3.5f, 3.5f, 3f),
    HIP_HOP(6f, 5f, 3f, 0.5f, -1f, 0f, 1f, 2f, 2.5f, 2f),
    JAZZ(3.5f, 3f, 1.5f, 1f, 0f, 1f, 1.5f, 2f, 2.5f, 2.5f),
    CLASSICAL(3.5f, 3f, 2f, 1f, 0f, 0f, 1f, 2f, 2.5f, 3f),
    SMALL_SPEAKERS(5.5f, 5f, 4f, 2.5f, 1f, 0f, -1f, -1.5f, -2f, -2.5f),
    LATE_NIGHT(3.5f, 3f, 1.5f, 0.5f, 0f, 0.5f, 1f, 0f, -1.5f, -3.5f),
    PODCAST(-5f, -4f, -1.5f, 2f, 4f, 4.5f, 3.5f, 2f, 0.5f, -1.5f),
    CUSTOM,
    ;

    val bands: List<Float> get() = bandsDb.toList()

    companion object {
        /** How close two gains must be to count as the same preset. */
        private const val TOLERANCE_DB = 0.05f

        /**
         * The preset whose curve these gains are, or [CUSTOM] if they are
         * nobody's.
         *
         * Lets a slider dragged back to where it started stop saying "Custom",
         * and lets a restricted curve name what it restored.
         */
        fun matching(gainsDb: List<Float>): EqualizerPreset = entries.firstOrNull { preset ->
            preset != CUSTOM && preset.bandsDb.size == gainsDb.size &&
                preset.bandsDb.indices.all { abs(preset.bandsDb[it] - gainsDb[it]) < TOLERANCE_DB }
        } ?: CUSTOM

        /** The presets worth showing in a menu, in a sensible reading order. */
        val menuOrder: List<EqualizerPreset> = listOf(
            FLAT, ACOUSTIC, BASS_BOOST, BASS_CUT, VOCAL, TREBLE_BOOST, TREBLE_CUT,
            LOUDNESS, SPOKEN_WORD, PODCAST, ELECTRONIC, ROCK, HIP_HOP, JAZZ,
            CLASSICAL, SMALL_SPEAKERS, LATE_NIGHT,
        )
    }
}

/**
 * The tone pad's two axes, as the Android build describes them.
 *
 * A tone pad is a different instrument from a graphic equalizer: instead of ten
 * numbers it offers tilt (dark to bright) and contour (thin to full), which are
 * the two judgements a listener can actually make about a mix without knowing
 * any frequencies. The conversion below is what makes the pad and the sliders
 * two views of one curve rather than two features.
 */
data class TonePadSetting(
    /** -1 is dark and heavy, +1 is bright and thin. */
    val x: Float,
    /** -1 is thin, +1 is full-bodied. */
    val y: Float,
) {
    /** What the pad's position is called, for the label above it. */
    val label: String
        get() {
            val tilt = when {
                x <= -0.55f -> "Warm"
                x <= -0.2f -> "Slightly warm"
                x < 0.2f -> "Neutral"
                x < 0.55f -> "Slightly bright"
                else -> "Bright"
            }
            val body = when {
                y <= -0.55f -> "thin"
                y <= -0.2f -> "lean"
                y < 0.2f -> "balanced"
                y < 0.55f -> "full"
                else -> "heavy"
            }
            return "$tilt, $body"
        }

    companion object {
        val Neutral = TonePadSetting(0f, 0f)
    }
}

/** How much gain a full sideways move on the tone pad is worth at the extremes. */
private const val TILT_RANGE_DB = 7f

/** How much gain a full vertical move is worth in the bass. */
private const val BODY_RANGE_DB = 5f

/**
 * Turns a tone-pad position into a ten-band curve.
 *
 * The tilt is a straight line from bass to treble: a cosine ramp rather than a
 * linear one, because a linear ramp across ten octaves sounds like a step at
 * each end while a raised cosine sounds like a single move. The contour is a
 * low-shelf plus a gentle dip through the mids, which is what "full" versus
 * "thin" actually means - body lives below 250 Hz and is heard as a *relative*
 * absence when the mids are level.
 */
private fun tiltBasis(band: Int): Float {
    val fraction = band.toFloat() / (EQUALIZER_BAND_COUNT - 1)
    // 0 at the bass end, 1 at the treble end, S-shaped in between.
    val ramp = (1f - cos(fraction * Math.PI)).toFloat() / 2f
    return (ramp * 2f - 1f) * TILT_RANGE_DB
}

/**
 * The gain one band takes when the pad sits fully forward, per decibel of body.
 *
 * Full below 250 Hz and tapered out by 2 kHz, so "full" against "thin" reads as
 * weight rather than as mud. Split out from [curveFor] so that [padFor] fits
 * against precisely the basis the forward transform uses; a second copy of this
 * shape, even an arithmetically identical one, is a thing that can drift.
 */
private fun bodyBasis(band: Int): Float {
    val fraction = band.toFloat() / (EQUALIZER_BAND_COUNT - 1)
    val weight = when {
        fraction <= 0.3f -> 1f
        fraction >= 0.7f -> 0f
        else -> 1f - (fraction - 0.3f) / 0.4f
    }
    return weight * BODY_RANGE_DB
}

/**
 * Turns a tone-pad position into a ten-band curve.
 *
 * The curve is a linear combination of the two basis shapes above, which is what
 * lets [padFor] invert it exactly rather than approximately. The clamp is for
 * curves that did not come from the pad - a hand-dragged one can ask for far more
 * than the pad can produce - and it never binds on a pad position: the two shapes
 * overlap only in the low mids, so even the worst corner (full tilt down, full
 * body up, band 1) reaches exactly the 12 dB limit and no further.
 */
fun curveFor(pad: TonePadSetting): List<Float> {
    val tilt = pad.x.coerceIn(-1f, 1f)
    val body = pad.y.coerceIn(-1f, 1f)

    return List(EQUALIZER_BAND_COUNT) { band ->
        (tiltBasis(band) * tilt + bodyBasis(band) * body).coerceIn(-12f, 12f)
    }
}

/**
 * The inverse of [curveFor]: where the pad sits for a given curve.
 *
 * Solved as a two-parameter least-squares fit rather than by reading the bass and
 * treble averages off the ends, which is what this used to do. The averaging
 * version interpreted a pure body lift as a treble tilt - the bass end rose while
 * the treble end stayed put, and "bass up, treble flat" is indistinguishable from
 * "tilt down" if all you look at is the difference - so picking a bass-heavy
 * preset teleported the pad dot into the bottom-left corner. Fitting against both
 * basis shapes at once attributes the lift to the term that actually caused it.
 *
 * A curve that [curveFor] produced comes back exactly, because it lies in the span
 * of the two basis shapes. A curve that did not - a preset, or hand-dragged bands
 * that no pad position can express - returns the nearest pad position in the
 * least-squares sense, which is the most honest answer available.
 */
fun padFor(gainsDb: List<Float>): TonePadSetting {
    if (gainsDb.size != EQUALIZER_BAND_COUNT) return TonePadSetting.Neutral

    // Normal equations for the fit `gains[i] ~= tilt * tiltBasis(i) + body * bodyBasis(i)`.
    var tiltTilt = 0f
    var tiltBody = 0f
    var bodyBody = 0f
    var tiltGain = 0f
    var bodyGain = 0f
    for (band in gainsDb.indices) {
        val a = tiltBasis(band)
        val b = bodyBasis(band)
        tiltTilt += a * a
        tiltBody += a * b
        bodyBody += b * b
        tiltGain += a * gainsDb[band]
        bodyGain += b * gainsDb[band]
    }

    // The two shapes are independent for any real band count, but a degenerate
    // geometry would divide by zero, and a pad at neutral is the right answer when
    // there is no direction to fit.
    val determinant = tiltTilt * bodyBody - tiltBody * tiltBody
    if (abs(determinant) < 1e-6f) return TonePadSetting.Neutral

    val tilt = ((bodyBody * tiltGain - tiltBody * bodyGain) / determinant).coerceIn(-1f, 1f)
    val body = ((tiltTilt * bodyGain - tiltBody * tiltGain) / determinant).coerceIn(-1f, 1f)
    return TonePadSetting(tilt, body)
}

/**
 * Computes the preamp a curve needs so that it cannot clip.
 *
 * This is the important part of the whole file. Boosting a band raises the level
 * of everything in it, and a curve that boosts more than the signal's headroom
 * allows will clip - which in a music player means audible distortion that no
 * amount of downstream volume control can undo. The safe preamp is the negative
 * of the largest boost, minus a small margin, because a signal already near full
 * scale passes through the boosted band at that much gain.
 *
 * The result is never positive: a curve that only cuts needs no attenuation, and
 * *adding* gain to compensate for a cut would be guessing at the mix's level
 * rather than correcting for it.
 *
 * @param gainsDb the applied curve.
 * @param headroomDb extra margin below the computed limit; 1.5 dB is inaudible
 *   on its own and covers inter-sample peaks that a band's own gain cannot see.
 */
fun preampFor(gainsDb: List<Float>, headroomDb: Float = 1.5f): Float {
    if (gainsDb.isEmpty()) return 0f
    val peak = gainsDb.maxOrNull() ?: 0f
    return if (peak <= 0f) 0f else -(peak + headroomDb)
}

/**
 * A named curve the user saved, so the pad and the sliders can both be recalled.
 */
data class SavedEqualizerCurve(
    val name: String,
    val gainsDb: List<Float>,
    val pad: TonePadSetting,
    val source: String,
)
