// Copyright (c) A|iens. All rights reserved.
// AliensStyle code-header — full structured block below.
//
// Name        : ExploreCardStack.kt
// Version     : 2.0.0
// Author      : A'nil Cyborg
// Author URI  : https://github.com/ihimanshunayak
// Description : Hosts the Explore "swipe deck": a Tinder-style stack of
//               rich-content cards where the front card follows the
//               user's finger left and right, tips as it goes, and is
//               either committed — the next card takes its place — or
//               snapped back into place on release. Two cards sit
//               behind the front one, scaled and turned away, so the
//               deck reads as a stack rather than as a row.
//
//               v2.0.0 replaces v1.1.0's horizontal LazyRow carousel
//               with this deck. The LazyRow was a *pager* — the user
//               scrolled through the moods sideways — and the brief was
//               for the deck every swipe-first app uses: one card in
//               front, the rest stacked behind it, swiped out of the
//               way.
//
// Requires    : Compose foundation 1.7+ (detectHorizontalDragGestures,
//               graphicsLayer, animate, animateFloatAsState,
//               animateDpAsState), Material3, FreeMusic ExploreCard,
//               FreeMusic LiquidGlass, FreeMusic MoodGenre model.
// Responsibilities:
//   - Lay out the deck: one active card in front, up to two behind it.
//   - Track the drag, tip the front card by the direction of travel,
//     and commit or snap back on release.
//   - Fly the committed card off in the direction it was thrown and
//     bring the next one up from behind it.
//   - Render the page-indicator pill row (active pill is wider).
//   - Render the Play + Shuffle action row tied to the active card.
//   - Render the section label of the active card.
// Dependencies:
//   - ExploreCard (com.ihimanshunayak.freemusic.ui.components)
//   - MoodGenre / MoodGenreSection (data.model.Models)
//   - Lightweight glass (ui.components.LiquidGlass) for the action row
// Integration Notes:
//   - This composable does NOT navigate itself. The caller wires
//     onCardOpen to navigation.
//   - The deck is driven by a single index rather than by a scroll
//     position: there is no continuous scroll to read, because the
//     card under the finger is *this* card and not a position between
//     two of them. The old carousel's derived "scroll progress" has
//     no meaning here.
//   - A drag commits when it passes a fraction of the card's width or
//     when it is thrown. Swiping right opens the card — the Tinder
//     "like" gesture — and swiping left skips it. Both fly the card
//     off in the direction it was travelling.
//   - Reduced-motion: if Settings.Global.animator_duration_scale == 0,
//     the settle tween is reduced to 0 ms (effectively instant).

package com.ihimanshunayak.freemusic.ui.components

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
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
import com.ihimanshunayak.freemusic.ui.haptics.Haptic
import com.ihimanshunayak.freemusic.ui.haptics.rememberHaptics
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Deck geometry.
 *
 * The card is [CARD_WIDTH_FRACTION] of the page on a phone and
 * [CARD_WIDTH_WIDE_FRACTION] on a 600 dp+ tablet. The cards behind the
 * front one are set back by [STACK_OFFSET_DP] and scaled down by
 * [STACK_SCALE_STEP] each, so the stack has depth without the back
 * cards stealing attention from the one being read.
 */
private const val CARD_WIDTH_FRACTION = 0.82f
private const val CARD_WIDTH_WIDE_FRACTION = 0.46f
private val STACK_OFFSET_DP = 12.dp
private const val STACK_SCALE_STEP = 0.055f

/** How many cards sit behind the front one. */
private const val STACK_DEPTH = 2

/** The tallest the card is ever drawn, and the shortest it may shrink to. */
private val CARD_HEIGHT_MAX = 470.dp
private val CARD_HEIGHT_MIN = 300.dp

/**
 * How far the front card has to travel before the gesture counts as a
 * swipe rather than a nudge, as a fraction of the card's own width.
 *
 * A third, which is the usual compromise: far enough that an accidental
 * brush of the card does not throw it away, near enough that a
 * deliberate swipe does not have to cross half the screen.
 */
private const val SWIPE_COMMIT_FRACTION = 0.32f

/**
 * How much of the card's width a fast flick has to cover to count as
 * a swipe regardless of the distance threshold.
 *
 * A flick is short but fast, so a rule that only measured distance
 * would throw away the gesture people actually mean when they flick.
 */
private const val FLICK_VELOCITY_PX_PER_SECOND = 900f

