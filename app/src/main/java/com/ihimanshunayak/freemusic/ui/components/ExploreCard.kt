// Copyright (c) A|iens. All rights reserved.
// AliensStyle code-header — full structured block below.
//
// Name        : ExploreCard.kt
// Version     : 1.1.0
// Author      : A'nil Cyborg
// Author URI  : https://github.com/ihimanshunayak
// Description : One card in the Explore "liquid carousel". Renders a
//               full-bleed artwork with a dynamic-palette wash, a
//               liquid-glass surface (when supported), the section
//               eyebrow, a 2-line title, a 1-line subtitle, two tag
//               chips, and the Play button overlay. Layout-time
//               transforms (scale / rotationY / alpha / translation)
//               are owned by the host carousel via
//               `Modifier.graphicsLayer`; this composable is a pure
//               visual.
// Requires    : Compose foundation 1.7+, Coil 3.0+, FreeMusic
//               ArtworkPalette, FreeMusic LiquidGlass.
// Responsibilities:
//   - Paint the artwork, wash, glass surface, eyebrow, title, subtitle,
//     tag chips, and the corner Play button.
//   - Honour the per-card dynamic palette from the artwork URL, with a
//     hash-based fallback so the first frame is never blank.
//   - Adapt to depthProgress (1 = active, 0 = about to leave) and
//     peekProgress (0 = center, +1 = right neighbor, -1 = left
//     neighbor) so the host can fade overlays gracefully.
// Dependencies:
//   - ArtworkPalette (ui.theme.ArtworkPalette)
//   - LiquidGlass (ui.components.LiquidGlass) for the glass surface
//   - MoodGenre (data.model.Models) for the title and browseId
//   - AsyncImage (coil3.compose) for artwork loading
// Integration Notes:
//   - Designed to be placed inside a graphicsLayer { rotationY = ... }
//     host. Do not apply Modifier.clipToBounds() to this composable;
//     the carousel host handles clipping at the page level.
//   - Title and eyebrow respect MaterialTheme.typography so theme-switch
//     automatically picks up displayLarge vs titleLarge vs labelLarge.
//   - Reduced-motion: depth / peek transforms are still applied, but
//     the rotation tween becomes 0 ms when Settings.Global's animator
//     scale is 0 (handled in the host).

package com.ihimanshunayak.freemusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ihimanshunayak.freemusic.data.model.MoodGenre
import com.ihimanshunayak.freemusic.ui.theme.rememberArtworkPalette

/**
 * The shape of the card surface used by the Explore carousel.
 *
 * 28 dp corner radius — wide enough to read as a "tile" at
 * `width = 0.72 * pageWidth`, tight enough to look intentional on
 * tablets (`width = 0.42 * pageWidth`, 600 dp+).
 */
private val CardShape = RoundedCornerShape(28.dp)

/**
 * Which way the front card of the Explore deck is being swiped.
 *
 * The two directions mean different things, and the card says which one
 * is about to happen while the finger is still down: right opens the
 * mood, left skips it. Without the badge the two directions would be
 * indistinguishable until the card had already gone.
 */
enum class SwipeAction {

    /** Swiped right — the card will open. */
    Open,

    /** Swiped left — the card will be skipped. */
    Skip,
}

/**
 * A rich-content card in the Explore carousel. Renders a full-bleed
 * artwork with a dynamic-palette wash, a liquid-glass surface (when
 * supported), the section eyebrow, a 2-line title, a 1-line
 * subtitle, two tag chips, and the corner Play button.
 *
 * @param item            the mood / genre being shown.
 * @param sectionTitle    the section this card came from; only
 *                        rendered when the host is in the front row
 *                        (depthProgress ≈ 1) so the eyebrow doesn't
 *                        ghost-stack across peeks.
 * @param depthProgress   1 = active, 0 = about to leave. Drives the
 *                        alpha / scale of the corner Play button and
 *                        the tag-chip row.
 * @param isActive        true iff this card is the front-most. Gates
 *                        click handlers, the play-button visibility, and
 *                        whether the card draws a backdrop-sampled glass
 *                        surface — only the card being looked at samples.
 * @param swipeAction     which way the card is being swiped, if at all.
 *                        Drives the Open / Skip badge on the card.
 * @param swipeProgress   0 to 1, how far into the swipe the card is.
 *                        Drives the badge's opacity and scale.
 * @param onClick         invoked when the user taps the card body.
 * @param onPlayClick     invoked when the user taps the corner Play
 *                        button.
 */
