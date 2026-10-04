// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - downloads screen.
//
// NAME
//     DownloadsScreen.kt - the offline download queue and its history.
//
// DESCRIPTION
//     A download queue has two audiences at once: the item being transferred
//     right now, which needs a progress bar and a cancel button, and the hundred
//     finished items behind it, which need to be searchable and removable. This
//     screen shows both in one list, ordered so the active work is always at the
//     top, because that is the only part a user ever acts on.
//
//     The quality selector sits above the list rather than per item: a user
//     downloading an album wants one decision, not twelve.
//
// RESPONSIBILITIES
//     - Show every queued, active and finished download with its own progress.
//     - Cancel, retry and dismiss individual items, and the whole failed set.
//     - Choose the quality for the next batch.
//     - Report the aggregate summary and the destination folder.
//
// DEPENDENCIES
//     - [DownloadManager], [DownloadItem], [formatBytes] from the download layer.
//     - [SettingsWidgets] for the surrounding chrome.
//
// INTEGRATION NOTES
//     - The manager owns all state; this screen is a rendering of its `items`
//       flow plus calls back into it. Nothing is cached here, so a download that
//       advances while the screen is closed is correct the moment it reopens.
//     - Downloads survive a restart because the queue is persisted, so the list
//       can legitimately contain an item from last week. Ordering by state first
//       and time second is what keeps that from burying today's work.

package com.ihimanshunayak.freemusic.desktop.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.data.DownloadQuality
import com.ihimanshunayak.freemusic.desktop.data.Settings
import com.ihimanshunayak.freemusic.desktop.data.download.DownloadItem
import com.ihimanshunayak.freemusic.desktop.data.download.DownloadState
import com.ihimanshunayak.freemusic.desktop.ui.component.EmptyState
import com.ihimanshunayak.freemusic.desktop.ui.component.EnumRow
import com.ihimanshunayak.freemusic.desktop.ui.component.FluentGlyphs
import com.ihimanshunayak.freemusic.desktop.ui.component.KeyValueRow
import com.ihimanshunayak.freemusic.desktop.ui.component.SectionHeader
import com.ihimanshunayak.freemusic.desktop.ui.component.Thumbnail
import com.ihimanshunayak.freemusic.desktop.ui.component.VerticalGap
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Icon
import io.github.composefluent.component.ListItem
import io.github.composefluent.component.ProgressBar
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text

/**
 * The download queue.
 *
 * @param summary the manager's aggregate line; passed in rather than computed
 *   here so the screen and the status bar cannot disagree about the totals.
 */
@Composable
fun DownloadsScreen(
    items: List<DownloadItem>,
    summary: String,
    settings: Settings,
    onSetQuality: (DownloadQuality) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onRetryAllFailed: () -> Unit,
    onClearFinished: () -> Unit,
    onBrowseFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showFinished by remember { mutableStateOf(true) }

    val failed = items.count { it.state == DownloadState.FAILED }
    val finished = items.count { it.state.isTerminal }

    // Active work first, then newest first within each group: the item a user is
    // waiting on is the one they came here to look at.
    val ordered = remember(items, showFinished) {
        items
            .filter { showFinished || !it.state.isTerminal }
            .sortedWith(
                compareByDescending<DownloadItem> { it.state.isActive }
                    .thenByDescending { it.queuedAtMs },
            )
    }

    Column(modifier = modifier.fillMaxSize()) {
        SectionHeader("Downloads") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (failed > 0) {
                    SubtleButton(onClick = onRetryAllFailed) {
                        Icon(FluentGlyphs.Retry, contentDescription = null)
                        Text("Retry $failed", modifier = Modifier.padding(start = 6.dp))
                    }
                }
                if (finished > 0) {
                    SubtleButton(onClick = onClearFinished) {
                        Icon(FluentGlyphs.Broom, contentDescription = null)
                        Text("Clear finished", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            KeyValueRow("Saved", summary.ifBlank { "Nothing downloaded yet." })
            KeyValueRow("Folder", settings.effectiveDownloadsDirectory)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SubtleButton(onClick = onBrowseFolder) {
                    Icon(FluentGlyphs.Folder, contentDescription = null)
                    Text("Change folder", modifier = Modifier.padding(start = 6.dp))
                }
                SubtleButton(onClick = { showFinished = !showFinished }) {
                    Icon(
                        if (showFinished) FluentGlyphs.Filter else FluentGlyphs.FilterOff,
                        contentDescription = null,
                    )
                    Text(
                        text = if (showFinished) "Showing everything" else "Hiding finished",
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            VerticalGap(8.dp)
            EnumRow(
                title = "Quality for new downloads",
                options = DownloadQuality.entries,
                selected = settings.downloadQuality,
                labelOf = { it.label },
                onSelect = onSetQuality,
                detail = "Original keeps the source container; the others re-encode.",
            )
            VerticalGap(12.dp)
        }

        if (ordered.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = FluentGlyphs.Download,
                    title = "Nothing downloading",
                    detail = "Use the download action on any track, album or playlist " +
                        "and it will appear here.",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 24.dp,
                    end = 24.dp,
                    bottom = 16.dp,
                ),
            ) {
                items(ordered, key = { it.id }) { item ->
                    DownloadRow(
                        item = item,
                        onCancel = { onCancel(item.id) },
                        onRetry = { onRetry(item.id) },
                        onDismiss = { onDismiss(item.id) },
                    )
                }
            }
        }
    }
}

/**
 * One download.
 *
 * The progress bar is drawn for active states only. A finished item showing a
 * full bar is noise, and a failed one showing a partial bar implies the transfer
 * is resumable when it is not.
 */
@Composable
private fun DownloadRow(
    item: DownloadItem,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        ListItem(
            selected = false,
            onSelectedChanged = { },
            text = {
                Column {
                    Text(
                        text = item.title.ifBlank { "Untitled" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.detail,
                        style = FluentTheme.typography.caption,
                        color = when (item.state) {
                            DownloadState.FAILED -> FluentTheme.colors.system.critical
                            DownloadState.DONE -> FluentTheme.colors.text.text.tertiary
                            else -> FluentTheme.colors.text.text.secondary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            icon = { Thumbnail(url = item.thumbnailUrl, modifier = Modifier.width(40.dp)) },
            trailing = {
                Text(
                    text = item.state.label,
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.tertiary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                when {
                    item.state.isActive -> SubtleButton(onClick = onCancel) {
                        Icon(FluentGlyphs.Cancel, contentDescription = "Cancel")
                    }
                    item.state == DownloadState.FAILED -> SubtleButton(onClick = onRetry) {
                        Icon(FluentGlyphs.Retry, contentDescription = "Retry")
                    }
                    else -> SubtleButton(onClick = onDismiss) {
                        Icon(FluentGlyphs.Delete, contentDescription = "Remove from list")
                    }
                }
            },
        )
        if (item.state.isActive) {
            ProgressBar(
                progress = item.progress.coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            )
        }
    }
}
