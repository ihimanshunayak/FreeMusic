# Play Counts

A small count next to a track in a list, saying how many times it has been
played on this device.

- [Where the number comes from](#where-the-number-comes-from)
- [Why a separate counter from the summary](#why-a-separate-counter-from-the-summary)
- [Why absent rather than zero](#why-absent-rather-than-zero)
- [The badge](#the-badge)
- [What it does not do](#what-it-does-not-do)

## Where the number comes from

`ListeningStats` already counted plays — `TrackEntry.plays` is incremented in
`record()` when a sample is classified as a play rather than as part of one.
Nothing about *counting* changed for this feature. What was missing was a way
to ask for the counts without reading the whole history, and that is the whole
of what was added:

- `plays: StateFlow<Map<String, Int>>` — plays per track id, every month
  merged, unplayed tracks left out.
- `computePlays()` — the merge, run on the writer coroutine.
- `publishPlays()` — rebuilds and publishes the map.

The threshold for a play is unchanged: `min(length / 2, PLAY_CEILING_MS)`,
never less than `PLAY_FLOOR_MS` (30s). Skipping through an album and stopping
on each track for four seconds adds nothing here, which is the behaviour the
Replay page already had and the reason its numbers read as listening rather
than as taps.

## Why a separate counter from the summary

`version` is bumped by every completed write, and `write()` runs on a
thirty-second cadence for as long as anything is playing. Keying the count map
on `version` would therefore re-read and re-parse every stored month twice a
minute, in full, to rebuild a map that had not changed — a JSON pass over up
to 36 months of history every thirty seconds of playback, for the sake of a
number that only moves when a track finishes.

`playsVersion` is a second counter, bumped only where the *number of plays*
could have moved:

| Event | `version` | `playsVersion` |
|---|---|---|
| Thirty-second flush of the open month | ✅ | ❌ |
| A counted play | ✅ | ✅ |
| An import | ✅ | ✅ |
| A pruned month, or a trimmed bucket | ❌ | ✅ (only if tracks went) |

The last row is the subtle one. `prune()` evicts by time played, so a track
can be dropped for having little of it — and a track with a count of 1 is
exactly the row a badge would have been drawn on. The counter is bumped only
when an entry actually left, so the common pass over a bucket under its caps
costs nothing.

The map is cached against `playsVersion` and the counter is captured *before*
the flush inside `computePlays()`, not after the read. Captured after, a play
counted mid-rebuild would be written by the flush, read back, and filed under
the version that play had already moved — right once, and then claiming the
stale answer was current. Captured before, a concurrent play can only make the
cache entry behind rather than wrong, which costs a rebuild and never a count.

## Why absent rather than zero

A list is mostly tracks that have never been played, and an entry per track in
the account would be a map of zeroes several hundred kilobytes wide, carried
around and walked on every recomposition so that nine rows out of ten could
draw a "0". Unplayed tracks are left out of the map entirely, and the badge
draws only what it finds. "No badge" and "no plays" are the same statement
here, which is why the map can afford to be sparse.

## The badge

`PlayCountBadge`, in the shared component file, drawn in `SongRowContent`
between the now-playing glyph and the duration. A glyph and a number — `▶ 128`
— rather than the phrase, because the trailing run of a row already carries a
download mark, a now-playing glyph, the duration and a menu button, and
"128 plays" on a phone would leave the title a few characters wide. The glyph
is what keeps it from reading as a track number.

The count is formatted with the existing `replay_play_count` plural, so
"1 play" and "1,204 plays" are both covered in all sixteen languages without a
second set of translations. The row reads:

```kotlin
val count = ListeningStats.plays.collectAsStateWithLifecycle().value[videoId] ?: return
```

— which is a single `StateFlow` read shared by every row on screen, not a disk
read per row. Nothing here polls: the map is republished when a play is
counted, so a number settles a moment after the track it belongs to finishes.

Counted plays are published even while no list is on screen, because a rebuild
is a disk pass and doing it while the app is quiet is cheaper than doing it
while a list is scrolling.

## What it does not do

- **No per-row filtering.** A count is drawn on any row for a track that has
  one, including album track listings where the leading box holds a track
  number rather than artwork. The number belongs to the track, not to the
  context it is listed in.
- **No server-side counts.** This is what has been played *on this device*, on
  this install. A reinstall starts from zero.
- **No all-time guarantee.** The history is kept to `KEEP_MONTHS` (36) months
  and each month to `MAX_TRACKS`, so a count is "every play still on disk"
  rather than "every play ever". That is the same horizon the Replay page
  reports against, and the two agreeing matters more than either being longer.
- **Nothing for an unplayed track.** Not a zero, not a dimmed zero — nothing.

## Turning it off

`AppSettings.showPlayCounts`, default **on**, in Settings under *Your data*
beside the Replay genre switch. Off, the badge is not drawn and no count is
added to any row; nothing else changes and no data is discarded, so turning it
back on restores every number immediately.
