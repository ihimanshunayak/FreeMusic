package com.ihimanshunayak.freemusic.data.model

/**
 * The public engagement figures for one track, and the token that opens its
 * comments.
 *
 * Two different families of number live here, and keeping them together is
 * deliberate rather than accidental: they are read out of one response, they
 * are fetched together, and they are cached together.
 *
 * - [likeCount] / [commentCount] / [viewCount] belong to *this upload*. A
 *   song reachable through two different uploads reports two different sets,
 *   and these are the ones YouTube itself prints on the buttons.
 * - The catalogue tally — "197M plays" — belongs to the recording rather than
 *   to an upload, rides along with the browse response for free, and lives on
 *   [Song.globalPlayCount].
 *
 * [dislikeCount] is null and always will be: YouTube publishes no count for
 * it. The thumb-down button exists and its state can be read and written, but
 * the figure behind it was never published, and a chip that invented one
 * would be lying to the listener.
 */
data class TrackEngagement(
    /**
     * The raw like count — 809396, not "809K". The short label YouTube
     * prints is kept beside it as [likeLabel] so the row shows the same
     * wording YouTube does rather than a count this app rounded on its own.
     */
    val likeCount: Long?,
    /** YouTube's own short form of the like count — "809K", "3.8K". */
    val likeLabel: String?,
    /**
     * The number of comments, as a short label — "17K", "37". Kept as the
     * label rather than a number because this is the only form YouTube
     * publishes; the underlying count is not on the wire.
     */
    val commentCount: String?,
    /**
     * The play/view count as a short label — "113M views", "1.5B views".
     */
    val viewCount: String?,
    /**
     * The continuation that opens the track's comments, or null when the
     * response carried no comment section — which is what a track with
     * comments turned off looks like.
     */
    val commentToken: String?,
) {
    /** Whether there is anything worth drawing for this track. */
    val hasAny: Boolean
        get() = likeCount != null || commentCount != null || viewCount != null
}

/**
 * One comment on a track.
 *
 * @param author the channel that wrote it, already prefixed with the "@" the
 *   comment itself carries.
 * @param text the comment's body.
 * @param likeLabel how many listeners liked it — "4.4K".
 * @param replyCount how many replies it has.
 * @param published when it was written — "5 years ago".
 */
data class TrackComment(
    val author: String,
    val text: String,
    val likeLabel: String?,
    val replyCount: Int,
    val published: String?,
)

/**
 * One page of a track's comments.
 *
 * @param comments the comments themselves, in the order YouTube returned them.
 * @param continuation the token for the next page, or null once there are no
 *   more.
 */
data class CommentPage(
    val comments: List<TrackComment>,
    val continuation: String?,
)