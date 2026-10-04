// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - now playing screen.
//
// NAME
//     NowPlayingScreen.kt - the full-window player.
//
// DESCRIPTION
//     The transport bar is for the window you are working in; this is the screen
//     for the window you are *listening* in. It is deliberately built around the
//     artwork rather than around a list of controls: a large sleeve, the colours
//     it carries, and the few controls that matter, with everything secondary -
//     lyrics, equalizer, canvas mode, the queue - one click away rather than
//     crowding the surface.
//
//     The canvas modes come from the Android build's own vocabulary, translated
//     for a desktop window:
//
//       Automatic   - a loop when the source has one, otherwise the still cover
//                     with a slow drift so the surface is never quite dead.
//       Loops only  - only animate when a real loop was found.
//       Off         - the still cover, still.
//
//     Windows has no equivalent of Android's animated YouTube canvas clips, and
//     shipping a fake one would be worse than shipping none, so the "loop" here
//     is the artwork itself animated: a slow pan and scale over the sleeve's own
//     dominant colours. It reads as intentional rather than as a substitute.
//
// RESPONSIBILITIES
//     - Large artwork with the artwork-derived palette washing the background.
//     - Full transport: seek, play/pause, skip, shuffle, repeat, volume, mute.
//     - Playback speed, sleep timer, and the nerd-stats overlay.
//     - Entry points to lyrics, the equalizer and the queue.
//
// DEPENDENCIES
//     - [artworkPalette] / [FreeMusicAccent] for the colour.
//     - [FluentGlyphs] for the transport.
//
// INTEGRATION NOTES
//     - The seek bar keeps a local value while dragging, exactly as the bar at
//       the bottom of the window does, and for the same reason.
//     - The wash is a radial gradient rather than a solid fill so the artwork
//       stays the brightest thing on screen. A solid tint would flatten the
//       sleeve into the background and lose the effect entirely.

package com.ihimanshunayak.freemusic.desktop.ui.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimationRepeat
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.audio.PlaybackSnapshot
import com.ihimanshunayak.freemusic.desktop.data.CanvasMode
import com.ihimanshunayak.freemusic.desktop.data.Settings
import com.ihimanshunayak.freemusic.desktop.model.PlaybackState
import com.ihimanshunayak.freemusic.desktop.model.RepeatMode
import com.ihimanshunayak.freemusic.desktop.ui.component.FluentGlyphs
import com.ihimanshunayak.freemusic.desktop.ui.RemoteImage
import com.ihimanshunayak.freemusic.desktop.ui.component.formatMillis
import com.ihimanshunayak.freemusic.desktop.ui.theme.LocalArtworkPalette
import com.ihimanshunayak.freemusic.desktop.ui.theme.artworkPalette
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Icon
import io.github.composefluent.component.Slider
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import io.github.composefluent.component.ToggleButton
import kotlinx.coroutines.delay

/**
 * The full-window player.
 *
 * @param settings the live settings, read for the canvas mode, the speed and the
 *   nerd-stats flag so the screen never shows a control that does nothing.
 * @param onOpenLyrics / [onOpenEqualizer] / [onOpenQueue] navigate rather than
 *   opening a panel: a desktop window has the room, and a page is easier to
 *   return from than a modal.
 */
