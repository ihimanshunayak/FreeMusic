// Copyright (c) A|iens. All rights reserved.
// AliensStyle code-header — full structured block below.
//
// Name        : ExploreScreen.kt
// Version     : 1.1.0   (was 1.0.0; v1.20 replaces the grid with a deck)
// Author      : A'nil Cyborg
// Author URI  : https://github.com/ihimanshunayak
// Description : Explore page surface. v1.20 re-renders the page body as a
//               stacked-card deck (ExploreCardStack) instead of a
//               2-column grid of small mood/genre cards. The public
//               signature is unchanged so MainActivity keeps compiling,
//               and the rest of the app is untouched.
// Requires    : Compose foundation 1.7+, Material3 1.3+, FreeMusic
//               ExploreCardStack + ExploreCard.
// Responsibilities:
//   - Render the "Explore" header.
//   - On Success, host the stacked-card deck.
//   - On Loading, show a deck-shaped skeleton (3 stacked shells).
//   - On Error, show the existing MessageState.
//   - Reuse MoodGenrePlaylistsScreen (unchanged) for the tap-to-expand
//     navigation target.
// Dependencies:
//   - MoodGenreSection, MoodGenre, UiState (data.model)
//   - ExploreCardStack, MessageState, ShimmerBox, PAGE_GUTTER,
//     PullToRefresh (ui.components)
//   - R (string resources)
// Integration Notes:
//   - v1.19 used an inline MoodGenreGrid + SectionHeader inside this
//     file. v1.20 deletes both and delegates to ExploreCardStack.
//   - The companion MoodGenrePlaylistsScreen is exported unchanged
//     from this file because MainActivity imports it from here.

package com.ihimanshunayak.freemusic.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.R
import com.ihimanshunayak.freemusic.data.model.HomeShelf
import com.ihimanshunayak.freemusic.data.model.MoodGenre
import com.ihimanshunayak.freemusic.data.model.MoodGenreSection
import com.ihimanshunayak.freemusic.data.model.ShelfItem
import com.ihimanshunayak.freemusic.data.model.UiState
import com.ihimanshunayak.freemusic.ui.components.ExploreCardStack
import com.ihimanshunayak.freemusic.ui.components.MessageState
import com.ihimanshunayak.freemusic.ui.components.PAGE_GUTTER
import com.ihimanshunayak.freemusic.ui.components.PullToRefresh
import com.ihimanshunayak.freemusic.ui.components.ShimmerBox
import com.ihimanshunayak.freemusic.ui.components.feedSkeleton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    state: UiState<List<MoodGenreSection>>,
    listState: LazyListState,
    onCategoryClick: (MoodGenre) -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                text = stringResource(R.string.explore),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            )
            when (state) {
                UiState.Loading -> ExploreDeckSkeleton()
                is UiState.Error -> MessageState(
                    state.message,
                    actionLabel = stringResource(R.string.retry),
                    onAction = onRetry,
                )
                is UiState.Success -> ExploreCardStack(
                    sections = state.data,
                    onCardOpen = onCategoryClick,
                    onPlayClick = onCategoryClick,
                    contentPadding = contentPadding,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun ExploreDeckSkeleton() {
    // Three stacked shells mimicking the deck shape so the page doesn't
    // pop when data arrives.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cardHeight = maxHeight * 0.62f
        val peek = 56.dp
        val pageWidth = maxWidth
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Top,
        ) {
            Spacer(Modifier.height(8.dp))
            repeat(3) { i ->
                Box(
                    Modifier
                        .padding(
                            horizontal = pageWidth * 0.07f,
                            vertical = peek * if (i == 0) 0f else 1f,
                        )
                        .fillMaxWidth()
                        .height(cardHeight)
                        .clip(RoundedCornerShape(22.dp)),
                ) {
                    ShimmerBox(Modifier.fillMaxSize(), RoundedCornerShape(22.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodGenrePlaylistsScreen(
    title: String,
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onItemClick: (ShelfItem) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text = title,
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            )
        }
        when (state) {
            UiState.Loading -> feedSkeleton()
            is UiState.Error -> item {
                MessageState(state.message, actionLabel = stringResource(R.string.retry), onAction = onRetry)
            }
            is UiState.Success -> items(state.data, key = { it.title }) { shelf ->
                Shelf(shelf = shelf, onItemClick = onItemClick)
            }
        }
    }
}
