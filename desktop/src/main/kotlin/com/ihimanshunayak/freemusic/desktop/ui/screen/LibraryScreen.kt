// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - local library screen.
//
// The desktop half of the Android app's library: audio files already on this
// machine. Folders are added with the OS folder picker rather than typed by hand,
// because a mistyped path is indistinguishable from an empty folder.

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.model.Track
import com.ihimanshunayak.freemusic.desktop.ui.component.EmptyState
import com.ihimanshunayak.freemusic.desktop.ui.component.SectionHeader
import com.ihimanshunayak.freemusic.desktop.ui.component.TrackRow

/**
 * A user's own audio files.
 *
 * [onAddFolder] is passed in rather than handled here so the folder picker - a
 * Swing/OS call - stays in the platform layer and this screen stays a pure
 * function of state.
 */
@Composable
fun LibraryScreen(
    folders: List<com.ihimanshunayak.freemusic.desktop.data.library.LibraryFolder>,
    tracks: List<Track>,
    scanning: Boolean,
    onAddFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onRefresh: () -> Unit,
    onPlay: (Track) -> Unit,
    currentTrackId: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Your library", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = when {
                        scanning -> "Scanning..."
                        tracks.isEmpty() -> "No local files yet"
                        else -> "${tracks.size} tracks in ${folders.size} folder(s)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
                TextButton(onClick = onRefresh, enabled = !scanning) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Rescan", modifier = Modifier.padding(start = 6.dp))
                }
                Button(onClick = onAddFolder, modifier = Modifier.padding(start = 8.dp)) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Add folder", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }

        if (folders.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(folders, key = { it.path }) { folder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        ) {
                            Text(
                                text = folder.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${folder.trackCount} tracks - ${folder.path}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { onRemoveFolder(folder.path) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Remove folder",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }

        if (tracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.LibraryMusic,
                    title = "No songs in your library",
                    detail = "Add a folder containing MP3, FLAC, M4A, Opus, OGG or WAV files.",
                )
            }
        } else {
            SectionHeader(
                title = "Songs",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(tracks, key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        isCurrent = track.id == currentTrackId,
                        isPlaying = isPlaying,
                        onClick = { onPlay(track) },
                    )
                }
            }
        }
    }
}