/**
 * How far past its resting place a card is allowed to go before the
 * deck stops following the finger exactly.
 *
 * The card is glued to the finger, which is what makes the deck feel
 * physical — but only up to a point. Past it, the card would be
 * halfway off the screen and dragging further would say nothing the
 * first half of the drag had not already said.
 */
private const val MAX_DRAG_FRACTION = 0.85f

/**
 * How far the card turns as it travels, in degrees at full travel.
 *
 * Twelve is enough for the card to read as tipping away from the
 * reader without the title becoming unreadable at the extreme.
 */
private const val TIP_DEGREES = 12f

/**
 * How much the card shrinks as it is thrown away, so that it reads as
 * leaving rather than as being dragged off the edge.
 */
private const val THROW_SHRINK = 0.12f

/**
 * Indicator track metrics.
 *
 * The deck can hold dozens of cards, so the indicator is a single
 * progress track rather than a row of pills: one pill per card would
 * either overflow the page or shrink to nothing, and neither tells the
 * user where they are in the deck. A track shows the same thing at any
 * length, and it is what the swipe-first apps the deck is modelled on
 * use.
 */
private val TRACK_HEIGHT_ACTIVE = 6.dp
private val TRACK_WIDTH = 180.dp

/**
 * Action-button row metrics.
 */
private val ACTION_BUTTON_HEIGHT = 56.dp
private val ACTION_BUTTON_SHAPE = RoundedCornerShape(20.dp)

/**
 * The vertical space the deck keeps for itself below the card: the
 * indicator row, the action row and the section label, with their
 * gaps. The card takes whatever is left of the page.
 */
private val DECK_CHROME_HEIGHT = 126.dp

/**
 * What the deck is doing with the card in front of it.
 */
private sealed interface DeckThrow {

    /** The card is being dragged; the offset is how far the finger has gone. */
    data class Dragging(val offset: Float) : DeckThrow

    /** The card has been let go and is on its way off the deck. */
    data class Thrown(
        val item: MoodGenre,
        val sectionTitle: String?,
        /** -1 for a throw to the left, +1 for a throw to the right. */
        val direction: Int,
        /** Where the finger let go, so the throw continues from there. */
        val fromOffset: Float,
    ) : DeckThrow
}

/**
 * Public surface for the deck. Renders the swipeable card stack, the
 * page-indicator pill row, the Play + Shuffle action buttons for the
 * active card, and the section label of the active card.
 *
 * @param sections       the existing Explore data, unchanged shape.
 * @param onCardOpen     invoked when the user taps the active card, or
 *                       swipes it to the right.
 * @param onPlayClick    invoked when the user taps the Play button in
 *                       the action row (the active card).
 * @param onShuffle      invoked when the user taps the Shuffle button
 *                       in the action row (the active card).
 * @param onActiveArtworkChanged reports the artwork of the card in
 *                       front, so the page's header can wear it.
 */
