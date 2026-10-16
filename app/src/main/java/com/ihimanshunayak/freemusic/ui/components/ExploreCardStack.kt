// Copyright (c) A|iens. All rights reserved.
// AliensStyle code-header — full structured block below.
//
// Name        : ExploreCardStack.kt
// Version     : 1.0.0
// Author      : A'nil Cyborg
// Author URI  : https://github.com/ihimanshunayak
// Description : Vertically-stacked card deck used by the Explore page. Owns
//               the scroll state, drives per-card translationY / scale /
//               alpha, and hosts the SharedTransitionLayout boundary used
//               to expand a tapped card into the detail screen with a
//               matched-geometry transition.
// Requires    : Compose foundation 1.7+ (verticalScroll, graphicsLayer,
//               animateFloatAsState), SharedTransitionLayout experimental
//               API. FreeMusic MoodGenre model, ArtworkPalette.
// Responsibilities:
//   - Lay out N cards in a vertical stack with overlap.
//   - Map scroll offset to per-card translationY / scaleX / scaleY / alpha.
//   - Map drag deltas into scroll offset, settle to nearest card on release.
//   - Provide the SharedTransitionLayout that lets the tapped card
//     animate into MoodGenrePlaylistsScreen's hero.
// Dependencies:
//   - ExploreCard (com.ihimanshunayak.freemusic.ui.components)
//   - MoodGenre / MoodGenreSection (data.model.Models)
//   - SharedTransitionLayout / sharedBounds / AnimatedVisibility
//     (androidx.compose.animation)
// Integration Notes:
//   - This composable does NOT navigate itself. The caller wires
//     onCardOpen to navigation and uses the SharedTransitionScope
//     parent (Compose-Navigation supports AnimatedContent) to
//     play the transition.
//   - Snapping is implemented with animateFloatAsState(scroll) so the
//     back-pressure is automatic. We deliberately avoid spring() so the
//     reference-video "controlled" feel is preserved (no overshoot).
//   - Reduced-motion: if Settings.Global.animator_duration_scale == 0,
//     the snap tween is reduced to 0 ms (effectively instant).

package com.ihimanshunayak.freemusic.ui.components

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.data.model.MoodGenre
import com.ihimanshunayak.freemusic.data.model.MoodGenreSection

