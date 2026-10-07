package com.ihimanshunayak.freemusic.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Storage
import com.ihimanshunayak.freemusic.ui.icons.FreeMusicIcons
import com.ihimanshunayak.freemusic.R
import coil3.compose.AsyncImage
import com.ihimanshunayak.freemusic.data.model.CARD_ART_PX
import com.ihimanshunayak.freemusic.data.model.HEADER_ART_PX
import com.ihimanshunayak.freemusic.data.model.HomeChip
import com.ihimanshunayak.freemusic.data.model.HomeShelf
import com.ihimanshunayak.freemusic.data.model.ROW_ART_PX
import com.ihimanshunayak.freemusic.data.model.ShelfItem
import com.ihimanshunayak.freemusic.data.model.UiState
import kotlin.math.roundToInt
import java.util.Locale
import com.ihimanshunayak.freemusic.data.model.artworkAt
import com.ihimanshunayak.freemusic.data.settings.AppSettings
import com.ihimanshunayak.freemusic.data.settings.LibraryViewType
import com.ihimanshunayak.freemusic.ui.theme.ArtworkPalette
import com.ihimanshunayak.freemusic.ui.theme.rememberArtworkPalette
import com.ihimanshunayak.freemusic.ui.theme.rememberArtworkTopBandLuminance
import com.ihimanshunayak.freemusic.ui.theme.topBandScrimAlpha
import com.ihimanshunayak.freemusic.ui.components.ArtworkWash
import com.ihimanshunayak.freemusic.ui.components.HERO_CARD_RATIO
import com.ihimanshunayak.freemusic.ui.components.MessageState
import com.ihimanshunayak.freemusic.ui.components.PAGE_GUTTER
import com.ihimanshunayak.freemusic.ui.components.PullToRefresh
import com.ihimanshunayak.freemusic.ui.components.SHELF_CARD_WIDTH
import com.ihimanshunayak.freemusic.ui.components.SignInBanner
import com.ihimanshunayak.freemusic.ui.components.feedMoreSkeleton
import com.ihimanshunayak.freemusic.ui.components.feedSkeleton
import com.ihimanshunayak.freemusic.ui.components.glassContentColor
import com.ihimanshunayak.freemusic.ui.components.heroCardWidth
import com.ihimanshunayak.freemusic.ui.components.lightweightLiquidGlass
import com.ihimanshunayak.freemusic.ui.components.recentlyPlayedSkeleton
import com.ihimanshunayak.freemusic.ui.components.thumbnailBorder
import com.ihimanshunayak.freemusic.ui.components.trackColumnWidth
import com.ihimanshunayak.freemusic.ui.player.MeshGradientBackground
import com.ihimanshunayak.freemusic.ui.player.MeshPalette