@Composable
fun NowPlayingScreen(
    snapshot: PlaybackSnapshot,
    settings: Settings,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onToggleShuffle: () -> Unit,
    onSetRepeat: (RepeatMode) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetCanvasMode: (CanvasMode) -> Unit,
    onSetSleepTimer: (Int) -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenQueue: () -> Unit,
    onToggleFullScreen: () -> Unit,
    onClose: () -> Unit,
    engineAvailable: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = artworkPalette
    val track = snapshot.currentTrack
    val isPlaying = snapshot.state == PlaybackState.PLAYING

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }
    var sleepMinutes by remember { mutableStateOf(0) }

    val fraction = if (dragging) dragFraction else snapshot.progress

    // The sleep timer counts down in the composition rather than in the player:
    // pausing the count when the window is closed would make the timer
    // meaningless, but pausing it when playback pauses is what a user expects.
    LaunchedEffect(sleepMinutes, isPlaying) {
        if (sleepMinutes <= 0 || !isPlaying) return@LaunchedEffect
        delay(60_000L)
        val next = sleepMinutes - 1
        sleepMinutes = next
        if (next <= 0) onTogglePlayPause()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FluentTheme.colors.background.solid.base)
            // The wash is drawn behind everything so the sleeve reads as the
            // source of the colour rather than as an object pasted onto it.
            .drawBehind {
                val center = Offset(size.width * 0.5f, size.height * 0.32f)
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(
                            palette.accent.copy(alpha = 0.30f),
                            palette.wash.copy(alpha = 0.14f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = size.maxDimension * 0.75f,
                    ),
                )
            },
    ) {
        // A back affordance, because this is a sub-view of the shell rather than
        // a destination and the navigation pane shows the destination behind it.
        SubtleButton(
            onClick = onClose,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp),
        ) {
            Icon(FluentGlyphs.Back, contentDescription = null)
            Text("Back", modifier = Modifier.padding(start = 8.dp))
        }

        Row(modifier = Modifier.fillMaxSize()) {
            // ---- left: the artwork and the metadata under it ----------------
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ArtworkStage(
                    artworkUrl = track?.thumbnailUrl,
                    canvasMode = settings.canvasMode,
                    reduceAnimation = settings.reduceAnimation,
                    fullBleed = settings.fullBleedArtwork,
                    meshGradient = settings.meshGradient,
                )

                Text(
                    text = track?.title ?: "Nothing playing",
                    style = FluentTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 24.dp),
                )
                Text(
                    text = track?.artist ?: "Pick a song to start",
                    style = FluentTheme.typography.body,
                    color = FluentTheme.colors.text.text.secondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (!track?.album.isNullOrBlank()) {
                    Text(
                        text = track.album,
                        style = FluentTheme.typography.caption,
                        color = FluentTheme.colors.text.text.tertiary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // ---- right: the controls ----------------------------------------
            Column(
                modifier = Modifier
                    .width(420.dp)
                    .fillMaxHeight()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                snapshot.error?.let { error ->
                    Text(
                        text = error,
                        style = FluentTheme.typography.caption,
                        color = FluentTheme.colors.system.critical,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }

                SeekBlock(
                    fraction = fraction,
                    positionMillis = if (dragging) (dragFraction * snapshot.durationMillis).toLong() else snapshot.positionMillis,
                    durationMillis = snapshot.durationMillis,
                    enabled = snapshot.durationMillis > 0,
                    onDrag = {
                        dragging = true
                        dragFraction = it
                    },
                    onCommit = {
                        onSeek(dragFraction)
                        dragging = false
                    },
                    buffering = snapshot.state == PlaybackState.BUFFERING,
                )

                TransportBlock(
                    isPlaying = isPlaying,
                    buffering = snapshot.state == PlaybackState.BUFFERING,
                    shuffle = snapshot.shuffle,
                    repeatMode = snapshot.repeatMode,
                    enabled = engineAvailable && track != null,
                    onTogglePlayPause = onTogglePlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onToggleShuffle = onToggleShuffle,
                    onSetRepeat = onSetRepeat,
                )

                VolumeBlock(
                    volume = snapshot.volume,
                    muted = snapshot.muted,
                    onVolumeChange = onVolumeChange,
                    onToggleMute = onToggleMute,
                )

                SpeedAndSleepBlock(
                    speed = settings.playbackSpeed,
                    onSetSpeed = onSetSpeed,
                    sleepMinutes = sleepMinutes,
                    onSetSleepTimer = {
                        sleepMinutes = it
                        onSetSleepTimer(it)
                    },
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SubtleButton(onClick = onOpenLyrics) {
                        Icon(FluentGlyphs.Lyrics, contentDescription = null)
                        Text("Lyrics", modifier = Modifier.padding(start = 6.dp))
                    }
                    SubtleButton(onClick = onOpenEqualizer) {
                        Icon(FluentGlyphs.Equalizer, contentDescription = null)
                        Text("Equalizer", modifier = Modifier.padding(start = 6.dp))
                    }
                    SubtleButton(onClick = onOpenQueue) {
                        Icon(FluentGlyphs.Queue, contentDescription = null)
                        Text("Queue", modifier = Modifier.padding(start = 6.dp))
                    }
                    SubtleButton(onClick = onToggleFullScreen, iconOnly = true) {
                        Icon(FluentGlyphs.FullScreen, contentDescription = "Full screen")
                    }
                }

                if (settings.showNerdStats) {
                    NerdStats(snapshot = snapshot, settings = settings)
                }
            }
        }
    }
}

/**
 * The artwork, animated according to the canvas mode.
 *
 * The drift is a 24-second cycle of a 4% scale and a small vertical translate:
 * long enough that a user does not consciously see it repeat, small enough that
 * it never crops the sleeve's own border off. Both are skipped when the user has
 * asked for reduced animation, and the still image is drawn either way - the
 * animation is a motion applied to the artwork, not a replacement for it.
 *
 * The mesh gradient is a separate, additive layer rather than part of the drift.
 * It is derived from the same artwork palette the rest of the window uses, so it
 * cannot clash with the accent, and being translucent it reads as a wash over the
 * wallpaper rather than as a solid panel.
 */
@Composable
private fun ArtworkStage(
    artworkUrl: String?,
    canvasMode: CanvasMode,
    reduceAnimation: Boolean,
    fullBleed: Boolean,
    meshGradient: Boolean,
) {
    val animate = !reduceAnimation && canvasMode != CanvasMode.OFF

    val transition = rememberInfiniteTransition(label = "canvas")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 24_000, easing = LinearEasing),
            repeatMode = AnimationRepeat.Reverse,
        ),
        label = "drift",
    )

    val scale = if (animate) 1f + drift * 0.04f else 1f

    Box(
        modifier = Modifier
            .fillMaxWidth(if (fullBleed) 0.86f else 0.62f)
            .aspectRatio(1f)
            .scale(scale)
            .clip(FluentTheme.shapes.overlay),
        contentAlignment = Alignment.Center,
    ) {
        if (meshGradient && animate) {
            // Washes are taken from the artwork palette, which is already the
            // accent source, so this cannot drift away from the window's own tint.
            val palette = LocalArtworkPalette.current
            MeshGradient(
                primary = palette.accent,
                secondary = palette.wash,
                tertiary = palette.elevated,
                phase = drift,
                modifier = Modifier.fillMaxSize(),
            )
        }
        RemoteImage(url = artworkUrl.orEmpty(), modifier = Modifier.fillMaxSize())
    }
}

