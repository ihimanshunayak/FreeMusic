# FreeMusic Explore — Liquid Carousel Redesign (v16.10.26.3)

## Objective

Replace v1.20's vertically-stacked Explore deck with a **horizontal
"liquid carousel"**: one large center card with two visible side peeks,
Apple-Music-style page-indicator pills, 3D parallax depth, and a true
liquid-glass card surface (Haze + glass refraction when available, with
a dynamic-palette wash fallback). The card itself becomes "rich" —
eyebrow, title, subtitle, tag chips, and two action buttons (Play +
Shuffle) — instead of a single artwork-and-title surface.

## Why this is a major improvement

| Problem in v1.20 | Fix in v16.10.26.3 |
| --- | --- |
| Only one card visible at a time — no preview of what's next | 1 large center + 2 side peeks (left + right) |
| Looks like a single image that changes, not a deck | Horizontal `LazyRow` with snap, clear "carousel" shape |
| 86% width centered leaves dead empty sides on phones | Center card ~70% width, peeks ~22% width each (visible 8% gap) |
| No progress indicator | Row of 8 dp pills, active = 22 dp wide, fades with 0.4 alpha |
| Card has minimal content (artwork + title + play) | Eyebrow + title + subtitle + 2 tag chips + Play + Shuffle |
| No 3D depth | `rotationY = ±8°` on side peeks, `translationZ` 12 dp on center |
| Opaque card on the page | `Modifier.liquidGlass(RoundedCornerShape(28.dp))` on Android 12+; Haze blur fallback; otherwise dynamic-palette wash |
| "Tap card to explore" caption was inside the artwork area | That caption moves to a row below the card; the card surface is reserved for media |
| Drag direction is vertical (same as Home scroll) | Drag is horizontal so it doesn't fight vertical page scroll |

## Scope (strict)

- Touched (rewritten):
  - `ui/components/ExploreCard.kt` — now a rich-content card (eyebrow,
    artwork, title + subtitle, tag chips, Play + Shuffle row). Accepts
    a `depthProgress` (1 = active, 0 = about to leave) and a
    `peekProgress` (0 = center, +1 = right neighbor, -1 = left
    neighbor) for transform math.
  - `ui/components/ExploreCardStack.kt` — now a horizontal carousel:
    `LazyRow` + `pager-style` snap, peek math, page-indicator
    pill row, `SharedTransitionLayout` host kept for the active
    card's artwork → detail hero transition.
  - `ui/screens/ExploreScreen.kt` — skeleton updated to carousel
    shape; public `ExploreScreen(...)` signature unchanged.
  - `docs/EXPLORE_STACKED_CARDS.md` — superseded by this document.
- Not touched: Home, Library, Search, Profile, Settings, mini-player,
  bottom navigation, app shell, theming, audio engine.

## Data

Existing `UiState<List<MoodGenreSection>>` is reused unchanged. Each
`MoodGenre` only carries `title, browseId, params, thumbnailUrl`, so
the new card derives a friendly **subtitle** and **two tag chips** from
the section it came from, e.g.:

| Section title | Card subtitle | Default tag chips |
| --- | --- | --- |
| `Moods`        | `Mood collection`            | `PLAYLIST`, `CURATED` |
| `Genres`       | `Genre deep-dive`            | `PLAYLIST`, `MIX` |
| anything else  | `Explore on FreeMusic`       | `PLAYLIST`           |

This keeps the contract zero-changes but lifts the visual density.

## Visual / motion spec

### Page composition (top → bottom)

1. Header — `Explore` title, 8 dp vertical padding (same as v1.20).
2. **Carousel** — `height = 460 dp`, `LazyRow` host. Each card is
   `width = 0.72 × pageWidth`, `height = 440 dp`, with `8% × pageWidth`
   side padding so the two neighbor peeks are visible.
3. **Page indicator** — `Row` of pills, 28 dp from the carousel bottom
   edge, `Spacer(8.dp)` between pills, 8 dp pill height, 8 dp width
   (active = 22 dp), `MaterialTheme.colorScheme.primary` for active
   pill, `onSurface.copy(alpha = 0.30)` for inactive.
4. **Action row** — `Row` of 2 glass buttons (`Play` + `Shuffle`) tied
   to the *currently active* card, 64 dp tall, 16 dp from the indicator
   row, 16 dp horizontal gutter.
5. **Section label** — `Text` of the section that the active card came
   from, `labelLarge`, `onSurface.copy(alpha = 0.7)`, 12 dp below the
   action row.

### Carousel transform math

For each card at index `i`, with active index `a`:

- `distance = i - a` (signed, integer)
- `absDist = |distance|`
- `depthProgress` = `1 - absDist * 0.45f` (clamped 0..1)
- `peekProgress` = `-distance.toFloat()` (so right neighbor = +1)
- `scale` = `lerp(0.86f, 1.0f, depthProgress)`
- `alpha` = `lerp(0.55f, 1.0f, depthProgress)` (so far peeks fade out)
- `rotationY` = `peekProgress * 8f` (max 8°)
- `translationX` (visual offset) = `peekProgress * (peekWidth * 0.6f)`
  to push the peeked card slightly off the centerline for parallax.