private const val RECENTS_TITLE = "Recents"
private const val RECENT_TRACKS_PER_COLUMN = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    /** The tapped item and the recommendation shelf it came from. */
    onItemClick: (ShelfItem, String) -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    title: String,
    signedIn: Boolean = true,
    onSignIn: (() -> Unit)? = null,
    /**
     * Holding a card rather than tapping it. Every card on the feed answers,
     * whichever kind it is: the caller reads the item the same way it does for
     * a tap, so a track card opens the track menu and a card that points at a
     * collection opens the album / playlist one.
     */
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    // Explore doesn't page — only Home has a continuation worth following.
    onLoadMore: (() -> Unit)? = null,
    loadingMore: Boolean = false,
    recentlyPlayedLoading: Boolean = false,
    /**
     * The filter chips that rode the feed's own response, already ranked.
     *
     * Empty while chips are not in play at all (Explore reuses this screen and
     * passes none), which is also the state that hides the row — so the page
     * has one way of saying "there is no chip row" rather than two.
     */
    chips: List<HomeChip> = emptyList(),
    /** The chip currently filtering the page, or null for the unfiltered feed. */
    selectedChip: HomeChip? = null,
    onChipClick: ((HomeChip) -> Unit)? = null,
    /** The shelves behind [selectedChip]; only read while one is selected. */
    chipShelves: UiState<List<HomeShelf>> = UiState.Success(emptyList()),
    onChipRetry: (() -> Unit)? = null,
    /**
     * The handful of tiles at the head of the page — recently played tracks,
     * album covers and playlists, drawn as gradient tiles.
     *
     * A separate parameter from the shelves rather than another [HomeShelf]
     * because it is laid out differently: a fixed grid of large tiles, not a
     * scrolling row of cards. Empty hides the whole section.
     *
     * While this is non-empty the feed's own Recents shelf is left out, since
     * the tiles *are* those same tracks laid out a second way — see
     * [itemsIndexedShelves].
     */
    speedDial: List<ShelfItem> = emptyList(),
    onSpeedDialClick: ((ShelfItem) -> Unit)? = null,
    /**
     * The picture YouTube paints behind the whole page for the current filter.
     *
     * Belongs to the page rather than to any shelf, and changes when the filter
     * does — which is what makes picking a chip read as having moved somewhere
     * rather than as having only re-listed what was already there.
     *
     * Null is the ordinary case for pages the server has no picture for, and
     * leaves the page exactly as it was before this existed.
     */
    backgroundUrl: String? = null,
) {
    val recentsViewType by AppSettings.homeRecentsViewType.collectAsStateWithLifecycle()
    // One definition of "the recents layout flips" shared by the feed, the
    // chip-filtered feed and the hero slot above them — three rows that must
    // not each decide for themselves what the other layout is.
    val onRecentsViewTypeToggle: (LibraryViewType) -> Unit = { current ->
        AppSettings.setHomeRecentsViewType(
            if (current == LibraryViewType.LIST) LibraryViewType.GRID else LibraryViewType.LIST,
        )
    }

    // Read off the header picture rather than the theme, so the title, the
    // chips and the page's own wash are all one family of colours while a
    // picture is up — and so a burst of white artwork doesn't leave the page's
    // text sitting on white. A page with no picture falls back to the theme's
    // own palette, which is exactly what it drew before.
    val palette = rememberArtworkPalette(backgroundUrl, artPx = HEADER_ART_PX)

    Box(modifier.fillMaxSize()) {
        // Only while there is a picture. A wash with nothing over it would tint
        // every ordinary page with a colour that has no source on screen.
        if (backgroundUrl != null) {
            HomeBackdrop(
                palette = palette,
                imageUrl = backgroundUrl,
                listState = listState,
                contentPadding = contentPadding,
                modifier = Modifier.matchParentSize(),
            )
        }

        PullToRefresh(
            refreshing = refreshing,
            onRefresh = onRefresh,
            state = pullState,
            modifier = Modifier.matchParentSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
            ) {
            item {
                Text(
                    text = title,
                    style = MaterialTheme.typography.displayLarge,
                    // Off the header picture's own palette while one is up, so
                    // the heading is legible against the image rather than
                    // against a page colour the image is covering.
                    color = if (backgroundUrl != null) {
                        palette.onBackground
                    } else {
                        MaterialTheme.colorScheme.onBackground
                    },
                    modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                )
            }
            if (chips.isNotEmpty()) {
                item(key = "home_chips") {
                    HomeChipRow(
                        chips = chips,
                        selected = selectedChip,
                        onClick = { chip -> onChipClick?.invoke(chip) },
                        palette = palette,
                    )
                }
            }
            if (!signedIn && onSignIn != null) {
                item {
                    SignInBanner(onSignIn = onSignIn, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            // A selected chip replaces the page's contents without replacing
            // the page: the title bar and the chip row stay put, so the row
            // that turned the filter on is the same row that turns it off.
            if (selectedChip != null) {
                when (chipShelves) {
                    is UiState.Loading -> feedSkeleton()
                    is UiState.Error -> item(key = "chip_error") {
                        MessageState(
                            chipShelves.message,
                            actionLabel = stringResource(R.string.retry),
                            onAction = { onChipRetry?.invoke() },
                        )
                    }
                    is UiState.Success -> if (chipShelves.data.isEmpty()) {
                        item(key = "chip_empty") {
                            MessageState(stringResource(R.string.nothing_to_explore))
                        }
                    } else {
                        itemsIndexedShelves(
                            shelves = chipShelves.data,
                            onItemClick = onItemClick,
                            onItemLongPress = onItemLongPress,
                            firstIsHero = false,
                            recentsViewType = recentsViewType,
                            onRecentsViewTypeToggle = { onRecentsViewTypeToggle(recentsViewType) },
                            hideRecents = false,
                        )
                    }
                }
            } else {
                if (speedDial.isNotEmpty() && onSpeedDialClick != null) {
                    item(key = "speed_dial") {
                        SpeedDialSection(items = speedDial, onClick = onSpeedDialClick)
                    }
                }
                when (state) {
                    is UiState.Loading -> {
                        if (recentlyPlayedLoading) {
                            recentlyPlayedSkeleton(listLayout = recentsViewType == LibraryViewType.LIST)
                            // Recents owns the leading layout while its request is
                            // pending, so the feed behind it starts with ordinary
                            // shelf placeholders rather than another hero card.
                            feedSkeleton(firstIsHero = false)
                        } else {
                            feedSkeleton()
                        }
                    }
                    is UiState.Error -> item {
                        MessageState(state.message, actionLabel = stringResource(R.string.retry), onAction = onRetry)
                    }
                    is UiState.Success -> {
                        if (recentlyPlayedLoading) {
                            recentlyPlayedSkeleton(listLayout = recentsViewType == LibraryViewType.LIST)
                        }
                        // The loading skeleton already owns the hero slot. Until
                        // Recently Played lands, every real shelf must retain its
                        // compact-card layout instead of briefly becoming a hero.
                        itemsIndexedShelves(
                            shelves = state.data,
                            onItemClick = onItemClick,
                            onItemLongPress = onItemLongPress,
                            firstIsHero = !recentlyPlayedLoading,
                            recentsViewType = recentsViewType,
                            onRecentsViewTypeToggle = { onRecentsViewTypeToggle(recentsViewType) },
                            // The tiles above are these same tracks. Rendering
                            // both would put every recent play on the page twice,
                            // once as a tile and once as a row — so the tiles win
                            // and the shelf they were drawn from steps aside.
                            hideRecents = speedDial.isNotEmpty() && onSpeedDialClick != null,
                        )
                        if (loadingMore) feedMoreSkeleton()
                    }
                }
            }
            }
        }
    }

    if (onLoadMore != null && state is UiState.Success) {
        val loadMore by rememberUpdatedState(onLoadMore)
        // Re-checked on every layout change, rather than on the rising edge of
        // "the tail is in view". A page that appends only a shelf or two leaves
        // the list still near its end, so an edge-triggered effect would never
        // fire a second time: the feed dead-ended at the bottom with no
        // skeleton and no request in flight to explain it. Restarting on
        // [loadingMore] re-checks the moment a page settles, so the next one is
        // asked for while the tail is still on screen to show it loading.
        LaunchedEffect(listState, loadingMore) {
            snapshotFlow {
                val layout = listState.layoutInfo
                (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
            }.collect { (lastVisible, total) ->
                if (!loadingMore && total > 0 && lastVisible >= total - 3) loadMore()
            }
        }
    }
}

/**
 * The filter row above the feed.
 *
 * A row of pills rather than a segmented control: the labels are YouTube's own
 * vocabulary and can be renamed under us, so the row has to read as a list of
 * suggestions rather than a fixed set of modes. Tapping the active chip clears
 * the filter — the same gesture that turned it on turns it off.
 *
 * Pills rather than filled buttons on purpose, and glass rather than a flat
 * fill because that is what they are: a membrane over the picture the page is
 * wearing, not a control cut out of the page. The unfiltered page keeps a
 * picture of its own in most cases, so a solid chip would read as a hole in it.
 *
 * No backdrop sampling here — [lightweightLiquidGlass] rather than
 * [liquidGlass]. This row lives inside the app's shared Haze source, where a
 * backdrop read would come back empty and the chip would draw as a hole rather
 * than as glass over what is behind it. The tint, the rim and the highlight are
 * the parts of the glass that read at this size anyway.
 */
@Composable
private fun HomeChipRow(
    chips: List<HomeChip>,
    selected: HomeChip?,
    onClick: (HomeChip) -> Unit,
    palette: ArtworkPalette,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = PAGE_GUTTER, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 10.dp),
    ) {
        items(chips, key = { it.title + it.params }) { chip ->
            val isSelected = selected == chip
            val shape = RoundedCornerShape(50)
            Box(
                modifier = Modifier
                    .lightweightLiquidGlass(
                        shape = shape,
                        // Off the picture's own palette rather than the theme's
                        // surfaceVariant: the chip is drawn over an image, and a
                        // fill cut from the theme's greys reads as a sticker on
                        // top of it rather than as something laid over it.
                        fallbackColor = if (isSelected) {
                            palette.onBackground.copy(alpha = 0.86f)
                        } else {
                            palette.elevated.copy(alpha = 0.72f)
                        },
                    )
                    .clickable { onClick(chip) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = localizeShelfTitle(chip.title),
                    style = MaterialTheme.typography.titleSmall,
                    // The selected pill is filled with the picture's own
                    // foreground colour, so its label has to be the colour that
                    // reads *on that* — which is the inverse, and which is what
                    // the page has already been tinted against.
                    color = if (isSelected) {
                        palette.background
                    } else {
                        glassContentColor()
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The picture YouTube paints behind the Home page, and the wash under it.
 *
 * Three layers, and the order between them is the whole effect:
 *
 * - [ArtworkWash] beneath, holding the picture's own colours and easing out
 *   into the page tint on the way down. It has no image in it at all, so it
 *   costs the same on every API level and stays up under "reduce dynamic
 *   blur" — which is what keeps the page from going flat the moment the picture
 *   scrolls away.
 * - The picture itself above it, sized to the header and moved with the list.
 * - A scrim across the picture's own top band, so the heading, the chips and
 *   the status-bar glyphs have something to sit against. Its alpha comes from
 *   how bright that band is, so a pale picture gets a heavier one and a dark
 *   picture is left almost alone rather than being flattened for no reason.
 *
 * The picture is *not* re-blurred. A blurred copy of an image that is also on
 * screen still reads as that image — the faces in it come back through — and a
 * full-screen `RenderEffect` behind a scrolling feed would be paid for on every
 * frame of that scroll. So it is drawn sharp and simply moved, which is what
 * makes it a header rather than a backdrop.
 *
 * Movement mirrors the detail page's: the header is scrolled off the top by the
 * list's own offset while it is still on screen, and parks far out of the way
 * once it isn't, so a long feed does not carry its picture along with it.
 *
 * Placed with `offset {}` rather than translated in a `graphicsLayer`: Haze
 * records where an area is when it is *placed*, and a layer moves content at
 * draw time. Nothing here is a Haze source, but the app's own chrome is, and
 * the same rule is what keeps the two agreeing about where the header is.
 */
@Composable
private fun HomeBackdrop(
    palette: ArtworkPalette,
    imageUrl: String,
    listState: LazyListState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // The picture is as tall as the space the list gives its header — the top
    // inset plus the large title and the chip row — because anything taller is
    // hidden behind content that has already been laid over it, and anything
    // shorter leaves the page's own tint showing as a band under the picture.
    val headerHeight = with(density) {
        contentPadding.calculateTopPadding() + HEADER_CONTENT_HEIGHT
    }
    // Read from the picture's upper band rather than the whole of it: that is
    // what the heading and the status bar actually lie over, and a picture that
    // is bright at the top and dark below is exactly the case a whole-image
    // average gets wrong.
    val topBandLuminance = rememberArtworkTopBandLuminance(imageUrl, artPx = HEADER_ART_PX)

    Box(modifier.clipToBounds()) {
        ArtworkWash(palette = palette, modifier = Modifier.matchParentSize())

        Box(
            Modifier
                .fillMaxWidth()
                .height(headerHeight)
                .offset {
                    IntOffset(
                        x = 0,
                        y = listState.headerOffsetPx(headerHeight.toPx()).roundToInt(),
                    )
                },
        ) {
            AsyncImage(
                model = imageUrl.artworkAt(HEADER_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .background(palette.elevated),
            )

            // Settles the foot of the picture onto the colour the page is made
            // of, so the join between the picture and the wash below it is
            // already close before anything else is drawn over it — the same
            // join, and the same fix, as the detail page's header.
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.62f to Color.Transparent,
                            1.00f to palette.wash.copy(alpha = 0.92f),
                        ),
                    ),
            )

            // The scrim is the page's own tint rather than a fixed black: the
            // page's palette already follows the theme, so this darkens the
            // picture in dark mode and lifts it in light mode — the same
            // direction the bar's own glyphs are already heading, which is what
            // keeps one of the two from having to be wrong.
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

/**
 * How far down the page the header picture runs: a slice of the page rather
 * than the whole of the first screen.
 *
 * Deliberately less than the title, the chips and the first shelf together.
 * The picture's job is to say which filter the page is showing, and that has
 * been said by the time the chips have gone by; running it further would leave
 * the first shelf sitting on an image it has nothing to do with.
 */
private val HEADER_CONTENT_HEIGHT = 340.dp

/**
 * Where the header picture currently is, relative to the list's own scroll.
 *
 * Mirrors `DetailScreen.headerTop`, and for the same reason: while the header
 * is still on screen the amount it has been scrolled off the top is exactly the
 * offset the picture needs to stay pinned to it, and once the list has moved
 * past the header entirely there is nothing to agree with any more — so it
 * parks two header-heights up, far enough that no part of it comes back down.
 */
private fun LazyListState.headerOffsetPx(heightPx: Float): Float =
    if (firstVisibleItemIndex == 0) -firstVisibleItemScrollOffset.toFloat() else -heightPx * 2f

/** How many tiles the speed dial shows before the feed begins. */
private const val SPEED_DIAL_TILES = 6

/**
 * The row of shortcut tiles at the head of the page.
 *
 * Each tile is a mesh gradient rather than the item's own cover: the point of
 * the section is that it reads as six *places* at a glance, and six square
 * album covers at this size read as a seventh shelf instead. The gradient is
 * hashed off the item's identity, so a tile keeps its colour between launches
 * while the covers themselves arrive asynchronously — and a tile whose artwork
 * has not landed is still a finished thing rather than a hole.
 *
 * Two rows of three rather than one scrolling row: six fixed tiles fit a phone
 * width without scrolling, and a section you can take in at one glance is what
 * makes it a shortcut rather than another shelf to browse.
 */
@Composable
private fun SpeedDialSection(
    items: List<ShelfItem>,
    onClick: (ShelfItem) -> Unit,
) {
    val tiles = remember(items) { items.take(SPEED_DIAL_TILES) }
    Column(Modifier.padding(bottom = 26.dp)) {
        SectionHeader(stringResource(R.string.speed_dial))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val tileWidth = (maxWidth - PAGE_GUTTER * 2 - 12.dp) / 2
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = PAGE_GUTTER),
            ) {
                tiles.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { item ->
                            SpeedDialTile(
                                item = item,
                                onClick = { onClick(item) },
                                modifier = Modifier.width(tileWidth),
                            )
                        }
                        // Keeps a lone final tile at its own half-width instead
                        // of letting the row stretch it across the page.
                        if (row.size == 1) Spacer(Modifier.width(tileWidth))
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedDialTile(item: ShelfItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = speedDialColor(item.browseId ?: item.videoId ?: item.title)
    Box(
        modifier = modifier
            .height(100.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.linearGradient(
                    listOf(color, color.copy(red = color.red * .68f, green = color.green * .68f, blue = color.blue * .68f)),
                ),
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                // The same cropped-sleeve corner the Explore cards use, so the
                // two surfaces read as one design rather than two.
                .offset(x = 10.dp, y = 12.dp)
                .size(82.dp)
                .graphicsLayer { rotationZ = 16f }
                .clip(RoundedCornerShape(7.dp))
                .background(Color.White.copy(alpha = .22f)),
        ) {
            item.thumbnailUrl?.let { artwork ->
                AsyncImage(
                    model = artwork.artworkAt(ROW_ART_PX),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart).padding(end = 48.dp),
        )
    }
}

/**
 * A tile's colour, hashed off its identity.
 *
 * Identity rather than position, so the same album keeps the same colour as the
 * section reorders around it — a palette indexed by slot would repaint every
 * tile the moment one of them changed.
 */
private fun speedDialColor(key: String): Color =
    when ((key.hashCode() and Int.MAX_VALUE) % 8) {
        0 -> Color(0xFFE64A19)
        1 -> Color(0xFFEC0B65)
        2 -> Color(0xFF8664AC)
        3 -> Color(0xFF6B4EFF)
        4 -> Color(0xFFBE6100)
        5 -> Color(0xFF233C78)
        6 -> Color(0xFF4D97E5)
        else -> Color(0xFFAA267E)
    }

/**
 * Recents mirrors the artist page's top-tracks pager. Any other lead shelf keeps
 * Apple's full-bleed treatment, while the remaining shelves use square cards.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedShelves(
    shelves: List<HomeShelf>,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    firstIsHero: Boolean = true,
    recentsViewType: LibraryViewType,
    onRecentsViewTypeToggle: () -> Unit,
    /**
     * Leaves the lead Recents shelf out. Set by the unfiltered feed while the
     * speed dial above is drawing the same tracks, so the page doesn't carry
     * them twice; the chip feed keeps its own, since a filter that happens to
     * return a Recents shelf is describing a different set.
     */
    hideRecents: Boolean = false,
) {
    // After the filter, so "index == 0" still means the shelf the page leads
    // with rather than the first one that survived.
    val visible = if (hideRecents) {
        shelves.filterNot { it.title.equals(RECENTS_TITLE, ignoreCase = true) }
    } else {
        shelves
    }
    visible.forEachIndexed { index, shelf ->
        item(key = shelf.title + index) {
            val openItem: (ShelfItem) -> Unit = { item -> onItemClick(item, shelf.title) }
            if (index == 0 && shelf.title.equals(RECENTS_TITLE, ignoreCase = true)) {
                RecentShelf(
                    shelf = shelf,
                    onItemClick = openItem,
                    onItemLongPress = onItemLongPress,
                    viewType = recentsViewType,
                    onViewTypeToggle = onRecentsViewTypeToggle,
                )
            } else if (index == 0 && firstIsHero) {
                HeroShelf(shelf = shelf, onItemClick = openItem, onItemLongPress = onItemLongPress)
            } else {
                Shelf(shelf = shelf, onItemClick = openItem, onItemLongPress = onItemLongPress)
            }
        }
    }
}

/** The same four-rows-per-page treatment used by an artist's Top songs. */
@Composable
private fun RecentShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    viewType: LibraryViewType,
    onViewTypeToggle: () -> Unit,
) {
    Column(Modifier.padding(bottom = 26.dp)) {
        RecentSectionHeader(
            title = shelf.title,
            subtitle = shelf.subtitle,
            viewType = viewType,
            onViewTypeToggle = onViewTypeToggle,
        )
        if (viewType == LibraryViewType.LIST) {
            BoxWithConstraints {
                val columnWidth = trackColumnWidth(maxWidth)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(shelf.items.chunked(RECENT_TRACKS_PER_COLUMN)) { column ->
                        Column(Modifier.width(columnWidth)) {
                            column.forEach { item ->
                                RecentTrackRow(
                                    item = item,
                                    onClick = { onItemClick(item) },
                                    onLongPress = onItemLongPress?.let { { it(item) } },
                                )
                            }
                        }
                    }
                }
            }
        } else {
            BoxWithConstraints {
                val cardWidth = heroCardWidth(maxWidth)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(shelf.items) { item ->
                        HeroCard(
                            item = item,
                            onClick = { onItemClick(item) },
                            onLongPress = onItemLongPress?.let { { it(item) } },
                            modifier = Modifier.width(cardWidth),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSectionHeader(
    title: String,
    subtitle: String,
    viewType: LibraryViewType,
    onViewTypeToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(onClick = onViewTypeToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (viewType == LibraryViewType.LIST) {
                    FreeMusicIcons.GridView
                } else {
                    FreeMusicIcons.ListView
                },
                contentDescription = stringResource(
                    if (viewType == LibraryViewType.LIST) {
                        R.string.switch_to_grid_view
                    } else {
                        R.string.switch_to_list_view
                    },
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentTrackRow(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(ROW_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(7.dp))
                .thumbnailBorder(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onLongPress != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onLongPress),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.more),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Shared by the home feed, Explore and Library so headings line up across tabs.
 *
 * [onShowAll] is only ever set on Library, whose rows stop at five cards
 * rather than running the shelf's whole length — see [LibraryGridShelf].
 * Home and Explore never pass it, so their heading is unchanged.
 */
@Composable
internal fun SectionHeader(title: String, subtitle: String = "", onShowAll: (() -> Unit)? = null) {
    val displayTitle = localizeShelfTitle(title)
    val displaySubtitle = localizeShelfSubtitle(subtitle)
    Row(
        modifier = Modifier
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (displaySubtitle.isNotBlank()) {
                Text(
                    text = displaySubtitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onShowAll != null) {
            Text(
                text = stringResource(R.string.show_all),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onShowAll)
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}
@Composable
internal fun localizeShelfTitle(title: String): String {
    val trimmed = title.trim()
    return when {
        trimmed.equals("Recents", ignoreCase = true) ||
            trimmed.equals("Recently played", ignoreCase = true) ||
            trimmed.equals("Gần đây", ignoreCase = true) ||
            trimmed.equals("最近", ignoreCase = true) ->
            stringResource(R.string.shelf_recents)
        trimmed.equals("Playlists", ignoreCase = true) ||
            trimmed.equals("Danh sách phát", ignoreCase = true) ||
            trimmed.equals("再生リスト", ignoreCase = true) ->
            stringResource(R.string.playlists)
        trimmed.equals("Albums", ignoreCase = true) ||
            trimmed.equals("Album", ignoreCase = true) ||
            trimmed.equals("アルバム", ignoreCase = true) ->
            stringResource(R.string.albums)
        trimmed.equals("Artists", ignoreCase = true) ||
            trimmed.equals("Nghệ sĩ", ignoreCase = true) ||
            trimmed.equals("アーティスト", ignoreCase = true) ->
            stringResource(R.string.artists)
        trimmed.equals("Subscriptions", ignoreCase = true) ||
            trimmed.equals("Đã đăng ký", ignoreCase = true) ||
            trimmed.equals("登録チャンネル", ignoreCase = true) ->
            stringResource(R.string.subscriptions)
        trimmed.equals("Trending community playlists", ignoreCase = true) ||
            trimmed.equals("Danh sách phát cộng đồng thịnh hành", ignoreCase = true) ||
            trimmed.equals("Danh sách phát thịnh hành trong cộng đồng người dùng", ignoreCase = true) ||
            trimmed.equals("急上昇のコミュニティ再生リスト", ignoreCase = true) ->
            stringResource(R.string.shelf_trending_community_playlists)
        trimmed.equals("Featured playlists for you", ignoreCase = true) ||
            trimmed.equals("Danh sách phát đề xuất cho bạn", ignoreCase = true) ||
            trimmed.equals("Danh sách phát nổi bật dành cho bạn", ignoreCase = true) ||
            trimmed.equals("おすすめの再生リスト", ignoreCase = true) ->
            stringResource(R.string.shelf_featured_playlists_for_you)
        trimmed.equals("Quick picks", ignoreCase = true) ||
            trimmed.equals("Lựa chọn nhanh", ignoreCase = true) ||
            trimmed.equals("Chọn nhanh đài phát", ignoreCase = true) ||
            trimmed.equals("クイック ミックス", ignoreCase = true) ->
            stringResource(R.string.shelf_quick_picks)
        trimmed.equals("Listen again", ignoreCase = true) ||
            trimmed.equals("Nghe lại", ignoreCase = true) ||
            trimmed.equals("もう一度聴く", ignoreCase = true) ->
            stringResource(R.string.shelf_listen_again)
        trimmed.equals("Mixed for you", ignoreCase = true) ||
            trimmed.equals("Dành riêng cho bạn", ignoreCase = true) ||
            trimmed.equals("ミックス", ignoreCase = true) ->
            stringResource(R.string.shelf_mixed_for_you)
        trimmed.startsWith("Similar to", ignoreCase = true) -> {
            val rest = trimmed.substring(10).trim()
            stringResource(R.string.shelf_similar_to, rest)
        }
        trimmed.startsWith("Tương tự như", ignoreCase = true) -> {
            val rest = trimmed.substring(12).trim()
            stringResource(R.string.shelf_similar_to, rest)
        }
        trimmed.equals("Forgotten favorites", ignoreCase = true) ||
            trimmed.equals("Giai điệu quen thuộc", ignoreCase = true) ||
            trimmed.equals("よく聴いたお気に入りの曲", ignoreCase = true) ->
            stringResource(R.string.shelf_forgotten_favorites)
        trimmed.equals("Recommended music videos", ignoreCase = true) ||
            trimmed.equals("Video âm nhạc đề xuất", ignoreCase = true) ||
            trimmed.equals("おすすめのミュージック ビデオ", ignoreCase = true) ->
            stringResource(R.string.shelf_recommended_music_videos)
        trimmed.equals("From your library", ignoreCase = true) ||
            trimmed.equals("Từ thư viện của bạn", ignoreCase = true) ||
            trimmed.equals("ライブラリから", ignoreCase = true) ->
            stringResource(R.string.shelf_from_your_library)
        trimmed.equals("Charts", ignoreCase = true) ||
            trimmed.equals("Bảng xếp hạng", ignoreCase = true) ||
            trimmed.equals("チャート", ignoreCase = true) ->
            stringResource(R.string.shelf_charts)
        trimmed.equals("New releases", ignoreCase = true) ||
            trimmed.equals("Bản phát hành mới", ignoreCase = true) ||
            trimmed.equals("最新リリース", ignoreCase = true) ->
            stringResource(R.string.shelf_new_releases)
        trimmed.equals("Top music videos", ignoreCase = true) ||
            trimmed.equals("Video âm nhạc hàng đầu", ignoreCase = true) ||
            trimmed.equals("人気のミュージック ビデオ", ignoreCase = true) ->
            stringResource(R.string.shelf_top_music_videos)
        trimmed.equals("For you", ignoreCase = true) ||
            trimmed.equals("Dành cho bạn", ignoreCase = true) ||
            trimmed.equals("あなたへのおすすめ", ignoreCase = true) ->
            stringResource(R.string.shelf_for_you)
        trimmed.equals("Hits today", ignoreCase = true) ||
            trimmed.equals("Today's Hits", ignoreCase = true) ||
            trimmed.equals("Bản hit hôm nay", ignoreCase = true) ||
            trimmed.equals("今日のヒット曲", ignoreCase = true) ->
            stringResource(R.string.shelf_hits_today)
        trimmed.equals("Artists on the rise", ignoreCase = true) ||
            trimmed.equals("Nghệ sĩ đang lên", ignoreCase = true) ->
            stringResource(R.string.shelf_artists_on_the_rise)
        trimmed.equals("Concerts", ignoreCase = true) ||
            trimmed.equals("Buổi hòa nhạc", ignoreCase = true) ||
            trimmed.equals("コンサート", ignoreCase = true) ->
            stringResource(R.string.shelf_concerts)
        trimmed.startsWith("Shorts", ignoreCase = true) ||
            trimmed.equals("Shorts nổi bật", ignoreCase = true) ||
            trimmed.equals("ショート", ignoreCase = true) ->
            stringResource(R.string.shelf_shorts)
        trimmed.equals("Trending", ignoreCase = true) ||
            trimmed.equals("Thịnh hành", ignoreCase = true) ||
            trimmed.equals("急上昇", ignoreCase = true) ->
            stringResource(R.string.shelf_trending)
        else -> title
    }
}
@Composable
internal fun localizeShelfSubtitle(subtitle: String): String {
    val trimmed = subtitle.trim()
    return when {
        trimmed.equals("TOP TUNES RIGHT NOW", ignoreCase = true) ||
            trimmed.equals("Top tunes right now", ignoreCase = true) ||
            trimmed.equals("Giai điệu hàng đầu hiện nay", ignoreCase = true) ||
            trimmed.equals("注目の曲", ignoreCase = true) ->
            stringResource(R.string.shelf_top_tunes_right_now)
        trimmed.equals("From the community", ignoreCase = true) ||
            trimmed.equals("Từ cộng đồng", ignoreCase = true) ||
            trimmed.equals("コミュニティより", ignoreCase = true) ->
            stringResource(R.string.shelf_from_the_community)
        trimmed.equals("YouTube Charts", ignoreCase = true) ||
            trimmed.equals("Bảng xếp hạng YouTube", ignoreCase = true) ||
            trimmed.equals("YouTube チャート", ignoreCase = true) ->
            stringResource(R.string.shelf_youtube_charts)
        else -> subtitle
    }
}

@Composable
internal fun localizeCardSubtitle(subtitle: String): String {
    if (subtitle.isBlank()) return subtitle
    val delimiter = " • "
    val parts = subtitle.split(delimiter)
    val songLabel = stringResource(R.string.shelf_song_item)
    val singleLabel = stringResource(R.string.shelf_single)
    val chartLabel = stringResource(R.string.shelf_chart)
    val playlistLabel = stringResource(R.string.playlist)
    val localizedParts = parts.map { part ->
        val trimmed = part.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        when {
            trimmed.equals("Song", ignoreCase = true) || trimmed.equals("Titre", ignoreCase = true) ||
                trimmed.equals("Bài hát", ignoreCase = true) || trimmed.equals("曲", ignoreCase = true) -> songLabel
            trimmed.equals("Single", ignoreCase = true) || trimmed.equals("Đĩa đơn", ignoreCase = true) ||
                trimmed.equals("シングル", ignoreCase = true) -> singleLabel
            trimmed.equals("Chart", ignoreCase = true) || trimmed.equals("Bảng xếp hạng", ignoreCase = true) ||
                trimmed.equals("チャート", ignoreCase = true) -> chartLabel
            trimmed.equals("Playlist", ignoreCase = true) || trimmed.equals("Danh sách phát", ignoreCase = true) ||
                trimmed.equals("再生リスト", ignoreCase = true) -> playlistLabel
            lower.endsWith(" views") || lower.endsWith(" view") || lower.endsWith(" lượt xem") ||
                lower.endsWith(" 回視聴") || lower.endsWith("回視聴") -> {
                val count = trimmed.substringBeforeLast(' ', "").trim()
                if (count.any { it.isDigit() }) stringResource(R.string.card_views_format, count) else part
            }
            lower.endsWith(" plays") || lower.endsWith(" play") || lower.endsWith(" lượt phát") ||
                lower.endsWith(" 回再生") || lower.endsWith("回再生") -> {
                val count = trimmed.substringBeforeLast(' ', "").trim()
                if (count.any { it.isDigit() }) stringResource(R.string.card_plays_format, count) else part
            }
            lower.endsWith(" songs") || lower.endsWith(" song") || lower.endsWith(" bài hát") ||
                lower.endsWith(" 曲") || lower.endsWith("曲") -> {
                val count = trimmed.substringBeforeLast(' ', "").trim()
                if (count.any { it.isDigit() }) stringResource(R.string.card_songs_format, count) else part
            }
            else -> part
        }
    }
    return localizedParts.joinToString(delimiter)
}

@Composable
private fun HeroShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
) {
    Column(Modifier.padding(bottom = 26.dp)) {
        SectionHeader(shelf.title, shelf.subtitle)
        // Measured rather than taken as a share of the parent, because the card
        // has a ceiling as well as a fraction — see [heroCardWidth]. A fixed
        // width is also the only one of the two the aspect ratio below can turn
        // into a height, so the card keeps its shape however it was arrived at.
        BoxWithConstraints {
            val cardWidth = heroCardWidth(maxWidth)
            LazyRow(
                state = rememberLazyListState(),
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(shelf.items) { item ->
                    HeroCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        onLongPress = onItemLongPress?.let { { it(item) } },
                        modifier = Modifier.width(cardWidth),
                    )
                }
            }
        }
    }
}

/** Big card: artwork with the caption laid over a scrim, as on Listen Now. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroCard(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(HERO_CARD_RATIO)
            .clip(RoundedCornerShape(18.dp))
            .thumbnailBorder(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
    ) {
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(HEADER_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                    ),
                )
                .padding(start = 16.dp, end = 16.dp, top = 34.dp, bottom = 14.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotBlank()) {
                val displaySubtitle = localizeCardSubtitle(item.subtitle)
                Text(
                    text = displaySubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * [leadingCard] rides at the head of the row, ahead of the content — the
 * Library tab's "New playlist" tile, which belongs among the playlists rather
 * than in a bar somewhere above them. [onItemLongPress] opens the album /
 * playlist menu, and is null only where a card points at something with no
 * track list behind it to act on.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Shelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    leadingCard: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.padding(bottom = 26.dp)) {
        SectionHeader(shelf.title, shelf.subtitle)
        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            leadingCard?.let { card -> item(key = "leading") { card() } }
            items(shelf.items) { item ->
                ShelfCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onLongPress = onItemLongPress?.let { { it(item) } },
                )
            }
        }
    }
}

/**
 * A card that isn't a thing yet — the dashed "New playlist" tile at the head
 * of the Library's playlist row, sized to sit in line with the covers beside
 * it rather than as a button bolted above them.
 */
@Composable
internal fun NewShelfCard(
    icon: ImageVector,
    label: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(SHELF_CARD_WIDTH),
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A device-folder card: mesh gradient in the folder's colours with its glyph
 * on top. One composable for local music, WebDAV and SMB rather than three
 * copies of the same box.
 */
@Composable
private fun ServiceCard(colors: List<Color>, trackKey: String, icon: ImageVector) {
    val palette = remember { MeshPalette(colors) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        MeshGradientBackground(
            palette = palette,
            trackKey = trackKey,
            continuous = true,
            blurRadius = 24.dp,
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(40.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShelfCard(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier.width(SHELF_CARD_WIDTH),
    /** Set on a Library playlist card that's in [AppSettings.pinnedPlaylists][com.ihimanshunayak.freemusic.data.settings.AppSettings.pinnedPlaylists]. */
    isPinned: Boolean = false,
) {
    Column(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress),
    ) {
        when (item.browseId) {
            "local:downloads" -> {
                val palette = remember { MeshPalette(listOf(Color(0xFF1E3C72), Color(0xFF2A5298))) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    MeshGradientBackground(
                        palette = palette,
                        trackKey = "local:downloads",
                        continuous = true,
                        blurRadius = 24.dp,
                    )
                    Icon(
                        imageVector = FreeMusicIcons.Download,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            "local:all" -> ServiceCard(
                colors = listOf(Color(0xFF134E5E), Color(0xFF71B280)),
                trackKey = "local:all",
                icon = Icons.Rounded.LibraryMusic,
            )
            "local:webdav" -> ServiceCard(
                colors = listOf(Color(0xFF3A1C71), Color(0xFFD76D77)),
                trackKey = "local:webdav",
                icon = Icons.Rounded.Folder,
            )
            "local:smb" -> ServiceCard(
                colors = listOf(Color(0xFF0F2027), Color(0xFF2C5364)),
                trackKey = "local:smb",
                icon = Icons.Rounded.Storage,
            )
            else -> {
                AsyncImage(
                    model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .thumbnailBorder(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isPinned) {
                Icon(
                    imageVector = FreeMusicIcons.Pin,
                    contentDescription = stringResource(R.string.pinned),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        val displaySubtitle = localizeCardSubtitle(item.subtitle)
        Text(
            text = displaySubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