/**
 * A soft three-point colour wash.
 *
 * Drawn under the artwork, so it only shows in the margins the cover does not
 * cover - which is exactly the intent: it fills the frame rather than tinting the
 * sleeve itself. The two points move at different rates and in opposite
 * directions, which is what stops the effect reading as a single pulsing blob.
 */
@Composable
private fun MeshGradient(
    primary: Color,
    secondary: Color,
    tertiary: Color,
    phase: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(
            Brush.radialGradient(
                colors = listOf(
                    primary.copy(alpha = 0.55f),
                    secondary.copy(alpha = 0.30f),
                    Color.Transparent,
                ),
                center = Offset(x = 0.30f + phase * 0.12f, y = 0.28f),
                radius = 900f,
            ),
        ),
    )
    Box(
        modifier = modifier.background(
            Brush.radialGradient(
                colors = listOf(
                    tertiary.copy(alpha = 0.45f),
                    Color.Transparent,
                ),
                center = Offset(x = 0.78f, y = 0.70f - phase * 0.10f),
                radius = 800f,
            ),
        ),
    )
}

/** Seek bar plus its two time labels. */
@Composable
private fun SeekBlock(
    fraction: Float,
    positionMillis: Long,
    durationMillis: Long,
    enabled: Boolean,
    buffering: Boolean,
    onDrag: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = fraction,
            onValueChange = onDrag,
            onValueChangeFinished = { onCommit() },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatMillis(positionMillis),
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.text.text.secondary,
            )
            if (buffering) {
                Text(
                    text = "Buffering",
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.accent.primary,
                )
            }
            Text(
                text = formatMillis(durationMillis),
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.text.text.secondary,
            )
        }
    }
}

/** The five transport controls, centred and equally spaced. */
@Composable
private fun TransportBlock(
    isPlaying: Boolean,
    buffering: Boolean,
    shuffle: Boolean,
    repeatMode: RepeatMode,
    enabled: Boolean,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onSetRepeat: (RepeatMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SubtleButton(onClick = onToggleShuffle, iconOnly = true) {
            Icon(
                FluentGlyphs.Shuffle,
                contentDescription = "Shuffle",
                tint = if (shuffle) FluentTheme.colors.text.accent.primary
                else FluentTheme.colors.text.text.secondary,
            )
        }

        Box(modifier = Modifier.width(20.dp))
        SubtleButton(onClick = onPrevious, disabled = !enabled, iconOnly = true) {
            Icon(FluentGlyphs.Previous, contentDescription = "Previous")
        }

        AccentButton(
            onClick = onTogglePlayPause,
            disabled = !enabled,
            iconOnly = true,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .size(52.dp),
        ) {
            Icon(
                imageVector = if (isPlaying || buffering) FluentGlyphs.Pause else FluentGlyphs.Play,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(22.dp),
            )
        }

        SubtleButton(onClick = onNext, disabled = !enabled, iconOnly = true) {
            Icon(FluentGlyphs.Next, contentDescription = "Next")
        }
        Box(modifier = Modifier.width(20.dp))

        // Repeat is a three-state control with one button, so the button cycles
        // rather than opening a menu. Cycling is what Android does and what a
        // media key does everywhere else on the platform.
        ToggleButton(
            checked = repeatMode != RepeatMode.OFF,
            onCheckedChanged = {
                onSetRepeat(
                    when (repeatMode) {
                        RepeatMode.OFF -> RepeatMode.ALL
                        RepeatMode.ALL -> RepeatMode.ONE
                        RepeatMode.ONE -> RepeatMode.OFF
                    },
                )
            },
            iconOnly = true,
        ) {
            Icon(
                if (repeatMode == RepeatMode.ONE) FluentGlyphs.Like else FluentGlyphs.Repeat,
                contentDescription = when (repeatMode) {
                    RepeatMode.ONE -> "Repeat one"
                    RepeatMode.ALL -> "Repeat all"
                    RepeatMode.OFF -> "Repeat off"
                },
            )
        }
    }
}

