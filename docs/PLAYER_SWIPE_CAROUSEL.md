# Player Swipe Carousel — Design & Implementation

Implemented for v1.14, branch `windows-desktop`.

Dragging the now-playing sleeve sideways brings the previous and next covers in
from the edges, like a deck of records. This document records what the feature
does, why it is behind a switch of its own, and what it deliberately does not do.

---

## 1. It is one switch, and it is off

`AppSettings.playerCarouselEnabled` (Settings → **New features** → *Swipe the
sleeve*) is the only flag. With it **off** — which is how it ships — the player
is byte for byte the player it has always been:

- no neighbours are composed at all,
- a drag neither tracks the finger at full travel nor commits on a flick; the
  old damped `total * 0.35f` hint is what moves the sleeve,
- the full-bleed artwork banner and the Spotify Canvas clip behave as before.

With it **on**, the deck is the player: the sleeve follows the finger one-for-one,
the covers either side come in from the edges, and a release either turns the
track or springs home.

The switch exists because the two states are genuinely different players rather
than one player with a flourish added, and because the flourish is a taste. A
scattering of smaller flags would have produced a matrix of half-applied
behaviours nobody could reason about; one switch means the two players can be
tested and described as two.

---

## 2. Why switching it on takes the banner's place

A carousel needs two things: a card to slide, and an **edge** for a neighbour to
show behind. The full-bleed banner is the opposite of both — it is the artwork
itself, drawn to the screen edges, with no card and no edge anywhere in it.

So the carousel does not draw over the banner. It takes the banner's place:
`heroMode` short-circuits on `carouselActive`, which stands down

- the still-art banner,
- the Spotify Canvas full-screen clip, and
- the contained-portrait clip,

all three at once. A Canvas is the artwork rather than a treatment of it, so it
is the one thing that could have stayed; it does not, because it is likewise
full-bleed and would leave the deck with no edge either.

This is the honest cost of the feature and the reason it is opt-in: **you cannot
have the still-art banner and the carousel at the same time.**

---

## 3. Where the visible sliver comes from

The carousel reserves no inset of its own. It does not need one: the player
already holds its controls off the screen edge by `PLAYER_GUTTER` (30dp), and on
a phone the sleeve is the widest thing inside that gutter. So the sliver at
either edge is whatever the gutter has left over once `CAROUSEL_NEIGHBOUR_GAP`
(8dp) is taken out of it — a shade over twenty dp — and it lines up with the
controls below instead of being a second, almost-equal inset fighting the first.

The gap is read as a **gap** and not as a peek, for the same reason the player's
own layout nails the sleeve to its width rather than to a size of its own: on a
short screen the sleeve comes off width-bound, and a hard-coded peek would then
be wrong by however much the card lost.

A neighbour is the sleeve's own square, parked one pitch — its side plus the gap
— beyond it, and both numbers are read off `artSize()` at **measure and
placement** time rather than at composition. Reading them at composition would
recompose the whole player once a frame to resize two cards that are mostly off
screen.

---

## 4. It is a phone idiom

`carouselActive` is

```kotlin
carouselEnabled &&
    playerFillsWindow(windowWidth) &&
    !landscapePlayerAvailable(windowWidth, windowHeight)
```

exactly as the full-bleed banner is. Landscape spends the screen's width on a
column of sliders and a lyric list, and a tablet's player is a column with a
backdrop of its own around it; neither has an edge for a neighbour to show
behind. Turning the phone sideways therefore returns the old player, banner and
all, without touching the switch.

---

## 5. How a release is read

The arithmetic lives in `PlayerSwipeCarousel.kt`, apart from the screen, because
it is the part that cannot be checked by looking at it — every answer lasts a
frame at a time, and a carousel that turns a card on a two-pixel wobble or
refuses one that was thrown reads as a broken animation rather than as a wrong
constant. `PlayerSwipeCarouselTest` pins all of it down.

A release asks for a track change when

| Condition | Meaning |
| --- | --- |
| `abs(velocity) >= CAROUSEL_FLICK_PX_S` (900 px/s) | it was **thrown** |
| or `abs(projected) >= cardSize * CAROUSEL_COMMIT_FRACTION` (28%) | it was **dragged** far enough |

where

