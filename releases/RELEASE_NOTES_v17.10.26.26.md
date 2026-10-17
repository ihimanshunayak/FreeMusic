# FreeMusic v17.10.26.26

Per-song play count on every row. YouTube Music-style: every song row now shows
how many times you have played that track on this device, sitting just to the
right of the duration. The counter is local — same model as the Replay totals,
it only ever grows on this phone.

## What's new

- **Play count badge on every song row.** A small "12 plays" mark (or "1 play")
  to the right of the duration. Hidden for tracks you have never played, and
  hidden altogether when the new setting is off, so a row reads as the row it
  used to be.
- **Your data → Show play counts.** A new switch in the *Your data* group sits
  next to *Work out genres*. Default on. The Replay totals are unaffected —
  this is purely a row-level display.
- **Local-only counter, reused from the existing Replay pipeline.** No new data
  collection, no network call. The same `track.plays` value the Replay page has
  been using to draw the songs chart now flows out to a `StateFlow` and into
  the rows.

## Notes

- A counted play bumps the play count immediately; a heartbeat that ticks the
  file version does not pay for a re-scan, so a 30-second playback does not
  walk 36 months of buckets.
- After an import (Backup → Restore), the badge re-publishes once the import
  completes, so the numbers you see on a fresh device match the file you
  brought in.
- 16 languages covered: each of `values/`, `values-de/`, `values-es/`,
  `values-fr/`, `values-he/`, `values-hi/`, `values-id/`, `values-it/`,
  `values-ja/`, `values-pl/`, `values-pt/`, `values-ru/`, `values-th/`,
  `values-tr/`, `values-vi/`, `values-zh/` has the new `show_play_counts`,
  `show_play_counts_enabled_subtitle` and `show_play_counts_disabled_subtitle`
  strings. Translations will land in a follow-up; the English copy is the
  fallback everywhere for now.

## Builds

- `app-prod-arm64-v8a-release.apk` — most modern phones
- `app-prod-armeabi-v7a-release.apk` — older 32-bit ARM
- `app-prod-x86_64-release.apk` — emulators / Chromebooks
- The universal APK is dropped because it exceeds GitHub's 100 MB push limit;
  every device is covered by one of the three above.
