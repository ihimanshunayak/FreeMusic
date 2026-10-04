// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - Listen Together screen.
//
// NAME
//     ListenTogetherScreen.kt - the shared listening room.
//
// DESCRIPTION
//     A listening room synchronises transport across machines that have no
//     shared clock. The screen therefore shows the two numbers that make the
//     synchronisation honest - the measured clock offset and the round trip - so
//     a user hearing an echo can see whether the room has calibrated yet rather
//     than guessing at a bug.
//
//     Host and guest see the same room but different controls: a host publishes
//     its transport as the authority, a guest follows it and can only request a
//     change. Showing a guest a play button that silently does nothing would be
//     worse than showing them a request button that visibly works.
//
// RESPONSIBILITIES
//     - Join or create a room, with a display name.
//     - Show who is present and who is hosting.
//     - Show the shared queue and each entry's contributor.
//     - Show the clock calibration, and the last error or notice.
//     - Host: control the room. Guest: request a change.
//
// DEPENDENCIES
//     - [PartyState], [PartyMember], [PartyQueueItem], [PartyRole].
//     - [SettingsWidgets] for the surrounding chrome.
//
// INTEGRATION NOTES
//     - The room needs a server. The client speaks a documented WebSocket
//       protocol but none is bundled, so the server field is a required input
//       and the screen says so plainly rather than failing silently.
//     - Dropping the connection leaves a host playing locally on purpose: a
//       host that stalls because its socket died is a worse outcome than a room
//       that drifts apart.

package com.ihimanshunayak.freemusic.desktop.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.data.Settings
import com.ihimanshunayak.freemusic.desktop.data.party.PartyMember
import com.ihimanshunayak.freemusic.desktop.data.party.PartyQueueItem
import com.ihimanshunayak.freemusic.desktop.data.party.PartyRole
import com.ihimanshunayak.freemusic.desktop.data.party.PartyState
import com.ihimanshunayak.freemusic.desktop.ui.component.ActionRow
import com.ihimanshunayak.freemusic.desktop.ui.component.EmptyState
import com.ihimanshunayak.freemusic.desktop.ui.component.FluentGlyphs
import com.ihimanshunayak.freemusic.desktop.ui.component.KeyValueRow
import com.ihimanshunayak.freemusic.desktop.ui.component.SectionHeader
import com.ihimanshunayak.freemusic.desktop.ui.component.SettingsGroup
import com.ihimanshunayak.freemusic.desktop.ui.component.TextRow
import com.ihimanshunayak.freemusic.desktop.ui.component.Thumbnail
import com.ihimanshunayak.freemusic.desktop.ui.component.ToggleRow
import com.ihimanshunayak.freemusic.desktop.ui.component.VerticalGap
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Icon
import io.github.composefluent.component.InfoBar
import io.github.composefluent.component.InfoBarSeverity
import io.github.composefluent.component.ListItem
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text

/**
 * The listening room.
 *
 * @param onConnect connects as host or guest; the screen does not open sockets
 *   itself.
 * @param onPlayQueueItem asks for an entry to play; as a guest this becomes a
 *   request the host arbitrates.
 */
