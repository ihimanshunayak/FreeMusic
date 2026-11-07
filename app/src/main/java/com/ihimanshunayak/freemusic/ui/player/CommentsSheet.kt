package com.ihimanshunayak.freemusic.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.ihimanshunayak.freemusic.R
import com.ihimanshunayak.freemusic.data.engagement.EngagementRepository
import com.ihimanshunayak.freemusic.data.model.CommentPage
import com.ihimanshunayak.freemusic.data.model.TrackComment
import dev.chrisbanes.haze.HazeState

/**
 * The comments on the track that is playing.
 *
 * A [PlayerDrawer] like every other sheet in this package: the comment
 * thread belongs to the player rather than to the page behind it, and it
 * arrives over the artwork the track is playing on.
 *
 * The comments are YouTube's own, read from the track's watch-queue response
 * and its continuation. The body, the author, the like tally ("4.4K"), the
 * reply count and the age ("5 years ago") all come as published — none of it
 * is generated here, and a thread that read as though the app had written it
 * would be worse than no thread at all.
 *
 * The pages are fetched as the list is scrolled to the end rather than all at
 * once: a track with thousands of comments has a thousand continuations, and
 * the listener is reading the top twenty, not the bottom of the archive.
 */
@Composable
internal fun CommentsSheet(
    hazeState: HazeState,
    videoId: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var comments by remember(videoId) { mutableStateOf<List<TrackComment>?>(null) }
    var continuation by remember(videoId) { mutableStateOf<String?>(null) }
    var loading by remember(videoId) { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var failed by remember(videoId) { mutableStateOf(false) }
    // The sheet's own scope, so fetching the next page outlives the click
    // that asked for it but not the sheet itself.
    val scope = rememberCoroutineScope()

    LaunchedEffect(videoId) {
        loading = true
        failed = false
        comments = null
        continuation = null
        val page = EngagementRepository.comments(videoId).getOrNull()
        if (page == null) {
            failed = true
            loading = false
            return@LaunchedEffect
        }
        comments = page.comments
        continuation = page.continuation
        loading = false
    }

    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(R.string.engagement_comments),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        when {
            loading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            failed -> {
                Text(
                    text = stringResource(R.string.engagement_comments_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }

            comments.isNullOrEmpty() -> {
                Text(
                    text = stringResource(R.string.engagement_no_comments),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }

            else -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    comments.orEmpty().forEach { comment ->
                        CommentRow(comment)
                    }
                    // The next page's marker. Tapping it is deliberate: the
                    // list is a read, not a feed, and a thread that keeps
                    // loading as the listener scrolls past the twentieth
                    // comment makes the drawer taller for no reason.
                    val next = continuation
                    if (next != null) {
                        LoadMoreCommentsRow(
                            loading = loadingMore,
                            onClick = {
                                if (loadingMore) return@LoadMoreCommentsRow
                                loadingMore = true
                                // The continuation is spent outside the
                                // first page's effect on purpose: fetching a
                                // page must not restart the first page's
                                // fetch, or the list would replace itself
                                // with the page it was about to show.
                                scope.launch {
                                    val more = EngagementRepository.commentsPage(next).getOrNull()
                                    if (more != null) {
                                        comments = comments.orEmpty() + more.comments
                                        continuation = more.continuation
                                    }
                                    loadingMore = false
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The row that fetches the next page of comments.
 *
 * A button rather than an automatic scroll trigger, and the reason is the
 * drawer's own scroll: the list inside it takes the finger, so a page that
 * loaded on approach would arrive while the reader was still reading the
 * last line of the previous one, and the text they were looking at would
 * move under them.
 */
@Composable
private fun LoadMoreCommentsRow(
    loading: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !loading,
                onClick = onClick,
            )
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Text(
                text = stringResource(R.string.engagement_load_more_comments),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
    }
}

/**
 * One comment.
 *
 * The author, then the body, then the three figures YouTube prints under it:
 * the likes it received, the replies it has, and when it was written.
 */
@Composable
private fun CommentRow(comment: TrackComment) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text(
            text = comment.author,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            ),
            color = Color.White.copy(alpha = 0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = comment.text,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = Color.White.copy(alpha = 0.78f),
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
        val likes = comment.likeLabel
        val replies = comment.replyCount
        val published = comment.published
        if (likes != null || replies > 0 || published != null) {
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (likes != null) {
                    Icon(
                        Icons.Rounded.ThumbUp,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = likes,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = Color.White.copy(alpha = 0.5f),
                    )
                }
                if (replies > 0) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.engagement_replies, replies),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = Color.White.copy(alpha = 0.5f),
                    )
                }
                if (published != null) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = published,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = Color.White.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}
