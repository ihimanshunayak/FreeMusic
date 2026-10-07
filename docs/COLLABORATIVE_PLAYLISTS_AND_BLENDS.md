# Collaborative Playlists & Blends — Architecture Audit

Audit performed against `96146b5` (v1.11), branch `windows-desktop`.

This document is the required first step of the Collaborative Playlist / Blend
work: an audit of what the repository actually contains, before any code. Three
of its findings **contradict the assumptions in the original brief**, and those
are called out first because they change the design.

---

## 0. Corrections to the brief

The brief was written from an outside description of the project. Three of its
premises are not true of this repository as it stands.

### 0.1 There is no Room, and never was

The brief states:

> If an existing Room/database architecture exists: extend it.
> … Room is already a dependency (the app has `SearchHistoryEntity` and a
> download store)

Room is **not** a dependency. There is no `androidx.room` anywhere in
`app/build.gradle.kts`, no `@Entity`, no `@Dao`, no `RoomDatabase`, and no
KSP annotation processor. `SearchHistoryEntity` exists and is misleadingly
named: it is a `@kotlinx.serialization.Serializable` data class in
`data/model/SearchHistoryEntity.kt`, persisted as JSON — not a Room entity.

The app's entire persistence layer is `SharedPreferences` plus
`kotlinx.serialization` JSON documents written to app storage. `PlaylistStore`
(v1.11) follows that same pattern.

**Consequence:** the suggested entity list (`PlaylistEntity`,
`PlaylistMemberEntity`, `BlendEntity`, …) and the instruction to "use foreign
keys and indexes appropriately" describe a schema that has nowhere to live. Room
could be introduced, but that would be a new persistence technology in a project
that has deliberately used one, and the brief itself says not to introduce
another database technology unnecessarily. **The entity list is therefore not
implemented as written.** Storage follows the existing JSON-document pattern on
the device, and a store abstraction on the server.

### 0.2 The server has no user accounts

The brief assumes `userId` identifies an authenticated user. It does not.

- `party.Member.UserId` is **client-supplied** and unverified.
- `protocol.JoinRequest.Validate()` only checks that `userId` is a non-empty
  string of at most 128 characters. Any client can claim any `userId`.
- The bearer token the server issues is **party-scoped** (`Member.Token`), not a
  user identity. `Party.Authenticate(token)` resolves a token to a *member of
  that party*, and that is all it can do.
- The Android client derives `userId` as
  `sha256("${account.accountId}:${profileId}").take(32)` — a stable pseudonym,
  and a good one, but still self-asserted.

**Consequence:** "prevent unauthorized playlist modification" cannot be built on
`userId` alone, because any client can present any `userId`. Authorisation has to
rest on a **server-issued secret**, which means a playlist needs its own
credential per member — an invite or membership token minted by the server, held
by that member's device, and never derived from `userId`. This is a larger
security surface than the brief implies and is designed for explicitly below
(§6).

### 0.3 There is nowhere durable to persist anything

The brief assumes a database can simply be added. Two deployment facts prevent
that:

- `backend/render.yaml` targets Render's **Free** plan. Render's free tier has
  **no persistent disk** — the filesystem is ephemeral and is discarded on every
  deploy, restart, and idle spin-down.
- `render.yaml` already states `numInstances: 1` with the comment *"Parties live
  in memory, so keep to 1 instance"*, and `config.go` records the reason: *"Render
  Free has 0.1 CPU."*

So: a persistent playlist feature cannot be delivered by "adding a database" to
the existing free deployment. Either the deployment gains a paid instance with a
mounted disk (or an external managed database), or persistence is a
configurable capability rather than an assumption.

