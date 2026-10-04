// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - play history screen.
//
// NAME
//     HistoryScreen.kt - what has been played, newest first.
//
// DESCRIPTION
//     The same records the statistics screen summarises, shown as a list rather
//     than as totals, because the two questions are different: "what did I play
//     yesterday" wants a chronological list, and "what do I play most" wants a
//     ranking. History therefore offers search over its own rows and a date
//     filter, and leaves the rankings to Statistics.
//
//     Rows are grouped by day with a heading, which is what makes a long list
//     scannable - "when" is the question a history answers, and a flat list of
//     timestamps makes the reader do that work themselves.
//
// RESPONSIBILITIES
//     - List recorded plays, newest first, grouped by date.
//     - Filter by free text across title, artist and album.
//     - Replay a recorded track.
//     - Clear the history.
//
// DEPENDENCIES
//     - [PlayRecord] from the stats layer.
//     - [TrackRow] and the Fluent chrome.
//
// INTEGRATION NOTES
//     - A recorded play is not a `Track`: it has no video id for a local file and
//       no source. Replaying therefore hands the record to the caller, which
//       resolves it through the same path as a search row, rather than this
//       screen inventing a track that might not be streamable.
//     - Clearing is destructive and shared with Statistics, so it goes through a
//       confirmation rather than firing on the first click.

package com.ihimanshunayak.freemusic.desktop.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.data.stats.PlayRecord
import com.ihimanshunayak.freemusic.desktop.ui.component.EmptyState
import com.ihimanshunayak.freemusic.desktop.ui.component.FluentGlyphs
import com.ihimanshunayak.freemusic.desktop.ui.component.SectionHeader
import com.ihimanshunayak.freemusic.desktop.ui.component.TextRow
import com.ihimanshunayak.freemusic.desktop.ui.component.TrackRow
import com.ihimanshunayak.freemusic.desktop.ui.component.VerticalGap
import com.ihimanshunayak.freemusic.desktop.model.SourceKind
import com.ihimanshunayak.freemusic.desktop.model.Track
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.Icon
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text

/**
 * The play history.
 *
 * @param plays every recorded play, newest first.
 * @param onReplay plays a record again through the normal resolution path.
 */
@Composable
fun HistoryScreen(
    plays: List<PlayRecord>,
    currentTrackId: String?,
    isPlaying: Boolean,
    onReplay: (PlayRecord) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    val shown = remember(plays, filter) {
        val needle = filter.trim().lowercase()
        if (needle.isEmpty()) {
            plays
        } else {
            plays.filter { record ->
                record.title.lowercase().contains(needle) ||
                    record.artist.lowercase().contains(needle) ||
                    record.album.orEmpty().lowercase().contains(needle)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        SectionHeader("History") {
            SubtleButton(onClick = { confirmClear = true }, disabled = plays.isEmpty()) {
                Icon(FluentGlyphs.Delete, contentDescription = null)
                Text("Clear", modifier = Modifier.padding(start = 6.dp))
            }
        }

        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            TextRow(
                title = "Filter",
                value = filter,
                onValueChange = { filter = it },
                placeholder = "Song, artist or album",
                modifier = Modifier.width(360.dp),
                detail = if (filter.isBlank()) {
                    "${plays.size} plays recorded."
                } else {
                    "${shown.size} of ${plays.size} match."
                },
            )
            VerticalGap(8.dp)
        }

        if (shown.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = FluentGlyphs.History,
                    title = if (plays.isEmpty()) "Nothing played yet" else "No matches",
                    detail = if (plays.isEmpty()) {
                        "A play is recorded once a track has been listened to for half its " +
                            "length, or four minutes - whichever comes first."
                    } else {
                        "Nothing in your history matches \"$filter\"."
                    },
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 16.dp),
            ) {
                // Grouped by the date string the record already carries, which is
                // the machine-local date the listen happened on rather than a
                // reformatted timestamp that could drift across time zones.
                shown.groupBy { it.date }.forEach { (date, dayPlays) ->
                    item(key = "date-$date") {
                        Text(
                            text = date,
                            style = FluentTheme.typography.bodyStrong,
                            color = FluentTheme.colors.text.text.secondary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(
                        count = dayPlays.size,
                        key = { index ->
                            val record = dayPlays[index]
                            "${record.trackId}-${record.playedAtMs}-$index"
                        },
                    ) { index ->
                        val record = dayPlays[index]
                        TrackRow(
                            track = record.toTrack(),
                            isCurrent = record.trackId == currentTrackId,
                            isPlaying = isPlaying,
                            onClick = { onReplay(record) },
                            trailing = {
                                Text(
                                    text = formatListened(record.listenedSeconds),
                                    style = FluentTheme.typography.caption,
                                    color = FluentTheme.colors.text.text.tertiary,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ContentDialog(
            title = "Clear play history?",
            visible = true,
            content = {
                Text(
                    "Every recorded play is deleted, which also resets the rankings on the " +
                        "Statistics screen. This cannot be undone.",
                )
            },
            primaryButtonText = "Clear",
            closeButtonText = "Keep",
            onButtonClick = { button ->
                if (button == ContentDialogButton.Primary) onClear()
                confirmClear = false
            },
        )
    }
}

/**
 * A record as a display row.
 *
 * The row is for reading, not for resolving: a click hands the whole record to
 * the caller, which resolves it through the same path as a search row. Inventing
 * a streamable track here would risk queueing one that does not exist.
 */
private fun PlayRecord.toTrack() = Track(
    id = trackId,
    title = title,
    artist = artist,
    album = album,
    source = SourceKind.YOUTUBE_MUSIC,
    videoId = trackId.takeIf { it.isNotBlank() },
)

/** `3:24 listened`, or a dash when the recording predates the field. */
private fun formatListened(seconds: Int): String {
    if (seconds <= 0) return "-"
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} listened"
}
