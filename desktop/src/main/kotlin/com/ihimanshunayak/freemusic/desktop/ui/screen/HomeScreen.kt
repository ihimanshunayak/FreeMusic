// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - the Home feed.
//
// NAME
//     HomeScreen.kt - what YouTube Music suggests, as browsable shelves.
//
// DESCRIPTION
//     Renders the browse feed as a stack of horizontal card rows, one per shelf.
//     The feed is the app's front door, so it greets and it explains itself: an
//     empty feed and a failed feed are different screens, because they need
//     different actions from the user. A failed feed keeps the last good payload
//     on screen while a refresh is in flight rather than flashing empty.
//
//     Refresh is manual rather than automatic. A feed that reloads itself while
//     the user is reading it moves the shelf under their cursor, and the payload
//     is large enough that polling it would be wasteful.
//
// RESPONSIBILITIES
//     - Render every shelf as a scrolling row of cards.
//     - Play a card that has a video id and search for one that does not.
//     - Refresh on demand, and say why when the feed could not be fetched.
//
// DEPENDENCIES
//     - [BrowseViewModel] for the payload; this screen owns no fetching of its own.
//     - [MediaCard], [SectionHeader] and the shared empty, loading and error states.
//
// INTEGRATION NOTES
//     - A shelf row is a `LazyRow` inside a `LazyColumn`, which is fine as long as
//       the inner list has a bounded height. The cards set their own height, so
//       the row does not need one.
//     - The card click branches on `videoId` rather than on [ResultKind]: YouTube
//       returns a song with no video id when it is only a catalogue entry, and a
//       browse id on a card that looks like a song, so the id is the honest test.
//     - [HomeShelf] carries no browse id, so "See all" hands the shelf title to the
//       search screen instead. That always resolves to something, whereas a dead
//       header would not.

package com.ihimanshunayak.freemusic.desktop.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.model.SearchResult
import com.ihimanshunayak.freemusic.desktop.ui.component.EmptyState
import com.ihimanshunayak.freemusic.desktop.ui.component.ErrorState
import com.ihimanshunayak.freemusic.desktop.ui.component.FluentGlyphs
import com.ihimanshunayak.freemusic.desktop.ui.component.LoadingState
import com.ihimanshunayak.freemusic.desktop.ui.component.MediaCard
import com.ihimanshunayak.freemusic.desktop.ui.component.SectionHeader
import com.ihimanshunayak.freemusic.desktop.ui.component.iconForKind
import com.ihimanshunayak.freemusic.desktop.ui.state.BrowseViewModel
import com.ihimanshunayak.freemusic.desktop.ui.state.HomeShelf
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text

/**
 * The Home feed.
 *
 * [onOpenShelf] is how a shelf header navigates; the destination lives in the
 * app's navigation state, so it is not handled here.
 */
@Composable
fun HomeScreen(
    viewModel: BrowseViewModel,
    onPlay: (SearchResult) -> Unit,
    onOpenShelf: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.home.collectAsState()

    /*
     * The feed loads itself when Home is first shown.
     *
     * `loadHome` is idempotent - it returns early while a load is in flight and
     * short-circuits when shelves are already present - so re-entering the
     * destination does not refetch. Without this the screen had nothing to draw
     * until the user pressed Refresh, because the only other callers are the two
     * buttons below.
     */
    LaunchedEffect(Unit) { viewModel.loadHome() }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.loading && state.shelves.isEmpty() -> LoadingState()
            state.error != null -> ErrorState(
                title = "Could not reach YouTube Music",
                detail = state.error.orEmpty(),
                onRetry = { viewModel.loadHome(force = true) },
            )
            state.shelves.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = FluentGlyphs.Home,
                    title = "Nothing on Home right now",
                    detail = "YouTube Music returned no shelves for your region. Search " +
                        "works as usual, and Explore browses by mood.",
                )
            }
            else -> HomeShelves(
                shelves = state.shelves,
                onPlay = onPlay,
                onOpenShelf = onOpenShelf,
                onRefresh = { viewModel.loadHome(force = true) },
            )
        }
    }
}

@Composable
private fun HomeShelves(
    shelves: List<HomeShelf>,
    onPlay: (SearchResult) -> Unit,
    onOpenShelf: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "greeting") {
            SectionHeader("Good to see you") {
                SubtleButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            }
            Text(
                text = "Free Music - unlimited, free, and open source.",
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.text.text.secondary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        shelves.forEach { shelf ->
            item(key = "header-${shelf.title}") {
                SectionHeader(shelf.title) {
                    // A shelf title is a usable query, so "See all" always lands
                    // somewhere. A dead header would be worse than no button.
                    SubtleButton(onClick = { onOpenShelf(shelf.title) }) {
                        Text("See all")
                    }
                }
            }
            item(key = "row-${shelf.title}") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    items(shelf.items, key = { it.stableKey() }) { result ->
                        MediaCard(
                            title = result.title,
                            subtitle = result.subtitle,
                            thumbnailUrl = result.thumbnailUrl,
                            icon = iconForKind(result.kind),
                            onClick = {
                                // A row with a video id plays; anything else is a
                                // container the app cannot queue, so it navigates.
                                if (result.videoId != null) onPlay(result) else onOpenShelf(result.title)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** A stable key for a card, since not every result has a video id. */
private fun SearchResult.stableKey(): String =
    videoId ?: browseId ?: playlistId ?: "$title-$subtitle"
