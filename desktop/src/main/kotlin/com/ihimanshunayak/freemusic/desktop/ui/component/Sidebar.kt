// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - sidebar.
//
// Permanent navigation. A desktop window does not need a drawer: the sidebar is
// always visible, which removes a click from every navigation and gives the
// window a stable left edge to align content against.

package com.ihimanshunayak.freemusic.desktop.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ihimanshunayak.freemusic.desktop.ui.Screen

/**
 * Left navigation rail.
 *
 * [showDiagnostics] hides the last entry rather than disabling it, because a
 * greyed-out debug tab raises a question that a hidden one does not.
 */
@Composable
fun Sidebar(
    current: Screen,
    onSelect: (Screen) -> Unit,
    showDiagnostics: Boolean,
    engineAvailable: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(220.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BrandMark()
        Spacer(modifier = Modifier.height(16.dp))

        Screen.entries
            .filter { showDiagnostics || !it.advanced }
            .forEach { screen ->
                NavigationItem(
                    label = screen.label,
                    icon = screen.icon,
                    selected = screen == current,
                    onClick = { onSelect(screen) },
                )
            }

        Spacer(modifier = Modifier.weight(1f))

        if (!engineAvailable) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "VLC not found - playback disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/**
 * The app's wordmark.
 *
 * The mark is a rounded tile with a play glyph, drawn from a Material icon
 * rather than a bitmap so it stays crisp at every DPI and the packaged app ships
 * no image asset for it.
 */
@Composable
private fun BrandMark() {
    Row(
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(19.dp),
            )
        }
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(
                text = "Free Music",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "for Windows",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
            )
        }
    }
}
