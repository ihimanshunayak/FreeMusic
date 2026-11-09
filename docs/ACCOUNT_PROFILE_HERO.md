# Account & Integrations — Profile Hero redesign

Date: 2026-10-16
Author: AnilCyborg / Software Engineer

## What this is

The current `AccountAndScrobblingScreen` is a long flat list of rows. A signed-in
user lands on it and is met with a 52dp avatar and an email — that is it for the
section that is supposed to be the page's own identity. Everything else
(Discord, ListenBrainz, Last.fm, scrobbling timing) is wiring rather than the
page itself.

This change introduces a **profile hero** at the top of the page: a section
that looks like a profile (avatar, name, status, stats) and that is the only
part the page sets out to be. The wiring (scrobbling, rich presence, sign-out)
moves down and keeps the same group structure.

The reference image is a third-party clubs/social app (Lydia Fletcher, 9:41
status bar). The information architecture is what is being borrowed, not the
visual treatment:

| Reference                | FreeMusic equivalent                |
|--------------------------|--------------------------------------|
| 9:41 status bar          | unchanged                            |
| Avatar (circle)          | Signed-in YouTube account avatar (or a local override if the user has picked one) |
| Display name             | Display name (overridable)           |
| Status line (pill)       | "What's playing lately" or user-set status (overridable) |
| 280 followers            | **Hours listened** (lifetime)        |
| 145 following            | **Tracks played** (lifetime)         |
| Total XP gained 39 512   | **Playlists** (library count)        |
| Events attended 32       | **Sources** (music sources added)    |
| Log out                  | Sign out (existing DestructiveRow)   |
| Edit profile             | New EditProfileDialog (name / status / bio / avatar) |

The follow/follower numbers are not in a music player — but the *visual*
treatment (4-up stat strip, liquid glass) is what is being matched, with
numbers that are actually meaningful for the app.

## Why this is being done now

User asked for the Account & Integrations page to be improved and to be done
in liquid glass with a profile-style layout. The codebase already has every
piece the design needs:

- `rememberArtworkPalette` already derives a tint palette from a URL
- `Modifier.liquidGlass` and `lightweightLiquidGlass` already do the glass
- `ArtworkBackdrop` already blurs a sleeve into a wash
- The account card, the rich-presence row and the scrobbling groups already
  exist; they are being **re-arranged**, not rewritten

So the work is composition, not invention.

## Design rules

1. **Everything in the hero is in the existing palette system.** The hero's
   background, divider, accent and text colours all come out of
   `rememberArtworkPalette` over the avatar URL. There is no second palette
   and no hand-picked swatch.
2. **Glass where glass already exists.** Stat tiles, the edit button, and
   the status pill use `Modifier.liquidGlass` with `RoundedCornerShape`. On
   pre-Android-12 devices or when the user has turned liquid glass off, the
   tiles fall back to `MaterialTheme.colorScheme.surfaceVariant` — the same
   fallback the floating nav bar already uses.
