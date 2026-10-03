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
import com.ihimanshunayak.freemusic.desktop.data.innertube.MusicRepository
import com.ihimanshunayak.freemusic.desktop.data.innertube.YouTubeSession
import com.ihimanshunayak.freemusic.desktop.data.library.LocalLibraryRepository
import com.ihimanshunayak.freemusic.desktop.data.stream.StreamResolver
import com.ihimanshunayak.freemusic.desktop.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The live object graph for the running app.
 *
 * Construction order matters and is the reason this is not a DI framework: the
 * resolver needs no session, the player needs the resolver, and the settings
 * store must exist before anything reads a quality preference.
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

    val engine: AudioEngine = AudioEngine(scope)

    val player: PlayerController = PlayerController(engine, resolver, scope)

    /** True once [initialise] has finished, so the UI can show a starting state. */
    @Volatile
    var ready: Boolean = false
        private set

    /**
     * Brings the audio engine up and warms the YouTube session.
     *
     * Both are slow - libVLC loads native libraries and the session fetches a
     * visitor id over the network - so they run off the UI thread and the window
     * is shown immediately with placeholders.
     */
    fun initialise(onReady: () -> Unit = {}) {
        scope.launch {
            engine.initialise()
            // A failure here is not fatal: browse and search report their own
            // errors, and the app stays usable for local files.
            runCatching { session.ensureVisitorData() }
                .onFailure { Log.w("session warm-up failed: ${it.message}", tag = "app") }
            ready = true
            onReady()
        }
    }

    fun close() {
        runCatching { player.dispose() }
        runCatching { engine.dispose() }
        runCatching { session.innerTube.close() }
        runCatching { Http.close() }
        scope.cancel()
    }
}
