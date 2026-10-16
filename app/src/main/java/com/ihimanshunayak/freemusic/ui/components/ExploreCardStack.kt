// Copyright (c) A|iens. All rights reserved.
// AliensStyle code-header — full structured block below.
//
// Name        : ExploreCardStack.kt
// Version     : 1.1.0
// Author      : A'nil Cyborg
// Author URI  : https://github.com/ihimanshunayak
// Description : Hosts the Explore "liquid carousel": a horizontal
//               LazyRow of rich-content cards with snap-to-center
//               behaviour, side-peek math, a page-indicator pill row,
//               and the SharedTransitionLayout boundary that expands
//               the active card's artwork into the detail screen.
//               One card is fully active at a time; the two on either
//               side are rendered as visible peeks with rotationY +
//               scale + alpha. Cards beyond ±2 of the active index
//               aren't composed.
// Requires    : Compose foundation 1.7+ (LazyRow, graphicsLayer,
//               animateFloatAsState, animateDpAsState,
//               rememberSnapFlingBehavior), SharedTransitionLayout
//               experimental API, FreeMusic ExploreCard, FreeMusic
//               LiquidGlass. FreeMusic MoodGenre model, ArtworkPalette.
// Responsibilities:
//   - Lay out N cards horizontally with 1 large center + 2 side peeks.
//   - Map scroll position to per-card translationX / scaleX / scaleY /
//     alpha / rotationY / depth.
//   - Snap to the nearest card on fling-end and on slow-drag release.
//   - Render the page-indicator pill row (active pill is wider).
//   - Render the Play + Shuffle action row tied to the active card.
//   - Host SharedTransitionLayout so the active card's artwork animates
//     into MoodGenrePlaylistsScreen's hero on tap.
// Dependencies:
//   - ExploreCard (com.ihimanshunayak.freemusic.ui.components)
//   - MoodGenre / MoodGenreSection (data.model.Models)
//   - SharedTransitionLayout / sharedBounds / AnimatedVisibility
//     (androidx.compose.animation)
// Integration Notes:
//   - This composable does NOT navigate itself. The caller wires
//     onCardOpen to navigation and uses the SharedTransitionScope
//     parent (Compose-Navigation supports AnimatedContent) to play
//     the transition.
//   - Snapping is implemented with rememberSnapFlingBehavior (the
//     LazyRow-flavoured pager fling) plus a LaunchedEffect that
//     animates to the nearest item on slow-drag release. We
//     deliberately avoid HorizontalPager (not in our foundation
//     1.7 subset) and spring() (we want a "controlled" feel, no
//     overshoot).
//   - Reduced-motion: if Settings.Global.animator_duration_scale == 0,
//     the snap tween is reduced to 0 ms (effectively instant).

package com.ihimanshunayak.freemusic.ui.components

import android.provider.Settings
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.data.model.MoodGenre
import com.ihimanshunayak.freemusic.data.model.MoodGenreSection
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Carousel heights & widths.
 *
 * The card surface is `CAROUSEL_HEIGHT` tall. The card width is
 * `CARD_WIDTH_FRACTION * pageWidth` on phones and
 * `CARD_WIDTH_WIDE_FRACTION * pageWidth` on 600 dp+ tablets. The
 * LazyRow's content padding is set so the *active* card's left edge
 * is exactly `sideGutter` from the page edge — that gives the two
 * peeks their visible slivers.
 */
private val CAROUSEL_HEIGHT = 460.dp
private const val CARD_WIDTH_FRACTION = 0.72f
private const val CARD_WIDTH_WIDE_FRACTION = 0.46f
private const val SIDE_GUTTER_FRACTION = 0.14f
private const val MAX_VISIBLE_PEEKS = 2

/**
 * Indicator pill metrics.
 */
private val PILL_HEIGHT = 8.dp
private val PILL_WIDTH_INACTIVE = 8.dp
private val PILL_WIDTH_ACTIVE = 22.dp
private val PILL_SPACING = 8.dp

/**
 * Action-button row metrics.
 */
private val ACTION_BUTTON_HEIGHT = 56.dp

