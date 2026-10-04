// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - search and browse state.
//
// Screens are stateless; this is where the requests live. Keeping the loading
// flags and the error text in one holder - rather than in each composable - is
// what makes a failed search survive navigating away and back, and what stops a
// second keystroke from spawning a second in-flight request.

package com.ihimanshunayak.freemusic.desktop.ui.state

import com.ihimanshunayak.freemusic.desktop.data.innertube.InnertubeParser
import com.ihimanshunayak.freemusic.desktop.data.innertube.MusicRepository
import com.ihimanshunayak.freemusic.desktop.model.SearchResult
import com.ihimanshunayak.freemusic.desktop.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One shelf on Home.
 *
 * [browseId] and [params] are kept so a shelf can be opened for a "see all"
 * view without re-deriving them from the title.
 */
data class HomeShelf(
    val title: String,
    val items: List<SearchResult>,
    val browseId: String? = null,
    val params: String? = null,
)

/** Everything the Home screen draws, including the reason it is empty. */
data class HomeState(
    val loading: Boolean = false,
    val shelves: List<HomeShelf> = emptyList(),
    val error: String? = null,
)

/** Everything the Search screen draws. */
data class SearchState(
    val query: String = "",
    val loading: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val results: List<SearchResult> = emptyList(),
    val error: String? = null,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
)

/** Everything the Explore screen draws for one browsable page. */
data class BrowseState(
    val title: String = "",
    val subtitle: String? = null,
    val loading: Boolean = false,
    val shelves: List<HomeShelf> = emptyList(),
    val error: String? = null,
) {
    /** Every row on the page, flattened, so a "play all" has something to queue. */
    val allItems: List<SearchResult> get() = shelves.flatMap { it.items }
}

/**
 * Drives Home, Search and Explore.
 *
 * Debouncing lives here rather than in the search field because it is a property
 * of the *request*, not of the widget: the field stays responsive while the
 * suggestion call waits for the user to stop typing.
 */
