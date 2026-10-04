// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - artwork-driven colour.
//
// NAME
//     ArtworkPalette.kt - quantises a cover into the colours a page paints itself in.
//
// DESCRIPTION
//     The Android app tints every album, playlist and artist page from the
//     sleeve. This is the same idea implemented for the desktop build, and it is
//     what makes the Windows app feel like a music player rather than a file
//     browser: opening a record changes the whole window's accent, wash and
//     divider to that record's colours.
//
//     The desktop has no androidx.palette, and pulling one in would be a new
//     dependency for one algorithm, so the quantiser is implemented here. It is
//     a median-cut reducer over a coarse RGB histogram - the same two-stage
//     shape Palette uses - plus the artwork-specific refinements the Android
//     version arrived at through iteration: read the dominant colour with filters
//     *off* so a dark sleeve is not reduced to a surviving highlight, and score
//     the accent on saturation against the square root of population so a
//     four-fifths-black album cover does not accent in black.
//
// RESPONSIBILITIES
//     - Reduce a bitmap to a small set of representative swatches.
//     - Derive the dominant, vibrant and edge colours.
//     - Map those onto a dark-mode or light-mode [ArtworkPalette].
//     - Cache the result per artwork URL, since a URL's artwork cannot change.
//
// DEPENDENCIES
//     - Compose `ImageBitmap` for input; the caller supplies the decoded cover.
//
// INTEGRATION NOTES
//     - `AnimatedArtworkPalette` crossfades between sleeves so a track change is
//       a colour transition rather than a flash.
//     - Neutral artwork stays neutral: see [adaptedArtworkSaturation] for why
//       raising a grey to a saturation floor is the wrong move.

package com.ihimanshunayak.freemusic.desktop.data.palette

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.ihimanshunayak.freemusic.desktop.ui.ImageLoading
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The colours an album, playlist or artist paints itself in.
 *
 * Every value is theme-aware. The same sleeve yields a near-black tint in dark
 * mode and a pale wash of the same hue in light mode, which is the only way the
 * screens stay readable when the app's theme disagrees with the artwork's.
 */
@Immutable
data class ArtworkPalette(
    /** The page's background wash. */
    val background: Color,
    /**
     * The colour the artwork's own bottom edge blurs down to.
     *
     * A blur wide enough to lose the picture leaves the mean of what it
     * sampled, so a page that starts from this colour where the artwork stops
     * reads as that blur carrying on rather than as a second surface beginning.
     */
    val wash: Color,
    /** Fill for the raised surfaces that sit on [background]. */
    val elevated: Color,
    /** The artwork's own colour, contrast-corrected — titles, icons, focal points. */
    val accent: Color,
    val onBackground: Color,
    val onBackgroundVariant: Color,
    val divider: Color,
    /** Relative luminance of the artwork's top band, or null when unknown. */
    val topBandLuminance: Float? = null,
) {
    companion object {
        /**
         * The palette to use before any artwork has been read.
         *
         * Built from the current theme rather than from artwork so a screen has
         * something coherent to draw on its very first frame.
         */
        fun of(
            background: Color,
            elevated: Color,
            accent: Color,
            onBackground: Color,
            onBackgroundVariant: Color,
            divider: Color,
        ) = ArtworkPalette(
            background = background,
            wash = background,
            elevated = elevated,
            accent = accent,
            onBackground = onBackground,
            onBackgroundVariant = onBackgroundVariant,
            divider = divider,
        )
    }
}

/** Reading colours again costs a decode for an answer that cannot have changed. */
private val seedCache = object : LinkedHashMap<String, Seed>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, Seed>) = size > SEED_CACHE_ENTRIES
}

/** Deep enough to cover a session's browsing without holding a screenful of colours. */
private const val SEED_CACHE_ENTRIES = 128

/** Short: this is a surface settling into its colour, not an effect in itself. */
private const val TINT_FADE_MS = 260

/** Swatches the histogram is reduced to. 24 matches the Android implementation. */
private const val SWATCH_COUNT = 24

/** How much of the artwork's height the edge colour is read from. */
private const val EDGE_BAND = 0.18f

