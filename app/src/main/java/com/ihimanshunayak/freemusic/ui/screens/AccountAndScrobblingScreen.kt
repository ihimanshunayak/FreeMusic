// Copyright (c) A|iens. All rights reserved.

/*
 * FreeMusic — Account & Integrations (Profile) screen
 * ----------------------------------------------------------------------
 * Phase: Phase 1B (Profile) — social-media-style redesign.
 *
 * Design intent (matches the reference image `Pasted Image 2.png`):
 *   - Title:    "My profile" (large display type, top-leading)
 *   - Actions:  bell-with-5-badge + gear icon, top-trailing
 *   - Hero:     circular avatar (overlapping "verified" check badge in
 *               BrandLime) + display name + @username + 4 stat tiles
 *   - Tabs:     3-tab pill row (Feed / Challenge / Badge)
 *   - Body:     sample post card + empty-state copy
 *
 * Brand: BrandLime = #BFFF00 — brand mark, not Material 3 primary role.
 */
package com.ihimanshunayak.freemusic.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ihimanshunayak.freemusic.R
import com.ihimanshunayak.freemusic.data.model.Account
import com.ihimanshunayak.freemusic.data.profile.ProfileStats
import com.ihimanshunayak.freemusic.data.settings.AppSettings
import com.ihimanshunayak.freemusic.ui.theme.BrandLime
import com.ihimanshunayak.freemusic.ui.theme.BrandLimeOn
import com.ihimanshunayak.freemusic.ui.theme.BrandLimeSoft

// -----------------------------------------------------------------------------
// Public composable
// -----------------------------------------------------------------------------

/**
 * Profile screen.
 *
 * Signature is stable: all parameters from the previous implementation
 * are preserved (so MainActivity's call site does not need to change),
 * and two new optional lambdas are added with safe defaults.
 */
@Composable
fun AccountAndScrobblingScreen(
    signedIn: Boolean,
    account: Account?,
    channelName: String?,
    stats: ProfileStats,
    onSignIn: () -> Unit,
    onSwitchChannel: () -> Unit,
    onSignOut: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenListenBrainzLogin: () -> Unit,
    onOpenLastfmLogin: () -> Unit,
    onOpenDiscord: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onOpenNotifications: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    // Diagnostic marker — see footer.
    Log.d("Account", "AccountAndScrobblingScreen compose: signedIn=$signedIn account=${account?.name}")

    val displayName by AppSettings.displayNameOverride.collectAsStateWithLifecycle()
    val username by AppSettings.usernameOverride.collectAsStateWithLifecycle()
    val status by AppSettings.statusLineOverride.collectAsStateWithLifecycle()
    val bio by AppSettings.bioOverride.collectAsStateWithLifecycle()
    val avatarOverride by AppSettings.avatarOverrideUri.collectAsStateWithLifecycle()

    val resolvedName = remember(displayName, account?.name) {
        displayName.ifBlank { account?.name.orEmpty() }
    }
    val resolvedHandle = remember(username) {
        if (username.isBlank()) "" else "@$username"
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(contentPadding),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item("title-row") {
            ProfileTitleRow(
                onOpenNotifications = onOpenNotifications,
                onOpenSettings = onOpenSettings,
                notificationCount = 5,
            )
        }

        item("hero") {
            ProfileHero(
                signedIn = signedIn,
                displayName = resolvedName,
                handle = resolvedHandle,
                status = status,
                bio = bio,
                account = account,
                avatarOverride = avatarOverride,
                stats = stats,
                onEditProfile = onEditProfile,
            )
        }

        item("tabs") {
            Spacer(Modifier.height(8.dp))
            ProfileTabsRow()
            Spacer(Modifier.height(16.dp))
        }

        item("feed-header") {
            SectionHeader(label = stringResource(R.string.profile_tab_feed))
        }

        if (signedIn && resolvedName.isNotBlank()) {
            item("feed-post") {
                SamplePostCard(
                    authorName = resolvedName,
                    authorHandle = resolvedHandle,
                    timeAgo = stringResource(R.string.profile_post_sample_time),
                    body = stringResource(R.string.profile_post_sample_text),
                )
                Spacer(Modifier.height(24.dp))
            }
        } else {
            item("feed-empty") {
                EmptyStateCard(
                    title = stringResource(R.string.profile_feed_empty),
                    body = stringResource(R.string.profile_feed_empty_hint),
                )
                Spacer(Modifier.height(24.dp))
            }
        }

        item("challenge-header") {
            SectionHeader(label = stringResource(R.string.profile_tab_challenge))
        }
        item("challenge-empty") {
            EmptyStateCard(
                title = stringResource(R.string.profile_challenge_empty),
                body = stringResource(R.string.profile_challenge_empty_hint),
            )
            Spacer(Modifier.height(24.dp))
        }

        item("badge-header") {
            SectionHeader(label = stringResource(R.string.profile_tab_badge))
        }
        item("badge-empty") {
            EmptyStateCard(
                title = stringResource(R.string.profile_badge_empty),
                body = stringResource(R.string.profile_badge_empty_hint),
            )
            Spacer(Modifier.height(24.dp))
        }

        item("bottom-spacer") {
            // The bottom tab bar is rendered by the host (MainActivity);
            // we just give the content enough room to scroll past it.
            Spacer(Modifier.height(96.dp))
        }
    }
}

// -----------------------------------------------------------------------------
// Section: title row
// -----------------------------------------------------------------------------

