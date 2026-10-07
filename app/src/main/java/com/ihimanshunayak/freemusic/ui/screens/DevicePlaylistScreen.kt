/*
 * Copyright (c) Aliens. All rights reserved.
 */

package com.ihimanshunayak.freemusic.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ihimanshunayak.freemusic.R
import com.ihimanshunayak.freemusic.data.model.Song
import com.ihimanshunayak.freemusic.data.playlist.PlaylistStore
import com.ihimanshunayak.freemusic.ui.components.MessageState
import com.ihimanshunayak.freemusic.ui.components.PAGE_GUTTER
import com.ihimanshunayak.freemusic.ui.components.PillTextField
import com.ihimanshunayak.freemusic.ui.components.SongRow
import com.ihimanshunayak.freemusic.ui.haptics.Haptic
import com.ihimanshunayak.freemusic.ui.haptics.rememberHaptics
import com.ihimanshunayak.freemusic.ui.icons.FreeMusicIcons
import com.ihimanshunayak.freemusic.ui.player.MeshGradientBackground
import com.ihimanshunayak.freemusic.ui.player.MeshPalette
import com.ihimanshunayak.freemusic.ui.theme.ArtworkPalette
import com.ihimanshunayak.freemusic.ui.theme.rememberArtworkPalette

/**
 * One of the listener's own playlists, opened.
 *
 * ## Why this is not [DetailScreen]
 *
 * It reads the same store a release page would only borrow from, and every part
 * of it is editable — the name, the running order, the membership. A release
 * page is a read-only view of something YouTube holds, with a header built
 * around artwork, an About blurb, artist stats and a save-to-library button;
 * none of which exist here, since the playlist is already on the device and it
 * is nobody else's. What is left is a list of tracks and the handful of things
 * that can be done to them, which is a smaller page than the release one and
 * would be mostly conditional branches inside it.
 *
 * ## Why it reads the store, not the page
 *
 * The page's own `songs` is a snapshot taken when it was opened — enough to
 * paint the first frame, and stale the moment a row is removed or dragged.
 * Reordering off a snapshot would animate backwards: the store would publish
 * the new order, the page's own copy would still hold the old one, and the row
 * would appear to jump back before it jumped forward. Reading
 * [PlaylistStore.playlists] directly means there is one answer, and it is the
 * one on disk.
 *
 * A playlist deleted from inside itself — the menu can do that — leaves the id
 * here unmatched, and this says so rather than showing an empty list over a
 * name that no longer exists; the page is popped on the next back press.
 */
@Composable
fun DevicePlaylistScreen(
    playlistId: String,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val playlists by PlaylistStore.playlists.collectAsStateWithLifecycle()
    val playlist = playlists.firstOrNull { it.id == playlistId }
    val songs = remember(playlist) { playlist?.asSongs().orEmpty() }
    val palette = rememberArtworkPalette(songs.firstOrNull()?.thumbnailUrl)
    val haptics = rememberHaptics()

    var renaming by remember(playlistId) { mutableStateOf(false) }
    var confirmingDelete by remember(playlistId) { mutableStateOf(false) }

    if (renaming) {
        RenamePlaylistDialog(
            initial = playlist?.name.orEmpty(),
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                onRename(name)
            },
        )
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.delete_playlist)) },
            text = { Text(stringResource(R.string.delete_playlist_confirmation, playlist?.name.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (playlist == null) {
        MessageState(
            message = stringResource(R.string.playlist_empty),
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "header") {
            PlaylistHeader(
                name = playlist.name,
                trackCount = playlist.size,
                palette = palette,
                onPlay = {
                    haptics.play(Haptic.Resume)
                    if (songs.isNotEmpty()) onSongClick(songs, 0)
                },
                onShuffle = { onShuffle(songs) },
                onRename = { renaming = true },
                onDelete = { confirmingDelete = true },
            )
        }
        if (songs.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.playlist_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.onBackgroundVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PAGE_GUTTER * 2, vertical = 40.dp),
                )
            }
        }
        itemsIndexed(songs, key = { _, song -> song.videoId }) { index, song ->
            val isCurrent = song.videoId == currentSong?.videoId
            SongRow(
                song = song,
                onClick = { onSongClick(songs, index) },
                onLongPress = { onSongLongPress(song) },
                onMore = { onSongLongPress(song) },
                onSwipeToQueue = { onSongSwipe(song) },
                trackNumber = index + 1,
                isCurrent = isCurrent,
                isPlaying = isCurrent && isPlaying,
                activeTint = palette.accent,
                subtitleColor = palette.onBackgroundVariant,
                downloadedTint = null,
            )
        }
    }
}

