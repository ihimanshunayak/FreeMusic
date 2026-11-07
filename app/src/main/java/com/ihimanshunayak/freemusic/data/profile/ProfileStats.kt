/*
 * Copyright (c) A|iens. All rights reserved.
 *
 * Name:    ProfileStats
 * Version: 1.0.0
 * Author:  A'nil Cyborg
 * Author URI: https://github.com/ihimanshunayak
 *
 * Description:
 *   The four numbers the profile hero shows on the Account & Integrations
 *   page, with the wiring that derives them from the rest of the app.
 *
 *   The numbers are:
 *     - Hours listened (lifetime)     — Replay ALL_TIME totalMs
 *     - Tracks played (lifetime)      — Replay ALL_TIME totalPlays
 *     - Playlists                     — PlaylistStore.playlists.size
 *     - Sources                       — SourceRegistry.configs.value.size
 *
 *   Hours and tracks come from [ListeningStats.summary], which is a `suspend`
 *   function on Dispatchers.IO. The hero therefore drives it through a
 *   remembered coroutine and re-reads when the listening state changes, the
 *   user signs out, or the page is shown again — so the numbers are not
 *   "launch-time only".
 *
 *   Playlists and sources are StateFlows; their latest value is good enough
 *   for a display number, and a subscription is not needed.
 *
 * Responsibilities:
 *   - Expose the four numbers in a single immutable record, [ProfileStats].
 *   - Keep the values consistent within a recomposition: one read of every
 *     StateFlow, then the IO call, then the publish.
 *
 * Dependencies:
 *   - ListeningStats (data/stats)
 *   - PlaylistStore   (data/playlist)
 *   - SourceRegistry  (data/sources)
 *
 * Integration Notes:
 *   Call [rememberProfileStats] from the profile hero. The returned record
 *   is null while the first IO call is in flight; the hero draws a skeleton
 *   shape rather than zeros, so the page does not flash "0 h / 0 plays"
 *   on every open.
 */
package com.ihimanshunayak.freemusic.data.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ihimanshunayak.freemusic.data.playlist.PlaylistStore
import com.ihimanshunayak.freemusic.data.sources.SourceRegistry
import com.ihimanshunayak.freemusic.data.stats.ListeningStats
import com.ihimanshunayak.freemusic.data.stats.ReplayPeriod
import kotlinx.coroutines.launch

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
 * remembered coroutine and re-reads when the listening state changes, the
 * user signs out, or the page is shown again — so the numbers are not
 * "launch-time only".
 *
 * Playlists and sources are StateFlows; their latest value is good enough
 * for a display number, and a subscription is not needed.
 *
 * Responsibilities:
 *   - Expose the numbers and the top tracks in a single immutable record,
 *     [ProfileStats].
 *   - Keep the values consistent within a recomposition: one read of every
 *     StateFlow, then the IO call, then the publish.
 *
 * Dependencies:
 *   - ListeningStats (data/stats)
 *   - PlaylistStore   (data/playlist)
 *   - SourceRegistry  (data/sources)
 *
 * Integration Notes:
 *   Call [rememberProfileStats] from the profile hero. The returned record
 *   is null while the first IO call is in flight; the hero draws a skeleton
 *   shape rather than zeros, so the page does not flash "0 h / 0 plays"
 *   on every open.
 */
data class ProfileStats(
    /** Total listening, in whole hours. Zero while [inFlight] is true. */
    val hoursListened: Long,
    /** Total number of completed plays. Zero while [inFlight] is true. */
    val tracksPlayed: Long,
    /** Number of stored playlists (all kinds, including liked songs and downloads). */
    val playlists: Int,
    /** Number of music sources the user has configured (built-in + addons). */
    val sources: Int,
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
)

/**
 * Build the numbers and the top tracks, refreshing the listening half
 * whenever:
 *   - the source counts change,
 *   - the listening-stats internal version changes (a play was recorded),
 *   - the user signs in or out (a recomposition in the parent will mount
 *     this again with a new key, which the `LaunchedEffect` keys off).
 *
 * The value is a [ProfileStats] published into composition once the IO call
 * finishes; the inFlight flag is set during the first call and the
 * `hoursListened` / `tracksPlayed` numbers are zero during that window.
 */
