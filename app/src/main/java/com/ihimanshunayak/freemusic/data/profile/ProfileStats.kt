/*
 * Copyright (c) A|iens. All rights reserved.
 *
 * Name:    ProfileStats
 * Version: 2.0.0
 * Author:  A'nil Cyborg
 * Author URI: https://github.com/ihimanshunayak
 *
 * Description:
 *   The numbers the profile hero shows on the Account & Integrations page,
 *   with the wiring that derives them from the rest of the app.
 *
 *   The numbers are:
 *     - Hours listened (lifetime)     — Replay ALL_TIME totalMs
 *     - Tracks played (lifetime)      — Replay ALL_TIME totalPlays
 *     - Playlists                     — PlaylistStore.playlists.size
 *     - Sources                       — SourceRegistry.configs.value.size
 *
 *   Hours and tracks come from [ListeningStats.summary], which is a `suspend`
 *   function on Dispatchers.IO. The hero therefore drives it through a
 *   lifecycle-bound read that refreshes whenever the listening-stats
 *   revision moves — so the numbers are not "launch-time only".
 *
 *   Playlists and sources are StateFlows; their latest value is good enough
 *   for a display number, and a subscription is not needed.
 *
 * Responsibilities:
 *   - Expose the four numbers in a single immutable record, [ProfileStats].
 *   - Keep the values consistent within a recomposition: one read of every
 *     StateFlow, then the IO call, then the publish.
 *   - Fold a finished summary into the displayed record through one pure
 *     reducer, [ProfileStats.withSummary], so the arithmetic that turns a
 *     summary into headline numbers is testable without a Compose runtime.
 *
 * Dependencies:
 *   - ListeningStats (data/stats)
 *   - PlaylistStore   (data/playlist)
 *   - SourceRegistry  (data/sources)
 *
 * Integration Notes:
 *   Call [rememberProfileStats] from the profile hero. The returned record
 *   carries an `inFlight` flag rather than being null: the hero draws a
 *   skeleton shape while the first IO call is in flight, so the page does not
 *   flash "0 h / 0 plays" on every open.
 */
package com.ihimanshunayak.freemusic.data.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ihimanshunayak.freemusic.data.playlist.PlaylistStore
import com.ihimanshunayak.freemusic.data.sources.SourceRegistry
import com.ihimanshunayak.freemusic.data.stats.ListeningStats
import com.ihimanshunayak.freemusic.data.stats.ReplayPeriod
import com.ihimanshunayak.freemusic.data.stats.ReplaySummary

/**
 * One of the user's most-played tracks, as the profile page shows it.
 *
 * Kept as its own small record rather than handing the whole
 * `ReplaySummary` to the UI: the profile wants a title, an artist, a cover
 * and a play count, and nothing else. Carrying the summary would put the
 * whole chart — every artist, every album, every genre — into a composition
 * that only ever draws five rows.
 */
data class ProfileTopSong(
    /** The video id, so a tap can start a station off the track itself. */
    val videoId: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    /** How many times this track has been played. */
    val plays: Int,
)

/**
 * The numbers and facts the profile hero and the profile page show.
 *
 * The four headline numbers are:
 *     - Hours listened (lifetime)     — Replay ALL_TIME totalMs
 *     - Tracks played (lifetime)      — Replay ALL_TIME totalPlays
 *     - Playlists                     — PlaylistStore.playlists.size
 *     - Sources                       — SourceRegistry.configs.value.size
 *
 * And alongside them:
 *     - [streak]  — consecutive days with listening
 *     - [topSongs] — the most-played tracks, ranked
 *
 * Hours and tracks come from [ListeningStats.summary], which is a `suspend`
 * function on Dispatchers.IO. The hero therefore drives it through a
 *   lifecycle-bound read that refreshes whenever the listening-stats
 *   revision moves — so the numbers are not "launch-time only".
 *
 * Playlists and sources are StateFlows; their latest value is good enough
 * for a display number, and a subscription is not needed.
 *
 * Responsibilities:
 *   - Expose the numbers and the top tracks in a single immutable record,
 *     [ProfileStats].
 *   - Keep the values consistent within a recomposition: one read of every
 *     StateFlow, then the IO call, then the publish.
 *   - Fold a finished summary into the displayed record through one pure
 *     reducer, [ProfileStats.withSummary], so the arithmetic that turns a
 *     summary into headline numbers is testable without a Compose runtime.
 *
 * Dependencies:
 *   - ListeningStats (data/stats)
 *   - PlaylistStore   (data/playlist)
 *   - SourceRegistry  (data/sources)
 *
 * Integration Notes:
 *   Call [rememberProfileStats] from the profile hero. The returned record
 *   carries an `inFlight` flag rather than being null: the hero draws a
 *   skeleton shape while the first IO call is in flight, so the page does not
 *   flash "0 h / 0 plays" on every open.
 */