Cards beyond `|distance| > 2` are not laid out at all (so a 50-card
list only ever composes 5 cards).

### Snap behaviour

- `LazyRow` with `contentPadding = PaddingValues(horizontal = 14% page width)`
  to center the active card.
- A `PagerState`-like `rememberLazyListState` is updated by a `LaunchedEffect`
  that snaps to the nearest card after a fling ends (saves us from
  pulling in `HorizontalPager` which isn't in our Compose foundation
  1.7 subset).
- Snap `tween(380, easing = FastOutSlowinEasing)`.
- Reduced-motion: `Settings.Global.ANIMATOR_DURATION_SCALE == 0` →
  snap becomes 0 ms (effectively instant), `rotationY` and `scale`
  still apply but the rotation `tween` becomes 0 ms too.

### Liquid glass card surface

```
Box(
    Modifier
        .liquidGlass(RoundedCornerShape(28.dp))  // 12+ only
        .background(                              // 11 fallback wash
            Brush.verticalGradient(washDeep, wash, wash.copy(alpha = 0.65f))
        )
        ...
)
```

The `liquidGlass` modifier is a no-op on Android < 12 (it returns
`this` unchanged), so the wash always paints. On 12+ it draws the
backdrop with `colorControls(saturation = 1.5f) + blur + lens` and
then a tinted rectangle on top — same effect as the bottom nav and
the mini player. The card therefore picks up the carousel artwork
behind it through the glass when the page is at rest, and through the
dynamic palette wash once `reduceDynamicBlur` is on.

### Page indicator pills

- 8 dp height, 8 dp width, 4 dp `CircleShape`-equivalent pill.
- Active = 22 dp width, 8 dp height, full `primary` colour.
- `animateDpAsState` for the width and `animateFloatAsState` for the
  alpha to make the indicator glide as the carousel settles.

### Action row buttons

Two `Box` containers, `Modifier.liquidGlass(RoundedCornerShape(20.dp))`,
`height(56.dp)`, each with an `IconButton`-style 48 dp click target.
`Play` is `primary`-tinted (high emphasis), `Shuffle` is
`onSurface.copy(alpha = 0.7)` (low emphasis). Both have a
`glassContentColor()` label so they read well on any backdrop.

## Shared-element transition

`SharedTransitionLayout` is kept at the carousel host. The active
card's artwork carries a `sharedBounds(key = "explore-art-${browseId}",
animatedVisibilityScope = this@AnimatedVisibility)`. The existing
`MoodGenrePlaylistsScreen` already consumes a `sharedElement` on its
hero image, so the active card's image continues to flow into the
detail screen without any change on that side.

## Performance

- `LazyRow` only composes 5 cards at any time (active + 2 each side).
- `AsyncImage` is reused per `browseId` via Coil's `model` cache.
- `graphicsLayer { scaleX = scale; scaleY = scale; rotationY = ...; alpha = ... }`
  on each card so all transforms are GPU-side.
- No `Modifier.shadow` (uses a 2-px `drawRoundRect` instead) so the
  GPU doesn't have to allocate a separate offscreen buffer per card.

## Accessibility

- Each card is `clickable` with `onClickLabel = "Open ${item.title}"`
  so TalkBack reads the right thing.
- The page indicator pills are `Modifier.semantics { contentDescription =
  "Page X of Y" }` on the active pill only, so the rest stay silent.
- Reduced-motion: still navigates by index, no spinning, no scale jolt.

## Backward compatibility

- `ExploreScreen(state, listState, onCategoryClick, onRetry, refreshing,
  onRefresh, pullState, contentPadding, modifier)` signature is
  unchanged. The new `ExploreCardStack` and `ExploreCard` are private
  internals; no caller in `MainActivity.kt` needs to change.
- `MoodGenrePlaylistsScreen` is unchanged. Tapping a card still
  routes to it.
- `AppSettings.liquidGlass` is the existing toggle that controls
  `Modifier.liquidGlass(...)` — the new card respects it for free.

## What was kept

- `SharedTransitionLayout` for active-card → detail-hero flow.
- `depthProgress` parameter on `ExploreCard` (now driven by horizontal
  `peekProgress` instead of vertical scroll).
- `rememberArtworkPalette(imageUrl)` for the wash fallback.
- `moodColorForCard(title)` hash palette (used as the wash when the
  artwork hasn't loaded yet).

## What was removed

- Vertical scroll, vertical drag, scroll-state mirroring, the
  `rawScroll / animatedScroll` pair, the `peekHeight * index` math,
  the `BoxWithConstraints` total-height calculation. The carousel
  has its own scroll model (`LazyListState`) and a height-locked card.
- "Tap card to explore" inline caption (moved to the section label
  below the action row).
- `AnimatedVisibility` per card for the fade-in / fade-out at
  boundaries; the carousel uses `LazyRow`'s natural scroll lifecycle.
