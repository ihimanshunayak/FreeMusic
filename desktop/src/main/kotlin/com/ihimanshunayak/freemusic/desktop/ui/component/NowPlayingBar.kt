// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - now-playing bar.
//
// NAME
//     NowPlayingBar.kt - the persistent transport at the bottom of the window.
//
// DESCRIPTION
//     The only control surface that is always visible, which is why it carries
//     the seek bar as well as the transport buttons: reaching the player should
//     never require navigating to a page.
//
//     The bar is drawn on the Mica layer rather than on an opaque panel, so the
//     desktop wallpaper stays visible through it the way it does through
//     Explorer's own chrome. That is why it uses the acrylic brush rather than a
//     solid colour - a solid fill here would break the material the window is
//     built on.
//
// RESPONSIBILITIES
//     - Transport: play/pause, previous, next, shuffle, repeat.
//     - Seeking, with a drag that does not fight the engine's position poll.
//     - Volume and mute.
//
// DEPENDENCIES
//     - [FluentGlyphs] for the transport glyphs.
//     - [formatMillis] for both time labels.
//
// INTEGRATION NOTES
//     - The seek slider keeps a local value while the user drags it. Binding it
//       straight to the engine's position would snap the thumb back to the real
//       position on every poll and make the bar impossible to drag.
//     - Repeat cycles off -> all -> one, matching the Android build's order.

package com.ihimanshunayak.freemusic.desktop.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Icon
import io.github.composefluent.component.Slider
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text

/**
 * The bottom transport bar.
 *
 * The centre column is given the flexible width and the two side columns a fixed
 * one, so widening the window grows the seek bar rather than the artwork - the
 * thing a user drags is the thing that should have room.
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
    onOpenNowPlaying: () -> Unit,
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
            .background(FluentTheme.colors.background.acrylic.default)
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
                    style = FluentTheme.typography.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track?.artist ?: "Pick a song to start",
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                snapshot.error?.let { error ->
                    Text(
                        text = error,
                        style = FluentTheme.typography.caption,
                        color = FluentTheme.colors.system.critical,
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
                SubtleButton(onClick = onToggleShuffle, iconOnly = true) {
                    Icon(
                        FluentGlyphs.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (snapshot.shuffle) FluentTheme.colors.text.accent.primary
                        else FluentTheme.colors.text.text.secondary,
                    )
                }
                SubtleButton(
                    onClick = onPrevious,
                    disabled = !engineAvailable || track == null,
                    iconOnly = true,
                ) {
                    Icon(FluentGlyphs.Previous, contentDescription = "Previous")
                }

                // The play button is the one accent-filled control in the bar, so
                // it reads as the primary action without any other control having
                // to be de-emphasised to make room for it.
                Box(
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            if (engineAvailable && track != null) FluentTheme.colors.fillAccent.default
                            else FluentTheme.colors.control.disabled,
                        )
                        .clickable(enabled = engineAvailable && track != null, onClick = onTogglePlayPause),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlaying || buffering) FluentGlyphs.Pause else FluentGlyphs.Play,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = FluentTheme.colors.text.onAccent.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }

                SubtleButton(onClick = onNext, disabled = !engineAvailable || track == null, iconOnly = true) {
                    Icon(FluentGlyphs.Next, contentDescription = "Next")
                }
                SubtleButton(onClick = onToggleRepeat, iconOnly = true) {
                    Icon(
                        imageVector = if (snapshot.repeatMode == RepeatMode.ONE) FluentGlyphs.Repeat
                        else FluentGlyphs.Repeat,
                        contentDescription = "Repeat",
                        tint = if (snapshot.repeatMode == RepeatMode.OFF) FluentTheme.colors.text.text.secondary
                        else FluentTheme.colors.text.accent.primary,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatMillis(if (dragging) (dragFraction * snapshot.durationMillis).toLong() else snapshot.positionMillis),
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
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
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                )
            }
        }

        // ---- right: volume and queue ---------------------------------------
        Row(
            modifier = Modifier.width(200.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            SubtleButton(onClick = onToggleMute, iconOnly = true) {
                Icon(
                    imageVector = when {
                        snapshot.muted || snapshot.volume <= 0.001f -> FluentGlyphs.VolumeMute
                        snapshot.volume < 0.5f -> FluentGlyphs.VolumeLow
                        else -> FluentGlyphs.VolumeHigh
                    },
                    contentDescription = if (snapshot.muted) "Unmute" else "Mute",
                    tint = FluentTheme.colors.text.text.secondary,
                )
            }
            Slider(
                value = if (snapshot.muted) 0f else snapshot.volume,
                onValueChange = onVolumeChange,
                modifier = Modifier.width(90.dp),
            )
            SubtleButton(onClick = onOpenQueue, iconOnly = true) {
                Icon(
                    FluentGlyphs.Queue,
                    contentDescription = "Queue",
                    tint = FluentTheme.colors.text.text.secondary,
                )
            }
            SubtleButton(onClick = onOpenNowPlaying, iconOnly = true) {
                Icon(
                    FluentGlyphs.Expand,
                    contentDescription = "Now playing",
                    tint = FluentTheme.colors.text.text.secondary,
                )
            }
        }
    }
}
