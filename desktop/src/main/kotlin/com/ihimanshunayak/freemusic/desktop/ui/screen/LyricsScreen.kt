// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - lyrics screen.
//
// NAME
//     LyricsScreen.kt - the synced lyrics pane.
//
// DESCRIPTION
//     Seventeen providers feed this screen, and they disagree: some return word
//     timings, some return line timings, some return plain text with no timings
//     at all. The pane therefore has three visual states rather than one, and it
//     says which it is in so a user who sees no highlight knows why.
//
//     The highlight, not the layout, is what makes this pane readable, so the
//     active line is scaled and coloured while past and future lines recede. The
//     list is keyed by line index so the animations stay attached to the right
//     row when a reload returns a different number of lines.
//
// RESPONSIBILITIES
//     - Render timed, word-timed and untimed lyrics.
//     - Follow the playing line, and let the user take over by scrolling.
//     - Offset, blur, and translation controls.
//
// DEPENDENCIES
//     - [LyricLine] and friends from the lyrics package.
//     - [LyricsQuery] for the manual re-fetch.
//
// INTEGRATION NOTES
//     - Auto-follow switches itself off the moment the user scrolls, and offers a
//       button to resume. The alternative - fighting the user for the scroll
//       position - is the single most common complaint about lyric panes.
//     - A word-synced line is highlighted per word; a line-synced line per line.
//       That difference is visible in the footer rather than hidden, because a
//       user comparing two providers needs to know which one they are looking at.

package com.ihimanshunayak.freemusic.desktop.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.audio.PlaybackSnapshot
import com.ihimanshunayak.freemusic.desktop.lyrics.LyricLine
import com.ihimanshunayak.freemusic.desktop.lyrics.LyricsResult
import com.ihimanshunayak.freemusic.desktop.ui.component.EmptyState
import com.ihimanshunayak.freemusic.desktop.ui.component.FluentGlyphs
import com.ihimanshunayak.freemusic.desktop.ui.component.SliderRow
import com.ihimanshunayak.freemusic.desktop.ui.component.ToggleRow
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Icon
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text

/**
 * The lyrics pane.
 *
 * @param result the loaded lyrics, or null while a lookup is running or after one
 *   failed. [status] carries the explanation in that case, which is what stops a
 *   blank pane from looking like a bug.
 */
