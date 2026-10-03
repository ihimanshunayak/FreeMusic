// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - now-playing bar.
//
// The persistent transport at the bottom of the window. It is the only control
// surface that is always visible, which is why it carries the seek bar as well
// as the transport buttons: reaching the player should never require navigating
// to a page.

package com.ihimanshunayak.freemusic.desktop.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.audio.PlaybackSnapshot
import com.ihimanshunayak.freemusic.desktop.model.PlaybackState
import com.ihimanshunayak.freemusic.desktop.model.RepeatMode

/**
 * Bottom transport bar.
 *
 * The seek slider keeps a local value while the user is dragging it: binding it
 * straight to the engine's position would snap the thumb back to the real
 * position on every 500 ms poll and make the bar impossible to drag.
 */
@Composable
fun NowPlayingBar(
    snapshot: PlaybackSnapshot,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onSkipBy: (Long) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onOpenQueue: () -> Unit,
    engineAvailable: Boolean,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }

    val track = snapshot.currentTrack
    val fraction = if (dragging) dragFraction else snapshot.progress
    val isPlaying = snapshot.state == PlaybackState.PLAYING
    val buffering = snapshot.state == PlaybackState.BUFFERING

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(84.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ---- left: artwork and metadata ------------------------------------
        Row(
            modifier = Modifier.width(260.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(track?.thumbnailUrl, Modifier.size(52.dp), cornerRadius = 8)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = track?.title ?: "Nothing playing",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track?.artist ?: "Pick a song to start",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                snapshot.error?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // ---- centre: transport and seek ------------------------------------
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        Icons.Outlined.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (snapshot.shuffle) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onPrevious, enabled = engineAvailable && track != null) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous")
                }
                IconButton(
                    onClick = onTogglePlayPause,
                    enabled = engineAvailable && track != null,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(9.dp),
                    ) {
                        Icon(
                            imageVector = if (isPlaying || buffering) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                IconButton(onClick = onNext, enabled = engineAvailable && track != null) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next")
                }
                IconButton(onClick = onToggleRepeat) {
                    Icon(
                        imageVector = when (snapshot.repeatMode) {
                            RepeatMode.ONE -> Icons.Outlined.RepeatOne
                            else -> Icons.Outlined.Repeat
                        },
                        contentDescription = "Repeat",
                        tint = if (snapshot.repeatMode == RepeatMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatMillis(if (dragging) (dragFraction * snapshot.durationMillis).toLong() else snapshot.positionMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = fraction,
                    onValueChange = {
                        dragging = true
                        dragFraction = it
                    },
                    onValueChangeFinished = {
                        onSeek(dragFraction)
                        dragging = false
                    },
                    enabled = snapshot.durationMillis > 0,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                )
                Text(
                    text = formatMillis(snapshot.durationMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- right: volume and queue ---------------------------------------
        Row(
            modifier = Modifier.width(200.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onToggleMute) {
                Icon(
                    imageVector = when {
                        snapshot.muted || snapshot.volume <= 0.001f -> Icons.Default.VolumeMute
                        snapshot.volume < 0.5f -> Icons.Default.VolumeDown
                        else -> Icons.Default.VolumeUp
                    },
                    contentDescription = if (snapshot.muted) "Unmute" else "Mute",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Slider(
                value = if (snapshot.muted) 0f else snapshot.volume,
                onValueChange = onVolumeChange,
                modifier = Modifier.width(90.dp),
            )
            IconButton(onClick = onOpenQueue) {
                Icon(
                    Icons.Outlined.PlaylistPlay,
                    contentDescription = "Queue",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
