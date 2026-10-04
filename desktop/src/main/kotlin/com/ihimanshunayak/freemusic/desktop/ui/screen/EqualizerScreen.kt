// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - equalizer screen.
//
// NAME
//     EqualizerScreen.kt - the ten-band equalizer, tone pad and preset library.
//
// DESCRIPTION
//     The equalizer is one curve with three ways of looking at it, and this
//     screen shows all three at once rather than as tabs: ten sliders give the
//     exact picture, the tone pad gives the two judgements a listener can
//     actually make without knowing any frequencies, and the preset row names
//     where the curve came from. Editing any one of them rewrites the other two,
//     which is why they share a single source of truth in the settings model.
//
//     The preamp is shown, not hidden. Every decibel of boost has to be paid for
//     out of headroom, and a user who boosts the bass and then wonders why the
//     track got quieter deserves to see the -7.5 dB on screen next to the curve
//     that caused it.
//
// RESPONSIBILITIES
//     - Render and edit the ten ISO bands.
//     - Render and edit the two-axis tone pad.
//     - Apply presets, and name the preset a manually edited curve matched.
//     - Save, apply and delete named curves.
//     - Show the automatic preamp and the manual balance.
//
// DEPENDENCIES
//     - [EqualizerPreset], [curveFor], [padFor], [preampFor] from the DSP layer.
//     - [SettingsWidgets] for the surrounding chrome.
//
// INTEGRATION NOTES
//     - The screen edits settings only; pushing the curve into libVLC is the
//       container's job. That keeps the audio thread out of the composition and
//       lets a curve be edited while nothing is playing.
//     - `equalizerFocused` records whether the pad or the sliders were last
//       touched, because the two are lossy inverses of each other: writing the
//       pad back from the sliders after the user dragged the pad would fight
//       them a little more on every frame.

package com.ihimanshunayak.freemusic.desktop.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.audio.dsp.EqualizerPreset
import com.ihimanshunayak.freemusic.desktop.audio.dsp.IsoBand
import com.ihimanshunayak.freemusic.desktop.audio.dsp.TonePadSetting
import com.ihimanshunayak.freemusic.desktop.audio.dsp.curveFor
import com.ihimanshunayak.freemusic.desktop.audio.dsp.padFor
import com.ihimanshunayak.freemusic.desktop.audio.dsp.preampFor
import com.ihimanshunayak.freemusic.desktop.data.EqualizerMode
import com.ihimanshunayak.freemusic.desktop.data.SavedCurve
import com.ihimanshunayak.freemusic.desktop.data.Settings
import com.ihimanshunayak.freemusic.desktop.ui.component.ActionRow
import com.ihimanshunayak.freemusic.desktop.ui.component.EnumRow
import com.ihimanshunayak.freemusic.desktop.ui.component.FluentGlyphs
import com.ihimanshunayak.freemusic.desktop.ui.component.KeyValueRow
import com.ihimanshunayak.freemusic.desktop.ui.component.SectionHeader
import com.ihimanshunayak.freemusic.desktop.ui.component.SettingsGroup
import com.ihimanshunayak.freemusic.desktop.ui.component.SliderRow
import com.ihimanshunayak.freemusic.desktop.ui.component.TextRow
import com.ihimanshunayak.freemusic.desktop.ui.component.ToggleRow
import com.ihimanshunayak.freemusic.desktop.ui.component.VerticalGap
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Icon
import io.github.composefluent.component.InfoBar
import io.github.composefluent.component.InfoBarSeverity
import io.github.composefluent.component.ListItem
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import kotlin.math.abs

/** The slider range, matching the DSP layer's own clamp. */
private const val GAIN_RANGE_DB = 12f

/**
 * The equalizer.
 *
 * @param onUpdate the settings transform; every control writes through it so the
 *   screen holds no shadow copy of the curve that could drift from the file.
 */