/**
 * The upper sliver of a full-bleed hero, where a caption sits over the artwork.
 * Kept tight so faces lower in the cover do not decide the colour for pixels
 * that are never behind the text.
 */
private const val TOP_BAND = 0.10f

/** Below this, boosting saturation makes quantisation noise visible as a tint. */
private const val CHROMATIC_SATURATION_THRESHOLD = 0.12f

/**
 * The raw artwork colours: what the page is mostly made of, its brightest note,
 * what its bottom edge averages out to, and how bright its top band is.
 */
internal data class Seed(
    val dominant: Color,
    val vibrant: Color,
    val edge: Color,
    val topBandLuminance: Float,
)

/** One representative colour and how many pixels it stands for. */
internal data class Swatch(
    val red: Int,
    val green: Int,
    val blue: Int,
    val population: Int,
) {
    val argb: Int get() = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}

/**
 * Reduces a cover to a small set of representative colours.
 *
 * Histogram first, then median cut. Quantising to 5 bits per channel before
 * cutting keeps the histogram at a bounded size regardless of resolution, which
 * matters because this runs for every album the user browses past.
 */
internal fun quantise(pixels: IntArray, requested: Int = SWATCH_COUNT): List<Swatch> {
    if (pixels.isEmpty()) return emptyList()

    // 5 bits per channel: 32768 buckets, sparse map so memory follows variety
    // rather than the fixed bucket count.
    val histogram = HashMap<Int, Int>(4096)
    for (pixel in pixels) {
        val alpha = (pixel ushr 24) and 0xFF
        // Fully transparent pixels carry no colour information.
        if (alpha < 128) continue
        val reduced = (((pixel ushr 19) and 0x1F) shl 10) or
            (((pixel ushr 11) and 0x1F) shl 5) or
            ((pixel ushr 3) and 0x1F)
        histogram[reduced] = (histogram[reduced] ?: 0) + 1
    }
    if (histogram.isEmpty()) return emptyList()

    data class Box(val entries: List<Map.Entry<Int, Int>>) {
        val population: Int get() = entries.sumOf { it.value }
    }

    fun widen(bucket: Int, shift: Int): Int = ((bucket shr shift) and 0x1F) shl 3 or (0x1F shr 1)

    var boxes = listOf(Box(histogram.entries.toList()))

    // Split the widest-range box repeatedly. Median cut, not mean cut: splitting
    // on the median population keeps the boxes balanced, which is what stops a
    // large flat background from consuming every swatch.
    while (boxes.size < requested) {
        val target = boxes.maxByOrNull { box ->
            if (box.entries.size < 2) -1 else {
                val reds = box.entries.map { widen(it.key, 10) }
                val greens = box.entries.map { widen(it.key, 5) }
                val blues = box.entries.map { widen(it.key, 0) }
                maxOf(
                    reds.max() - reds.min(),
                    greens.max() - greens.min(),
                    blues.max() - blues.min(),
                )
            }
        } ?: break

        if (target.entries.size < 2) break

        val channelScores = (0..2).map { channel ->
            val values = target.entries.map { widen(it.key, 10 - channel * 5) }
            values.max() - values.min()
        }
        val channel = channelScores.indices.maxByOrNull { channelScores[it] } ?: 0
        val sorted = target.entries.sortedBy { widen(it.key, 10 - channel * 5) }
        val half = target.population / 2

        var running = 0
        var splitIndex = 0
        for ((index, entry) in sorted.withIndex()) {
            running += entry.value
            if (running >= half) {
                splitIndex = index
                break
            }
        }
        // Both halves must be non-empty or the loop never terminates.
        splitIndex = splitIndex.coerceIn(1, sorted.size - 1).takeIf { sorted.size > 1 } ?: break

        boxes = boxes.filter { it !== target } +
            Box(sorted.subList(0, splitIndex)) +
            Box(sorted.subList(splitIndex, sorted.size))
    }

    return boxes.mapNotNull { box ->
        if (box.entries.isEmpty()) return@mapNotNull null
        var red = 0L
        var green = 0L
        var blue = 0L
        var population = 0
        for ((bucket, count) in box.entries) {
            red += widen(bucket, 10).toLong() * count
            green += widen(bucket, 5).toLong() * count
            blue += widen(bucket, 0).toLong() * count
            population += count
        }
        if (population == 0) null
        else Swatch(
            red = (red / population).toInt().coerceIn(0, 255),
            green = (green / population).toInt().coerceIn(0, 255),
            blue = (blue / population).toInt().coerceIn(0, 255),
            population = population,
        )
    }
}

