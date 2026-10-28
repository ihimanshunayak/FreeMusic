# FreeMusic v28.10.26.19

Player ka design ab compact ho gaya — screenshot mein jo **volume slider** tha, wo
poora hata diya gaya hai, aur uski jagah khali space kisi se bhar nahi gayi: freed
height ab seedha **album artwork** ko milti hai. Player ka look cleaner, tighter aur
zyada focused lagta hai.

## What's new

- **Volume slider hata diya gaya.** Player mein ab koi volume row nahi hai — na
  portrait (half-player) mein, na landscape mein. Pehle wahan ek volume capsule
  tha jo transport row aur action row ke beech mein baithta tha.
- **Compact player layout.** Volume row ka 50 dp footprint poori tarah collapse
  ho gaya, isliye transport row aur action row ab zyada paas hain. Jo vertical
  space bacha, wo artwork block ko gaya — sleeve pehle se **~302 dp** (792 px) ho
  gaya hai, jo pehle se noticeably bada hai.
- **Settings se "Hide volume bar" option bhi hata diya.** Ab wo toggle hi nahi
  hai, to confusion nahi hota ki volume kab dikhega aur kab nahi. Volume control
  ab ek hi jagah hai — player ke **Audio output** sheet mein, jahan apne
  AudioManager wala slider (device ka actual media volume, output change hone par
  dobara read hota hai) maujood hai.
- **Volume control kahin gaya nahi hai.** Hardware volume keys, system volume
  panel, aur Audio output sheet — teeno pehle ki tarah kaam karte hain.
- **Dead code poora hata diya.** `PlayerVolume` / `rememberPlayerVolume` state
  machinery, `VOLUME_ROW_HEIGHT`, `hideVolumeBar` preference + uska pref key,
  aur 16 locale files ke `hide_volume_bar` / `hide_volume_bar_subtitle` strings —
  sab removed. Koi orphaned code ya stale setting nahi bachayi.

## Layout kaise badla — technical detail

Portrait player ka foot block pehle `scrubber → transport → volume → actions` tha.
Ab `scrubber → transport → actions` hai, aur transport se actions ke beech sirf
ek `12.dp + controlSpread / 2` spacer hai (pehle yahan volume slot + uska 6 dp
balance inset bhi tha).

`controlSpread` / `slack` mechanism pehle se yahi kaam karta tha — foot block ki
natural height measure hoti hai, baaki height `slack` ban kar artwork ko jaati
hai, capped at `CONTROL_GAP_SPREAD_MAX = 24.dp`. Isliye 50 dp hatane se koi
hole nahi bana; gap absorb hua aur artwork bada hua.

Landscape player mein bhi wahi hua — `LandscapeMainPane` ka `volume` parameter
poora remove, right column ab caption → credits → lyric strip → scrubber →
transport hai.

## Notes

- Verified dev aur prod release builds par, emulator me: player bina volume row
  khulta hai, artwork bada render hota hai, Audio output sheet ka volume slider
  intact hai, zero ANR, zero crash.
- Light aur dark dono themes me layout check kiya gaya.
- Player ka lyric idle-timeout aur Spotify Canvas auto-hide ab sirf scrubber
  gesture ko dekhte hain (pehle volume drag bhi unhe rokta tha) — ab volume
  row hone se yeh effects galat se trigger nahi hote.

## Builds

- `app-prod-arm64-v8a-release.apk` — most modern phones
- `app-prod-armeabi-v7a-release.apk` — older 32-bit ARM
- `app-prod-x86_64-release.apk` — emulators / Chromebooks
- Universal APK is baar bhi drop hai: GitHub ke 100 MB push limit se upar jaata hai;
  har device in teen me se ek se covered hai.
