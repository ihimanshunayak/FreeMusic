package com.ihimanshunayak.freemusic

import com.ihimanshunayak.freemusic.ui.player.CAROUSEL_COMMIT_FRACTION
import com.ihimanshunayak.freemusic.ui.player.CAROUSEL_EDGE_RESISTANCE
import com.ihimanshunayak.freemusic.ui.player.CAROUSEL_FLICK_PX_S
import com.ihimanshunayak.freemusic.ui.player.CAROUSEL_NOISE_FRACTION
import com.ihimanshunayak.freemusic.ui.player.CarouselFlick
import com.ihimanshunayak.freemusic.ui.player.CarouselRelease
import com.ihimanshunayak.freemusic.ui.player.carouselCommit
import com.ihimanshunayak.freemusic.ui.player.carouselDragOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a sideways drag of the player's sleeve is read, both while a finger is on
 * it and at the moment it lifts.
 *
 * The deck is the one part of the player whose behaviour is entirely arithmetic
 * — how far a card has travelled, how fast it was going, whether that adds up to
 * a track change — and it is also the part that cannot be checked by looking at
 * it, since every one of these answers lasts a frame at a time. Hence the tests:
 * a carousel that turns a card on a two-pixel wobble, or refuses one that was
 * thrown, reads as a broken animation rather than as a wrong constant.
 */
class PlayerSwipeCarouselTest {

    /** The sleeve on a 360dp phone at 3x, which is where these numbers come from. */
    private val card = 1080f

    /** A release with a given distance and speed, both gates open unless said otherwise. */
    private fun release(
        drag: Float,
        velocity: Float = 0f,
        next: Boolean = true,
        previous: Boolean = true,
    ) = carouselCommit(
        drag = drag,
        velocity = velocity,
        cardSize = card,
        allowNext = next,
        allowPrevious = previous,
    )

    // ---- Committing a release ----

    @Test
    fun `a sleeve nobody touched is not a track change`() {
        assertEquals(CarouselRelease.Return, release(drag = 0f))
    }

    @Test
    fun `a drag under the commit fraction springs back rather than turning the card`() {
        val short = card * CAROUSEL_COMMIT_FRACTION - 1f
        assertEquals(CarouselRelease.Return, release(drag = -short))
        assertEquals(CarouselRelease.Return, release(drag = short))
    }

    @Test
    fun `more than the commit fraction turns it`() {
        val past = card * CAROUSEL_COMMIT_FRACTION + 1f
        assertEquals(CarouselRelease.Next, release(drag = -past))
        assertEquals(CarouselRelease.Previous, release(drag = past))
    }

    @Test
    fun `a fling turns the card without the distance`() {
        // A fifth of the card, well under what a slow drag would need.
        val short = card * 0.2f
        assertEquals(
            CarouselRelease.Next,
            release(drag = -short, velocity = -CAROUSEL_FLICK_PX_S),
        )
        assertEquals(
            CarouselRelease.Previous,
            release(drag = short, velocity = CAROUSEL_FLICK_PX_S),
        )
    }

    @Test
    fun `a fling is spent as the travel it would still have made`() {
        // A quarter of the card: under the commit fraction on its own, but the
        // same gesture thrown carries 600px/s of look-ahead past it.
        val drag = -(card * 0.25f)
        assertEquals(CarouselRelease.Return, release(drag = drag))
        assertEquals(CarouselRelease.Next, release(drag = drag, velocity = -600f))
    }

    @Test
    fun `a throw overrides the direction the finger had drifted in`() {
        // Drifted right by five per cent — nowhere near anything — and then
        // thrown left. The throw is the newer intent.
        assertEquals(
            CarouselRelease.Next,
            release(drag = card * 0.05f, velocity = -CAROUSEL_FLICK_PX_S),
        )
    }

    @Test
    fun `a tap with a bad aim is not a track change`() {
        // The floor is on the *projection*, which is what the release is asking
        // for: a sleeve that has barely moved and is not moving turns nothing,
        // however long the finger rested on it.
        val nudge = card * CAROUSEL_NOISE_FRACTION * 0.5f
        assertEquals(CarouselRelease.Return, release(drag = nudge, velocity = 60f))
        assertEquals(CarouselRelease.Return, release(drag = -nudge, velocity = -60f))
    }

    @Test
    fun `an unmeasured card turns nothing`() {
        // Before the sleeve has been laid out there is no fraction to take.
        assertEquals(
            CarouselRelease.Return,
            carouselCommit(drag = -400f, velocity = -4000f, cardSize = 0f),
        )
    }

    @Test
    fun `the last track has nothing to turn to`() {
        assertEquals(
            CarouselRelease.Return,
            release(drag = -card * 0.5f, velocity = -CAROUSEL_FLICK_PX_S, next = false),
        )
        assertEquals(
            CarouselRelease.Return,
            release(drag = card * 0.5f, velocity = CAROUSEL_FLICK_PX_S, previous = false),
        )
    }

