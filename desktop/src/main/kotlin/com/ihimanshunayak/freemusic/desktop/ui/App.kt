// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - root composition.
//
// NAME
//     App.kt - the shell: navigation, the active screen, and the transport bar.
//
// DESCRIPTION
//     Owns the Fluent shell and the few pieces of state the whole window shares:
//     which destination is showing, whether a full-screen sub-view of the player
//     is open over it, and the selected statistics period. Everything else is
//     read from the container's own state flows at the point of use, so a screen
//     re-reads exactly what it needs and nothing more.
//
//     This is also where cross-screen actions live. A screen cannot reach another
//     screen's state, and threading callbacks down from `Main.kt` would put
//     platform code in the UI, so the operations that span screens - play a search
//     row, replay a history entry, connect a listening room - are defined here
//     once and handed to the screen that needs them.
//
//     The window is wrapped in Fluent's `Mica`, which is not merely a background
//     colour: it blurs whatever sits behind the window through a Haze state so the
//     desktop wallpaper shows through the chrome the way Explorer's does. That is
//     why the navigation pane and the transport bar are drawn inside it as
//     translucent layers rather than as opaque columns.
//
// RESPONSIBILITIES
//     - Dispatch the active destination to its screen.
//     - Define the operations that span more than one screen.
//     - Keep the player, the settings store and the optional integrations in step.
//     - Run the lyrics lookup, because it is keyed on the playing track rather
//       than owned by any one screen.
//
// DEPENDENCIES
//     - [AppContainer] for the object graph.
//     - [BrowseViewModel] for Home, Search and Explore.
//     - Every screen under `ui.screen`, and the shell components under
//       `ui.component`.
//
// INTEGRATION NOTES
//     - The `when` over [Screen] is exhaustive on purpose: adding a destination is
//       a compile error here until it has somewhere to go, rather than a blank
//       pane at runtime.
//     - Settings are pushed into the player from here rather than from the
//       settings screen, so a value edited in `settings.json` by hand still takes
//       effect on the next launch even if that screen is never opened.
//     - The equalizer is applied from here for the same reason, and because the
//       curve arrives from three independent sources (the band sliders, the tone
//       pad and a saved curve) that all end up in the same two fields.
//     - A listening room applies its playback through [PartyClient.onApplyPlayback]
//       and publishes transport changes back through `publishPlayback`. Both are
//       wired here so neither screen has to know about the other.
//     - Now playing, Lyrics and the Equalizer are full-pane sub-views rather than
//       navigation entries. Windows apps reach the player from the transport bar,
//       and a permanent "Now playing" row in the pane would sit above the queue it
//       duplicates.

