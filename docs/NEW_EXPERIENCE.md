# New Experience

A single switch, in Settings, that swaps a handful of the app's control
surfaces for a redesigned set. Off, the app is exactly the app it was; on,
the same buttons appear in a new skin with a new icon family. Nothing is
half-applied and nothing is lost in the off state.

- [Why one switch](#why-one-switch)
- [What the switch changes](#what-the-switch-changes)
- [The button](#the-button)
- [The icon family](#the-icon-family)
- [Which icons go where](#which-icons-go-where)
- [Why the sleeve switch moved inside it](#why-the-sleeve-switch-moved-inside-it)
- [What is not covered](#what-is-not-covered)

## Why one switch

The player carousel that shipped in 1.14 arrived behind `player_carousel` — a
switch named after the one thing it changed. Everything since has been a
second such switch waiting for its own row, and a settings page that grows one
row per redesign is a page nobody can answer "am I using the new app or not?"
from. `new_experience` replaces it: one gate, one answer, and the rows it
governs are the same surface the app ships in its place.

The switch is off by default for the reason the carousel was. A control
surface is where someone is most likely to be annoyed by a change they did not
ask for, so the redesign is opted into rather than inflicted, and the app as
first opened is the app that was reviewed.

## What the switch changes

Three surfaces, and only three:

| Surface | Off | On |
|---|---|---|
| Now Playing sleeve | single card, leans at a sideways drag | carousel the neighbours swipe in from |
| Bottom bar tabs | 25dp glyph over its label, tint carrying selection | a glass disc under the selected glyph, no label |
| Library page's "new" tiles | flat `surfaceVariant` square with a `Plus` in it | the same square with a glass disc and a filled glyph |

Everything else — colours, type, spacing, the rest of the shelves, every other
page — is untouched. This is a control-surface redesign, not a theme, and it
has no light/dark story of its own because it does not need one: it is drawn
from the same theme tokens the surfaces it replaces were.

## The button

One composable, `NewExperienceButton`, is the whole of the visual change. It
draws a circular glass disc with the glyph centred in it:

- **Glass**, when the device supports it and the listener has glass on — the
  same `liquidGlass` surface the bottom bar and the nav bar already sample
  their backdrop through, so the disc is the material that is already on
  screen rather than a second one that nearly matches it.
- **`surfaceVariant`**, when glass is off or unsupported, so the control
  degrades to a flat disc rather than to nothing. `reduceDynamicBlur` lands
  here too, which is what that setting promises.

The disc is 34dp and the glyph inside it 19dp, in the ratio the reference
sheet uses. That is bigger than the 25dp glyph the bottom bar draws today, and
deliberately so — the disc *is* the tab's footprint now, and a disc drawn at
the old glyph's size would read as a badge pinned to the corner of one.

Selection is carried by the disc, not by the glyph: selected tabs get the
disc and full-strength tint, unselected get no disc and the same tint at 65%.
That is the nav bar's own rule, kept, because two bars a few pixels apart
disagreeing about how selection reads is worse than either rule alone.

## The icon family

The reference sheet is 24 icons in four rows of six, drawn solid and chunky on
a 24 grid — the same family as the app's `FreeMusicIcons` in weight and
roundness, but filled where those are stroked.

They live in a second object, `NewExperienceIcons`, rather than as more
members of `FreeMusicIcons`. The split is the point: the existing set's
docstring is about a 2.2px stroke reading as one family, and 24 filled glyphs
dropped into it would be a second family inside a container that claims to be
one. Two objects, two documented styles, and the switch picks which is asked.

They are drawn with `FillType.EvenOdd`, taking the outline of a shape and
punching the counters out of it, which is what lets one path draw a bell with
a hole in its handle and a download arrow with daylight around it. The
alternative — a filled shape plus a second path in the background colour —
paints a rectangle over whatever is behind the icon and is not an option.

## Which icons go where

Not all 24 are used; the sheet is a set, and a set is not a placement plan.
The eleven that map onto controls the app actually has:

| Sheet position | Icon | Used for |
|---|---|---|
| r0c0 | `Library` | bottom bar, Library tab |
| r0c1 | `Notifications` | bottom bar *and* the Home header's bell, which shares the glyph |
| r0c2 | `Clock` | bottom bar, History — the sheet's clock, not a second one |
| r2c0 | `Person` | the signed-in header |
| r2c1 | `Party` | a shared playlist's `NewShelfCard` |
| r2c2 | `Plus` | the same card's disc |
| r2c3 | `Heart` | saved/liked artwork |
| r2c4 | `Check` | a completed download |
| r2c5 | `Bookmark` | a pinned playlist |
| r3c0 | `Grid` | the library page's grid toggle |
| r3c1 | `Performance` | the settings row's own glyph |

The remaining thirteen — download, play, shuffle, search, home, lyrics,
equaliser, explore, albums, person-plus, music note, repeat, timer — have no
control on the surfaces this switch governs. They are drawn in the sheet, so
they are drawn here, and the ones with an obvious owner (search, play,
shuffle) are the first to reach for when one of those controls is redesigned
next. Drawing all 24 and wiring eleven is cheaper than drawing eleven and
discovering that the twelfth is what the next surface needed.

## Why the sleeve switch moved inside it

`player_carousel` and `new_experience` would have been two switches governing
one surface — the player — with no way to say what the difference between them
was without reading both subtitles. The carousel is *part of* the redesigned
player, not a separate feature of it, so its row is gone and its behaviour is
what turning the new experience on does to the sleeve.

This is a breaking change to a stored preference: `new_experience` reads
`player_carousel` when it has never been written, so anyone who had already
turned the carousel on still has it on after updating rather than being
silently reset. The old key is left in place, unread, so the migration is
idempotent and downgrading does not lose the answer either.

## What is not covered

- **Labels stay.** The redesign drops a tab's label to make room for the disc,
  which is the one way it is a downside rather than a trade: four unlabelled
  glyphs are less discoverable than four labelled ones, and the sheet's own
  icons are the only thing that carries the meaning. It is confined to four
  tabs that the whole app is arranged around, and it is what the reference
  asks for.
- **No theme change.** Light and dark are the app's, not this switch's.
- **No layout change.** Every disc occupies the box the control it replaces
  occupied, so nothing reflows and no spacing constant moves.
- **The desktop app is untouched.** This is an Android surface.
