// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - search.
//
// NAME
//     SearchScreen.kt - the search box, its suggestions, and the result list.
//
// DESCRIPTION
//     One text field, a floating suggestion panel, and a list of results. The
//     suggestions float over the results rather than pushing them down, because
//     the user is usually editing a query whose results they can already see, and
//     having the page jump on every keystroke is worse than a little overlap.
//
//     The box is focused on arrival. Search is the only screen in the app with a
//     single keyboard target, and requiring a click before typing is a tax paid
//     every single time the screen opens.
//
// RESPONSIBILITIES
//     - Debounced live suggestions, and a submitted search.
//     - Infinite scroll: "Load more" is reached by scrolling, not by a button.
//     - A row that plays when it has a video id and navigates when it does not.
//
// DEPENDENCIES
//     - [BrowseViewModel] for the query, the suggestions and the results.
//     - [SearchResultCard] for the rows.
//
// INTEGRATION NOTES
//     - [initialQuery] is the only cross-screen hand-off in the app: Home's "See
//       all" opens this screen with the shelf title already submitted.
//     - The suggestion panel is dismissed on submit rather than on blur, because a
//       blur can be caused by clicking a suggestion and closing the panel before
//       the click lands would swallow the click.
//     - Load-more is triggered by a sentinel item at the end of the list rather
//       than by the scroll position. A position test has to guess how tall the
//       viewport is; a sentinel simply asks to be composed, which happens exactly
//       when it would become visible.

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.model.SearchResult
import com.ihimanshunayak.freemusic.desktop.ui.component.EmptyState
import com.ihimanshunayak.freemusic.desktop.ui.component.ErrorState
import com.ihimanshunayak.freemusic.desktop.ui.component.FluentGlyphs
import com.ihimanshunayak.freemusic.desktop.ui.component.LoadingState
import com.ihimanshunayak.freemusic.desktop.ui.component.SearchResultCard
import com.ihimanshunayak.freemusic.desktop.ui.component.SectionHeader
import com.ihimanshunayak.freemusic.desktop.ui.state.BrowseViewModel
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Icon
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import io.github.composefluent.component.TextField
import io.github.composefluent.surface.Card

/**
 * Search with suggestions.
 *
 * [initialQuery] lets another screen open Search with the box already filled,
 * which is how Home's "See all" hands over a shelf title.
 */
@Composable
fun SearchScreen(
    viewModel: BrowseViewModel,
    onPlay: (SearchResult) -> Unit,
    onOpenResult: (SearchResult) -> Unit,
    initialQuery: String? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.search.collectAsState()
    val focusRequester = remember { FocusRequester() }

    // The box mirrors the view model rather than holding its own copy. The query
    // is state the view model owns - suggestions and results are derived from it -
    // so a second copy here would be a second source of truth.
    var field by remember { mutableStateOf(TextFieldValue(state.query)) }
    var suggestionsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank()) {
            field = TextFieldValue(initialQuery)
            viewModel.submit(initialQuery)
            suggestionsOpen = false
        }
    }

    // A query that arrives from elsewhere (a cleared box) has to be reflected in
    // the field, but only when it differs - assigning unconditionally would fight
    // the user's cursor while they type.
    LaunchedEffect(state.query) {
        if (state.query != field.text) field = TextFieldValue(state.query, field.selection)
    }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // The magnifier sits outside the field rather than inside it: Fluent's
            // TextField has no leading/trailing icon slots, and overlaying artwork
            // on top of it would collide with whatever content padding the control
            // chooses for the current theme and density.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    FluentGlyphs.Search,
                    contentDescription = null,
                    tint = FluentTheme.colors.text.text.tertiary,
                    modifier = Modifier.size(16.dp),
                )
                TextField(
                    value = field,
                    onValueChange = { updated ->
                        field = updated
                        viewModel.onQueryChange(updated.text)
                        suggestionsOpen = updated.text.isNotBlank()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                viewModel.submit()
                                suggestionsOpen = false
                                true
                            } else {
                                false
                            }
                        },
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = "Songs, artists, albums, playlists",
                            style = FluentTheme.typography.body,
                        )
                    },
                )
                if (field.text.isNotBlank()) {
                    SubtleButton(
                        onClick = {
                            field = TextFieldValue("")
                            viewModel.clearSearch()
                            suggestionsOpen = false
                            runCatching { focusRequester.requestFocus() }
                        },
                        modifier = Modifier.padding(start = 8.dp),
                        iconOnly = true,
                    ) {
                        Icon(FluentGlyphs.Close, contentDescription = "Clear search")
                    }
                }
            }

            if (suggestionsOpen && state.suggestions.isNotEmpty() && field.text.isNotBlank()) {
                SuggestionList(
                    suggestions = state.suggestions,
                    onPick = { suggestion ->
                        field = TextFieldValue(suggestion)
                        viewModel.onQueryChange(suggestion)
                        viewModel.submit(suggestion)
                        suggestionsOpen = false
                    },
                    modifier = Modifier
                        .padding(start = 24.dp, end = 24.dp, top = 60.dp)
                        .align(Alignment.TopStart),
                )
            }
        }

        when {
            state.loading -> LoadingState()
            state.error != null -> ErrorState(
                title = "Search failed",
                detail = state.error.orEmpty(),
                onRetry = { viewModel.submit() },
            )
            state.results.isNotEmpty() -> ResultList(
                results = state.results,
                hasMore = state.hasMore,
                loadingMore = state.loadingMore,
                onLoadMore = viewModel::loadMore,
                onPlay = onPlay,
                onOpen = onOpenResult,
            )
            state.query.isBlank() -> EmptyState(
                icon = FluentGlyphs.Search,
                title = "Search YouTube Music",
                detail = "Find a song, an artist or an album and it plays instantly.",
            )
            else -> EmptyState(
                icon = FluentGlyphs.FilterOff,
                title = "No results for \"${state.query}\"",
                detail = "Check the spelling, or try a shorter query.",
            )
        }
    }
}

/**
 * The floating suggestion panel.
 *
 * A [Card] rather than a plain column: the panel sits over results that are still
 * visible beneath it, and the card's surface is what makes it read as a layer
 * rather than as part of the list.
 */
@Composable
private fun SuggestionList(
    suggestions: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(suggestions, key = { it }) { suggestion ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(suggestion) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        FluentGlyphs.Search,
                        contentDescription = null,
                        tint = FluentTheme.colors.text.text.tertiary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = suggestion,
                        style = FluentTheme.typography.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
    }
}

/** Results as rows, with an endless-scroll sentinel at the end. */
@Composable
private fun ResultList(
    results: List<SearchResult>,
    hasMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    onPlay: (SearchResult) -> Unit,
    onOpen: (SearchResult) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "count") {
            SectionHeader(if (results.size == 1) "1 result" else "${results.size} results")
        }
        items(results, key = { it.videoId ?: it.browseId ?: it.playlistId ?: it.title }) { result ->
            SearchResultCard(
                result = result,
                onClick = { if (result.videoId != null) onPlay(result) else onOpen(result) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (hasMore) {
            item(key = "sentinel") {
                // Being composed *is* the trigger: this item only enters the list
                // when the viewport has reached the end, so no scroll maths and no
                // listener is needed.
                LaunchedEffect(loadingMore, results.size) {
                    if (!loadingMore) onLoadMore()
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (loadingMore) {
                        ProgressRing(modifier = Modifier.size(20.dp), width = 2.dp)
                    } else {
                        SubtleButton(onClick = onLoadMore) {
                            Text("Load more")
                        }
                    }
                }
            }
        }
    }
}