/**
 * Public surface for the deck. Renders a vertical scroll of overlapping
 * cards and hands back the active browseId to the caller when the user
 * swipes past the half-way mark.
 *
 * @param sections       the existing Explore data, unchanged shape.
 * @param onCardOpen     invoked when the user taps a card or when the
 *                       stack settles on a new active card via
 *                       `onCardOpenOnSettle = true`.
 * @param onPlayClick    invoked when the corner play button is tapped;
 *                       receives the active MoodGenre so the host can
 *                       start playback through the existing audio path.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ExploreCardStack(
    sections: List<MoodGenreSection>,
    onCardOpen: (MoodGenre) -> Unit,
    onPlayClick: (MoodGenre) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    // Flatten the data once. Each card carries its section title so the
    // eyebrow can update as the user scrolls past section boundaries.
    val flat = remember(sections) { sections.flatMap { sec -> sec.items.map { it to sec.title } } }
    if (flat.isEmpty()) return

    val context = LocalContext.current
    val reducedMotion = remember {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) == 0f
        } catch (_: Throwable) {
            false
        }
    }

    val snapTween = if (reducedMotion) 0 else 420
    val isWide = LocalConfiguration.current.screenWidthDp >= 600

    // Scroll offset is the *target* state; the actual scroll value is
    // animated toward it so the snap feels natural. A `real` scroll
    // value drives the visible offset for direct finger tracking.
    var rawScroll by remember { mutableFloatStateOf(0f) }
    val animatedScroll by animateFloatAsState(
        targetValue = rawScroll,
        animationSpec = tween(durationMillis = snapTween, easing = FastOutSlowInEasing),
        label = "explore-stack-snap",
    )

    // Per-card metrics in pixels.
    val density = LocalDensity.current
    val cardWidthFraction = if (isWide) 0.42f else 0.86f
    val peekHeight = with(density) { if (isWide) 32.dp.toPx() else 56.dp.toPx() }
    val cardHeightFraction = if (isWide) 0.72f else 0.62f
    val pageHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val totalHeight = pageHeightPx * cardHeightFraction + (flat.size - 1).coerceAtLeast(0) * peekHeight
        val cardWidth = constraints.maxWidth * cardWidthFraction
        val cardHeight = pageHeightPx * cardHeightFraction
        val sideMargin = (constraints.maxWidth - cardWidth) / 2f

        // Active index is derived purely from rawScroll. The host page
        // can read it back if it wants to render a different eyebrow.
        val activeIndex by remember(flat.size) {
            derivedStateOf {
                if (peekHeight <= 0f) 0
                else (rawScroll / peekHeight).toInt().coerceIn(0, flat.size - 1)
            }
        }

        // Vertical drag overrides rawScroll. Release causes the snap
        // tween to settle on the nearest card.
        val dragState = remember { object {
            var dragging = false
            var velocity = 0f
        } }
        val scrollState = rememberScrollState()
        LaunchedEffect(scrollState.value) {
            // Mirror the LazyScrollState-free verticalScroll into rawScroll
            // so swiping on the page (without an inner scroller) also
            // moves the deck.
            if (!dragState.dragging) {
                rawScroll = scrollState.value.toFloat()
            }
        }

        // Page-level column with vertical scroll, hosting the deck.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .pointerInput(flat.size) {
                    detectVerticalDragGestures(
                        onDragStart = { dragState.dragging = true },
                        onDragEnd = {
                            dragState.dragging = false
                            // Snap to nearest card on release.
                            val nearest = (rawScroll / peekHeight).toInt()
                            val clamped = nearest.coerceIn(0, flat.size - 1)
                            rawScroll = clamped * peekHeight
                        },
                        onDragCancel = {
                            dragState.dragging = false
                            val nearest = (rawScroll / peekHeight).toInt()
                                .coerceIn(0, flat.size - 1)
                            rawScroll = clampedIndex(nearest) * peekHeight
                        },
                        onVerticalDrag = { _, dragAmount ->
                            rawScroll = (rawScroll - dragAmount)
                                .coerceIn(0f, (flat.size - 1) * peekHeight)
                        },
                    )
                }
                .padding(contentPadding),
        ) {
            // We need a known-height container so each card can size
            // itself against the page. Use Spacer to occupy totalHeight.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { totalHeight.toDp() }),
            ) {
                SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
                    flat.forEachIndexed { index, (item, sectionTitle) ->
                        // distance from active (signed) drives the transform.
                        val offsetCards = index - activeIndex
                        val translationY = animatedScroll - index * peekHeight
                        val absDelta = kotlin.math.abs(offsetCards)
                        val depth = (1f - absDelta.coerceAtMost(2) * 0.5f).coerceIn(0f, 1f)
                        val scale = (1f - absDelta * 0.04f).coerceIn(0.88f, 1f)
                        val alpha = (1f - absDelta * 0.18f).coerceIn(0.55f, 1f)

                        val key = "card:${item.browseId}"
                        AnimatedVisibility(
                            visible = absDelta <= 2,
                            enter = fadeIn() + scaleIn(initialScale = 0.92f),
                            exit = fadeOut() + scaleOut(targetScale = 0.92f),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = with(density) { sideMargin.toDp() }),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(with(density) { cardHeight.toDp() })
                                        .graphicsLayer {
                                            this.translationY = translationY
                                            scaleX = scale
                                            scaleY = scale
                                            this.alpha = alpha
                                        },
                                ) {
                                    ExploreCard(
                                        item = item,
                                        sectionTitle = if (absDelta <= 1) sectionTitle else null,
                                        depthProgress = depth,
                                        isActive = activeIndex == index,
                                        onClick = { onCardOpen(item) },
                                        onPlayClick = { onPlayClick(item) },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .sharedBounds(
                                                rememberSharedContentState(key = key),
                                                animatedVisibilityScope = this@AnimatedVisibility,
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun clampedIndex(v: Int): Int = v.coerceAtLeast(0)
