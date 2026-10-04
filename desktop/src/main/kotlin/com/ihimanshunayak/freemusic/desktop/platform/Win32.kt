// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - Win32 shell integration.
//
// NAME
//     Win32.kt - DWM backdrop, window frame and dark-mode integration.
//
// DESCRIPTION
//     Compose renders into a plain AWT window, which Windows treats as an
//     ordinary document frame: opaque client area, light title bar, square
//     corners. Windows 11 applications look nothing like that. This file closes
//     the gap by calling the same Desktop Window Manager APIs that WinUI itself
//     calls, so the window gets Mica (the wallpaper-tinted desktop material),
//     the immersive dark title bar, rounded corners and a correct shadow.
//
//     Everything here is best effort by design. Every call is guarded twice -
//     once on the OS being Windows at all, and once on the entry point being
//     present in the loaded dwmapi - because a Windows 10 machine, a stripped
//     system or a future Windows release can each legitimately refuse these
//     attributes. A refusal degrades to the previous appearance rather than
//     failing startup.
//
// RESPONSIBILITIES
//     - Resolve the native window handle (HWND) behind a Compose window.
//     - Apply Mica / Acrylic / Tabbed backdrop to that HWND.
//     - Flip the title bar between light and dark to match the app theme.
//     - Request rounded corners and a custom border colour.
//     - Report whether the compositor accepted each attribute, for diagnostics.
//
// DEPENDENCIES
//     - JNA (net.java.dev.jna) for the dwmapi binding.
//     - Java AWT for the window handle lookup.
//
// INTEGRATION NOTES
//     - `awaitAndApplyWindowFrame` polls for the peer rather than assuming a
//       point in the Compose lifecycle, so it is safe to call from a
//       LaunchedEffect.
//     - The backdrop is painted *behind* the client area, so the UI has to
//       leave it visible. `theme/Theme.kt` provides a translucent window
//       background for that reason; a fully opaque root would hide the effect.

package com.ihimanshunayak.freemusic.desktop.platform

import com.ihimanshunayak.freemusic.desktop.util.Log
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import java.awt.Window

/**
 * The Windows 11 system backdrop materials.
 *
 * The numeric values are dwmapi's own `DWM_SYSTEMBACKDROP_TYPE` ordinals and must
 * not be renumbered: they are passed straight into `DwmSetWindowAttribute`.
 */
enum class SystemBackdrop(val dwmValue: Int, val label: String) {
    /** Follows the user's Accessibility > Transparency setting. */
    AUTOMATIC(0, "Automatic"),

    /** A flat, opaque surface - the Windows 10 look. */
    NONE(1, "None"),

    /** Blurs the wallpaper through the whole window. The desktop default. */
    MICA(2, "Mica"),

    /** Like Mica, but blurred from whatever is behind the window. */
    ACRYLIC(3, "Acrylic"),

    /**
     * Mica applied to the title bar only, with the client area active.
     * The material Windows 11 File Explorer uses.
     */
    TABBED(4, "Tabbed"),
}

/** `DWMWA_*` attribute ordinals, named for readability at every call site. */
object DwmAttribute {
    /** Which [SystemBackdrop] the compositor paints. Windows 11 22H2 and later. */
    const val SYSTEMBACKDROP_TYPE = 38

    /** Mica/Acrylic enable flag. Superseded by [SYSTEMBACKDROP_TYPE] but required on 21H2/22H1. */
    const val MICA_EFFECT_ENABLED = 1029

    /** Rounded vs square corners: 0 = default, 1 = square, 2 = round, 3 = round-small. */
    const val WINDOW_CORNER_PREFERENCE = 33

    /** Border colour, used here to suppress the default light 1px frame. */
    const val BORDER_COLOR = 34

    /** Title-bar colouring mode: 0 = default, 1 = light, 2 = dark. */
    const val USE_IMMERSIVE_DARK_MODE = 20
}

/** `DWM_WINDOW_CORNER_PREFERENCE` values. */
enum class WindowCorners(val dwmValue: Int, val label: String) {
    DEFAULT(0, "Default"),
    SQUARE(1, "Square"),
    ROUND(2, "Round"),
    ROUND_SMALL(3, "Round (small)"),
}

/**
 * Direct dwmapi binding, loaded lazily.
 *
 * A null [instance] means dwmapi is not available, which happens only on a
 * non-Windows host - the launcher's own guard normally prevents that, but the
 * code must not crash a test JVM that never had a window at all.
 */