/** Reads the seed colours out of a decoded cover. */
internal fun seedOf(image: ImageBitmap): Seed? {
    val width = image.width
    val height = image.height
    if (width <= 0 || height <= 0) return null

    val pixels = IntArray(width * height)
    image.readPixels(pixels, 0, 0, width, height, 0, width)

    val swatches = quantise(pixels)
    if (swatches.isEmpty()) return null

    // The accent has to earn its place twice over: a colour nobody sees enough
    // of reads as arbitrary, and a grey one is not an accent at all. Scoring on
    // saturation against the *square root* of population is what stops a sleeve
    // that is four-fifths black sky from accenting in black.
    val vibrant = swatches.maxBy { swatch ->
        val hsl = hslOf(swatch.red, swatch.green, swatch.blue)
        hsl[1] * sqrt(swatch.population.toFloat())
    }
    val dominant = swatches.maxBy { it.population }

    return Seed(
        dominant = Color(dominant.red, dominant.green, dominant.blue),
        vibrant = Color(vibrant.red, vibrant.green, vibrant.blue),
        edge = bottomEdgeColor(pixels, width, height),
        topBandLuminance = averageRelativeLuminance(topBandPixels(pixels, width, height)),
    )
}

/** The pixel indices of the artwork's top band. */
private fun topBandPixels(pixels: IntArray, width: Int, height: Int): IntArray {
    val band = (height * TOP_BAND).toInt().coerceIn(1, height)
    return pixels.copyOfRange(0, width * band)
}

/**
 * The mean of the artwork's bottom band — what a blur wide enough to lose the
 * picture leaves behind at that edge.
 *
 * A flat mean rather than a quantised swatch on purpose: a blur has no notion
 * of which colour is *important*, and the page under the artwork has to match
 * what the blur actually produced, not what the picture is about.
 */
private fun bottomEdgeColor(pixels: IntArray, width: Int, height: Int): Color {
    val band = (height * EDGE_BAND).toInt().coerceIn(1, height)
    val start = width * (height - band)

    var red = 0L
    var green = 0L
    var blue = 0L
    var count = 0
    for (index in start until pixels.size) {
        val pixel = pixels[index]
        red += (pixel shr 16) and 0xFF
        green += (pixel shr 8) and 0xFF
        blue += pixel and 0xFF
        count++
    }
    if (count == 0) return Color.Black
    return Color(
        red = (red / count).toInt(),
        green = (green / count).toInt(),
        blue = (blue / count).toInt(),
    )
}

/** WCAG relative luminance: average in linear light, never gamma-encoded RGB. */
internal fun averageRelativeLuminance(pixels: IntArray): Float {
    if (pixels.isEmpty()) return 0f
    return pixels.sumOf { relativeLuminance(it).toDouble() }.div(pixels.size).toFloat()
}

