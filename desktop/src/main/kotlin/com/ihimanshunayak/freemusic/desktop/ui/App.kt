// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - root composition.
//
// Wires the sidebar, the active screen and the transport bar together, and owns
// the two pieces of state the whole window shares: the current screen and the
// pending search query that a Home shelf click can push into Search.
//
// This is also where cross-screen actions live (play a search row, add a local
// file, open a folder picker), because a screen cannot reach another screen's
// state and threading callbacks down from Main would put platform code in the UI.

package com.ihimanshunayak.freemusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ihimanshunayak.freemusic.desktop.AppContainer
import com.ihimanshunayak.freemusic.desktop.model.PlaybackState
import com.ihimanshunayak.freemusic.desktop.model.RepeatMode
import com.ihimanshunayak.freemusic.desktop.model.SearchResult
import com.ihimanshunayak.freemusic.desktop.model.SourceKind
import com.ihimanshunayak.freemusic.desktop.model.Track
import com.ihimanshunayak.freemusic.desktop.ui.component.NowPlayingBar
import com.ihimanshunayak.freemusic.desktop.ui.component.Sidebar
import com.ihimanshunayak.freemusic.desktop.ui.screen.DiagnosticsScreen
import com.ihimanshunayak.freemusic.desktop.ui.screen.HomeScreen
import com.ihimanshunayak.freemusic.desktop.ui.screen.LibraryScreen
import com.ihimanshunayak.freemusic.desktop.ui.screen.QueueScreen
import com.ihimanshunayak.freemusic.desktop.ui.screen.SearchScreen
import com.ihimanshunayak.freemusic.desktop.ui.screen.SettingsScreen
import com.ihimanshunayak.freemusic.desktop.ui.state.BrowseViewModel
import kotlinx.coroutines.launch

/**
 * The whole window.
 *
 * [onPickFolder] is injected so the OS folder dialog stays in `Main.kt`: the
 * dialog is a Swing call and keeping it out of this file means the composition
 * above it never has to know which desktop toolkit is underneath.
 */
@Composable
fun App(
    container: AppContainer,
    viewModel: BrowseViewModel,
    onPickFolder: (title: String, onPicked: (String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.HOME) }

    val settings by container.settings.flow.collectAsState()
    val snapshot by container.player.snapshot.collectAsState()
    val folders by container.library.folders.collectAsState()
    val localTracks by container.library.tracks.collectAsState()
    val scanning by container.library.scanning.collectAsState()
    val engineAvailable = container.engine.isAvailable

    // Settings are pushed into the player here rather than from the settings
    // screen, so a value edited in settings.json by hand still takes effect on
    // the next launch without the settings screen ever being opened.
    LaunchedEffect(settings.volume, settings.playbackSpeed, settings.repeatMode) {
        container.player.setVolume(settings.volume)
        container.engine.setPlaybackSpeed(settings.playbackSpeed)
        container.player.setRepeatMode(settings.repeatMode)
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(modifier = Modifier.weight(1f)) {
            Sidebar(
                current = screen,
                onSelect = { screen = it },
                showDiagnostics = settings.showDiagnostics,
                engineAvailable = engineAvailable,
            )

            Box(modifier = Modifier.fillMaxSize()) {
                when (screen) {
                    Screen.HOME -> HomeScreen(
                        viewModel = viewModel,
                        onPlay = { result -> playResult(container, viewModel, result) },
                        onOpenShelf = { title ->
                            viewModel.submit(title)
                            screen = Screen.SEARCH
                        },
                    )

                    Screen.SEARCH -> SearchScreen(
                        viewModel = viewModel,
                        onPlay = { result -> playResult(container, viewModel, result) },
                        onOpenResult = { result -> viewModel.submit(result.title) },
                    )

                    Screen.LIBRARY -> LibraryScreen(
                        folders = folders,
                        tracks = localTracks,
                        scanning = scanning,
                        onAddFolder = {
                            onPickFolder("Choose a music folder") { picked ->
                                if (picked != null) scope.launch { container.library.addFolder(picked) }
                            }
                        },
                        onRemoveFolder = { path -> scope.launch { container.library.removeFolder(path) } },
                        onRefresh = { scope.launch { container.library.refresh() } },
                        onPlay = { track -> playLocal(container, track, localTracks) },
                        currentTrackId = snapshot.currentTrack?.id,
                        isPlaying = snapshot.state == PlaybackState.PLAYING,
                    )

                    Screen.QUEUE -> QueueScreen(
                        snapshot = snapshot,
                        onPlayIndex = { index -> container.player.playAll(snapshot.queue, index) },
                        onRemove = container.player::removeFromQueue,
                        onClear = container.player::clearQueue,
                        onToggleShuffle = { container.player.setShuffle(!snapshot.shuffle) },
                    )

                    Screen.SETTINGS -> SettingsScreen(
                        settings = settings,
                        engineAvailable = engineAvailable,
                        onUpdate = { transform -> container.settings.update(transform) },
                        onPickDownloadsFolder = {
                            onPickFolder("Choose where downloads are saved") { picked ->
                                if (picked != null) {
                                    container.settings.update { it.copy(downloadsDirectory = picked) }
                                }
                            }
                        },
                    )

                    Screen.DIAGNOSTICS -> DiagnosticsScreen(
                        snapshot = snapshot,
                        engineAvailable = engineAvailable,
                        engineUnavailableReason = container.engine.unavailableReason,
                        visitorDataPresent = !container.session.innerTube.visitorData.isNullOrBlank(),
                    )
                }
            }
        }

        NowPlayingBar(
            snapshot = snapshot,
            onTogglePlayPause = container.player::togglePlayPause,
            onNext = container.player::next,
            onPrevious = container.player::previous,
            onSeek = container.player::seekToFraction,
            onSkipBy = container.player::skipBy,
            onVolumeChange = { volume ->
                container.player.setVolume(volume)
                container.settings.update { it.copy(volume = volume, muted = false) }
            },
            onToggleMute = {
                container.player.toggleMute()
                container.settings.update { it.copy(muted = !it.muted) }
            },
            onToggleRepeat = {
                val next = when (snapshot.repeatMode) {
                    RepeatMode.OFF -> RepeatMode.ALL
                    RepeatMode.ALL -> RepeatMode.ONE
                    RepeatMode.ONE -> RepeatMode.OFF
                }
                container.player.setRepeatMode(next)
                container.settings.update { it.copy(repeatMode = next) }
            },
            onToggleShuffle = {
                val next = !snapshot.shuffle
                container.player.setShuffle(next)
                container.settings.update { it.copy(shuffle = next) }
            },
            onOpenQueue = { screen = Screen.QUEUE },
            engineAvailable = engineAvailable,
        )
    }
}

/** Plays a YouTube Music search row by queueing it as a single track. */
private fun playResult(container: AppContainer, viewModel: BrowseViewModel, result: SearchResult) {
    val track = viewModel.toTrack(result) ?: return
    container.player.playSingle(track)
}

/** Plays a local file. The list is passed in so the queue holds the whole folder. */
private fun playLocal(container: AppContainer, track: Track, all: List<Track>) {
    if (track.source != SourceKind.LOCAL_FILE) return
    val index = all.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
    container.player.playAll(all, index)
}
