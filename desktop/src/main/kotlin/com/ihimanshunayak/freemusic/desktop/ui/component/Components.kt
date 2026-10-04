// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - shared UI components.
//
// NAME
//     Components.kt - the small pieces reused by more than one screen.
//
// DESCRIPTION
//     Everything here is stateless and takes plain values. Nothing in this file
//     can start a request, open a file or change the queue, which is what keeps
//     the screens the only place where behaviour lives: a component that can
//     mutate state is a component that has to be understood before it can be
//     reused.
//
//     The look is Windows' own. Surfaces come from `FluentTheme.colors` rather
//     than from literal colours, so the same component renders correctly in the
//     light theme, the dark theme, and under a user accent colour without a
//     single conditional.
//
// RESPONSIBILITIES
//     - Artwork placeholders that never show an empty hole.
//     - Rows and cards shared by Home, Search, Library, Queue and History.
//     - Formatting helpers the transport and the lists agree on.
//
// DEPENDENCIES
//     - Compose Fluent for the surface colours and the controls.
//     - [RemoteImage] for the asynchronous artwork load.
//
// INTEGRATION NOTES
//     - [formatMillis] is the single definition of a time label. The queue, the
//       player bar and the history list all call it, so a change to the format
//       happens once.
//     - Artwork is painted *over* the placeholder rather than instead of it,
//       which is what makes a slow or 404 image degrade to a music note instead
//       of to a blank rectangle.

package com.ihimanshunayak.freemusic.desktop.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.model.ResultKind
import com.ihimanshunayak.freemusic.desktop.model.SearchResult
import com.ihimanshunayak.freemusic.desktop.model.Track
import com.ihimanshunayak.freemusic.desktop.ui.RemoteImage
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Icon
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text

/**
 * A square thumbnail with a graceful placeholder.
 *
 * Artwork loads asynchronously and can fail - a deleted video returns 404 - so
 * the placeholder is the *default* and the image is painted over it, which means
 * a slow or broken URL shows a music note rather than an empty hole.
 */
@Composable
fun Thumbnail(
    url: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector = FluentGlyphs.Music,
    cornerRadius: Int = 8,
) {
    Box(
        modifier = modifier
            .clip(FluentTheme.shapes.control)
            .background(FluentTheme.colors.background.card.default),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = FluentTheme.colors.text.text.tertiary,
            modifier = Modifier.fillMaxSize(0.42f),
        )
        if (!url.isNullOrBlank()) {
            RemoteImage(url = url, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * A large, round artwork image for an artist hero or an avatar.
 *
 * [RemoteImage] already renders nothing when it fails, so the icon underneath
 * becomes the visible content in that case.
 */
@Composable
fun CircleArtwork(url: String?, size: Int, icon: ImageVector = FluentGlyphs.Artists) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(FluentTheme.colors.background.card.default),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = FluentTheme.colors.text.text.tertiary,
            modifier = Modifier.size((size * 0.4).dp),
        )
        if (!url.isNullOrBlank()) {
            RemoteImage(url = url, modifier = Modifier.fillMaxSize())
        }
    }
}

/** One row of a track list, used by Home, Search, Library, Queue and History. */
@Composable
fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onPlayNext: (() -> Unit)? = null,
    onEnqueue: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    // The selected row uses the accent's subtle fill rather than the accent
    // itself: a full-strength accent behind a line of body text would fight the
    // text for attention, and on this screen the *title* is the thing to read.
    val background = if (isCurrent) FluentTheme.colors.subtleFill.secondary else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FluentTheme.shapes.control)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(track.thumbnailUrl, Modifier.size(40.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = track.title,
                style = FluentTheme.typography.body,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) FluentTheme.colors.text.accent.primary else FluentTheme.colors.text.text.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.text.text.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onPlayNext != null) {
            SubtleButton(onClick = onPlayNext, iconOnly = true) {
                Icon(FluentGlyphs.Playlist, contentDescription = "Play next")
            }
        }
        if (onEnqueue != null) {
            SubtleButton(onClick = onEnqueue, iconOnly = true) {
                Icon(FluentGlyphs.QueueAdd, contentDescription = "Add to queue")
            }
        }
        trailing?.invoke()
        if (!isCurrent) {
            Text(
                text = track.durationLabel,
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.text.text.secondary,
                modifier = Modifier.padding(start = 8.dp),
            )
        } else if (isPlaying) {
            Icon(
                FluentGlyphs.Play,
                contentDescription = "Now playing",
                tint = FluentTheme.colors.text.accent.primary,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(16.dp),
            )
        }
    }
}

