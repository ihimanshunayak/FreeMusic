// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - browse page (Explore).
//
// NAME
//     ExploreScreen.kt - an album, artist or playlist page, and the mood grid.
//
// DESCRIPTION
//     Explore is the app's browsing surface, and it doubles as the destination
//     for every card that is not a song: opening an album from Home, an artist
//     from Search and a genre from the grid all land here. They are the same
//     request to YouTube Music, so they are the same screen, with the grid shown
//     when nothing is open.
//
//     A page arrives as sections rather than as one list - an album carries its
//     track list and a "more by" shelf, an artist carries top songs and a set of
//     albums - so each section is drawn by shape: a section whose rows are all
//     songs becomes a track list, anything else becomes a row of cards.
//
// RESPONSIBILITIES
//     - Show the mood and genre grid when no page is open.
//     - Show a browsed page's sections, header and actions.
//     - Offer play and shuffle for the page's playable rows.
//     - Route non-playable rows into another browse page.
//
// DEPENDENCIES
//     - [BrowseState] and [HomeShelf] from the browse view model.
//     - [MediaCard], [TrackRow], [SectionHeader].
//
// INTEGRATION NOTES
//     - The mood tiles are YouTube Music's own browse ids rather than search
//       terms, so a tile asks for exactly the page the service means by that
//       heading instead of guessing at keywords and hoping.
//     - Shuffle on a page is expressed as a random starting row; the player owns
//       the shuffle mode, and picking the start here keeps the page from having
//       to reach into transport state.

package com.ihimanshunayak.freemusic.desktop.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.model.ResultKind
import com.ihimanshunayak.freemusic.desktop.model.SearchResult
import com.ihimanshunayak.freemusic.desktop.model.SourceKind
import com.ihimanshunayak.freemusic.desktop.model.Track
import com.ihimanshunayak.freemusic.desktop.ui.component.EmptyState
import com.ihimanshunayak.freemusic.desktop.ui.component.FluentGlyphs
import com.ihimanshunayak.freemusic.desktop.ui.component.MediaCard
import com.ihimanshunayak.freemusic.desktop.ui.component.SectionHeader
import com.ihimanshunayak.freemusic.desktop.ui.component.TrackRow
import com.ihimanshunayak.freemusic.desktop.ui.component.VerticalGap
import com.ihimanshunayak.freemusic.desktop.ui.component.iconForKind
import com.ihimanshunayak.freemusic.desktop.ui.state.BrowseState
import com.ihimanshunayak.freemusic.desktop.ui.state.HomeShelf
import com.ihimanshunayak.freemusic.desktop.ui.state.MoodGenre
import com.ihimanshunayak.freemusic.desktop.ui.state.MoodState
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Icon
import io.github.composefluent.component.InfoBar
import io.github.composefluent.component.InfoBarSeverity
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text

/**
 * The mood and genre grid.
 *
 * The tiles are whatever `FEmusic_moods_and_genres` returned, in the order it
 * returned them. They are not a table of ids written down here: the service
 * rejects a browse id that did not come out of its own response with a 400, so
 * the grid has to be fetched before it can be drawn at all.
 */
private const val MOOD_COLUMNS = 4

/**
 * Explore.
 *
 * @param onOpenPage opens a browse id; the back action calls [onClosePage].
 * @param onPlayRows queues a page's playable rows starting at an index.
 */