internal fun relativeLuminance(argb: Int): Float {
    fun linear(channel: Int): Float {
        val srgb = channel / 255f
        return if (srgb <= 0.04045f) {
            srgb / 12.92f
        } else {
            ((srgb + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        }
    }
    return 0.2126f * linear((argb shr 16) and 0xFF) +
        0.7152f * linear((argb shr 8) and 0xFF) +
        0.0722f * linear(argb and 0xFF)
}

/** Perceived brightness of a colour, used only to tell a dark theme from a light one. */
fun Color.relativeLuminance(): Float = relativeLuminance(toArgb())

/** RGB (0..255) to HSL with hue in degrees, saturation and lightness in 0..1. */
internal fun hslOf(red: Int, green: Int, blue: Int): FloatArray {
    val r = red / 255f
    val g = green / 255f
    val b = blue / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val lightness = (max + min) / 2f
    if (max == min) return floatArrayOf(0f, 0f, lightness)

    val delta = max - min
    val saturation = if (lightness > 0.5f) delta / (2f - max - min) else delta / (max + min)
    val hue = when (max) {
        r -> ((g - b) / delta + if (g < b) 6f else 0f)
        g -> ((b - r) / delta + 2f)
        else -> ((r - g) / delta + 4f)
    } * 60f
    return floatArrayOf(hue, saturation, lightness)
}

/** HSL back to an ARGB colour. Hue in degrees, saturation and lightness in 0..1. */
internal fun hslToArgb(hue: Float, saturation: Float, lightness: Float): Int {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)

    val chroma = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val x = chroma * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = l - chroma / 2f

    val (r, g, b) = when {
        h < 60f -> Triple(chroma, x, 0f)
        h < 120f -> Triple(x, chroma, 0f)
        h < 180f -> Triple(0f, chroma, x)
        h < 240f -> Triple(0f, x, chroma)
        h < 300f -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    fun channel(value: Float) = ((value + m) * 255f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}

/**
 * Keeps neutral artwork neutral instead of inventing a hue for it.
 *
 * HSL represents grey with hue zero. Raising that grey to a saturation floor
 * therefore does not make it "more colourful"; it manufactures red, which
 * becomes brown once the page lightness is lowered. A small real amount of
 * colour is kept as-is, while an unmistakably chromatic swatch can still be
 * strengthened enough to make controls legible and the page recognisable.
 */
internal fun adaptedArtworkSaturation(source: Float, minimum: Float, maximum: Float): Float {
    val saturation = source.coerceIn(0f, 1f)
    return if (saturation < CHROMATIC_SATURATION_THRESHOLD) {
        saturation
    } else {
        saturation.coerceIn(minimum, maximum)
    }
}

/** Rewrites a colour's saturation and lightness while keeping its hue. */
private fun Color.withHsl(
    saturation: (Float) -> Float = { it },
    lightness: (Float) -> Float = { it },
): Color {
    val hsl = hslOf(
        (toArgb() shr 16) and 0xFF,
        (toArgb() shr 8) and 0xFF,
        toArgb() and 0xFF,
    )
    return Color(
        hslToArgb(
            hue = hsl[0],
            saturation = saturation(hsl[1]).coerceIn(0f, 1f),
            lightness = lightness(hsl[2]).coerceIn(0f, 1f),
        )
    )
}

/**
 * Maps the raw colours onto the finished set a page paints with.
 *
 * Dark mode pushes everything deep enough that white body text clears contrast
 * on any sleeve, without dropping the hue — the whole point is that the page is
 * recognisably *this* record's colour. Light mode inverts the relationship and
 * keeps the tint pale enough to read black text on.
 */
internal fun Seed.toPalette(dark: Boolean): ArtworkPalette = if (dark) {
    ArtworkPalette(
        background = dominant.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.20f, maximum = 0.62f) },
            lightness = { 0.13f },
        ),
        // Follows the edge's own brightness within a band that stays clear of
        // white body text at the top and of the background at the bottom.
        wash = edge.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.18f, maximum = 0.58f) },
            lightness = { it.coerceIn(0.14f, 0.24f) },
        ),
        elevated = dominant.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.20f, maximum = 0.62f) },
            lightness = { 0.22f },
        ),
        accent = vibrant.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.55f, maximum = 1f) },
            lightness = { it.coerceIn(0.62f, 0.78f) },
        ),
        onBackground = Color.White,
        // Well above the grey the untinted screens use for secondary text. A
        // tint is a *coloured* background, not a black one, so the contrast a
        // dim grey has against black is not the contrast it has here.
        onBackgroundVariant = Color.White.copy(alpha = 0.80f),
        divider = Color.White.copy(alpha = 0.12f),
        topBandLuminance = topBandLuminance,
    )
} else {
    ArtworkPalette(
        background = dominant.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.14f, maximum = 0.50f) },
            lightness = { 0.91f },
        ),
        wash = edge.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.12f, maximum = 0.46f) },
            lightness = { it.coerceIn(0.78f, 0.90f) },
        ),
        elevated = dominant.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.14f, maximum = 0.50f) },
            lightness = { 0.83f },
        ),
        accent = vibrant.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.55f, maximum = 1f) },
            lightness = { it.coerceIn(0.30f, 0.44f) },
        ),
        onBackground = Color.Black,
        onBackgroundVariant = Color.Black.copy(alpha = 0.70f),
        divider = Color.Black.copy(alpha = 0.10f),
        topBandLuminance = topBandLuminance,
    )
}