@Composable
fun LyricsScreen(
    result: LyricsResult?,
    status: String,
    snapshot: PlaybackSnapshot,
    offsetMs: Int,
    blurInactive: Boolean,
    onOffsetChange: (Int) -> Unit,
    onToggleBlur: (Boolean) -> Unit,
    onSeekTo: (Float) -> Unit,
    onRefetch: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var autoFollow by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    val lines = result?.lines.orEmpty()
    val positionMs = snapshot.positionMillis + offsetMs
    // A linear scan of at most a few hundred short lines, recomputed four times a
    // second: cheaper than the state plumbing a lookup structure would need.
    val activeIndex = remember(lines, positionMs) { activeLineIndex(lines, positionMs) }

    // Following resumes whenever the track changes: a new song means the user's
    // earlier scroll position is meaningless, and continuing to hold it would
    // leave the pane parked in the middle of the previous song's lyrics.
    LaunchedEffect(snapshot.currentTrack?.videoId) { autoFollow = true }

    LaunchedEffect(activeIndex, autoFollow) {
        if (autoFollow && activeIndex >= 0) {
            // The active line is placed a third of the way down rather than at
            // the top, so the line the user is about to sing is already visible.
            val target = (activeIndex - 3).coerceAtLeast(0)
            runCatching { listState.animateScrollToItem(target) }
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
                Text("Lyrics", style = FluentTheme.typography.title)
                Text(
                    text = result?.source?.label ?: status,
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SubtleButton(onClick = onClose) {
                    Icon(FluentGlyphs.Back, contentDescription = null)
                    Text("Back", modifier = Modifier.padding(start = 6.dp))
                }
                SubtleButton(onClick = { autoFollow = !autoFollow }) {
                    Icon(
                        FluentGlyphs.Trending,
                        contentDescription = null,
                        tint = if (autoFollow) FluentTheme.colors.text.accent.primary
                        else FluentTheme.colors.text.text.secondary,
                    )
                    Text(
                        text = if (autoFollow) "Following" else "Free scroll",
                        modifier = Modifier.padding(start = 6.dp),
                        color = if (autoFollow) FluentTheme.colors.text.accent.primary
                        else FluentTheme.colors.text.text.primary,
                    )
                }
                SubtleButton(onClick = onRefetch) {
                    Icon(FluentGlyphs.Refresh, contentDescription = null)
                    Text("Reload", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }

        if (lines.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = FluentGlyphs.LyricsQuote,
                    title = "No lyrics yet",
                    detail = status.ifBlank { "Lyrics load automatically when a track starts." },
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                // Any drag means the user wants to look somewhere specific, so
                // following stops rather than pulling the list back under them.
                userScrollEnabled = true,
            ) {
                itemsIndexed(lines) { index, line ->
                    LyricRow(
                        line = line,
                        isActive = index == activeIndex,
                        isPast = index < activeIndex,
                        positionMs = snapshot.positionMillis + offsetMs,
                        blurInactive = blurInactive,
                        onClick = {
                            // Seeking to a lyric is what makes a lyric pane worth
                            // having: finding the line and then having to find the
                            // time by hand is the whole problem it solves.
                            if (snapshot.durationMillis > 0 && line.timeMs >= 0) {
                                onSeekTo((line.timeMs.toFloat() / snapshot.durationMillis).coerceIn(0f, 1f))
                            }
                        },
                    )
                }
            }
        }

        LyricsControls(
            offsetMs = offsetMs,
            blurInactive = blurInactive,
            onOffsetChange = onOffsetChange,
            onToggleBlur = onToggleBlur,
            hasLyrics = lines.isNotEmpty(),
            wordSynced = result?.lines?.any { it.isWordSynced } == true,
            sourceLabel = result?.source?.label,
        )
    }
}

/**
 * Which line is playing.
 *
 * Written as a scan rather than a binary search because the list is ordered but a
 * user can seek anywhere: the last line whose start is at or before the position
 * is correct for any position, including one before the first line.
 */
private fun activeLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
    var index = -1
    for (i in lines.indices) {
        if (lines[i].timeMs <= positionMs) index = i else break
    }
    return index
}

/**
 * One line of lyrics.
 *
 * The blur is applied to lines that are neither active nor past, which is what
 * makes the current line readable at a glance without dimming the rest so far
 * that the song's shape is lost.
 */
@Composable
private fun LyricRow(
    line: LyricLine,
    isActive: Boolean,
    isPast: Boolean,
    positionMs: Long,
    blurInactive: Boolean,
    onClick: () -> Unit,
) {
    val color by animateColorAsState(
        targetValue = when {
            isActive -> FluentTheme.colors.text.accent.primary
            isPast -> FluentTheme.colors.text.text.secondary
            else -> FluentTheme.colors.text.text.tertiary
        },
        animationSpec = tween(durationMillis = 220),
        label = "lyricColor",
    )

    val alpha = when {
        isActive -> 1f
        isPast -> 0.75f
        else -> 0.55f
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(Color.Transparent)
            .clickable(enabled = line.timeMs >= 0, onClick = onClick)
            .then(
                if (blurInactive && !isActive) {
                    Modifier.blur(1.2.dp)
                } else {
                    Modifier
                },
            ),
    ) {
        if (line.isWordSynced && isActive) {
            // A word-synced line is drawn as one centred flow of words, each
            // tinted by its own progress, so the highlight travels through the
            // line rather than snapping word to word.
            WordSyncedLine(line = line, positionMs = positionMs, baseColor = color)
        } else {
            Text(
                text = line.text.ifBlank { " " },
                style = if (isActive) FluentTheme.typography.bodyLarge else FluentTheme.typography.body,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = color.copy(alpha = alpha),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Transparent)
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * A word-synced line.
 *
 * Compose has no per-word rich text that also animates, so the line is drawn as a
 * `Row` of word composables. The consequence is that a very long line wraps at
 * word boundaries rather than filling the last line's width - acceptable for
 * lyrics, whose lines are short by construction.
 */
@Composable
private fun WordSyncedLine(line: LyricLine, positionMs: Long, baseColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        line.words.forEach { word ->
            val progress = word.progressAt(positionMs)
            Text(
                text = word.text,
                style = FluentTheme.typography.bodyLarge,
                fontWeight = if (progress >= 1f) FontWeight.Bold else FontWeight.SemiBold,
                color = baseColor.copy(alpha = 0.45f + progress * 0.55f),
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

/** The offset, blur and translation controls under the pane. */
@Composable
private fun LyricsControls(
    offsetMs: Int,
    blurInactive: Boolean,
    onOffsetChange: (Int) -> Unit,
    onToggleBlur: (Boolean) -> Unit,
    hasLyrics: Boolean,
    wordSynced: Boolean,
    sourceLabel: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(FluentTheme.colors.background.layer.default)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SliderRow(
                    title = "Timing offset",
                    value = offsetMs.toFloat(),
                    onValueChange = { onOffsetChange(it.toInt()) },
                    valueLabel = if (offsetMs == 0) "0 ms" else "${if (offsetMs > 0) "+" else ""}$offsetMs ms",
                    range = -5000f..5000f,
                    steps = 99,
                    detail = "Positive shows a line later; negative shows it earlier.",
                )
            }
            Column(modifier = Modifier.width(240.dp)) {
                ToggleRow(
                    title = "Blur inactive lines",
                    checked = blurInactive,
                    onCheckedChange = onToggleBlur,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                FluentGlyphs.LyricsQuote,
                contentDescription = null,
                tint = FluentTheme.colors.text.text.tertiary,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text(
                text = when {
                    !hasLyrics -> "No provider returned lyrics for this track."
                    wordSynced -> "Word-synced lyrics from ${sourceLabel ?: "an unknown provider"}."
                    else -> "Line-synced lyrics from ${sourceLabel ?: "an unknown provider"}. " +
                        "No provider had word timings for this track."
                },
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.text.text.tertiary,
            )
        }
    }
}
