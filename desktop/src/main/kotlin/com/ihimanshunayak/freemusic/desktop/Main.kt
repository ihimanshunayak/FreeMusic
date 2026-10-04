// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - application entry point.
//
// A Compose Desktop window around the same data layer the Android app uses. The
// YouTube session, the stream resolver and the vlcj audio engine are brought up
// off the UI thread because both the visitor-id fetch and libVLC's native load
// take long enough to be visible as a frozen window if done inline.
//
// This file is also where the platform calls live that the UI deliberately does
// not own: the native folder picker, the process-wide shutdown hook, and the
// Windows shell integration (Mica backdrop, dark title bar, tray icon, global
// media keys and taskbar state).
//
// The shell integration is all best-effort. Nothing here is allowed to stop the
// app from starting: a Windows build without DWM composition, or a session with
// no notification area, degrades to "that one feature is off" rather than an
// exception on the way to a first frame.

package com.ihimanshunayak.freemusic.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.ihimanshunayak.freemusic.desktop.data.ThemePreference
import com.ihimanshunayak.freemusic.desktop.model.PlaybackState
import com.ihimanshunayak.freemusic.desktop.model.RepeatMode
import com.ihimanshunayak.freemusic.desktop.platform.MediaKeyService
import com.ihimanshunayak.freemusic.desktop.platform.ShellCommands
import com.ihimanshunayak.freemusic.desktop.platform.SystemBackdrop
import com.ihimanshunayak.freemusic.desktop.platform.TaskbarState
import com.ihimanshunayak.freemusic.desktop.platform.TrayController
import com.ihimanshunayak.freemusic.desktop.platform.applyTitleBarTheme
import com.ihimanshunayak.freemusic.desktop.platform.applyWindowFrame
import com.ihimanshunayak.freemusic.desktop.platform.awaitAndApplyWindowFrame
import com.ihimanshunayak.freemusic.desktop.ui.App
import com.ihimanshunayak.freemusic.desktop.ui.state.BrowseViewModel
import com.ihimanshunayak.freemusic.desktop.ui.theme.FreeMusicTheme
import com.ihimanshunayak.freemusic.desktop.ui.theme.resolveDarkTheme
import com.ihimanshunayak.freemusic.desktop.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.Frame
import java.awt.Window as AwtWindow
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFileChooser
import javax.swing.UIManager

/** Set once at startup so the version is in every log line of a support bundle. */
private const val APP_NAME = "Free Music"

/**
 * How often transport state is pushed to the Windows shell.
 *
 * The taskbar indicator is animated from outside the composition, and it does
 * not need a frame-accurate clock: a quarter of a second is below the threshold
 * where a progress fill looks stepped and well above the cost of one shell call.
 */
private const val SHELL_SYNC_INTERVAL_MS = 250L

fun main() {
    Log.i("$APP_NAME for Windows starting (Java ${System.getProperty("java.version")})", tag = "main")

    // The Swing file chooser inherits its look from the platform LAF; without
    // this it renders as a Motif dialog on Windows.
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

    val container = AppContainer()
    val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            Log.i("$APP_NAME shutting down", tag = "main")
            container.close()
        }
    )

    application {
        val windowState = rememberWindowState(
            size = DpSize(1180.dp, 760.dp),
            position = WindowPosition.Aligned(Alignment.Center),
        )

        // The tray, the media keys and the taskbar state outlive any single
        // composition, so they are built here rather than remembered inside the
        // window content - a recomposition must not re-register a hotkey.
        val shell = remember { ShellHost(container, uiScope) }

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = APP_NAME,
        ) {
            // A minimum size is enforced because the transport bar and the
            // sidebar together need about this much room before they start to
            // overlap - Compose will happily shrink a window past usefulness.
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(900, 620)
                shell.attach(window)
            }

            LaunchedEffect(Unit) {
                container.initialise { }
                container.library.refresh()
            }

            val viewModel = remember { BrowseViewModel(container.music, uiScope) }
            val settings by container.settings.flow.collectAsState()
            val snapshot by container.player.snapshot.collectAsState()
            val dark = resolveDarkTheme(settings.theme)

            // The window frame is re-applied whenever the theme flips: the
            // immersive dark title bar is a per-window attribute and Windows
            // resets it when the caption is redrawn on a theme change.
            LaunchedEffect(dark) {
                applyTitleBarTheme(window, dark)
            }

            // The Mica switch changes the backdrop, which is also per-window and
            // independent of the theme, so it gets its own effect rather than
            // riding along on the theme one.
            LaunchedEffect(settings.micaBackdrop) {
                shell.syncBackdrop()
            }

            // The tray and the taskbar follow the player's own snapshot rather
            // than callbacks from the UI, so a track started by a global media
            // key updates them exactly like one started by a click.
            LaunchedEffect(Unit) {
                container.player.snapshot
                    .map { snapshot ->
                        Triple(
                            snapshot.currentTrack?.let { "${it.title} - ${it.artist}" },
                            snapshot.state,
                            snapshot.progress,
                        )
                    }
                    .distinctUntilChanged()
                    .collect { (description, state, progress) ->
                        shell.onSnapshot(
                            description = description,
                            playing = state == PlaybackState.PLAYING,
                            progress = progress,
                        )
                    }
            }

            FreeMusicTheme(
                preference = settings.theme,
                artworkUrl = snapshot.currentTrack?.thumbnailUrl,
            ) {
                App(
                    container = container,
                    viewModel = viewModel,
                    onPickFolder = ::pickFolder,
                )
            }
        }
    }
}

