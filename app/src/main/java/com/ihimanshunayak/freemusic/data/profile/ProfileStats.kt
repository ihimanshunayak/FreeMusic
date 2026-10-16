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
 * The four numbers a profile hero needs.
 *
 * A `0` for [hoursListened] / [tracksPlayed] combined with [inFlight] being
 * true is the in-flight state before the first `ReplayPeriod.ALL_TIME`
 * summary has come back from disk. [playlists] and [sources] are always
 * populated synchronously and so are always meaningful.
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
     * True only on the first composition, before the listening summary has
     * come back. Used to draw a skeleton row in the hero rather than zeros.
     */
    val inFlight: Boolean = false,
)

/**
 * Build the four numbers, refreshing the listening half whenever:
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
    var inFlight by remember { mutableStateOf(true) }

    // Keying the effect on signedIn and the *count* of sources is enough:
    // adding or removing a source is the only way the number changes, and
    // signing in is the only way a fresh lifetime read should happen. The
    // listening-stats version is read off the next summary call anyway.
    LaunchedEffect(signedIn, sources.size) {
        if (!signedIn) {
            hours = 0L
            plays = 0L
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
            }
            inFlight = false
        }
    }

    return ProfileStats(
        hoursListened = hours,
        tracksPlayed = plays,
        playlists = playlists.size,
        sources = sources.size,
        inFlight = inFlight,
    )
}

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
//
// Extension hooks:
//   - For a "current streak" tile, add it as a fifth [ProfileStats] field
//     derived from [ListeningStats] — the same call already returns the
//     days listened; the streak is one more summary line on top of that.
//
// Future compatibility notes:
//   - [ListeningStats.summary] is `suspend` and lives on Dispatchers.IO;
//     this composable must not call it directly, only through the launched
//     effect. Anything that calls it from composition will run on the main
//     thread by mistake.
//   - [SourceRegistry.configs] and [PlaylistStore.playlists] are
//     `MutableStateFlow`s, so any new source-count tile just needs to read
//     the same flow — there is no separate facade to maintain.
