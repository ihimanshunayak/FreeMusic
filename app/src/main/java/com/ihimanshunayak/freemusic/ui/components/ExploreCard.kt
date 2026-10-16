// Copyright (c) A|iens. All rights reserved.
// AliensStyle code-header — full structured block below.
//
// Name        : ExploreCard.kt
// Version     : 1.0.0
// Author      : A'nil Cyborg
// Author URI  : https://github.com/ihimanshunayak
// Description : One stacked-card surface used by ExploreCardStack. Renders
//               full-bleed artwork with a mood-color wash, a dynamic-palette
//               shadow, a title pill, an optional section eyebrow, and a
//               corner play button. All transform animations are applied
//               by the host via Modifier.graphicsLayer — this composable
//               is intentionally a pure visual; no scroll, no gesture.
// Requires    : Compose foundation 1.7+, Coil 3.0+, FreeMusic ArtworkPalette.
// Responsibilities:
//   - Paint the artwork / wash / shadow / typography / play button.
//   - Honour the per-card dynamic palette from the artwork URL.
//   - Fall back to the existing moodColor() hash palette if no URL is
//     available yet (so first-frame paint is never blank).
// Dependencies:
//   - ArtworkPalette (ui.theme.ArtworkPalette) for the wash / shadow colors.
//   - MoodGenre (data.model.Models) for the title and browseId.
//   - AsyncImage (coil3.compose) for artwork loading.
// Integration Notes:
//   - Designed to be placed inside a graphicsLayer { translationY = ... }
//     host. Do not apply Modifier.clipToBounds() to this composable; the
//     stack host handles the clipping at the page level.
//   - Title and eyebrow respect MaterialTheme.typography so theme-switch
//     automatically picks up displayLarge vs titleLarge vs labelLarge.

package com.ihimanshunayak.freemusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ihimanshunayak.freemusic.data.model.MoodGenre
import com.ihimanshunayak.freemusic.ui.theme.rememberArtworkPalette

/**
 * One stacked-card surface. All layout-time transforms are owned by the
 * stack host; this composable only paints. `depthProgress` is the host's
 * normalized 0..1 indicator of "how in front" this card is
 * (1 = front-most, 0 = about to leave, -1 = below), used to fade the
 * corner play button and the eyebrow in/out gracefully.
 */
@Composable
fun ExploreCard(
    item: MoodGenre,
    sectionTitle: String?,
    depthProgress: Float,
    isActive: Boolean,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = rememberArtworkPalette(imageUrl = item.thumbnailUrl)
    val fallback = moodColorForCard(item.title)
    val wash = if (palette.background.alpha > 0.01f) palette.background else fallback
    val washDeep = if (palette.accent.alpha > 0.01f) palette.accent else fallback.copy(alpha = 0.85f)
    val onWash = if (palette.onBackground.luminanceOrWhite() > 0.55f) Color.Black else Color.White

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(22.dp))
            .drawBehind {
                // Outer shadow (a single blurred-edge ring drawn as a
                // 2-px stroke) — keeps the card off the page surface
                // without invoking the heavier Modifier.shadow path.
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.30f),
                    topLeft = Offset(2f, 6f),
                    size = Size(size.width - 4f, size.height - 6f),
                    cornerRadius = CornerRadius(22.dp.toPx(), 22.dp.toPx()),
                )
            }
            .background(
                Brush.verticalGradient(
                    listOf(washDeep, wash, wash.copy(alpha = 0.65f)),
                    startY = 0f,
                    endY = Float.POSITIVE_INFINITY,
                ),
            )
            .clickable(
                onClick = onClick,
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
                    .graphicsLayer { alpha = 0.85f + 0.15f * depthProgress.coerceIn(0f, 1f) },
            )
        }

        // Top-down dark gradient so the eyebrow + title stay readable
        // even over very light artwork.
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

        // Section eyebrow (fades in on active cards)
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

        // Title + play action row, anchored bottom-start
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 22.dp, end = 22.dp, bottom = 26.dp)
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
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(onWash)
                        .clickable(enabled = isActive, onClick = onPlayClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Play",
                        tint = washDeep,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    text = "Tap card to explore",
                    style = MaterialTheme.typography.labelLarge,
                    color = onWash.copy(alpha = 0.7f * depthProgress.coerceIn(0f, 1f)),
                )
            }
        }
    }
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