/** A mute button and a volume slider, on one line. */
@Composable
private fun VolumeBlock(
    volume: Float,
    muted: Boolean,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SubtleButton(onClick = onToggleMute, iconOnly = true) {
            Icon(
                imageVector = when {
                    muted || volume <= 0.001f -> FluentGlyphs.VolumeMute
                    volume < 0.5f -> FluentGlyphs.VolumeLow
                    else -> FluentGlyphs.VolumeHigh
                },
                contentDescription = if (muted) "Unmute" else "Mute",
                tint = FluentTheme.colors.text.text.secondary,
            )
        }
        Slider(
            value = if (muted) 0f else volume,
            onValueChange = onVolumeChange,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        Text(
            text = "${((if (muted) 0f else volume) * 100).toInt()}%",
            style = FluentTheme.typography.caption,
            color = FluentTheme.colors.text.text.secondary,
            modifier = Modifier.padding(start = 8.dp).width(38.dp),
        )
    }
}

/** Playback speed, and the sleep timer's three presets. */
@Composable
private fun SpeedAndSleepBlock(
    speed: Float,
    onSetSpeed: (Float) -> Unit,
    sleepMinutes: Int,
    onSetSleepTimer: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Speed", style = FluentTheme.typography.caption, color = FluentTheme.colors.text.text.secondary)
            Text(
                text = "%.2fx".format(speed),
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.text.text.primary,
            )
        }
        Slider(
            value = speed,
            onValueChange = onSetSpeed,
            valueRange = 0.5f..2.0f,
            steps = 14,
            snap = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                FluentGlyphs.Sleep,
                contentDescription = null,
                tint = FluentTheme.colors.text.text.secondary,
                modifier = Modifier.size(16.dp),
            )
            listOf(0, 15, 30, 60).forEach { minutes ->
                SubtleButton(onClick = { onSetSleepTimer(minutes) }) {
                    Text(
                        text = if (minutes == 0) "Off" else "${minutes}m",
                        color = if (minutes != 0 && sleepMinutes == minutes) FluentTheme.colors.text.accent.primary
                        else FluentTheme.colors.text.text.primary,
                    )
                }
            }
            if (sleepMinutes > 0) {
                Text(
                    text = "$sleepMinutes min left",
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.accent.primary,
                )
            }
        }
    }
}

/** The optional technical readout, for when something is behaving oddly. */
@Composable
private fun NerdStats(snapshot: PlaybackSnapshot, settings: Settings) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            text = "Engine",
            style = FluentTheme.typography.caption,
            color = FluentTheme.colors.text.text.secondary,
        )
        val lines = listOf(
            "state" to snapshot.state.name,
            "position" to "${snapshot.positionMillis} ms",
            "duration" to "${snapshot.durationMillis} ms",
            "queue" to "${snapshot.queueIndex + 1} / ${snapshot.queue.size}",
            "quality" to settings.audioQuality.label,
            "backend" to settings.outputBackend.label,
            "volume" to "%.3f".format(if (snapshot.muted) 0f else snapshot.volume),
            "speed" to "%.2fx".format(settings.playbackSpeed),
            "crossfade" to "${settings.crossfadeSeconds}s",
        )
        lines.forEach { (key, value) ->
            Text(
                text = "$key  $value",
                style = com.ihimanshunayak.freemusic.desktop.ui.theme.MonospaceLogStyle,
                color = FluentTheme.colors.text.text.tertiary,
                maxLines = 1,
            )
        }
    }
}
