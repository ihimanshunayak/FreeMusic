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
// This file is also where the two platform calls live that the UI deliberately
// does not own: the native folder picker and the process-wide shutdown hook.

package com.ihimanshunayak.freemusic.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.ihimanshunayak.freemusic.desktop.ui.App
import com.ihimanshunayak.freemusic.desktop.ui.state.BrowseViewModel
import com.ihimanshunayak.freemusic.desktop.ui.theme.FreeMusicTheme
import com.ihimanshunayak.freemusic.desktop.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.awt.Dimension
import javax.swing.JFileChooser
import javax.swing.UIManager

/** Set once at startup so the version is in every log line of a support bundle. */
private const val APP_NAME = "Free Music for Windows"

fun main() {
    Log.i("$APP_NAME starting (Java ${System.getProperty("java.version")})", tag = "main")

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
        val ready = remember { mutableStateOf(false) }
        val windowState = rememberWindowState(
            size = DpSize(1180.dp, 760.dp),
            position = WindowPosition.Aligned(Alignment.Center),
        )

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
            }

            LaunchedEffect(Unit) {
                container.initialise { ready.value = true }
                container.library.refresh()
            }

            val viewModel = remember { BrowseViewModel(container.music, uiScope) }
            val themePreference by container.settings.flow.collectAsState()

            FreeMusicTheme(preference = themePreference.theme) {
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