@Composable
private fun ProfileTitleRow(
    onOpenNotifications: () -> Unit,
    onOpenSettings: () -> Unit,
    notificationCount: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.profile_title),
            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )

        BellWithBadge(
            count = notificationCount,
            onClick = onOpenNotifications,
        )
        Spacer(Modifier.width(8.dp))
        IconAction(
            icon = Icons.Rounded.Settings,
            contentDescription = stringResource(R.string.profile_settings_cd),
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun BellWithBadge(
    count: Int,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.NotificationsActive,
            contentDescription = stringResource(R.string.profile_notifications_cd),
            tint = MaterialTheme.colorScheme.onBackground,
        )
        if (count > 0) {
            Surface(
                shape = CircleShape,
                color = BrandLime,
                contentColor = BrandLimeOn,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(18.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (count > 9) "9+" else count.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

@Composable
private fun IconAction(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
}

// -----------------------------------------------------------------------------
// Section: hero
// -----------------------------------------------------------------------------

@Composable
private fun ProfileHero(
    signedIn: Boolean,
    displayName: String,
    handle: String,
    status: String,
    bio: String,
    account: Account?,
    avatarOverride: String,
    stats: ProfileStats,
    onEditProfile: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AvatarWithVerified(
            account = account,
            avatarOverrideUri = avatarOverride,
            sizeDp = 96,
        )
        Spacer(Modifier.height(12.dp))

        Text(
            text = if (signedIn) displayName.ifBlank { "Your Name" } else stringResource(R.string.profile_signed_out_title),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (handle.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = handle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
        if (status.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            )
        }
        if (bio.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = bio,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(20.dp))

        ProfileStatsRow(stats = stats, onEditProfile = onEditProfile, signedIn = signedIn)
    }
}

@Composable
private fun AvatarWithVerified(
    account: Account?,
    avatarOverrideUri: String,
    sizeDp: Int,
) {
    Box(modifier = Modifier.size((sizeDp + 16).dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val firstLetter = (account?.name?.firstOrNull() ?: 'A').uppercaseChar()
            Text(
                text = firstLetter.toString(),
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Surface(
            shape = CircleShape,
            color = BrandLime,
            contentColor = BrandLimeOn,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(28.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Verified,
                    contentDescription = stringResource(R.string.profile_verified_cd),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ProfileStatsRow(
    stats: ProfileStats,
    onEditProfile: () -> Unit,
    signedIn: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                shape = RoundedCornerShape(20.dp),
            )
            .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatTile(value = "${stats.hoursListened}h", label = stringResource(R.string.stat_hours_listened))
        StatDivider()
        StatTile(value = formatThousandsLong(stats.tracksPlayed), label = stringResource(R.string.stat_tracks_played))
        StatDivider()
        StatTile(value = stats.playlists.toString(), label = stringResource(R.string.stat_playlists))
        StatDivider()
        StatTile(value = stats.sources.toString(), label = stringResource(R.string.stat_sources))
    }
}

@Composable
private fun StatTile(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(28.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
    )
}

private fun formatThousands(value: Int): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 1_000 -> "%.1fk".format(value / 1_000.0)
    else -> value.toString()
}

private fun formatThousandsLong(value: Long): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 1_000 -> "%.1fk".format(value / 1_000.0)
    else -> value.toString()
}

// -----------------------------------------------------------------------------
// Section: tabs
// -----------------------------------------------------------------------------

@Composable
private fun ProfileTabsRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabPill(
            label = stringResource(R.string.profile_tab_feed),
            icon = Icons.Rounded.ChatBubbleOutline,
            selected = true,
            onClick = { /* Feed is the only tab wired in Phase 1B */ },
        )
        TabPill(
            label = stringResource(R.string.profile_tab_challenge),
            icon = Icons.Rounded.EmojiEvents,
            selected = false,
            onClick = { /* Challenge → Phase 2 */ },
        )
        TabPill(
            label = stringResource(R.string.profile_tab_badge),
            icon = Icons.Rounded.Badge,
            selected = false,
            onClick = { /* Badges → Phase 2 */ },
        )
    }
}

@Composable
private fun TabPill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) BrandLime else Color.Transparent
    val fg = if (selected) BrandLimeOn else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
        )
    }
}

// -----------------------------------------------------------------------------
// Section: section header
// -----------------------------------------------------------------------------

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

// -----------------------------------------------------------------------------
// Section: sample post card
// -----------------------------------------------------------------------------

@Composable
private fun SamplePostCard(
    authorName: String,
    authorHandle: String,
    timeAgo: String,
    body: String,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(BrandLimeSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = (authorName.firstOrNull() ?: 'A').uppercaseChar().toString(),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = BrandLimeOn,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = authorName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = if (authorHandle.isNotBlank()) "$authorHandle · $timeAgo" else timeAgo,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.MoreHoriz,
                    contentDescription = stringResource(R.string.profile_post_menu_cd),
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Reaction(
                    icon = Icons.Rounded.ChatBubbleOutline,
                    count = "12",
                    contentDescription = stringResource(R.string.profile_post_comments_cd),
                )
                Spacer(Modifier.width(20.dp))
                Reaction(
                    icon = Icons.Rounded.FavoriteBorder,
                    count = "40",
                    contentDescription = stringResource(R.string.profile_post_likes_cd),
                )
            }
        }
    }
}

@Composable
private fun Reaction(
    icon: ImageVector,
    count: String,
    contentDescription: String?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = count,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
    }
}

// -----------------------------------------------------------------------------
// Section: empty state card
// -----------------------------------------------------------------------------

@Composable
private fun EmptyStateCard(title: String, body: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

// -----------------------------------------------------------------------------
// File footer
// -----------------------------------------------------------------------------
//
// Usage:
//   Mounted from MainActivity when the user taps "Profile" tab.
//
// Versioning:
//   v2.0 — Phase 1B Profile redesign.
//

