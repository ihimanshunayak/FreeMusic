// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - Search screen.
//
// A search box over YouTube Music with live suggestions. The suggestions list
// floats over the results rather than pushing them down, because the user is
// usually editing a query they can already see results for and having the page
// jump on every keystroke is worse than a little overlap.

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.model.SearchResult
import com.ihimanshunayak.freemusic.desktop.ui.component.EmptyState
import com.ihimanshunayak.freemusic.desktop.ui.component.SearchResultCard
import com.ihimanshunayak.freemusic.desktop.ui.state.BrowseViewModel

/**
 * Search with suggestions.
 *
 * [initialQuery] lets another screen (a "see all" link on Home) open Search with
 * the box already filled, which is the only cross-screen hand-off this app has.
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
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank()) viewModel.submit(initialQuery)
    }

    // The search box is the only keyboard target on this screen, so focusing it
    // on arrival saves a click every time the user opens Search.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        // Down-arrow jumps into the suggestion list; Enter searches.
                        if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                            event.key == Key.Enter
                        ) {
                            viewModel.submit()
                            focused = false
                            true
                        } else {
                            false
                        }
                    },
                placeholder = { Text("Songs, artists, albums, playlists") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotBlank()) {
                        IconButton(onClick = viewModel::clearSearch) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.submit() }),
            )

            if (focused && state.suggestions.isNotEmpty() && state.query.isNotBlank()) {
                SuggestionList(
                    suggestions = state.suggestions,
                    onPick = { suggestion ->
                        viewModel.onQueryChange(suggestion)
                        viewModel.submit(suggestion)
                        focused = false
                    },
                    modifier = Modifier
                        .padding(start = 24.dp, end = 24.dp, top = 68.dp)
                        .align(Alignment.TopStart),
                )
            }
        }

        when {
            state.loading -> LoadingState()
            state.error != null -> SearchError(state.error!!) { viewModel.submit() }
            state.results.isNotEmpty() -> ResultGrid(
                results = state.results,
                hasMore = state.hasMore,
                loadingMore = state.loadingMore,
                onLoadMore = viewModel::loadMore,
                onPlay = onPlay,
                onOpen = onOpenResult,
            )
            state.query.isBlank() -> EmptyState(
                icon = Icons.Default.Search,
                title = "Search YouTube Music",
                detail = "Find a song, an artist or an album and it plays instantly.",
            )
            else -> EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = "No results for \"${state.query}\"",
                detail = "Check the spelling, or try a shorter query.",
            )
        }
    }
}

/** The floating suggestion panel. */
@Composable
private fun SuggestionList(
    suggestions: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(vertical = 6.dp),
    ) {
        LazyColumn {
            items(suggestions, key = { it }) { suggestion ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(suggestion) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = suggestion,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
    }
}

/** Results as a responsive grid of cards, because rows alone look sparse on a wide window. */
@Composable
private fun ResultGrid(
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(results, key = { it.videoId ?: it.browseId ?: it.title }) { result ->
            SearchResultRow(
                result = result,
                onClick = { if (result.videoId != null) onPlay(result) else onOpen(result) },
            )
        }
        if (hasMore) {
            item {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (loadingMore) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(16.dp)
                                .size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        TextButton(onClick = onLoadMore, modifier = Modifier.padding(8.dp)) {
                            Text("Load more")
                        }
                    }
                }
            }
        }
    }
}

/**
 * A result row: artwork, title, and the raw subtitle YouTube supplied.
 *
 * The subtitle is shown verbatim rather than split into artist and album fields
 * because the service formats it differently per result kind ("Song - Artist",
 * "Album - Artist - 2024") and the app has no reliable way to tell them apart.
 */
@Composable
private fun SearchResultRow(result: SearchResult, onClick: () -> Unit) {
    SearchResultCard(
        result = result,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SearchError(message: String, onRetry: () -> Unit) {
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
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = "Search failed",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        TextButton(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) {
            Text("Try again")
        }
    }
}
