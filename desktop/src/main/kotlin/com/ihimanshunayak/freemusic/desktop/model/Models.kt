// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - domain model.
//
// These types mirror the Android app's data layer closely enough that the two
// codebases stay recognisably the same product, but they carry no Android
// imports, so the same values flow through the desktop UI, a unit test and a
// background resolver without translation.

package com.ihimanshunayak.freemusic.desktop.model

import kotlinx.serialization.Serializable

/** Where a track came from. Drives how a stream is resolved and what is shown. */
@Serializable
enum class SourceKind {
    /** YouTube Music, resolved through InnerTubeX. */
    YOUTUBE_MUSIC,

    /** A file already on this machine. */
    LOCAL_FILE,
}

/**
 * One playable row.
 *
 * [videoId] is null for local files and non-null for everything YouTube-backed,
 * which is what [isStreamable] keys off when the player decides whether it needs
 * a resolved URL before it can start.
 */
@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSeconds: Int = 0,
    val thumbnailUrl: String? = null,
    val source: SourceKind = SourceKind.YOUTUBE_MUSIC,
    val videoId: String? = null,
    val localPath: String? = null,
    val isExplicit: Boolean = false,
) {
    val isStreamable: Boolean get() = source == SourceKind.YOUTUBE_MUSIC && videoId != null

    /** `3:07`, or `1:02:44` once the track runs past an hour. */
    val durationLabel: String
        get() {
            if (durationSeconds <= 0) return "--:--"
            val h = durationSeconds / 3600
            val m = (durationSeconds % 3600) / 60
            val s = durationSeconds % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
}

/** A single row of search results, before the user has picked anything. */
@Serializable
data class SearchResult(
    val title: String,
    val subtitle: String,
    val videoId: String? = null,
    val browseId: String? = null,
    val playlistId: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Int = 0,
    val kind: ResultKind = ResultKind.SONG,
)

@Serializable
enum class ResultKind {
    SONG, VIDEO, ALBUM, ARTIST, PLAYLIST, EPISODE, UNKNOWN
}

/** A named collection of tracks that can be queued in one go. */
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val trackCount: Int = 0,
    val thumbnailUrl: String? = null,
    val ownerName: String? = null,
)

/** What the player is doing right now. */
enum class PlaybackState {
    IDLE, BUFFERING, PLAYING, PAUSED, STOPPED, ENDED, ERROR
}

/** User-selectable playback order. */
enum class RepeatMode {
    OFF, ALL, ONE
}

/** A failure worth showing the user, with enough context to act on. */
data class UserFacingError(
    val title: String,
    val detail: String,
    val cause: Throwable? = null,
)
