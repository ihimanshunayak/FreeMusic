package com.ihimanshunayak.freemusic.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ihimanshunayak.freemusic.data.model.CARD_ART_PX
import com.ihimanshunayak.freemusic.data.model.Song
import com.ihimanshunayak.freemusic.data.model.artworkAt
import com.ihimanshunayak.freemusic.ui.icons.FreeMusicIcons
import kotlin.math.abs

/**
 * Swiping the sleeve sideways to bring the next and the previous cover in from
 * the edges, like a deck of records.
 *
 * Off until it is asked for, which is the whole point of the switch in Settings
 * — see [com.ihimanshunayak.freemusic.data.settings.AppSettings.playerCarouselEnabled].
 * With it off the player is byte for byte the player it has always been: no
 * inset, no neighbours, and the damped directional hint the old horizontal drag
 * gave. With it on the drag tracks the finger at full travel and commits on a
 * third of the card or a flick, and the covers either side are parked a gap
 * away, showing by turns.
 *
 * The two states are one switch and not a scattering of flags so that the
 * players can be reasoned about as two, rather than as a matrix of half-applied
 * behaviours nobody has seen the inside of.
 *
 * A carousel needs a card to slide and an edge for a neighbour to show behind,
 * and a full-bleed banner is neither — so switching it on takes the still-art
 * banner's place rather than drawing over it. A Spotify Canvas is the artwork
 * itself rather than a treatment of it, and keeps the screen either way.
 */

/**
 * Clear space between the sleeve and the covers either side of it.
 *
 * The only geometry a neighbour needs, because the room it shows in is not the
 * carousel's to reserve: the player already holds its controls off the screen
 * edge by a gutter of its own, and the sleeve is the widest thing inside it. So
 * the visible sliver at either edge is whatever that gutter has left over once
 * this gap is taken out of it — a shade over twenty dp on a phone — and it lines
 * up with the controls below instead of being a second, almost-equal inset
 * fighting the first one.
 *
 * Read as a gap and not as a peek for the same reason the player's own layout
 * nails the sleeve to its width rather than to a size of its own: on a short
 * screen the sleeve comes off width-bound, and a hard-coded peek would then be
 * wrong by however much the card lost.
 */
internal val CAROUSEL_NEIGHBOUR_GAP = 8.dp
/** A neighbour sits back until it is being brought in. */
private const val CAROUSEL_NEIGHBOUR_ALPHA = 0.72f

/** A release travelling at least this fast turns the card, whatever the distance. */
internal const val CAROUSEL_FLICK_PX_S = 900f

/** How much of a release's velocity counts as travel it would still have made. */
internal const val CAROUSEL_FLICK_LOOKAHEAD_S = 0.12f

/** How far, as a fraction of the card's own side, a slow release has to travel. */
internal const val CAROUSEL_COMMIT_FRACTION = 0.28f

/** Below this fraction of the card, a lift-off is a tap that wobbled. */
internal const val CAROUSEL_NOISE_FRACTION = 0.02f

/** The span a release's speed is measured over. */
internal const val CAROUSEL_FLICK_WINDOW_MS = 100L

/**
 * How many pointer samples that window is held in.
 *
 * A ring rather than a growing list, because this is fed from inside a pointer
 * gesture that runs for as long as the finger is down and the gesture handler is
 * not the place to be allocating. Ten is comfortable: touch arrives well under
 * 10ms apart, so the window is filled long before the finger has travelled
 * anywhere, and anything older is outside the window anyway.
 */
private const val CAROUSEL_FLICK_SAMPLES = 10

/** How much of a drag past a queue's end the deck still takes, so it never feels dead. */
internal const val CAROUSEL_EDGE_RESISTANCE = 0.3f

/** What a released drag asks the player to do. */
internal enum class CarouselRelease { Return, Next, Previous }

/**
 * What a finger that has just left the sleeve was asking for.
 *
 * Distance alone is not the question. A short fast flick is meant to turn the
 * card exactly as a long slow drag does — that is what makes the deck feel like
 * something thrown rather than something dragged — so the release's velocity is
 * spent as the travel it would still have made over [CAROUSEL_FLICK_LOOKAHEAD_S],
 * and the gesture is measured at where it was *going* rather than where it
 * stopped.
 *
 * A flick is not allowed to stand on its own for nothing, though: [CAROUSEL_NOISE_FRACTION]
 * of the card is the floor, so a press that wobbled a pixel and lifted cannot
 * turn a track. Below the flick speed the projection has to clear
 * [CAROUSEL_COMMIT_FRACTION] of the card, which is the part of the gesture a
 * person can see themselves making.
 *
 * [cardSize] is the sleeve's drawn side in pixels and not the screen's, because
 * the fraction is of the card: the same flick that turns a track on a phone
 * would be a fingernail's travel on a tablet's much wider sleeve.
 *
 * Leftwards travel is the next track and rightwards the previous one, matching
 * the transport glyphs and the direction the deck actually moves.
 */
