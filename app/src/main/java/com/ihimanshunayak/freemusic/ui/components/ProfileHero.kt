/*
 * Copyright (c) A|iens. All rights reserved.
 *
 * Name:    ProfileHero
 * Version: 2.0.0
 * Author:  A'nil Cyborg
 * Author URI: https://github.com/ihimanshunayak
 *
 * Description:
 *   The profile header at the top of the Account & Integrations screen.
 *
 *   v1 drew its own page: a blurred artwork wash at the top, the identity
 *   in the middle, and the four numbers below it. It was never mounted —
 *   the page carried a flat copy of its own instead — so the glass and the
 *   artwork tint were dead code.
 *
 *   v2 splits the page in two:
 *     - The screen paints the backdrop (the avatar's own colours, washed
 *       across the page, the way Home and Explore paint theirs) so the
 *       picture can run under the top bar.
 *     - This composable draws only what sits on top of that backdrop.
 *
 *   And it fixes three things the flat copy got wrong:
 *     - The lime tick is drawn for a signed-in account only. Before, it
 *       was drawn for a signed-out one too, which claimed something the
 *       app could not know.
 *     - The handle (@name) is shown, and it is the one the user set.
 *     - The signed-out branch is actionable: it says how to sign in and
 *       the button actually does it.
 *
 *   The treatment is dynamic:
 *     - The hero's tint comes from [rememberArtworkPalette] over the
 *       avatar URL — the same palette the album, artist and playlist pages
 *       use for their own tint, so an orange avatar leaves the hero warm
 *       and a grey one leaves it slate.
 *     - The four stat tiles, the streak chip, the status pill and the edit
 *       button are all liquid glass, picking up the same artwork-derived
 *       palette through the [liquidGlass] modifier. On pre-Android-12
 *       devices, or when the user has switched liquid glass off, they fall
 *       back to a translucent surface that still reads as "the same hero,
 *       just not glass".
 *
 * Responsibilities:
 *   - Draw the avatar, the identity lines, the four stat tiles, the
 *     streak chip, the bio and the edit affordance.
 *   - Drive the "Edit profile" and "Sign in" affordances.
 *   - Stay out of the scrobbling, Discord and ListenBrainz groups. Those
 *     live in the screen that hosts this composable.
 *
 * Dependencies:
 *   - rememberArtworkPalette (ui/theme/ArtworkPalette)
 *   - Modifier.liquidGlass (ui/components/LiquidGlass)
 *   - AppSettings profile overrides (data/settings/AppSettings)
 *   - ProfileStats (data/profile/ProfileStats)
 *   - rememberHaptics + Haptic.Select (ui/haptics)
 *
 * Integration Notes:
 *   The screen hosts this and the wiring for [onEditProfile] and
 *   [onSignIn]. The dialogs are opened from there too, so this composable
 *   does not own the dialog state.
 */
package com.ihimanshunayak.freemusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.ihimanshunayak.freemusic.R
import com.ihimanshunayak.freemusic.data.model.Account
import com.ihimanshunayak.freemusic.data.profile.ProfileStats
import com.ihimanshunayak.freemusic.data.settings.AppSettings
import com.ihimanshunayak.freemusic.ui.haptics.Haptic
import com.ihimanshunayak.freemusic.ui.haptics.rememberHaptics
import com.ihimanshunayak.freemusic.ui.theme.BrandLime
import com.ihimanshunayak.freemusic.ui.theme.BrandLimeOn
import com.ihimanshunayak.freemusic.ui.theme.rememberArtworkPalette
import java.util.Locale

/**
 * The hero. Public composable for the Account & Integrations screen.
 *
 * @param signedIn   Whether the user has a YouTube account signed in. The
 *                   hero's signed-in body is hidden when false and the
 *                   signed-out card is drawn instead.
 * @param account    The signed-in account, if any. Provides the default
 *                   display name, email and avatar when the user has not
 *                   overridden them.
 * @param stats      The numbers. Pass [rememberProfileStats] in.
 * @param onEditProfile Invoked when the user taps "Edit profile" or the
 *                   status pill. The host is responsible for showing the
 *                   dialog.
 * @param onSignIn   Invoked when the user taps the sign-in button on the
 *                   signed-out card. Defaults to doing nothing, so a host
 *                   with no sign-in route can leave it out.
 * @param modifier   Passed straight through to the outer container.
 */
