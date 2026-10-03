// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - queue screen.
//
// The Android app shows the queue as a bottom sheet over the player; a desktop
// window has room to give it a page of its own, which is also the only place the
// user can reorder-by-removal and jump to an arbitrary point in the list.

package com.ihimanshunayak.freemusic.desktop.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.PlaylistRemove
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.audio.PlaybackSnapshot
import com.ihimanshunayak.freemusic.desktop.model.PlaybackState
import com.ihimanshunayak.freemusic.desktop.ui.component.EmptyState
import com.ihimanshunayak.freemusic.desktop.ui.component.TrackRow

/**
 * The current queue with its play position marked.
 *
 * The list scrolls itself to the playing row when it changes - a long queue that
 * does not follow the music makes the screen useless after the first few skips.
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
                Text("Queue", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = when (snapshot.queue.size) {
                        0 -> "Nothing queued"
                        1 -> "1 track"
                        else -> "${snapshot.queue.size} tracks"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (snapshot.queue.isNotEmpty()) {
                    TextButton(onClick = onToggleShuffle) {
                        Icon(
                            Icons.Outlined.Shuffle,
                            contentDescription = null,
                            tint = if (snapshot.shuffle) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = if (snapshot.shuffle) "Shuffling" else "Shuffle",
                            color = if (snapshot.shuffle) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    TextButton(onClick = onClear) {
                        Icon(Icons.Outlined.PlaylistRemove, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Clear", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }

        if (snapshot.queue.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.QueueMusic,
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
                            IconButton(
                                onClick = { onRemove(track.id) },
                                enabled = !isCurrent || snapshot.queue.size > 1,
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove from queue",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
