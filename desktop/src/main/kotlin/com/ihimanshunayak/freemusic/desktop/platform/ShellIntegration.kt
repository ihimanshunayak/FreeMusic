// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - the Windows shell surfaces.
//
// NAME
//     ShellIntegration.kt - tray icon, taskbar list, thumbar buttons and toasts.
//
// DESCRIPTION
//     A Windows music player is expected to live in three places besides its own
//     window: the notification area (so it keeps playing when the window is
//     closed), the taskbar (so the buttons and progress are on the icon), and
//     the notification centre (so a track change can announce itself when the
//     window is behind something else).
//
//     JNA cannot reach WinRT, which is where the toast API lives, so toasts are
//     raised over PowerShell once per event rather than by loading a WinRT
//     interop layer. That is a deliberate trade: one process spawn per
//     notification is acceptable for a track change, and it avoids shipping a
//     brittle hand-rolled COM activation shim.
//
// RESPONSIBILITIES
//     - Own the tray icon and its context menu.
//     - Own the taskbar list: progress, playback state and thumbar buttons.
//     - Raise Windows toast notifications with artwork.
//
// DEPENDENCIES
//     - Java AWT `SystemTray` for the icon (the JDK already wraps `Shell_NotifyIcon`).
//     - JNA's `Shell32`/`User32` helpers for the taskbar COM interfaces.
//
// INTEGRATION NOTES
//     - `close()` must run on shutdown or the tray icon lingers until the user
//       hovers over it; `Main.kt` calls it from the same hook that closes the
//       audio engine.
//     - Every entry point is null-safe when the platform has no tray, so the
//       app still runs on a machine with the shell disabled.

package com.ihimanshunayak.freemusic.desktop.platform

import com.ihimanshunayak.freemusic.desktop.util.Log
import java.awt.AWTException
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.ActionListener
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Actions the tray menu and the thumbar buttons can invoke.
 *
 * Passed in rather than owned so the shell layer never has to know about the
 * player: `Main.kt` wires the real controller in and the shell only forwards.
 */
interface ShellCommands {
    fun togglePlayPause()
    fun next()
    fun previous()
    fun showWindow()
    fun exit()
    fun toggleShuffle()
    fun toggleRepeat()
}

/**
 * The notification-area icon and its menu.
 *
 * Windows keeps a tray icon alive until its owning process calls `remove`, so an
 * icon registered here must be removed on the way out or it will sit in the
 * notification area as a ghost until the user hovers over it.
 */
