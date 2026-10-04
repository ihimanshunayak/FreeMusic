// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - account and integrations screen.
//
// NAME
//     AccountScreen.kt - Last.fm, ListenBrainz and Discord Rich Presence.
//
// DESCRIPTION
//     Three integrations that all answer the question "what am I listening to"
//     somewhere else, and they differ in how much ceremony they need. Discord
//     needs nothing but a switch. ListenBrainz needs a token the user pastes.
//     Last.fm needs a two-legged authorisation in a browser, which is the only
//     flow here with a genuine intermediate state, so it is the only one drawn
//     with steps.
//
//     Every integration is off until switched on, and each says where its
//     credential is stored. None of them is contacted until it is configured -
//     an app that phones home before being asked is not one a user can trust
//     with a listening history.
//
// RESPONSIBILITIES
//     - Toggle and configure the three integrations.
//     - Run the Last.fm authorisation round trip.
//     - Report the connected account names.
//     - Show and edit the scrobble threshold rules.
//
// DEPENDENCIES
//     - [ScrobbleManager]'s state, injected as plain values.
//     - [SettingsWidgets] for the surrounding chrome.
//
// INTEGRATION NOTES
//     - Authorisation is two calls with a browser visit between them, so the
//       screen keeps the pending token URL in local state and the container owns
//       the network calls. Closing the screen mid-flow loses only the extracted
//       token, not the session.
//     - "Now playing" is a separate switch from scrobbling because they cost
//       different amounts of reputation: an announced track that is skipped is
//       visible to friends, a scrobble that never happens is not.

package com.ihimanshunayak.freemusic.desktop.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.data.DiscordActivityType
import com.ihimanshunayak.freemusic.desktop.data.Settings
import com.ihimanshunayak.freemusic.desktop.ui.component.ActionRow
import com.ihimanshunayak.freemusic.desktop.ui.component.EmptyState
import com.ihimanshunayak.freemusic.desktop.ui.component.EnumRow
import com.ihimanshunayak.freemusic.desktop.ui.component.FluentGlyphs
import com.ihimanshunayak.freemusic.desktop.ui.component.KeyValueRow
import com.ihimanshunayak.freemusic.desktop.ui.component.SectionHeader
import com.ihimanshunayak.freemusic.desktop.ui.component.SettingsGroup
import com.ihimanshunayak.freemusic.desktop.ui.component.SliderRow
import com.ihimanshunayak.freemusic.desktop.ui.component.TextRow
import com.ihimanshunayak.freemusic.desktop.ui.component.ToggleRow
import com.ihimanshunayak.freemusic.desktop.ui.component.VerticalGap
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Icon
import io.github.composefluent.component.InfoBar
import io.github.composefluent.component.InfoBarSeverity
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The account screen.
 *
 * @param lastFmAuthorised whether a session key is held; drives which of the two
 *   Last.fm panels is shown.
 * @param onBeginLastFmAuth returns the browser URL to send the user to, or null if
 *   the keys are not filled in yet.
 * @param openUrl opens a URL in the default browser.
 *
 * Every network call is launched into [scope] and reports back through a local
 * piece of state. Running them inline would block the composition on a round trip
 * to a third-party service, which is the one thing a settings screen must never
 * do.
 */
