// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - application container.
//
// One object owns the session, the repositories, the resolver and the player,
// and it is created once at startup and closed once at exit. Screens receive it
// rather than building their own, which is what keeps a search started on one
// screen and a queue built on another from using two different HTTP sessions.

package com.ihimanshunayak.freemusic.desktop

import com.ihimanshunayak.freemusic.desktop.audio.AudioEngine
import com.ihimanshunayak.freemusic.desktop.audio.PlayerController
import com.ihimanshunayak.freemusic.desktop.data.Http
import com.ihimanshunayak.freemusic.desktop.data.SettingsStore
import com.ihimanshunayak.freemusic.desktop.data.discord.DiscordPresence
import com.ihimanshunayak.freemusic.desktop.data.download.DownloadManager
import com.ihimanshunayak.freemusic.desktop.data.innertube.MusicRepository
import com.ihimanshunayak.freemusic.desktop.data.innertube.YouTubeSession
import com.ihimanshunayak.freemusic.desktop.data.library.LocalLibraryRepository
import com.ihimanshunayak.freemusic.desktop.data.party.PartyClient
import com.ihimanshunayak.freemusic.desktop.data.remote.RemoteLibraryRepository
import com.ihimanshunayak.freemusic.desktop.data.scrobble.ScrobbleManager
import com.ihimanshunayak.freemusic.desktop.data.source.SourceAggregator
import com.ihimanshunayak.freemusic.desktop.data.source.SourceRegistry
import com.ihimanshunayak.freemusic.desktop.data.stats.ListeningRecorder
import com.ihimanshunayak.freemusic.desktop.data.stats.ListeningStats
import com.ihimanshunayak.freemusic.desktop.data.stream.StreamResolver
import com.ihimanshunayak.freemusic.desktop.lyrics.LyricsRepository
import com.ihimanshunayak.freemusic.desktop.lyrics.LyricsSource
import com.ihimanshunayak.freemusic.desktop.util.AppPaths
import com.ihimanshunayak.freemusic.desktop.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * The live object graph for the running app.
 *
 * Construction order matters and is the reason this is not a DI framework: the
 * resolver needs no session, the player needs the resolver, and the settings
 * store must exist before anything reads a quality preference.
 *
 * The integrations that are *optional* - scrobbling, Discord, a listening room,
 * downloads - are built here but stay inert until the feature is enabled in
 * settings, so the cost of having them available is one object each rather than
 * a startup term.
 */
class AppContainer {

    /** Long-lived scope for app-lifetime work (session warm-up, library scans). */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: SettingsStore = SettingsStore()

    val session: YouTubeSession = YouTubeSession()

    val music: MusicRepository = MusicRepository(
        session = session,
        language = { settings.current.language },
        region = { settings.current.region },
    )

    val resolver: StreamResolver = StreamResolver(quality = { settings.current.audioQuality })

    val library: LocalLibraryRepository = LocalLibraryRepository()

    val remote: RemoteLibraryRepository = RemoteLibraryRepository()

    val engine: AudioEngine = AudioEngine(scope)

    val player: PlayerController = PlayerController(engine, resolver, scope)

    // -- ## SECTION: Optional integrations ----------------------------------

    /** Listening history, used by the Statistics screen and by Replay. */
    val stats: ListeningStats = ListeningStats(File(AppPaths.rootDir, "listening-history.json"))

    /** Turns playback events into history rows. */
    val recorder: ListeningRecorder = ListeningRecorder(stats)

    /** Last.fm and ListenBrainz, driven from the same playback events. */
    val scrobbler: ScrobbleManager = ScrobbleManager(File(AppPaths.rootDir, "scrobble-session.json"))

    /** Discord Rich Presence; connects lazily and stays inert until enabled. */
    val discord: DiscordPresence = DiscordPresence(DISCORD_APPLICATION_ID, scope)

    /** The offline download queue. */
    val downloads: DownloadManager = DownloadManager(resolver, settings = { settings.current }, scope = scope)

    /** Extra sources: SoundCloud, Bandcamp, PeerTube and user addons. */
    val sources: SourceRegistry = SourceRegistry(settings = { settings.current })

    /** Fan-out search across the enabled sources. */
    val aggregator: SourceAggregator = SourceAggregator()

    /** The synchronised listening room. */
    val party: PartyClient = PartyClient(scope)

    /**
     * Lyrics, tried across every enabled provider in the user's order.
     *
     * Constructed with lambdas rather than values so a change to the provider list
     * or the order takes effect on the next lookup without rebuilding anything.
     */
    val lyrics: LyricsRepository = LyricsRepository(
        sourcesEnabled = { settings.current.lyricsSources.mapNotNull { name ->
            LyricsSource.entries.firstOrNull { it.name == name }
        }.toSet() },
        sourceOrder = { LyricsSource.orderedBy(settings.current.effectiveLyricsOrder) },
        paxSenixKey = { settings.current.paxSenixApiKey },
        preferWordSync = { settings.current.prioritizeSyllableSync },
    )

    /** True once [initialise] has finished, so the UI can show a starting state. */
    @Volatile
    var ready: Boolean = false
        private set

