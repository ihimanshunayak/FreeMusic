// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - listening statistics screen.
//
// NAME
//     StatisticsScreen.kt - listening history, rankings and the activity chart.
//
// DESCRIPTION
//     Two questions get asked of a listening history: what have I been playing
//     lately, and what have I played most. They need different windows over the
//     same records, so the period selector drives a single summary rather than
//     each panel asking its own question of the data.
//
//     The activity chart is drawn by hand. A bar per day across a configurable
//     window is a handful of rectangles, and pulling in a charting library for
//     it would add more surface than the feature is worth.
//
// RESPONSIBILITIES
//     - Offer the five replay windows.
//     - Show the totals for the selected window.
//     - Rank songs, artists and albums within it.
//     - Chart plays per day.
//     - Show the raw recent plays, and clear the history.
//
// DEPENDENCIES
//     - [ListeningStats], [ReplaySummary], [ReplayPeriod], [PlayRecord].
//     - [SettingsWidgets] and [Components] for the surrounding chrome.
//
// INTEGRATION NOTES
//     - Ranking is done in the container, not here: the summary needs artwork
//       URLs that only the music repository can resolve, and doing it here would
//       put a network call behind a composition.
//     - Clearing is destructive and irreversible, so it goes through a
//       confirmation rather than firing on the first click.

package com.ihimanshunayak.freemusic.desktop.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.data.stats.PlayRecord
import com.ihimanshunayak.freemusic.desktop.data.stats.RankedEntry
import com.ihimanshunayak.freemusic.desktop.data.stats.ReplayPeriod
import com.ihimanshunayak.freemusic.desktop.data.stats.ReplaySummary
import com.ihimanshunayak.freemusic.desktop.ui.component.EmptyState
import com.ihimanshunayak.freemusic.desktop.ui.component.FluentGlyphs
import com.ihimanshunayak.freemusic.desktop.ui.component.KeyValueRow
import com.ihimanshunayak.freemusic.desktop.ui.component.SectionHeader
import com.ihimanshunayak.freemusic.desktop.ui.component.SettingsGroup
import com.ihimanshunayak.freemusic.desktop.ui.component.VerticalGap
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.Icon
import io.github.composefluent.component.ListItem
import io.github.composefluent.component.SelectorBar
import io.github.composefluent.component.SelectorBarItem
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text

/**
 * The statistics screen.
 *
 * @param recent the newest plays, newest first, already trimmed by the container.
 */