internal fun carouselCommit(
    drag: Float,
    velocity: Float,
    cardSize: Float,
    allowNext: Boolean = true,
    allowPrevious: Boolean = true,
): CarouselRelease {
    // Nothing measured yet — the first pass has no card to measure.
    if (cardSize <= 0f) return CarouselRelease.Return
    val projected = drag + velocity * CAROUSEL_FLICK_LOOKAHEAD_S
    if (abs(projected) < cardSize * CAROUSEL_NOISE_FRACTION) return CarouselRelease.Return
    val turned = abs(velocity) >= CAROUSEL_FLICK_PX_S ||
        abs(projected) >= cardSize * CAROUSEL_COMMIT_FRACTION
    if (!turned) return CarouselRelease.Return
    // The whole gesture, not just [projected]: a flick off the end of the queue
    // can still be pointing forwards after the projection has been dragged back
    // through zero, and it is the direction the finger was going that decides
    // which neighbour the release was aimed at. Checked here rather than at the
    // call site so a release that could not have gone anywhere is *Return* —
    // one answer to branch on, and one the tests can pin down.
    val next = drag + velocity * CAROUSEL_FLICK_LOOKAHEAD_S < 0f
    if (next && !allowNext) return CarouselRelease.Return
    if (!next && !allowPrevious) return CarouselRelease.Return
    return if (next) CarouselRelease.Next else CarouselRelease.Previous
}

/**
 * Where the deck sits under a finger that has dragged it [drag] pixels.
 *
 * Clamped to a single card's side — [cardSize] is the sleeve as drawn, the
 * distance at which the current card has left the screen and the next one has
 * arrived — because past that there is nothing left to reveal, and a deck that
 * kept travelling would show bare player where the following cover has not been
 * loaded yet.
 *
 * Moving towards an end of the queue that isn't there is resisted rather than
 * refused: the deck still tracks the finger and still springs back, so a swipe
 * at the last track reads as "you are at the end" instead of as a dead screen.
 * The same bargain the collapsed sleeve's fling already makes with
 * [com.ihimanshunayak.freemusic.ui.haptics.Haptic].
 */
internal fun carouselDragOffset(
    drag: Float,
    cardSize: Float,
    allowNext: Boolean,
    allowPrevious: Boolean,
): Float {
    if (cardSize <= 0f) return 0f
    val allowed = if (drag < 0f) allowNext else allowPrevious
    val travel = if (allowed) drag else drag * CAROUSEL_EDGE_RESISTANCE
    return travel.coerceIn(-cardSize, cardSize)
}

/**
 * How fast the finger was moving when it left the sleeve.
 *
 * `detectHorizontalDragGestures` hands out travel and not speed, and the
 * carousel wants the speed. Measured over a short window rather than across the
 * whole gesture on purpose, and this is the whole reason the class exists: a
 * drag that crossed the screen and then stopped dead before release is a drag
 * that was *not* moving when it ended, and a tracker that averaged the entire
 * gesture would still be quoting the speed it had a second earlier. Summing only
 * the samples inside the window is what makes a pause read as a pause.
 *
 * Kept as a class rather than as a few floats in the gesture because the
 * arithmetic is the part that is easy to get subtly wrong and impossible to
 * check by looking at it.
 */
internal class CarouselFlick {

    private val travel = FloatArray(CAROUSEL_FLICK_SAMPLES)
    private val times = LongArray(CAROUSEL_FLICK_SAMPLES)
    private var count = 0
    private var head = 0
    private var lastAt = 0L
    private var started = false