@Composable
fun ExploreCard(
    item: MoodGenre,
    sectionTitle: String?,
    depthProgress: Float,
    isActive: Boolean,
    swipeAction: SwipeAction? = null,
    swipeProgress: Float = 0f,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = rememberArtworkPalette(imageUrl = item.thumbnailUrl)
    val fallback = moodColorForCard(item.title)
    val wash = if (palette.background.alpha > 0.01f) palette.background else fallback
    val washDeep = if (palette.accent.alpha > 0.01f) palette.accent else fallback.copy(alpha = 0.85f)
    val onWash = if (palette.onBackground.luminanceOrWhite() > 0.55f) Color.Black else Color.White
    val subtitle = subtitleForSection(sectionTitle)
    val tags = tagsForSection(sectionTitle)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clip(CardShape)
            .drawBehind {
                // Outer shadow ring drawn as a 2-px stroke so the GPU
                // doesn't have to allocate a separate offscreen buffer
                // per card (a real Modifier.shadow would).
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.30f),
                    topLeft = Offset(2f, 6f),
                    size = Size(size.width - 4f, size.height - 6f),
                    cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx()),
                )
            }
            // Dynamic-palette wash — paints *under* the glass surface,
            // so the wash is the fallback for devices where the glass
            // effect isn't available (Android < 12) and for the peek
            // cards, which are flat by design.
            .background(
                Brush.verticalGradient(
                    listOf(washDeep, wash, wash.copy(alpha = 0.65f)),
                    startY = 0f,
                    endY = Float.POSITIVE_INFINITY,
                ),
            )
            // The card's own rim. It is drawn rather than sampled.
            //
            // A backdrop-sampled surface is not an option here, and not
            // because of cost: the app records the whole page into one
            // layer for the glass bars to sample, and this card is *inside*
            // that recorded page. A surface that reads the layer it is
            // itself recorded into makes the render tree reference itself,
            // and Android's render-node tree walks that cycle until the
            // thread runs out of stack — the app dies with a SIGSEGV in
            // the render thread the moment the Explore tab is opened.
            //
            // The card does not need a sampled backdrop in any case: the
            // artwork is painted full-bleed over this surface, so a
            // backdrop read would be hidden by the very next draw. The
            // rim, the directional highlight and the translucent tint are
            // the parts of the glass that read at this size, and
            // [lightweightLiquidGlass] is exactly those three.
            .lightweightLiquidGlass(
                shape = CardShape,
                fallbackColor = Color.Transparent,
            )
            // The card is tipped by the host's gesture layer, not here: the
            // host owns the drag and the travel that comes with it, and a
            // second rotation here would fight it.
            .clickable(
                onClick = onClick,
                onClickLabel = "Open ${item.title}",
                enabled = isActive,
            ),
    ) {
        // Full-bleed artwork
        item.thumbnailUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.78f + 0.22f * depthProgress.coerceIn(0f, 1f) },
            )
        }

        // Top-down + bottom-up dark gradient so the eyebrow + title
        // and the tag chips stay readable even over very light
        // artwork.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.55f * (1f - depthProgress.coerceIn(0f, 1f))),
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.55f),
                    ),
                ),
        )

        // Section eyebrow (fades in on active / first-row cards)
        if (sectionTitle != null) {
            Text(
                text = sectionTitle.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = onWash.copy(alpha = 0.85f * depthProgress.coerceIn(0f, 1f)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 22.dp, top = 22.dp, end = 22.dp),
            )
        }

        // Title + subtitle + tag chips, anchored bottom-start
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 22.dp, end = 22.dp, bottom = 22.dp)
                .fillMaxWidth(),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.displaySmall,
                color = onWash,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.titleMedium,
                color = onWash.copy(alpha = 0.80f * depthProgress.coerceIn(0f, 1f)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { tag ->
                    TagChip(text = tag, onWash = onWash, depth = depthProgress)
                }
            }
        }

        // Corner Play button (fades + scales out for non-active cards)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 18.dp, end = 18.dp)
                .size(48.dp)
                .graphicsLayer {
                    val dp = depthProgress.coerceIn(0f, 1f)
                    scaleX = 0.7f + 0.3f * dp
                    scaleY = 0.7f + 0.3f * dp
                    this.alpha = dp
                }
                .clip(CircleShape)
                .background(onWash)
                .clickable(enabled = isActive, onClick = onPlayClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Play ${item.title}",
                tint = washDeep,
                modifier = Modifier.size(28.dp),
            )
        }

        // The swipe badge: what is about to happen if the finger lets go
        // now. It sits on the side the card is travelling towards and
        // grows as the gesture approaches the point of no return.
        if (isActive && swipeAction != null && swipeProgress > 0f) {
            SwipeBadge(
                action = swipeAction,
                progress = swipeProgress,
                modifier = Modifier
                    .align(if (swipeAction == SwipeAction.Open) Alignment.TopStart else Alignment.TopEnd)
                    .padding(
                        start = if (swipeAction == SwipeAction.Open) 22.dp else 0.dp,
                        end = if (swipeAction == SwipeAction.Skip) 22.dp else 0.dp,
                        top = 26.dp,
                    ),
            )
        }
    }
}