/** A card in a horizontal shelf. Used on Home and for search top-results. */
@Composable
fun MediaCard(
    title: String,
    subtitle: String?,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = FluentGlyphs.Album,
    width: Int = 160,
    round: Boolean = false,
) {
    Column(
        modifier = modifier
            .width(width.dp)
            .clip(FluentTheme.shapes.control)
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        if (round) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircleArtwork(url = thumbnailUrl, size = width - 12, icon = icon)
            }
        } else {
            Thumbnail(
                url = thumbnailUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                icon = icon,
                cornerRadius = 10,
            )
        }
        Text(
            text = title,
            style = FluentTheme.typography.body,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.text.text.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The glyph that matches a search row's kind, so mixed results read at a glance. */
fun iconForKind(kind: ResultKind): ImageVector = when (kind) {
    ResultKind.ARTIST -> FluentGlyphs.Artists
    ResultKind.ALBUM -> FluentGlyphs.Album
    ResultKind.PLAYLIST -> FluentGlyphs.Playlist
    else -> FluentGlyphs.Music
}

/** A section heading with an optional trailing action. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = FluentTheme.typography.subtitle,
            color = FluentTheme.colors.text.text.primary,
        )
        trailing?.invoke()
    }
}

/** Shown when a list has no content yet, so a screen is never blank without cause. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = FluentTheme.colors.text.text.tertiary,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = title,
            style = FluentTheme.typography.bodyStrong,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = detail,
            style = FluentTheme.typography.caption,
            color = FluentTheme.colors.text.text.secondary,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** A centred spinner, used while a screen has nothing to draw yet. */
@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String? = null) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ProgressRing()
            if (label != null) {
                Text(
                    text = label,
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/**
 * A failure with a retry.
 *
 * The reason is shown verbatim rather than paraphrased: this app is a thin client
 * over a service the user cannot inspect, so the service's own message is the most
 * useful thing it has. It is styled as secondary so the action stays the focus.
 */
@Composable
fun ErrorState(
    title: String,
    detail: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            FluentGlyphs.Warning,
            contentDescription = null,
            tint = FluentTheme.colors.system.critical,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = title,
            style = FluentTheme.typography.subtitle,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = detail,
            style = FluentTheme.typography.caption,
            color = FluentTheme.colors.text.text.secondary,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (onRetry != null) {
            AccentButton(onClick = onRetry, modifier = Modifier.padding(top = 20.dp)) {
                Text("Try again")
            }
        }
    }
}

/**
 * A shimmering stand-in for a row of cards.
 *
 * Used by screens that would otherwise show nothing at all while they load, which
 * reads as a hung app. A static block is enough: at this size an animation would
 * cost more than it communicates.
 */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(5) {
            Box(
                modifier = Modifier
                    .size(width = 160.dp, height = 160.dp)
                    .clip(FluentTheme.shapes.control)
                    .background(FluentTheme.colors.background.card.default),
            )
        }
    }
}

/** A labelled value pair, used by the diagnostics and account screens. */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = FluentTheme.typography.body,
            color = FluentTheme.colors.text.text.secondary,
            modifier = Modifier.width(180.dp),
        )
        Text(
            text = value,
            style = FluentTheme.typography.body,
            color = FluentTheme.colors.text.text.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** `3:07` / `1:02:44` from milliseconds, for the player bar and the lists. */
@Composable
fun rememberTimeLabel(millis: Long): String = remember(millis) { formatMillis(millis) }

fun formatMillis(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = millis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Search result card, used when a screen wants cards instead of rows. */
@Composable
fun SearchResultCard(result: SearchResult, onClick: () -> Unit, modifier: Modifier = Modifier) {
    MediaCard(
        title = result.title,
        subtitle = result.subtitle,
        thumbnailUrl = result.thumbnailUrl,
        onClick = onClick,
        modifier = modifier,
        icon = iconForKind(result.kind),
        round = result.kind == ResultKind.ARTIST,
    )
}

/**
 * The vertical gap a screen puts between its sections.
 *
 * A named constant rather than a literal at each call site so the spacing of a
 * whole screen can be tightened in one edit.
 */
val SectionGap = 24.dp

/** A blank line, spelled out so a `Spacer` call reads as intent rather than maths. */
@Composable
fun VerticalGap(height: androidx.compose.ui.unit.Dp) {
    Spacer(Modifier.height(height))
}

/** A full-height divider, used between the list and the detail column. */
@Composable
fun VerticalDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(1.dp)
            .fillMaxSize()
            .background(FluentTheme.colors.stroke.divider.default),
    )
}

/** A horizontal divider between list rows. */
@Composable
fun HorizontalDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(FluentTheme.colors.stroke.divider.default),
    )
}