/**
 * Owns the Windows-shell integrations and keeps them in step with the player.
 *
 * Split out of `main` because the lifecycle is different: the composition is
 * torn down and rebuilt on every theme flip, while a registered global hotkey,
 * a tray icon and a taskbar registration are process-wide and must be created
 * exactly once and released exactly once.
 */
private class ShellHost(
    private val container: AppContainer,
    private val scope: CoroutineScope,
) : ShellCommands {

    private var tray: TrayController? = null
    private var mediaKeys: MediaKeyService? = null
    private var taskbar: TaskbarState? = null
    private var window: AwtWindow? = null
    private val started = AtomicBoolean(false)

    /**
     * The backdrop most recently requested, so a redundant re-apply is skipped.
     *
     * Re-setting [DwmAttribute.SYSTEMBACKDROP_TYPE] makes the compositor cross-fade
     * the wallpaper, so a launch that applied Mica and then had the settings effect
     * ask for Mica again would visibly flicker. Only a real change is worth the
     * call, and `null` means nothing has been applied yet.
     */
    @Volatile
    private var appliedBackdrop: SystemBackdrop? = null

    /**
     * The most recent state pushed to the shell.
     *
     * Held rather than read from the flow because the refresh loop runs off the
     * UI thread and `StateFlow.value` would be re-read on every tick even when
     * nothing changed, which the shell surfaces would then redraw for nothing.
     */
    @Volatile
    private var description: String? = null

    @Volatile
    private var playing: Boolean = false

    @Volatile
    private var progress: Float = 0f

    fun attach(awtWindow: AwtWindow) {
        window = awtWindow
        taskbar = TaskbarState(awtWindow)

        // `attach` is called from a LaunchedEffect, which can re-run; only the
        // first pass is allowed to touch process-wide resources.
        if (!started.compareAndSet(false, true)) return

        applyWindowChrome(awtWindow)
        installTray()
        mediaKeys = MediaKeyService(this).also { it.start() }
        startShellSync()
    }

    private fun applyWindowChrome(awtWindow: AwtWindow) {
        // Mica is the Windows 11 "wallpaper through the window" material and the
        // one Explorer uses. The helper falls back to the older boolean Mica
        // attribute on 21H2 builds, and reports what actually took.
        //
        // Both flags are read here rather than passed in because `attach` runs
        // outside any composition, where a `@Composable` read has no owner - the
        // theme is a plain settings value, so it can be snapshotted instead.
        val report = awaitAndApplyWindowFrame(
            window = awtWindow,
            backdrop = requestedBackdrop(),
            darkTitleBar = container.settings.current.theme != ThemePreference.LIGHT,
        )
        if (report == null) {
            Log.i("window chrome left to Windows (DWM not available)", tag = "shell")
        } else {
            appliedBackdrop = requestedBackdrop()
            Log.i("window chrome applied: ${report.summary}", tag = "shell")
        }
    }

    /**
     * The backdrop the user's settings ask for.
     *
     * `micaBackdrop` is a user-facing switch, so turning it off has to mean
     * something. Setting the attribute to Mica unconditionally, as this once did,
     * made the switch a half-measure: the window frame kept asking the compositor
     * to blur the wallpaper through an app that was no longer drawing anything
     * translucent, which leaves a seam the user cannot explain and cannot turn off.
     */
    private fun requestedBackdrop(): SystemBackdrop =
        if (container.settings.current.micaBackdrop) SystemBackdrop.MICA else SystemBackdrop.NONE

    /**
     * Re-applies the backdrop when the user flips the Mica switch.
     *
     * Separate from [applyWindowChrome] because that runs once per process: the
     * tray, the hotkeys and the taskbar registration are process-wide, while a
     * backdrop is a per-window attribute that can be changed at any moment. A
     * request matching what is already applied is dropped, so the settings effect
     * firing on the first composition does not re-set the attribute and make the
     * compositor cross-fade the wallpaper for nothing.
     */
    fun syncBackdrop() {
        val target = window ?: return
        val wanted = requestedBackdrop()
        if (wanted == appliedBackdrop) return
        val report = runCatching {
            applyWindowFrame(target, backdrop = wanted, darkTitleBar = isDark())
        }.onFailure { Log.w("could not re-apply the window backdrop: ${it.message}", tag = "shell") }
            .getOrNull()
        // Only a real report counts: a null return means the handle was not ready,
        // and caching that as applied would leave the switch dead for the session.
        if (report != null) {
            appliedBackdrop = wanted
            Log.i("window backdrop changed: ${report.summary}", tag = "shell")
        }
    }

    /** The theme the title bar should match, read without a composition owner. */
    private fun isDark(): Boolean = container.settings.current.theme != ThemePreference.LIGHT

    private fun installTray() {
        val controller = TrayController(this)
        if (controller.install()) tray = controller
    }

    /**
     * Re-pushes the taskbar indicator on a timer.
     *
     * A loop rather than a collector, because the taskbar progress has to keep
     * up with a track whose position changes without the snapshot's *state*
     * changing - which is exactly the case `distinctUntilChanged` filters out
     * upstream.
     */
    private fun startShellSync() {
        scope.launch {
            while (isActive) {
                delay(SHELL_SYNC_INTERVAL_MS)
                runCatching {
                    taskbar?.setProgress(progress.takeIf { playing })
                }.onFailure { Log.d("shell sync skipped: ${it.message}", tag = "shell") }
            }
        }
    }

    /** Called whenever the player's description, state or position changes. */
    fun onSnapshot(description: String?, playing: Boolean, progress: Float) {
        this.description = description
        this.playing = playing
        this.progress = progress

        tray?.updateNowPlaying(description)
        tray?.setPlaying(playing)
        taskbar?.setPlaying(playing)
        taskbar?.setNowPlaying(description)
    }

    // ---- ShellCommands: what the tray menu and the media keys can do ----

    override fun togglePlayPause() {
        container.player.togglePlayPause()
    }

    override fun next() {
        container.player.next()
    }

    override fun previous() {
        container.player.previous()
    }

    override fun showWindow() {
        val target = window ?: return
        // `isVisible` is not enough: a minimised window is visible but not on
        // screen, and restoring it takes the extended state, not the flag.
        if (target is Frame) {
            target.extendedState = target.extendedState and Frame.ICONIFIED.inv()
        }
        target.isVisible = true
        target.toFront()
        target.requestFocus()
    }

    override fun exit() {
        Log.i("exit requested from the shell", tag = "shell")
        // A hard exit rather than `exitApplication`, because this runs on the
        // AWT event thread; the shutdown hook is what releases the engine.
        kotlin.system.exitProcess(0)
    }

    override fun toggleShuffle() {
        val next = !container.player.snapshot.value.shuffle
        container.player.setShuffle(next)
        container.settings.update { it.copy(shuffle = next) }
    }

    override fun toggleRepeat() {
        val next = when (container.player.snapshot.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        container.player.setRepeatMode(next)
        container.settings.update { it.copy(repeatMode = next) }
    }
}

/**
 * Opens the OS folder chooser on the Swing event thread.
 *
 * [onPicked] is invoked on the UI thread with the chosen absolute path, or with
 * null when the user cancelled - the caller is expected to treat those the same
 * way it would treat "no folder added yet".
 */
private fun pickFolder(title: String, onPicked: (String?) -> Unit) {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isMultiSelectionEnabled = false
    }
    val result = chooser.showOpenDialog(null)
    onPicked(if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null)
}
