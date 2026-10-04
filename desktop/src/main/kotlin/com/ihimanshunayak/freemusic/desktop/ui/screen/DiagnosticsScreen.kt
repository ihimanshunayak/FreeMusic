// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - diagnostics screen.
//
// NAME
//     DiagnosticsScreen.kt - the support surface: status, log tail, cache.
//
// DESCRIPTION
//     Exists so a user can answer "why did that not play" without a debugger. The
//     three things that break in practice are a missing libVLC, an expired stream
//     URL and a session that never minted a visitor id, and all three are visible
//     in the status block at the top.
//
//     Below the status block is a live tail of this session's logger. It is a
//     subscription rather than a re-read of the log file: the file is written with
//     buffering and is shared with a second instance of the app, so tailing it
//     would show stale lines or race the writer. The subscription gives exactly the
//     lines this process produced, in order, as they happen.
//
// RESPONSIBILITIES
//     - Report engine, session, playback and cache state in one glance.
//     - Stream the session log with per-level colouring.
//     - Offer the two escapes a user needs: clear the view, open the log folder.
//
// DEPENDENCIES
//     - [Log] for the live subscription and the current file path.
//     - [ArtworkDiagnostics] for the on-disk artwork size.
//     - [MonospaceLogStyle] for the log body.
//
// INTEGRATION NOTES
//     - The tail is capped at [MAX_LINES]. A subscription that grows without bound
//       would hold the whole session in memory, and only the newest lines matter
//       for a support question.
//     - Clearing empties the *view*, not the file. The file is the record; the view
//       is a window onto it, and destroying evidence the user might want later
//       would be the wrong default. The folder button is right next to it.
//     - Auto-scroll is keyed on the line count, so it follows new lines but does
//       not fight the user while they scroll back through old ones.

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.audio.PlaybackSnapshot
import com.ihimanshunayak.freemusic.desktop.ui.ArtworkDiagnostics
import com.ihimanshunayak.freemusic.desktop.ui.component.FluentGlyphs
import com.ihimanshunayak.freemusic.desktop.ui.component.SectionHeader
import com.ihimanshunayak.freemusic.desktop.ui.component.SettingsGroup
import com.ihimanshunayak.freemusic.desktop.ui.theme.MonospaceLogStyle
import com.ihimanshunayak.freemusic.desktop.util.AppPaths
import com.ihimanshunayak.freemusic.desktop.util.Log
import com.ihimanshunayak.freemusic.desktop.util.LogLevel
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Icon
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import java.util.Locale

/**
 * A log line as the UI shows it.
 *
 * The logger already formats the timestamp and tag into the message it hands to
 * listeners, so this only carries the level for colouring.
 */
private data class LogLine(
    val level: LogLevel,
    val text: String,
)

/**
 * Live diagnostics.
 *
 * State is passed in rather than read from the container so the screen stays a
 * pure function of its inputs: everything shown here is produced elsewhere, and a
 * screen that reads the object graph cannot be rendered against a fixture.
 */