package com.ihimanshunayak.freemusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ihimanshunayak.freemusic.desktop.AppContainer
import com.ihimanshunayak.freemusic.desktop.audio.PlaybackSnapshot
import com.ihimanshunayak.freemusic.desktop.audio.dsp.EqualizerSettings
import com.ihimanshunayak.freemusic.desktop.audio.dsp.preampFor
import com.ihimanshunayak.freemusic.desktop.data.CanvasMode
import com.ihimanshunayak.freemusic.desktop.data.download.DownloadItem
import com.ihimanshunayak.freemusic.desktop.data.library.LibraryFolder
import com.ihimanshunayak.freemusic.desktop.data.party.PartyPlayback
import com.ihimanshunayak.freemusic.desktop.data.party.PartyQueueItem
import com.ihimanshunayak.freemusic.desktop.data.stats.PlayRecord
import com.ihimanshunayak.freemusic.desktop.data.stats.ReplayPeriod
import com.ihimanshunayak.freemusic.desktop.lyrics.LyricsQuery
import com.ihimanshunayak.freemusic.desktop.lyrics.LyricsResult
import com.ihimanshunayak.freemusic.desktop.model.PlaybackState
import com.ihimanshunayak.freemusic.desktop.model.RepeatMode
import com.ihimanshunayak.freemusic.desktop.model.SearchResult
import com.ihimanshunayak.freemusic.desktop.model.SourceKind
import com.ihimanshunayak.freemusic.desktop.model.Track
import com.ihimanshunayak.freemusic.desktop.ui.component.NowPlayingBar
import com.ihimanshunayak.freemusic.desktop.ui.component.Sidebar
import com.ihimanshunayak.freemusic.desktop.ui.screen.AccountScreen
import com.ihimanshunayak.freemusic.desktop.ui.screen.DiagnosticsScreen
import com.ihimanshunayak.freemusic.desktop.ui.screen.DownloadsScreen
import com.ihimanshunayak.freemusic.desktop.ui.screen.EqualizerScreen
import com.ihimanshunayak.freemusic.desktop.ui.screen.ExploreScreen
import com.ihimanshunayak.freemusic.desktop.ui.screen.HistoryScreen
import com.ihimanshunayak.freemusic.desktop.ui.screen.HomeScreen
import com.ihimanshunayak.freemusic.desktop.ui.screen.LibraryScreen
import com.ihimanshunayak.freemusic.desktop.ui.screen.ListenTogetherScreen
import com.ihimanshunayak.freemusic.desktop.ui.screen.LyricsScreen
import com.ihimanshunayak.freemusic.desktop.ui.screen.NowPlayingScreen
import com.ihimanshunayak.freemusic.desktop.ui.screen.QueueScreen
import com.ihimanshunayak.freemusic.desktop.ui.screen.SearchScreen
import com.ihimanshunayak.freemusic.desktop.ui.screen.SettingsScreen
import com.ihimanshunayak.freemusic.desktop.ui.screen.SourcesScreen
import com.ihimanshunayak.freemusic.desktop.ui.screen.StatisticsScreen
import com.ihimanshunayak.freemusic.desktop.ui.state.BrowseState
import com.ihimanshunayak.freemusic.desktop.ui.state.BrowseViewModel
import com.ihimanshunayak.freemusic.desktop.ui.state.HomeState
import com.ihimanshunayak.freemusic.desktop.ui.state.SearchState
import io.github.composefluent.ExperimentalFluentApi
import io.github.composefluent.FluentTheme
import io.github.composefluent.background.Mica
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often a listening room reconciles its playback against the host's clock. */
private const val PARTY_DRIFT_INTERVAL_MS = 2_000L

/** How far the local position may drift before it is corrected, in milliseconds. */
private const val PARTY_DRIFT_TOLERANCE_MS = 400L

/**
 * The whole window.
 *
 * [onPickFolder] is injected so the OS folder dialog stays in `Main.kt`: the
 * dialog is a Swing call and keeping it out of this file means the composition
 * above it never has to know which desktop toolkit is underneath.
 */