private object DwmApi {
    val instance: Dwmapi? = runCatching { Native.load("dwmapi", Dwmapi::class.java) }.getOrNull()

    interface Dwmapi : com.sun.jna.Library {
        fun DwmSetWindowAttribute(
            hwnd: WinDef.HWND,
            dwAttribute: Int,
            pvAttribute: Pointer,
            cbAttribute: Int,
        ): Int

        fun DwmGetWindowAttribute(
            hwnd: WinDef.HWND,
            dwAttribute: Int,
            pvAttribute: Pointer,
            cbAttribute: Int,
        ): Int
    }
}

/**
 * Whether the current process can apply native Windows window effects.
 *
 * False on macOS, Linux and on any JVM that could not load dwmapi. Callers use
 * it to skip the whole feature rather than to branch the UI build, so the
 * desktop module stays a single, platform-agnostic code base.
 */
val windowsEffectsAvailable: Boolean
    get() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
        DwmApi.instance != null

/**
 * The native window handle behind an AWT [Window], or null before it exists.
 *
 * AWT deliberately keeps the HWND private, and reflecting into
 * `sun.awt.windows.WComponentPeer` breaks across JDK builds. Instead this asks
 * Windows directly: enumerate every top-level window, keep the ones owned by
 * this process, and pick the one whose title matches the AWT window's.
 *
 * Matching the process id first means a second instance of the app - or any
 * other window that happens to share the title - cannot be picked up by
 * mistake. When the title is unavailable or ambiguous, the process's only
 * titled top-level window is used, which covers the normal case of a single
 * Compose window.
 *
 * The result is null until the peer is realised, which is the normal case for
 * the first frames of a Compose window; callers poll rather than assume.
 */
internal fun resolveHandle(window: Window): Long? {
    val wanted = (window as? java.awt.Frame)?.title
    val user32 = User32.INSTANCE
    val myPid = Kernel32.INSTANCE.GetCurrentProcessId()

    val matches = mutableListOf<Pair<Long, String>>()
    user32.EnumWindows(
        { hwnd, _ ->
            val length = user32.GetWindowTextLength(hwnd)
            if (length > 0) {
                val pidRef = IntByReference()
                user32.GetWindowThreadProcessId(hwnd, pidRef)
                if (pidRef.value == myPid) {
                    val buffer = CharArray(length + 1)
                    user32.GetWindowText(hwnd, buffer, buffer.size)
                    matches += Pointer.nativeValue(hwnd.pointer) to String(buffer, 0, length)
                }
            }
            true
        },
        null,
    )

    if (matches.isEmpty()) return null
    if (wanted != null) {
        matches.firstOrNull { it.second == wanted }?.let { return it.first }
    }
    return matches.singleOrNull()?.first
}

/** Human-readable summary of what the compositor accepted, for the diagnostics screen. */
data class WindowFrameReport(
    val handle: Long,
    val backdrop: SystemBackdrop?,
    val darkTitleBar: Boolean,
    val corners: WindowCorners?,
    val materialApplied: Boolean,
) {
    val summary: String
        get() = buildString {
            append("HWND 0x").append(handle.toString(16))
            append(" - backdrop=").append(backdrop?.label ?: "not applied")
            append(", darkTitleBar=").append(darkTitleBar)
            append(", corners=").append(corners?.label ?: "default")
            if (backdrop != null && !materialApplied) append(" (legacy attribute path)")
        }
}

/**
 * Applies the full Windows 11 window treatment to a Compose window.
 *
 * Each attribute is attempted independently so that a partially supporting
 * Windows build still gets whatever it does support - a 21H2 machine, for
 * instance, refuses [DwmAttribute.SYSTEMBACKDROP_TYPE] but accepts the older
 * [DwmAttribute.MICA_EFFECT_ENABLED] flag.
 *
 * @param window the AWT window, normally `ComposeWindow`.
 * @param backdrop which material to ask for.
 * @param darkTitleBar whether the caption should be dark; pass the app's theme.
 * @param corners corner treatment; [WindowCorners.ROUND] is the Windows 11 default.
 * @param borderColor 0xAARRGGBB frame colour, or null to leave Windows' own.
 * @return what was actually applied, or null when the handle is not ready yet.
 */
