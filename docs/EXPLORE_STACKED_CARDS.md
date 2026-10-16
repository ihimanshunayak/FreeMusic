# FreeMusic Explore — Fluid Stacked Cards Redesign (v1.20)

## Objective
Replace the current 2-column grid of mood/genre cards on the Explore page
with a **vertically stacked, scroll- and gesture-driven card deck** in the
style of the reference video: large cards with overlap, depth, subtle
scale, and a shared-element expansion into the existing
`MoodGenrePlaylistsScreen`.

## Scope (strict)
- Touched: `ui/screens/ExploreScreen.kt` (rewritten), new
  `ui/components/ExploreCardStack.kt` + `ui/components/ExploreCard.kt`.
  One wiring change in `MainActivity.kt` (route to the new screen).
  `MoodGenrePlaylistsScreen` (the destination of `onCategoryClick`) is
  reused unchanged.
- Not touched: Home, Library, Search, Profile, Settings, the
  mini-player, bottom navigation, app shell, theming, audio engine.

## Data
The existing data source still emits
`UiState<List<MoodGenreSection>>`, where each `MoodGenreSection` has
`title + items: List<MoodGenre>` and each `MoodGenre` has
`title, browseId, params, thumbnailUrl`. **No data layer changes.**
The redesign flattens all sections into one ordered list of
`ExploreCard` (one per `MoodGenre`), grouped visually by
"section index" so the header label can change as the active card
crosses a section boundary (a nice extra touch borrowed from Apple
Music's segmented-paging style).

## Visual / motion spec
- Each card is ~62% of the page height, 86% of the page width,
  centered, with 18 dp corner radius. A subtle inner highlight and
  a soft shadow give it the "lifted" feeling from the reference.
- Stack offset: each card is offset vertically by `-(cardIndex -
  activeIndex) * peekHeight` (peekHeight ≈ 56 dp), with `scaleX/Y =
  1 - |offset| * 0.04f` and `alpha = 1 - |offset| * 0.18f`, clamped
  to >= 0.55 alpha and 0.88 scale.
- Z-order: `activeIndex` on top, behind it `activeIndex - 1` then
  `activeIndex + 1` (so a card below is still visible above the one
  two slots behind, matching the reference's "card-in-front-of-card"
  reveal).
- Scrolling: a `LazyColumn` is **not** used (would force the host
  list to scroll, breaking snap). Instead, a single
  `Column`-with-`Modifier.verticalScroll` containing N `Box`-stacked
  children, each tied to a `graphicsLayer { translationY = ... }` driven
  by the scroll offset. A `derivedStateOf` over `scrollState` recomputes
  each card's offset only when the value actually changes; combined with
  Compose's frame-pumped recomposition this stays at 60/120 fps.
- Gesture: a `pointerInput { detectVerticalDragGestures }` adds a
  fling inertia to the next active card when the user releases. Snapping
  uses `Animatable` with `tween(420, easing = FastOutSlowInEasing)`
  for a controlled "click" feel; the reference video's softness is
  approximated by tweening scale and translation together so the card
  doesn't pop.
- Card expansion (tap → detail): a `SharedTransitionLayout` with a
  `SharedContentState(key = "card:$browseId")` wraps the stack. Tapping
  navigates to `MoodGenrePlaylistsScreen` with `AnimatedVisibility`
  + `sharedBounds` modifiers animating the card into a full-bleed hero
  at the top of the detail screen. Back navigation reverses the
  animation and restores the active index.
- Palette: `rememberArtworkPalette(thumbnailUrl)` per card so the
  card's background wash, deep shadow, and accent gradient all derive
  from the artwork, matching the rest of FreeMusic's dynamic-palette
  system. If `thumbnailUrl == null` we fall back to the existing
  `moodColor(title)` hash palette.
- Reduced motion: if `Settings.Global.getFloat(ANIMATOR_DURATION_SCALE)`
  is 0, all snaps become instant (no tween, no spring).

## Responsive
- Mobile: 1 card visible at a time, peek = 56 dp.
- Tablet (width >= 600 dp): 2 cards visible side-by-side, each
  rotated `8 deg` and offset diagonally for a "fanned deck" feel.
  Same scroll/gesture logic; the per-card offset is applied in both
  axes.

## Performance
- All transforms go through `graphicsLayer` (translationY, scaleX,
  scaleY, alpha) — no layout, no recomposition of children.
- `ArtworkPalette` is cached on a per-`thumbnailUrl` basis
  (existing implementation already does this).
- `AsyncImage` is shared between the stack card and the detail-screen
  hero through a single `Model` so Coil doesn't reload on transition.

## Files
1. **NEW** `app/src/main/java/com/ihimanshunayak/freemusic/ui/components/ExploreCard.kt`
   — single `ExploreCard(...)` composable, ~120 lines, with the artwork,
   the title pill, the mood color wash, the corner badge, the play button.
2. **NEW** `app/src/main/java/com/ihimanshunayak/freemusic/ui/components/ExploreCardStack.kt`
   — the deck host. ~260 lines. Owns the scroll state, derives per-card
   transforms, hosts the `SharedTransitionLayout` boundary.
3. **MODIFIED** `app/src/main/java/com/ihimanshunayak/freemusic/ui/screens/ExploreScreen.kt`
   — kept the file path so `MainActivity` keeps compiling. The internal
   `ExploreScreen` composable is now a thin wrapper that delegates to
   `ExploreCardStack` for `UiState.Success`, keeps the existing skeleton
   and error state. The companion `MoodGenrePlaylistsScreen` is
   extended by 30 lines to participate in the shared-element expansion.
4. **MODIFIED** `app/src/main/java/com/ihimanshunayak/freemusic/MainActivity.kt`
   — passes an extra `onSharedTransition: (MoodGenre) -> Unit` callback
   (default: `onCategoryClick`) so the new screen can trigger
   navigation with the same semantics the old grid had.

## Out of scope
- Discovery categories the existing `MoodGenre` data doesn't expose
  ("Trending Now", "New Releases", "Made for You" — those would
  require new backend endpoints; spec §3 forbids that).
- Removing or modifying the existing 2-column grid fallback.
- Changing the bottom navigation or the mini-player.

## Risks
- The `SharedTransitionLayout` API is experimental; we declare
  `@OptIn(ExperimentalSharedTransitionApi::class)` in both files and
  pin a single `SharedTransitionScope` ancestor.
- The peek-height constant was tuned against a 360x800 viewport; on
  tablets the per-axis scale is reduced. If real devices show
  clipping we'll follow up in v1.21.