@Composable
fun StatisticsScreen(
    summary: ReplaySummary,
    recent: List<PlayRecord>,
    period: ReplayPeriod,
    onPeriodChange: (ReplayPeriod) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        SectionHeader("Listening") {
            SubtleButton(onClick = { confirmClear = true }) {
                Icon(FluentGlyphs.Delete, contentDescription = null)
                Text("Clear history", modifier = Modifier.padding(start = 6.dp))
            }
        }

        SelectorBar(modifier = Modifier.padding(horizontal = 24.dp)) {
            ReplayPeriod.entries.forEach { entry ->
                SelectorBarItem(
                    selected = entry == period,
                    onSelectedChange = { selected -> if (selected) onPeriodChange(entry) },
                    text = { Text(entry.label) },
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 16.dp),
        ) {
            item {
                SettingsGroup(title = "Totals", detail = period.label) {
                    KeyValueRow("Plays", summary.totalPlays.toString())
                    KeyValueRow("Time listened", formatHours(summary.totalSeconds))
                    KeyValueRow("Average per day", "${"%.1f".format(summary.averagePerDay)} plays")
                    KeyValueRow(
                        "Distinct",
                        "${summary.distinctSongs} songs, ${summary.distinctArtists} artists, " +
                            "${summary.distinctAlbums} albums",
                    )
                }
                VerticalGap(16.dp)
            }

            if (summary.dailyPlays.isNotEmpty()) {
                item {
                    SettingsGroup(
                        title = "Activity",
                        detail = "${summary.dailyPlays.size} days, busiest " +
                            "${summary.dailyPlays.maxOf { it.second }} plays",
                    ) {
                        ActivityChart(summary.dailyPlays.map { it.second })
                    }
                    VerticalGap(16.dp)
                }
            }

            if (summary.totalPlays == 0) {
                item {
                    EmptyState(
                        icon = FluentGlyphs.History,
                        title = "No listening history yet",
                        detail = "A play is recorded once a track has been listened to for " +
                            "half its length, or four minutes - whichever comes first.",
                    )
                }
            }

            if (summary.topSongs.isNotEmpty()) {
                item { GroupHeading("Top songs") }
                items(summary.topSongs, key = { "song-${it.trackId ?: it.name}" }) { entry ->
                    RankedRow(entry = entry, rank = summary.topSongs.indexOf(entry) + 1)
                }
                item { VerticalGap(16.dp) }
            }

            if (summary.topArtists.isNotEmpty()) {
                item { GroupHeading("Top artists") }
                items(summary.topArtists, key = { "artist-${it.name}" }) { entry ->
                    RankedRow(entry = entry, rank = summary.topArtists.indexOf(entry) + 1)
                }
                item { VerticalGap(16.dp) }
            }

            if (summary.topAlbums.isNotEmpty()) {
                item { GroupHeading("Top albums") }
                items(summary.topAlbums, key = { "album-${it.name}" }) { entry ->
                    RankedRow(entry = entry, rank = summary.topAlbums.indexOf(entry) + 1)
                }
                item { VerticalGap(16.dp) }
            }

            if (recent.isNotEmpty()) {
                item { GroupHeading("Recent plays") }
                items(recent.take(50), key = { "${it.trackId}-${it.playedAtMs}" }) { record ->
                    ListItem(
                        selected = false,
                        onSelectedChanged = { },
                        text = {
                            Column {
                                Text(
                                    text = record.title.ifBlank { "Untitled" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = listOfNotNull(record.artist, record.album)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" - "),
                                    style = FluentTheme.typography.caption,
                                    color = FluentTheme.colors.text.text.tertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(FluentGlyphs.History, contentDescription = null) },
                        trailing = {
                            Text(
                                text = "${record.date} - ${formatSeconds(record.listenedSeconds)}",
                                style = FluentTheme.typography.caption,
                                color = FluentTheme.colors.text.text.tertiary,
                            )
                        },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        ContentDialog(
            title = "Clear listening history?",
            visible = true,
            content = {
                Text(
                    "Every recorded play is deleted. This cannot be undone, and it also " +
                        "resets the rankings on this screen.",
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

/** A group heading inside the scrolling list. */
@Composable
private fun GroupHeading(title: String) {
    Text(
        text = title,
        style = FluentTheme.typography.subtitle,
        color = FluentTheme.colors.text.text.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

/** One ranked entry: rank, artwork, name, plays and time. */
@Composable
private fun RankedRow(entry: RankedEntry, rank: Int) {
    ListItem(
        selected = false,
        onSelectedChanged = { },
        text = {
            Column {
                Text(text = entry.name.ifBlank { "Unknown" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = listOfNotNull(entry.artist, entry.album)
                        .filter { it.isNotBlank() && it != entry.name }
                        .joinToString(" - "),
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
        icon = {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(FluentTheme.colors.subtleFill.secondary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = rank.toString(),
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                )
            }
        },
        trailing = {
            Text(
                text = "${entry.plays} plays - ${formatSeconds(entry.seconds.toInt())}",
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.text.text.tertiary,
            )
        },
    )
}

/**
 * Plays per day.
 *
 * Drawn as equal-width columns with a shared baseline rather than a proportional
 * area: the question this answers is "which days did I listen", and a column
 * chart answers it at a glance while an area chart invites reading a trend that
 * a few weeks of data cannot support.
 */
@Composable
private fun ActivityChart(values: List<Int>) {
    if (values.isEmpty()) return
    val accent = FluentTheme.colors.fillAccent.default
    val track = FluentTheme.colors.stroke.divider.default
    val peak = values.max().coerceAtLeast(1)

    Column {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(FluentTheme.shapes.control),
        ) {
            val baseline = size.height - 1f
            drawLine(track, Offset(0f, baseline), Offset(size.width, baseline), 2f)

            // A gap of one pixel between columns at any width keeps adjacent days
            // legible as separate days without having to cap the column count.
            val slot = size.width / values.size
            val barWidth = (slot - 1f).coerceAtLeast(1f)
            values.forEachIndexed { index, value ->
                if (value <= 0) return@forEachIndexed
                val height = (value.toFloat() / peak) * (size.height - 8f)
                drawRect(
                    color = accent,
                    topLeft = Offset(index * slot, baseline - height),
                    size = Size(barWidth, height),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "oldest",
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.text.text.tertiary,
            )
            Text(
                text = "peak $peak plays/day",
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.text.text.tertiary,
            )
            Text(
                text = "newest",
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.text.text.tertiary,
            )
        }
    }
}

/** `3 h 12 m`, or `12 m` under an hour. */
private fun formatHours(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        seconds <= 0 -> "None yet"
        hours <= 0 -> "$minutes min"
        minutes <= 0 -> "$hours h"
        else -> "$hours h $minutes min"
    }
}

/** `4:07`, or `2 h 14 m` for a long listen. */
private fun formatSeconds(seconds: Int): String {
    if (seconds <= 0) return "-"
    val minutes = seconds / 60
    val remainder = seconds % 60
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} m" else "$minutes:${remainder.toString().padStart(2, '0')}"
}
