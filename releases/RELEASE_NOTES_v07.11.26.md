# FreeMusic v07.11.26

Player ka engagement row ab YouTube Music jaisa ho gaya hai — Like, Dislike,
Comments, Save, Share, Download aur Mix, har song ke Now Playing screen pe.
Saath hi har song ke aage **kitne plays hue hain**, wo bhi dikhta hai.
Profile page ko bhi redesign kiya gaya hai aur uska chrome ab Home/Explore jaisa
artwork-led ban gaya hai.

## What's new

### YouTube Music engagement row (Now Playing)

- **7 actions ek hi row me.** `Like` (with real count), `Dislike`, `Comments`
  (with count), `Save`, `Share`, `Download` aur `Mix` — sab scrubber ke upar,
  ek hi line me. YT Music ki tarah.
- **Real counts, hard-coded nahi.** Like count YouTube ka apna short label hai
  (`2.5M`, `809K`), comment count bhi (`78K`, `17K`). Ye sab live watch-queue
  response se aate hain — koi placeholder nahi.
- **Like/Dislike actually toggle hote hain.** Active state visually alag hai
  (filled thumb) aur haptic feedback deta hai.
- **Comments sheet on tap khulta hai.** Pagination tap par hoti hai, scroll par
  nahi — taaki reader ka text finger ke neeche na sarke.
- **Save ek bookmark hai, heart nahi.** Credits row mein heart already "like" ka
  matlab rakhta hai; do hearts do alag cheezein represent karenge to rating row
  me wo allowed nahi hai.
- **Mix** button track se radio station start karta hai.

### Per-song play counts

- Har song row ab apne subtitle me plays dikhati hai —
  `Funda Arar · 8.8M plays`, `Al Sevgilim · 157M plays`. Ye YouTube Music ka
  catalogue tally hai, browse response ke saath hi aa jaata hai.

### Profile page (Account & Integrations) redesign

- **Artwork-led chrome.** Ab profile bhi apne avatar ke colours pe paint hota
  hai — Home aur Explore jaisa. Top fade scrim aur top-bar blur is page pe
  skip hote hain, warna wo wash ko paint kar dete. Chrome ka fade colour bhi
  wahi palette se aata hai, to screen ke top par seam nahi banta.
- **Asli notification badge.** Pehle bell pe lime dot hamesha painted rehta tha
  (ek fixed decoration). Ab dot sirf tab dikhta hai jab sach me unread
  notifications hon, aur bell tap karte hi list mark-as-seen ho jaati hai.
- **Top bar me Settings gear.** Bell ke saath ek gear bhi — profile se seedha
  Settings kholne ke liye.
- **Page ke andar Notifications aur Settings rows.** Profile ke andar hi dono
  milte hain — ab signed-out aur signed-in dono state me.

### Bugs fixed

- **Profile ka Settings gear kuch nahi karta tha.** `AnimatedContent` ke
  priority order me account page Settings se upar hai, to dono true hone par
  profile hi visible rehta tha aur tap silently ignore ho jaata tha. Ab route
  dono taraf se saaf hota hai (Settings → Account aur Account → Settings).
- **Settings sheet me section headers do baar render hote the.** `SettingsGroup`
  ka header `Text` block duplicate tha — isliye "RICH PRESENCE" aur "SCROBBLING"
  dono screen par ek se zyada baar dikhte the. Ek copy hata di gayi.
- **Locale parity.** 15 non-English locales me 46 missing strings the (edit
  profile fields, profile tab labels, settings rows) — unme se 27 Kotlin se
  reference hote the, to un screens par English beech me flash hota tha. Ab
  har locale me base ke saare 1161 keys maujood hain (`missing=0, extra=0`).
  Hindi me asli translation hai, baaki locales me English copy — jo resource
  system waise hi fallback deta.

## Notes

- Prod release aur prod debug dono builds green.
- `LocaleStringsTest` (15 locales, parameterized) ab pass karta hai.
- Emulator pe release-signed APK verify kiya gaya: profile page, notifications
  sheet, aur Settings navigation — zero crash, zero ANR.
- Signed-in profile branch (hero, top songs, insights, badges) compile-verified
  hai — emulator pe Google account nahi hai, to wo branch runtime pe exercise
  nahi hua.

## Builds

- `app-prod-arm64-v8a-release.apk` — most modern phones
- `app-prod-armeabi-v7a-release.apk` — older 32-bit ARM
- `app-prod-x86_64-release.apk` — emulators / Chromebooks
- Universal APK is baar bhi drop hai: GitHub ke 100 MB push limit se upar jaata
  hai; har device in teen me se ek se covered hai.