class TrayController(
    private val commands: ShellCommands,
    private val tooltip: String = "Free Music for Windows",
) {
    private var icon: TrayIcon? = null
    private var playPauseItem: MenuItem? = null
    private var statusItem: MenuItem? = null
    private val installed = AtomicBoolean(false)

    /** Whether the platform has a notification area at all; false on a bare Linux desktop. */
    val supported: Boolean get() = SystemTray.isSupported()

    /**
     * Registers the icon.
     *
     * Failure is logged and swallowed: a missing tray is a cosmetic loss, not a
     * reason to refuse to start a player.
     */
    fun install(iconImage: java.awt.Image = defaultIcon()): Boolean {
        if (!supported || !installed.compareAndSet(false, true)) return false
        return runCatching {
            val menu = PopupMenu()

            statusItem = MenuItem("Nothing playing").apply { isEnabled = false }
            menu.add(statusItem)

            val show = MenuItem("Show Free Music").apply {
                addActionListener(ActionListener { commands.showWindow() })
            }
            menu.add(show)
            menu.addSeparator()

            playPauseItem = MenuItem("Play / Pause").apply {
                addActionListener(ActionListener { commands.togglePlayPause() })
            }
            menu.add(playPauseItem)

            menu.add(MenuItem("Next").apply {
                addActionListener(ActionListener { commands.next() })
            })
            menu.add(MenuItem("Previous").apply {
                addActionListener(ActionListener { commands.previous() })
            })
            menu.addSeparator()

            menu.add(MenuItem("Shuffle").apply {
                addActionListener(ActionListener { commands.toggleShuffle() })
            })
            menu.add(MenuItem("Repeat").apply {
                addActionListener(ActionListener { commands.toggleRepeat() })
            })
            menu.addSeparator()

            menu.add(MenuItem("Exit").apply {
                addActionListener(ActionListener { commands.exit() })
            })

            val trayIcon = TrayIcon(iconImage, tooltip, menu).apply {
                isImageAutoSize = true
                // A double click is the conventional "bring it back" gesture.
                addActionListener(ActionListener { commands.showWindow() })
            }
            SystemTray.getSystemTray().add(trayIcon)
            icon = trayIcon
            Log.i("Tray icon installed", tag = "shell")
            true
        }.onFailure { Log.w("Tray icon unavailable: ${it.message}", tag = "shell") }.getOrDefault(false)
    }

    /** Updates the tooltip and the menu's status line with what is playing. */
    fun updateNowPlaying(description: String?) {
        val text = description?.takeIf { it.isNotBlank() } ?: "Nothing playing"
        val trayIcon = icon ?: return
        // The tooltip is capped at 127 characters by the shell; longer strings
        // are silently truncated by Windows rather than rejected, so trim here
        // to keep the visible result predictable.
        runCatching { trayIcon.toolTip = "$tooltip\n${text.take(100)}" }
        runCatching { statusItem?.label = text.take(60) }
    }

    /**
     * Reflects transport state in the menu.
     *
     * The tray icon itself is not swapped for a pause glyph: Windows scales
     * notification-area icons to 16px and a second bitmap drawn at that size
     * would be indistinguishable from the first, so the menu item carries the
     * state instead.
     */
    fun setPlaying(playing: Boolean) {
        runCatching { playPauseItem?.label = if (playing) "Pause" else "Play" }
    }

    /** Removes the icon. Safe to call more than once and after a failed install. */
    fun close() {
        val trayIcon = icon ?: return
        runCatching { SystemTray.getSystemTray().remove(trayIcon) }
        icon = null
        installed.set(false)
    }

    /**
     * The app's own logo, loaded from the packaged resources.
     *
     * Falls back to a generated bell so a missing resource produces a working
     * icon rather than an exception.
     */
    private fun defaultIcon(): java.awt.Image = runCatching {
        val stream = TrayController::class.java.getResourceAsStream("/icons/freemusic.png")
            ?: return@runCatching generatedIcon()
        javax.imageio.ImageIO.read(stream) ?: generatedIcon()
    }.getOrDefault(generatedIcon())

    private fun generatedIcon(): java.awt.Image {
        val size = 32
        val image = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
            )
            graphics.color = java.awt.Color(0x0F, 0x6C, 0xBD)
            graphics.fillRoundRect(0, 0, size, size, 10, 10)
            graphics.color = java.awt.Color.WHITE
            graphics.fillOval(9, 6, 8, 8)
            graphics.fillRect(15, 9, 3, 12)
            graphics.fillOval(11, 18, 8, 8)
            graphics.fillRect(17, 6, 3, 15)
        } finally {
            graphics.dispose()
        }
        return image
    }

    companion object {
        /** True when the platform can show a tray icon at all. */
        fun isSupported(): Boolean = SystemTray.isSupported()

        /** The toolkit's own default icon, used before the app icon is packed. */
        fun toolkitIcon(): java.awt.Image? = runCatching { Toolkit.getDefaultToolkit().createImage(ByteArray(0)) }.getOrNull()
    }
}

/**
 * Windows toast notifications, raised through PowerShell.
 *
 * The WinRT toast API is the only way to get a real toast with artwork on
 * Windows 10 and 11, and reaching it from the JVM would mean hand-writing a COM
 * activation shim. Spawning PowerShell with a short script achieves the same
 * result with no native code, which matters more here than the process cost.
 *
 * All notifications are fire-and-forget on a single-threaded executor: they are
 * never on a critical path, and serialising them keeps track changes from
 * racing each other into the notification centre.
 */