data class ProfileStats(
    /** Total listening, in whole hours. Zero while [inFlight] is true. */
    val hoursListened: Long = 0L,
    /** Total number of completed plays. Zero while [inFlight] is true. */
    val tracksPlayed: Long = 0L,
    /** Number of stored playlists (all kinds, including liked songs and downloads). */
    val playlists: Int = 0,
    /** Number of music sources the user has configured (built-in + addons). */
    val sources: Int = 0,
    /**
     * Consecutive days with listening, counting today. Zero when there is
     * nothing on the calendar, and while [inFlight] is true.
     */
    val streak: Int = 0,
    /**
     * The most-played tracks, ranked. Empty while [inFlight] is true, and
     * empty for good when the user has never played anything.
     */
    val topSongs: List<ProfileTopSong> = emptyList(),
    /**
     * The hour of the day with the most listening, 0..23, or null when
     * nothing has been played.
     */
    val peakHour: Int? = null,
    /**
     * The day with the most listening, as `YYYY-MM-DD`, or null.
     */
    val busiestDay: String? = null,
    /** How many distinct days carry some listening. */
    val activeDays: Int = 0,
    /** How many distinct songs have been played. */
    val uniqueSongs: Int = 0,
    /** How many distinct artists have been played. */
    val uniqueArtists: Int = 0,
    /**
     * True only on the first composition, before the listening summary has
     * come back. Used to draw a skeleton row in the hero rather than zeros.
     */
    val inFlight: Boolean = false,
) {
    /**
     * Fold a finished [summary] into this record.
     *
     * Pure on purpose: the arithmetic that turns a Replay summary into the
     * profile's headline numbers is the part that can be wrong, and it is
     * the part a unit test can reach without a Compose runtime or a real
     * device. The record's own counts (playlists, sources) are left alone —
     * they come from other StateFlows and are not part of the summary.
     *
     * The previous values are carried over rather than reset, so a refresh
     * that lands while the page is open does not blank the numbers for a
     * frame.
     */
    fun withSummary(summary: ReplaySummary): ProfileStats = copy(
        hoursListened = summary.totalMs / 3_600_000L,
        tracksPlayed = summary.totalPlays.toLong(),
        streak = summary.streak,
        topSongs = summary.songs.take(TOP_SONGS_COUNT).map { entry ->
            ProfileTopSong(
                videoId = entry.song.videoId,
                title = entry.song.title,
                artist = entry.song.artist,
                artworkUrl = entry.song.thumbnailUrl,
                plays = entry.plays,
            )
        },
        peakHour = summary.peakHour,
        busiestDay = summary.busiestDay,
        activeDays = summary.activeDays.size,
        uniqueSongs = summary.distinctSongs,
        uniqueArtists = summary.distinctArtists,
        inFlight = false,
    )

    /**
     * The empty reading: what the page shows when there is nothing to show.
     *
     * The listening numbers belong to the device rather than to whichever
     * account is signed in, so a sign-out does not clear them — but the
     * *hero* does change, and this exists so that any path which genuinely
     * has no reading to show has one obvious answer rather than a set of
     * hand-cleared fields that can drift apart.
     */
    fun cleared(): ProfileStats = copy(
        hoursListened = 0L,
        tracksPlayed = 0L,
        streak = 0,
        topSongs = emptyList(),
        peakHour = null,
        busiestDay = null,
        activeDays = 0,
        uniqueSongs = 0,
        uniqueArtists = 0,
        inFlight = false,
    )
}

/**
 * Build the numbers and the top tracks, refreshing the listening half
 * whenever:
 *   - the source counts change,
 *   - the listening-stats revision changes (a play was recorded),
 *   - the user signs in or out (a recomposition in the parent will mount
 *   this again with a new key, which the `produceState` keys off).
 *
 * The value is a [ProfileStats] published into composition once the IO call
 * finishes; the inFlight flag is set during the first call and the
 * `hoursListened` / `tracksPlayed` numbers are zero during that window.
 *
 * ## Why this is not a `LaunchedEffect` with an inner `launch`
 *
 * The first cut wrapped the IO call in `scope.launch { … }` *inside* a
 * `LaunchedEffect`. That reads as if it were tied to the effect's lifetime,
 * but it is not: `rememberCoroutineScope()` is tied to the *composition*, so
 * the inner launch survives the effect being cancelled and can publish its
 * result after the keys have moved on. The visible symptom was a profile
 * page whose numbers stayed at whatever the first read found, because
 * nothing the effect keyed on ever changed when a play was recorded — and a
 * sign-out that raced an in-flight read could be overwritten by the stale
 * numbers arriving afterwards.
 *
 * [produceState] runs its body in a scope that is cancelled the moment the
 * keys change, so a read that belongs to an older state can no longer
 * publish over a newer one. The keys are the listening-stats revision, the
 * source count and the sign-in state — which is everything that can change
 * the answer.
 */
