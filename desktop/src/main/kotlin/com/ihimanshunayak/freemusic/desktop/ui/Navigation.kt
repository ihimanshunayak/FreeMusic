// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - navigation model.
//
// The destinations, held as an enum rather than a string route. The Android app
// uses Navigation-Compose; a desktop window with a permanent navigation pane has
// no back stack worth the machinery, so this is a single `var` plus the list.
//
// The order here is the order in the pane, and it is grouped the way the Windows
// shell groups its own navigation: the things you play first, then the things you
// manage, then the things you configure. `advanced` entries are hidden unless
// diagnostics are switched on - a greyed-out debug tab raises a question that a
// hidden one does not.

package com.ihimanshunayak.freemusic.desktop.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.ihimanshunayak.freemusic.desktop.ui.component.FluentGlyphs

/**
 * The navigation pane's destinations.
 *
 * Every screen the Android app has is represented, including the ones that only
 * became meaningful on the desktop (Downloads, Sources) - the point of the port
 * is feature parity, so a destination is only absent if it genuinely has no
 * Windows equivalent.
 */
enum class Screen(
    val label: String,
    val glyph: ImageVector,
    val advanced: Boolean = false,
) {
    HOME("Home", FluentGlyphs.Home),
    SEARCH("Search", FluentGlyphs.Search),
    EXPLORE("Explore", FluentGlyphs.Explore),
    LIBRARY("Library", FluentGlyphs.Library),
    LOCAL("Local music", FluentGlyphs.LocalFiles),
    HISTORY("History", FluentGlyphs.History),
    QUEUE("Queue", FluentGlyphs.Queue),

    DOWNLOADS("Downloads", FluentGlyphs.Downloads),
    STATISTICS("Statistics", FluentGlyphs.Statistics),

    LISTEN_TOGETHER("Listen Together", FluentGlyphs.ListenTogether),
    SOURCES("Sources", FluentGlyphs.Sources),
    ACCOUNT("Account", FluentGlyphs.Account),
    SETTINGS("Settings", FluentGlyphs.Settings),

    DIAGNOSTICS("Diagnostics", FluentGlyphs.Diagnostics, advanced = true),
}
