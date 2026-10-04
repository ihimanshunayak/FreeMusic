// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - system-wide media keys.
//
// NAME
//     MediaKeys.kt - global hotkeys for the transport, plus taskbar state.
//
// DESCRIPTION
//     Windows routes the keyboard's media keys through the System Media
//     Transport Controls (SMTC), a WinRT surface that a plain JVM process
//     cannot register with. The practical consequence is that on a laptop the
//     play/pause key would otherwise do nothing while Free Music is playing.
//
//     This closes that gap with `RegisterHotKey`, the classic Win32 mechanism.
//     It is not a perfect substitute - SMTC also feeds the Windows 11 volume
//     flyout and the lock screen - but it makes the physical keys work, which
//     is what the user actually presses.
//
// RESPONSIBILITIES
//     - Register the media keys as global hotkeys while the app runs.
//     - Translate them into [ShellCommands] calls.
//     - Report taskbar playback state so the icon animates like a player's.
//
// DEPENDENCIES
//     - JNA `User32` for `RegisterHotKey` and a dedicated message pump.
//
// INTEGRATION NOTES
//     - `RegisterHotKey` needs a message loop on the registering thread.
//       [MediaKeyService.start] therefore owns a daemon thread that only pumps
//       messages, which also keeps the registration valid for the process's
//       whole life without touching the Compose thread.
//     - Registration is exclusive: if another player already holds a key, the
//       call fails for that key alone. Failures are logged and skipped so the
//       remaining keys still work.

package com.ihimanshunayak.freemusic.desktop.platform

import com.ihimanshunayak.freemusic.desktop.util.Log
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The two `java.awt.Taskbar.State` constants this file needs, under short
 * local names.
 *
 * `Taskbar.State` is a *nested enum* on `java.awt.Taskbar`, so the constants
 * are accessed as `Taskbar.State.NORMAL`, not `Taskbar.STATE_NORMAL`. Writing
 * the latter - which reads naturally, because `java.awt.Window` really does
 * expose flat `STATE_*` ints - produces an "unresolved reference" that points
 * at the enum rather than at the typo.
 */
private val TASKBAR_STATE_NORMAL: java.awt.Taskbar.State = java.awt.Taskbar.State.NORMAL
private val TASKBAR_STATE_PAUSED: java.awt.Taskbar.State = java.awt.Taskbar.State.PAUSED

/** The Win32 virtual-key codes the media row sends. */
private object VirtualKey {
    const val MEDIA_NEXT_TRACK = 0xB0
    const val MEDIA_PREV_TRACK = 0xB1
    const val MEDIA_STOP = 0xB2
    const val MEDIA_PLAY_PAUSE = 0xB3

    /** The volume keys double as transport modifiers when held with Ctrl. */
    const val VOLUME_MUTE = 0xAD
    const val VOLUME_DOWN = 0xAE
    const val VOLUME_UP = 0xAF

    /** A harmless key used as the registration id space; ids must be unique per thread. */
    const val MOD_NOREPEAT = 0x4000
}

/**
 * Owns the global hotkey registrations.
 *
 * The service is deliberately separate from the player so that the keyboard
 * layer never holds a reference to the audio engine, and so a failure to grab a
 * key cannot affect playback. Call [start] once, after the window exists, and
 * [stop] on shutdown.
 */
class MediaKeyService(private val commands: ShellCommands) {

    private val started = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var running = false

    /** Which bindings actually took, for the diagnostics screen. */
    @Volatile var registeredKeys: List<String> = emptyList()
        private set

    /**
     * Starts the message pump and registers the media keys.
     *
     * @param volumeUp optional handler for the volume keys; when null those keys
     *   are left alone so Windows keeps its own behaviour.
     */
    fun start(volumeUp: (() -> Unit)? = null, volumeDown: (() -> Unit)? = null): Boolean {
        if (!windowsEffectsAvailable) return false
        if (!started.compareAndSet(false, true)) return false

        running = true
        val worker = Thread({ pump(volumeUp, volumeDown) }, "freemusic-media-keys").apply {
            isDaemon = true
        }
        thread = worker
        worker.start()
        return true
    }

    /** Unregisters every hotkey and stops the pump. Safe to call twice. */
    fun stop() {
        if (!started.compareAndSet(true, false)) return
        running = false
        // Posting a message to our own thread is what wakes the blocking
        // GetMessage; without it the daemon thread would only exit at JVM stop.
        thread?.let { worker ->
            val id = workerId.get()
            if (id != 0) {
                User32.INSTANCE.PostThreadMessage(id, WM_QUIT, WinDef.WPARAM(0), WinDef.LPARAM(0))
            }
        }
        thread = null
    }

