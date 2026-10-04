// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - local library.
//
// NAME
//     LibraryScreen.kt - the audio files already on this machine.
//
// DESCRIPTION
//     The desktop half of the Android app's library. Folders are added through the
//     OS folder picker rather than typed, because a mistyped path is
//     indistinguishable from an empty folder: both show nothing, and the user has
//     no way to tell which mistake they made.
//
//     Folders and songs are two separate lists rather than one tree. A tree would
//     be more faithful to the filesystem, but the app plays a flat queue and the
//     scanner already flattens the folders, so a tree would be a hierarchy the
//     playback model cannot use.
//
// RESPONSIBILITIES
//     - Add, list and remove the folders being watched.
//     - Show the flattened song list and play from it.
//     - Report scan progress, since a first scan of a large folder takes time.
//
// DEPENDENCIES
//     - [LibraryFolder] and [Track] as plain data; this screen scans nothing.
//     - [TrackRow] for the song rows.
//
// INTEGRATION NOTES
//     - [onAddFolder] is passed in rather than handled here so the folder picker -
//       a Swing/OS call - stays in the platform layer and this screen stays a pure
//       function of state.
//     - The folder list has a fixed height cap rather than `weight`. Songs are the
//       reason the user is here; the folder list is configuration, and letting it
//       grow would push the songs off screen once a few folders are added.
//     - Removing a folder removes it from the watch list only. The files on disk
//       are never touched, which is why this needs no confirmation dialog.

package com.ihimanshunayak.freemusic.desktop.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.data.library.LibraryFolder
import com.ihimanshunayak.freemusic.desktop.model.Track
import com.ihimanshunayak.freemusic.desktop.ui.component.EmptyState
import com.ihimanshunayak.freemusic.desktop.ui.component.FluentGlyphs
import com.ihimanshunayak.freemusic.desktop.ui.component.SectionHeader
import com.ihimanshunayak.freemusic.desktop.ui.component.TrackRow
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Icon
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text

/**
 * A user's own audio files.
 *
 * A flat list rather than a virtualised tree: the scanner already flattens the
 * folders into one queue and playback is linear, so a hierarchy here would be a
 * structure nothing downstream could use.
 */
@Composable
fun LibraryScreen(
    folders: List<LibraryFolder>,
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
        SectionHeader(
            title = "Your library",
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (scanning) {
                    ProgressRing(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(18.dp),
                        width = 2.dp,
                    )
                }
                SubtleButton(onClick = onRefresh, disabled = scanning) {
                    Icon(FluentGlyphs.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Rescan", modifier = Modifier.padding(start = 8.dp))
                }
                AccentButton(onClick = onAddFolder, modifier = Modifier.padding(start = 8.dp)) {
                    Icon(FluentGlyphs.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Add folder", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        Row(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A live dot while scanning, so the spinner in the header is not the
            // only sign the app is busy - the header scrolls out of view.
            if (scanning) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(FluentTheme.colors.text.accent.primary),
                )
                Text(
                    text = "Scanning folders...",
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            } else {
                Text(
                    text = when {
                        tracks.isEmpty() && folders.isEmpty() -> "No folders added yet"
                        tracks.isEmpty() -> "${folders.size} folder(s), no audio files found"
                        else -> "${tracks.size} tracks in ${folders.size} folder(s)"
                    },
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                )
            }
        }

        if (folders.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .heightIn(max = 180.dp),
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
                            FluentGlyphs.FolderOpen,
                            contentDescription = null,
                            tint = FluentTheme.colors.text.text.tertiary,
                            modifier = Modifier.size(18.dp),
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        ) {
                            Text(
                                text = folder.name,
                                style = FluentTheme.typography.body,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${folder.trackCount} tracks - ${folder.path}",
                                style = FluentTheme.typography.caption,
                                color = FluentTheme.colors.text.text.secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        SubtleButton(
                            onClick = { onRemoveFolder(folder.path) },
                            iconOnly = true,
                        ) {
                            Icon(
                                FluentGlyphs.Delete,
                                contentDescription = "Stop watching ${folder.name}",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }

        if (tracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = FluentGlyphs.Library,
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