@Composable
fun ProfileHero(
    signedIn: Boolean,
    account: Account?,
    currentTrackTitle: String? = null,
    stats: ProfileStats,
    onEditProfile: () -> Unit,
    onSignIn: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (!signedIn) {
        SignedOutHero(onSignIn = onSignIn, modifier = modifier)
        return
    }
    SignedInHero(
        account = account,
        currentTrackTitle = currentTrackTitle,
        stats = stats,
        onEditProfile = onEditProfile,
        modifier = modifier,
    )
}

// ── Signed in ────────────────────────────────────────────────────────────────

@Composable
private fun SignedInHero(
    account: Account?,
    currentTrackTitle: String?,
    stats: ProfileStats,
    onEditProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Diagnostic marker — see the file footer. Tracks the path through the
    // page so a crash on Account page open can be narrowed to either
    // AccountAndScrobblingScreen itself or to ProfileHero composition.
    android.util.Log.d("Account", "SignedInHero compose: avatarOverride present? name=${account?.name}")
    val haptics = rememberHaptics()
    val displayNameOverride by AppSettings.displayNameOverride.collectAsStateWithLifecycle()
    val usernameOverride by AppSettings.usernameOverride.collectAsStateWithLifecycle()
    val statusOverride by AppSettings.statusLineOverride.collectAsStateWithLifecycle()
    val bioOverride by AppSettings.bioOverride.collectAsStateWithLifecycle()
    val avatarOverride by AppSettings.avatarOverrideUri.collectAsStateWithLifecycle()

    val effectiveName = displayNameOverride.takeIf { it.isNotBlank() }
        ?: account?.name?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.signed_in)
    val effectiveHandle = usernameOverride.takeIf { it.isNotBlank() }?.let { "@$it" }
    val effectiveStatus = statusOverride.takeIf { it.isNotBlank() }
        ?: currentTrackTitle?.takeIf { it.isNotBlank() }?.let {
            stringResource(R.string.profile_status_listening_to, it)
        }
        ?: stringResource(R.string.profile_status_default)
    val effectiveBio = bioOverride.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.profile_bio_default)
    val effectiveAvatar = avatarOverride.takeIf { it.isNotBlank() }
        ?: account?.thumbnailUrl

    val palette = rememberArtworkPalette(effectiveAvatar)
    val context = LocalContext.current
    val avatarRequest = remember(effectiveAvatar) {
        ImageRequest.Builder(context)
            .data(effectiveAvatar)
            .crossfade(true)
            .build()
    }

    val contentColor = palette.onBackground
    val onPaletteAccent = palette.accent

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Avatar ring — the avatar itself, with a glass-coloured outer ring.
        // On Android 12+ with liquid glass on, the ring itself is glass;
        // otherwise it is a hairline border.
        Box(
            modifier = Modifier
                .size(112.dp)
                .clip(CircleShape)
                .then(
                    if (isLiquidGlassEnabled()) {
                        Modifier.liquidGlass(CircleShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (effectiveAvatar != null) {
                AsyncImage(
                    model = avatarRequest,
                    contentDescription = stringResource(R.string.edit_profile_avatar_alt),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(104.dp)
                        .clip(CircleShape)
                        .border(2.dp, onPaletteAccent.copy(alpha = 0.6f), CircleShape),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .clip(CircleShape)
                        .background(palette.elevated),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = onPaletteAccent,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
            // The lime tick. Only ever drawn for a signed-in account, and
            // only because this branch is the signed-in branch — the flat
            // copy drew it for a signed-out profile too, which claimed
            // something the app could not know.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(BrandLime)
                    .border(2.dp, palette.background, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Verified,
                    contentDescription = stringResource(R.string.profile_verified_cd),
                    tint = BrandLimeOn,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = effectiveName,
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 30.sp,
                lineHeight = 34.sp,
            ),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (effectiveHandle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = effectiveHandle,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        account?.email?.takeIf { it.isNotBlank() }?.let { email ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = email,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(12.dp))
        // Status pill — a single short sentence, glass.
        StatusPill(
            text = effectiveStatus,
            contentColor = contentColor,
            onClick = onEditProfile,
        )
        Spacer(Modifier.height(20.dp))
        StatGrid(stats = stats, onPalette = palette.onBackground)
        Spacer(Modifier.height(10.dp))
        StreakChip(
            streak = stats.streak,
            contentColor = contentColor,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = effectiveBio,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.onBackgroundVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        EditProfileButton(
            contentColor = contentColor,
            onClick = {
                haptics.play(Haptic.Select)
                onEditProfile()
            },
        )
    }
}

@Composable
private fun StatusPill(
    text: String,
    contentColor: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val base = Modifier
        .clip(shape)
        .then(
            if (isLiquidGlassEnabled()) {
                Modifier.liquidGlass(shape)
            } else {
                Modifier.background(
                    color = contentColor.copy(alpha = 0.10f),
                    shape = shape,
                )
            },
        )
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 8.dp)
    Row(
        modifier = base,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // A small accent dot — the only piece of pure accent colour in the
        // hero, the way the reference image's status pill carries the page's
        // own yellow.
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(contentColor),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.W600,
            ),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The listening streak, as a chip under the four numbers.
 *
 * A streak of zero is not drawn at all: an empty chip reading "0-day
 * streak" is a small discouragement dressed as a fact, and there is
 * nothing to say until there is one.
 */
@Composable
private fun StreakChip(
    streak: Int,
    contentColor: Color,
) {
    if (streak <= 0) return
    val shape = RoundedCornerShape(50)
    val base = Modifier
        .clip(shape)
        .then(
            if (isLiquidGlassEnabled()) {
                Modifier.liquidGlass(shape)
            } else {
                Modifier.background(
                    color = contentColor.copy(alpha = 0.10f),
                    shape = shape,
                )
            },
        )
        .padding(horizontal = 16.dp, vertical = 9.dp)
    Row(
        modifier = base,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.LocalFireDepartment,
            contentDescription = null,
            tint = BrandLime,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(R.string.profile_streak_days, streak),
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.W700,
            ),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatGrid(
    stats: ProfileStats,
    onPalette: Color,
) {
    val tiles = listOf(
        StatTile(
            label = stringResource(R.string.stat_hours_listened),
            value = formatCount(stats.hoursListened, "h"),
            inFlight = stats.inFlight,
        ),
        StatTile(
            label = stringResource(R.string.stat_tracks_played),
            value = formatCount(stats.tracksPlayed, ""),
            inFlight = stats.inFlight,
        ),
        StatTile(
            label = stringResource(R.string.stat_playlists),
            value = formatCount(stats.playlists.toLong(), ""),
            inFlight = false,
        ),
        StatTile(
            label = stringResource(R.string.stat_sources),
            value = formatCount(stats.sources.toLong(), ""),
            inFlight = false,
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 2x2 grid — same layout as the reference image. Each tile is
        // half the row width minus half the row gap.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles[0].StatTileBox(onPalette, modifier = Modifier.weight(1f))
            tiles[1].StatTileBox(onPalette, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles[2].StatTileBox(onPalette, modifier = Modifier.weight(1f))
            tiles[3].StatTileBox(onPalette, modifier = Modifier.weight(1f))
        }
    }
}

private data class StatTile(
    val label: String,
    val value: String,
    val inFlight: Boolean,
)

@Composable
private fun StatTile.StatTileBox(
    onPalette: Color,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    val base = modifier
        .heightIn(min = 78.dp)
        .clip(shape)
        .then(
            if (isLiquidGlassEnabled()) {
                Modifier.liquidGlass(shape)
            } else {
                Modifier.background(
                    color = onPalette.copy(alpha = 0.08f),
                    shape = shape,
                )
            },
        )
        .padding(horizontal = 14.dp, vertical = 12.dp)
    Column(
        modifier = base,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = onPalette.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (inFlight) {
            // Skeleton number — a single line, the same width as a 4-digit
            // number, dimmed. The palette crossfade already animates the
            // colour, so the only animation this page does is the stat
            // appearing once the IO call returns.
            Box(
                modifier = Modifier
                    .height(22.dp)
                    .widthIn(min = 48.dp, max = 88.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(onPalette.copy(alpha = 0.10f)),
            )
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 24.sp,
                ),
                color = onPalette,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EditProfileButton(
    contentColor: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val base = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .heightIn(min = 48.dp)
        .clip(shape)
        .then(
            if (isLiquidGlassEnabled()) {
                Modifier.liquidGlass(shape)
            } else {
                Modifier.background(
                    color = contentColor.copy(alpha = 0.12f),
                    shape = shape,
                )
            },
        )
        .clickable(onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 12.dp)
    Row(
        modifier = base,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Edit,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.edit_profile),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.W700,
            ),
            color = contentColor,
        )
    }
}

// ── Signed out ───────────────────────────────────────────────────────────────

@Composable
private fun SignedOutHero(
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp)
                .clip(shape)
                .background(scheme.surfaceVariant)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(scheme.outline),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.not_signed_in),
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.tap_to_sign_in_google),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            // The card used to stop at the explanation and leave nothing to
            // act on: the profile page was the one page you could not sign
            // in from.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(BrandLime)
                    .clickable(onClick = onSignIn)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(
                    text = stringResource(R.string.sign_in),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.W700,
                    ),
                    color = BrandLimeOn,
                )
            }
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

// Whether a glass surface is drawn at all is answered by the shared
// [isLiquidGlassEnabled]: the app-wide switch, the user's own setting and the
// device's support all have to agree before a surface samples the backdrop.

/**
 * Right-aligned counts — short numbers stay tight, long ones pick up the
 * grouping comma the page already uses elsewhere.
 */
private fun formatCount(value: Long, suffix: String): String {
    val withSep = String.format(Locale.getDefault(), "%,d", value)
    return if (suffix.isEmpty()) withSep else "$withSep$suffix"
}

// ──────────────────────────────────────────────────────────────────────────────
// CodeFooter
// ──────────────────────────────────────────────────────────────────────────────
//
// Usage:
//   val stats = rememberProfileStats(signedIn = signedIn)
//   ProfileHero(
//       signedIn = signedIn,
//       account = account,
//       stats = stats,
//       onEditProfile = { showEditProfile = true },
//       onSignIn = { showSignIn = true },
//   )
//
// Troubleshooting:
//   - Hero is solid black even with an avatar: the artwork palette's seed
//     cache missed (the avatar URL has not been seen before). Open the
//     album page the avatar came from and back; the next read hits the
//     cache and the hero tints on the frame the page returns to.
//   - Status pill does not react to the tap: this composable does not own
//     the dialog — wire [onEditProfile] to your host's `showEditProfile`
//     state. The button's own click handler is wired in [EditProfileButton].
//   - "Hours listened" is 0: stats come from `ListeningStats` which only
//     starts writing once PlaybackService has had a turn. Open Replay or
//     play a track and the number appears.
//   - The numbers stay at 0 after playing: the stats are read from the
//     device's own listening history and refresh when the
//     `ListeningStats.revision` counter moves. If they do not move,
//     check that playback is actually running — a paused player records
//     nothing.
//   - No streak chip: a streak of zero is not drawn at all. Play something
//     today and the chip appears; it disappears after a day with nothing
//     in it.
//   - Page crashes on open with no logcat: the hero and the host page
//     have `Log.d` markers on tag "Account" that fire before any other
//     work. Filter logcat on that tag to see how far the page got
//     before the crash.
//
// Versioning notes:
//   1.0.0 — initial cut. Signed-in / signed-out branches, 2x2 stat grid,
//   glass status pill, glass edit button.
//   1.0.1 — logcat markers on tag "Account" at compose entry of
//   `AccountAndScrobblingScreen`, `SignedInHero`, and `EditProfileDialog`
//   for easier crash diagnosis.
//   2.0.0 — the backdrop moved to the hosting screen so the picture can run
//   under the top bar; the handle and a signed-in-only tick were added; the
//   streak chip was added; the signed-out card gained a sign-in button.
//
// Extension hooks:
//   - A "share my profile" tile goes beneath the edit button as another
//     [SettingsGroup] row in the host screen; this composable does not
//     own a share affordance.
//   - A "current mood" tile is one more entry in the [StatGrid] rows; the
//     function is already parameterised on the tile list.
//
// Future compatibility notes:
//   - [rememberArtworkPalette] returns theme-derived colours when the
//     avatar URL is null, so the signed-out branch never sees a null
//     palette. The branch still reads [MaterialTheme.colorScheme] directly
//     to keep its look aligned with the rest of the app rather than with
//     a non-existent artwork.
//   - `Modifier.liquidGlass` is API 31+; the [isLiquidGlassEnabled] gate
//     falls back to a flat translucent surface for older devices. The
//     fallback is the same shape, the same colour family, and the same
//     padding — so a user upgrading their phone does not see a layout
//     jump, just glass appearing.
//   - This composable paints no background of its own since v2.0.0. A host
//     that wants the wash has to paint it, or the glass surfaces will have
//     nothing behind them to sample.