**Decision taken:** the store is an **interface** with two implementations — an
in-memory store that works everywhere with no disk, and a durable JSON-file
store that activates when a volume is actually mounted. The service layer never
knows which is in use. This satisfies the brief's own instruction
(*"Design a repository abstraction … Then provide MemoryPlaylistRepository and a
production persistent implementation"*) without pretending the free tier has a
disk it does not have. Swapping in SQLite later is a new implementation of one
interface.

---

## 1. Existing playlist model

| Location | What it is |
|---|---|
| `data/playlist/PlaylistStore.kt` (v1.11) | Device-local personal playlists. JSON document, `Playlist` + `StoredSong`. No members, no server. |
| `YtMusicRepository.kt` — `userPlaylists()`, `createPlaylist()`, `addToPlaylist()` | YouTube Music playlists, against the signed-in Google account. |
| `Downloads` — `local:playlist:` prefix | A read-only snapshot of a downloaded playlist. |

Three separate things, deliberately not merged. A collaborative playlist is a
fourth: it has **members**, which none of the above can express. The v1.11 work
introduced `BROWSE_PREFIX = "local:mine:"` to keep device playlists distinct from
downloaded ones; a collaborative playlist needs the same treatment, not a reuse
of an existing prefix.

## 2. Existing database

None. See §0.1 and §0.3. `backend/` contains no `database/sql`, no SQLite, no
Bolt, no file writes at all — verified by scanning every `.go` file for
`database/sql`, `os.WriteFile` and friends.

## 3. Existing user model

There is **no server-side user account**. Identity is the pair
`(userId, deviceId)` supplied by the client at join time (§0.2). The server
stores it on the member and nothing more. There is no registration, no login, no
user table, no profile endpoint.

A `userId` is stable across devices only because the client derives it from the
Google account and profile id — the server has no way to check that.

## 4. Existing authentication

Two mechanisms, both narrow:

| Mechanism | Scope | Where |
|---|---|---|
| Bearer token | A member of **one** party | `parseBearerToken` (`main.go:191`), `Party.Authenticate` |
| Host-only control | Playback/queue actions within a party | `config.ControlActions`, `Party.ShouldRelaxHostOnly` |

Plus per-IP create rate limiting (`createLimiter`), per-member control budget
(`SpendControlBudget`), per-frame budget (`SpendFrameBudget`), and
`RequestMaxBytes` / `WebSocketMaxBytes` (16 KB each).

There is **no** cross-party identity, so there is nothing today that could answer
"is this the same person who owns that playlist?". This is the central gap for
collaborative playlists and is addressed in §6.

## 5. Existing queue model

`party.PlaybackState` holds the live queue: `Queue []*Track`, `QueueIndex`,
`QueueSeq`, and `AddUpcoming` / `RemoveUpcoming` / `ClearUpcoming` /
`MoveUpcoming`. Bounded by `MaxUpcomingQueue` (25) and `MaxQueueLength`.

This is a **live playback queue**, capped at 25 upcoming tracks, and it is
destroyed with the party. A collaborative playlist is unbounded, durable, and
outlives every session. They are different objects; the playlist is the source
of truth and the queue is a projection of it during a Listen Together session
(Phase 10).

## 6. Existing Listen Together backend

| File | Lines | Contents |
|---|---|---|
| `main.go` | 1191 | Routes, handlers, rate limiting, CORS, static assets |
| `party/party.go` | 862 | `Party`, `Member`, `PlaybackState`, `PartyStore`, budgets |
| `hub/hub.go` | 167 | WebSocket fan-out, per-connection write serialisation |
| `protocol/protocol.go` | 110 | Frame and action name constants, `JoinRequest` validation |
| `clock/clock.go` | — | Server clock |
| `codes/codes.go` | — | 6-char party codes over a confusion-free alphabet |

Routes today: `GET /{$}`, `GET /healthz`, `GET /api/time`, `POST /api/parties`,
`POST /api/parties/{code}/join`, `GET /api/parties/{code}`,
`GET /api/parties/{code}/preview`, `POST /api/parties/{code}/leave`,
`GET /invite/{code}`, `GET /ws/parties/{code}`.

## 7. Existing WebSocket protocol

Server → client: `welcome`, `state`, `queue`, `members`, `activity`, `pong`,
`error`, `bye`.
Client → server: `ping`, `control`, `sync`, `syncQueue`, `report`.

`control` carries the 14 actions listed in `protocol.ControlActions`. The
existing frames are all **party-scoped** — every one of them is meaningless
without a live party, and a `welcome` cannot be sent for something that is not a
party. Playlist events are therefore a **separate frame family** on the same
connection and the same `hub`, not new actions inside `control`.

## 8. Existing playback model

Media3 `ExoPlayer` inside `PlaybackService` (thousands of lines), driven by
`QueueCoordinator`; `PartySync.kt` (1165) binds party state to the player;
`playback/smart/` holds automix (Phase 11's target).

## 9. Existing history / listening tracking

`data/stats/ListeningStats.kt` (34 KB) — per-month buckets (`StoredBucket`) of
`TrackEntry` (plays, ms) and `NameEntry` (artists, albums), plus `hours` and
`days`. `ListeningRecorder.kt` writes them. **There are no skip counts, no
completion rates, and no per-session history** — only aggregated play counts and
durations.

`data/stats/ArtistFacts.kt` adds artist metadata. `data/replay/` renders the
summary.

**Consequence for Blend:** of the signals the brief lists, these exist:
frequently played artists, frequently played tracks, replay count, recent
listening, listening frequency. These do **not** exist and cannot be read
without new instrumentation: skip rate, completion rate, liked/favourited tracks
(likes live on YouTube Music, not locally).

## 10. Existing native audio analysis

`playback/smart/TrackFeatures.kt` calls `System.loadLibrary("freemusic_analysis")`
(a native C++ library) and returns a rich `Features` record: `bpm`,
`beatInterval`, `firstBeat`, `beatConfidence`, `key`, `keyConfidence`,
`vocalProbability`, `mixInTime`, `mixOutTime`, `energyCurve`, `lowEnergyCurve`,
`downbeats`, `phraseBoundaries`, and mix in/out candidates. `AnalysisStore.kt`
caches results; `TrackAnalyzer.kt`, `BeatTracker.kt`, `VocalTracker.kt`,
`MelSpectrogram.kt`, `TransitionPlanner.kt` consume them.

**This is a genuine asset for Blend.** Of the audio signals the brief lists, BPM,
tempo, energy, key and vocal/instrumental all exist and are already computed
on-device. It is also the strongest argument for computing taste profiles
**locally** and sending only the summary — the data is already here, and the
analysis is already the expensive part.

## 11. Existing navigation structure

Single `MainActivity` (~4800 lines) driving a Compose tree. Library tab is a
`LazyColumn` of keyed items (`shelf:mine`, `shelf:<name>`, `replay`) built by
`LibraryScreen.kt`. Detail pages are a `detailStack` of `DetailPage` values
routed by `browseId` prefix, with `MainViewModel.Companion.browseTypeOf`
deciding the type. Deep links are parsed by
`data/listentogether/JamInviteLink.kt`, which already owns the `freemusic://`
scheme and `freemusic://party/<CODE>` host.

**Consequence:** a collaborative playlist should be a **new `browseId` prefix**
(`collab:`) with a new `BrowseType` arm, exactly as `local:mine:` was added in
v1.11 — not a new Activity or a new navigation graph. Invites should extend
`JamInviteLink`'s existing parsing rather than adding a second deep-link parser,
so the scheme has one owner.

## 12. Existing settings architecture

`data/settings/AppSettings.kt` — a `SharedPreferences`-backed object with typed
accessors, surfaced in `SettingsSheet.kt`, exported and imported by
`data/stats/Backup.kt`. The Blend opt-in toggle belongs here, and the backup
document is where a device's playlist membership list belongs.

---

## 13. Files to be modified

| File | Change |
|---|---|
| `backend/protocol/protocol.go` | playlist frame and error-code constants |
| `backend/config/config.go` | playlist caps, invite TTL, blend caps |
| `backend/main.go` | playlist routes; construct and inject the store |
| `backend/party/party.go` | *(unchanged)* — playlists are a separate concern |
| `docs/COLLABORATIVE_PLAYLISTS_AND_BLENDS.md` | this document |
| `app/…/data/playlist/PlaylistStore.kt` | *(unchanged)* — personal playlists stay as they are |
| `app/…/data/listentogether/JamInviteLink.kt` | accept `freemusic://playlist/invite/<token>` |
| `app/…/ui/MainViewModel.kt` | `collab:` browse prefix and type routing |
| `app/…/MainActivity.kt` | dispatch, chrome, sheets |
| `app/…/ui/screens/LibraryScreen.kt` | Collaborative and Blends shelves |
| `app/…/data/settings/AppSettings.kt` | Blend opt-in |

## 14. Files to be added

| File | Purpose |
|---|---|
| `backend/playlist/model.go` | domain types and wire forms |
| `backend/playlist/store.go` | `Store` interface, `MemoryStore` |
| `backend/playlist/filestore.go` | durable JSON store |
| `backend/playlist/service.go` | service, options, credential resolution |
| `backend/playlist/operations.go` | create, read, metadata, track mutations |
| `backend/playlist/membership.go` | invites, join/leave, deltas |
| `backend/playlist/crypto.go` | tokens, ids, constant-time comparison |
| `backend/playlist/clock.go` | server-time indirection |
| `backend/playlist_routes.go` | every `/api/playlists` HTTP route |
| `backend/playlist/*_test.go`, `backend/playlist_routes_test.go` | tests |
| `backend/playlist/blend.go` | taste profiles, scoring, generation *(phase 8)* |
| `app/…/data/collab/CollabModels.kt` | wire models |
| `app/…/data/collab/CollabPlaylists.kt` | client, mirroring `ListenTogether`'s shape |
| `app/…/data/collab/TasteProfile.kt` | on-device profile from `ListeningStats` + `TrackFeatures` |
| `app/…/ui/screens/CollabPlaylistScreen.kt` | detail UI |
| `app/…/ui/screens/BlendScreen.kt` | blend UI |

---

## 15. Security design (§0.2's consequence)

`userId` is self-asserted, so it cannot be an authorisation principal. The
design is therefore:

1. **Membership is a capability.** Joining a playlist mints a
   `membershipToken` — 32 bytes from `crypto/rand`, hex-encoded — returned once
   to that device and stored on it. Every mutating request presents it.
2. **Owner is a separate secret.** The `ownerToken` is issued at creation and
   is never derivable from the membership token, so a compromised membership
   cannot escalate to ownership.
3. **Invite tokens are opaque and single-purpose.** 32 random bytes, stored
   only as a SHA-256 hash, with an expiry (default 7 days, matching the brief),
   revocable, and never containing a playlist or user id.
4. **Removal is immediate.** Authorisation is resolved from the store on every
   request, so a removed member loses the ability to mutate at once — there is
   no cached session to expire.
5. **Constant-time comparison** for every token check (`crypto/subtle`), the
   same primitive `party.go` already uses.
6. **Revisions are server-assigned.** Clients send the revision they believe
   they are at; the server rejects stale writes and never consults a client
   clock.

## 16. Phasing

The brief's twelve phases, with the ones that are constrained by §0 marked:

| Phase | Status |
|---|---|
| 1 — data model + persistence | **done** — `backend/playlist/` |
| 2 — API + auth + authz | **done** — `backend/playlist_routes.go` |
| 3 — invitation system | **done** (server) — deep link in phase 3b |
| 3b — invite deep link on Android | next |
| 4 — UI | after the server contract is fixed |
| 5 — real-time sync | extends the existing `hub` |
| 6 — offline / reconnection | revision handshake as the brief describes |
| 7 — taste profiles | **on-device**, leveraging §10 |
| 8 — generation engine | server-side, over summaries only |
| 9 — Blend UI + score | labelled "Music compatibility", never as fact |
| 10 — Listen Together bridge | playlist → party queue via the existing `setQueue` |
| 11 — Automix | reuse `playback/smart/`, no second engine |
| 12 — testing + hardening | throughout, not last |

### 16.1 What phases 1-3 changed against this document

Recorded because the plan and the code disagree in three places, and the code
is right:

1. **`List` takes variadic credentials, not one.** §15 establishes that a
   credential is per-playlist, so a user with three playlists holds three
   unrelated tokens and there is no single credential that answers "my
   playlists". The endpoint accepts the bearer token plus a repeatable `token`
   query parameter and returns the union. The alternative — one request per
   token, merged on the device — would have put the merge, and its failure
   modes, in every client.
2. **There is no public playlist read.** §14 of the brief has the invitation
   screen show the playlist before anyone joins, which reads as an
   unauthenticated `GET`. It is not: the preview lives on the invitation
   (`GET /api/playlist-invites/{token}`) and returns only what the sharer chose
   to share. A public `GET /api/playlists/{id}` would return every member and
   every track to anyone who guessed an id, which would make the capability
   model decorative.
3. **Invitation redemption is not under `/api/playlists/`.** It is
   `/api/playlist-invites/{token}`, because the recipient does not know the
   playlist id and must not need to. Go's `ServeMux` also refuses to route
   `/api/playlists/invites/{token}` against `/api/playlists/{id}/deltas` — the
   two patterns are genuinely ambiguous — so the separate prefix is not only
   clearer but load-bearing.

### 16.2 Two defects found by the phase 1 tests

Both were silent — neither would have surfaced until the UI was wired, and both
would have looked like a client bug.

1. **Reorder was a no-op.** `MoveTrack` spliced the track slice into the wanted
   order and then called `SortTracks()`, which sorts by the `Position` field
   that still held the *old* order, undoing the move. Fixed by splitting the two
   concerns: `SortTracks()` (sort by `Position`, then renumber) is for load,
   where the file is the authority on `Position`, and `Renumber()` (rewrite
   `Position` from the current slice order) is for in-memory mutations, where the
   slice is already correct.
2. **`UpdatedAtMs` could tie.** Wall-clock milliseconds are too coarse to order
   two updates in the same millisecond, and "most recently updated" is the order
   the playlist list is drawn in, so ties shuffled the list between reads. The
   service now issues strictly increasing stamps (`Service.stamp`), advanced past
   the wall clock only while mutations outpace one per millisecond.

### 16.3 Deploying phases 1-3

The service is wired and reachable, but two operational facts decide whether it
is useful:

- **`PLAYLIST_STORE_PATH` is unset by default**, so playlists live in memory and
  do not survive a restart. That is the honest default on Render Free, which has
  no persistent disk; set it only on a plan that has one. `render.yaml` carries
  the other eight bounds and deliberately omits this one, with a comment saying
  why.
- **The service is still single-instance-only**, for the same reason parties are:
  state is in the process, so a second instance would serve a different set of
  playlists. `render.yaml` already pins `numInstances: 1`.