@OptIn(ExperimentalFluentApi::class)
@Composable
fun App(
    container: AppContainer,
    viewModel: BrowseViewModel,
    onPickFolder: (title: String, onPicked: (String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.HOME) }

    // Which full-pane sub-view of the player is open over the destination, if any.
    // Held as one value rather than three flags so two of them cannot be open at
    // once and the `when` below stays exhaustive.
    var overlay by remember { mutableStateOf<PlayerOverlay?>(null) }

    val settings by container.settings.flow.collectAsState()
    val snapshot by container.player.snapshot.collectAsState()
    val folders by container.library.folders.collectAsState()
    val localTracks by container.library.tracks.collectAsState()
    val scanning by container.library.scanning.collectAsState()
    val home by viewModel.home.collectAsState()
    val search by viewModel.search.collectAsState()
    val browse by viewModel.browse.collectAsState()
    val downloads by container.downloads.items.collectAsState()
    val plays by container.stats.plays.collectAsState()
    val party by container.party.state.collectAsState()
    val engineAvailable = container.engine.isAvailable

    val currentTrack = snapshot.currentTrack
    val isPlaying = snapshot.state == PlaybackState.PLAYING

    // ---- lyrics -----------------------------------------------------------

    // Bumping this re-runs the lookup, which is what the Reload button on the
    // lyrics pane does.
    var lyricsRevision by remember { mutableStateOf(0) }

    val lyricsState = rememberTrackLyrics(
        container = container,
        track = currentTrack,
        enabled = settings.syncedLyrics,
        revision = lyricsRevision,
    )

    // ---- the listening room ----------------------------------------------

    val isHost = party.connected && party.isHost
    val partyQueue = party.queue

    // The host publishes every transport change; a listener applies what the host
    // sends. Both directions are wired here so neither screen owns the other's
    // state, and so the room keeps working while the user is on any screen.
    DisposableEffect(container) {
        container.party.onApplyPlayback = { playback, _ ->
            applyRemotePlayback(container, playback)
        }
        onDispose { container.party.onApplyPlayback = null }
    }

    LaunchedEffect(isHost, currentTrack?.videoId, isPlaying) {
        if (!isHost) return@LaunchedEffect
        // The queue is republished with the transport because a listener joining
        // mid-session has no other way to learn what is playing.
        container.party.publishQueue(
            partyQueue.map {
                PartyQueueItem(
                    videoId = it.videoId,
                    title = it.title,
                    artist = it.artist,
                    thumbnailUrl = it.thumbnailUrl,
                    durationSeconds = it.durationSeconds,
                    addedBy = it.addedBy,
                )
            },
        )
    }

    // A listener's clock drifts against the host's, so the position is nudged
    // rather than trusted. The correction is skipped unless audio is actually
    // playing, because seeking a paused player would start it.
    val following = party.hasRemotePlayback
    LaunchedEffect(following, party.connected) {
        if (!following) return@LaunchedEffect
        while (true) {
            delay(PARTY_DRIFT_INTERVAL_MS)
            val latest = container.party.state.value
            if (!latest.hasRemotePlayback) continue
            val state = container.player.snapshot.value
            if (state.state != PlaybackState.PLAYING) continue
            val target = latest.positionNowMs()
            val duration = state.durationMillis.coerceAtLeast(1L)
            if (target <= 0L || kotlin.math.abs(state.positionMillis - target) < PARTY_DRIFT_TOLERANCE_MS) {
                continue
            }
            container.player.seekToFraction((target.toFloat() / duration.toFloat()).coerceIn(0f, 1f))
        }
    }

    // ---- settings pushdown ------------------------------------------------

    // Settings are pushed into the player here rather than from the settings
    // screen, so a value edited in settings.json by hand still takes effect on the
    // next launch without the settings screen ever being opened.
    LaunchedEffect(settings.volume, settings.playbackSpeed, settings.repeatMode) {
        container.player.setVolume(settings.volume)
        container.player.setRepeatMode(settings.repeatMode)
    }

    // The speed lives on the engine, not the player, and is only touchable when
    // the engine started.
    LaunchedEffect(settings.playbackSpeed, engineAvailable) {
        if (engineAvailable) container.engine.setPlaybackSpeed(settings.playbackSpeed)
    }

    LaunchedEffect(
        settings.equalizerEnabled,
        settings.equalizerBands,
        settings.equalizerHeadroomDb,
        settings.equalizerBalance,
        engineAvailable,
    ) {
        if (!engineAvailable) return@LaunchedEffect
        if (!settings.equalizerEnabled) {
            container.engine.setEqualizer(EqualizerSettings.Disabled)
            return@LaunchedEffect
        }
        // The preamp is derived rather than stored twice: the headroom setting is
        // the user's intent and the preamp is what the engine needs, so computing
        // it here keeps the two from disagreeing after a preset is applied.
        container.engine.setEqualizer(
            EqualizerSettings(
                enabled = true,
                gainsDb = settings.equalizerBands,
                preampDb = preampFor(
                    gainsDb = settings.equalizerBands,
                    headroomDb = settings.equalizerHeadroomDb,
                ),
                balance = settings.equalizerBalance,
            ),
        )
    }

    LaunchedEffect(settings.normalizeVolume, engineAvailable) {
        if (engineAvailable) container.engine.setLoudnessNormalization(settings.normalizeVolume)
    }

    // The output module is resolved by libVLC when a media starts, so this only
    // stores the choice; it takes effect on the next track. Pushed from here rather
    // than from the settings screen so a hand-edited settings.json still applies.
    LaunchedEffect(settings.outputBackend) {
        container.player.setOutputBackend(settings.outputBackend)
    }

    // ---- the sleep timer --------------------------------------------------

    // The countdown is measured against the wall clock rather than by decrementing a
    // counter every second, so a timer set for 30 minutes still fires 30 minutes
    // later if the machine sleeps or the process is starved for a while. The end
    // time is derived from the remaining seconds in settings, which is the value the
    // now-playing sheet writes.
    LaunchedEffect(settings.sleepTimerSeconds) {
        val remaining = settings.sleepTimerSeconds
        if (remaining <= 0) return@LaunchedEffect
        val deadline = System.currentTimeMillis() + remaining * 1_000L
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0L) break
            delay(left.coerceAtMost(1_000L))
        }
        container.player.pause()
        // Clearing the setting is what makes the timer one-shot: a cancelled timer
        // that silently re-armed on the next launch would be a bug the user could
        // not see coming.
        container.settings.update { it.copy(sleepTimerSeconds = 0) }
    }

    // ---- the window -------------------------------------------------------

    Mica(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                Sidebar(
                    current = screen,
                    onSelect = { target ->
                        // Choosing a destination closes any player sub-view, which
                        // is what makes the pane read as a destination list rather
                        // than as a set of overlays.
                        overlay = null
                        screen = target
                    },
                    showDiagnostics = settings.showDiagnostics,
                    engineAvailable = engineAvailable,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(FluentTheme.colors.background.solid.base),
                    ) {
                        when (overlay) {
                            PlayerOverlay.NOW_PLAYING -> NowPlayingScreen(
                                snapshot = snapshot,
                                settings = settings,
                                onTogglePlayPause = container.player::togglePlayPause,
                                onNext = container.player::next,
                                onPrevious = container.player::previous,
                                onSeek = container.player::seekToFraction,
                                onVolumeChange = { volume ->
                                    container.player.setVolume(volume)
                                    container.settings.update { it.copy(volume = volume, muted = false) }
                                },
                                onToggleMute = {
                                    container.player.toggleMute()
                                    container.settings.update { it.copy(muted = !it.muted) }
                                },
                                onToggleShuffle = {
                                    val next = !snapshot.shuffle
                                    container.player.setShuffle(next)
                                    container.settings.update { it.copy(shuffle = next) }
                                },
                                onSetRepeat = { mode ->
                                    container.player.setRepeatMode(mode)
                                    container.settings.update { it.copy(repeatMode = mode) }
                                },
                                onSetSpeed = { speed ->
                                    container.engine.setPlaybackSpeed(speed)
                                    container.settings.update { it.copy(playbackSpeed = speed) }
                                },
                                onSetCanvasMode = { mode ->
                                    container.settings.update { it.copy(canvasMode = mode) }
                                },
                                onSetSleepTimer = { minutes ->
                                    container.settings.update { it.copy(sleepTimerSeconds = minutes * 60) }
                                },
                                onOpenLyrics = { overlay = PlayerOverlay.LYRICS },
                                onOpenEqualizer = { overlay = PlayerOverlay.EQUALIZER },
                                onOpenQueue = {
                                    overlay = null
                                    screen = Screen.QUEUE
                                },
                                onToggleFullScreen = { },
                                onClose = { overlay = null },
                                engineAvailable = engineAvailable,
                            )

                            PlayerOverlay.LYRICS -> LyricsScreen(
                                result = lyricsState.result,
                                status = lyricsState.status,
                                snapshot = snapshot,
                                offsetMs = settings.lyricsOffsetMs,
                                blurInactive = settings.lyricsBlur,
                                onOffsetChange = { offset ->
                                    container.settings.update { it.copy(lyricsOffsetMs = offset) }
                                },
                                onToggleBlur = { blur ->
                                    container.settings.update { it.copy(lyricsBlur = blur) }
                                },
                                onSeekTo = container.player::seekToFraction,
                                onRefetch = {
                                    container.lyrics.invalidate()
                                    lyricsRevision++
                                },
                                onClose = { overlay = PlayerOverlay.NOW_PLAYING },
                            )

                            PlayerOverlay.EQUALIZER -> EqualizerScreen(
                                settings = settings,
                                engineAvailable = container.engine.equalizerAvailable,
                                onUpdate = { transform -> container.settings.update(transform) },
                                onClose = { overlay = PlayerOverlay.NOW_PLAYING },
                            )

                            null -> ActiveScreen(
                                screen = screen,
                                container = container,
                                viewModel = viewModel,
                                home = home,
                                search = search,
                                browse = browse,
                                downloads = downloads,
                                plays = plays,
                                partyQueue = partyQueue,
                                onPickFolder = onPickFolder,
                                onNavigate = { target -> screen = target },
                                onOpenNowPlaying = { overlay = PlayerOverlay.NOW_PLAYING },
                                folders = folders,
                                localTracks = localTracks,
                                scanning = scanning,
                                snapshot = snapshot,
                                engineAvailable = engineAvailable,
                                scope = scope,
                            )
                        }
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
                onOpenQueue = {
                    overlay = null
                    screen = Screen.QUEUE
                },
                onOpenNowPlaying = {
                    overlay = if (overlay == null) PlayerOverlay.NOW_PLAYING else null
                },
                engineAvailable = engineAvailable,
            )
        }
    }
}