@Composable
fun ListenTogetherScreen(
    state: PartyState,
    settings: Settings,
    onConnect: (serverUrl: String, roomCode: String, displayName: String, asHost: Boolean) -> Unit,
    onDisconnect: () -> Unit,
    onNewRoomCode: () -> String,
    onSetDisplayName: (String) -> Unit,
    onSetAllowGuestControl: (Boolean) -> Unit,
    onPlayQueueItem: (PartyQueueItem) -> Unit,
    onRemoveQueueItem: (PartyQueueItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var serverUrl by remember { mutableStateOf(settings.partyServerUrl) }
    var roomCode by remember { mutableStateOf(state.roomCode.ifBlank { settings.partyInviteCode }) }
    var displayName by remember(state.selfName) {
        mutableStateOf(state.selfName.ifBlank { settings.partyDisplayName })
    }

    Column(modifier = modifier.fillMaxSize()) {
        SectionHeader("Listen Together") {
            if (state.connected) {
                SubtleButton(onClick = onDisconnect) {
                    Icon(FluentGlyphs.Close, contentDescription = null)
                    Text("Leave", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }

        if (!state.connected) {
            JoinPanel(
                serverUrl = serverUrl,
                onServerUrlChange = { serverUrl = it },
                roomCode = roomCode,
                onRoomCodeChange = { roomCode = it },
                displayName = displayName,
                onDisplayNameChange = {
                    displayName = it
                    onSetDisplayName(it)
                },
                onGenerateCode = { roomCode = onNewRoomCode() },
                onJoin = {
                    onConnect(serverUrl, roomCode, displayName, false)
                },
                onCreate = {
                    val code = roomCode.ifBlank { onNewRoomCode().also { roomCode = it } }
                    onConnect(serverUrl, code, displayName, true)
                },
            )
        } else {
            RoomPanel(
                state = state,
                onPlayQueueItem = onPlayQueueItem,
                onRemoveQueueItem = onRemoveQueueItem,
                onSetAllowGuestControl = onSetAllowGuestControl,
            )
        }
    }
}

/** The pre-join form. */
@Composable
private fun JoinPanel(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    roomCode: String,
    onRoomCodeChange: (String) -> Unit,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    onGenerateCode: () -> Unit,
    onJoin: () -> Unit,
    onCreate: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        InfoBar(
            title = { Text("A room needs a server") },
            message = {
                Text(
                    "Listen Together synchronises playback between machines over a " +
                        "WebSocket room. Point this at a server running the Free Music " +
                        "room protocol - one of you hosts it, or both join the same " +
                        "address. Nothing is sent anywhere until you connect.",
                )
            },
            severity = InfoBarSeverity.Informational,
        )

        VerticalGap(16.dp)

        SettingsGroup(title = "Room") {
            TextRow(
                title = "Server",
                value = serverUrl,
                onValueChange = onServerUrlChange,
                detail = "Host or address, for example wss://room.example.com or " +
                    "room.example.com:8080",
                placeholder = "wss://room.example.com",
                modifier = Modifier.width(420.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextRow(
                    title = "Room code",
                    value = roomCode,
                    onValueChange = onRoomCodeChange,
                    detail = "Everyone in the room types the same code.",
                    placeholder = "A short shared code",
                    modifier = Modifier.width(280.dp),
                )
                SubtleButton(onClick = onGenerateCode) {
                    Icon(FluentGlyphs.Sparkle, contentDescription = null)
                    Text("Generate", modifier = Modifier.padding(start = 6.dp))
                }
            }
            TextRow(
                title = "Your name",
                value = displayName,
                onValueChange = onDisplayNameChange,
                detail = "How the others see you in the member list.",
                placeholder = "Listener",
                modifier = Modifier.width(280.dp),
            )
        }

        VerticalGap(8.dp)

        ActionRow {
            AccentButton(
                onClick = onCreate,
                disabled = serverUrl.isBlank(),
            ) {
                Icon(FluentGlyphs.Play, contentDescription = null)
                Text("Create room", modifier = Modifier.padding(start = 6.dp))
            }
            SubtleButton(
                onClick = onJoin,
                disabled = serverUrl.isBlank() || roomCode.isBlank(),
            ) {
                Icon(FluentGlyphs.ListenTogether, contentDescription = null)
                Text("Join room", modifier = Modifier.padding(start = 6.dp))
            }
        }

        if (serverUrl.isBlank()) {
            VerticalGap(8.dp)
            Text(
                text = "Enter a server address to continue.",
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.text.text.tertiary,
            )
        }
    }
}

/** The in-room view. */
@Composable
private fun RoomPanel(
    state: PartyState,
    onPlayQueueItem: (PartyQueueItem) -> Unit,
    onRemoveQueueItem: (PartyQueueItem) -> Unit,
    onSetAllowGuestControl: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            InfoBar(
                title = { Text(if (state.isHost) "You are hosting" else "You are listening") },
                message = {
                    Text(
                        if (state.isHost) {
                            "Your player is the room's clock. Everything you play is " +
                                "mirrored to the others."
                        } else {
                            "The host's player drives the room. You can add to the queue, " +
                                "and request a change if the host allows it."
                        },
                    )
                },
                severity = InfoBarSeverity.Success,
            )

            VerticalGap(12.dp)
            KeyValueRow("Room", state.roomCode.ifBlank { "-" })
            KeyValueRow(
                "Clock",
                when {
                    state.clockIsCalibrated ->
                        "Calibrated: offset ${state.clockOffsetMs} ms, round trip ${state.roundTripMs} ms"
                    state.roundTripMs > 0 ->
                        "Poor: round trip ${state.roundTripMs} ms. Playback may echo."
                    else -> "Not calibrated yet"
                },
            )
            KeyValueRow(
                "Playing",
                state.playback.videoId?.let { id ->
                    "${if (state.playback.playing) "Yes" else "Paused"} at " +
                        formatClock(state.positionNowMs()) + " - $id"
                } ?: "Nothing",
            )

            state.lastError?.let { error ->
                VerticalGap(8.dp)
                InfoBar(
                    title = { Text("Room error") },
                    message = { Text(error) },
                    severity = InfoBarSeverity.Warning,
                )
            }

            VerticalGap(12.dp)

            if (state.isHost) {
                ToggleRow(
                    title = "Let guests request changes",
                    checked = state.allowGuestControl,
                    onCheckedChange = onSetAllowGuestControl,
                    detail = "Off makes you the only one who can change what is playing.",
                )
            }

            VerticalGap(8.dp)
        }

        MemberStrip(state.members, state.isHost)

        if (state.queue.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = FluentGlyphs.ListenTogether,
                    title = "The room queue is empty",
                    detail = "Add tracks from anywhere in the app and they appear here " +
                        "for everyone.",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
            ) {
                items(state.queue, key = { "${it.videoId}-${it.addedBy}" }) { item ->
                    ListItem(
                        selected = state.playback.videoId == item.videoId,
                        onSelectedChanged = { onPlayQueueItem(item) },
                        text = {
                            Column {
                                Text(
                                    text = item.title.ifBlank { "Untitled" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = listOf(item.artist, "added by ${item.addedBy}")
                                        .filter { it.isNotBlank() && it != "added by " }
                                        .joinToString(" - "),
                                    style = FluentTheme.typography.caption,
                                    color = FluentTheme.colors.text.text.tertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Thumbnail(url = item.thumbnailUrl, modifier = Modifier.width(40.dp)) },
                        trailing = {
                            if (state.isHost || item.addedBy == state.selfName) {
                                SubtleButton(onClick = { onRemoveQueueItem(item) }) {
                                    Icon(FluentGlyphs.Delete, contentDescription = "Remove")
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Who is in the room.
 *
 * A horizontal strip rather than a list: presence is a glanceable fact, and a
 * vertical list of names would take space the queue needs.
 */
@Composable
private fun MemberStrip(members: List<PartyMember>, isHost: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FluentTheme.colors.background.layer.default)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${members.size} listening",
            style = FluentTheme.typography.caption,
            color = FluentTheme.colors.text.text.tertiary,
        )
        members.forEach { member ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(
                            if (member.isSelf) FluentTheme.colors.fillAccent.default
                            else FluentTheme.colors.subtleFill.secondary,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = member.initial,
                        style = FluentTheme.typography.caption,
                        color = if (member.isSelf) FluentTheme.colors.text.onAccent.primary
                        else FluentTheme.colors.text.text.secondary,
                    )
                }
                Text(
                    text = if (member.role == PartyRole.HOST) "${member.name} (host)" else member.name,
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        if (isHost && members.size <= 1) {
            Text(
                text = "Share the room code to invite others.",
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.text.text.tertiary,
            )
        }
    }
}

/** `12:04` for a playback position. */
private fun formatClock(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
