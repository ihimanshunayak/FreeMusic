// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - Home screen.
//
// The anonymous YouTube Music feed as horizontal shelves. It is the screen a
// user lands on, so it does three things at once: proves the session works, shows
// something playable within a second of launch, and offers a way out when the
// network or the session is broken.

package com.ihimanshunayak.freemusic.desktop.ui.screen

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.model.SearchResult
import com.ihimanshunayak.freemusic.desktop.ui.component.MediaCard
import com.ihimanshunayak.freemusic.desktop.ui.component.SectionHeader
import com.ihimanshunayak.freemusic.desktop.ui.component.Thumbnail
import com.ihimanshunayak.freemusic.desktop.ui.component.iconForKind
import com.ihimanshunayak.freemusic.desktop.ui.state.BrowseViewModel

/**
 * The Home feed.
 *
 * [onOpenShelf] is how a shelf title navigates; it is not handled inside this
 * screen because the destination lives in the app's navigation state, not here.
 */
@Composable
fun HomeScreen(
    viewModel: BrowseViewModel,
    onPlay: (SearchResult) -> Unit,
    onOpenShelf: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.home.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.loading && state.shelves.isEmpty() -> LoadingState()
            state.error != null && state.shelves.isEmpty() -> HomeError(state.error!!) {
                viewModel.loadHome(force = true)
            }
            state.shelves.isEmpty() -> EmptyHome()
            else -> HomeShelves(state.shelves, onPlay, onOpenShelf)
        }
    }
}

@Composable
private fun HomeShelves(
    shelves: List<com.ihimanshunayak.freemusic.desktop.ui.state.HomeShelf>,
    onPlay: (SearchResult) -> Unit,
    onOpenShelf: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                text = "Good to see you",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = "Free Music - unlimited, free, and open source.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        shelves.forEach { shelf ->
            item(key = "header-${shelf.title}") {
                SectionHeader(title = shelf.title)
            }
            item(key = "row-${shelf.title}") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(shelf.items, key = { it.videoId ?: it.browseId ?: it.title }) { result ->
                        MediaCard(
                            title = result.title,
                            subtitle = result.subtitle,
                            thumbnailUrl = result.thumbnailUrl,
                            icon = iconForKind(result.kind),
                            onClick = {
                                // A row with a video id plays; anything else is a
                                // container the app cannot queue, so it navigates.
                                if (result.videoId != null) onPlay(result)
                                else onOpenShelf(result.title)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shown when a browse payload arrives but carries no shelves.
 *
 * This is not an error state: an empty feed is what YouTube returns for a
 * region with no chart data, and saying "nothing to show" is more honest than
 * offering a retry that will return the same empty page.
 */
@Composable
private fun EmptyHome() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.Home,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = "Nothing on Home right now",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "Use Search to find something to play.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** A failure that the user can act on, with the real reason underneath. */
@Composable
private fun HomeError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = "Could not reach YouTube Music",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 20.dp)) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Try again", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Centred spinner used by every screen while a first load is in flight. */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = "Loading",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** Shared skeleton row, used while a list is loading under an existing header. */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(null, Modifier.size(40.dp), icon = Icons.Outlined.Album)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(12.dp)
                    .padding(bottom = 4.dp),
            )
        }
    }
}
