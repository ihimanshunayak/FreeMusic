// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - diagnostics screen.
//
// Support surface: live log tail, engine status, and the caches. It exists so a
// user can answer "why did that not play" without a debugger - the three things
// that break in practice are a missing VLC, an expired stream URL and a session
// that failed to mint a visitor id, and all three are visible here.

package com.ihimanshunayak.freemusic.desktop.ui.screen

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.audio.PlaybackSnapshot
import com.ihimanshunayak.freemusic.desktop.ui.ArtworkDiagnostics
import com.ihimanshunayak.freemusic.desktop.ui.theme.MonospaceLogStyle
import com.ihimanshunayak.freemusic.desktop.util.AppPaths
import com.ihimanshunayak.freemusic.desktop.util.Log
import com.ihimanshunayak.freemusic.desktop.util.LogLevel
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
 * The log tail is capped at [MAX_LINES]: a subscription that grows without bound
 * would leak the whole session into memory, and the newest lines are the only
 * ones that matter for a support question.
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

    LaunchedEffect(Unit) { artworkDiskKb = ArtworkDiagnostics.diskBytes() / 1024 }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Diagnostics", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = "Live log from this session",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { lines.clear() }) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Clear view", modifier = Modifier.padding(start = 6.dp))
                }
                TextButton(onClick = { openPath(AppPaths.logDir) }) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Open log folder", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            StatusLine("Audio engine", if (engineAvailable) "Available" else (engineUnavailableReason ?: "Unavailable"), engineAvailable)
            StatusLine("YouTube session", if (visitorDataPresent) "Visitor id minted" else "Not yet minted", visitorDataPresent)
            StatusLine("Playback", "${snapshot.state} - queue ${snapshot.queue.size}", snapshot.state.name != "ERROR")
            StatusLine("Artwork cache", "$artworkDiskKb KB on disk", true)
            StatusLine("Log file", Log.currentLogFile(), true)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            if (lines.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        text = "Nothing logged yet this session",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(lines) { line ->
                        Row {
                            Text(
                                text = line.level.name.take(4),
                                style = MonospaceLogStyle,
                                fontWeight = FontWeight.Bold,
                                color = when (line.level) {
                                    LogLevel.ERROR -> MaterialTheme.colorScheme.error
                                    LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                                    LogLevel.INFO -> MaterialTheme.colorScheme.primary
                                    LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(
                                text = line.text,
                                style = MonospaceLogStyle,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One `key: value` status row with a coloured dot. */
@Composable
private fun StatusLine(label: String, value: String, healthy: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .padding(end = 0.dp),
        ) {
            Icon(
                imageVector = if (healthy) Icons.Outlined.Terminal else Icons.Outlined.Terminal,
                contentDescription = null,
                tint = if (healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(8.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(width = 130.dp, height = 16.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Opens a folder in the OS file browser.
 *
 * Uses `rundll32` on Windows and `xdg-open` elsewhere because the desktop app is
 * built for Windows but the module still compiles and runs on Linux and macOS.
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

private const val MAX_LINES = 800
