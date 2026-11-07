# FreeMusic v08.11.26

v07.11.26 ka **fresh release rebuild** — same code, naya version number aur
naye release-signed APKs. Is build me koi naya feature nahi hai; pichle release
ka poora feature set (YouTube Music engagement row, per-song play counts,
polished Profile page) as-is ship ho raha hai.

Version bump reason: date rollover — `07.11.26` se build roll hua to generator
ne next version `08.11.26` (`versionCode 2026110801`) assign kiya.

## What's in this build

### Player — YouTube Music engagement row

- `Like` (real count ke saath), `Dislike`, `Comments` (count ke saath),
  `Save`, `Share`, `Download` aur `Mix` — sab scrubber ke upar ek hi row me.
- Counts live watch-queue response se aate hain, hard-coded nahi.
- Like/Dislike toggle hote hain (filled thumb + haptic feedback).
- Comments sheet tap par paginate hota hai (scroll par nahi).
- `Save` bookmark hai, heart nahi (heart already "like" ka matlab rakhta hai).

### Player — per-song play counts

- Har song row ke subtitle me plays dikhte hain —
  `Funda Arar · 8.8M plays`, `Al Sevgilim · 157M plays`.

### Profile page (Account & integrations)

- Artwork-led chrome — apne avatar ke colours pe paint hota hai, Home aur
  Explore jaisa.
- Asli unread notification badge (pehle fixed lime dot tha).
- Top bar me bell + Settings gear.
- Page ke andar Notifications aur Settings rows — signed-out aur signed-in
  dono state me.

### Explore

- Tinder-style left/right swipe deck.
- Liquid Glass controls aur Home-page jaisa header artwork background.

### Bugs fixed (v07.11.26 se carry forward)

- Profile ka Settings gear kabhi kuch nahi karta tha (`AnimatedContent`
  priority order bug) — ab Account ↔ Settings dono directions clean.
- Settings sheet me section headers duplicate render hote the.
- Locale parity — 15 non-English locales me 46 missing strings the, ab har
  locale me base ke saare 1161 keys maujood hain (`missing=0, extra=0`).
- Now Playing se volume bar option remove karke layout compact kiya gaya.

## Verification

- `:app:assembleProdRelease :app:assembleProdDebug` → **BUILD SUCCESSFUL**.
- APK badging verify: `versionCode=2026110801`, `versionName=08.11.26`.
- Emulator pe release-signed x86_64 APK install karke verify kiya gaya:
  - Home screen clean render, zero crash, zero ANR.
  - Now Playing engagement row live — saare 7 chips present
    (`Like`, `Dislike`, `Comments`, `Save`, `Share`, `Download`, `Mix`).
  - Profile (Account & integrations) page clean open, signed-out quick actions
    visible, zero crash.
  - Settings page clean open, saare groups visible, zero crash.
- Signed-in profile branch (hero, top songs, insights, badges) compile-verified
  hai — emulator pe Google account nahi hai, to wo branch runtime pe exercise
  nahi hua; real device par ek baar dekhna hai.

## Builds

- `app-prod-arm64-v8a-release.apk` — 53.69 MB — most modern phones
- `app-prod-armeabi-v7a-release.apk` — 45.39 MB — older 32-bit ARM
- `app-prod-x86_64-release.apk` — 59.45 MB — emulators / Chromebooks

Universal APK is baar bhi drop hai: GitHub ke 100 MB push limit se upar jaata
hai; har device in teen me se ek se covered hai.
