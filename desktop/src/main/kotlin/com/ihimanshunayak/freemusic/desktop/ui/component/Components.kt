// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - shared UI components.
//
// Small pieces reused by more than one screen. Everything here is stateless and
// takes plain values, so nothing in this file can start a request or change the
// queue - that keeps the screens the only place where behaviour lives.

package com.ihimanshunayak.freemusic.desktop.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.model.ResultKind
import com.ihimanshunayak.freemusic.desktop.model.SearchResult
import com.ihimanshunayak.freemusic.desktop.model.Track
import com.ihimanshunayak.freemusic.desktop.ui.RemoteImage

/**
 * A square thumbnail with a graceful placeholder.
 *
 * Artwork loads asynchronously and can fail - a deleted video returns 404 - so
 * the placeholder is the *default* and the image is painted over it, which means
 * a slow or broken URL shows a music note rather than an empty hole.
 */
@Composable
fun Thumbnail(
    url: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.MusicNote,
    cornerRadius: Int = 8,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(0.42f),
        )
        if (!url.isNullOrBlank()) {
            RemoteImage(url = url, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * A large, round avatar-style image for an artist or playlist hero.
 *
 * [RemoteImage] already falls back to nothing when it fails, so the icon below
 * becomes the visible content in that case.
 */
@Composable
fun CircleArtwork(url: String?, size: Int, icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size((size * 0.4).dp),
        )
        if (!url.isNullOrBlank()) {
            RemoteImage(url = url, modifier = Modifier.fillMaxSize())
        }
    }
}

/** One row of a track list, used by Home, Search and the local library. */
@Composable
fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onPlayNext: (() -> Unit)? = null,
    onEnqueue: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val background = when {
        isCurrent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(track.thumbnailUrl, Modifier.size(40.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onPlayNext != null) {
            IconButton(onClick = onPlayNext) {
                Icon(
                    Icons.Outlined.PlaylistPlay,
                    contentDescription = "Play next",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (onEnqueue != null) {
            IconButton(onClick = onEnqueue) {
                Icon(
                    Icons.Outlined.LibraryMusic,
                    contentDescription = "Add to queue",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        trailing?.invoke()
        if (!isCurrent) {
            Text(
                text = track.durationLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        } else if (isPlaying) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Now playing",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(18.dp),
            )
        }
    }
}

/** A card in a horizontal shelf. Used on Home and for search top-results. */
@Composable
fun MediaCard(
    title: String,
    subtitle: String?,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Album,
    width: Int = 160,
) {
    Column(
        modifier = modifier
            .width(width.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        Thumbnail(
            url = thumbnailUrl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            icon = icon,
            cornerRadius = 10,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The icon that matches a search row's kind, so mixed results read at a glance. */
fun iconForKind(kind: ResultKind): ImageVector = when (kind) {
    ResultKind.ARTIST -> Icons.Default.Person
    ResultKind.ALBUM -> Icons.Outlined.Album
    ResultKind.PLAYLIST -> Icons.Outlined.PlaylistPlay
    else -> Icons.Default.MusicNote
}

/** A section heading with an optional trailing action. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        trailing?.invoke()
    }
}

/** Shown when a list has no content yet, so a screen is never blank without cause. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** `3:07` / `1:02:44` from milliseconds, for the player bar. */
@Composable
fun rememberTimeLabel(millis: Long): String = remember(millis) { formatMillis(millis) }

fun formatMillis(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = millis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Search result card, used when a screen wants cards instead of rows. */
@Composable
fun SearchResultCard(result: SearchResult, onClick: () -> Unit, modifier: Modifier = Modifier) {
    MediaCard(
        title = result.title,
        subtitle = result.subtitle,
        thumbnailUrl = result.thumbnailUrl,
        onClick = onClick,
        modifier = modifier,
        icon = iconForKind(result.kind),
    )
}

/** A sidebar entries' shared look: icon + label, highlighted when selected. */
@Composable
fun NavigationItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    else Color.Transparent
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}