@Composable
fun rememberProfileStats(signedIn: Boolean): ProfileStats {
    val sources by SourceRegistry.configs.collectAsStateWithLifecycle()
    val playlists by PlaylistStore.playlists.collectAsStateWithLifecycle()
    val revision by ListeningStats.revision.collectAsStateWithLifecycle()

    val stats = produceState(
        initialValue = ProfileStats(
            hoursListened = 0L,
            tracksPlayed = 0L,
            playlists = playlists.size,
            sources = sources.size,
            inFlight = true,
        ),
        // The playlist and source counts are read straight into the record
        // below rather than through the effect, because they are already
        // StateFlows and a display number does not need a subscription of
        // its own.
        key1 = signedIn,
        key2 = sources.size,
        key3 = revision,
    ) {
        // The listening numbers belong to this device, not to whichever
        // account is signed in: they are recorded into the app's own files
        // and are the same whoever is listening. A signed-out visitor still
        // gets their own history.
        val summary = runCatching {
            ListeningStats.summary(ReplayPeriod.ALL_TIME)
        }.getOrNull()
        if (summary != null) {
            value = value.withSummary(summary)
        } else {
            // The read failed — a folder that could not be opened, a file
            // that could not be parsed. The page shows what it had rather
            // than blanking itself, and the skeleton goes away so the tiles
            // are readable.
            value = value.copy(inFlight = false)
        }
    }.value

    return stats.copy(
        playlists = playlists.size,
        sources = sources.size,
    )
}

/** How many tracks the profile's "top songs" list carries. */
private const val TOP_SONGS_COUNT = 5

// ──────────────────────────────────────────────────────────────────────────────
// CodeFooter
// ──────────────────────────────────────────────────────────────────────────────
//
// Usage:
//   val stats = rememberProfileStats(signedIn = signedIn)
//   ProfileHero(stats = stats, ...)
//
// Troubleshooting:
//   - Numbers stay at zero after a long listen: the summary is read from
//     the listening-stats folder, which is initialised when the app starts
//     (see FreeMusicApplication.onCreate) and not by playback. If the
//     numbers are still zero, the folder has nothing in it — open the
//     Replay page, which reads the same summary, and check there too.
//   - "Sources" reads 0 before any source has been added: the built-ins
//     (YouTube, JioSaavn) only count once they have been seen at least
//     once by SourceRegistry. This is the same condition the Sources
//     page reads in, so there is no special-case here.
//   - The numbers do not move while the page is open: the page refreshes
//     on [ListeningStats.revision], which moves on a counted play and on a
//     completed write. A listening session that has not yet counted a play
//     has, by the app's own definition, not been listened to yet.
//
// Versioning notes:
//   1.0.0 — initial cut. Four numbers, one in-flight flag.
//   1.1.0 — added [ProfileStats.streak] and [ProfileStats.topSongs], both
//     derived from the same `ReplayPeriod.ALL_TIME` summary the four numbers
//     already come from. No extra IO: one read still answers everything.
//   2.0.0 — the read is now lifecycle-bound (a `produceState` rather than
//     an inner `launch` inside a `LaunchedEffect`), it refreshes on
//     [ListeningStats.revision] rather than only on a sign-in, it no
//     longer hides the device-local numbers behind a sign-in, and the
//     summary-to-numbers arithmetic lives in the pure
//     [ProfileStats.withSummary] so it can be tested directly.
//
// Extension hooks:
//   - For another lifetime tile (distinct songs, distinct artists), the same
//     summary already carries `distinctSongs` / `distinctArtists` /
//     `distinctAlbums` — add a field here and read it off the same call.
//   - For a period-scoped tile (this month, this year), the summary's
//     `period` is the only thing that has to change; the merge cost is one
//     more `ListeningStats.summary` call, so it is worth a second read only
//     if the tile really is period-scoped.
//
// Future compatibility notes:
//   - [ListeningStats.summary] is `suspend` and lives on Dispatchers.IO;
//     this composable must not call it directly, only through the
//     `produceState` body. Anything that calls it from composition will
//     run on the main thread by mistake.
//   - [SourceRegistry.configs] and [PlaylistStore.playlists] are
//     `MutableStateFlow`s, so any new source-count tile just needs to read
//     the same flow — there is no separate facade to maintain.
//   - [ProfileStats] is a plain immutable record, so adding a field is
//     backward compatible for every caller.
