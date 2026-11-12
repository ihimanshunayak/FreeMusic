// Copyright (c) A|iens. All rights reserved.

/*
 * FreeMusic — Account & Integrations (Profile) screen
 * ----------------------------------------------------------------------
 * Phase: Phase 3 (Profile) — the artwork-led redesign.
 *
 * Design intent:
 *   - The page wears the avatar's own colours: a blurred wash of it at
 *     the top, running under the top bar, the way Home and Explore run
 *     theirs. The glass hero sits on top of that wash.
 *   - The identity: avatar, display name, handle, status pill, the four
 *     lifetime numbers, the listening streak and the bio.
 *   - Real data below it: the most-played tracks (ranked, with covers),
 *     the listening insights, and the badges those numbers have earned.
 *   - The integrations the page was named for: Discord rich presence,
 *     ListenBrainz, Last.fm, and the scrobble timing behind them.
 *
 * What this replaced:
 *   - A flat duplicate of the hero, with the lime "verified" tick drawn
 *     even for a signed-out profile.
 *   - A hard-coded notification badge of "5".
 *   - A sample post about a landscape, and a fake like count of 40.
 *   - Three tab pills that did nothing.
 *   - The integrations themselves, which had been dropped from the page
 *     even though the page was still called "Account & Integrations".
 *
 * Brand: BrandLime = #BFFF00 — brand mark, not Material 3 primary role.
 */
package com.ihimanshunayak.freemusic.ui.screens

import android.util.Log
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwitchAccount
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.ihimanshunayak.freemusic.R
import com.ihimanshunayak.freemusic.data.model.Account
import com.ihimanshunayak.freemusic.data.model.artworkAt
import com.ihimanshunayak.freemusic.data.model.ROW_ART_PX
import com.ihimanshunayak.freemusic.data.profile.ProfileStats
import com.ihimanshunayak.freemusic.data.profile.ProfileTopSong
import com.ihimanshunayak.freemusic.data.settings.AppSettings
import com.ihimanshunayak.freemusic.ui.components.ArtworkBackdrop
import com.ihimanshunayak.freemusic.ui.components.ProfileHero
import com.ihimanshunayak.freemusic.ui.components.isLiquidGlassEnabled
import com.ihimanshunayak.freemusic.ui.components.liquidGlass
import com.ihimanshunayak.freemusic.ui.replay.formatDay
import com.ihimanshunayak.freemusic.ui.replay.formatHour
import com.ihimanshunayak.freemusic.ui.replay.grouped
import com.ihimanshunayak.freemusic.ui.theme.BrandLime
import com.ihimanshunayak.freemusic.ui.theme.BrandLimeOn
import com.ihimanshunayak.freemusic.ui.theme.rememberArtworkPalette
import kotlin.math.roundToInt

// -----------------------------------------------------------------------------
// Public composable
// -----------------------------------------------------------------------------

/**
 * Profile screen.
 *
 * @param signedIn    Whether a Google account is signed in.
 * @param account     The signed-in account, if any.
 * @param channelName The YouTube channel the user is listening as.
 * @param stats       The lifetime numbers, the streak and the top tracks.
 * @param onPlayTopSong Invoked when a track in the "top songs" list is
 *                    tapped. The host starts a station off that track.
 * @param onOpenReplay Invoked when the insights card is tapped, so the user
 *                    can go and see the whole story behind the numbers.
 */
