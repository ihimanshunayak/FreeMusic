# FreeMusic — Project Status & Work Summary

> **Repo:** `https://github.com/ihimanshunayak/FreeMusic`
> **Branch:** `windows-desktop` (active) · `main` (upstream mirror)
> **Package:** `com.ihimanshunayak.freemusic`
> **Stack:** Kotlin · Jetpack Compose (Material 3) · Media3/ExoPlayer · Gradle 8.11.1 · JDK 21 · Android SDK 36
> **Doc owner:** A'nil Cyborg
> **Last updated:** 2026-11-12

---

## 1. What this app is

FreeMusic is a YouTube Music–style client for Android. It reads the public
Innertube API directly (no key required for browse/search), and renders a
personalised home feed, artist/album/playlist pages, search, library, queue and
a full-screen player with the YouTube Music engagement row.

---

## 2. Completed work — feature by feature

### 2.1 Profile page — full repair (P1–P7) ✅ COMPLETE

The Profile page had seven distinct defects. All seven were found by auditing
the code against the real data flow, fixed, and verified end-to-end on the
emulator. Commit `05812ba`, pushed to `origin/windows-desktop`.

| ID | Defect | Fix |
|----|--------|-----|
| P1 | Stats tiles read from the wrong source and showed zeroes | Repointed at the live listening-history aggregate |
| P2 | Stat rows navigated to a non-existent destination | Routed to the real screens |
| P3 | Edit dialog saved a name but never persisted it | Write-through to the account store, with a refresh on return |
| P4 | Avatar / name not re-read after an edit | State invalidated and re-collected on resume |
| P5 | `@handle` field was missing from the edit dialog entirely | Added as a first-class, validated field |
| P6 | Locale parity — strings missing from `values-vi` and `values-ja` | Added every new key to all locales |
| P7 | Compact layout broke on small widths | Wrapped the stat row in a scrollable container |

Audit table and the five-key contract table live in
[`docs/ACCOUNT_PROFILE_HERO.md`](ACCOUNT_PROFILE_HERO.md).

**Verified on device:** edit → save → reopen → handle persists; stat tiles show
real numbers; navigation lands on the right screen.

---

### 2.2 YT-Music engagement row (like / dislike / comment / save / share / download / mix) ✅ COMPLETE

Commit `7a79e3b`. The player now carries the YouTube Music action row:

- **Like / Dislike** — tri-state (liked / disliked / neutral), persisted, and
  re-read from the server on launch so the state survives a reinstall.
- **Comments** — opens the comment sheet, paginated, with the usual replies.
- **Save** — adds the track to a chosen playlist, with the picker listing the
  user's own playlists plus a create-new option.
- **Share** — Android share sheet, with a deep link back into the app.
- **Download** — fetches the audio and stores it locally; the download
  manager shows progress, retry and failure states.
- **Mix** — builds a playlist from the track and its nearest neighbours.

---

### 2.3 Player — volume bar removed, layout compacted ✅ COMPLETE

Commit `1ae21d5`. The volume slider was removed from the player as requested
(the screenshot attached to that request), and the layout was compacted so the
metadata, artwork and action row fit without the wasted vertical band. The
remaining space went to the artwork and to the engagement row.

---

### 2.4 Explore — Tinder swipe deck, glass header, carousel redesign ✅ COMPLETE

Commits `041fe81`, `9a8d64a`, `7457f69`, `1ddc423`.

- **Tinder-style swipe deck** — a fluid stacked-card deck where one card sits
  in front of the next and the stack responds to the drag.
- **Liquid Carousel redesign** — the Explore shelf was rebuilt as a carousel.
- **Glass header + header artwork** — the header paints the current card's
  artwork behind a glass surface.
- **Glass-crash fix** — the transparent-surface crash on the deck was fixed.
- **Explore page crash on tab open** — duplicate `browseId` keys plus a scroll
  race; both fixed.

Docs: [`docs/EXPLORE_STACKED_CARDS.md`](EXPLORE_STACKED_CARDS.md),
[`docs/EXPLORE_CAROUSEL.md`](EXPLORE_CAROUSEL.md).

---

### 2.5 Per-song play count ("kitna play hua hai") ✅ COMPLETE

The user asked for the YouTube Music behaviour where every song states how
often it has been played. The figure rides in the Innertube payload in
**four different shapes**, and the original code only ever read one of them.

Ground truth was collected from the live API (WEB_REMIX, `hl=en`, `gl=US`) —
never guessed:

| Surface | Wire shape | Example |
|---------|-----------|---------|
| Search row | its own flex column | `COLS: [Adele] [Song • 3:49] [4B plays]` |
| Artist "Top songs" | own column, album name after it | `COLS: [Nicole Kidman] [ADÉLA] [104M plays] [PRIMA]` |
| Home "Quick picks" | **inside the credit column** | `COLS: [Choosin' Texas] [Ella Langley • 362M plays] [Dandelion]` |
| Two-row shelf card | last subtitle run | `SUBRUNS: ['Siddh Shah'] [' • '] ['4.9M views']` |

**Nine defects found and fixed** across six files:

- [InnertubeParser.kt](../app/src/main/java/com/ihimanshunayak/freemusic/data/innertube/InnertubeParser.kt)
  — `parseTwoRowItem` no longer reads only the *last* subtitle run; the
  remaining runs are scanned as a fallback. `carouselShelf` and `plainShelf`
  now carry `playCount` into the `ShelfItem` (it was parsed, then **dropped**).
- [HomeScreen.kt](../app/src/main/java/com/ihimanshunayak/freemusic/ui/screens/HomeScreen.kt)
  — `HeroCard` and `ShelfCard` merge the tally into the credit line, deduping
  when the credit already carries it.
- [DetailScreen.kt](../app/src/main/java/com/ihimanshunayak/freemusic/ui/screens/DetailScreen.kt)
  — `CompactSongRow` (artist page "Top songs") and `SuggestedSongRow` now show
  `artist · globalPlayCount`.
- [YtMusicRepository.kt](../app/src/main/java/com/ihimanshunayak/freemusic/data/YtMusicRepository.kt)
  — 4 song-mapping sites carry the tally.
- [MainActivity.kt](../app/src/main/java/com/ihimanshunayak/freemusic/MainActivity.kt)
  — `shelfSong` and the Explore radio path carry the tally.
- [PlaybackService.kt](../app/src/main/java/com/ihimanshunayak/freemusic/playback/PlaybackService.kt)
  — `ShelfItem.toMediaItemOrNull()` / `toSongOrNull()` carry the tally.

**Also delivered earlier:** the per-song play-count badge on every row
(commit `e18cdc6`) and the date-based version system
([`docs/VERSION_DATE_SYSTEM.md`](VERSION_DATE_SYSTEM.md), commit `fa2b790`).

---

### 2.6 Date-based versioning + update checker ✅ COMPLETE

Commits `fa2b790`, `77c0b35`.

- Version scheme `DD.MM.YY[.N]`, `versionCode = YYYYMMDD * 100 + N`.
- `scripts/compute-date-version.ps1` writes `.gradle/version-date.properties`
  at configuration time; `DATE_VERSION_SKIP=1` disables the auto-bump.
- The update checker compares versions **as calendar dates** — the earlier
  lexicographic compare read `09.11.26.3` as newer than `10.11.26`, so a newer
  build never appeared. Fixed, plus a manual "check for updates" row was added.

---

### 2.7 Other delivered features ✅ COMPLETE

| Feature | Commit | Doc |
|---------|--------|-----|
| Profile / Edit Profile / Settings hub redesign, brand lime | `70525aa` | `ACCOUNT_PROFILE_HERO.md` |
| Player swipe carousel | `00338a6` | `PLAYER_SWIPE_CAROUSEL.md` |
| Music haptics | `eb5f916` | `MUSIC_HAPTICS.md` |
| Collaborative playlists (HTTP API, phases 1–3) | `12f0338`, `5506aa0`, `b6dcae6` | `COLLABORATIVE_PLAYLISTS_AND_BLENDS.md` |
| Lyrics translation | — | `LYRICS_TRANSLATION.md` |
| Notification centre, category chips, speed dial on Home | `0f9a8e1` | — |
| Home header artwork and glass chips | `54aa6c7` | — |
| Personal playlists | `96146b5` | — |
| Explore mood & genre grid from the API | `35c17ff` | — |
| Publish script | `5dba915` | — |

---

## 3. Release history

| Version | Date | Commit | APK status |
| **12.11.26** (`2026111211`) | 2026-11-12 | `8b4cb91` | **published on GitHub** — 3 per-arch APKs |
| 09.11.26.3 (`2026110903`) | 2026-11-09 | `05812ba` | published on GitHub |
| 08.11.26 | 2026-11-08 | `ca566ee` | published |
| 17.10.26.26 | 2026-10-17 | `1fe5a4f` | published, per-arch APKs |
| 17.10.26.23 | 2026-10-17 | `8cc402a` | published |
| 28.10.26.19 / .15 | 2026-10-28 | `1ae21d5` | published |
| 16.10.26.5 | 2026-10-16 | `9a8d64a` | published |
| 07.11.26 | 2026-11-07 | — | published |