/**
 * The badge that says what a swipe will do, drawn on the card while the
 * finger is down.
 *
 * Two things make it read: it is a glass surface rather than a flat fill,
 * so it belongs to the same material as the rest of the app; and it is
 * stamped at an angle, which is the convention every swipe deck already
 * uses and which the eye reads before it reads the word.
 */
@Composable
private fun SwipeBadge(
    action: SwipeAction,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val label = when (action) {
        SwipeAction.Open -> "OPEN"
        SwipeAction.Skip -> "SKIP"
    }
    val tint = when (action) {
        SwipeAction.Open -> Color(0xFF4ADE80)
        SwipeAction.Skip -> Color(0xFFF87171)
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .graphicsLayer {
                // The badge grows into the gesture and turns with it, so
                // it reads as stamped on the card rather than laid over it.
                val eased = progress.coerceIn(0f, 1f)
                scaleX = 0.70f + 0.30f * eased
                scaleY = 0.70f + 0.30f * eased
                rotationZ = if (action == SwipeAction.Open) -14f else 14f
                this.alpha = eased.coerceIn(0f, 1f)
            }
            .lightweightLiquidGlass(
                shape = shape,
                fallbackColor = tint.copy(alpha = 0.22f),
            )
            .border(2.dp, tint.copy(alpha = 0.85f), shape)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = tint,
            fontWeight = FontWeight.ExtraBold,
            // Wide-tracked capitals, the way a stamp is set.
            letterSpacing = 2.sp,
        )
    }
}

/**
 * A single tag chip shown on the active card. Renders a low-opacity
 * glass-like pill with the tag label inside. `depth` drives the alpha
 * so the chips fade out gracefully as the card peeks.
 */
@Composable
private fun TagChip(text: String, onWash: Color, depth: Float) {
    val alpha = (0.18f + 0.22f * depth.coerceIn(0f, 1f)).coerceIn(0f, 0.45f)
    Box(
        modifier = Modifier
            .background(
                color = onWash.copy(alpha = alpha),
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = onWash,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Map the section title to a short subtitle for the card body.
 * Falls back to a neutral phrase when the section isn't one we have
 * a richer copy for.
 */
private fun subtitleForSection(sectionTitle: String?): String = when (sectionTitle?.lowercase()) {
    "moods" -> "Mood collection"
    "genres" -> "Genre deep-dive"
    "trending" -> "Trending now"
    "new releases" -> "Fresh on FreeMusic"
    "top charts" -> "What everyone's playing"
    "workout" -> "Push harder"
    "focus" -> "Stay in the zone"
    "chill" -> "Wind down"
    "party" -> "Get the room moving"
    "sleep" -> "Drift off"
    "romance" -> "Slow dance"
    "commute" -> "For the road"
    else -> "Explore on FreeMusic"
}

/**
 * Two tag chips drawn under the subtitle. The first is always
 * `PLAYLIST` (FreeMusic's taxonomy); the second varies by section
 * to give a bit of curated feel without inventing numbers.
 */
private fun tagsForSection(sectionTitle: String?): List<String> {
    val second = when (sectionTitle?.lowercase()) {
        "moods", "chill", "sleep", "focus", "workout", "party", "romance", "commute" -> "CURATED"
        "genres" -> "MIX"
        "trending" -> "HOT"
        "new releases" -> "NEW"
        "top charts" -> "TOP"
        else -> "FREE MUSIC"
    }
    return listOf("PLAYLIST", second)
}

/**
 * Hash-based fallback palette so the first frame always has a color
 * even before artwork loads. Same hue families the old grid used.
 */
private fun moodColorForCard(title: String): Color = when ((title.hashCode() and Int.MAX_VALUE) % 8) {
    0 -> Color(0xFFE64A19)
    1 -> Color(0xFFEC0B65)
    2 -> Color(0xFF8664AC)
    3 -> Color(0xFF6B4EFF)
    4 -> Color(0xFFBE6100)
    5 -> Color(0xFF233C78)
    6 -> Color(0xFF4D97E5)
    else -> Color(0xFFAA267E)
}

private fun Color.luminanceOrWhite(): Float = try {
    // Best-effort luminance without depending on androidx.compose.ui.graphics.luminance
    // being imported in this file (the extension exists in newer Compose but we
    // keep our own copy here for safety).
    0.2126f * red + 0.7152f * green + 0.0722f * blue
} catch (_: Throwable) {
    1f
}