```text
projected = drag + velocity * CAROUSEL_FLICK_LOOKAHEAD_S   // 0.12s
```

The projection is the point. Distance alone is the wrong question: a short fast
flick is meant to turn the card exactly as a long slow drag does, which is what
makes the deck feel like something thrown rather than something dragged.

Two guards keep a flick from standing on its own for nothing:

- `CAROUSEL_NOISE_FRACTION` (2% of the card) is a floor on the **projection**, so
  a press that wobbled a pixel and lifted cannot turn a track however long the
  finger rested there;
- below the flick speed, the projection still has to clear the commit fraction.

`cardSize` is the sleeve's drawn side in **pixels** and not the screen's: the
same flick that turns a track on a phone would be a fingernail's travel on a
tablet's much wider sleeve.

Leftwards travel is the next track and rightwards the previous, matching the
transport glyphs and the direction the deck actually moves.

---

## 6. Speed, measured over a window

`detectHorizontalDragGestures` hands out travel and not speed, so `CarouselFlick`
derives the speed from the travel — over a **trailing 100ms window**, not across
the whole gesture.

That window is the whole reason the class exists. A drag that crossed the screen
and then stopped dead before release is a drag that was *not* moving when it
ended, and a tracker that averaged the entire gesture would still be quoting the
speed it had a second earlier. Summing only the samples inside the window is what
makes a pause read as a pause.

Implementation notes:

- Samples are held in a **ring of ten** rather than a growing list: this is fed
  from inside a pointer gesture that runs for as long as the finger is down, and
  a gesture handler is not the place to be allocating. Touch arrives well under
  10ms apart, so the window is filled long before the finger has travelled
  anywhere.
- The sample carries the **event's own clock** (`SystemClock.uptimeMillis()` from
  the pointer event), so a slow frame reads as a longer gap rather than as a
  faster finger — but a sample whose clock went backwards is dropped rather than
  treated as negative time, since pointer events are allowed to arrive out of
  order on some devices.
- `reset()` on `onDragStart`, so the last gesture's speed cannot leak into this
  one.

---

## 7. Holding and handing over

The live drag and the settling spring are two separate values that are **added**
when the sleeve is drawn:

```kotlin
translationX = (carouselDrag.floatValue + swipeSettle.value) * (1f - collapse)
```

They move differently — the drag tracks the finger exactly, the spring takes
over on release — and adding them is what makes the handover invisible. The drag
is dropped *inside* the coroutine, immediately after the spring has been snapped
to the same place: dropping it first would show the deck at rest for the frame
between, and snapping first and dropping second cannot be done from outside,
since the snap suspends. `Animatable.snapTo` on an uncontended mutex does not
actually suspend, so the handover lands inside one frame and the sum is never
counted twice.

Both writes are read at **draw phase** (`graphicsLayer` lambda), so a finger's
worth of movement costs a layer update and not a recomposition of the player.

---

## 8. The ends of the queue

Travelling towards an end of the queue that isn't there is **resisted rather than
refused** — `CAROUSEL_EDGE_RESISTANCE` (0.3) — so a swipe at the last track reads
as *"you are at the end"* instead of as a dead screen, and the deck still springs
back. The position is clamped rather than wrapped: a carousel that rolled the
queue round would make a swipe at the last track bring in the first, and the
transport underneath it would not.

A release that could not have gone anywhere is `CarouselRelease.Return` rather
than a track change the caller has to veto, so there is one answer to branch on.

---

## 9. Honest limits

- **Off by default.** Nothing changes for anyone until the switch is found.
- **On, the full-bleed banner is gone** (and with it the Canvas clip) — see §2.
- **Phone portrait only.** Landscape and tablets keep the old player.
- **A neighbour is a `CARD_ART_PX` (480px) cover, not `PLAYER_ART_PX`.** It is
  only ever seen as a sliver, and the largest rung on the ladder is the wrong
  price for twenty dp of it — but a neighbour that has been dragged fully into
  view is therefore softer than the sleeve. The sleeve keeps the full ladder for
  itself.
- **The gap (8dp) was read off a reference screenshot** rather than measured on
  a device, so it is the one constant most likely to want tuning.
- **The deck is not a pager.** Past one card's side there is nothing left to
  reveal and travel is clamped, so a queue is still crossed one track at a time —
  which is what the transport it sits under does.
