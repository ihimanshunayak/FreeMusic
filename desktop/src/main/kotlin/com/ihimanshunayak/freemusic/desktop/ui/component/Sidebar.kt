// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - navigation pane.
//
// A desktop window does not need a drawer, so this is WinUI's own
// `NavigationView` with the pane pinned open: the same control, the same
// selection indicator that slides between items, the same hover and press
// states as Explorer's own sidebar. That is the whole point of building the
// desktop app against Compose Fluent rather than against Material - navigation
// should not feel like a ported phone app.
//
// The pane is collapsible, because a 220px column of mostly-empty space is a
// real cost on a window the user may want narrow, and Windows users expect the
// hamburger to work.

package com.ihimanshunayak.freemusic.desktop.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ihimanshunayak.freemusic.desktop.ui.Screen
import io.github.composefluent.ExperimentalFluentApi
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Icon
import io.github.composefluent.component.InfoBar
import io.github.composefluent.component.InfoBarDefaults
import io.github.composefluent.component.InfoBarSeverity
import io.github.composefluent.component.NavigationDisplayMode
import io.github.composefluent.component.NavigationView
import io.github.composefluent.component.Text
import io.github.composefluent.component.menuItem
import io.github.composefluent.component.rememberNavigationState

/**
 * Left navigation rail, wrapping the active screen.
 *
 * The pane and its content are one control rather than two siblings, which is
 * what lets Fluent animate the collapse: `NavigationView` owns the width
 * transition and slides the content over as the pane hides, and it is also
 * where the drag-to-resize hit target lives. Splitting them into a `Row` would
 * lose all three.
 *
 * [showDiagnostics] hides the last entry rather than disabling it, because a
 * greyed-out debug tab raises a question that a hidden one does not.
 */
@OptIn(ExperimentalFluentApi::class)
@Composable
fun Sidebar(
    current: Screen,
    onSelect: (Screen) -> Unit,
    showDiagnostics: Boolean,
    engineAvailable: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberNavigationState(initialExpanded = true)
    var engineWarningDismissed by remember { mutableStateOf(false) }

    val entries = remember(showDiagnostics) {
        Screen.entries.filter { showDiagnostics || !it.advanced }
    }
    val showEngineWarning = !engineAvailable && !engineWarningDismissed

    NavigationView(
        modifier = modifier.fillMaxSize(),
        displayMode = NavigationDisplayMode.Left,
        state = state,
        title = { BrandMark() },
        menuItems = {
            entries.forEach { screen ->
                menuItem(
                    selected = screen == current,
                    onClick = { onSelect(screen) },
                    text = { Text(screen.label) },
                    icon = { Icon(screen.glyph, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    key = screen,
                )
            }
        },
        // `footerItems` is *not* a composable scope - Fluent measures it once
        // through a plain interval list - so a composable cannot be emitted
        // there. The engine notice therefore wraps the pane instead, which also
        // puts it above the screen content where it reads as a window-level
        // status rather than as a nav item.
        pane = {
            Column(modifier = Modifier.fillMaxSize()) {
                if (showEngineWarning) {
                    EngineWarningBar(onDismiss = { engineWarningDismissed = true })
                }
                Box(modifier = Modifier.weight(1f)) { content() }
            }
        },
    )
}

/**
 * The app's wordmark, drawn in Fluent's own text styles.
 *
 * The mark is a rounded tile with a play glyph, drawn from a vector rather than
 * a bitmap so it stays crisp at every DPI and the packaged app ships no image
 * asset for it. The tile uses the theme's accent, which is derived from the
 * current track's artwork - so the logo itself changes colour with the music,
 * which is the cheapest possible way to make the window feel alive.
 */
@Composable
private fun BrandMark() {
    Row(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(FluentTheme.colors.fillAccent.default),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = BrandGlyph,
                contentDescription = null,
                tint = FluentTheme.colors.text.onAccent.primary,
                modifier = Modifier.size(17.dp),
            )
        }
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(
                text = "Free Music",
                style = FluentTheme.typography.bodyStrong,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "for Windows",
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.text.text.secondary,
                fontSize = 10.sp,
            )
        }
    }
}

/** The "no libVLC" notice, in Fluent's own caution styling. */
@Composable
private fun EngineWarningBar(onDismiss: () -> Unit) {
    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        InfoBar(
            title = { Text("Playback engine not loaded") },
            message = { Text("Install VLC 3.x to play audio. Browsing still works.") },
            severity = InfoBarSeverity.Warning,
            closeAction = { InfoBarDefaults.CloseActionButton(onClick = onDismiss) },
        )
    }
}

/** The play glyph the wordmark is built from. */
private val BrandGlyph: ImageVector
    get() = com.ihimanshunayak.freemusic.desktop.ui.component.BrandIcons.Play