fun applyWindowFrame(
    window: Window,
    backdrop: SystemBackdrop = SystemBackdrop.MICA,
    darkTitleBar: Boolean = true,
    corners: WindowCorners = WindowCorners.ROUND,
    borderColor: Int? = null,
): WindowFrameReport? {
    val dwm = DwmApi.instance ?: return null
    val handle = resolveHandle(window) ?: return null
    val hwnd = WinDef.HWND(Pointer(handle))

    fun setInt(attribute: Int, value: Int): Boolean = runCatching {
        val buffer = com.sun.jna.Memory(4).apply { setInt(0, value) }
        dwm.DwmSetWindowAttribute(hwnd, attribute, buffer, 4) == 0
    }.getOrDefault(false)

    // The immersive dark title bar is the attribute every Windows 10 1809+
    // build understands, so it is the most likely to succeed.
    val darkApplied = setInt(DwmAttribute.USE_IMMERSIVE_DARK_MODE, if (darkTitleBar) 1 else 0)

    // Windows 11 22H2+ takes the backdrop as a system value...
    val backdropApplied = setInt(DwmAttribute.SYSTEMBACKDROP_TYPE, backdrop.dwmValue)

    // ...while 21H2 only understands a boolean Mica flag. Setting both is
    // harmless: the newer attribute simply takes precedence where it exists.
    val legacyApplied = if (backdrop == SystemBackdrop.MICA || backdrop == SystemBackdrop.ACRYLIC) {
        setInt(DwmAttribute.MICA_EFFECT_ENABLED, if (backdropApplied) 0 else 1)
    } else {
        false
    }

    val cornersApplied = setInt(DwmAttribute.WINDOW_CORNER_PREFERENCE, corners.dwmValue)

    if (borderColor != null) {
        setInt(DwmAttribute.BORDER_COLOR, borderColor)
    }

    return WindowFrameReport(
        handle = handle,
        backdrop = if (backdropApplied || legacyApplied) backdrop else null,
        darkTitleBar = darkApplied,
        corners = if (cornersApplied) corners else null,
        materialApplied = backdropApplied,
    )
}

/**
 * Re-applies just the caption colour, for when the app theme changes.
 *
 * Kept separate from [applyWindowFrame] because a theme switch happens often and
 * must not re-request the backdrop: re-setting [DwmAttribute.SYSTEMBACKDROP_TYPE]
 * on every toggle makes the compositor cross-fade the wallpaper each time.
 */
fun applyTitleBarTheme(window: Window, dark: Boolean): Boolean {
    val dwm = DwmApi.instance ?: return false
    val handle = resolveHandle(window) ?: return false
    return runCatching {
        val buffer = com.sun.jna.Memory(4).apply { setInt(0, if (dark) 1 else 0) }
        dwm.DwmSetWindowAttribute(
            WinDef.HWND(Pointer(handle)),
            DwmAttribute.USE_IMMERSIVE_DARK_MODE,
            buffer,
            4,
        ) == 0
    }.getOrDefault(false)
}

/**
 * Waits for the window's native handle and then applies [applyWindowFrame].
 *
 * The peer is created asynchronously, so the first attempt almost always finds
 * no handle. Polling for up to [timeoutMillis] is preferable to a fixed delay
 * because the wait is typically one or two frames but can be much longer on a
 * cold jpackage start.
 *
 * @return the applied report, or null if the handle never appeared.
 */
fun awaitAndApplyWindowFrame(
    window: Window,
    timeoutMillis: Long = 5_000,
    backdrop: SystemBackdrop = SystemBackdrop.MICA,
    darkTitleBar: Boolean = true,
): WindowFrameReport? {
    if (!windowsEffectsAvailable) {
        Log.i("Native window effects unavailable on this platform", tag = "win32")
        return null
    }
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        val report = applyWindowFrame(window, backdrop, darkTitleBar)
        if (report != null) {
            Log.i("Window frame applied: ${report.summary}", tag = "win32")
            return report
        }
        Thread.sleep(50)
    }
    Log.w("Native window handle never appeared within ${timeoutMillis}ms", tag = "win32")
    return null
}

/** Reads one informational DWM attribute, used by the diagnostics screen. */
fun readDwmAttribute(window: Window, attribute: Int): Int? {
    val dwm = DwmApi.instance ?: return null
    val handle = resolveHandle(window) ?: return null
    return runCatching {
        val reference = IntByReference()
        val ok = dwm.DwmGetWindowAttribute(
            WinDef.HWND(Pointer(handle)),
            attribute,
            reference.pointer,
            4,
        )
        if (ok == 0) reference.value else null
    }.getOrNull()
}
