// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - settings screen.
//
// Every option here maps to a field the app actually reads while running, so
// there is no "saved but ignored" setting. Preferences live in
// %LOCALAPPDATA%\FreeMusic\settings.json and the path is shown at the bottom so
// a user can find, back up or delete it.

package com.ihimanshunayak.freemusic.desktop.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.data.AudioQuality
import com.ihimanshunayak.freemusic.desktop.data.Settings
import com.ihimanshunayak.freemusic.desktop.data.ThemePreference
import com.ihimanshunayak.freemusic.desktop.util.AppPaths
import com.ihimanshunayak.freemusic.desktop.util.Log

/** Settings page. Writes straight through to the store on every change. */
@Composable
fun SettingsScreen(
    settings: Settings,
    engineAvailable: Boolean,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onPickDownloadsFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Changes are saved immediately.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        SettingsGroup("Playback") {
            QualityPicker(
                selected = settings.audioQuality,
                onSelect = { quality -> onUpdate { it.copy(audioQuality = quality) } },
            )
            ToggleRow(
                title = "Normalise volume",
                detail = "Evens out loud and quiet tracks. Slightly reduces peak quality.",
                checked = settings.normalizeVolume,
                onCheckedChange = { on -> onUpdate { it.copy(normalizeVolume = on) } },
            )
            SliderRow(
                title = "Playback speed",
                valueLabel = "%.2fx".format(settings.playbackSpeed),
                value = settings.playbackSpeed,
                range = 0.5f..2.0f,
                steps = 14,
                onValueChange = { speed -> onUpdate { it.copy(playbackSpeed = speed) } },
            )
            SliderRow(
                title = "Volume",
                valueLabel = "${(settings.volume * 100).toInt()}%",
                value = settings.volume,
                range = 0f..1f,
                steps = 19,
                onValueChange = { volume -> onUpdate { it.copy(volume = volume) } },
            )
        }

        SettingsGroup("Appearance") {
            EnumPicker(
                label = "Theme",
                options = ThemePreference.entries,
                selected = settings.theme,
                labelOf = { it.label },
                onSelect = { theme -> onUpdate { it.copy(theme = theme) } },
            )
            ToggleRow(
                title = "Show diagnostics tab",
                detail = "Adds a log viewer and engine status page to the sidebar.",
                checked = settings.showDiagnostics,
                onCheckedChange = { on -> onUpdate { it.copy(showDiagnostics = on) } },
            )
        }

        SettingsGroup("Downloads") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Folder", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = settings.effectiveDownloadsDirectory,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onPickDownloadsFolder) { Text("Change") }
            }
        }

        SettingsGroup("Region and language") {
            Text(
                text = "Region ${settings.region} - language ${settings.language}. " +
                    "These set which charts Home shows and what YouTube Music returns.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsGroup("About") {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(18.dp),
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text("Free Music for Windows", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Version $APP_VERSION - GPL-3.0-or-later",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Built by Himanshu Nayak",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Settings: ${AppPaths.settingsFile}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        text = "Logs: ${AppPaths.logDir}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!engineAvailable) {
                        Text(
                            text = "Audio engine unavailable - install VLC from videolan.org and reopen Free Music.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The app version, resolved from the JAR manifest with a sensible fallback. */
val APP_VERSION: String = runCatching {
    val pkg = Log::class.java.`package`
    val impl = pkg?.implementationVersion
    if (impl.isNullOrBlank()) "1.0.0" else impl
}.getOrDefault("1.0.0")

/** A titled card, so the page reads as sections rather than one long list. */
@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun ToggleRow(
    title: String,
    detail: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
        )
    }
}

/** Audio quality, with the bitrate hint shown because the label alone is vague. */
@Composable
private fun QualityPicker(selected: AudioQuality, onSelect: (AudioQuality) -> Unit) {
    EnumPicker(
        label = "Audio quality",
        options = AudioQuality.entries,
        selected = selected,
        labelOf = { it.label },
        onSelect = onSelect,
    )
}

/** A generic dropdown over an enum, so every picker on this page looks the same. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumPicker(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = labelOf(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labelOf(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
