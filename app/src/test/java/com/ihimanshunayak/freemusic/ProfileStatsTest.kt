/*
 * Copyright (c) A|iens. All rights reserved.
 *
 * Name:    ProfileStatsTest
 * Version: 1.0.0
 * Author:  A'nil Cyborg
 * Author URI: https://github.com/ihimanshunayak
 *
 * Description:
 *   Guards the arithmetic that turns a Replay summary into the profile
 *   page's headline numbers.
 *
 *   The reducer [ProfileStats.withSummary] is the one piece of the
 *   profile that can be wrong without anything looking wrong on screen
 *   for long: a truncated hour count, a top-songs list that quietly
 *   carries six rows instead of five, an in-flight flag that never
 *   clears. All of it is reachable from a plain JUnit test, so it is
 *   tested here rather than by eye on a device.
 *
 * Responsibilities:
 *   - Cover the hour truncation, the play count, the top-songs cap, the
 *     in-flight clearing and the preserved playlist/source counts.
 *   - Cover [ProfileStats.cleared] so the empty reading stays a single
 *     obvious answer rather than a set of hand-cleared fields.
 *
 * Dependencies:
 *   - ProfileStats (data/profile)
 *   - ReplaySummary, RankedSong, Song (data/stats, data/model)
 *
 * Integration Notes:
 *   Run with `:app:testProdDebugUnitTest`.
 */
package com.ihimanshunayak.freemusic

import com.ihimanshunayak.freemusic.data.model.Song
import com.ihimanshunayak.freemusic.data.profile.ProfileStats
import com.ihimanshunayak.freemusic.data.stats.RankedSong
import com.ihimanshunayak.freemusic.data.stats.ReplayPeriod
import com.ihimanshunayak.freemusic.data.stats.ReplaySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the profile page's number arithmetic.
 *
 * The reducer is pure, so these tests need no device, no Compose
 * runtime and no listening history — only a summary shaped like the one
 * `ListeningStats.summary` returns.
 */
class ProfileStatsTest {

    private fun summary(
        totalMs: Long = 0L,
        totalPlays: Int = 0,
        songs: List<RankedSong> = emptyList(),
        activeDays: Set<String> = emptySet(),
    ) = ReplaySummary(
        period = ReplayPeriod.ALL_TIME,
        label = "All time",
        totalMs = totalMs,
        totalPlays = totalPlays,
        songs = songs,
        artists = emptyList(),
        albums = emptyList(),
        genres = emptyList(),
        hourOfDay = List(24) { 0L },
        busiestDay = null,
        busiestDayMs = 0L,
        distinctSongs = songs.size,
        distinctArtists = 0,
        distinctAlbums = 0,
        since = null,
        activeDays = activeDays,
    )

    private fun rankedSong(id: String, title: String, plays: Int) = RankedSong(
        song = Song(
            videoId = id,
            title = title,
            artist = "Artist $id",
            thumbnailUrl = null,
        ),
        ms = plays * 60_000L,
        plays = plays,
    )

    @Test
    fun `hours are whole hours and the remainder is dropped`() {
        // 1 h 59 min is one hour on a tile that says "hours listened";
        // rounding it up would claim listening that never happened.
        val stats = ProfileStats().withSummary(summary(totalMs = 3_600_000L + 59 * 60_000L))

        assertEquals(1L, stats.hoursListened)
    }

    @Test
    fun `less than an hour is zero hours`() {
        val stats = ProfileStats().withSummary(summary(totalMs = 59 * 60_000L))

        assertEquals(0L, stats.hoursListened)
    }

    @Test
    fun `tracks played comes straight from the summary`() {
        val stats = ProfileStats().withSummary(summary(totalPlays = 137))

        assertEquals(137L, stats.tracksPlayed)
    }

    @Test
    fun `the top songs list is capped at five`() {
        val songs = (1..8).map { rankedSong("v$it", "Track $it", it) }

        val stats = ProfileStats().withSummary(summary(songs = songs))

        assertEquals(5, stats.topSongs.size)
        assertEquals("v1", stats.topSongs.first().videoId)
        assertEquals("Track 1", stats.topSongs.first().title)
    }

    @Test
    fun `a top song keeps its own play count`() {
        val songs = listOf(rankedSong("abc", "Only Track", 12))

        val stats = ProfileStats().withSummary(summary(songs = songs))

        assertEquals(12, stats.topSongs.single().plays)
    }

    @Test
    fun `the in-flight flag clears once a summary arrives`() {
        val inFlight = ProfileStats(inFlight = true)

        val stats = inFlight.withSummary(summary(totalMs = 3_600_000L))

        assertFalse(stats.inFlight)
    }

    @Test
    fun `the playlist and source counts survive the reducer`() {
        // They come from other StateFlows and are not part of the summary,
        // so folding a summary in must not wipe them.
        val before = ProfileStats(playlists = 7, sources = 3)

        val stats = before.withSummary(summary(totalMs = 7_200_000L, totalPlays = 4))

        assertEquals(7, stats.playlists)
        assertEquals(3, stats.sources)
        assertEquals(2L, stats.hoursListened)
    }

    @Test
    fun `active days and distinct counts come from the summary`() {
        val songs = listOf(rankedSong("a", "A", 1), rankedSong("b", "B", 1))

        val stats = ProfileStats().withSummary(
            summary(songs = songs, activeDays = setOf("2026-01-01", "2026-01-02")),
        )

        assertEquals(2, stats.activeDays)
        assertEquals(2, stats.uniqueSongs)
    }

    @Test
    fun `clearing zeroes the listening numbers but keeps the counts`() {
        val before = ProfileStats(
            hoursListened = 12L,
            tracksPlayed = 90L,
            playlists = 4,
            sources = 2,
            streak = 5,
            topSongs = listOf(
                com.ihimanshunayak.freemusic.data.profile.ProfileTopSong(
                    videoId = "v",
                    title = "T",
                    artist = "A",
                    artworkUrl = null,
                    plays = 3,
                ),
            ),
            peakHour = 21,
            busiestDay = "2026-01-01",
            activeDays = 9,
            uniqueSongs = 11,
            uniqueArtists = 6,
        )

        val cleared = before.cleared()

        assertEquals(0L, cleared.hoursListened)
        assertEquals(0L, cleared.tracksPlayed)
        assertEquals(0, cleared.streak)
        assertTrue(cleared.topSongs.isEmpty())
        assertNull(cleared.peakHour)
        assertNull(cleared.busiestDay)
        assertEquals(0, cleared.activeDays)
        assertEquals(0, cleared.uniqueSongs)
        assertEquals(0, cleared.uniqueArtists)
        assertFalse(cleared.inFlight)
        // The playlist and source counts belong to other flows and must
        // survive — clearing is about the listening history, not the
        // whole page.
        assertEquals(4, cleared.playlists)
        assertEquals(2, cleared.sources)
    }
}
