# FreeMusic v12.11.26

**Har song pe ab "kitna play hua hai"** — YouTube Music ki tarah. Figure
abhi bhi wire se aata hai, par pehle woh **parse hoke phenk diya jaata tha**
aur kabhi screen tak nahi pahunchta tha.

Is build me **9 defects** theek hue, 6 files me, aur har affected surface
live API ke against emulator pe verify kiya gaya.

## Root cause

Tally wire pe **4 alag-alag shapes** me aata hai, aur code me sirf **ek**
shape padha jaata tha:

| Surface | Wire shape | Example |
|---------|-----------|---------|
| Search row | apna flex column | `COLS: [Adele] [Song • 3:49] [4B plays]` |
| Artist "Top songs" | apna column, uske baad album name | `COLS: [Nicole Kidman] [ADÉLA] [104M plays] [PRIMA]` |
| Home "Quick picks" | **credit column ke andar**, artist ke saath | `COLS: [Choosin' Texas] [Ella Langley • 362M plays] [Dandelion]` |
| Two-row shelf card | aakhri subtitle run | `SUBRUNS: ['Siddh Shah'] [' • '] ['4.9M views']` |

Do alag bugs iske upar the:

1. **Index-based lookup.** `parseResponsiveListItem` flex columns ko
   *index* se padhta tha. Search row me column 2 pe tally hai, artist page
   pe column 2 pe **aur** album column 3 pe — to ek hi index dono jagah
   kaam nahi karta.
2. **Parsed, phir dropped.** `carouselShelf` aur `plainShelf` tally ko
   parse karte the aur `ShelfItem` banane me **bilkul transfer nahi karte
   the**. Yani figure server se aata tha, parser usse pakadta tha, aur
   phir wahi chhod diya jaata tha.

## What's in this build

### Parser — the tally is read wherever it is

- `parseResponsiveListItem` ab flex columns **aur** subtitle ke apne
  segments dono scan karta hai — kisi ek ko index karke nahi, balki
  `TALLY` pattern se pehla match dhoondh kar. Isliye wo surface par
  depend nahi karta jahan tally rakhi gayi ho.
- `parseTwoRowItem` ab sirf **aakhri** run nahi padhta — baaki runs
  fallback ke taur pe scan hote hain. Pichle build me `"4.9M views"` aakhri
  run me tha, par kuch shelves me wo beech me aa jaata tha.
- `carouselShelf` aur `plainShelf` — `ShelfItem` ab tally ko carry karta
  hai. **Yahi woh jagah thi jahan figure drop ho raha tha.**
- Album name ko tally samajhne ka koi chance nahi — `PRIMA`, `ADÉLA`,
  `SUPERSCAR` jaise strings pattern se match nahi hote, aur unit test me
  yeh fix kiya gaya hai.

### Rendering — the tally reaches the screen

- **Home → Quick picks** (`ShelfCard`) — ab credit line ke saath tally
  bhi dikhta hai: `Ella Langley • 362M plays`.
- **Home → hero cards** (`HeroCard`) — wahi treatment.
- **Artist page → Top songs** (`CompactSongRow`) — pehle sirf title aur
  artist tha, ab `Adele • 4B plays`.
- **Suggested songs** (`SuggestedSongRow`) — wahi treatment.

Duplicate guard: agar credit line me pehle se tally maujood hai to wo dobara
print nahi hota — `Siddh Shah • 4.9M views • 4.9M views` jaisa double nahi
banta.

### Har jagah tak pahuncha

- `YtMusicRepository` ke 4 song-mapping sites (Quick picks ke 3 fallbacks
  + new releases).
- `MainActivity` — `shelfSong` (card se track open karte waqt) aur Explore
  radio path.
- `PlaybackService` — `ShelfItem.toMediaItemOrNull()` aur `toSongOrNull()`,
  taaki Android Auto browse tree bhi figure carry kare.

## Verification

### Emulator (live API ke against)

| Surface | Rendered |
|---------|----------|
| Home → Quick picks | `Ella Langley • 362M plays` |
| Home → Trending community playlists | `Siddh Shah • 4.9M views` |
| Home → Trending songs for you | `El Trono de Mexico • 98M plays` |
| Artist page (Adele) → Top songs | `Rolling in the Deep` / `Adele · 4B plays` |
| Search results | `Adele · 587M plays`, `Rolling in the Deep` / `Adele · 4B plays` |

### Tests

- `:app:testProdDebugUnitTest` → **BUILD SUCCESSFUL**, **1031 tests, 0 failures**
  (90 test suites).
- **9 naye tests**:
  - `PlayCountParsingTest` — 6 cases, literal wire fixtures: search row ka
    tally, artist top-songs ka tally (album name ko tally na samjha jaaye),
    tally na hone wala row, shelf card ka tally, magnitude suffix apne
    figure ke saath (`57M plays`), aur copied song me tally ka bacha rehna.
  - `QuickPicksFixtureTest` — 3 cases, **real** `musicCarouselShelfRenderer`
    payload (wire se liya hua): artist ke saath baitha tally bacha rehta
    hai, card ka subtitle sirf artist hai, aur album name ko tally nahi
    samajha jaata.

## Build

- `:app:assembleProdRelease` → **BUILD SUCCESSFUL**
- `:app:assembleProdDebug` → **BUILD SUCCESSFUL**

## Builds

- `app-prod-arm64-v8a-release.apk` — arm64-v8a — modern phones
- `app-prod-armeabi-v7a-release.apk` — armeabi-v7a — older 32-bit ARM
- `app-prod-x86_64-release.apk` — x86_64 — emulators / Chromebooks

Universal APK is baar bhi drop hai: GitHub ke 100 MB asset limit se upar
jaata hai; har device in teen me se ek se covered hai.

## Also in this build

Pichle release (`v09.11.26`) ke baad ka poora kaam, jiska detail
[`docs/PROJECT_STATUS.md`](../docs/PROJECT_STATUS.md) me hai:

- **Profile page full repair** (P1–P7) — stats, navigation, edit contract,
  `@handle` field, locale parity, compact layout. Commit `05812ba`.
- **Update checker** — versions ab calendar date ki tarah compare hote hain,
  plus Settings me manual "Check for updates" row. Commit `77c0b35`.