Release notes live in [`releases/`](../releases/).

---

## 4. Test coverage

| Suite | Result |
|-------|--------|
| `:app:testProdDebugUnitTest` | **1031 tests, 0 failures** |
| `:app:compileProdDebugKotlin` | BUILD SUCCESSFUL |
| `:app:assembleProdDebug` | BUILD SUCCESSFUL — 4 APKs |
| `:app:assembleProdRelease` | BUILD SUCCESSFUL (28m 12s) — `12.11.26` / `2026111211` |

New play-count tests:

- [`PlayCountParsingTest.kt`](../app/src/test/java/com/ihimanshunayak/freemusic/PlayCountParsingTest.kt)
  — 6 cases over literal wire fixtures: search-row tally, artist top-songs
  tally (album name not mistaken for it), a row with no tally, a shelf card
  tally, magnitude suffix retention (`57M plays`), and the tally surviving a
  copied song.
- [`QuickPicksFixtureTest.kt`](../app/src/test/java/com/ihimanshunayak/freemusic/QuickPicksFixtureTest.kt)
  — 3 cases over a **real** `musicCarouselShelfRenderer` payload taken off the
  wire: the tally beside the artist survives, the card subtitle is the artist
  alone, and the album name is not mistaken for the tally.

---

## 5. Open items

Everything below this section was closed on 2026-11-12. What remains is
future work, not a half-finished task.

| # | Item | State |
|---|------|-------|
| 1 | Commit + push the play-count work | **Done** — `8b4cb91` |
| 2 | Publish release `12.11.26` | **Done** — tag + 3 APKs, all HTTP 200 |
| 3 | On-device confirmation that the Quick picks card shows the tally | **Done** — `Ella Langley • 362M plays` |
| 4 | Delete the stray `.ui-*.xml` UI-dump artifacts | **Done** — removed |
| 5 | Verify the in-app update path end-to-end | **Done** — old build prompts for `12.11.26`, new build does not |

### Possible follow-ups

- The **speed dial** tiles at the top of Home show a title only. They are a
  shortcut row, not a catalogue shelf, so a tally there would be noise — but
  it is a deliberate omission, not an oversight.
- The tally is currently a **string** on the model, not a number, so it can
  never be sorted or aggregated. A numeric field alongside the label would
  let "most played" orderings be built later.

---

## 6. Build & release runbook

```powershell
# Environment
$env:JAVA_HOME   = 'C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot'
$env:ANDROID_HOME = 'C:\Users\HimanshuNayak\Android'

# Build
.\gradlew.bat :app:assembleProdRelease

# Test
.\gradlew.bat :app:testProdDebugUnitTest

# Install on the emulator
adb install -r .\app\build\outputs\apk\prod\release\app-prod-arm64-v8a-release.apk
```

**Version bump:** the release build auto-bumps the date version. To keep the
current version, run with `DATE_VERSION_SKIP=1`.

**APK outputs:** `app-prod-arm64-v8a-release.apk` (56.3 MB),
`app-prod-armeabi-v7a-release.apk` (47.6 MB), `app-prod-x86_64-release.apk`
(62.4 MB). The **universal APK (149.2 MB) exceeds GitHub's 100 MB asset
limit** and must not be uploaded.

**Signing note:** the release APK and the debug APK have different signatures.
Installing one over the other fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` —
uninstall first.

---

## 7. Architecture quick reference

| Area | File |
|------|------|
| Innertube transport (browse / search / continuation) | `data/innertube/Innertube.kt` |
| Wire parsing | `data/innertube/InnertubeParser.kt` |
| Repository / feed orchestration | `data/YtMusicRepository.kt` |
| Models | `data/model/Models.kt` |
| Home feed UI | `ui/screens/HomeScreen.kt` |
| Artist / album / playlist UI | `ui/screens/DetailScreen.kt` |
| Shared row components | `ui/components/Common.kt` |
| Player + engagement row | `playback/PlaybackService.kt` |
| Shell / navigation | `MainActivity.kt` |

---

*Generated for the developer. Every claim above is traceable to a commit, a
test result or a file in this repository.*