    @Test
    fun `an end of the queue does not block the other direction`() {
        assertEquals(CarouselRelease.Previous, release(drag = card * 0.5f, next = false))
        assertEquals(CarouselRelease.Next, release(drag = -card * 0.5f, previous = false))
    }

    // ---- Following the finger ----

    @Test
    fun `the deck follows the finger while there is a neighbour to show`() {
        assertEquals(-140f, carouselDragOffset(-140f, card, true, true), 0f)
        assertEquals(140f, carouselDragOffset(140f, card, true, true), 0f)
    }

    @Test
    fun `the deck stops at one card`() {
        // Past a card's side there is nothing left to reveal — the following
        // cover has not been loaded and is not being asked for.
        assertEquals(-card, carouselDragOffset(-card * 3f, card, true, true), 0f)
        assertEquals(card, carouselDragOffset(card * 3f, card, true, true), 0f)
    }

    @Test
    fun `an end of the queue is resisted rather than refused`() {
        // Still moves, so the swipe reads as "you are at the end" instead of as
        // a dead screen — but only a fraction of what the finger asked for.
        val pulled = carouselDragOffset(-200f, card, allowNext = false, allowPrevious = true)
        assertEquals(-200f * CAROUSEL_EDGE_RESISTANCE, pulled, 0f)
        assertTrue("$pulled", pulled < 0f)
    }

    @Test
    fun `resisted travel is clamped like any other`() {
        assertEquals(
            -card,
            carouselDragOffset(-card * 20f, card, allowNext = false, allowPrevious = true),
            0f,
        )
    }

    @Test
    fun `an unmeasured card does not move`() {
        assertEquals(0f, carouselDragOffset(-300f, cardSize = 0f, true, true), 0f)
    }

    // ---- Measuring the speed of a release ----

    @Test
    fun `a speed in the window is the speed of the release`() {
        val flick = CarouselFlick()
        var travel = 0f
        var at = 1000L
        // 40px every 16ms is 2500px/s, held across the whole window.
        repeat(8) {
            flick.add(travel, at)
            travel -= 40f
            at += 16L
        }
        flick.add(travel, at)
        assertEquals(-2500f, flick.velocity, 1f)
    }

    @Test
    fun `a drag that stopped before it was let go releases at rest`() {
        val flick = CarouselFlick()
        // A fast stretch, then a finger held still for longer than the window —
        // which is every drag across a sleeve that then thinks better of it.
        var travel = 0f
        var at = 1000L
        repeat(6) {
            flick.add(travel, at)
            travel -= 60f
            at += 16L
        }
        // Twelve samples is 192ms of standing still, well past the window.
        repeat(12) {
            flick.add(travel, at)
            at += 16L
        }
        assertEquals(0f, flick.velocity, 0f)
    }

    @Test
    fun `a slow finish outvotes a fast start`() {
        val flick = CarouselFlick()
        // Five fast samples, then a tail of slow ones long enough to fill the
        // window on its own: the release is the slow part, whatever came before.
        var travel = 0f
        var at = 1000L
        repeat(5) {
            flick.add(travel, at)
            travel -= 100f
            at += 16L
        }
        repeat(12) {
            flick.add(travel, at)
            travel -= 4f
            at += 16L
        }
        assertEquals(-250f, flick.velocity, 1f)
    }

    @Test
    fun `one sample is not a speed`() {
        val flick = CarouselFlick()
        flick.add(0f, 1000L)
        assertEquals(0f, flick.velocity, 0f)
    }

    @Test
    fun `the window is measured from the newest sample, not the first`() {
        val flick = CarouselFlick()
        // Eight rightward samples after a long leftward run. The run is outside
        // the window by then; a tracker that averaged the whole gesture would
        // still be quoting it.
        var travel = 0f
        var at = 1000L
        repeat(40) {
            flick.add(travel, at)
            travel -= 100f
            at += 16L
        }
        repeat(8) {
            flick.add(travel, at)
            travel += 100f
            at += 16L
        }
        assertEquals(6250f, flick.velocity, 1f)
    }

    @Test
    fun `a reset drops the last gesture's speed`() {
        val flick = CarouselFlick()
        var travel = 0f
        var at = 1000L
        repeat(5) {
            flick.add(travel, at)
            travel -= 60f
            at += 16L
        }
        assertTrue("${flick.velocity}", flick.velocity < 0f)
        flick.reset()
        assertEquals(0f, flick.velocity, 0f)
        // And the next gesture lands on its own samples rather than the pile.
        flick.add(0f, 5000L)
        flick.add(40f, 5016L)
        assertEquals(2500f, flick.velocity, 1f)
    }

    @Test
    fun `a clock that goes backwards is dropped rather than counted`() {
        val flick = CarouselFlick()
        flick.add(0f, 2000L)
        flick.add(-40f, 2016L)
        // Out of order, as some devices deliver pointer events.
        flick.add(-80f, 1990L)
        flick.add(-120f, 2032L)
        // 120px over the 32ms that actually elapsed, not over a negative span.
        assertEquals(-3750f, flick.velocity, 1f)
    }
}
