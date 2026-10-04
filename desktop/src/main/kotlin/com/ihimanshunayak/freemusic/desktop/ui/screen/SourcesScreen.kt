// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - music sources screen.
//
// NAME
//     SourcesScreen.kt - built-in sources and user-installed addons.
//
// DESCRIPTION
//     A search asks every enabled source in quality order and stops at the first
//     that answers, so this screen is really a priority editor with a switch on
//     each row. The order is shown explicitly rather than left implicit, because
//     "why is this song from SoundCloud when I have YouTube Music enabled" is
//     otherwise an unanswerable question.
//
//     Addons are third-party scripts, so they are treated with the caution that
//     deserves: a script is validated before it is installed, its access is
//     described in plain terms next to the editor, and a script that has thrown
//     once is marked and can be re-enabled deliberately.
//
// RESPONSIBILITIES
//     - Toggle and reorder the built-in sources.
//     - Install, edit, disable and remove addons.
//     - Report the validation result of a script before it is saved.
//
// DEPENDENCIES
//     - [BuiltInSources], [SourceRegistry], [AddonSource], [SAMPLE_ADDON_SCRIPT].
//     - [SettingsWidgets] for the surrounding chrome.
//
// INTEGRATION NOTES
//     - The registry reads its addon list from settings on every call, so this
//       screen writes to settings and the registry picks the change up without a
//       reinstall step.
//     - Script validation runs on the UI thread against Rhino's interpreter. It
//       is bounded by Rhino's own instruction limit, so a hostile script cannot
//       hang the screen; the alternative - validating off-thread - would let a
//       user save a script that had not finished being checked.

package com.ihimanshunayak.freemusic.desktop.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.data.AddonRecord
import com.ihimanshunayak.freemusic.desktop.data.Settings
import com.ihimanshunayak.freemusic.desktop.data.source.BuiltInSources
import com.ihimanshunayak.freemusic.desktop.data.source.MusicSource
import com.ihimanshunayak.freemusic.desktop.data.source.SAMPLE_ADDON_SCRIPT
import com.ihimanshunayak.freemusic.desktop.ui.component.ActionRow
import com.ihimanshunayak.freemusic.desktop.ui.component.EnumRow
import com.ihimanshunayak.freemusic.desktop.ui.component.FluentGlyphs
import com.ihimanshunayak.freemusic.desktop.ui.component.KeyValueRow
import com.ihimanshunayak.freemusic.desktop.ui.component.SectionHeader
import com.ihimanshunayak.freemusic.desktop.ui.component.SettingsGroup
import com.ihimanshunayak.freemusic.desktop.ui.component.TextRow
import com.ihimanshunayak.freemusic.desktop.ui.component.ToggleRow
import com.ihimanshunayak.freemusic.desktop.ui.component.VerticalGap
import io.github.composefluent.FluentTheme
import io.github.composefluent.LocalTextStyle
import io.github.composefluent.component.Icon
import io.github.composefluent.component.InfoBar
import io.github.composefluent.component.InfoBarSeverity
import io.github.composefluent.component.ListItem
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import io.github.composefluent.component.TextField
import java.util.Locale

/**
 * The sources screen.
 *
 * @param validateAddon the registry's Rhino check; returns null when a script is
 *   valid, or the reason it is not. Injected so this screen does not have to
 *   construct an engine of its own.
 * @param disabledAddons ids the engine has disabled after a throw.
 */