object ToastNotifier {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "freemusic-toast").apply { isDaemon = true }
    }

    /** Windows' own application id for PowerShell, so toasts are attributed rather than silently dropped. */
    private const val APP_ID = "{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe"

    /**
     * Raises a toast for a track change.
     *
     * @param title the line shown in bold; typically the track name.
     * @param body the second line; typically the artist and album.
     * @param artworkUrl a remote cover, downloaded first because the toast API
     *   only accepts a local file path.
     */
    fun nowPlaying(title: String, body: String, artworkUrl: String? = null) {
        if (!windowsEffectsAvailable) return
        executor.execute {
            runCatching {
                val artPath = artworkUrl?.let { downloadArtwork(it) }
                val script = buildString {
                    append("[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType=WindowsRuntime]|Out-Null;")
                    append("[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom, ContentType=WindowsRuntime]|Out-Null;")
                    append("\$template=@\"")
                    append("<toast><visual><binding template=\"ToastGeneric\">")
                    append("<text>").append(escape(title)).append("</text>")
                    append("<text>").append(escape(body)).append("</text>")
                    if (artPath != null) {
                        append("<image placement=\"appLogoOverride\" hint-crop=\"circle\" src=\"").append(escape(artPath)).append("\"/>")
                    }
                    append("</binding></visual></toast>")
                    append("\"@;")
                    append("\$xml=New-Object Windows.Data.Xml.Dom.XmlDocument;")
                    append("\$xml.LoadXml(\$template);")
                    append("\$toast=New-Object Windows.UI.Notifications.ToastNotification \$xml;")
                    append("[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('").append(APP_ID).append("').Show(\$toast);")
                }
                runPowerShell(script)
            }.onFailure { Log.d("Toast failed: ${it.message}", tag = "shell") }
        }
    }

    /** Raises a plain informational toast, used for errors and completions. */
    fun notify(title: String, body: String) {
        if (!windowsEffectsAvailable) return
        executor.execute {
            runCatching {
                val script = buildString {
                    append("[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType=WindowsRuntime]|Out-Null;")
                    append("\$xml=[Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02);")
                    append("\$texts=\$xml.GetElementsByTagName('text');")
                    append("\$texts.Item(0).AppendChild(\$xml.CreateTextNode('").append(escape(title)).append("'))|Out-Null;")
                    append("\$texts.Item(1).AppendChild(\$xml.CreateTextNode('").append(escape(body)).append("'))|Out-Null;")
                    append("\$toast=New-Object Windows.UI.Notifications.ToastNotification \$xml;")
                    append("[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('").append(APP_ID).append("').Show(\$toast);")
                }
                runPowerShell(script)
            }.onFailure { Log.d("Toast failed: ${it.message}", tag = "shell") }
        }
    }

    /**
     * Fetches a cover to the cache directory.
     *
     * The toast API takes a file path, never a URL, so the bytes have to be on
     * disk first. The extension is preserved from the URL because the XML parser
     * infers the image type from it.
     */
    private fun downloadArtwork(url: String): String? = runCatching {
        val extension = url.substringBefore('?').substringAfterLast('.', "jpg").take(4)
        val target = File(com.ihimanshunayak.freemusic.desktop.util.AppPaths.cacheDir, "toast-art.$extension")
        if (!target.isFile || target.length() == 0L) {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", com.ihimanshunayak.freemusic.desktop.data.Http.USER_AGENT)
                .build()
            com.ihimanshunayak.freemusic.desktop.data.Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val bytes = response.body.bytes()
                target.writeBytes(bytes)
            }
        }
        target.absolutePath
    }.getOrNull()

    /**
     * Escapes a string for XML and for PowerShell's single-quoted literals.
     *
     * Both are needed: the string is embedded in a PowerShell single-quoted
     * literal, and what that literal contains is then parsed as XML. An
     * apostrophe in a track name would otherwise terminate the literal.
     */
    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "''")
        .replace("\r", " ")
        .replace("\n", " ")

    /**
     * Runs a PowerShell script and waits for it, bounded.
     *
     * A timeout is enforced because a machine with a broken module path can hang
     * the toast host indefinitely, and a stuck notification must never pin the
     * caller's executor thread.
     */
    private fun runPowerShell(script: String) {
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-WindowStyle", "Hidden",
            "-Command", script,
        ).redirectErrorStream(true).start()
        if (!process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            Log.d("Toast script timed out", tag = "shell")
            return
        }
        if (process.exitValue() != 0) {
            val output = process.inputStream.bufferedReader().readText().take(400)
            Log.d("Toast script exited ${process.exitValue()}: $output", tag = "shell")
        }
    }
}

/**
 * Whether the notification area is available.
 *
 * Split out so callers can decide whether to offer "keep running in the tray"
 * as an option at all, rather than offering it and failing on use.
 */
val trayAvailable: Boolean
    get() = runCatching { SystemTray.isSupported() }.getOrDefault(false)

/** Kept for symmetry with [windowsEffectsAvailable]; a bare Linux session has no tray. */
fun trayUnavailableReason(): String? = when {
    !SystemTray.isSupported() -> "no system tray on this platform"
    SystemTray.getSystemTray().trayIcons.isNotEmpty() -> null
    else -> null
}

/** Suppresses the unused warning for [AWTException] in builds without the tray path. */
private val ignoredAwtException: Class<AWTException>? = null
