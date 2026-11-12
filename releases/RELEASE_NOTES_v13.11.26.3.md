# FreeMusic v13.11.26.3

## What's new

- **Library sound intelligence:** listening time, peak time, and genre distribution cards now summarize Last.fm listening history. Tapping a card opens the matching Replay view.
- **Genre summaries:** genre time is attributed through one primary tag per artist so a track is not counted in several genres at once.
- **Profile fixes:** repaired profile navigation and show the currently playing track on the profile. Added the account and scrobbling settings screen and supporting text.
- **Replay cleanup:** the Library keeps the Replay entry distinct from the new listening insight cards.

## Verification

- `:app:compileProdDebugKotlin` → successful.
- `:app:assembleProdRelease` → successful.
- Installed the signed x86_64 release APK on the emulator and opened Library.
- Verified Listening time and Genre distribution both navigate to Replay. The emulator has no listening history, so Replay correctly shows its empty state.

## Builds

- `app-prod-arm64-v8a-release.apk` — arm64-v8a phones.
- `app-prod-armeabi-v7a-release.apk` — 32-bit ARM phones.
- `app-prod-x86_64-release.apk` — emulators and Chromebooks.

The universal APK is also in the local `FreeApp` backup. The published bundle follows the project's existing three-ABI release format.
