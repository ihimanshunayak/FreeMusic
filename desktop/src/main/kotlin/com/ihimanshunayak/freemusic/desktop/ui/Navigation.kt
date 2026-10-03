// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - navigation model.
//
// Six destinations, held as a sealed hierarchy rather than a string route. The
// Android app uses Navigation-Compose; a desktop window with a fixed sidebar has
// no back stack worth the machinery, so this is a single `var` plus the enum.

package com.ihimanshunayak.freemusic.desktop.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The sidebar's destinations.
 *
 * [Diagnostics] is not shown unless `Settings.showDiagnostics` is on - it exists
 * for support cases, not for everyday use.
 */
enum class Screen(
    val label: String,
    val icon: ImageVector,
    val advanced: Boolean = false,
) {
    HOME("Home", Icons.Outlined.Home),
    SEARCH("Search", Icons.Outlined.Search),
    LIBRARY("Library", Icons.Outlined.LibraryMusic),
    QUEUE("Queue", Icons.Outlined.QueueMusic),
    SETTINGS("Settings", Icons.Outlined.Settings),
    DIAGNOSTICS("Diagnostics", Icons.Outlined.BugReport, advanced = true),
}