@Composable
fun AccountAndScrobblingScreen(
    signedIn: Boolean,
    account: Account?,
    channelName: String?,
    currentTrackTitle: String? = null,
    stats: ProfileStats,
    onSignIn: () -> Unit,
    onSwitchChannel: () -> Unit,
    onSwitchGoogleAccount: () -> Unit = {},
    onSignOut: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenListenBrainzLogin: () -> Unit,
    onOpenLastfmLogin: () -> Unit,
    onOpenDiscord: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onOpenNotifications: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onPlayTopSong: (ProfileTopSong) -> Unit = {},
    onOpenReplay: () -> Unit = {},
) {
    // Diagnostic marker — see footer.
    Log.d("Account", "AccountAndScrobblingScreen compose: signedIn=$signedIn account=${account?.name}")

    // The page's own palette. The avatar's colours are the page's colours,
    // so the wash at the top and the identity below it belong to the same
    // picture rather than to two unrelated surfaces.
    val avatarOverride by AppSettings.avatarOverrideUri.collectAsStateWithLifecycle()
    val avatarUrl = remember(avatarOverride, account?.thumbnailUrl) {
        avatarOverride.ifBlank { account?.thumbnailUrl.orEmpty() }
    }
    val palette = rememberArtworkPalette(avatarUrl.takeIf { it.isNotBlank() })

    // Every integration this page owns. Read live, so a connection made
    // here shows as connected here without the page having to be reopened.
    val lastfmEnabled by AppSettings.lastfmEnabled.collectAsStateWithLifecycle()
    val lastfmUsername by AppSettings.lastfmUsername.collectAsStateWithLifecycle()
    val lastfmSessionKey by AppSettings.lastfmSessionKey.collectAsStateWithLifecycle()
    val lastfmScrobbleEnabled by AppSettings.lastfmScrobbleEnabled.collectAsStateWithLifecycle()
    val lastfmNowPlayingEnabled by AppSettings.lastfmNowPlaying.collectAsStateWithLifecycle()
    val lastfmPrimaryArtistOnly by AppSettings.lastfmPrimaryArtistOnly.collectAsStateWithLifecycle()
    val scrobbleMinDuration by AppSettings.scrobbleMinDuration.collectAsStateWithLifecycle()
    val scrobbleDelayPercent by AppSettings.scrobbleDelayPercent.collectAsStateWithLifecycle()
    val scrobbleDelaySeconds by AppSettings.scrobbleDelaySeconds.collectAsStateWithLifecycle()
    val listenBrainzEnabled by AppSettings.listenBrainzEnabled.collectAsStateWithLifecycle()
    val listenBrainzToken by AppSettings.listenBrainzToken.collectAsStateWithLifecycle()
    val listenBrainzPrimaryArtistOnly by AppSettings.listenBrainzPrimaryArtistOnly.collectAsStateWithLifecycle()
    val discordToken by AppSettings.discordToken.collectAsStateWithLifecycle()
    val discordUsername by AppSettings.discordUsername.collectAsStateWithLifecycle()
    val discordRpcEnabled by AppSettings.discordRpcEnabled.collectAsStateWithLifecycle()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        // The wash at the top of the page: the avatar, blurred to a colour.
        // It runs under the top bar rather than starting below it, which
        // is why this page is treated as artwork-led by the host — the
        // upside-down top scrim would paint a solid band straight over it.
        ArtworkBackdrop(
            palette = palette,
            imageUrl = avatarUrl.takeIf { it.isNotBlank() },
            modifier = Modifier.matchParentSize(),
            washFraction = PROFILE_WASH_FRACTION,
            artPx = PROFILE_HEADER_ART_PX,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 32.dp,
            ),
        ) {
            item("hero") {
                ProfileHero(
                    signedIn = signedIn,
                    account = account,
                    currentTrackTitle = currentTrackTitle,
                    stats = stats,
                    onEditProfile = onEditProfile,
                    onSignIn = onSignIn,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            // Listening stats are device-local — they come from
            // ListeningStats on this handset, not from the Google account.
            // Hiding them behind a sign-in therefore showed an empty page
            // to a visitor who already has a listening history on the
            // device. They are shown either way; only the account-bound
            // rows below stay behind the sign-in.
            item("top-songs-header") {
                SectionHeader(label = stringResource(R.string.top_songs))
            }
            if (stats.topSongs.isEmpty()) {
                item("top-songs-empty") {
                    EmptyStateCard(
                        title = stringResource(R.string.profile_top_songs_empty),
                        body = stringResource(R.string.profile_top_songs_empty_hint),
                    )
                }
            } else {
                items(
                    count = stats.topSongs.size,
                    key = { index -> stats.topSongs[index].videoId },
                ) { index ->
                    TopSongRow(
                        song = stats.topSongs[index],
                        rank = index + 1,
                        onClick = { onPlayTopSong(stats.topSongs[index]) },
                    )
                }
            }

            item("insights-header") {
                SectionHeader(label = stringResource(R.string.profile_insights))
            }
            item("insights") {
                InsightsCard(
                    stats = stats,
                    onClick = onOpenReplay,
                )
            }

            item("badges-header") {
                SectionHeader(label = stringResource(R.string.profile_badges))
            }
            item("badges") {
                BadgesCard(stats = stats)
            }

            if (signedIn) {
                item("account-group") {
                    SettingsGroup(
                        footer = stringResource(R.string.account_profiles_help),
                    ) {
                        SettingsRow(
                            icon = Icons.Rounded.SwitchAccount,
                            title = stringResource(R.string.listen_as),
                            subtitle = channelName ?: stringResource(R.string.default_youtube_profile),
                            onClick = onSwitchChannel,
                        )
                        RowDivider()
                        // The Google account on the device is a different
                        // thing from the YouTube channel inside it, and it
                        // needs its own way in: without this row the account
                        // selector had no route at all once the top-bar
                        // button was repointed at the profile page.
                        SettingsRow(
                            icon = Icons.Rounded.AccountCircle,
                            title = stringResource(R.string.switch_account),
                            onClick = onSwitchGoogleAccount,
                        )
                        RowDivider()
                        SettingsRow(
                            icon = Icons.Rounded.Notifications,
                            title = stringResource(R.string.notifications),
                            onClick = onOpenNotifications,
                        )
                        RowDivider()
                        SettingsRow(
                            icon = Icons.Rounded.Settings,
                            title = stringResource(R.string.settings),
                            onClick = onOpenSettings,
                        )
                    }
                }

                item("sign-out-group") {
                    SettingsGroup {
                        DestructiveRow(label = stringResource(R.string.sign_out), onClick = onSignOut)
                    }
                }
            } else {
                // The signed-out visitor still needs a way into Settings and
                // Notifications from this page — otherwise the whole
                // navigation lives behind a sign-in the visitor may not want.
                // The same two rows the signed-in branch carries, so the page
                // reads the same either way.
                item("quick-actions-group") {
                    SettingsGroup {
                        SettingsRow(
                            icon = Icons.Rounded.Notifications,
                            title = stringResource(R.string.notifications),
                            onClick = onOpenNotifications,
                        )
                        RowDivider()
                        SettingsRow(
                            icon = Icons.Rounded.Settings,
                            title = stringResource(R.string.settings),
                            onClick = onOpenSettings,
                        )
                    }
                }
            }

            item("discord-group") {
                SettingsGroup(
                    header = stringResource(R.string.rich_presence),
                    footer = stringResource(R.string.rich_presence_footer),
                ) {
                    SettingsRow(
                        icon = ImageVector.vectorResource(R.drawable.ic_discord),
                        title = "Discord",
                        subtitle = when {
                            discordToken.isEmpty() -> stringResource(R.string.tap_to_connect)
                            !discordRpcEnabled -> stringResource(R.string.connected_presence_off)
                            discordUsername.isNotEmpty() -> stringResource(R.string.sharing_as, discordUsername)
                            else -> stringResource(R.string.sharing_listens)
                        },
                        onClick = onOpenDiscord,
                    )
                }
            }

            if (AppSettings.scrobblingAvailable) {
                item("scrobbling-group") {
                    SettingsGroup(
                        header = stringResource(R.string.scrobbling),
                        footer = stringResource(R.string.scrobbling_footer),
                    ) {
                        SettingsRow(
                            icon = Icons.Rounded.Cloud,
                            title = "ListenBrainz",
                            subtitle = if (listenBrainzEnabled && listenBrainzToken.isNotBlank()) {
                                stringResource(R.string.connected)
                            } else {
                                stringResource(R.string.enter_token_to_enable)
                            },
                            trailing = {
                                Switch(
                                    checked = listenBrainzEnabled,
                                    onCheckedChange = { checked ->
                                        if (checked && listenBrainzToken.isBlank()) {
                                            onOpenListenBrainzLogin()
                                        } else {
                                            AppSettings.setListenBrainzEnabled(checked)
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                                        checkedBorderColor = MaterialTheme.colorScheme.primary,
                                    ),
                                )
                            },
                            onClick = onOpenListenBrainzLogin,
                        )
                        if (listenBrainzEnabled && listenBrainzToken.isNotBlank()) {
                            RowDivider()
                            SettingsRow(
                                icon = Icons.Rounded.GraphicEq,
                                title = stringResource(R.string.scrobble_primary_artist_only),
                                subtitle = stringResource(R.string.scrobble_primary_artist_only_subtitle),
                                trailing = {
                                    Switch(
                                        checked = listenBrainzPrimaryArtistOnly,
                                        onCheckedChange = AppSettings::setListenBrainzPrimaryArtistOnly,
                                        colors = SwitchDefaults.colors(
                                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                                        ),
                                    )
                                },
                                onClick = {
                                    AppSettings.setListenBrainzPrimaryArtistOnly(!listenBrainzPrimaryArtistOnly)
                                },
                            )
                        }
                        RowDivider()
                        SettingsRow(
                            icon = Icons.Rounded.History,
                            title = "Last.fm",
                            subtitle = if (lastfmSessionKey.isNotBlank()) {
                                stringResource(R.string.signed_in_as, lastfmUsername)
                            } else {
                                stringResource(R.string.tap_to_sign_in)
                            },
                            trailing = {
                                Switch(
                                    checked = lastfmEnabled,
                                    onCheckedChange = { checked ->
                                        if (checked && lastfmSessionKey.isBlank()) {
                                            onOpenLastfmLogin()
                                        } else {
                                            AppSettings.setLastfmEnabled(checked)
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                                        checkedBorderColor = MaterialTheme.colorScheme.primary,
                                    ),
                                )
                            },
                            onClick = {
                                if (lastfmSessionKey.isNotBlank()) {
                                    AppSettings.setLastfmSessionKey("")
                                    AppSettings.setLastfmUsername("")
                                    AppSettings.setLastfmEnabled(false)
                                    AppSettings.setLastfmScrobbleEnabled(false)
                                    AppSettings.setLastfmNowPlaying(false)
                                } else {
                                    onOpenLastfmLogin()
                                }
                            },
                        )
                        if (lastfmEnabled && lastfmSessionKey.isNotBlank()) {
                            RowDivider()
                            SettingsRow(
                                icon = Icons.Rounded.GraphicEq,
                                title = stringResource(R.string.scrobble_tracks),
                                subtitle = stringResource(R.string.scrobble_tracks_subtitle),
                                trailing = {
                                    Switch(
                                        checked = lastfmScrobbleEnabled,
                                        onCheckedChange = AppSettings::setLastfmScrobbleEnabled,
                                        colors = SwitchDefaults.colors(
                                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                                        ),
                                    )
                                },
                                onClick = {
                                    AppSettings.setLastfmScrobbleEnabled(!lastfmScrobbleEnabled)
                                },
                            )
                            RowDivider()
                            SettingsRow(
                                icon = Icons.Rounded.GraphicEq,
                                title = stringResource(R.string.scrobble_primary_artist_only),
                                subtitle = stringResource(R.string.scrobble_primary_artist_only_subtitle),
                                trailing = {
                                    Switch(
                                        checked = lastfmPrimaryArtistOnly,
                                        onCheckedChange = AppSettings::setLastfmPrimaryArtistOnly,
                                        colors = SwitchDefaults.colors(
                                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                                        ),
                                    )
                                },
                                onClick = {
                                    AppSettings.setLastfmPrimaryArtistOnly(!lastfmPrimaryArtistOnly)
                                },
                            )
                            RowDivider()
                            SettingsRow(
                                icon = Icons.Rounded.GraphicEq,
                                title = stringResource(R.string.now_playing),
                                subtitle = stringResource(R.string.lastfm_now_playing_subtitle),
                                trailing = {
                                    Switch(
                                        checked = lastfmNowPlayingEnabled,
                                        onCheckedChange = AppSettings::setLastfmNowPlaying,
                                        colors = SwitchDefaults.colors(
                                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                                        ),
                                    )
                                },
                                onClick = { AppSettings.setLastfmNowPlaying(!lastfmNowPlayingEnabled) },
                            )
                        }
                    }
                }

                if (lastfmEnabled && lastfmSessionKey.isNotBlank()) {
                    item("scrobble-timing-group") {
                        SettingsGroup(header = stringResource(R.string.scrobble_timing)) {
                            SliderRow(
                                icon = Icons.Rounded.Tune,
                                title = stringResource(R.string.min_song_duration),
                                subtitle = stringResource(R.string.min_song_duration_subtitle),
                                value = "${scrobbleMinDuration}s",
                                sliderValue = scrobbleMinDuration.toFloat(),
                                onSliderValue = { AppSettings.setScrobbleMinDuration(it.roundToInt()) },
                                valueRange = 15f..120f,
                                steps = 20,
                            )
                            RowDivider()
                            SliderRow(
                                icon = Icons.Rounded.Tune,
                                title = stringResource(R.string.scrobble_delay),
                                subtitle = stringResource(R.string.scrobble_delay_subtitle),
                                value = "${(scrobbleDelayPercent * 100).roundToInt()}%",
                                sliderValue = scrobbleDelayPercent,
                                onSliderValue = { AppSettings.setScrobbleDelayPercent(it) },
                                valueRange = 0.1f..1.0f,
                                steps = 8,
                            )
                            RowDivider()
                            SliderRow(
                                icon = Icons.Rounded.Tune,
                                title = stringResource(R.string.max_delay),
                                subtitle = stringResource(R.string.max_delay_subtitle),
                                value = "${scrobbleDelaySeconds}s",
                                sliderValue = scrobbleDelaySeconds.toFloat(),
                                onSliderValue = { AppSettings.setScrobbleDelaySeconds(it.roundToInt()) },
                                valueRange = 30f..300f,
                                steps = 26,
                            )
                        }
                    }
                }
            }
        }
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
// Section: top songs
// -----------------------------------------------------------------------------

/**
 * One of the user's most-played tracks.
 *
 * The count is the whole point of the row, so it is on the right where a
 * chart's column of numbers would be, and the cover is on the left because
 * a list of five words is not a list of five songs.
 */
@Composable
private fun TopSongRow(
    song: ProfileTopSong,
    rank: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W800),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
            modifier = Modifier.width(24.dp),
        )
        Spacer(Modifier.width(10.dp))
        AsyncImage(
            model = song.artworkUrl?.artworkAt(ROW_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (song.artist.isNotBlank()) {
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.card_plays_format, grouped(song.plays.toLong())),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            maxLines = 1,
        )
    }
}

// -----------------------------------------------------------------------------
// Section: insights
// -----------------------------------------------------------------------------

/**
 * The four facts about *when* and *how* the user listens.
 *
 * All of them come off the same lifetime summary the hero's numbers do, so
 * nothing here is a second query and nothing can disagree with the hero.
 */
@Composable
private fun InsightsCard(
    stats: ProfileStats,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val tiles = listOf(
        InsightTile(
            icon = Icons.Rounded.LocalFireDepartment,
            label = stringResource(R.string.profile_insight_streak),
            value = if (stats.streak > 0) {
                stringResource(R.string.profile_streak_days, stats.streak)
            } else {
                stringResource(R.string.profile_insight_none)
            },
        ),
        InsightTile(
            icon = Icons.Rounded.Schedule,
            label = stringResource(R.string.profile_insight_peak_hour),
            value = stats.peakHour?.let { formatHour(context, it) }
                ?: stringResource(R.string.profile_insight_none),
        ),
        InsightTile(
            icon = Icons.Rounded.EmojiEvents,
            label = stringResource(R.string.profile_insight_busiest_day),
            value = stats.busiestDay?.let { formatDay(context, it) }
                ?: stringResource(R.string.profile_insight_none),
        ),
        InsightTile(
            icon = Icons.Rounded.GraphicEq,
            label = stringResource(R.string.profile_insight_unique),
            value = if (stats.uniqueSongs > 0) {
                stringResource(
                    R.string.profile_insight_unique_value,
                    grouped(stats.uniqueSongs.toLong()),
                    grouped(stats.uniqueArtists.toLong()),
                )
            } else {
                stringResource(R.string.profile_insight_none)
            },
        ),
    )

    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .then(
                if (isLiquidGlassEnabled()) {
                    Modifier.liquidGlass(shape)
                } else {
                    Modifier.background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = shape,
                    )
                },
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { tile ->
                    InsightTileBox(
                        tile = tile,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keeps the last row's two tiles the same width as the first
                // row's, rather than the second tile stretching to fill.
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private data class InsightTile(
    val icon: ImageVector,
    val label: String,
    val value: String,
)

@Composable
private fun InsightTileBox(
    tile: InsightTile,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = tile.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = tile.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = tile.value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W700),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// -----------------------------------------------------------------------------
// Section: badges
// -----------------------------------------------------------------------------

/**
 * The badges the user's own numbers have earned.
 *
 * Each one is a threshold, checked against the same summary as everything
 * else, so a badge cannot claim a milestone the numbers do not show. The
 * locked ones are drawn dimmed rather than hidden: the point of a badge is
 * what it takes to get one.
 */
@Composable
private fun BadgesCard(stats: ProfileStats) {
    val badges = listOf(
        ProfileBadge(
            title = stringResource(R.string.profile_badge_starter),
            description = stringResource(R.string.profile_badge_starter_desc),
            earned = stats.tracksPlayed >= STARTER_PLAYS,
            icon = Icons.Rounded.GraphicEq,
        ),
        ProfileBadge(
            title = stringResource(R.string.profile_badge_listener),
            description = stringResource(R.string.profile_badge_listener_desc),
            earned = stats.hoursListened >= LISTENER_HOURS,
            icon = Icons.Rounded.Schedule,
        ),
        ProfileBadge(
            title = stringResource(R.string.profile_badge_streak),
            description = stringResource(R.string.profile_badge_streak_desc),
            earned = stats.streak >= STREAK_DAYS,
            icon = Icons.Rounded.LocalFireDepartment,
        ),
    )

    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 6.dp),
    ) {
        badges.forEach { badge ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (badge.earned) {
                                BrandLime
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (badge.earned) badge.icon else Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = if (badge.earned) {
                            BrandLimeOn
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = badge.title,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = badge.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (badge.earned) {
                        stringResource(R.string.profile_badge_earned)
                    } else {
                        stringResource(R.string.profile_badge_locked)
                    },
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.W700,
                    ),
                    color = if (badge.earned) {
                        BrandLime
                    } else {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

private data class ProfileBadge(
    val title: String,
    val description: String,
    val earned: Boolean,
    val icon: ImageVector,
)

// -----------------------------------------------------------------------------
// Section: empty state card
// -----------------------------------------------------------------------------

@Composable
private fun EmptyStateCard(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(18.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
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

// -----------------------------------------------------------------------------
// Constants
// -----------------------------------------------------------------------------

/**
 * How far down the page the avatar's blur reaches.
 *
 * A third of the screen: enough for the whole identity block to sit on the
 * picture's colour, and not so much that the wash is still colour at the
 * foot of a long page.
 */
private const val PROFILE_WASH_FRACTION = 0.34f

/** The avatar's own size, which is what the page is reading for its palette. */
private const val PROFILE_HEADER_ART_PX = 320

/** Plays needed for the first badge. */
private const val STARTER_PLAYS = 100L

/** Hours needed for the second badge. */
private const val LISTENER_HOURS = 10L

/** Consecutive days needed for the third badge. */
private const val STREAK_DAYS = 7

// ──────────────────────────────────────────────────────────────────────────────
// CodeFooter
// ──────────────────────────────────────────────────────────────────────────────
//
// Usage:
//   AccountAndScrobblingScreen(
//       signedIn = signedIn,
//       account = account,
//       channelName = selectedChannelName,
//       stats = rememberProfileStats(signedIn = signedIn),
//       onSignIn = { webSession = WebSessionMode.SIGN_IN },
//       onSwitchChannel = { viewModel.loadChannels(); showAccountChannels = true },
//       onSignOut = { viewModel.signOut() },
//       onEditProfile = { showEditProfile = true },
//       onOpenListenBrainzLogin = { showListenBrainzLogin = true },
//       onOpenLastfmLogin = { showLastfmLogin = true },
//       onOpenDiscord = { showDiscord = true },
//       onPlayTopSong = { song -> playRadio(song.toSong(), QueueSource(...)) },
//       onOpenReplay = { showReplay = true },
//       contentPadding = listPadding,
//   )
//
// Troubleshooting:
//   - The page's wash is the theme's grey, not the avatar's colour: the
//     avatar URL was never seen before, so the palette's seed cache missed.
//     Open the page whose cover it was and come back.
//   - "Top songs" is empty: the listening-stats folder only starts being
//     written once PlaybackService has had a turn. Play a track and reopen
//     the page.
//   - The streak is zero after a day of listening: the streak counts
//     consecutive days, and a day with nothing in it breaks it. The chip
//     is not drawn at all when the streak is zero.
//   - The integrations are missing: the page shows them only while
//     `AppSettings.scrobblingAvailable` is true. It is a build-time flag.
//   - "Listen as" opens the wrong picker: it must open the YouTube
//     channel picker (`AccountChannelDialog`) for the signed-in account,
//     not the Google account selector (`AccountProfileSelector`) — the
//     latter switches accounts on the device and cannot name a channel.
//
// Versioning notes:
//   1.0.0 — Phase 1B: flat hero, sample post, inert tabs.
//   2.0.0 — Phase 3: glass hero over an avatar wash, real top songs,
//   insights and badges, the integrations restored, the fake badge and
//   sample post gone.
//   3.0.0 — the device-local listening stats (top songs, insights,
//   badges) are shown to a signed-out visitor too; only the account
//   group and the sign-out row remain behind the sign-in. "Listen as"
//   now opens the YouTube channel picker.
//
// Extension hooks:
//   - Another badge is one more entry in the [BadgesCard] list; each one
//     is checked against the same [ProfileStats] the rest of the page uses.
//   - Another insight is one more tile in the [InsightsCard] list.
//   - A "share my profile" row belongs in the account group, beside the
//     sign-out row.
//
// Future compatibility notes:
//   - The page paints its own background (the avatar's palette). A host
//     that wants the top scrim left off has to treat the page as
//     artwork-led — see `isProfileArtworkPage` in MainActivity.
//   - Every number on this page comes off one lifetime summary, so the
//     page costs one IO read however many rows it grows.
