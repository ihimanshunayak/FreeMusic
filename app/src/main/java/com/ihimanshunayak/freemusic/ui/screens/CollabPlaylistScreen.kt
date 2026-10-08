/*
 * Copyright (c) Aliens. All rights reserved.
 */

package com.ihimanshunayak.freemusic.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ihimanshunayak.freemusic.R
import com.ihimanshunayak.freemusic.data.collab.CollabPlaylistType
import com.ihimanshunayak.freemusic.data.collab.CollabPlaylists
import com.ihimanshunayak.freemusic.data.collab.CollabRole
import com.ihimanshunayak.freemusic.data.collab.CollabSnapshot
import com.ihimanshunayak.freemusic.data.collab.CollabTrack
import com.ihimanshunayak.freemusic.data.model.Song
import com.ihimanshunayak.freemusic.ui.components.MessageState
import com.ihimanshunayak.freemusic.ui.components.PAGE_GUTTER
import com.ihimanshunayak.freemusic.ui.components.SongRow
import com.ihimanshunayak.freemusic.ui.haptics.Haptic
import com.ihimanshunayak.freemusic.ui.haptics.rememberHaptics
import com.ihimanshunayak.freemusic.ui.icons.FreeMusicIcons
import com.ihimanshunayak.freemusic.ui.player.MeshGradientBackground
import com.ihimanshunayak.freemusic.ui.player.MeshPalette
import com.ihimanshunayak.freemusic.ui.theme.ArtworkPalette
import com.ihimanshunayak.freemusic.ui.theme.rememberArtworkPalette

/**
 * A shared playlist, opened.
 *
 * ## Why this is not [DetailScreen] and not [DevicePlaylistScreen]
 *
 * It is closer to the device one — every part of it can be edited, and it reads
 * a store rather than a page snapshot — but three things make it its own page:
 *
 * - **It is somebody else's server.** Every edit can be refused, because
 *   somebody else may have changed the list since this device last read it, and a
 *   page that can be refused has to be able to say so without discarding what the
 *   user typed.
 * - **It has members.** A device playlist has one owner and no concept of anyone
 *   else; this has a member list, an invitation flow, an owner who can remove
 *   people, and a role that decides which of those controls are drawn.
 * - **It can be a Blend.** A Blend is generated and read-only for members, so
 *   the header, the empty state and the whole track list differ.
 *
 * ## Revision-aware, deliberately
 *
 * The page never holds a revision of its own. Every mutation reads the current
 * revision from [CollabPlaylists] inside the repository, so a stale one cannot
 * be sent from here. That is what makes a conflict a rare, server-reported event
 * rather than something this screen has to predict.
 */
