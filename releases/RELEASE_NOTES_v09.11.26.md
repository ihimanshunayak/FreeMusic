# FreeMusic v09.11.26

**Update prompt ka bug fix** — release ab sahi time pe dikhta hai, aur Settings
me manual **"Check for updates"** row aa gaya hai.

Pichle release (v08.11.26) ke baad user ne report kiya tha ke *"apk me nhi show
ho rha hai new update"*. Root cause mil gaya aur fix ho gaya hai.

## Root cause

Version scheme calendar-date based hai — `DD.MM.YY` ya same-day build ke liye
`DD.MM.YY.N` (jaise `28.10.26.19`). Lekin `AppUpdateChecker.isNewer()` ise
**plain dotted numbers** ki tarah compare kar raha tha.

Iska matlab: `08.11.26` (8 November) ka numeric compare `28.10.26.19`
(28 October) se **chhota** dikhta hai — kyunki `8 < 28`. To October build wale
har user ko November release ka update prompt **kabhi** nahi milta tha. Yeh
silent, permanent failure tha — GitHub API healthy thi, assets upload the,
sirf comparison galat tha.

Yehi wahan tha jahan `docs/VERSION_DATE_SYSTEM.md` galat claim kar raha tha ke
`parseVersion` "no app-side changes needed" — ab doc sahi hai.

## What's in this build

### Update checker — calendar-date comparison

- Date-based versions ab **asli calendar date** ki tarah compare hote hain:
  year → month → day → same-day counter.
  `08.11.26` (8 Nov) ab correctly `28.10.26.19` (28 Oct) se naya hai.
- Legacy `1.N` scheme apna numeric compare retain karta hai (`1.10` > `1.9`).
- Cross-scheme rule: date-based release hamesha legacy build se naya hai, aur
  legacy release kabhi date-based build se naya nahi hota (date scheme baad me
  aaya tha).
- `-betaN` pre-release marker pehle ki tarah kaam karta hai — plain release
  uske aage rehta hai.

### Settings — "Check for updates" row

- **Settings → Miscellaneous → Check for updates** ab ek manual row hai.
- Subtitle installed version dikhata hai (`Installed version 09.11.26.3`).
- Tap karte hi row *"Checking for updates…"* dikhata hai, aur phir:
  - naya release mila → update dialog khulta hai (Download Now / Remind Me Later),
  - already latest → toast: *"You're on the latest version."*,
  - check fail hua → toast: *"Couldn't check for updates. Try again later."*
- Launch-time check silent hai aur ek hi baar chalta hai — pehle user ke paas
  dobara poochhne ka koi tareeqa hi nahi tha, aur flaky connection pe wo ek
  silent attempt hi akela attempt tha.

### Reliability

- Update check ab `Mutex` se guarded hai — launch check aur manual check ek
  saath chal ke `_available` pe race nahi karte (pehle slower/staler response
  jeet sakta tha).
- Ek failed check ab *"up to date"* ki tarah report nahi hota — alag
  `Failed` outcome hai, taaki "kuch naya nahi" aur "check hi nahi hua" mix na
  hon.
- `CancellationException` swallow nahi hota — cancelled check ko "failed"
  report karna galat tha.

### Locales

- 5 naye string keys (title, subtitle, checking, up-to-date, failed) — saare
  **15 non-English locales** me translate karke inject kiye gaye (Hindi real
  translation ke saath).
- Locale parity test (`LocaleStringsTest`) 15/15 green.

## Verification

- Focused unit tests → **BUILD SUCCESSFUL**, 40/40 pass:
  - `AppUpdateVersionCompareTest` — 16 tests (day/month/year rollover,
    same-day counter, cross-scheme, legacy numeric ordering, pre-release
    suffix, non-date fallback),
  - `AppUpdateAssetPickTest` — 9 tests,
  - `LocaleStringsTest` — 15 tests.
- `:app:assembleProdRelease :app:assembleProdDebug` → **BUILD SUCCESSFUL**
  (28m 22s; sirf pre-existing R8 kotlin-metadata warnings).
- APK badging verify: `versionCode=2026110903`, `versionName=09.11.26.3`.
- **Regression proof (emulator, release-signed APK):**
  - Purana build `28.10.26.19` install karke launch kiya → **"Software Update —
    Free Music 08.11.26 is available to download"** dialog aaya. Yehi scenario
    pehle silently fail hota tha.
  - Settings → Miscellaneous → **"Check for updates"** tap kiya → wahi update
    dialog khula (manual path bhi kaam karta hai).
  - Naya build `09.11.26.3` install karke launch kiya → **koi update dialog
    nahi** (sahi — 09.11.26.3 hi latest hai), Settings row
    "Installed version 09.11.26.3" dikhaata hai.
- Zero crash. Cold-start pe ek ANR aaya — trace se confirm hua ki wo
  Compose text-measurement/rendering path me hai aur **purane code ke saath
  bhi** (11:09 ka pre-existing trace) hota hai; mera change us path me nahi
  hai.

## Builds

- `app-prod-arm64-v8a-release.apk` — 53.70 MB — most modern phones
- `app-prod-armeabi-v7a-release.apk` — 45.40 MB — older 32-bit ARM
- `app-prod-x86_64-release.apk` — 59.50 MB — emulators / Chromebooks

Universal APK is baar bhi drop hai: GitHub ke 100 MB push limit se upar jaata
hai; har device in teen me se ek se covered hai.