/** The palette's raw colours for a URL, or null while it has not been read yet. */
fun peekPalette(imageUrl: String?, dark: Boolean): ArtworkPalette? =
    imageUrl?.let(seedCache::get)?.toPalette(dark)

/**
 * Reads [imageUrl] and animates the theme into its colours.
 *
 * Artwork already read once is tinted on the very first frame, off [seedCache] -
 * a screen opened from a page it shares a cover with, or a page opened twice,
 * has nothing to wait for and nothing to fade. Only a sleeve genuinely being
 * seen for the first time starts from [fallback] and warms into the artwork's,
 * so it never flashes a placeholder tint.
 *
 * @param imageUrl the artwork to tint from; null keeps the fallback.
 * @param dark which theme the palette is built for.
 * @param fallback the colours to use before the artwork resolves.
 * @param reduceAnimation when true the tint cuts instead of fading.
 */
@Composable
fun animatedArtworkPalette(
    imageUrl: String?,
    dark: Boolean,
    fallback: ArtworkPalette,
    reduceAnimation: Boolean = false,
): ArtworkPalette {
    val cached = remember(imageUrl, dark) { peekPalette(imageUrl, dark) }
    var resolved by remember(imageUrl, dark) { mutableStateOf(cached) }

    LaunchedEffect(imageUrl, dark) {
        if (imageUrl == null || resolved != null) return@LaunchedEffect
        val bitmap = ImageLoading.loadBlocking(imageUrl) ?: return@LaunchedEffect
        val seed = seedOf(bitmap)?.takeIf { it.dominant != Color.Unspecified } ?: return@LaunchedEffect
        seedCache[imageUrl] = seed
        resolved = seed.toPalette(dark)
    }

    val target = resolved ?: fallback
    // `knownUpFront` distinguishes "already had the colours" from "just reached
    // them": when they were there from the first frame there is nothing to
    // crossfade from, and animating would only delay a surface that is already
    // correct.
    val knownUpFront = cached != null
    val spec = if (reduceAnimation || knownUpFront) snap<Color>() else tween(TINT_FADE_MS)

    return ArtworkPalette(
        background = animateColorAsState(target.background, spec, label = "tintBackground").value,
        wash = animateColorAsState(target.wash, spec, label = "tintWash").value,
        elevated = animateColorAsState(target.elevated, spec, label = "tintElevated").value,
        accent = animateColorAsState(target.accent, spec, label = "tintAccent").value,
        onBackground = animateColorAsState(target.onBackground, spec, label = "tintOn").value,
        onBackgroundVariant = animateColorAsState(
            target.onBackgroundVariant,
            spec,
            label = "tintOnVariant",
        ).value,
        divider = animateColorAsState(target.divider, spec, label = "tintDivider").value,
        topBandLuminance = target.topBandLuminance,
    )
}

/**
 * Maps artwork luminance to overlay opacity, keeping a caption's icons legible
 * over a light cover while staying subtle on a dark one.
 */
fun topBandScrimAlpha(artworkLuminance: Float?): Float {
    val luminance = artworkLuminance?.coerceIn(0f, 1f) ?: 0f
    return PLAYER_STATUS_SCRIM_MIN_ALPHA +
        (PLAYER_STATUS_SCRIM_MAX_ALPHA - PLAYER_STATUS_SCRIM_MIN_ALPHA) * luminance
}

private const val PLAYER_STATUS_SCRIM_MIN_ALPHA = 0.16f
private const val PLAYER_STATUS_SCRIM_MAX_ALPHA = 0.65f
