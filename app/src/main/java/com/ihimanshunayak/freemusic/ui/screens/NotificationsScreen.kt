// Copyright (c) A|iens. All rights reserved.
//
// Free Music — notifications screen
//
// Name:     NotificationsScreen.kt
// Version:  1.0
// Author:   Himanshu Nayak
// Requires: Kotlin 2.x, Compose, Coil 3
// Purpose:  The page behind the top bar's notification button.
//
// Why the list is composed on the device rather than fetched: YouTube Music's
// notification menu answers a signed-out session with an empty popup and
// carries no payload through this client, so a surface that waited on it would
// be a button that never has anything in it. What the app can honestly report
// instead is what it already knows — an update it has found, transfers it is
// running, and the newest releases it has fetched. Those are states, not
// events, which is why [NotificationFeed] can read them out without a server.
//
// The page draws rows in the order [NotificationFeed] hands them over and does
// not re-sort: what belongs at the top is a decision about meaning, and it is
// made in one place rather than split across a builder and a renderer.

package com.ihimanshunayak.freemusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Upgrade
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ihimanshunayak.freemusic.R
import com.ihimanshunayak.freemusic.data.model.CARD_ART_PX
import com.ihimanshunayak.freemusic.data.model.NotificationEntry
import com.ihimanshunayak.freemusic.data.model.NotificationKind
import com.ihimanshunayak.freemusic.data.model.artworkAt
import com.ihimanshunayak.freemusic.ui.components.MessageState
import com.ihimanshunayak.freemusic.ui.components.PAGE_GUTTER

/**
 * The notification list.
 *
 * [entries] being empty is a normal state rather than an error — it means
 * there is genuinely nothing to report — so it says so plainly instead of
 * offering a retry for a request that was never made.
 */
@Composable
fun NotificationsScreen(
    entries: List<NotificationEntry>,
    listState: LazyListState,
    onOpen: (NotificationEntry) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        if (entries.isEmpty()) {
            item(key = "notifications:empty") {
                MessageState(stringResource(R.string.notifications_empty))
            }
            return@LazyColumn
        }
        items(entries, key = { it.key }) { entry ->
            NotificationRow(entry = entry, onClick = { onOpen(entry) })
        }
    }
}

@Composable
private fun NotificationRow(entry: NotificationEntry, onClick: () -> Unit) {
    val openable = entry.browseId != null || entry.videoId != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (openable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = PAGE_GUTTER, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NotificationArtwork(entry)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.subtitle.isNotBlank()) {
                Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (entry.kind == NotificationKind.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            entry.progress?.let { fraction ->
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(50)),
                )
            }
        }
    }
}

/**
 * A row's leading square: the item's own cover when it has one, and a tinted
 * tile carrying the row's kind when it does not. A transfer in flight has no
 * cover to show — it has not finished being anything yet — so the fallback is
 * the normal case there rather than an error state.
 */
@Composable
private fun NotificationArtwork(entry: NotificationEntry) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (entry.thumbnailUrl != null) {
            AsyncImage(
                model = entry.thumbnailUrl.artworkAt(CARD_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = entry.kind.icon(),
                contentDescription = null,
                tint = if (entry.kind == NotificationKind.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

private fun NotificationKind.icon() = when (this) {
    NotificationKind.UPDATE -> Icons.Rounded.Upgrade
    NotificationKind.DOWNLOAD -> Icons.Rounded.Download
    NotificationKind.UPLOAD -> Icons.Rounded.CloudUpload
    NotificationKind.RELEASE -> Icons.Rounded.NewReleases
    NotificationKind.FAILED -> Icons.Rounded.ErrorOutline
}
