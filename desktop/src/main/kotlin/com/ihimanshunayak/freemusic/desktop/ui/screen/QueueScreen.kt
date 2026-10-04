// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - queue screen.
//
// NAME
//     QueueScreen.kt - the play queue, in order, with the current row marked.
//
// DESCRIPTION
//     The Android app shows the queue as a bottom sheet over the player. A
//     desktop window has room to give it a page of its own, which is also the
//     only place the user can drop a single track out of the middle of the list
//     and jump to an arbitrary point in it.
//
// RESPONSIBILITIES
//     - Render the queue with the playing row highlighted.
//     - Remove one track, clear the whole queue, toggle shuffle.
//     - Follow the music: scroll to the playing row as it changes.
//
// DEPENDENCIES
//     - [TrackRow] for each entry, so a queued track looks like it does anywhere
//       else in the app.
//
// INTEGRATION NOTES
//     - The scroll is wrapped in `runCatching` because the queue can shrink
//       between the effect being scheduled and it running, and asking a
//       LazyColumn to scroll past its end throws rather than no-ops.

package com.ihimanshunayak.freemusic.desktop.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.audio.PlaybackSnapshot
import com.ihimanshunayak.freemusic.desktop.model.PlaybackState
import com.ihimanshunayak.freemusic.desktop.ui.component.EmptyState
import com.ihimanshunayak.freemusic.desktop.ui.component.FluentGlyphs
import com.ihimanshunayak.freemusic.desktop.ui.component.TrackRow
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Icon
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text

/**
 * The current queue.
 *
 * The header carries the two actions that apply to the queue as a whole, and each
 * row carries the action that applies only to itself - that separation mirrors a
 * context menu without the extra click.
 */
@Composable
fun QueueScreen(
    snapshot: PlaybackSnapshot,
    onPlayIndex: (Int) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onToggleShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(snapshot.queueIndex) {
        if (snapshot.queueIndex in snapshot.queue.indices) {
            runCatching { listState.animateScrollToItem(snapshot.queueIndex) }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Queue", style = FluentTheme.typography.title)
                Text(
                    text = when (snapshot.queue.size) {
                        0 -> "Nothing queued"
                        1 -> "1 track"
                        else -> "${snapshot.queue.size} tracks"
                    },
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (snapshot.queue.isNotEmpty()) {
                    SubtleButton(onClick = onToggleShuffle) {
                        Icon(
                            FluentGlyphs.Shuffle,
                            contentDescription = null,
                            tint = if (snapshot.shuffle) FluentTheme.colors.text.accent.primary
                            else FluentTheme.colors.text.text.secondary,
                        )
                        Text(
                            text = if (snapshot.shuffle) "Shuffling" else "Shuffle",
                            color = if (snapshot.shuffle) FluentTheme.colors.text.accent.primary
                            else FluentTheme.colors.text.text.secondary,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    SubtleButton(onClick = onClear) {
                        Icon(FluentGlyphs.Remove, contentDescription = null)
                        Text("Clear", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }

        if (snapshot.queue.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = FluentGlyphs.Queue,
                    title = "The queue is empty",
                    detail = "Play something from Home, Search or your library.",
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(snapshot.queue, key = { index, track -> "$index:${track.id}" }) { index, track ->
                    val isCurrent = index == snapshot.queueIndex
                    TrackRow(
                        track = track,
                        isCurrent = isCurrent,
                        isPlaying = isCurrent && snapshot.state == PlaybackState.PLAYING,
                        onClick = { onPlayIndex(index) },
                        trailing = {
                            // Removing the playing row is only meaningful while
                            // something else is left to play; otherwise the queue
                            // would be emptied by a button that says "remove one".
                            SubtleButton(
                                onClick = { onRemove(track.id) },
                                disabled = isCurrent && snapshot.queue.size <= 1,
                                iconOnly = true,
                            ) {
                                Icon(
                                    FluentGlyphs.Delete,
                                    contentDescription = "Remove from queue",
                                    tint = FluentTheme.colors.text.text.secondary,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
