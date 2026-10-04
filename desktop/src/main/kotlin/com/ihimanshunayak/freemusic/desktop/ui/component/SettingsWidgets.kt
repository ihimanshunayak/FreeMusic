// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - Fluent settings widgets.
//
// NAME
//     SettingsWidgets.kt - the control row vocabulary every settings page uses.
//
// DESCRIPTION
//     A settings page is a list of the same six shapes over and over: a switch
//     with a title and an explanation, a slider with a live value label, a
//     dropdown over an enum, a text field, a folder picker, and a button. Written
//     per page, those six shapes drift - one page right-aligns its switches and
//     another puts them under the text, one shows the value in the label and
//     another omits it.
//
//     This file is the fix: one implementation of each shape, on Fluent
//     controls, so a page is a list of calls rather than a layout exercise.
//
// RESPONSIBILITIES
//     - [SettingsGroup] - a titled card.
//     - [ToggleRow] - a switch with an explanation.
//     - [SliderRow] - a slider with a formatted live value.
//     - [ChoiceRow] / [EnumRow] - a dropdown over a fixed set.
//     - [TextRow] - a single-line text field.
//     - [PathRow] - a read-only path with a Change button.
//
// DEPENDENCIES
//     - Compose Fluent's Switcher, Slider, TextField, Button and Card.
//
// INTEGRATION NOTES
//     - Every row takes a `detail` string rather than a composed body: the
//       explanation is always one sentence of caption text directly under the
//       title, and letting a caller compose something else into that slot is
//       what produces inconsistent pages.
//     - Rows are stateless. The value comes in and the change goes out, so a
//       settings page holds no state of its own beyond the store.

package com.ihimanshunayak.freemusic.desktop.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.ComboBox
import io.github.composefluent.surface.Card
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Switcher
import io.github.composefluent.component.Text
import io.github.composefluent.component.TextField
import androidx.compose.ui.text.input.TextFieldValue

/** The width every control in a settings row is given, so the column lines up. */
private val ControlWidth = 240.dp

/**
 * A titled card.
 *
 * Fluent's own settings pages put each group on its own card rather than under a
 * bare heading, because a card is what makes a long scrolling page readable: the
 * grouping is visible at a glance instead of requiring the reader to notice a
 * font-size change.
 */
@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = FluentTheme.typography.bodyStrong)
            if (detail != null) {
                Text(
                    text = detail,
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                )
            }
            content()
        }
    }
}

/** A switch with a title, an explanation, and the control on the trailing edge. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    detail: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = title,
                style = FluentTheme.typography.body,
                color = if (enabled) FluentTheme.colors.text.text.primary
                else FluentTheme.colors.text.text.disabled,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                )
            }
        }
        Switcher(
            checked = checked,
            onCheckStateChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/**
 * A slider with its live value printed on the trailing edge of the label row.
 *
 * The value sits beside the title rather than under the track because a user
 * dragging a slider is watching the thumb, not the caption, and a percentage that
 * moves under the label makes the whole row shift as the digits change width.
 */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    detail: String? = null,
    enabled: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = FluentTheme.typography.body,
                    color = if (enabled) FluentTheme.colors.text.text.primary
                    else FluentTheme.colors.text.text.disabled,
                )
                if (!detail.isNullOrBlank()) {
                    Text(
                        text = detail,
                        style = FluentTheme.typography.caption,
                        color = FluentTheme.colors.text.text.secondary,
                    )
                }
            }
            Text(
                text = valueLabel,
                style = FluentTheme.typography.body,
                color = FluentTheme.colors.text.text.secondary,
            )
        }
        io.github.composefluent.component.Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/**
 * A dropdown over a fixed set of labels.
 *
 * Takes labels rather than a generic type because the caller usually has an enum
 * whose stored name and displayed label differ, and mapping it here would mean
 * every caller passing two lambdas.
 */
@Composable
fun ChoiceRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    detail: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = title,
                style = FluentTheme.typography.body,
                color = if (enabled) FluentTheme.colors.text.text.primary
                else FluentTheme.colors.text.text.disabled,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                )
            }
        }
        ComboBox(
            modifier = Modifier.width(ControlWidth),
            items = options,
            selected = selectedIndex.takeIf { it in options.indices },
            disabled = !enabled,
            onSelectionChange = { index, _ -> onSelect(index) },
        )
    }
}

/** A dropdown over an enum, so a page does not have to map entries to labels. */
@Composable
fun <T> EnumRow(
    title: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    detail: String? = null,
    enabled: Boolean = true,
) {
    ChoiceRow(
        title = title,
        options = options.map(labelOf),
        selectedIndex = options.indexOf(selected),
        onSelect = { index -> options.getOrNull(index)?.let(onSelect) },
        detail = detail,
        enabled = enabled,
    )
}

/** A single-line text field with a label and an optional explanation. */
@Composable
fun TextRow(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    detail: String? = null,
    placeholder: String = "",
    enabled: Boolean = true,
    secret: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var field by remember(value) { mutableStateOf(TextFieldValue(value)) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = title,
                style = FluentTheme.typography.body,
                color = if (enabled) FluentTheme.colors.text.text.primary
                else FluentTheme.colors.text.text.disabled,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                )
            }
        }
        TextField(
            value = field,
            onValueChange = {
                field = it
                onValueChange(it.text)
            },
            modifier = Modifier.width(ControlWidth),
            enabled = enabled,
            singleLine = true,
            placeholder = { Text(placeholder) },
            // A secret is masked with a visual transformation rather than by
            // never showing it: the user has to be able to confirm a token was
            // pasted correctly, and a field that shows nothing at all makes that
            // impossible.
            visualTransformation = if (secret) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
        )
    }
}

/** A read-only path with a button that opens a picker. */
@Composable
fun PathRow(
    title: String,
    path: String,
    onBrowse: () -> Unit,
    buttonLabel: String = "Change",
    detail: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = FluentTheme.typography.body)
            Text(
                text = path.ifBlank { "Not set" },
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.text.text.secondary,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                )
            }
        }
        SubtleButton(onClick = onBrowse, disabled = !enabled) { Text(buttonLabel) }
    }
}

/** A row of buttons, used for the actions that apply to a whole group. */
@Composable
fun ActionRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