@Composable
fun ExploreCardStack(
    sections: List<MoodGenreSection>,
    onCardOpen: (MoodGenre) -> Unit,
    onPlayClick: (MoodGenre) -> Unit,
    onShuffle: (MoodGenre) -> Unit,
    onActiveArtworkChanged: (String?) -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    // Flatten the data once. Each card carries its section title so
    // the eyebrow can update as the user swipes past section
    // boundaries. Duplicates by browseId are de-duped (keeping the
    // first occurrence) because the YouTube server commonly returns
    // identical playlist IDs across related sections (e.g. shared
    // "Workout" content in both "Workout" and "Energy Boost"), and
    // duplicate rows in the deck would mislead the user.
    val flat = remember(sections) {
        val seen = HashSet<String>()
        val out = ArrayList<Pair<MoodGenre, String>>()
        for (sec in sections) {
            for (item in sec.items) {
                // The key has to carry the *params* as well as the browse id.
                //
                // Every mood and genre button on this page points into the same
                // browse family and differs only by its params — the params are
                // what select which playlist shelves belong to that exact mood.
                // A key of just the browse id therefore matched every button
                // against the first one and collapsed the whole deck to a
                // single card, which is exactly what it did: the page said
                // "Card 1 of 1" and there was nothing to swipe to.
                //
                // The title is in the key as well, so two buttons that really
                // do share both a browse id and params but sit under different
                // labels are still two cards — they are two different moods to
                // the user, and they open the same shelves by coincidence.
                val key = buildString {
                    append(item.browseId)
                    append('|')
                    append(item.params.orEmpty())
                    append('|')
                    append(item.title)
                }
                if (key.isNotEmpty() && seen.add(key)) {
                    out += item to sec.title
                }
            }
        }
        out
    }
    if (flat.isEmpty()) return

    val context = LocalContext.current
    val reducedMotion = remember {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) == 0f
        } catch (_: Throwable) {
            false
        }
    }
    val settleMillis = if (reducedMotion) 0 else 320

    val isWide = LocalConfiguration.current.screenWidthDp >= 600
    val density = LocalDensity.current
    val pageWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val cardWidthPx = pageWidthPx * if (isWide) CARD_WIDTH_WIDE_FRACTION else CARD_WIDTH_FRACTION

    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()

    // The card in front.
    var activeIndex by remember(flat.size) { mutableIntStateOf(0) }

    // What the deck is doing with the front card.
    var deckState by remember { mutableStateOf<DeckThrow>(DeckThrow.Dragging(0f)) }

    // How far through the throw the committed card is, 0 to 1.
    var throwProgress by remember { mutableFloatStateOf(0f) }

    // The callbacks are read through rememberUpdatedState so a
    // recomposition mid-gesture cannot make the gesture call a stale
    // lambda.
    val currentOnCardOpen by rememberUpdatedState(onCardOpen)
    val currentOnPlayClick by rememberUpdatedState(onPlayClick)
    val currentOnShuffle by rememberUpdatedState(onShuffle)
    val currentOnArtworkChanged by rememberUpdatedState(onActiveArtworkChanged)

    val activeItem = flat.getOrNull(activeIndex)?.first
    val activeSectionTitle = flat.getOrNull(activeIndex)?.second

    // The header wears the artwork of whatever is in front, so the page
    // changes with the deck rather than sitting on a fixed picture.
    LaunchedEffect(activeItem?.thumbnailUrl) {
        currentOnArtworkChanged(activeItem?.thumbnailUrl)
    }

    // The card's travel, either the drag in progress or the animation
    // of it being thrown. One value drives both, so the throw and the
    // drag can never disagree about where the card is.
    val dragOffset = (deckState as? DeckThrow.Dragging)?.offset ?: 0f
    val isThrown = deckState is DeckThrow.Thrown

    val thrown = deckState as? DeckThrow.Thrown
    val travel = when {
        thrown != null -> {
            // The throw starts where the finger let go and ends well
            // past the edge of the screen, so the card reads as leaving
            // rather than as stopping.
            val start = thrown.fromOffset
            val end = thrown.direction * (cardWidthPx * 1.45f)
            start + (end - start) * throwProgress
        }

        else -> dragOffset
    }

    // How much of the card's width the travel represents. This is what
    // the commit threshold is measured against, and it is also what
    // drives the tip and the swipe affordance on the card.
    val travelFraction = if (cardWidthPx > 0f) travel / cardWidthPx else 0f
    val commitThreshold = cardWidthPx * SWIPE_COMMIT_FRACTION
    val maxTravel = cardWidthPx * MAX_DRAG_FRACTION

    // The action the current gesture is heading towards, and how far
    // towards it the card has got. Right is "open", left is "skip".
    val swipeAction = when {
        travelFraction > 0.02f -> SwipeAction.Open
        travelFraction < -0.02f -> SwipeAction.Skip
        else -> null
    }
    val swipeProgress = abs(travelFraction).coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // No background of its own. The page behind the deck paints
            // the header picture and the wash; an opaque fill here
            // would cover both, and the deck would sit on a flat colour
            // with the picture nowhere in sight.
            .padding(contentPadding),
    ) {
        // The card takes whatever the page has left once the indicator,
        // the action row and the label have had their share.
        val cardHeight = (maxHeight - DECK_CHROME_HEIGHT).coerceIn(CARD_HEIGHT_MIN, CARD_HEIGHT_MAX)
        val stackOffsetPx = with(density) { STACK_OFFSET_DP.toPx() }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // -------- The deck --------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight),
                contentAlignment = Alignment.Center,
            ) {
                // The cards behind, drawn first so the front card is on
                // top of them. Each one is a step further back: smaller,
                // lower, and turned slightly away.
                //
                // While a card is being thrown, the card behind it comes
                // up a step to take its place, which is why the step is
                // not derived from the index alone.
                for (depth in STACK_DEPTH downTo 1) {
                    val behindIndex = activeIndex + depth
                    val behind = flat.getOrNull(behindIndex) ?: continue
                    val (item, sectionTitle) = behind
                    val step = (depth - throwProgress).coerceAtLeast(0f)
                    val scale = (1f - STACK_SCALE_STEP * step).coerceIn(0.5f, 1f)
                    val yOffset = stackOffsetPx * step

                    Box(
                        modifier = Modifier
                            .width(with(density) { cardWidthPx.toDp() })
                            .height(cardHeight)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationY = yOffset
                                // The back of the deck is turned away a
                                // little, so the front card is the only
                                // one whose face is square to the reader.
                                rotationY = 5f * step
                            },
                    ) {
                        ExploreCard(
                            item = item,
                            sectionTitle = sectionTitle,
                            depthProgress = (1f - 0.45f * step).coerceIn(0f, 1f),
                            isActive = false,
                            onClick = {},
                            onPlayClick = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // The card in front. It follows the finger, tips by the
                // direction of travel, and is thrown clear of the deck
                // when the gesture commits.
                val front = flat.getOrNull(activeIndex)
                if (front != null) {
                    val (item, sectionTitle) = front
                    Box(
                        modifier = Modifier
                            .width(with(density) { cardWidthPx.toDp() })
                            .height(cardHeight)
                            .pointerInput(activeIndex, cardWidthPx) {
                                var totalDrag = 0f
                                var velocity = 0f
                                var lastTime = 0L
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        totalDrag = 0f
                                        velocity = 0f
                                        lastTime = 0L
                                        deckState = DeckThrow.Dragging(0f)
                                    },
                                    onDragCancel = {
                                        totalDrag = 0f
                                        velocity = 0f
                                        deckState = DeckThrow.Dragging(0f)
                                    },
                                    onDragEnd = {
                                        val thrownFast = velocity > FLICK_VELOCITY_PX_PER_SECOND ||
                                            velocity < -FLICK_VELOCITY_PX_PER_SECOND
                                        val crossed = abs(totalDrag) >= commitThreshold
                                        val direction = if (totalDrag < 0f) -1 else 1
                                        if (crossed || thrownFast) {
                                            // The card leaves in the
                                            // direction it was travelling.
                                            deckState = DeckThrow.Thrown(
                                                item = item,
                                                sectionTitle = sectionTitle,
                                                direction = direction,
                                                fromOffset = totalDrag.coerceIn(-maxTravel, maxTravel),
                                            )
                                            throwProgress = 0f
                                            haptics.play(Haptic.Select)
                                            scope.launch {
                                                animate(
                                                    initialValue = 0f,
                                                    targetValue = 1f,
                                                    animationSpec = tween(
                                                        durationMillis = settleMillis,
                                                        easing = FastOutSlowInEasing,
                                                    ),
                                                ) { value, _ ->
                                                    throwProgress = value
                                                }
                                                // The deck moves on. A swipe
                                                // to the right opens the card
                                                // — the Tinder "like" — and
                                                // a swipe to the left skips
                                                // it; either way the next
                                                // card comes forward.
                                                if (direction > 0) {
                                                    currentOnCardOpen(item)
                                                }
                                                activeIndex =
                                                    (activeIndex + 1).coerceAtMost(flat.size - 1)
                                                deckState = DeckThrow.Dragging(0f)
                                                throwProgress = 0f
                                            }
                                        } else {
                                            // Not far enough: the card
                                            // goes back where it was.
                                            deckState = DeckThrow.Dragging(0f)
                                        }
                                        totalDrag = 0f
                                        velocity = 0f
                                    },
                                    onHorizontalDrag = { change, delta ->
                                        change.consume()
                                        totalDrag += delta
                                        // The speed of the finger, worked
                                        // out over the time between the
                                        // last two samples rather than
                                        // across the whole gesture — a
                                        // drag that crossed the screen and
                                        // then stopped dead before
                                        // release was not moving when it
                                        // ended.
                                        val now = change.uptimeMillis
                                        if (lastTime > 0L) {
                                            val elapsed = (now - lastTime).coerceAtLeast(1L)
                                            velocity = delta * 1000f / elapsed
                                        }
                                        lastTime = now
                                        deckState = DeckThrow.Dragging(
                                            totalDrag.coerceIn(-maxTravel, maxTravel),
                                        )
                                    },
                                )
                            }
                            .graphicsLayer {
                                translationX = travel
                                // The card tips away from the direction
                                // of travel, the way a card pushed across
                                // a table turns.
                                rotationZ = (travelFraction * TIP_DEGREES)
                                    .coerceIn(-TIP_DEGREES, TIP_DEGREES)
                                // A little yaw as well, so the card reads
                                // as a solid object turning rather than as
                                // a picture being stretched sideways.
                                rotationY = (-travelFraction * 6f).coerceIn(-6f, 6f)
                                // A card being thrown shrinks a little, so
                                // that it reads as leaving rather than as
                                // being dragged off the edge.
                                val shrink = throwProgress * THROW_SHRINK
                                val shrinkScale = 1f - shrink
                                scaleX = shrinkScale
                                scaleY = shrinkScale
                            },
                    ) {
                        ExploreCard(
                            item = item,
                            sectionTitle = sectionTitle,
                            depthProgress = 1f,
                            isActive = true,
                            swipeAction = swipeAction,
                            swipeProgress = swipeProgress,
                            onClick = { currentOnCardOpen(item) },
                            onPlayClick = { currentOnPlayClick(item) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // -------- Progress track --------
            ProgressTrack(
                count = flat.size,
                activeIndex = activeIndex,
                reducedMotion = reducedMotion,
            )

            Spacer(Modifier.height(16.dp))

            // -------- Action row (Play + Shuffle for the active card) --------
            if (activeItem != null) {
                ActionRow(
                    activeItem = activeItem,
                    onPlay = { currentOnPlayClick(activeItem) },
                    onShuffle = { currentOnShuffle(activeItem) },
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
 * The deck's progress track.
 *
 * A single bar showing how far through the deck the user is. The
 * filled part grows as the deck is swiped, so the track reads as a
 * position in the deck rather than as a row of dots that has to be
 * counted.
 */
@Composable
private fun ProgressTrack(
    count: Int,
    activeIndex: Int,
    reducedMotion: Boolean,
) {
    val tweenMillis = if (reducedMotion) 0 else 240
    // The fraction of the track the deck has been through. The last
    // card sits at the very end rather than a fraction short of it, so
    // the track can finish filling.
    val fraction = if (count <= 1) 1f else (activeIndex + 1f) / count.toFloat()
    val animatedFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = tweenMillis, easing = FastOutSlowInEasing),
        label = "explore-progress",
    )
    val trackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.16f)
    val fillColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .width(TRACK_WIDTH)
            .height(TRACK_HEIGHT_ACTIVE)
            .clip(CircleShape)
            .background(trackColor)
            .semantics {
                contentDescription = "Card ${activeIndex + 1} of $count"
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth(animatedFraction.coerceAtLeast(0.001f))
                .height(TRACK_HEIGHT_ACTIVE)
                .clip(CircleShape)
                .background(fillColor),
        )
    }
}

/**
 * The Play + Shuffle action row for the active card.
 *
 * Both buttons are glass, the same material the rest of the app's
 * buttons are made of: a translucent tint, a directional highlight
 * along the top edge and a hairline around the rim. The one that is
 * filled is the one that starts playback, so it is drawn with the
 * theme's own primary colour — a filled button does not need a glass
 * rim to be found.
 */
@Composable
private fun ActionRow(
    activeItem: MoodGenre,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    val haptics = rememberHaptics()
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
            onClick = {
                haptics.play(Haptic.Tap)
                onPlay()
            },
            modifier = Modifier.weight(1f),
        )
        ActionButton(
            label = "Shuffle",
            icon = Icons.Rounded.Shuffle,
            emphasis = ActionEmphasis.Secondary,
            onClick = {
                haptics.play(Haptic.Tap)
                onShuffle()
            },
            modifier = Modifier.weight(1f),
        )
    }
}

private enum class ActionEmphasis { Primary, Secondary }

/**
 * One action button in the action row.
 *
 * The secondary button is a glass surface — the same tint, rim and
 * highlight the app's other buttons are made of. The primary button
 * is filled with the theme's primary colour, because it is the one
 * that starts playback and it should not have to be read to be
 * found.
 */
@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    emphasis: ActionEmphasis,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fg = when (emphasis) {
        ActionEmphasis.Primary -> MaterialTheme.colorScheme.onPrimary
        ActionEmphasis.Secondary -> MaterialTheme.colorScheme.onBackground
    }
    val base = modifier
        .height(ACTION_BUTTON_HEIGHT)
        .clip(ACTION_BUTTON_SHAPE)
    val painted = when (emphasis) {
        ActionEmphasis.Primary -> base
            .background(MaterialTheme.colorScheme.primary)
            .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, ACTION_BUTTON_SHAPE)
        ActionEmphasis.Secondary -> base
            .lightweightLiquidGlass(
                shape = ACTION_BUTTON_SHAPE,
                fallbackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f),
            )
    }
    Box(
        modifier = painted
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