@Composable
fun CollabPlaylistScreen(
    playlistId: String,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val open by CollabPlaylists.open.collectAsStateWithLifecycle()
    val state by CollabPlaylists.state.collectAsStateWithLifecycle()
    val snapshot = open[playlistId]

    // Loaded on open rather than assumed present: the library list holds cards,
    // not snapshots, so arriving here from a cold start has nothing to draw yet.
    androidx.compose.runtime.LaunchedEffect(playlistId) {
        if (open[playlistId] == null) CollabPlaylists.open(playlistId)
    }

    val haptics = rememberHaptics()

    var inviting by remember(playlistId) { mutableStateOf(false) }
    var showingMembers by remember(playlistId) { mutableStateOf(false) }
    var confirmingLeave by remember(playlistId) { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<Pair<String, String>?>(null) }

    if (inviting) {
        InviteSheet(
            playlistId = playlistId,
            name = snapshot?.name.orEmpty(),
            onDismiss = { inviting = false },
        )
    }
    if (showingMembers && snapshot != null) {
        MembersDialog(
            snapshot = snapshot,
            onDismiss = { showingMembers = false },
            onRemove = { userId, name -> confirmRemove = userId to name },
        )
    }
    confirmRemove?.let { (userId, name) ->
        val owner = snapshot?.isOwner == true
        if (owner) {
            AlertDialog(
                onDismissRequest = { confirmRemove = null },
                title = { Text(stringResource(R.string.shared_playlist_members_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.shared_playlist_remove_member_confirmation,
                            name,
                            snapshot?.name.orEmpty(),
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmRemove = null
                        CollabPlaylists.scopeLaunch { CollabPlaylists.removeMember(playlistId, userId) }
                    }) {
                        Text(stringResource(R.string.shared_playlist_remove_member))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmRemove = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        } else {
            confirmRemove = null
        }
    }
    if (confirmingLeave) {
        AlertDialog(
            onDismissRequest = { confirmingLeave = false },
            title = { Text(stringResource(R.string.shared_playlist_leave)) },
            text = {
                Text(
                    stringResource(
                        R.string.shared_playlist_leave_confirmation,
                        snapshot?.name.orEmpty(),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingLeave = false
                    CollabPlaylists.scopeLaunch { CollabPlaylists.leave(playlistId) }
                    onBack()
                }) {
                    Text(stringResource(R.string.shared_playlist_leave))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLeave = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (snapshot == null) {
        // Distinguish "still loading" from "cannot reach it", because the two
        // need different words and only one of them is the user's to fix.
        val message = when {
            state.offline -> stringResource(R.string.shared_playlist_offline)
            state.error != null -> state.error
            else -> null
        }
        if (message != null) {
            MessageState(
                message = message,
                modifier = modifier.fillMaxSize(),
                actionLabel = stringResource(R.string.retry),
                onAction = { CollabPlaylists.openAsync(playlistId, force = true) },
            )
        } else {
            MessageState(
                message = stringResource(R.string.loading),
                modifier = modifier.fillMaxSize(),
            )
        }
        return
    }

    val palette = rememberArtworkPalette(snapshot.tracks.firstOrNull()?.thumbnailUrl)
    val songs = remember(snapshot) { snapshot.tracks.map { it.toSong() } }
    val isBlend = snapshot.playlistType == CollabPlaylistType.BLEND
    val editable = snapshot.isEditable

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "header") {
            CollabHeader(
                snapshot = snapshot,
                palette = palette,
                onPlay = {
                    haptics.play(Haptic.Resume)
                    if (songs.isNotEmpty()) onSongClick(songs, 0)
                },
                onShuffle = { onShuffle(songs) },
                onInvite = { inviting = true },
                onMembers = { showingMembers = true },
                onLeave = { confirmingLeave = true },
            )
        }
        if (isBlend) {
            item(key = "blend-note") {
                Text(
                    text = stringResource(R.string.shared_playlist_blend_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onBackgroundVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PAGE_GUTTER, vertical = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        if (songs.isEmpty() && !isBlend) {
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

        itemsIndexed(
            items = snapshot.tracks,
            key = { _, track -> track.entryId.ifBlank { track.videoId + track.position } },
        ) { index, track ->
            val song = songs.getOrNull(index) ?: return@itemsIndexed
            val isCurrent = song.videoId == currentSong?.videoId
            // A Blend is read-only, so the long-press and swipe gestures that
            // would offer an edit are withheld rather than offered and refused.
            SongRow(
                song = song,
                onClick = { onSongClick(songs, index) },
                onLongPress = { if (editable) onSongLongPress(song) },
                onMore = { if (editable) onSongLongPress(song) },
                onSwipeToQueue = { onSongSwipe(song) },
                trackNumber = index + 1,
                isCurrent = isCurrent,
                isPlaying = isCurrent && isPlaying,
                activeTint = palette.accent,
                subtitleColor = palette.onBackgroundVariant,
                downloadedTint = null,
            )
        }

        if (state.error != null && snapshot != null) {
            item(key = "error") {
                Text(
                    text = state.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PAGE_GUTTER, vertical = 12.dp),
                )
            }
        }
    }
}

/**
 * The header: name, what it is, who is in it, and what can be done.
 *
 * The counts are the interesting part. A shared playlist's two numbers — how many
 * songs and how many people — are the two things somebody opening it wants to
 * know, and they are the two things a device playlist only has one of.
 */
@Composable
private fun CollabHeader(
    snapshot: CollabSnapshot,
    palette: ArtworkPalette,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onInvite: () -> Unit,
    onMembers: () -> Unit,
    onLeave: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

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
            trackKey = "collab-${snapshot.id}",
            continuous = true,
            blurRadius = 32.dp,
        )
        Icon(
            imageVector = if (snapshot.playlistType == CollabPlaylistType.BLEND) {
                FreeMusicIcons.Infinity
            } else {
                FreeMusicIcons.Queue
            },
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
                text = snapshot.name,
                style = MaterialTheme.typography.headlineSmall,
                color = palette.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.track_count_plural,
                        snapshot.tracks.size,
                        snapshot.tracks.size,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.onBackgroundVariant,
                )
                Text("·", style = MaterialTheme.typography.bodyMedium, color = palette.onBackgroundVariant)
                Text(
                    text = stringResource(R.string.shared_playlist_members, snapshot.members.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.onBackgroundVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onMembers() }
                        .padding(horizontal = 2.dp),
                )
            }
            if (snapshot.playlistType == CollabPlaylistType.BLEND) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.shared_playlist_blend),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.accent,
                )
            } else if (!snapshot.isOwner) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = snapshot.members.firstOrNull { it.role == CollabRole.OWNER }
                        ?.displayName
                        ?.takeIf { it.isNotBlank() }
                        ?.let { stringResource(R.string.shared_playlist_added_by, it) }
                        .orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.onBackgroundVariant,
                )
            }
        }

        Box {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.more),
                tint = palette.onBackgroundVariant,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { menuOpen = true }
                    .padding(10.dp)
                    .size(22.dp),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.shared_playlist_members_title)) },
                    onClick = {
                        menuOpen = false
                        onMembers()
                    },
                )
                // A Blend cannot be shared onward — the server only lets an owner
                // invite, and nobody owns a generated list — so the entry is
                // withheld rather than offered and then refused.
                if (snapshot.playlistType != CollabPlaylistType.BLEND && snapshot.isOwner) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shared_playlist_invite)) },
                        onClick = {
                            menuOpen = false
                            onInvite()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.shared_playlist_leave)) },
                    onClick = {
                        menuOpen = false
                        onLeave()
                    },
                )
            }
        }
    }

    Spacer(Modifier.height(14.dp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SharedPill(
            icon = FreeMusicIcons.Play,
            label = stringResource(R.string.play),
            filled = true,
            onClick = onPlay,
        )
        SharedCircle(
            icon = FreeMusicIcons.Shuffle,
            contentDescription = stringResource(R.string.shuffle),
            onClick = onShuffle,
        )
        if (snapshot.playlistType != CollabPlaylistType.BLEND && snapshot.isOwner) {
            SharedCircle(
                icon = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.shared_playlist_invite),
                onClick = onInvite,
            )
        }
    }

    if (snapshot.blend?.compatibility?.isNotEmpty() == true) {
        Spacer(Modifier.height(14.dp))
        CompatibilityRow(snapshot, palette)
    }

    Spacer(Modifier.height(18.dp))
}