@Composable
fun ExploreScreen(
    state: BrowseState,
    moods: MoodState,
    currentTrackId: String?,
    isPlaying: Boolean,
    onOpenPage: (browseId: String, title: String, subtitle: String?, params: String?) -> Unit,
    onClosePage: () -> Unit,
    onLoadMoods: () -> Unit,
    onPlayRows: (List<SearchResult>, Int) -> Unit,
    onEnqueueRow: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    /*
     * The grid fetches itself the first time Explore is shown.
     *
     * It is deliberately not fetched at startup: the ids only matter once the
     * grid is on screen, and the request is a full browse call. `loadMoods` is
     * idempotent, so switching away and back does not refetch.
     */
    LaunchedEffect(Unit) { onLoadMoods() }

    Column(modifier = modifier.fillMaxSize()) {
        val hasPage = state.title.isNotBlank() || state.loading || state.error != null

        if (hasPage) {
            val playable = state.allItems.filter { it.kind == ResultKind.SONG }
            SectionHeader(state.title.ifBlank { "Opening" }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SubtleButton(onClick = onClosePage) {
                        Icon(FluentGlyphs.SortUp, contentDescription = null)
                        Text("Back", modifier = Modifier.padding(start = 6.dp))
                    }
                    if (playable.isNotEmpty()) {
                        SubtleButton(onClick = { onPlayRows(playable, 0) }) {
                            Icon(FluentGlyphs.Play, contentDescription = null)
                            Text(
                                text = "Play all (${playable.size})",
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        SubtleButton(onClick = {
                            onPlayRows(playable, playable.indices.random())
                        }) {
                            Icon(FluentGlyphs.Shuffle, contentDescription = null)
                            Text("Shuffle", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        } else {
            SectionHeader("Explore")
        }

        when {
            state.loading -> Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                ProgressRing()
            }

            state.error != null -> Column(modifier = Modifier.padding(24.dp)) {
                InfoBar(
                    title = { Text("Could not open this page") },
                    message = { Text(state.error) },
                    severity = InfoBarSeverity.Warning,
                )
            }

            !hasPage -> MoodGrid(
                state = moods,
                onOpen = { mood -> onOpenPage(mood.browseId, mood.title, null, mood.params) },
                modifier = Modifier.fillMaxSize(),
            )

            state.shelves.isEmpty() -> Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = FluentGlyphs.Explore,
                    title = "Nothing on this page",
                    detail = "The page was reachable but carried no rows. Try another.",
                )
            }

            else -> LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                state.shelves.forEachIndexed { index, shelf ->
                    item(key = "shelf-$index-${shelf.title}") {
                        Shelf(
                            shelf = shelf,
                            currentTrackId = currentTrackId,
                            isPlaying = isPlaying,
                            onPlayRows = onPlayRows,
                            onEnqueueRow = onEnqueueRow,
                            onOpenPage = onOpenPage,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The mood and genre grid.
 *
 * Drawn as rows inside one `LazyColumn` rather than as a lazy grid, because the
 * tiles have a label-driven width and the section count is small. Each section
 * YouTube returns keeps its own heading and its own row width, so a section with
 * three tiles does not stretch them across a row meant for four.
 */
@Composable
private fun MoodGrid(
    state: MoodState,
    onOpen: (MoodGenre) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.loading && state.sections.isEmpty() -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            ProgressRing()
        }

        state.error != null -> Column(modifier = Modifier.padding(24.dp)) {
            InfoBar(
                title = { Text("Could not load moods and genres") },
                message = { Text(state.error) },
                severity = InfoBarSeverity.Warning,
            )
        }

        state.sections.isEmpty() -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                icon = FluentGlyphs.Explore,
                title = "Nothing to explore right now",
                detail = "YouTube Music returned no moods or genres for your region. " +
                    "Search still works as usual.",
            )
        }

        else -> LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            state.sections.forEach { section ->
                item(key = "mood-header-${section.title}") {
                    SectionHeader(section.title)
                }
                items(section.items.chunked(MOOD_COLUMNS)) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { mood ->
                            MoodTile(
                                label = mood.title,
                                onClick = { onOpen(mood) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Blank filler keeps the final row's tiles the same width as
                        // every other row's, instead of stretching three tiles across
                        // four slots.
                        repeat(MOOD_COLUMNS - row.size) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                    VerticalGap(12.dp)
                }
            }
        }
    }
}

/** One mood tile. */
@Composable
private fun MoodTile(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(72.dp)
            .clip(FluentTheme.shapes.control)
            .background(FluentTheme.colors.background.card.default)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = label, style = FluentTheme.typography.subtitle)
    }
}

/**
 * One section of a browsed page.
 *
 * The shape test is what makes an album page put its track list first and its
 * recommendations below without anyone having to classify the sections: a
 * section made entirely of songs is the page's own music, and everything else is
 * a way to somewhere else.
 */
@Composable
private fun Shelf(
    shelf: HomeShelf,
    currentTrackId: String?,
    isPlaying: Boolean,
    onPlayRows: (List<SearchResult>, Int) -> Unit,
    onEnqueueRow: (SearchResult) -> Unit,
    onOpenPage: (browseId: String, title: String, subtitle: String?, params: String?) -> Unit,
) {
    val songs = shelf.items.filter { it.kind == ResultKind.SONG }
    val allSongs = songs.size == shelf.items.size && songs.isNotEmpty()

    Column(modifier = Modifier.fillMaxWidth()) {
        if (shelf.title.isNotBlank()) {
            SectionHeader(shelf.title)
        }

        if (allSongs) {
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                shelf.items.forEachIndexed { index, item ->
                    TrackRow(
                        track = item.toTrack(),
                        isCurrent = item.videoId != null && item.videoId == currentTrackId,
                        isPlaying = isPlaying,
                        onClick = { onPlayRows(songs, index) },
                        onEnqueue = { onEnqueueRow(item) },
                    )
                }
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(
                    items = shelf.items,
                    key = { index, item ->
                        item.browseId ?: item.videoId ?: "${shelf.title}-$index"
                    },
                ) { _, item ->
                    MediaCard(
                        title = item.title,
                        subtitle = item.subtitle.takeIf { it.isNotBlank() },
                        thumbnailUrl = item.thumbnailUrl,
                        onClick = {
                            val id = item.browseId
                            if (id.isNullOrBlank()) {
                                // A song with no browse id is still playable, so the
                                // click queues it rather than doing nothing.
                                if (item.kind == ResultKind.SONG) {
                                    onPlayRows(
                                        listOf(item),
                                        0,
                                    )
                                }
                            } else {
                                onOpenPage(id, item.title, item.subtitle, null)
                            }
                        },
                        icon = iconForKind(item.kind),
                        round = item.kind == ResultKind.ARTIST,
                    )
                }
            }
        }
        VerticalGap(16.dp)
    }
}

/** A search row as something queueable. */
private fun SearchResult.toTrack() = Track(
    id = videoId ?: title,
    title = title,
    artist = subtitle,
    durationSeconds = durationSeconds,
    thumbnailUrl = thumbnailUrl,
    source = SourceKind.YOUTUBE_MUSIC,
    videoId = videoId,
)