/**
 * The page's top: name, count, and the four things that can be done to the
 * whole list.
 *
 * Deliberately without cover art. There is none to show — the playlist is a
 * list of other people's records and has no sleeve of its own — and borrowing
 * the first track's would make the page look like it belonged to that track,
 * which is exactly the confusion a "Play all" button is meant to avoid. The
 * mesh behind it is built from the first track's colours instead, which says
 * what the playlist sounds like without claiming to be its artwork.
 */
@Composable
private fun PlaylistHeader(
    name: String,
    trackCount: Int,
    palette: ArtworkPalette,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER)
            .height(180.dp)
            .clip(RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        MeshGradientBackground(
            palette = remember(palette) {
                MeshPalette(listOf(palette.accent, palette.wash, palette.elevated))
            },
            trackKey = "device-playlist",
            continuous = true,
            blurRadius = 32.dp,
        )
        Icon(
            imageVector = FreeMusicIcons.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(56.dp),
        )
    }
    Spacer(Modifier.height(18.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.headlineSmall,
                color = palette.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(R.plurals.track_count_plural, trackCount, trackCount),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
            )
        }
        PlaylistOverflow(onRename = onRename, onDelete = onDelete)
    }
    Spacer(Modifier.height(14.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistPill(
            icon = FreeMusicIcons.Play,
            label = stringResource(R.string.play),
            filled = true,
            onClick = onPlay,
        )
        PlaylistCircle(
            icon = FreeMusicIcons.Shuffle,
            contentDescription = stringResource(R.string.shuffle),
            onClick = onShuffle,
        )
    }
    Spacer(Modifier.height(18.dp))
}

/** The page's overflow: Rename and Delete, the two things it can do to itself. */
@Composable
private fun PlaylistOverflow(onRename: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Icon(
            imageVector = Icons.Rounded.MoreVert,
            contentDescription = stringResource(R.string.more),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { open = true }
                .padding(10.dp)
                .size(22.dp),
        )
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.rename_playlist)) },
                onClick = {
                    open = false
                    onRename()
                },
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.delete_playlist)) },
                onClick = {
                    open = false
                    onDelete()
                },
            )
        }
    }
}

/** The white Play pill the release pages lead with, sized for this header. */
@Composable
private fun PlaylistPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val haptics = rememberHaptics()
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(if (filled) Color.White else MaterialTheme.colorScheme.surfaceVariant)
            .clickable {
                haptics.play(Haptic.Resume)
                onClick()
            }
            .padding(horizontal = 26.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.Black,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = Color.Black,
        )
    }
}

/** A glass circle beside the Play pill. */
@Composable
private fun PlaylistCircle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val haptics = rememberHaptics()
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable {
                haptics.play(Haptic.Tap)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The one-field dialog that starts a device playlist.
 *
 * Opens focused, because the only thing it can do is take a name — the same
 * reason [NewPlaylistForm][PlaylistPickerSheet] requests focus on open. Not
 * shared with that form: this one has no back button, no visibility selector
 * and no track to carry, since a device playlist is private by definition and
 * there is no account to publish it to.
 */
@Composable
fun NewDevicePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_playlist)) },
        text = {
            PillTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.playlist_name),
                modifier = Modifier.focusRequester(focusRequester),
                keyboardActions = KeyboardActions(
                    onDone = { if (name.isNotBlank()) onCreate(name.trim()) },
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.create_playlist))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * The rename dialog, shared by the picker's create form and this page's menu.
 *
 * Prefilled and pre-selected rather than empty: a rename is nearly always an
 * edit of what is already there, and starting from a blank field would make the
 * common case retyping the whole name.
 */
@Composable
private fun RenamePlaylistDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_playlist)) },
        text = {
            PillTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.playlist_name),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
