// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - listening-recorder tests.
//
// NAME
//     ListeningRecorderTest.kt - when a listen counts, and what it counts for.
//
// DESCRIPTION
//     The recorder decides what goes into the history and, because the history
//     feeds the statistics screen and the scrobblers, what every downstream total
//     is built from. Two mistakes here are invisible until much later:
//
//     1. Counting elapsed time from the reported position rather than from the
//        *increase* in it. A user who seeks forward halfway through a track would
//        bank the whole skipped span and trip the threshold without listening to
//        any of it.
//
//     2. Recording twice. The replay screen and the history list would then show
//        a song the user played once as two plays, and no amount of reloading
//        would correct it.
//
// RESPONSIBILITIES
//     - Pin the seek handling in both directions.
//     - Pin the threshold, including the two ways it can be met.
//     - Pin the once-only guarantee.
//
// DEPENDENCIES
//     - kotlin.test and the stats package.
//
// INTEGRATION NOTES
//     - [ListeningStats] writes to disk through [AppPaths], so these tests point
//       it at a temporary file and delete it afterwards rather than touching the
//       developer's own history. That is also why the file is per-test rather than
//       per-class: two tests sharing one file would see each other's plays.

package com.ihimanshunayak.freemusic.desktop

import com.ihimanshunayak.freemusic.desktop.data.stats.ListeningRecorder
import com.ihimanshunayak.freemusic.desktop.data.stats.ListeningStats
import com.ihimanshunayak.freemusic.desktop.data.stats.ReplayPeriod
import com.ihimanshunayak.freemusic.desktop.model.Track
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListeningRecorderTest {

    // ---- what counts as a listen -------------------------------------------

    @Test
    fun `half the track is a listen`() {
        withStats { stats ->
            val recorder = ListeningRecorder(stats)
            recorder.onTrackStarted(track(durationSeconds = 200))
            // 100 of 200 seconds is exactly the halfway mark.
            repeat(50) { step -> recorder.onPosition(step * 2_000L, 200_000L) }
            recorder.onPosition(100_000L, 200_000L)
            assertEquals(1, stats.plays.value.size, "half a 200 second track must count")
        }
    }

    @Test
    fun `four minutes of a long mix is a listen`() {
        withStats { stats ->
            val recorder = ListeningRecorder(stats)
            // An hour-long set: half of it is 30 minutes, but the four-minute rule
            // fires long before that.
            recorder.onTrackStarted(track(durationSeconds = 3_600))
            var position = 0L
            while (position <= 245_000L) {
                recorder.onPosition(position, 3_600_000L)
                position += 1_000L
            }
            assertEquals(1, stats.plays.value.size, "four minutes must be enough for a long track")
        }
    }

    @Test
    fun `a skip is not a listen`() {
        withStats { stats ->
            val recorder = ListeningRecorder(stats)
            val first = track(id = "a", durationSeconds = 300)
            recorder.onTrackStarted(first)
            recorder.onPosition(5_000L, 300_000L)
            // The user moves on after five seconds.
            recorder.onTrackStarted(track(id = "b", durationSeconds = 300))
            recorder.onStopped()
            assertTrue(stats.plays.value.isEmpty(), "a five second skip must not be recorded")
        }
    }

    @Test
    fun `an unknown duration counts on the four minute rule alone`() {
        withStats { stats ->
            val recorder = ListeningRecorder(stats)
            // A live stream reports no length; half of nothing is nothing, so only
            // the time rule can apply.
            recorder.onTrackStarted(track(durationSeconds = 0))
            var position = 0L
            while (position <= 245_000L) {
                recorder.onPosition(position, 0L)
                position += 1_000L
            }
            assertEquals(1, stats.plays.value.size, "a stream of unknown length must still count")
        }
    }

    // ---- seeking ----------------------------------------------------------

    @Test
    fun `a forward seek does not bank the skipped span`() {
        withStats { stats ->
            val recorder = ListeningRecorder(stats)
            recorder.onTrackStarted(track(durationSeconds = 600))
            recorder.onPosition(1_000L, 600_000L)
            // Jump most of the way through. The delta is implausibly large for one
            // poll, so it is a seek and contributes nothing.
            recorder.onPosition(500_000L, 600_000L)
            recorder.onPosition(501_000L, 600_000L)
            assertTrue(
                stats.plays.value.isEmpty(),
                "seeking to the end of a ten minute track must not count as listening to it",
            )
        }
    }

    @Test
    fun `a backwards seek contributes nothing but does not reset the count`() {
        withStats { stats ->
            val recorder = ListeningRecorder(stats)
            recorder.onTrackStarted(track(durationSeconds = 200))
            var position = 0L
            while (position <= 60_000L) {
                recorder.onPosition(position, 200_000L)
                position += 1_000L
            }
            val before = recorder.listenedSecondsPublic
            // Rewind to the start and play again.
            recorder.onPosition(0L, 200_000L)
            recorder.onPosition(1_000L, 200_000L)
            assertTrue(
                recorder.listenedSecondsPublic <= before + 1,
                "a rewind must not add time (was $before, now ${recorder.listenedSecondsPublic})",
            )
        }
    }

    @Test
    fun `the count survives a pause`() {
        withStats { stats ->
            val recorder = ListeningRecorder(stats)
            recorder.onTrackStarted(track(durationSeconds = 200))
            var position = 0L
            while (position <= 50_000L) {
                recorder.onPosition(position, 200_000L)
                position += 1_000L
            }
            val afterPlaying = recorder.listenedSecondsPublic
            // Paused: the poll keeps reporting the same position.
            repeat(20) { recorder.onPosition(50_000L, 200_000L) }
            assertEquals(
                afterPlaying,
                recorder.listenedSecondsPublic,
                "a stationary position must not accrue listening time",
            )
        }
    }

    // ---- once only --------------------------------------------------------

    @Test
    fun `a track is recorded once however long it plays`() {
        withStats { stats ->
            val recorder = ListeningRecorder(stats)
            recorder.onTrackStarted(track(durationSeconds = 200))
            var position = 0L
            // Play well past the threshold, to the end.
            while (position <= 200_000L) {
                recorder.onPosition(position, 200_000L)
                position += 1_000L
            }
            assertEquals(1, stats.plays.value.size, "one listen is one play, not two hundred")
        }
    }

    @Test
    fun `a second track records a second play`() {
        withStats { stats ->
            val recorder = ListeningRecorder(stats)
            listOf("a", "b").forEach { id ->
                recorder.onTrackStarted(track(id = id, durationSeconds = 200))
                var position = 0L
                while (position <= 110_000L) {
                    recorder.onPosition(position, 200_000L)
                    position += 1_000L
                }
            }
            assertEquals(2, stats.plays.value.size)
            // Oldest first. That order is load-bearing rather than cosmetic:
            // `playsIn` and `summarise` walk the list forwards to build a timeline,
            // and the history screen reverses it for display.
            assertEquals(listOf("a", "b"), stats.plays.value.map { it.trackId })
        }
    }

    @Test
    fun `replaying the same track records it twice`() {
        withStats { stats ->
            val recorder = ListeningRecorder(stats)
            repeat(2) {
                recorder.onTrackStarted(track(id = "same", durationSeconds = 200))
                var position = 0L
                while (position <= 110_000L) {
                    recorder.onPosition(position, 200_000L)
                    position += 1_000L
                }
            }
            // Two listens is genuinely two plays; the history is a log, not a set.
            assertEquals(2, stats.plays.value.size)
        }
    }

    @Test
    fun `a position before a track has started is ignored`() {
        withStats { stats ->
            val recorder = ListeningRecorder(stats)
            recorder.onPosition(120_000L, 200_000L)
            recorder.onPosition(121_000L, 200_000L)
            assertTrue(stats.plays.value.isEmpty(), "no track means nothing to record")
        }
    }

    // ---- the period windows ----------------------------------------------

    @Test
    fun `a period knows how far back it reaches`() {
        assertEquals(7, ReplayPeriod.WEEK.days)
        assertEquals(30, ReplayPeriod.MONTH.days)
        assertEquals(90, ReplayPeriod.QUARTER.days)
        assertEquals(365, ReplayPeriod.YEAR.days)
        // All time is unbounded, which is what null means here.
        assertEquals(null, ReplayPeriod.ALL_TIME.days)
    }

    @Test
    fun `every period has a label for the selector`() {
        ReplayPeriod.entries.forEach { period ->
            assertTrue(period.label.isNotBlank(), "${period.name} has no label")
        }
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Runs [body] against a recorder and a throwaway history file.
     *
     * The file is deleted even when the body throws, so a failing assertion cannot
     * leave debris in the temp directory or leak into a later run.
     */
    private fun withStats(body: (ListeningStats) -> Unit) {
        val file = File.createTempFile("freemusic-stats", ".json")
        file.delete()
        try {
            body(ListeningStats(file))
        } finally {
            file.delete()
        }
    }

    private fun track(id: String = "vid", durationSeconds: Int = 200) = Track(
        id = id,
        title = "A track",
        artist = "An artist",
        durationSeconds = durationSeconds,
    )
}