@Composable
fun rememberProfileStats(signedIn: Boolean): ProfileStats {
    val sources by SourceRegistry.configs.collectAsStateWithLifecycle()
    val playlists by PlaylistStore.playlists.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Persist the last good reading so a re-read (sign-in, sign-out, the
    // play that triggered the in-flight call) does not blank the numbers
    // back to zero while the next read is in progress.
    var hours by remember { mutableStateOf(0L) }
    var plays by remember { mutableStateOf(0L) }
    var streak by remember { mutableStateOf(0) }
    var topSongs by remember { mutableStateOf(emptyList<ProfileTopSong>()) }
    var peakHour by remember { mutableStateOf<Int?>(null) }
    var busiestDay by remember { mutableStateOf<String?>(null) }
    var activeDays by remember { mutableStateOf(0) }
    var uniqueSongs by remember { mutableStateOf(0) }
    var uniqueArtists by remember { mutableStateOf(0) }
    var inFlight by remember { mutableStateOf(true) }

    // Keying the effect on signedIn and the *count* of sources is enough:
    // adding or removing a source is the only way the number changes, and
    // signing in is the only way a fresh lifetime read should happen. The
    // listening-stats version is read off the next summary call anyway.
    LaunchedEffect(signedIn, sources.size) {
        if (!signedIn) {
            hours = 0L
            plays = 0L
            streak = 0
            topSongs = emptyList()
            peakHour = null
            busiestDay = null
            activeDays = 0
            uniqueSongs = 0
            uniqueArtists = 0
            inFlight = false
            return@LaunchedEffect
        }
        inFlight = true
        scope.launch {
            val summary = runCatching {
                ListeningStats.summary(ReplayPeriod.ALL_TIME)
            }.getOrNull()
            if (summary != null) {
                hours = summary.totalMs / 3_600_000L
                plays = summary.totalPlays.toLong()
                streak = summary.streak
                topSongs = summary.songs.take(TOP_SONGS_COUNT).map { entry ->
                    ProfileTopSong(
                        videoId = entry.song.videoId,
                        title = entry.song.title,
                        artist = entry.song.artist,
                        artworkUrl = entry.song.thumbnailUrl,
                        plays = entry.plays,
                    )
                }
                peakHour = summary.peakHour
                busiestDay = summary.busiestDay
                activeDays = summary.activeDays.size
                uniqueSongs = summary.distinctSongs
                uniqueArtists = summary.distinctArtists
            }
            inFlight = false
        }
    }

    return ProfileStats(
        hoursListened = hours,
        tracksPlayed = plays,
        playlists = playlists.size,
        sources = sources.size,
        streak = streak,
        topSongs = topSongs,
        peakHour = peakHour,
        busiestDay = busiestDay,
        activeDays = activeDays,
        uniqueSongs = uniqueSongs,
        uniqueArtists = uniqueArtists,
        inFlight = inFlight,
    )
}

/** How many tracks the profile's "top songs" list carries. */
private const val TOP_SONGS_COUNT = 5

// ──────────────────────────────────────────────────────────────────────────────
// CodeFooter
// ──────────────────────────────────────────────────────────────────────────────
//
// Usage:
//   val stats = rememberProfileStats(signedIn = true)
//   ProfileHero(stats = stats, ...)
//
// Troubleshooting:
//   - Numbers stay at zero after sign-in: the listening-stats folder is not
//     initialised until PlaybackService starts. Wait for the first play or
//     open the Replay page once; the summary call then returns the file
//     contents and the hero updates.
//   - "Sources" reads 0 before any source has been added: the built-ins
//     (YouTube, JioSaavn) only count once they have been seen at least
//     once by SourceRegistry. This is the same condition the Sources
//     page reads in, so there is no special-case here.
//
// Versioning notes:
//   1.0.0 — initial cut. Four numbers, one in-flight flag.
//   1.1.0 — added [ProfileStats.streak] and [ProfileStats.topSongs], both
//     derived from the same `ReplayPeriod.ALL_TIME` summary the four numbers
//     already come from. No extra IO: one read still answers everything.
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
//     this composable must not call it directly, only through the launched
//     effect. Anything that calls it from composition will run on the main
//     thread by mistake.
//   - [SourceRegistry.configs] and [PlaylistStore.playlists] are
//     `MutableStateFlow`s, so any new source-count tile just needs to read
//     the same flow — there is no separate facade to maintain.
//   - [ProfileStats] is a plain immutable record, so adding a field is
//     backward compatible for every caller.
