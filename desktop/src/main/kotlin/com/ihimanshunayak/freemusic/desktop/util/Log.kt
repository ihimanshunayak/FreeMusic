// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - logging.
//
// A tiny, dependency-free logger. The desktop build runs as a packaged desktop
// app where the user cannot attach a debugger, so everything that is worth
// knowing later has to reach a file on disk while it is still known.

package com.ihimanshunayak.freemusic.desktop.util

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

enum class LogLevel(val label: String) {
    DEBUG("DEBUG"), INFO("INFO "), WARN("WARN "), ERROR("ERROR")
}

/**
 * Console plus rotating file logger.
 *
 * Writes to `%LOCALAPPDATA%\FreeMusic\logs\freemusic.log` and keeps one previous
 * file, so a crash that repeats does not consume the disk and the evidence from
 * the run before it survives. All writes are best-effort: a logger that throws
 * would turn a recoverable problem into a fatal one.
 */
object Log {

    private const val MAX_BYTES = 2L * 1024 * 1024
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private val file: File? by lazy {
        runCatching {
            val dir = File(AppPaths.logDir)
            if (!dir.exists()) dir.mkdirs()
            File(dir, "freemusic.log").also { log -> rotateIfNeeded(log) }
        }.getOrNull()
    }

    /**
     * Listeners let the UI surface the same messages the file receives, which
     * is how the diagnostics screen stays honest without the core having to
     * know a UI exists.
     */
    private val listeners = CopyOnWriteArrayList<(LogLevel, String) -> Unit>()

    fun addListener(listener: (LogLevel, String) -> Unit) = listeners.add(listener)
    fun removeListener(listener: (LogLevel, String) -> Unit) = listeners.remove(listener)

    fun d(message: String, tag: String = "app") = write(LogLevel.DEBUG, tag, message)
    fun i(message: String, tag: String = "app") = write(LogLevel.INFO, tag, message)
    fun w(message: String, tag: String = "app") = write(LogLevel.WARN, tag, message)
    fun e(message: String, throwable: Throwable? = null, tag: String = "app") =
        write(LogLevel.ERROR, tag, if (throwable == null) message else "$message\n${stack(throwable)}")

    fun stack(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun write(level: LogLevel, tag: String, message: String) {
        val line = "${LocalDateTime.now().format(stamp)} ${level.label} [$tag] $message"
        println(line)
        runCatching { file?.appendText(line + System.lineSeparator()) }
        listeners.forEach { runCatching { it(level, line) } }
    }

    private fun rotateIfNeeded(log: File) {
        if (log.exists() && log.length() > MAX_BYTES) {
            val previous = File(log.parentFile, "freemusic.previous.log")
            previous.delete()
            log.renameTo(previous)
        }
    }

    /** Where the log is being written, for the diagnostics screen to display. */
    fun currentLogFile(): String = file?.absolutePath ?: "(logging to console only)"
}

/**
 * Where the application keeps its state.
 *
 * `%LOCALAPPDATA%` on Windows, `~/.local/share` elsewhere, so the packaged app
 * and a `gradlew run` from the repo agree on where settings and downloads live.
 */
object AppPaths {

    private val root: String by lazy {
        val local = System.getenv("LOCALAPPDATA")
        if (local != null && local.isNotBlank()) {
            File(local, "FreeMusic").absolutePath
        } else {
            val home = System.getProperty("user.home") ?: "."
            val xdg = System.getenv("XDG_DATA_HOME")
            val base = if (!xdg.isNullOrBlank()) File(xdg) else File(home, ".local/share")
            File(base, "FreeMusic").absolutePath
        }
    }

    val rootDir: String get() = root
    val logDir: String get() = File(root, "logs").absolutePath
    val settingsFile: String get() = File(root, "settings.json").absolutePath
    val downloadsDir: String get() = File(root, "downloads").absolutePath
    val cacheDir: String get() = File(root, "cache").absolutePath
}