    /**
     * Brings the audio engine up and restores everything persisted.
     *
     * All of it is slow - libVLC loads native libraries, the session fetches a
     * visitor id over the network, the download queue and history file are
     * parsed - so it runs off the UI thread and the window is shown immediately
     * with placeholders.
     */
    fun initialise(onReady: () -> Unit = {}) {
        scope.launch {
            engine.initialise()
            // A failure in any one of these is not fatal: the feature that owns
            // the missing file reports its own empty state, and the app stays
            // usable for local files.
            runCatching { session.ensureVisitorData() }
                .onFailure { Log.w("session warm-up failed: ${it.message}", tag = "app") }
            runCatching { stats.load() }
                .onFailure { Log.w("history load failed: ${it.message}", tag = "app") }
            runCatching { downloads.restore() }
                .onFailure { Log.w("download queue load failed: ${it.message}", tag = "app") }
            runCatching { scrobbler.load() }
                .onFailure { Log.w("scrobble session load failed: ${it.message}", tag = "app") }
            runCatching { applyIntegrations() }
                .onFailure { Log.w("integration restore failed: ${it.message}", tag = "app") }
            startPlaybackWatcher()
            ready = true
            onReady()
        }
    }

    /**
     * Re-applies the optional integrations after a settings change.
     *
     * Called at startup and again whenever the Settings screen writes something
     * that turns one of them on or off, so the switch takes effect without a
     * restart.
     */
    fun applyIntegrations() {
        val current = settings.current
        discord.setEnabled(current.discordRpcEnabled)
        discord.setShowQuality(current.discordShowAudioQuality)
        sources.setAddons(current.addons.filter { it.enabled }.map { it.toAddonSource() })
        scrobbler.configure(
            lastFmEnabled = current.lastfmEnabled && current.lastfmScrobbleEnabled,
            listenBrainzEnabled = current.listenBrainzEnabled,
            apiKey = current.lastfmApiKey,
            secret = current.lastfmSecret,
            token = current.listenBrainzToken,
            reportNowPlaying = current.lastfmNowPlaying,
        )
    }

    // -- ## SECTION: Playback fan-out ---------------------------------------

    private var playbackWatcher: Job? = null
    private var watchedTrackId: String? = null
    private var scrobbledSeconds: Int = 0

    /**
     * Connects the player's snapshot stream to history, scrobbling and Discord.
     *
     * One collector rather than three keeps the order deterministic: history
     * records before the remote services are told, so a network failure cannot
     * lose a local play.
     */
    private fun startPlaybackWatcher() {
        if (playbackWatcher != null) return
        playbackWatcher = scope.launch {
            player.snapshot.collect { snapshot ->
                val track = snapshot.currentTrack
                if (track?.videoId != watchedTrackId) {
                    watchedTrackId = track?.videoId
                    scrobbledSeconds = 0
                    if (track != null) {
                        recorder.onTrackStarted(track)
                        scrobbler.onTrackStarted(track)
                        discord.publish(track, System.currentTimeMillis())
                    } else {
                        recorder.onStopped()
                        scrobbler.onStopped()
                        discord.publish(null, null)
                    }
                }
                if (track != null) {
                    recorder.onPosition(snapshot.positionMillis, snapshot.durationMillis)
                    val delta = recorder.listenedSecondsPublic - scrobbledSeconds
                    if (delta > 0) {
                        scrobbledSeconds = recorder.listenedSecondsPublic
                        scrobbler.onPosition(delta, (snapshot.durationMillis / 1000).toInt())
                    }
                }
            }
        }
    }
    // -- ## SECTION: Cache maintenance --------------------------------------

    /**
     * Bytes currently held in the local cache.
     *
     * Walks the tree rather than keeping a running total, because the cache is
     * also written by the artwork loader and by anything the user drops in by
     * hand, and a counter that silently disagrees with the disk is worse than no
     * counter.
     */
    fun cacheBytes(): Long = runCatching {
        val root = File(AppPaths.cacheDir)
        if (!root.exists()) return 0L
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    /**
     * Empties the local cache.
     *
     * The directories are recreated rather than removed so a caller that cached
     * the path keeps working, and a file that is open - a cover being displayed -
     * is skipped instead of failing the whole clear.
     */
    fun clearCache() {
        scope.launch {
            runCatching {
                val root = File(AppPaths.cacheDir)
                if (!root.exists()) return@runCatching
                root.listFiles()?.forEach { entry ->
                    runCatching { entry.deleteRecursively() }
                        .onFailure { Log.w("could not clear ${entry.name}: ${it.message}", tag = "app") }
                }
                Log.i("cache cleared", tag = "app")
            }.onFailure { Log.w("cache clear failed: ${it.message}", tag = "app") }
        }
    }

    fun close() {
        runCatching { player.dispose() }
        runCatching { engine.dispose() }
        runCatching { downloads.close() }
        runCatching { party.close() }
        runCatching { discord.close() }
        runCatching { session.innerTube.close() }
        runCatching { Http.close() }
        scope.cancel()
    }

    private companion object {
        /**
         * A shared Rich Presence application. Every client of this build shows
         * up to other users as "Free Music", which is the intent - the presence
         * is about what you are listening to, not who you are.
         */
        const val DISCORD_APPLICATION_ID = "1218259495116836884"
    }
}