    /**
     * Pixels per second over the trailing window, negative for leftwards — the
     * direction of the next track. Zero until there is a window to measure, and
     * zero again once the samples have aged out, which is the pause.
     */
    val velocity: Float
        get() {
            if (count < 2) return 0f
            val newest = times[(head + count - 1) % CAROUSEL_FLICK_SAMPLES]
            val newestTravel = travel[(head + count - 1) % CAROUSEL_FLICK_SAMPLES]
            val cutoff = newest - CAROUSEL_FLICK_WINDOW_MS
            // Read backwards and stop at the window's far edge, so an old fast
            // stretch cannot outvote a slow finish.
            var i = count - 1
            while (i > 0) {
                val at = times[(head + i - 1) % CAROUSEL_FLICK_SAMPLES]
                if (at < cutoff) break
                i--
            }
            val span = newest - times[(head + i) % CAROUSEL_FLICK_SAMPLES]
            if (span <= 0L) return 0f
            return (newestTravel - travel[(head + i) % CAROUSEL_FLICK_SAMPLES]) /
                (span / 1000f)
        }

    /** Called when a drag starts, so the last gesture's speed cannot leak into this one. */
    fun reset() {
        count = 0
        head = 0
        lastAt = 0L
        started = false
    }

    /**
     * One pointer event. [delta] is the travel this event reported *since the
     * drag began* — the cumulative figure, not the step — and [uptimeMs] is the
     * clock it reported on. The event's own clock, so a slow frame reads as a
     * longer gap rather than as a faster finger.
     */
    fun add(delta: Float, uptimeMs: Long) {
        // The first event of a gesture has no span to measure, and neither has a
        // sample that arrived on a clock that went backwards — pointer events are
        // allowed to arrive out of order on some devices. Both are dropped rather
        // than treated as negative time.
        if (started && uptimeMs < lastAt) return
        started = true
        lastAt = uptimeMs
        if (count == CAROUSEL_FLICK_SAMPLES) {
            travel[head] = delta
            times[head] = uptimeMs
            head = (head + 1) % CAROUSEL_FLICK_SAMPLES
        } else {
            travel[(head + count) % CAROUSEL_FLICK_SAMPLES] = delta
            times[(head + count) % CAROUSEL_FLICK_SAMPLES] = uptimeMs
            count++
        }
    }
}

/**
 * One of the covers either side of the sleeve.
 *
 * Held to the same square, corners, tile and shadow as the sleeve so the three
 * read as one deck rather than as a card that happens to have two panels next
 * to it, and dimmed so the track being played stays the one the eye lands on.
 *
 * Sized and placed entirely by [modifier], which the deck fills in from inside
 * the layout: a neighbour is the same square as the sleeve and sits one gap
 * beyond it, and both of those are the sleeve's own measurements rather than
 * anything this card could know on its own.
 *
 * No haze source: only the sleeve the panels are named after is meant to be the
 * player's glass, and three sources behind one effect is three times the blur.
 *
 * The placeholder sits *under* the image rather than being swapped out for it,
 * as it does on the sleeve: at a rest position most of this card is off the edge
 * of the player, and a glyph that came and went with the load would be a flicker
 * in a sliver.
 */
@Composable
internal fun CarouselNeighbourCard(
    url: String?,
    modifier: Modifier = Modifier,
) {
    val corner = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            // A neighbour's shadow is cast onto the backdrop behind the sleeve's
            // own slot, not onto the sleeve, so it is only worth having once
            // there is a picture to lift.
            .shadow(if (url != null) 10.dp else 0.dp, corner)
            .clip(corner)
            .background(Color.Black.copy(alpha = 0.18f))
            .graphicsLayer { alpha = CAROUSEL_NEIGHBOUR_ALPHA },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = FreeMusicIcons.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(40.dp),
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                // Covers are square and video thumbnails are not; the same
                // answer the sleeve gives.
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The cover the deck is holding on the side a finger is dragging towards, as the
 * URL a card can be handed.
 *
 * Null at either end of the queue and null whenever the queue itself has not
 * been resolved, which is what the neighbour card draws its placeholder tile
 * from. The position is clamped rather than wrapped: a carousel that rolled the
 * queue round would make a swipe at the last track bring in the first, and the
 * transport it sits under would not.
 *
 * Asked at [CARD_ART_PX] rather than the player's own size — a neighbour is only
 * ever seen as a sliver, and the largest rung on the ladder is the wrong price
 * for eighteen dp of it. The sleeve keeps
 * [com.ihimanshunayak.freemusic.data.model.PLAYER_ART_PX] for itself.
 */
internal fun neighbourArtwork(queue: List<Song>, queueIndex: Int, step: Int): String? =
    queue.getOrNull(queueIndex + step)?.artworkAt(CARD_ART_PX)