class BrowseViewModel(
    private val music: MusicRepository,
    private val scope: CoroutineScope,
) {

    private val _home = MutableStateFlow(HomeState())
    val home: StateFlow<HomeState> = _home.asStateFlow()

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private val _browse = MutableStateFlow(BrowseState())
    val browse: StateFlow<BrowseState> = _browse.asStateFlow()

    private var suggestionJob: Job? = null
    private var searchJob: Job? = null
    private var browseJob: Job? = null
    private var continuation: String? = null

    /** Loads the anonymous home feed. Safe to call repeatedly; it replaces state. */
    fun loadHome(force: Boolean = false) {
        if (_home.value.loading) return
        if (!force && _home.value.shelves.isNotEmpty()) return
        scope.launch {
            _home.value = _home.value.copy(loading = true, error = null)
            runCatching { music.home() }
                .onSuccess { json ->
                    val shelves = InnertubeParser.parseShelves(json).map { shape ->
                        HomeShelf(title = shape.title, items = shape.items)
                    }
                    _home.value = HomeState(loading = false, shelves = shelves)
                    Log.i("home loaded with ${shelves.size} shelves", tag = "home")
                }
                .onFailure { e ->
                    Log.w("home failed: ${e.message}", tag = "home")
                    _home.value = HomeState(loading = false, error = describe(e))
                }
        }
    }

    /** Called on every keystroke; the request itself is debounced. */
    fun onQueryChange(query: String) {
        _search.value = _search.value.copy(query = query, error = null)
        suggestionJob?.cancel()
        if (query.isBlank()) {
            _search.value = _search.value.copy(suggestions = emptyList())
            return
        }
        suggestionJob = scope.launch {
            delay(SUGGESTION_DEBOUNCE_MS)
            runCatching { music.searchSuggestions(query) }
                .onSuccess { list ->
                    // A late reply for an abandoned query must not overwrite the
                    // suggestions for what is now in the box.
                    if (_search.value.query == query) {
                        _search.value = _search.value.copy(suggestions = list)
                    }
                }
                .onFailure { Log.d("suggestions failed: ${it.message}", tag = "search") }
        }
    }

    fun submit(query: String = _search.value.query) {
        if (query.isBlank()) return
        suggestionJob?.cancel()
        searchJob?.cancel()
        continuation = null
        searchJob = scope.launch {
            _search.value = _search.value.copy(
                query = query,
                loading = true,
                error = null,
                suggestions = emptyList(),
                results = emptyList(),
                hasMore = false,
                loadingMore = false,
            )
            runCatching { music.search(query) }
                .onSuccess { json ->
                    val results = InnertubeParser.parseSearchResults(json)
                    continuation = InnertubeParser.findContinuation(json)
                    _search.value = _search.value.copy(
                        loading = false,
                        results = results,
                        hasMore = continuation != null,
                    )
                    Log.i("search '$query' returned ${results.size} rows", tag = "search")
                }
                .onFailure { e ->
                    Log.w("search '$query' failed: ${e.message}", tag = "search")
                    _search.value = _search.value.copy(loading = false, error = describe(e))
                }
        }
    }

    /** Appends the next page. A no-op when there is nothing more to fetch. */
    fun loadMore() {
        val token = continuation ?: return
        if (_search.value.loadingMore || _search.value.loading) return
        scope.launch {
            _search.value = _search.value.copy(loadingMore = true)
            runCatching { music.searchNextPage(token) }
                .onSuccess { rows ->
                    _search.value = _search.value.copy(
                        loadingMore = false,
                        results = _search.value.results + rows,
                    )
                }
                .onFailure { e ->
                    Log.w("search continuation failed: ${e.message}", tag = "search")
                    _search.value = _search.value.copy(loadingMore = false, error = describe(e))
                }
        }
    }

    fun clearSearch() {
        suggestionJob?.cancel()
        searchJob?.cancel()
        continuation = null
        _search.value = SearchState()
    }

    /**
     * Opens an album, artist or playlist page.
     *
     * The title is passed in rather than read from the response, because YouTube
     * Music repeats the page header inside several differently-shaped renderers
     * and the row the user clicked already knows what it was called. Passing it
     * also lets the header render immediately, while the rows are still loading.
     */
    fun openPage(browseId: String, title: String, subtitle: String? = null, params: String? = null) {
        browseJob?.cancel()
        browseJob = scope.launch {
            _browse.value = BrowseState(
                title = title,
                subtitle = subtitle,
                loading = true,
            )
            runCatching { music.browse(browseId, params) }
                .onSuccess { json ->
                    val shelves = InnertubeParser.parseShelves(json).map { shape ->
                        HomeShelf(title = shape.title, items = shape.items)
                    }
                    _browse.value = BrowseState(
                        title = title,
                        subtitle = subtitle,
                        loading = false,
                        shelves = shelves,
                    )
                    Log.i(
                        "browse $browseId returned ${shelves.size} sections",
                        tag = "browse",
                    )
                }
                .onFailure { e ->
                    Log.w("browse $browseId failed: ${e.message}", tag = "browse")
                    _browse.value = BrowseState(
                        title = title,
                        subtitle = subtitle,
                        loading = false,
                        error = describe(e),
                    )
                }
        }
    }

    fun closePage() {
        browseJob?.cancel()
        _browse.value = BrowseState()
    }

    /**
     * Turns a row into something playable.
     *
     * A row for an album or artist has no video id, so it cannot be queued
     * directly; the caller is expected to check [SearchResult.videoId] first.
     */
    fun toTrack(result: SearchResult) = music.toTrack(result)

    private fun describe(e: Throwable): String = when (e) {
        is MusicRepository.MusicException -> e.message ?: "YouTube Music request failed"
        else -> e.message ?: "Something went wrong"
    }

    private companion object {
        /**
         * Long enough to stop typing from firing a request per character, short
         * enough that suggestions still feel immediate.
         */
        const val SUGGESTION_DEBOUNCE_MS = 220L
    }
}