    private fun pump(volumeUp: (() -> Unit)?, volumeDown: (() -> Unit)?) {
        val user32 = User32.INSTANCE
        val threadId = Kernel32.INSTANCE.GetCurrentThreadId()
        workerId.set(threadId)

        val bindings = buildList {
            add(Triple(VirtualKey.MEDIA_PLAY_PAUSE, "Play/Pause") { commands.togglePlayPause() })
            add(Triple(VirtualKey.MEDIA_NEXT_TRACK, "Next") { commands.next() })
            add(Triple(VirtualKey.MEDIA_PREV_TRACK, "Previous") { commands.previous() })
            add(Triple(VirtualKey.MEDIA_STOP, "Stop") { commands.togglePlayPause() })
            if (volumeUp != null) add(Triple(VirtualKey.VOLUME_UP, "Volume up") { volumeUp() })
            if (volumeDown != null) add(Triple(VirtualKey.VOLUME_DOWN, "Volume down") { volumeDown() })
        }

        val handlers = HashMap<Int, () -> Unit>()
        val accepted = mutableListOf<String>()

        bindings.forEachIndexed { index, (key, label, action) ->
            // The id is only unique per (thread, id) pair, and must be non-zero.
            val id = index + 1
            val ok = user32.RegisterHotKey(null, id, VirtualKey.MOD_NOREPEAT, key)
            if (ok) {
                handlers[id] = action
                accepted += label
            } else {
                Log.d("Media key '$label' (0x${key.toString(16)}) already held", tag = "mediakeys")
            }
        }

        registeredKeys = accepted
        Log.i("Global media keys registered: ${accepted.joinToString(", ").ifEmpty { "none" }}", tag = "mediakeys")

        val message = WinUser.MSG()
        while (running) {
            // GetMessage blocks until a message arrives, including the WM_QUIT
            // posted by stop(); a non-blocking PeekMessage would spin the CPU.
            val result = user32.GetMessage(message, null, 0, 0)
            if (result == 0 || result == -1) break
            if (message.message == WM_HOTKEY) {
                val action = handlers[message.wParam.toInt()]
                runCatching { action?.invoke() }
                    .onFailure { Log.d("Media key handler failed: ${it.message}", tag = "mediakeys") }
            }
        }

        handlers.keys.forEach { id -> runCatching { user32.UnregisterHotKey(null, id) } }
        workerId.set(0)
        Log.i("Media key pump stopped", tag = "mediakeys")
    }

    private companion object {
        const val WM_HOTKEY = 0x0312
        const val WM_QUIT = 0x0012
        val workerId = java.util.concurrent.atomic.AtomicInteger(0)
    }
}

/**
 * Reports playback to the taskbar.
 *
 * The taskbar exposes a per-window overlay icon and a progress bar; both are
 * cheap Win32 calls through the shell COM interfaces, and both are what makes a
 * running player look alive on the taskbar rather than like an idle window.
 *
 * The implementation is intentionally limited to what the shell supports
 * without a COM vtable written by hand: the window's own state. Controls
 * grouped on the taskbar button (Windows 7's "thumbbar") need `ITaskbarList3`,
 * which JNA does not expose, and are therefore left out rather than faked.
 */
class TaskbarState(private val window: java.awt.Window) {

    @Volatile private var lastDescription: String? = null
    @Volatile private var lastProgress: Float? = null
    @Volatile private var lastPlaying: Boolean? = null

    /**
     * Updates the window's own taskbar caption.
     *
     * Windows shows the taskbar button label from the window title, so
     * appending the current track is the supported way to surface it without
     * `ITaskbarList3`. The AWT title is kept in sync so the change is visible
     * immediately rather than only after the next repaint.
     *
     * @param track a "Title - Artist" line, or null to clear.
     */
    fun setNowPlaying(track: String?) {
        if (track == lastDescription) return
        lastDescription = track
        val frame = window as? java.awt.Frame ?: return
        val base = BASE_TITLE
        runCatching {
            frame.title = if (track.isNullOrBlank()) base else "$track - $base"
        }
    }

    /**
     * Sets the taskbar progress indicator.
     *
     * AWT's `Taskbar` is the supported path: it maps to `ITaskbarList3` inside
     * the JDK, which is the same interface Explorer uses, so the button gets the
     * real green fill rather than a client property nobody reads. `null` clears
     * the indicator, which is the correct state for a paused or finished track.
     *
     * @param fraction 0..1, or null to clear.
     */
    fun setProgress(fraction: Float?) {
        if (fraction == lastProgress) return
        lastProgress = fraction
        runCatching {
            val taskbar = java.awt.Taskbar.getTaskbar()
            if (fraction == null) {
                taskbar.setWindowProgressState(window, TASKBAR_STATE_NORMAL)
                taskbar.setWindowProgressValue(window, 0)
            } else {
                taskbar.setWindowProgressValue(window, (fraction.coerceIn(0f, 1f) * 100f).toInt())
            }
        }
    }

    /**
     * Switches the taskbar button between the playing and paused indicators.
     *
     * Windows draws the progress fill in the app's own accent while playing and
     * a muted yellow while paused, so this is also the cue that tells a user
     * glancing at the taskbar whether the app is still making sound.
     */
    fun setPlaying(playing: Boolean) {
        if (playing == lastPlaying) return
        lastPlaying = playing
        runCatching {
            val taskbar = java.awt.Taskbar.getTaskbar()
            taskbar.setWindowProgressState(
                window,
                if (playing) {
                    TASKBAR_STATE_NORMAL
                } else {
                    TASKBAR_STATE_PAUSED
                },
            )
        }
    }

    private companion object {
        const val BASE_TITLE = "Free Music for Windows"
    }}

/** Suppresses unused-import warnings for the JNA types used only in the pump. */
private val nativeProbe: Pointer? = null