/** The full-pane views of the player that can open over a destination. */
private enum class PlayerOverlay { NOW_PLAYING, LYRICS, EQUALIZER }

/**
 * Dispatches to the screen for [screen].
 *
 * Extracted because the argument list is long and identical for every branch, and
 * because the `when` is exhaustive over [Screen] - adding a destination is then a
 * compile error here until it has somewhere to go, rather than a silent blank
 * pane.
 */
@Composable
private fun ActiveScreen(
    screen: Screen,
    container: AppContainer,
    viewModel: BrowseViewModel,
    home: HomeState,
    search: SearchState,
    browse: BrowseState,
    downloads: List<DownloadItem>,
    plays: List<PlayRecord>,
    partyQueue: List<PartyQueueItem>,
    onPickFolder: (title: String, onPicked: (String?) -> Unit) -> Unit,
    onNavigate: (Screen) -> Unit,
    onOpenNowPlaying: () -> Unit,
    folders: List<LibraryFolder>,
    localTracks: List<Track>,
    scanning: Boolean,
    snapshot: PlaybackSnapshot,
    engineAvailable: Boolean,
    scope: CoroutineScope,
) {
    val settings = container.settings.current
    val currentId = snapshot.currentTrack?.id
    val isPlaying = snapshot.state == PlaybackState.PLAYING

    // The statistics period outlives a trip to another destination but not a
    // restart, which is the right lifetime for a view preference the user sets by
    // clicking around rather than by a setting.
    var period by remember { mutableStateOf(ReplayPeriod.MONTH) }
    val statsRevision = container.stats.revision.collectAsState().value

    when (screen) {
        Screen.HOME -> HomeScreen(
            viewModel = viewModel,
            onPlay = { result -> playResult(container, viewModel, result) },
            onOpenShelf = { title ->
                viewModel.submit(title)
                onNavigate(Screen.SEARCH)
            },
        )

        Screen.SEARCH -> SearchScreen(
            viewModel = viewModel,
            onPlay = { result -> playResult(container, viewModel, result) },
            onOpenResult = { result -> viewModel.submit(result.title) },
        )

        Screen.EXPLORE -> ExploreScreen(
            state = browse,
            currentTrackId = currentId,
            isPlaying = isPlaying,
            onOpenPage = viewModel::openPage,
            onClosePage = viewModel::closePage,
            onPlayRows = { rows, index -> playAllResults(container, viewModel, rows, index) },
            onEnqueueRow = { row -> enqueueResult(container, viewModel, row) },
        )

        Screen.LIBRARY, Screen.LOCAL -> LibraryScreen(
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
            currentTrackId = currentId,
            isPlaying = isPlaying,
        )

        Screen.HISTORY -> HistoryScreen(
            // Newest first. The store keeps plays in the order they happened,
            // which is what `summarise` and the date grouping want, so the flip
            // belongs here rather than in the model.
            plays = remember(plays) { plays.asReversed() },
            currentTrackId = currentId,
            isPlaying = isPlaying,
            onReplay = { record -> replayRecord(container, record) },
            onClear = container.stats::clear,
        )

        Screen.QUEUE -> QueueScreen(
            snapshot = snapshot,
            onPlayIndex = { index -> container.player.playAll(snapshot.queue, index) },
            onRemove = container.player::removeFromQueue,
            onClear = container.player::clearQueue,
            onToggleShuffle = { container.player.setShuffle(!snapshot.shuffle) },
        )

        Screen.DOWNLOADS -> DownloadsScreen(
            items = downloads,
            summary = container.downloads.summary,
            settings = settings,
            onSetQuality = { quality ->
                container.settings.update { it.copy(downloadQuality = quality) }
            },
            onCancel = container.downloads::cancel,
            onRetry = container.downloads::retry,
            onDismiss = container.downloads::dismiss,
            onRetryAllFailed = container.downloads::retryAllFailed,
            onClearFinished = container.downloads::clearFinished,
            onBrowseFolder = {
                onPickFolder("Choose where downloads are saved") { picked ->
                    if (picked != null) {
                        container.settings.update { it.copy(downloadsDirectory = picked) }
                    }
                }
            },
        )

        Screen.STATISTICS -> StatisticsScreen(
            // Keyed on the revision so a new play recomputes the ranking, but an
            // unrelated recomposition does not re-sort the whole history.
            summary = remember(statsRevision, period, settings.language) {
                container.stats.summarise(period)
            },
            recent = remember(statsRevision) { plays.takeLast(50).asReversed() },
            period = period,
            onPeriodChange = { next -> period = next },
            onClear = container.stats::clear,
        )

        Screen.LISTEN_TOGETHER -> ListenTogetherScreen(
            state = container.party.state.collectAsState().value,
            settings = settings,
            onConnect = { server, room, name, asHost ->
                container.settings.update {
                    it.copy(
                        partyServerUrl = server,
                        partyInviteCode = room,
                        partyDisplayName = name,
                    )
                }
                container.party.connect(server, room, name, asHost)
            },
            onDisconnect = container.party::disconnect,
            onNewRoomCode = { container.party.newRoomCode() },
            onSetDisplayName = { name ->
                container.settings.update { it.copy(partyDisplayName = name) }
            },
            onSetAllowGuestControl = { allow ->
                container.settings.update { it.copy(partyAllowGuestControl = allow) }
            },
            onPlayQueueItem = { item ->
                val index = partyQueue.indexOfFirst { it.videoId == item.videoId }.coerceAtLeast(0)
                container.player.playAll(partyQueue.map { it.toTrack() }, index)
            },
            onRemoveQueueItem = { item ->
                container.party.publishQueue(partyQueue.filterNot { it.videoId == item.videoId })
            },
        )

        Screen.SOURCES -> SourcesScreen(
            settings = settings,
            disabledAddons = remember(settings.addons) {
                settings.addons.map { it.id }.filter { container.sources.isAddonDisabled(it) }.toSet()
            },
            validateAddon = { script -> container.sources.validate(script) },
            onUpdate = { transform ->
                container.settings.update(transform)
                // A newly enabled addon only becomes reachable once the registry
                // has been handed the list again.
                container.applyIntegrations()
            },
            onRetryAddon = { id ->
                container.sources.retryAddon(id)
                container.applyIntegrations()
            },
        )

        Screen.ACCOUNT -> AccountScreen(
            settings = settings,
            lastFmConfigured = container.scrobbler.lastFmConfigured,
            lastFmAuthorised = container.scrobbler.lastFmAuthorised,
            lastFmUsername = container.scrobbler.lastFmUsername,
            listenBrainzUsername = container.scrobbler.listenBrainzUsername,
            discordConnected = container.discord.connected,
            scope = scope,
            onUpdate = { transform ->
                container.settings.update(transform)
                container.applyIntegrations()
            },
            onBeginLastFmAuth = { container.scrobbler.beginLastFmAuthorization() },
            onCompleteLastFmAuth = { token -> container.scrobbler.completeLastFmAuthorization(token) },
            onDisconnectLastFm = container.scrobbler::disconnectLastFm,
            onValidateListenBrainz = { container.scrobbler.validateListenBrainzToken() },
            onDisconnectListenBrainz = container.scrobbler::disconnectListenBrainz,
            openUrl = ::openBrowser,
        )

        Screen.SETTINGS -> SettingsScreen(
            settings = settings,
            engineAvailable = engineAvailable,
            onUpdate = { transform ->
                container.settings.update(transform)
                // Turning Discord, scrobbling or an addon on has to take effect
                // without a restart, and the container is the only place that
                // knows how to wire them.
                container.applyIntegrations()
            },
            onPickDownloadsFolder = {
                onPickFolder("Choose where downloads are saved") { picked ->
                    if (picked != null) {
                        container.settings.update { it.copy(downloadsDirectory = picked) }
                    }
                }
            },
            onPickExportFolder = {
                onPickFolder("Choose where to copy downloads as well") { picked ->
                    if (picked != null) {
                        container.settings.update { it.copy(exportDownloadsTo = picked) }
                    }
                }
            },
            onClearCache = container::clearCache,
            cacheBytes = container.cacheBytes(),
            onResetSettings = {
                container.settings.reset()
                container.applyIntegrations()
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

// ---------------------------------------------------------------------------
// ## SECTION: Lyrics lookup
// ---------------------------------------------------------------------------

/** What the lyrics pane is showing, and why it is empty when it is. */
private data class LyricsState(val result: LyricsResult? = null, val status: String = "")

/**
 * Looks up lyrics for the playing track.
 *
 * Keyed on the track rather than run on every position tick, because a lookup is
 * a network round trip to up to seventeen providers and the answer does not change
 * while a track plays. A track with no video id is a local file whose title and
 * artist came from its own tags, which providers *can* match on, so it is looked
 * up the same way.
 */
@Composable
private fun rememberTrackLyrics(
    container: AppContainer,
    track: Track?,
    enabled: Boolean,
    revision: Int,
): LyricsState {
    var state by remember { mutableStateOf(LyricsState()) }

    LaunchedEffect(track?.id, enabled, revision) {
        if (!enabled) {
            state = LyricsState(status = "Lyrics are switched off.")
            return@LaunchedEffect
        }
        if (track == null) {
            state = LyricsState(status = "Nothing is playing.")
            return@LaunchedEffect
        }
        state = LyricsState(status = "Looking for lyrics...")
        // The provider is reported as the walk proceeds, so a slow provider shows
        // as "trying PaxSenix" rather than as an unexplained spinner.
        var current = ""
        val result = runCatching {
            container.lyrics.find(
                LyricsQuery(
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationSeconds = track.durationSeconds,
                ),
                onProgress = { source ->
                    current = source.label
                    state = LyricsState(status = "Trying $current...")
                },
            )
        }
        state = result.fold(
            onSuccess = { found ->
                if (found == null) {
                    LyricsState(status = "No provider had lyrics for this track.")
                } else {
                    LyricsState(result = found)
                }
            },
            onFailure = { LyricsState(status = "The lyrics lookup failed: ${it.message}") },
        )
    }

    return state
}

// ---------------------------------------------------------------------------
// ## SECTION: Cross-screen operations
// ---------------------------------------------------------------------------

/** Plays a YouTube Music search row by queueing it as a single track. */
private fun playResult(container: AppContainer, viewModel: BrowseViewModel, result: SearchResult) {
    val track = viewModel.toTrack(result) ?: return
    container.player.playSingle(track)
}

/** Plays a list of search rows, starting at [index]. */
private fun playAllResults(
    container: AppContainer,
    viewModel: BrowseViewModel,
    rows: List<SearchResult>,
    index: Int,
) {
    val tracks = rows.mapNotNull { viewModel.toTrack(it) }
    if (tracks.isEmpty()) return
    container.player.playAll(tracks, index.coerceIn(0, tracks.lastIndex))
}

/** Adds a search row to the end of the queue. */
private fun enqueueResult(
    container: AppContainer,
    viewModel: BrowseViewModel,
    result: SearchResult,
) {
    val track = viewModel.toTrack(result) ?: return
    container.player.enqueue(track)
}

/** Plays a local file. The list is passed in so the queue holds the whole folder. */
private fun playLocal(container: AppContainer, track: Track, all: List<Track>) {
    if (track.source != SourceKind.LOCAL_FILE) return
    val index = all.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
    container.player.playAll(all, index)
}

/**
 * Replays a recorded history entry.
 *
 * A record carries a track id and its display fields but no source, so it is
 * resolved as a streamable track only when the id looks like a video id. Anything
 * else is dropped rather than queued, because a track that cannot resolve would
 * fail silently in the transport bar.
 */
private fun replayRecord(container: AppContainer, record: PlayRecord) {
    val id = record.trackId
    if (id.isBlank()) return
    container.player.playSingle(
        Track(
            id = id,
            title = record.title,
            artist = record.artist,
            album = record.album,
            source = SourceKind.YOUTUBE_MUSIC,
            videoId = id,
        ),
    )
}

/**
 * Applies playback a listening room's host has published.
 *
 * Drift is not corrected here - that is a timed loop - so this only handles the
 * two edges: a different track starts, or play and pause diverge.
 */
private fun applyRemotePlayback(container: AppContainer, playback: PartyPlayback) {
    val videoId = playback.videoId ?: return
    val state = container.player.snapshot.value
    if (state.currentTrack?.videoId != videoId) {
        container.player.playSingle(
            Track(
                id = videoId,
                title = "",
                artist = "",
                source = SourceKind.YOUTUBE_MUSIC,
                videoId = videoId,
            ),
        )
        return
    }
    val playing = state.state == PlaybackState.PLAYING
    if (playback.playing != playing) container.player.togglePlayPause()
}

/** Opens a URL in the default browser, for the Last.fm authorisation page. */
private fun openBrowser(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}