@Composable
fun SourcesScreen(
    settings: Settings,
    disabledAddons: Set<String>,
    validateAddon: (String) -> String?,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onRetryAddon: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()

    var editorName by remember { mutableStateOf("") }
    var editorScript by remember { mutableStateOf(SAMPLE_ADDON_SCRIPT) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }

    val enabled = settings.addonSourceOrder.toSet()
    // Enabled sources first in quality order, then the disabled ones: the list
    // then reads top-down as "what a search will actually try", in order.
    val ordered = remember(settings.addonSourceOrder, settings.addonPreferHigherQuality) {
        BuiltInSources.all.sortedWith(
            compareByDescending<MusicSource> { it.id in enabled }
                .thenByDescending { it.qualityRank },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(24.dp),
    ) {
        SectionHeader("Sources") {
            SubtleButton(onClick = {
                onUpdate { it.copy(addonSourceOrder = BuiltInSources.defaultEnabled) }
            }) {
                Icon(FluentGlyphs.Refresh, contentDescription = null)
                Text("Reset to default", modifier = Modifier.padding(start = 6.dp))
            }
        }

        InfoBar(
            title = { Text("How a search works") },
            message = {
                Text(
                    "Every enabled source is queried in the order below. The first one that " +
                        "returns a playable stream wins, so put the catalogue you trust most " +
                        "at the top.",
                )
            },
            severity = InfoBarSeverity.Informational,
        )

        VerticalGap(16.dp)

        SettingsGroup(
            title = "Built-in sources",
            detail = "${enabled.count { id -> BuiltInSources.byId(id) != null }} of " +
                "${BuiltInSources.all.size} enabled",
        ) {
            ordered.forEach { source ->
                val isEnabled = source.id in enabled
                ListItem(
                    selected = false,
                    onSelectedChanged = { toggleSource(source, onUpdate) },
                    text = {
                        Column {
                            Text(source.name)
                            Text(
                                text = source.description,
                                style = FluentTheme.typography.caption,
                                color = FluentTheme.colors.text.text.tertiary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = {
                        Icon(
                            if (isEnabled) FluentGlyphs.Checkmark else FluentGlyphs.Close,
                            contentDescription = null,
                            tint = if (isEnabled) FluentTheme.colors.text.accent.primary
                            else FluentTheme.colors.text.text.disabled,
                        )
                    },
                    trailing = {
                        Text(
                            text = "priority ${source.qualityRank}",
                            style = FluentTheme.typography.caption,
                            color = FluentTheme.colors.text.text.tertiary,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        SubtleButton(onClick = { moveSource(source.id, -1, onUpdate) }) {
                            Icon(FluentGlyphs.SortUp, contentDescription = "Move up")
                        }
                        SubtleButton(onClick = { moveSource(source.id, +1, onUpdate) }) {
                            Icon(FluentGlyphs.SortDown, contentDescription = "Move down")
                        }
                    },
                )
            }
        }

        VerticalGap(16.dp)

        SettingsGroup(
            title = "Search behaviour",
            detail = "How a search treats the sources above.",
        ) {
            ToggleRow(
                title = "Prefer the higher quality source",
                checked = settings.addonPreferHigherQuality,
                onCheckedChange = { prefer ->
                    onUpdate { it.copy(addonPreferHigherQuality = prefer) }
                },
                detail = "On ranks enabled sources by bitrate; off keeps the order you set above.",
            )
            ToggleRow(
                title = "Allow plain HTTP for addons",
                checked = settings.addonAllowHttp,
                onCheckedChange = { allow -> onUpdate { it.copy(addonAllowHttp = allow) } },
                detail = "Off requires addon requests to use HTTPS. Turn this on only for a " +
                    "source you host yourself.",
            )
        }

        VerticalGap(16.dp)

        SettingsGroup(
            title = "Source addons",
            detail = "${settings.addons.size} installed",
        ) {
            InfoBar(
                title = { Text("What an addon can do") },
                message = {
                    Text(
                        "A script can make HTTP requests through the app and return a stream " +
                            "URL. It cannot reach the filesystem, run programs, or load other " +
                            "scripts. A script that throws is disabled until you re-enable it.",
                    )
                },
                severity = InfoBarSeverity.Informational,
            )

            VerticalGap(8.dp)

            if (settings.addons.isEmpty()) {
                Text(
                    text = "No addons installed. Paste a script below to add one.",
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.tertiary,
                )
            }

            settings.addons.forEach { addon ->
                val isDisabled = addon.id in disabledAddons
                ListItem(
                    selected = false,
                    onSelectedChanged = {
                        editorName = addon.name
                        editorScript = addon.script
                        editingId = addon.id
                        editorError = null
                    },
                    text = {
                        Column {
                            Text(addon.name)
                            Text(
                                text = if (isDisabled) {
                                    "Disabled after an error. Version ${addon.version}."
                                } else {
                                    "Version ${addon.version}, priority ${addon.qualityRank}"
                                },
                                style = FluentTheme.typography.caption,
                                color = if (isDisabled) FluentTheme.colors.system.critical
                                else FluentTheme.colors.text.text.tertiary,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = {
                        Icon(
                            if (isDisabled) FluentGlyphs.Warning else FluentGlyphs.Tools,
                            contentDescription = null,
                        )
                    },
                    trailing = {
                        if (isDisabled) {
                            SubtleButton(onClick = { onRetryAddon(addon.id) }) {
                                Icon(FluentGlyphs.Retry, contentDescription = "Re-enable")
                            }
                        }
                        SubtleButton(onClick = { removeAddon(settings, addon.id, onUpdate) }) {
                            Icon(FluentGlyphs.Delete, contentDescription = "Remove")
                        }
                    },
                )
            }
        }

        VerticalGap(16.dp)

        SettingsGroup(
            title = if (editingId == null) "Add an addon" else "Editing ${editorName.ifBlank { "addon" }}",
            detail = "The script must define resolve(videoId, artist, title).",
        ) {
            TextRow(
                title = "Name",
                value = editorName,
                onValueChange = { editorName = it },
                placeholder = "My source",
                modifier = Modifier.width(320.dp),
            )

            VerticalGap(8.dp)

            EditorBox(
                value = editorScript,
                onValueChange = {
                    editorScript = it
                    editorError = null
                },
            )

            editorError?.let { message ->
                VerticalGap(8.dp)
                InfoBar(
                    title = { Text("This script cannot be saved") },
                    message = { Text(message) },
                    severity = InfoBarSeverity.Critical,
                )
            }

            if (editorScript != SAMPLE_ADDON_SCRIPT) {
                VerticalGap(8.dp)
                KeyValueRow("Fingerprint", editorScript.hashCode().toString(16))
            }
            VerticalGap(8.dp)
            ActionRow {
                SubtleButton(onClick = {
                    editorScript = SAMPLE_ADDON_SCRIPT
                    editorName = ""
                    editingId = null
                    editorError = null
                }) { Text("Start from the sample") }

                SubtleButton(onClick = {
                    val reason = validateAddon(editorScript)
                    if (reason == null) {
                        editorError = null
                        installAddon(editorName, editorScript, settings, editingId, onUpdate)
                    } else {
                        editorError = reason
                    }
                }) {
                    Icon(FluentGlyphs.Save, contentDescription = null)
                    Text(
                        text = if (editingId == null) "Validate and install" else "Validate and save",
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }

                if (editingId != null) {
                    SubtleButton(onClick = {
                        editingId = null
                        editorName = ""
                        editorScript = SAMPLE_ADDON_SCRIPT
                        editorError = null
                    }) { Text("Cancel edit") }
                }
            }
        }
    }
}

/**
 * The script editor.
 *
 * Deliberately a plain multi-line text field rather than a code editor: the
 * scripts are a dozen lines, and a user who needs more than this will write the
 * addon in their own editor and paste it in.
 *
 * Fluent's `TextField` reads its typography from `LocalTextStyle` rather than
 * taking a style parameter, so the monospace face is provided through that
 * composition local instead.
 */
@Composable
private fun EditorBox(value: String, onValueChange: (String) -> Unit) {
    var field by remember(value) { mutableStateOf(TextFieldValue(value)) }
    CompositionLocalProvider(
        LocalTextStyle provides TextStyle(fontFamily = FontFamily.Monospace),
    ) {
        TextField(
            value = field,
            onValueChange = {
                field = it
                onValueChange(it.text)
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
            header = { Text("Script") },
            placeholder = { Text("// resolve(videoId, artist, title)") },
            singleLine = false,
            maxLines = 60,
        )
    }
}

/** Flips a source's enabled state, keeping its position in the order. */
private fun toggleSource(source: MusicSource, onUpdate: ((Settings) -> Settings) -> Unit) {
    onUpdate { current ->
        val order = current.addonSourceOrder.ifEmpty { BuiltInSources.defaultEnabled }
        if (source.id in order) {
            val next = order - source.id
            // An empty list would disable search entirely, which reads as a broken
            // app rather than as a setting, so the last source cannot be removed.
            if (next.isEmpty()) current else current.copy(addonSourceOrder = next)
        } else {
            // A newly enabled source is appended, so a user's existing priority is
            // never silently reshuffled by turning something else on.
            current.copy(addonSourceOrder = order + source.id)
        }
    }
}

/** Moves a source one place within the order list. */
private fun moveSource(id: String, delta: Int, onUpdate: ((Settings) -> Settings) -> Unit) {
    onUpdate { current ->
        val order = (current.addonSourceOrder.ifEmpty { BuiltInSources.defaultEnabled }).toMutableList()
        val from = order.indexOf(id)
        if (from < 0) return@onUpdate current
        val to = (from + delta).coerceIn(0, order.lastIndex)
        if (to == from) return@onUpdate current
        order.add(to, order.removeAt(from))
        current.copy(addonSourceOrder = order)
    }
}

/** Removes an addon by id, and any record of it having been enabled. */
private fun removeAddon(settings: Settings, id: String, onUpdate: ((Settings) -> Settings) -> Unit) {
    onUpdate { current ->
        current.copy(
            addons = current.addons.filterNot { it.id == id },
            addonSourceOrder = current.addonSourceOrder.filterNot { it == id },
        )
    }
}

/**
 * Installs or replaces an addon.
 *
 * Performs the id derivation here rather than in the registry so the id is
 * written once and stays stable across a rename of the stored record.
 */
private fun installAddon(
    name: String,
    script: String,
    settings: Settings,
    editingId: String?,
    onUpdate: ((Settings) -> Settings) -> Unit,
) {
    val displayName = name.trim().ifBlank { "Unnamed addon" }
    val id = editingId ?: idFor(displayName)
    val record = AddonRecord(
        id = id,
        name = displayName,
        script = script,
        version = "1.0.0",
        qualityRank = 40,
        enabled = true,
        supportsSearch = true,
    )
    onUpdate { current ->
        val kept = current.addons.filterNot { it.id == id }
        current.copy(
            addons = kept + record,
            // A freshly installed addon is enabled, so it is appended to the source
            // order in the same write rather than needing a second action.
            addonSourceOrder = if (id in current.addonSourceOrder) current.addonSourceOrder
            else current.addonSourceOrder + id,
        )
    }
}

/** The same slug rule the registry applies, kept in step with it. */
private fun idFor(name: String): String =
    "addon:" + name.trim().lowercase(Locale.ROOT)
        .replace(Regex("""[^a-z0-9]+"""), "-")
        .trim('-')
        .ifBlank { "unnamed" }