/**
 * The Blends compatibility figure.
 *
 * Labelled "Music compatibility" and shown as a whole-number match, never as a
 * percentage with a decimal or a claim about the relationship. It is a similarity
 * between two listening profiles — a number this app computed, not a fact about
 * two people — and the wording is what keeps that honest.
 */
@Composable
private fun CompatibilityRow(snapshot: CollabSnapshot, palette: ArtworkPalette) {
    val score = snapshot.blend?.compatibility?.values?.average()?.takeIf { !it.isNaN() } ?: return
    val nameFor = { userId: String ->
        snapshot.members.firstOrNull { it.userId == userId }?.displayName?.takeIf { it.isNotBlank() }
    }
    val pair = snapshot.blend.compatibility.entries.firstOrNull()
    val otherName = pair?.key
        ?.split('|')
        ?.firstOrNull { nameFor(it) != null && it != snapshot.ownerId }
        ?.let(nameFor)
        ?: pair?.key?.split('|')?.firstOrNull()?.let(nameFor)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.elevated.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.shared_playlist_compatibility),
                style = MaterialTheme.typography.labelMedium,
                color = palette.onBackgroundVariant,
            )
            if (otherName != null) {
                Text(
                    text = otherName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.onBackground,
                )
            }
        }
        Text(
            text = stringResource(R.string.shared_playlist_compatibility_value, score.toInt()),
            style = MaterialTheme.typography.titleMedium,
            color = palette.accent,
        )
    }
}

