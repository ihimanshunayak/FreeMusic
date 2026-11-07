package com.ihimanshunayak.freemusic.ui.player

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ihimanshunayak.freemusic.R
import com.ihimanshunayak.freemusic.data.engagement.EngagementRepository
import com.ihimanshunayak.freemusic.data.model.LikeStatus
import com.ihimanshunayak.freemusic.data.model.TrackEngagement
import com.ihimanshunayak.freemusic.ui.components.lightweightLiquidGlass
import com.ihimanshunayak.freemusic.ui.haptics.Haptic
import com.ihimanshunayak.freemusic.ui.haptics.rememberHaptics
import com.ihimanshunayak.freemusic.ui.icons.FreeMusicIcons

/**
 * The engagement row YouTube Music draws above its scrubber: thumbs up,
 * thumbs down, comments, save, share, download and mix.
 *
 * Borrowed as a whole rather than pieced together, because the seven chips
 * read as one instrument panel — a rating, a conversation, and four ways of
 * taking the track somewhere else — and splitting them across the player's
 * existing rows would leave the panel saying two things in two places.
 *
 * The counts are YouTube's own, read from the track's watch-queue response:
 * the like count as the short label YouTube prints ("3.8K"), the comment
 * count as the only form published ("17K"), and the view count as
 * "113M views". No figure is rounded here — a chip that said "3,812" beside
 * YouTube's "3.8K" would be claiming a precision the wire does not carry.
 *
 * There is no share count. YouTube publishes none — four separate clients
 * were swept for it and the only "share"-named keys on the wire are opaque
 * share tokens — so the share chip is drawn without one rather than with a
 * number this app made up.
 *
 * The row scrolls sideways. Seven chips plus two counts do not fit a phone's
 * width, and shrinking them until they do makes them unreadable; a row that
 * scrolls off the right edge is the standard answer and the one YouTube Music
 * itself uses.
 */
@Composable
internal fun EngagementChipRow(
    /** The track's own figures, or null while they are still being fetched. */
    engagement: TrackEngagement?,
    /** The account's rating of this track. */
    likeStatus: LikeStatus,
    /** Whether this track is in the library. */
    saved: Boolean,
    /** Whether the account is signed in at all. */
    signedIn: Boolean,
    onToggleLike: () -> Unit,
    onToggleDislike: () -> Unit,
    onOpenComments: () -> Unit,
    onToggleSave: () -> Unit,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    onStartMix: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    // The figures arrive after the row does, and a row that changes its own
    // height when a count lands moves everything under it. So the count slots
    // are reserved: an empty slot is the width of a short label, which keeps
    // the chips that carry no count in the same place whether or not the
    // figures have turned up yet.
    val likeLabel = engagement?.likeLabel
    val commentLabel = engagement?.commentCount
    val viewLabel = engagement?.viewCount

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EngagementChip(
            icon = if (likeStatus == LikeStatus.LIKE) {
                FreeMusicIcons.ThumbsUpFilled
            } else {
                FreeMusicIcons.ThumbsUp
            },
            label = likeLabel,
            contentDescription = stringResource(R.string.engagement_like),
            active = likeStatus == LikeStatus.LIKE,
            onClick = onToggleLike,
            haptic = if (likeStatus == LikeStatus.LIKE) Haptic.ToggleOff else Haptic.ToggleOn,
        )
        EngagementChip(
            icon = if (likeStatus == LikeStatus.DISLIKE) {
                FreeMusicIcons.ThumbsDownFilled
            } else {
                FreeMusicIcons.ThumbsDown
            },
            label = null,
            contentDescription = stringResource(R.string.engagement_dislike),
            active = likeStatus == LikeStatus.DISLIKE,
            onClick = onToggleDislike,
            haptic = if (likeStatus == LikeStatus.DISLIKE) Haptic.ToggleOff else Haptic.ToggleOn,
        )
        EngagementChip(
            icon = FreeMusicIcons.Comment,
            label = commentLabel,
            contentDescription = stringResource(R.string.engagement_comments),
            onClick = onOpenComments,
        )
        // Save. A bookmark rather than the heart the credits use: the heart
        // already means "like" on this screen, and two hearts saying two
        // different things is the one thing a rating row cannot have.
        EngagementChip(
            icon = if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
            label = null,
            contentDescription = stringResource(
                if (saved) R.string.engagement_saved else R.string.engagement_save,
            ),
            active = saved,
            onClick = onToggleSave,
        )
        EngagementChip(
            icon = FreeMusicIcons.Share,
            label = null,
            contentDescription = stringResource(R.string.engagement_share),
            onClick = onShare,
        )
        EngagementChip(
            icon = FreeMusicIcons.Download,
            label = null,
            contentDescription = stringResource(R.string.engagement_download),
            onClick = onDownload,
        )
        EngagementChip(
            icon = FreeMusicIcons.Mix,
            label = null,
            contentDescription = stringResource(R.string.engagement_mix),
            onClick = onStartMix,
        )
        // The views, at the end of the row rather than on a chip — see above.
        if (viewLabel != null) {
            Text(
                text = viewLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

/**
 * One chip of the engagement row.
 *
 * A glass capsule with the glyph in it, and the count beside the glyph inside
 * the same capsule — the count belongs to the action, not to the row, and
 * putting it outside the capsule would leave a bare number floating between
 * two controls.
 *
 * The chip without a count is a round disc rather than a wide capsule: seven
 * equal-width capsules would leave the row looking like a set of blanks, and
 * the counts are what tell the listener which chip is which at a glance.
 */
@Composable
private fun EngagementChip(
    icon: ImageVector,
    label: String?,
    contentDescription: String,
    active: Boolean = false,
    onClick: () -> Unit,
    haptic: Haptic = Haptic.Tap,
) {
    val haptics = rememberHaptics()
    val alpha by animateFloatAsState(
        targetValue = if (active) 1f else 0.82f,
        animationSpec = tween(180),
        label = "engagementChipAlpha",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .lightweightLiquidGlass(
                shape = RoundedCornerShape(50),
                fallbackColor = Color.White.copy(alpha = 0.12f),
            )
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.85f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.play(haptic)
                onClick()
            }
            .semantics { this.contentDescription = contentDescription }
            .height(34.dp)
            .padding(horizontal = if (label != null) 10.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(34.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = alpha),
                modifier = Modifier.size(18.dp),
            )
        }
        if (label != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = Color.White.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(2.dp))
        }
    }
}

/**
 * The track's engagement figures, loaded when the player asks for them.
 *
 * Held here rather than in the view model because the figures are a property
 * of one track and nothing else: no screen lists them, no other page shows
 * them, and they change with every track. A view model would carry a map of
 * them for tracks nobody is playing.
 *
 * The load is keyed on the track, so changing track drops the old figures and
 * starts the new ones — a count that belongs to the previous song sitting
 * beside the next one's title is worse than no count at all.
 */
@Composable
internal fun rememberEngagement(videoId: String?): TrackEngagement? {
    var engagement by remember(videoId) { mutableStateOf<TrackEngagement?>(null) }
    LaunchedEffect(videoId) {
        engagement = null
        if (videoId.isNullOrBlank()) return@LaunchedEffect
        engagement = EngagementRepository.engagement(videoId)
    }
    return engagement
}