@Composable
fun DiagnosticsScreen(
    snapshot: PlaybackSnapshot,
    engineAvailable: Boolean,
    engineUnavailableReason: String?,
    visitorDataPresent: Boolean,
    modifier: Modifier = Modifier,
) {
    val lines = remember { mutableStateListOf<LogLine>() }
    val listState = rememberLazyListState()
    var artworkDiskKb by remember { mutableStateOf(0L) }

    DisposableEffect(Unit) {
        val listener: (LogLevel, String) -> Unit = { level, message ->
            lines.add(LogLine(level, message))
            // The oldest lines are dropped rather than the newest: a support
            // question is almost always about what just happened.
            while (lines.size > MAX_LINES) lines.removeAt(0)
        }
        Log.addListener(listener)
        onDispose { Log.removeListener(listener) }
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(lines.lastIndex) }
        }
    }

    // Measured once per visit rather than watched: the cache only changes when
    // artwork is fetched, and walking the directory on every recomposition would
    // be a surprising amount of IO for a number nobody is watching.
    LaunchedEffect(Unit) { artworkDiskKb = ArtworkDiagnostics.diskBytes() / 1024 }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        SectionHeader("Diagnostics") {
            SubtleButton(onClick = { lines.clear() }) {
                Icon(FluentGlyphs.Broom, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Clear view", modifier = Modifier.padding(start = 8.dp))
            }
            SubtleButton(
                onClick = { openPath(AppPaths.logDir) },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Icon(FluentGlyphs.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Open log folder", modifier = Modifier.padding(start = 8.dp))
            }
        }

        Text(
            text = "Live log from this session. Clearing empties this view, not the file.",
            style = FluentTheme.typography.caption,
            color = FluentTheme.colors.text.text.secondary,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        SettingsGroup("Status") {
            StatusLine(
                label = "Audio engine",
                value = if (engineAvailable) "Available" else (engineUnavailableReason ?: "Unavailable"),
                healthy = engineAvailable,
            )
            StatusLine(
                label = "YouTube session",
                value = if (visitorDataPresent) "Visitor id minted" else "Not yet minted",
                healthy = visitorDataPresent,
            )
            StatusLine(
                label = "Playback",
                value = if (snapshot.queue.isEmpty()) {
                    "${snapshot.state} - nothing queued"
                } else {
                    "${snapshot.state} - track ${snapshot.queueIndex + 1} of ${snapshot.queue.size}"
                },
                healthy = snapshot.state.name != "ERROR",
            )
            StatusLine(
                label = "Artwork cache",
                value = "$artworkDiskKb KB on disk",
                healthy = true,
            )
            StatusLine(label = "Log file", value = Log.currentLogFile(), healthy = true)
        }

        SectionHeader("Session log")

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 8.dp),
        ) {
            if (lines.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        FluentGlyphs.Code,
                        contentDescription = null,
                        tint = FluentTheme.colors.text.text.tertiary,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        text = "Nothing logged yet this session",
                        style = FluentTheme.typography.caption,
                        color = FluentTheme.colors.text.text.secondary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    items(lines) { line ->
                        Row {
                            // The four-character prefix keeps the columns aligned
                            // without a fixed width, so the body starts at the same
                            // x on every line including the short "WARN".
                            Text(
                                text = line.level.name.take(4),
                                style = MonospaceLogStyle,
                                fontWeight = FontWeight.Bold,
                                color = levelColor(line.level),
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(
                                text = line.text,
                                style = MonospaceLogStyle,
                                color = FluentTheme.colors.text.text.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Maps a log level onto the Fluent semantic colours.
 *
 * Semantic rather than decorative: ERROR uses the same red as every other failure
 * in the app, so the eye learns one meaning for red rather than several.
 */
@Composable
private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.ERROR -> FluentTheme.colors.system.critical
    LogLevel.WARN -> FluentTheme.colors.system.caution
    LogLevel.INFO -> FluentTheme.colors.text.accent.primary
    LogLevel.DEBUG -> FluentTheme.colors.text.text.tertiary
}

/** One `label: value` status row with a coloured dot. */
@Composable
private fun StatusLine(label: String, value: String, healthy: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (healthy) FluentTheme.colors.system.success else FluentTheme.colors.system.critical,
                ),
        )
        Text(
            text = label,
            style = FluentTheme.typography.caption,
            color = FluentTheme.colors.text.text.secondary,
            modifier = Modifier
                .padding(start = 10.dp)
                .size(width = 140.dp, height = 18.dp),
        )
        Text(
            text = value,
            style = FluentTheme.typography.caption,
            color = FluentTheme.colors.text.text.primary,
        )
    }
}

/**
 * Opens a folder in the OS file browser.
 *
 * Built for Windows but the module still compiles and runs on Linux and macOS, so
 * each platform's own opener is used rather than a hard-coded `explorer.exe`.
 * Failure is logged and ignored: a shell that refuses to open a folder is not a
 * reason to take the app down.
 */
private fun openPath(path: String) {
    runCatching {
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val command = when {
            os.contains("win") -> listOf("explorer.exe", path)
            os.contains("mac") -> listOf("open", path)
            else -> listOf("xdg-open", path)
        }
        ProcessBuilder(command).start()
    }.onFailure { Log.w("could not open $path: ${it.message}", tag = "diagnostics") }
}

/** How many log lines the view keeps before dropping the oldest. */
private const val MAX_LINES = 800
