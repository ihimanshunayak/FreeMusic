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