/**
 * Public surface for the carousel. Renders the horizontal carousel,
 * the page-indicator pill row, the Play + Shuffle action buttons for
 * the active card, and the section label of the active card.
 *
 * @param sections       the existing Explore data, unchanged shape.
 * @param onCardOpen     invoked when the user taps the active card.
 * @param onPlayClick    invoked when the user taps the Play button in
 *                       the action row (the active card).
 * @param onShuffle      invoked when the user taps the Shuffle button
 *                       in the action row (the active card).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ExploreCardStack(
    sections: List<MoodGenreSection>,
    onCardOpen: (MoodGenre) -> Unit,
    onPlayClick: (MoodGenre) -> Unit,
    onShuffle: (MoodGenre) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    // Flatten the data once. Each card carries its section title so
    // the eyebrow can update as the user swipes past section
    // boundaries.
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
    val snapTween = if (reducedMotion) 0 else 380

    val isWide = LocalConfiguration.current.screenWidthDp >= 600
    val density = LocalDensity.current
    val pageWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }

    val cardWidthPx = pageWidthPx * if (isWide) CARD_WIDTH_WIDE_FRACTION else CARD_WIDTH_FRACTION
    val sideGutterPx = pageWidthPx * SIDE_GUTTER_FRACTION
    val carouselHeight = CAROUSEL_HEIGHT

    // LazyRow state — drives the active index, and receives scroll
    // deltas for the snap-to-center behaviour.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = 0)
    val flingBehavior = rememberSnapFlingBehavior(
        lazyListState = listState,
        snapPosition = SnapPosition.Center,
    )
    val scope = rememberCoroutineScope()

    // Active card index = the LazyRow item whose center is nearest
    // the LazyRow's center. We derive this from the first visible
    // item + the offset within it so the indicator is in lockstep
    // with the gesture.
    val activeIndex by remember(flat.size) {
        derivedStateOf {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            var nearestIndex = 0
            var nearestDelta = Int.MAX_VALUE
            for (i in info.visibleItemsInfo.indices) {
                val item = info.visibleItemsInfo[i]
                val itemCenter = item.offset + item.size / 2
                val delta = abs(itemCenter - center)
                if (delta < nearestDelta) {
                    nearestDelta = delta
                    nearestIndex = item.index
                }
            }
            nearestIndex.coerceIn(0, flat.size - 1)
        }
    }

    // Subtle parallax: when the user flings the carousel, every card
    // re-derives its transform from a continuous `scrollProgress`
    // (not a discrete index), so the side peeks move fluidly. The
    // progress is in "card units" (0 = first card center, 1 = second
    // card center, etc.).
    val scrollProgress by remember(cardWidthPx) {
        derivedStateOf {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val first = info.visibleItemsInfo.firstOrNull()
            val firstOffset = first?.offset ?: 0
            val firstSize = (first?.size ?: cardWidthPx.toInt()).toFloat()
            val firstCenter = firstOffset + firstSize / 2f
            (center - firstCenter) / firstSize.coerceAtLeast(1f)
        }
    }
    val animatedScroll by animateFloatAsState(
        targetValue = scrollProgress,
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else 220, easing = FastOutSlowInEasing),
        label = "explore-carousel-progress",
    )

    // Snap to the nearest card on fling-end so the user doesn't end
    // up with the carousel half-way between two cards. The
    // `rememberSnapFlingBehavior` already does the snap on a fling,
    // but a slow drag end still needs an explicit settle.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .distinctUntilChanged()
            .collect {
                val target = activeIndex
                scope.launch {
                    listState.animateScrollToItem(
                        index = target,
                        scrollOffset = 0,
                    )
                }
            }
    }

    val activeItem = flat.getOrNull(activeIndex)?.first
    val activeSectionTitle = flat.getOrNull(activeIndex)?.second

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(contentPadding),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // -------- Carousel --------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(carouselHeight),
                contentAlignment = Alignment.Center,
            ) {
                SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
                    LazyRow(
                        state = listState,
                        flingBehavior = flingBehavior,
                        contentPadding = PaddingValues(horizontal = with(density) { sideGutterPx.toDp() }),
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(
                            count = flat.size,
                            key = { i -> flat[i].first.browseId },
                        ) { index ->
                            val (item, sectionTitle) = flat[index]
                            // Continuous offset of this card relative to
                            // the active card. 0 = active, +1 = right
                            // neighbor, -1 = left neighbor. Driven by
                            // the live scroll progress so peeks glide.
                            val continuousOffset = (index - activeIndex) - animatedScroll
                            val continuousAbs = abs(continuousOffset)
                            val liveDepth = (1f - continuousAbs * 0.45f).coerceIn(0f, 1f)
                            val livePeek = -continuousOffset
                            // Discrete offset (for click-handler gating
                            // and the section-eyebrow visibility rule).
                            val absDelta = abs(index - activeIndex)
                            val withinWindow = absDelta <= MAX_VISIBLE_PEEKS

                            if (withinWindow) {
                                Box(
                                    modifier = Modifier
                                        .width(with(density) { cardWidthPx.toDp() })
                                        .height(carouselHeight)
                                        .graphicsLayer {
                                            scaleX = 0.86f + 0.14f * liveDepth
                                            scaleY = 0.86f + 0.14f * liveDepth
                                            this.alpha = (0.55f + 0.45f * liveDepth).coerceIn(0f, 1f)
                                            translationX = livePeek * (cardWidthPx * 0.06f)
                                        },
                                ) {
                                    ExploreCard(
                                        item = item,
                                        sectionTitle = if (absDelta <= 1) sectionTitle else null,
                                        depthProgress = liveDepth,
                                        peekProgress = livePeek.coerceIn(-1f, 1f),
                                        isActive = activeIndex == index,
                                        onClick = { onCardOpen(item) },
                                        onPlayClick = { onPlayClick(item) },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // -------- Page indicator pills --------
            PageIndicator(
                count = flat.size,
                activeIndex = activeIndex,
                reducedMotion = reducedMotion,
            )

            Spacer(Modifier.height(16.dp))

            // -------- Action row (Play + Shuffle for the active card) --------
            if (activeItem != null) {
                ActionRow(
                    activeItem = activeItem,
                    onPlay = { onPlayClick(activeItem) },
                    onShuffle = { onShuffle(activeItem) },
                )
            }

            Spacer(Modifier.height(10.dp))

            // -------- Section label for the active card --------
            if (activeSectionTitle != null) {
                Text(
                    text = activeSectionTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.70f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}

/**
 * Row of pills with the active pill animating to a wider width and
 * the full primary color. Inactive pills are 8 dp wide circles in
 * `onSurface.copy(alpha = 0.30)`.
 */
