// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - model tests.
//
// `durationLabel` is what every list in the app shows, so it is worth pinning:
// the Android app formats it identically and the two must not drift.

package com.ihimanshunayak.freemusic.desktop.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackTest {

    @Test
    fun `duration under an hour is minutes and seconds`() {
        assertEquals("3:07", track(187).durationLabel)
        assertEquals("0:59", track(59).durationLabel)
        assertEquals("10:00", track(600).durationLabel)
    }

    @Test
    fun `duration of an hour or more gains an hours field`() {
        assertEquals("1:00:00", track(3600).durationLabel)
        assertEquals("1:02:44", track(3764).durationLabel)
        assertEquals("2:30:05", track(9005).durationLabel)
    }

    @Test
    fun `an unknown duration is shown as a placeholder rather than 0 00`() {
        assertEquals("--:--", track(0).durationLabel)
        assertEquals("--:--", track(-5).durationLabel)
    }

    @Test
    fun `a YouTube-backed track is streamable and a local file is not`() {
        val remote = Track(id = "ApXoWvfEYVU", title = "Sunflower", artist = "Post Malone", videoId = "ApXoWvfEYVU")
        val local = Track(
            id = "local:C:/Music/song.mp3",
            title = "song",
            artist = "Unknown artist",
            source = SourceKind.LOCAL_FILE,
            localPath = "C:/Music/song.mp3",
        )

        assertTrue(remote.isStreamable)
        assertFalse(local.isStreamable)
    }

    @Test
    fun `a youtube track without a video id is not streamable`() {
        val broken = Track(id = "x", title = "x", artist = "x", source = SourceKind.YOUTUBE_MUSIC)
        assertFalse(broken.isStreamable)
    }

    private fun track(seconds: Int) = Track(
        id = "id",
        title = "title",
        artist = "artist",
        durationSeconds = seconds,
    )
}