3. **Dynamic, not animated for its own sake.** The hero's content does not
   pulse, breathe or shimmer. The state is "what is the signed-in user
   actually called" — not an event. The only motion is the palette settling
   in (already handled by `ArtworkPalette`'s `TINT_FADE_MS`).
4. **Reduce-animation respect.** `AppSettings.reduceAnimation` already gates
   the palette crossfade; the rest of the hero is static and needs no
   additional gate.
5. **No new persistent state for things that already have state.** The
   display name override sits in `SharedPreferences` via `AppSettings`; the
   avatar override stores its local `content://` URI. The dialog does not
   duplicate the YouTube account name — it only **overrides** it.
6. **Haptics.** The status pill and edit button use the existing
   `Haptic.click` / `Haptic.soft` calls — same as the rest of Settings.

## Files touched

- `app/src/main/java/com/ihimanshunayak/freemusic/data/settings/AppSettings.kt`
  — add `displayNameOverride`, `statusLineOverride`, `bioOverride`,
  `avatarOverrideUri` flows + prefs keys + setters.
- `app/src/main/java/com/ihimanshunayak/freemusic/data/profile/ProfileStats.kt`
  — new, single object exposing the four stats from existing data sources.
- `app/src/main/java/com/ihimanshunayak/freemusic/ui/components/ProfileHero.kt`
  — new, the hero itself.
- `app/src/main/java/com/ihimanshunayak/freemusic/ui/components/EditProfileDialog.kt`
  — new, the dialog opened by "Edit profile".
- `app/src/main/java/com/ihimanshunayak/freemusic/ui/screens/AccountAndScrobblingScreen.kt`
  — wire the hero in at the top; replace the existing `AccountCard(...)` with
  the hero; sign-out becomes a DestructiveRow further down. The scrobbling
  groups, ListenBrainz, Last.fm, Discord, scrobble timing all stay where they
  are; nothing in their wiring changes.
- `app/src/main/java/com/ihimanshunayak/freemusic/MainActivity.kt`
  — pass new profile params (display name, status, bio, avatar override,
  stats) into the screen; open `EditProfileDialog` on demand.
- `app/src/main/res/values/strings.xml`
  — new strings: `profile_title`, `edit_profile`, `cancel`, `save`,
  `status_hint`, `bio_hint`, `display_name_hint`, `profile_avatar_alt`,
  `stat_hours_listened`, `stat_tracks_played`, `stat_playlists`,
  `stat_sources`, `sign_out_confirm`.
- `app/src/main/res/values-hi/strings.xml` (and the other 15 locales)
  — same set, translated by the existing string-resource convention.

## Out of scope (deliberate)

- A new user signup flow. This is a YouTube-backed music player; the
  signed-in user is the Google account. There is no separate
  FreeMusic-account signup.
- A profile photo gallery / camera. The avatar picker is a single
  `ActivityResultContracts.PickVisualMedia` call; the chosen image is
  copied into the app's files dir and read from there thereafter.
- Privacy controls. Status / bio are local to the device and never leave it.
- Following / followers. There is no other user to follow.

## Acceptance

- Page opens to the new hero when the user is signed in; the old `AccountCard`
  is gone.
- The four stats update as the user does things (plays a song, adds a
  playlist, adds a source). They are not "launch-time only".
- "Edit profile" opens a dialog that persists its fields across
  process-death.
- The hero's palette matches the avatar — yellow avatar → yellow-tinted
  hero, dark avatar → dark-tinted hero.
- Liquid glass is on when the user has the global switch on, off when they
  don't, and a fallback on pre-Android-12.
- Scrobbling, rich presence, sign-out, and the rest of the page still work
  exactly as before.

## Follow-up fixes (2026-11-09)

A deep audit of the page after the redesign found seven defects, all of which
made the page look broken to a user rather than merely rough. They are fixed
in the same release as the redesign itself, so the two ship together.

| # | Defect | Cause | Fix |
|---|--------|-------|-----|
| P1 | The stats never moved after a play | `rememberProfileStats` wrapped its IO read in a `scope.launch` *inside* a `LaunchedEffect`. `rememberCoroutineScope()` is bound to the composition, not to the effect, so the inner launch survived cancellation and published stale numbers over newer ones. The effect's keys also never changed when a play was recorded. | `ListeningStats` now exposes a `revision: StateFlow<Long>` that moves on a counted play, a completed write and a restore. `rememberProfileStats` reads it through `produceState`, whose body is cancelled when the keys change — so a stale read can no longer publish over a newer state. |
| P2 | Every edit made in "Edit profile" was invisible | The dialog wrote `fullNameOverride` / `phoneOverride` / `emailOverride` / `usernameOverride`; the hero reads `displayNameOverride` / `usernameOverride` / `statusLineOverride` / `bioOverride` / `avatarOverrideUri`. The two sets did not overlap, so an edit saved to one place and was read from another. Status and bio had no field at all. | The dialog now edits display name, handle, status, bio and avatar — the exact five the hero reads. The old override keys are kept in `AppSettings` so already-persisted data is not lost, but nothing writes them any more. Real validation was added: a blank display name, an over-long handle, an over-long status and an over-long bio each raise an inline error and keep Save disabled. |
| P3 | The profile was reachable only by digging through Settings | The top-bar account button opened Settings when signed out and the Google account picker when signed in — never the profile page. | The account photo now opens the profile page for a signed-out visitor and a signed-in one alike, clearing the overlays that outrank that route in `AnimatedContent`'s priority order. |
| P4 | "Listen as" opened the wrong picker | It opened `AccountProfileSelector` (the Google accounts on the device), which cannot name a YouTube channel, so the row's subtitle could never be changed from there. `AccountChannelDialog` — the real channel picker — was dead code. | "Listen as" now opens `AccountChannelDialog`, fed by `viewModel.channels` / `channelsLoading` / `selectedChannelKey`, with `viewModel.selectChannel` on a choice and the in-app browser for "choose in YouTube". A separate "Switch account" row was added for the Google account picker, so neither route loses its entry point. |
| P5 | A signed-out visitor saw an empty page | Every stat block sat inside `if (signedIn)`, even though the listening history is device-local (`ListeningStats` writes to `context.filesDir`) and belongs to whoever is listening. | Top songs, insights and badges are shown either way. Only the account group and the sign-out row remain behind the sign-in. |
| P6 | The dialog's own footer contradicted its behaviour | The footer described a "Save button is greyed out" rule that no validation implemented. | Real validation, real disabled Save, real inline errors — and the footer now describes what the code does. |
| P7 | `ProfileStats`' footer described a null record that did not exist | The record is non-null and carries an `inFlight` flag. | The footer and the CodeHeader now describe the flag, and the duplicated header block was removed. |

### Contract summary

The hero and the edit dialog now share one contract, and it is worth stating
plainly because the old one was the bug:

| Setting key | Read by the hero | Written by the dialog |
|--------------|-------------------|------------------------|
| `displayNameOverride` | yes | yes |
| `usernameOverride` | yes (as the `@handle`) | yes |
| `statusLineOverride` | yes | yes |
| `bioOverride` | yes | yes |
| `avatarOverrideUri` | yes | yes |
| `fullNameOverride` | no | no — kept for already-persisted data |
| `phoneOverride` | no | no — kept for already-persisted data |
| `emailOverride` | no | no — kept for already-persisted data |

The handle is stored **bare** (no leading `@`): `AppSettings.setUsernameOverride`
trims and strips the `@`, and `ProfileHero` re-adds it when it draws the
handle. Saving `@himanshu` in the dialog therefore renders as `@himanshu`,
never `@@himanshu`.

### Tests

`app/src/test/java/com/ihimanshunayak/freemusic/ProfileStatsTest.kt` covers the
pure reducer `ProfileStats.withSummary`: hour truncation, the play count, the
five-row top-songs cap, the in-flight flag clearing, and the playlist and
source counts surviving the fold. It needs no device and no Compose runtime.