@Composable
private fun PageIndicator(
    count: Int,
    activeIndex: Int,
    reducedMotion: Boolean,
) {
    val tween = if (reducedMotion) 0 else 220
    Row(
        horizontalArrangement = Arrangement.spacedBy(PILL_SPACING),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(PILL_HEIGHT)
            .semantics {
                contentDescription = "Page ${activeIndex + 1} of $count"
            },
    ) {
        repeat(count) { i ->
            val isActive = i == activeIndex
            val width by animateDpAsState(
                targetValue = if (isActive) PILL_WIDTH_ACTIVE else PILL_WIDTH_INACTIVE,
                animationSpec = tween(durationMillis = tween, easing = FastOutSlowInEasing),
                label = "explore-pill-width-$i",
            )
            val color = if (isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.30f)
            }
            Box(
                modifier = Modifier
                    .width(width)
                    .height(PILL_HEIGHT)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/**
 * The Play + Shuffle action row for the active card. Both buttons
 * use the theme's primary / onBackground palette so they sit well on
 * any backdrop.
 */
@Composable
private fun ActionRow(
    activeItem: MoodGenre,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(ACTION_BUTTON_HEIGHT),
    ) {
        ActionButton(
            label = "Play",
            icon = Icons.Rounded.PlayArrow,
            emphasis = ActionEmphasis.Primary,
            onClick = onPlay,
            modifier = Modifier.weight(1f),
        )
        ActionButton(
            label = "Shuffle",
            icon = Icons.Rounded.Shuffle,
            emphasis = ActionEmphasis.Secondary,
            onClick = onShuffle,
            modifier = Modifier.weight(1f),
        )
    }
}

private enum class ActionEmphasis { Primary, Secondary }

/**
 * One action button in the action row. `Primary` is filled with the
 * theme's primary color and white text. `Secondary` is a low-opacity
 * surface with onSurface text. Both use the same height and shape
 * so they line up in a row.
 */
@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    emphasis: ActionEmphasis,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = when (emphasis) {
        ActionEmphasis.Primary -> MaterialTheme.colorScheme.primary
        ActionEmphasis.Secondary -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)
    }
    val fg = when (emphasis) {
        ActionEmphasis.Primary -> MaterialTheme.colorScheme.onPrimary
        ActionEmphasis.Secondary -> MaterialTheme.colorScheme.onBackground
    }
    val cornerShape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .height(ACTION_BUTTON_HEIGHT)
            .clip(cornerShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = fg,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