@Composable
fun EqualizerScreen(
    settings: Settings,
    engineAvailable: Boolean,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    var curveName by remember { mutableStateOf("") }

    val gains = settings.equalizerBands
    val pad = TonePadSetting(settings.equalizerToneX, settings.equalizerToneY)
    val preset = remember(gains) { EqualizerPreset.matching(gains) }
    val preamp = remember(gains, settings.equalizerHeadroomDb) {
        preampFor(gains, settings.equalizerHeadroomDb)
    }
    val peak = gains.maxOrNull() ?: 0f

    /** Writes a curve, keeping the pad and the preset name in step with it. */
    fun writeCurve(newGains: List<Float>, touchedPad: Boolean) {
        onUpdate { current ->
            current.copy(
                equalizerBands = newGains,
                equalizerPreset = EqualizerPreset.matching(newGains).name,
                // Only the pad the user did not touch is recomputed, so dragging
                // the pad cannot be nudged by its own inverse.
                equalizerToneX = if (touchedPad) current.equalizerToneX
                else padFor(newGains).x,
                equalizerToneY = if (touchedPad) current.equalizerToneY
                else padFor(newGains).y,
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(24.dp),
    ) {
        SectionHeader("Equalizer") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SubtleButton(onClick = onClose) {
                    Icon(FluentGlyphs.Back, contentDescription = null)
                    Text("Back", modifier = Modifier.padding(start = 6.dp))
                }
                SubtleButton(onClick = {
                    writeCurve(EqualizerPreset.FLAT.bands, touchedPad = false)
                    onUpdate {
                        it.copy(
                            equalizerToneX = 0f,
                            equalizerToneY = 0f,
                            equalizerFocused = false,
                        )
                    }
                }) {
                    Icon(FluentGlyphs.Refresh, contentDescription = null)
                    Text("Reset", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }

        if (!engineAvailable) {
            VerticalGap(12.dp)
            InfoBar(
                title = { Text("Audio engine unavailable") },
                message = {
                    Text(
                        "The curve can still be edited and saved; it will be applied " +
                            "as soon as the engine loads.",
                    )
                },
                severity = InfoBarSeverity.Warning,
            )
        }

        if (peak > 0f) {
            VerticalGap(12.dp)
            InfoBar(
                title = { Text("Headroom") },
                message = {
                    Text(
                        "This curve boosts up to ${formatDb(peak)}, so the preamp is set to " +
                            "${formatDb(preamp)} to keep it from clipping. Turn the automatic " +
                            "preamp off only if the source material already has the room.",
                    )
                },
                severity = if (preamp <= -6f) InfoBarSeverity.Warning else InfoBarSeverity.Informational,
            )
        }

        VerticalGap(16.dp)

        SettingsGroup(
            title = "Tone",
            detail = "The pad and the sliders are two views of one curve.",
        ) {
            ToggleRow(
                title = "Enable equalizer",
                checked = settings.equalizerEnabled,
                onCheckedChange = { enabled -> onUpdate { it.copy(equalizerEnabled = enabled) } },
            )
            EnumRow(
                title = "Mode",
                options = EqualizerMode.entries,
                selected = settings.equalizerMode,
                labelOf = { it.label },
                onSelect = { mode ->
                    onUpdate { current ->
                        // Entering dynamic mode takes the pad's curve as the
                        // starting point, so switching modes never jumps the sound.
                        if (mode == EqualizerMode.DYNAMIC) {
                            current.copy(
                                equalizerMode = mode,
                                equalizerBands = curveFor(
                                    TonePadSetting(current.equalizerToneX, current.equalizerToneY),
                                ),
                            )
                        } else {
                            current.copy(equalizerMode = mode)
                        }
                    }
                },
                detail = "Dynamic drives the bands from the pad; Manual leaves them to the sliders.",
                enabled = settings.equalizerEnabled,
            )
            ToggleRow(
                title = "Portrait focus mode",
                checked = settings.equalizerFocused,
                onCheckedChange = { focused -> onUpdate { it.copy(equalizerFocused = focused) } },
                detail = "Records that the pad was the last thing touched.",
                enabled = settings.equalizerEnabled,
            )
        }

        VerticalGap(16.dp)

        SettingsGroup(
            title = "Preset",
            detail = "A preset is a place to start from, not a target.",
        ) {
            EnumRow(
                title = "Preset",
                options = EqualizerPreset.menuOrder,
                selected = preset,
                labelOf = { it.displayName() },
                onSelect = { chosen ->
                    writeCurve(chosen.bands, touchedPad = false)
                    onUpdate {
                        it.copy(
                            equalizerToneX = padFor(chosen.bands).x,
                            equalizerToneY = padFor(chosen.bands).y,
                        )
                    }
                },
                detail = if (preset == EqualizerPreset.CUSTOM) {
                    "The bands have been edited and no longer match a preset."
                } else {
                    null
                },
                enabled = settings.equalizerEnabled,
            )
        }

        VerticalGap(16.dp)

        SettingsGroup(
            title = "Tone pad",
            detail = "Left to right is dark to bright; top to bottom is full to thin.",
        ) {
            TonePad(
                pad = pad,
                enabled = settings.equalizerEnabled,
                onChange = { next ->
                    onUpdate {
                        it.copy(
                            equalizerToneX = next.x,
                            equalizerToneY = next.y,
                            equalizerMode = EqualizerMode.DYNAMIC,
                            equalizerBands = curveFor(next),
                            equalizerPreset = EqualizerPreset.matching(curveFor(next)).name,
                        )
                    }
                },
            )
            VerticalGap(8.dp)
            KeyValueRow("Position", pad.label)
            KeyValueRow(
                "Resulting curve",
                gains.joinToString("  ") { formatDb(it) },
            )
        }

        VerticalGap(16.dp)

        SettingsGroup(
            title = "Bands",
            detail = "Ten ISO octave centres, ${formatDb(GAIN_RANGE_DB)} at the extremes.",
        ) {
            IsoBand.entries.forEachIndexed { index, band ->
                SliderRow(
                    title = band.label,
                    value = gains.getOrElse(index) { 0f },
                    onValueChange = { value ->
                        val next = gains.toMutableList().also { it[index] = value }
                        writeCurve(next, touchedPad = false)
                    },
                    valueLabel = formatDb(gains.getOrElse(index) { 0f }),
                    range = -GAIN_RANGE_DB..GAIN_RANGE_DB,
                    steps = 0,
                    enabled = settings.equalizerEnabled &&
                        settings.equalizerMode == EqualizerMode.MANUAL,
                    detail = if (settings.equalizerMode == EqualizerMode.DYNAMIC) {
                        "Driven by the tone pad."
                    } else {
                        null
                    },
                )
            }
        }

        VerticalGap(16.dp)

        SettingsGroup(title = "Level") {
            ToggleRow(
                title = "Automatic preamp",
                checked = settings.equalizerHeadroomDb > 0f,
                onCheckedChange = { automatic ->
                    onUpdate { it.copy(equalizerHeadroomDb = if (automatic) 1.5f else 0f) }
                },
                detail = "Keeps a fixed margin below full scale so a boosted band cannot clip.",
                enabled = settings.equalizerEnabled,
            )
            if (settings.equalizerHeadroomDb > 0f) {
                SliderRow(
                    title = "Headroom",
                    value = settings.equalizerHeadroomDb,
                    onValueChange = { value -> onUpdate { it.copy(equalizerHeadroomDb = value) } },
                    valueLabel = formatDb(settings.equalizerHeadroomDb),
                    range = 0f..6f,
                    steps = 11,
                    detail = "1.5 dB is inaudible on its own and covers inter-sample peaks.",
                    enabled = settings.equalizerEnabled,
                )
            }
            KeyValueRow(
                "Applied preamp",
                if (settings.equalizerHeadroomDb > 0f) formatDb(preamp) else "0.0 dB (manual)",
            )
            SliderRow(
                title = "Balance",
                value = settings.equalizerBalance,
                onValueChange = { value -> onUpdate { it.copy(equalizerBalance = value) } },
                valueLabel = when {
                    abs(settings.equalizerBalance) < 0.02f -> "Centre"
                    settings.equalizerBalance < 0f -> "${(abs(settings.equalizerBalance) * 100).toInt()}% left"
                    else -> "${(settings.equalizerBalance * 100).toInt()}% right"
                },
                range = -1f..1f,
                steps = 0,
                enabled = settings.equalizerEnabled,
            )
        }

        VerticalGap(16.dp)

        SettingsGroup(
            title = "Saved curves",
            detail = "${settings.savedEqualizerCurves.size} saved.",
        ) {
            ActionRow {
                TextRow(
                    title = "Name",
                    value = curveName,
                    onValueChange = { curveName = it },
                    placeholder = "Warm headphones",
                    modifier = Modifier.width(280.dp),
                )
                SubtleButton(
                    onClick = {
                        val name = curveName.trim().ifBlank { return@SubtleButton }
                        val curve = SavedCurve(
                            name = name,
                            gainsDb = gains,
                            preampDb = preamp,
                            padX = pad.x,
                            padY = pad.y,
                        )
                        onUpdate { current ->
                            // Same-name saves replace rather than accumulate, which
                            // is what a user re-saving after a tweak expects.
                            val kept = current.savedEqualizerCurves.filterNot { it.name == name }
                            current.copy(savedEqualizerCurves = kept + curve)
                        }
                        curveName = ""
                    },
                ) { Text("Save") }
            }

            if (settings.savedEqualizerCurves.isEmpty()) {
                VerticalGap(4.dp)
                Text(
                    text = "Nothing saved yet. Move a slider, name the result and save it.",
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.tertiary,
                )
            }

            settings.savedEqualizerCurves.forEach { curve ->
                ListItem(
                    selected = false,
                    onSelectedChanged = {
                        writeCurve(curve.gainsDb, touchedPad = true)
                        onUpdate {
                            it.copy(
                                equalizerToneX = curve.padX,
                                equalizerToneY = curve.padY,
                                equalizerMode = EqualizerMode.MANUAL,
                            )
                        }
                    },
                    text = { Text(curve.name) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(FluentGlyphs.Save, contentDescription = null) },
                    trailing = {
                        Text(
                            text = formatDb(curve.preampDb),
                            style = FluentTheme.typography.caption,
                            color = FluentTheme.colors.text.text.tertiary,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        SubtleButton(onClick = {
                            onUpdate { current ->
                                current.copy(
                                    savedEqualizerCurves =
                                        current.savedEqualizerCurves.filterNot { it.name == curve.name },
                                )
                            }
                        }) {
                            Icon(FluentGlyphs.Delete, contentDescription = "Delete")
                        }
                    },
                )
            }
        }
    }
}

/**
 * The two-axis tone pad.
 *
 * Drawn by hand rather than composed from sliders because the point of a pad is
 * the continuous gesture: crossing from "warm and full" to "bright and thin" is
 * one diagonal drag, and no arrangement of two sliders makes that a single
 * movement. The crosshair and the grid are there so an exact neutral is findable
 * again after a drag.
 */
@Composable
private fun TonePad(pad: TonePadSetting, enabled: Boolean, onChange: (TonePadSetting) -> Unit) {
    val accent = FluentTheme.colors.text.accent.primary
    val grid = FluentTheme.colors.stroke.divider.default
    val surface = FluentTheme.colors.background.layer.default
    val knob = FluentTheme.colors.background.solid.base

    Box(
        modifier = Modifier
            .size(240.dp)
            .clip(FluentTheme.shapes.control)
            .background(surface),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (!enabled) {
                        Modifier
                    } else {
                        Modifier.pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                val x = (change.position.x / size.width) * 2f - 1f
                                val y = 1f - (change.position.y / size.height) * 2f
                                onChange(
                                    TonePadSetting(
                                        x = x.coerceIn(-1f, 1f),
                                        y = y.coerceIn(-1f, 1f),
                                    ),
                                )
                            }
                        }.pointerInput(Unit) {
                            // A tap is a legitimate way to place the knob; without
                            // this the pad would only respond once a drag started.
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Press) {
                                        val position = event.changes.first().position
                                        onChange(
                                            TonePadSetting(
                                                x = (position.x / size.width * 2f - 1f).coerceIn(-1f, 1f),
                                                y = (1f - position.y / size.height * 2f).coerceIn(-1f, 1f),
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    },
                ),
        ) {
            val stepX = size.width / 4f
            val stepY = size.height / 4f
            for (i in 1..3) {
                drawLine(grid, Offset(stepX * i, 0f), Offset(stepX * i, size.height), 1f)
                drawLine(grid, Offset(0f, stepY * i), Offset(size.width, stepY * i), 1f)
            }
            drawLine(
                color = grid,
                start = Offset(size.width / 2f, 0f),
                end = Offset(size.width / 2f, size.height),
                strokeWidth = 2f,
            )
            drawLine(
                color = grid,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 2f,
            )

            val cx = (pad.x + 1f) / 2f * size.width
            val cy = (1f - pad.y) / 2f * size.height
            drawCircle(accent, radius = 9f, center = Offset(cx, cy))
            drawCircle(
                color = knob,
                radius = 9f,
                center = Offset(cx, cy),
                style = Stroke(width = 2f),
            )
            drawLine(
                color = accent,
                start = Offset(0f, cy),
                end = Offset(size.width, cy),
                strokeWidth = 1f,
            )
            drawLine(
                color = accent,
                start = Offset(cx, 0f),
                end = Offset(cx, size.height),
                strokeWidth = 1f,
            )
        }
    }
}

/** Signed decibels, one decimal. `+6.0 dB`, `0.0 dB`, `-1.5 dB`. */
private fun formatDb(value: Float): String =
    (if (value > 0f) "+" else "") + String.format("%.1f", value) + " dB"

/** `BASS_BOOST` reads as `Bass boost`; the settings file keeps the enum name. */
private fun EqualizerPreset.displayName(): String = name
    .lowercase()
    .replace('_', ' ')
    .replaceFirstChar { it.uppercase() }