@Composable
fun AccountScreen(
    settings: Settings,
    lastFmConfigured: Boolean,
    lastFmAuthorised: Boolean,
    lastFmUsername: String,
    listenBrainzUsername: String,
    discordConnected: Boolean,
    scope: CoroutineScope,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onBeginLastFmAuth: suspend () -> String?,
    onCompleteLastFmAuth: suspend (String) -> Boolean,
    onDisconnectLastFm: () -> Unit,
    onValidateListenBrainz: suspend () -> String?,
    onDisconnectListenBrainz: () -> Unit,
    openUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()

    var authUrl by remember { mutableStateOf<String?>(null) }
    var authToken by remember { mutableStateOf("") }
    var authBusy by remember { mutableStateOf(false) }
    var authMessage by remember { mutableStateOf<String?>(null) }
    var listenBrainzMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(24.dp),
    ) {
        SectionHeader("Account")

        InfoBar(
            title = { Text("Nothing leaves this machine until you switch it on") },
            message = {
                Text(
                    "Each integration below stays off, and stays silent, until it is " +
                        "enabled and given its credential. Credentials are stored in the " +
                        "settings file in your own user profile.",
                )
            },
            severity = InfoBarSeverity.Informational,
        )

        VerticalGap(16.dp)

        // -- ## SECTION: Last.fm --------------------------------------------

        SettingsGroup(
            title = "Last.fm",
            detail = when {
                lastFmAuthorised -> "Connected as $lastFmUsername"
                lastFmConfigured -> "Keys saved, not authorised yet"
                else -> "Needs an API key and secret from last.fm/api"
            },
        ) {
            ToggleRow(
                title = "Scrobble to Last.fm",
                checked = settings.lastfmEnabled,
                onCheckedChange = { on -> onUpdate { it.copy(lastfmEnabled = on) } },
                enabled = lastFmAuthorised,
                detail = if (lastFmAuthorised) null
                else "Authorise first - there is nothing to scrobble to yet.",
            )
            ToggleRow(
                title = "Announce now playing",
                checked = settings.lastfmNowPlaying,
                onCheckedChange = { on -> onUpdate { it.copy(lastfmNowPlaying = on) } },
                enabled = lastFmAuthorised,
                detail = "Shows the current track immediately, before the scrobble is due.",
            )

            TextRow(
                title = "API key",
                value = settings.lastfmApiKey,
                onValueChange = { key -> onUpdate { it.copy(lastfmApiKey = key) } },
                detail = "The public key from your Last.fm API account.",
                modifier = Modifier.width(420.dp),
            )
            TextRow(
                title = "Shared secret",
                value = settings.lastfmSecret,
                onValueChange = { secret -> onUpdate { it.copy(lastfmSecret = secret) } },
                secret = true,
            )

            VerticalGap(8.dp)

            when {
                lastFmAuthorised -> ActionRow {
                    SubtleButton(onClick = onDisconnectLastFm) {
                        Icon(FluentGlyphs.Close, contentDescription = null)
                        Text("Disconnect", modifier = Modifier.padding(start = 6.dp))
                    }
                }

                authUrl == null -> ActionRow {
                    AccentButton(
                        onClick = {
                            authBusy = true
                            authMessage = null
                            scope.launch {
                                val url = runCatching { onBeginLastFmAuth() }.getOrNull()
                                authBusy = false
                                if (url.isNullOrBlank()) {
                                    authMessage =
                                        "Could not start authorisation. Check the API key and secret."
                                } else {
                                    authUrl = url
                                    openUrl(url)
                                }
                            }
                        },
                        disabled = !lastFmConfigured || authBusy,
                    ) {
                        Icon(FluentGlyphs.Link, contentDescription = null)
                        Text(
                            text = if (authBusy) "Waiting..." else "Authorise with Last.fm",
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }

                else -> {
                    Text(
                        text = "A browser tab was opened on Last.fm. Approve access there, " +
                            "then paste the token Last.fm shows you.",
                        style = FluentTheme.typography.caption,
                        color = FluentTheme.colors.text.text.secondary,
                    )
                    VerticalGap(8.dp)
                    ActionRow {
                        TextRow(
                            title = "Token",
                            value = authToken,
                            onValueChange = { authToken = it },
                            placeholder = "Paste the token here",
                            modifier = Modifier.width(360.dp),
                        )
                        SubtleButton(
                            onClick = {
                                authBusy = true
                                scope.launch {
                                    val ok = runCatching { onCompleteLastFmAuth(authToken.trim()) }
                                        .getOrDefault(false)
                                    authBusy = false
                                    authUrl = null
                                    authToken = ""
                                    authMessage = if (ok) null
                                    else "Last.fm rejected that token. Start again."
                                }
                            },
                            disabled = authToken.isBlank() || authBusy,
                        ) { Text("Finish") }

                        SubtleButton(onClick = {
                            authUrl = null
                            authToken = ""
                            authMessage = null
                        }) { Text("Cancel") }
                    }
                }
            }

            authMessage?.let { message ->
                VerticalGap(8.dp)
                InfoBar(
                    title = { Text("Authorisation") },
                    message = { Text(message) },
                    severity = InfoBarSeverity.Warning,
                )
            }

            if (!lastFmAuthorised) {
                Text(
                    text = "Last.fm asks for its own approval step, so this is a two-part " +
                        "setup. If you do not have API keys, create them at " +
                        "last.fm/api/account/create - both keys go in the fields above.",
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.tertiary,
                )
            }
        }

        VerticalGap(16.dp)

        // -- ## SECTION: ListenBrainz ---------------------------------------

        SettingsGroup(
            title = "ListenBrainz",
            detail = if (listenBrainzUsername.isNotBlank()) {
                "Connected as $listenBrainzUsername"
            } else {
                "Open source, and it takes a token rather than a handshake"
            },
        ) {
            ToggleRow(
                title = "Scrobble to ListenBrainz",
                checked = settings.listenBrainzEnabled,
                onCheckedChange = { on -> onUpdate { it.copy(listenBrainzEnabled = on) } },
                enabled = settings.listenBrainzToken.isNotBlank(),
            )
            TextRow(
                title = "User token",
                value = settings.listenBrainzToken,
                onValueChange = { token -> onUpdate { it.copy(listenBrainzToken = token) } },
                secret = true,
                detail = "Found in your ListenBrainz profile settings.",
            )

            ActionRow {
                SubtleButton(
                    onClick = {
                        scope.launch {
                            val result = runCatching { onValidateListenBrainz() }.getOrNull()
                            listenBrainzMessage = when {
                                result.isNullOrBlank() -> "That token was not accepted."
                                else -> "Connected as $result."
                            }
                        }
                    },
                    disabled = settings.listenBrainzToken.isBlank(),
                ) {
                    Icon(FluentGlyphs.Checkmark, contentDescription = null)
                    Text("Check token", modifier = Modifier.padding(start = 6.dp))
                }
                if (listenBrainzUsername.isNotBlank()) {
                    SubtleButton(onClick = {
                        onDisconnectListenBrainz()
                        listenBrainzMessage = null
                    }) {
                        Text("Disconnect")
                    }
                }
            }

            listenBrainzMessage?.let { message ->
                VerticalGap(8.dp)
                InfoBar(
                    title = { Text("ListenBrainz") },
                    message = { Text(message) },
                    severity = if (message.startsWith("Connected")) InfoBarSeverity.Success
                    else InfoBarSeverity.Warning,
                )
            }
        }

        VerticalGap(16.dp)

        // -- ## SECTION: Scrobble rules -------------------------------------

        SettingsGroup(
            title = "What counts as a scrobble",
            detail = "A track that is skipped early is not reported, by design.",
        ) {
            SliderRow(
                title = "Minimum track length",
                value = settings.scrobbleMinDurationSeconds.toFloat(),
                onValueChange = { seconds ->
                    onUpdate { it.copy(scrobbleMinDurationSeconds = seconds.toInt()) }
                },
                valueLabel = "${settings.scrobbleMinDurationSeconds} s",
                range = 0f..120f,
                steps = 23,
                detail = "Anything shorter than this is never reported, matching Last.fm's rule.",
            )
            SliderRow(
                title = "Report after",
                value = settings.scrobbleDelayPercent.toFloat(),
                onValueChange = { percent ->
                    onUpdate { it.copy(scrobbleDelayPercent = percent.toInt()) }
                },
                valueLabel = "${settings.scrobbleDelayPercent}% of the track",
                range = 10f..100f,
                steps = 17,
            )
            SliderRow(
                title = "Or after",
                value = settings.scrobbleDelaySeconds.toFloat(),
                onValueChange = { seconds ->
                    onUpdate { it.copy(scrobbleDelaySeconds = seconds.toInt()) }
                },
                valueLabel = "${settings.scrobbleDelaySeconds} s",
                range = 30f..600f,
                steps = 56,
                detail = "Whichever comes first, so a long track still reports promptly.",
            )
        }

        VerticalGap(16.dp)

        // -- ## SECTION: Discord --------------------------------------------

        SettingsGroup(
            title = "Discord Rich Presence",
            detail = if (discordConnected) "Connected to the local Discord client"
            else "Needs the Discord desktop app running",
        ) {
            ToggleRow(
                title = "Show what I am listening to",
                checked = settings.discordRpcEnabled,
                onCheckedChange = { on -> onUpdate { it.copy(discordRpcEnabled = on) } },
                detail = "Appears on your Discord profile while a track plays.",
            )
            ToggleRow(
                title = "Pause presence when playback stops",
                checked = settings.discordIdleWhenPaused,
                onCheckedChange = { idle -> onUpdate { it.copy(discordIdleWhenPaused = idle) } },
                enabled = settings.discordRpcEnabled,
            )
            ToggleRow(
                title = "Include the audio quality",
                checked = settings.discordShowAudioQuality,
                onCheckedChange = { show -> onUpdate { it.copy(discordShowAudioQuality = show) } },
                enabled = settings.discordRpcEnabled,
                detail = "Adds a second line naming the codec and bitrate.",
            )
            ToggleRow(
                title = "Advanced activity",
                checked = settings.discordAdvancedMode,
                onCheckedChange = { on -> onUpdate { it.copy(discordAdvancedMode = on) } },
                enabled = settings.discordRpcEnabled,
                detail = "Lets the activity name and buttons below be customised.",
            )

            if (settings.discordAdvancedMode) {
                EnumRow(
                    title = "Activity type",
                    options = DiscordActivityType.entries,
                    selected = settings.discordActivityType,
                    labelOf = { it.label },
                    onSelect = { type -> onUpdate { it.copy(discordActivityType = type) } },
                    detail = "What Discord calls the activity, and therefore what verb it shows.",
                    enabled = settings.discordRpcEnabled,
                )
                TextRow(
                    title = "Activity name",
                    value = settings.discordActivityName,
                    onValueChange = { name -> onUpdate { it.copy(discordActivityName = name) } },
                    placeholder = "Free Music",
                    enabled = settings.discordRpcEnabled,
                )
                TextRow(
                    title = "First button label",
                    value = settings.discordButton1Text,
                    onValueChange = { text -> onUpdate { it.copy(discordButton1Text = text) } },
                    placeholder = "Get the app",
                    enabled = settings.discordRpcEnabled,
                )
                TextRow(
                    title = "Second button label",
                    value = settings.discordButton2Text,
                    onValueChange = { text -> onUpdate { it.copy(discordButton2Text = text) } },
                    placeholder = "View the repository",
                    enabled = settings.discordRpcEnabled,
                )
            }

            if (settings.discordRpcEnabled && !discordConnected) {
                VerticalGap(8.dp)
                InfoBar(
                    title = { Text("Discord is not reachable") },
                    message = {
                        Text(
                            "Rich Presence talks to the Discord desktop client over a local " +
                                "pipe. Start Discord, then this connects on its own.",
                        )
                    },
                    severity = InfoBarSeverity.Warning,
                )
            }
        }

        VerticalGap(16.dp)

        // -- ## SECTION: Scrobble history -----------------------------------

        SettingsGroup(title = "History") {
            KeyValueRow("Last.fm", if (lastFmAuthorised) lastFmUsername else "Not connected")
            KeyValueRow(
                "ListenBrainz",
                listenBrainzUsername.ifBlank { "Not connected" },
            )
            KeyValueRow(
                "Discord",
                if (settings.discordRpcEnabled) {
                    if (discordConnected) "Connected" else "Enabled, waiting for Discord"
                } else {
                    "Off"
                },
            )
            if (!lastFmAuthorised && listenBrainzUsername.isBlank()) {
                VerticalGap(8.dp)
                EmptyState(
                    icon = FluentGlyphs.Account,
                    title = "No scrobbling accounts",
                    detail = "Connect one above and your listening history starts syncing " +
                        "from the next track.",
                )
            }
        }
    }
}

/**
 * A horizontal spacer used only by the Last.fm action row's alignment.
 *
 * Kept as a named constant rather than an inline literal so the row's two
 * buttons keep the same leading inset if the layout is retuned.
 */
private val authActionInset = 8.dp
