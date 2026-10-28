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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ihimanshunayak.freemusic.R
import com.ihimanshunayak.freemusic.data.model.HomeShelf
import com.ihimanshunayak.freemusic.data.model.MoodGenre
import com.ihimanshunayak.freemusic.data.model.MoodGenreSection
import com.ihimanshunayak.freemusic.data.model.ShelfItem
import com.ihimanshunayak.freemusic.data.model.UiState
import com.ihimanshunayak.freemusic.data.model.artworkAt
import com.ihimanshunayak.freemusic.ui.components.ArtworkWash
import com.ihimanshunayak.freemusic.ui.components.ExploreCardStack
import com.ihimanshunayak.freemusic.ui.components.MessageState
import com.ihimanshunayak.freemusic.ui.components.PAGE_GUTTER
import com.ihimanshunayak.freemusic.ui.components.PullToRefresh
import com.ihimanshunayak.freemusic.ui.components.ShimmerBox
import com.ihimanshunayak.freemusic.ui.components.feedSkeleton
import com.ihimanshunayak.freemusic.ui.theme.ArtworkPalette
import com.ihimanshunayak.freemusic.ui.theme.rememberArtworkPalette
import com.ihimanshunayak.freemusic.ui.theme.rememberArtworkTopBandLuminance
import com.ihimanshunayak.freemusic.ui.theme.topBandScrimAlpha

/**
 * How tall the Explore page's header picture is.
 *
 * A slice of the page rather than the whole of the first screen: the
 * picture's job is to say which mood is in front, and that has been
 * said by the time the deck and the action row have gone by.
 */
private val EXPLORE_HEADER_HEIGHT = 260.dp

/**
 * Where the header picture's own pixels are read from.
 *
 * 720 px, the same size Home reads its header picture at, so the two
 * pages draw the same artwork from the same decoded source and the
 * scrim over them is computed from the same pixels.
 */
private const val EXPLORE_HEADER_ART_PX = 720

/**
 * The Explore page surface.
 *
 * v2.0.0 renders the page body as a swipe deck (ExploreCardStack) and
 * gives the page a header picture of its own, the way Home has one —
 * the picture follows the card in front, so the page changes as the
 * deck is swiped rather than sitting on a fixed one.
 */
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
    // The artwork of the card in front of the deck. The deck reports it
    // as the user swipes, and the header wears it.
    var headerArtwork by remember { mutableStateOf<String?>(null) }

    val palette = rememberArtworkPalette(headerArtwork)

    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            // The header picture, behind the page's own content and
            // fading into the page's colour at its foot so the join
            // between the picture and the page is not a hard edge.
            val activeArtwork = headerArtwork
            if (activeArtwork != null) {
                ExploreBackdrop(
                    palette = palette,
                    imageUrl = activeArtwork,
                    modifier = Modifier.matchParentSize(),
                )
            }

            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                Text(
                    text = stringResource(R.string.explore),
                    style = MaterialTheme.typography.displayLarge,
                    // Off the picture's own palette while one is up, so
                    // the heading is legible against the image rather
                    // than against a page colour the image is covering.
                    color = if (activeArtwork != null) {
                        palette.onBackground
                    } else {
                        MaterialTheme.colorScheme.onBackground
                    },
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
                        onShuffle = onCategoryClick,
                        onActiveArtworkChanged = { headerArtwork = it },
                        contentPadding = contentPadding,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * The picture behind the Explore page, and the wash under it.
 *
 * The same three layers the Home page's own backdrop is made of: the
 * artwork's own colours as a wash, the picture above them, and a scrim
 * across the picture's top band so the heading and the status-bar
 * glyphs have something to sit against.
 *
 * Unlike Home's, this one does not scroll with a list — the Explore
 * page is a deck, not a feed, and there is nothing to scroll — so it
 * is pinned and simply cross-fades as the deck moves from one mood to
 * the next.
 */
@Composable
private fun ExploreBackdrop(
    palette: ArtworkPalette,
    imageUrl: String,
    modifier: Modifier = Modifier,
) {
    // Read from the picture's upper band rather than the whole of it:
    // that is what the heading and the status bar actually lie over,
    // and a picture that is bright at the top and dark below is exactly
    // the case a whole-image average gets wrong. Home reads its own
    // header the same way, so the two pages shade their headings the
    // same.
    val topBandLuminance = rememberArtworkTopBandLuminance(
        imageUrl,
        artPx = EXPLORE_HEADER_ART_PX,
    )

    Box(modifier.clipToBounds()) {
        // The page's own colour, washed with the artwork's colours.
        // Home lays this down first and the picture over it; this does
        // the same, so the page below the picture belongs to the same
        // palette the picture does.
        ArtworkWash(palette = palette, modifier = Modifier.matchParentSize())

        // The picture, pinned to the top of the page. Unlike Home's,
        // this one does not scroll with a list — the Explore page is a
        // deck, not a feed, and there is nothing to scroll — so it
        // stays put and cross-fades as the deck moves from one mood to
        // the next.
        Box(
            Modifier
                .fillMaxWidth()
                .height(EXPLORE_HEADER_HEIGHT),
        ) {
            AsyncImage(
                model = imageUrl.artworkAt(EXPLORE_HEADER_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .background(palette.elevated),
            )

            // Settles the foot of the picture onto the colour the page
            // is made of, so the join between the picture and the wash
            // below it is already close before anything else is drawn
            // over it — the same join, and the same fix, as Home's.
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.35f to Color.Transparent,
                            1.00f to palette.wash.copy(alpha = 0.96f),
                        ),
                    ),
            )

            // The scrim is the page's own tint rather than a fixed
            // black: the page's palette already follows the theme, so
            // this darkens the picture in dark mode and lifts it in
            // light mode.
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to palette.background.copy(
                                alpha = topBandScrimAlpha(topBandLuminance),
                            ),
                            0.42f to palette.background.copy(
                                alpha = topBandScrimAlpha(topBandLuminance) * 0.35f,
                            ),
                            1.00f to Color.Transparent,
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun ExploreDeckSkeleton() {
    // The deck's own shape: one large card in front, two cards behind
    // it set back and scaled down, then the progress track and the
    // action-row shells — so the page doesn't pop when the deck's
    // data arrives.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cardHeight = 460.dp
        val pageWidth = maxWidth
        val cardWidth = pageWidth * 0.82f
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(4.dp))
            // Deck shell
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight),
                contentAlignment = Alignment.Center,
            ) {
                // The two cards behind, each a step further back.
                for (depth in 2 downTo 1) {
                    Box(
                        modifier = Modifier
                            .width(cardWidth)
                            .height(cardHeight)
                            .graphicsLayer {
                                val step = depth.toFloat()
                                scaleX = 1f - 0.055f * step
                                scaleY = 1f - 0.055f * step
                                translationY = 12.dp.toPx() * step
                            }
                            .clip(RoundedCornerShape(28.dp)),
                    ) {
                        ShimmerBox(Modifier.fillMaxSize(), RoundedCornerShape(28.dp))
                    }
                }
                // The card in front.
                Box(
                    modifier = Modifier
                        .width(cardWidth)
                        .height(cardHeight)
                        .clip(RoundedCornerShape(28.dp)),
                ) {
                    ShimmerBox(Modifier.fillMaxSize(), RoundedCornerShape(28.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            // Progress-track shell
            Box(
                modifier = Modifier
                    .width(180.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)),
            )
            Spacer(Modifier.height(16.dp))
            // Action-row shell
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(56.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ShimmerBox(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    shape = RoundedCornerShape(20.dp),
                )
                ShimmerBox(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    shape = RoundedCornerShape(20.dp),
                )
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