/** The white Play pill, matching the release and device pages. */
@Composable
private fun SharedPill(
    icon: ImageVector,
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

/** A circle beside the Play pill. */
@Composable
private fun SharedCircle(
    icon: ImageVector,
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
 * The member list, and the owner's control over it.
 *
 * Shown as a dialog rather than a page: it is a short list that decides one
 * thing, and a page would put a back press between the reader and the playlist
 * they were looking at.
 */
@Composable
private fun MembersDialog(
    snapshot: CollabSnapshot,
    onDismiss: () -> Unit,
    onRemove: (userId: String, name: String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shared_playlist_members_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                snapshot.members.forEach { member ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = member.displayName.ifBlank { member.userId },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (member.role == CollabRole.OWNER) {
                                Text(
                                    text = stringResource(R.string.shared_playlist_owner),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // Only an owner may remove, and never themselves — leaving
                        // is a different action with a different consequence.
                        if (snapshot.isOwner && member.role != CollabRole.OWNER) {
                            TextButton(
                                onClick = { onRemove(member.userId, member.displayName) },
                            ) {
                                Text(stringResource(R.string.shared_playlist_remove_member))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done))
            }
        },
    )
}

/**
 * The invite sheet.
 *
 * Mints the link once, on open, and keeps it for the life of the sheet. A link
 * was already added to the server's outstanding set at that point, so re-minting
 * on a recomposition would accumulate links nobody has, working against the
 * server's own cap on how many may be outstanding.
 */
@Composable
private fun InviteSheet(
    playlistId: String,
    name: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var invite by remember(playlistId) { mutableStateOf<com.ihimanshunayak.freemusic.data.collab.CollabApi.CreatedInvite?>(null) }
    var failed by remember(playlistId) { mutableStateOf(false) }
    var copied by remember(playlistId) { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(playlistId) {
        val created = CollabPlaylists.invite(playlistId)
        invite = created
        failed = created == null
    }

    val link = invite?.deepLink ?: invite?.webLink

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shared_playlist_invite_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.shared_playlist_invite_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                when {
                    failed -> Text(
                        text = stringResource(R.string.shared_playlist_invite_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    link == null -> Text(
                        text = stringResource(R.string.loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Text(
                        text = link,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = link != null,
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, link)
                    }
                    context.startActivity(Intent.createChooser(send, null))
                },
            ) {
                Text(stringResource(R.string.shared_playlist_invite_share))
            }
        },
        dismissButton = {
            TextButton(
                enabled = link != null,
                onClick = {
                    runCatching {
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(
                            android.content.ClipData.newPlainText(name, link),
                        )
                    }
                    copied = true
                },
            ) {
                Text(stringResource(if (copied) R.string.shared_playlist_invite_copied else R.string.shared_playlist_invite_copy))
            }
        },
    )
}

/**
 * The one-field dialog that starts a shared playlist.
 *
 * Separate from [NewDevicePlaylistDialog] because the two produce different
 * things: this one creates a playlist on a server, which can fail for reasons the
 * user can act on — no server configured, the create limit reached, no network —
 * and so it owns a busy state and an error line that the device one has no use
 * for.
 */
@Composable
fun NewCollabPlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    busy: Boolean = false,
    error: String? = null,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.shared_playlist_new)) },
        text = {
            Column {
                com.ihimanshunayak.freemusic.ui.components.PillTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = stringResource(R.string.shared_playlist_name_hint),
                    enabled = !busy,
                )
                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank() && !busy,
            ) {
                Text(stringResource(if (busy) R.string.loading else R.string.shared_playlist_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * What an invitation link points at, before joining.
 *
 * Shown as a dialog over whatever the user was looking at, because arriving from
 * a shared link is a decision, not a destination: joining is a choice and the
 * playlist is the result of it.
 */
@Composable
fun JoinCollabPlaylistDialog(
    preview: com.ihimanshunayak.freemusic.data.collab.CollabInvitePreview,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onJoin: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.shared_playlist_join_title)) },
        text = {
            Column {
                Text(
                    text = if (preview.ownerName.isNotBlank()) {
                        stringResource(R.string.shared_playlist_join_body, preview.ownerName, preview.name)
                    } else {
                        preview.name
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = pluralStringResource(
                        R.plurals.track_count_plural,
                        preview.trackCount,
                        preview.trackCount,
                    ) + " · " + stringResource(R.string.shared_playlist_members, preview.memberCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!preview.joinable) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        // The server's own reason where it gave one, because it
                        // knows which of expired / used / full applies.
                        text = preview.reason.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.shared_playlist_invite_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onJoin, enabled = preview.joinable && !busy) {
                Text(stringResource(if (busy) R.string.loading else R.string.shared_playlist_join_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
