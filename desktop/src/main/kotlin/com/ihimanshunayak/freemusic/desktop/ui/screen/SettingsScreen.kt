// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - settings.
//
// NAME
//     SettingsScreen.kt - every persisted preference, in one page.
//
// DESCRIPTION
//     The single page that owns `settings.json`. It is written as a list of
//     visible sections in the order a user actually meets them rather than in the
//     order the fields happen to be declared, because the file's order is a
//     storage detail and this order is an argument: playback first, then what the
//     window looks like, then the two audio features a listener tunes, then the
//     places music can come from, then the services that watch what is played,
//     then the internals that only matter when something is wrong.
//
//     Every write goes straight through [onUpdate] to the store, which writes
//     atomically. There is no Apply button and no dirty state: a settings page
//     that can be left unsaved is a settings page whose state is a second source
//     of truth for values that already have an owner.
//
//     Fields whose backing feature lives on another screen are still shown here,
//     with a pointer to that screen, because a user looking for "Equalizer" in
//     settings should find the name and be told where the controls are rather
//     than find nothing.
//
// RESPONSIBILITIES
//     - Render every persisted preference with an appropriate control.
//     - Group them so a long page stays navigable.
//     - Write each change through to the store immediately.
//     - Show the paths, versions and engine state needed to diagnose a problem.
//
// DEPENDENCIES
//     - [Settings] from the data layer, as the read model.
//     - [SettingsWidgets] for the row shapes, so this page holds no control
//       styling of its own.
//
// INTEGRATION NOTES
//     - The signature is deliberately wide-callback rather than a bag of
//       individual lambdas: the page edits one immutable [Settings] through a
//       transform, and every row follows that shape, so adding a field never
//       means adding a parameter.
//     - Reordering the enabled lyrics providers and the enabled sources is done
//       on their own screens (Lyrics, Sources), because both are order-sensitive
//       lists rather than single values and need drag handles that would be out
//       of place here. Settings only toggles whole features on and off.
//     - The engine-unavailable notice is repeated from the side bar on purpose:
//       a user who opens Settings to fix silent playback is not looking at the
//       side bar.

package com.ihimanshunayak.freemusic.desktop.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ihimanshunayak.freemusic.desktop.data.AudioQuality
import com.ihimanshunayak.freemusic.desktop.data.CanvasMode
import com.ihimanshunayak.freemusic.desktop.data.DiscordActivityType
import com.ihimanshunayak.freemusic.desktop.data.DownloadQuality
import com.ihimanshunayak.freemusic.desktop.data.EqualizerMode
import com.ihimanshunayak.freemusic.desktop.data.LastPlayerScreen
import com.ihimanshunayak.freemusic.desktop.data.LibrarySort
import com.ihimanshunayak.freemusic.desktop.data.LibraryViewType
import com.ihimanshunayak.freemusic.desktop.data.LocalMusicSort
import com.ihimanshunayak.freemusic.desktop.data.OutputBackend
import com.ihimanshunayak.freemusic.desktop.data.Settings
import com.ihimanshunayak.freemusic.desktop.data.ThemePreference
import com.ihimanshunayak.freemusic.desktop.lyrics.LyricsSource
import com.ihimanshunayak.freemusic.desktop.model.RepeatMode
import com.ihimanshunayak.freemusic.desktop.util.AppPaths
import com.ihimanshunayak.freemusic.desktop.util.Log
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.Icon
import io.github.composefluent.component.InfoBar
import io.github.composefluent.component.InfoBarSeverity
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import com.ihimanshunayak.freemusic.desktop.ui.component.ActionRow
import com.ihimanshunayak.freemusic.desktop.ui.component.ChoiceRow
import com.ihimanshunayak.freemusic.desktop.ui.component.EnumRow
import com.ihimanshunayak.freemusic.desktop.ui.component.FluentGlyphs
import com.ihimanshunayak.freemusic.desktop.ui.component.PathRow
import com.ihimanshunayak.freemusic.desktop.ui.component.SettingsGroup
import com.ihimanshunayak.freemusic.desktop.ui.component.SliderRow
import com.ihimanshunayak.freemusic.desktop.ui.component.TextRow
import com.ihimanshunayak.freemusic.desktop.ui.component.ToggleRow

/**
 * The settings page.
 *
 * @param settings the current values; re-read on every change, so the page is
 *   always showing what the store holds rather than a copy.
 * @param engineAvailable whether libVLC loaded, so the audio group can explain
 *   itself when it did not.
 * @param onUpdate applies a transform to the settings and persists the result.
 * @param onPickDownloadsFolder opens the OS folder picker.
 * @param onPickExportFolder opens the OS folder picker for the export target.
 * @param onClearCache drops every cached audio file.
 * @param cacheBytes the size of the audio cache, for the label.
 * @param onResetSettings restores the defaults, after a confirmation.
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    engineAvailable: Boolean,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onPickDownloadsFolder: () -> Unit,
    onPickExportFolder: () -> Unit,
    onClearCache: () -> Unit,
    cacheBytes: Long,
    onResetSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmReset by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(Section.PLAYBACK) }

    Row(modifier = modifier.fillMaxSize()) {
        // The section list, laid out the way the Windows Settings app does it: a
        // narrow rail of labels rather than a horizontal strip, because twelve
        // destinations do not fit across a window at any sane font size and a
        // wrapping strip makes the page height jump as the window is resized.
        Column(
            modifier = Modifier
                .width(232.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 20.dp),
        ) {
            Text(
                text = "Settings",
                style = FluentTheme.typography.title,
                modifier = Modifier.padding(start = 12.dp, bottom = 12.dp),
            )
            Section.entries.forEach { entry ->
                SettingsNavItem(
                    label = entry.label,
                    glyph = entry.glyph,
                    selected = entry == section,
                    onClick = { section = entry },
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 8.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(section.label, style = FluentTheme.typography.titleLarge)
            Text(
                text = "Changes are saved as you make them.",
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.text.text.secondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            if (!engineAvailable) {
                InfoBar(
                    title = { Text("The audio engine did not start") },
                    message = {
                        Text(
                            "Install VLC from videolan.org and reopen Free Music. Everything " +
                                "except playback still works.",
                        )
                    },
                    severity = InfoBarSeverity.Warning,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            when (section) {
                Section.PLAYBACK -> PlaybackGroup(settings, onUpdate, engineAvailable)
                Section.APPEARANCE -> AppearanceGroup(settings, onUpdate)
                Section.EQUALIZER -> EqualizerGroup(settings, onUpdate, engineAvailable)
                Section.LYRICS -> LyricsGroup(settings, onUpdate)
                Section.LIBRARIES -> LibrariesGroup(settings, onUpdate)
                Section.DOWNLOADS ->
                    DownloadsGroup(settings, onUpdate, onPickDownloadsFolder, onPickExportFolder)
                Section.SOURCES -> SourcesGroup(settings, onUpdate)
                Section.SCROBBLING -> ScrobblingGroup(settings, onUpdate)
                Section.DISCORD -> DiscordGroup(settings, onUpdate)
                Section.LISTEN_TOGETHER -> ListenTogetherGroup(settings, onUpdate)
                Section.STORAGE -> StorageGroup(settings, onUpdate, onClearCache, cacheBytes)
                Section.ABOUT -> AboutGroup(onResetSettings = { confirmReset = true })
            }
        }
    }

    if (confirmReset) {
        ContentDialog(
            title = "Reset every setting?",
            visible = true,
            content = {
                Text(
                    "Playback, appearance, downloads, scrobbling and library preferences all " +
                        "return to their defaults. Your downloaded files, playlists and play " +
                        "history are not touched. This cannot be undone.",
                )
            },
            primaryButtonText = "Reset",
            closeButtonText = "Cancel",
            onButtonClick = { button ->
                if (button == ContentDialogButton.Primary) onResetSettings()
                confirmReset = false
            },
        )
    }
}

/** The sections of the settings page, in reading order. */
private enum class Section(val label: String, val glyph: ImageVector) {
    PLAYBACK("Playback", FluentGlyphs.Play),
    APPEARANCE("Appearance", FluentGlyphs.Palette),
    EQUALIZER("Equalizer", FluentGlyphs.Equalizer),
    LYRICS("Lyrics", FluentGlyphs.Lyrics),
    LIBRARIES("Libraries", FluentGlyphs.Library),
    DOWNLOADS("Downloads", FluentGlyphs.Downloads),
    SOURCES("Sources", FluentGlyphs.Sources),
    SCROBBLING("Scrobbling", FluentGlyphs.Trending),
    DISCORD("Discord", FluentGlyphs.Chat),
    LISTEN_TOGETHER("Listen Together", FluentGlyphs.ListenTogether),
    STORAGE("Storage", FluentGlyphs.Database),
    ABOUT("About", FluentGlyphs.Info),
}

/**
 * One row of the section rail.
 *
 * Fluent's `ListItem` is close, but its selected state paints a full accent pill
 * and this rail is narrow enough that the pill would dominate the page. A plain
 * row with an accent bar reads the way the Windows Settings rail does.
 */
@Composable
private fun SettingsNavItem(
    label: String,
    glyph: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = FluentTheme.colors.text.accent.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FluentTheme.shapes.control)
            .background(
                if (selected) FluentTheme.colors.subtleFill.secondary
                else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Three pixels of accent, inset, rather than a filled pill: it marks the
        // selection without competing with the content beside it.
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) accent else Color.Transparent),
        )
        Icon(
            glyph,
            contentDescription = null,
            modifier = Modifier.padding(start = 9.dp),
            tint = if (selected) accent else FluentTheme.colors.text.text.secondary,
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 10.dp),
            style = FluentTheme.typography.body,
            color = if (selected) FluentTheme.colors.text.text.primary
            else FluentTheme.colors.text.text.secondary,
        )
    }
}

// ---------------------------------------------------------------------------
// ## SECTION: Groups
// ---------------------------------------------------------------------------

@Composable
private fun PlaybackGroup(
    settings: Settings,
    onUpdate: ((Settings) -> Settings) -> Unit,
    engineAvailable: Boolean,
) {
    SettingsGroup(
        title = "Playback",
        detail = "How a stream is fetched and how it is played back.",
    ) {
        EnumRow(
            title = "Audio quality",
            detail = "Higher settings ask the source for a larger stream. On a metered " +
                "connection, lower is smoother.",
            options = AudioQuality.entries,
            selected = settings.audioQuality,
            labelOf = { it.label },
            onSelect = { quality -> onUpdate { it.copy(audioQuality = quality) } },
        )
        EnumRow(
            title = "Audio output",
            detail = "Which audio device the engine opens. Automatic is right unless a " +
                "driver misbehaves.",
            options = OutputBackend.entries,
            selected = settings.outputBackend,
            labelOf = { it.label },
            onSelect = { backend -> onUpdate { it.copy(outputBackend = backend) } },
            enabled = engineAvailable,
        )
        ToggleRow(
            title = "Normalise volume",
            detail = "Evens out loud and quiet tracks. Shares headroom with the equalizer " +
                "preamp, so enabling both lowers the output a little.",
            checked = settings.normalizeVolume,
            onCheckedChange = { on -> onUpdate { it.copy(normalizeVolume = on) } },
            enabled = engineAvailable,
        )
        ToggleRow(
            title = "Skip silence",
            detail = "Cuts long quiet passages at the start and end of a track.",
            checked = settings.skipSilence,
            onCheckedChange = { on -> onUpdate { it.copy(skipSilence = on) } },
        )
        ToggleRow(
            title = "Autoplay",
            detail = "Keeps playing related music once the queue runs out.",
            checked = settings.autoplay,
            onCheckedChange = { on -> onUpdate { it.copy(autoplay = on) } },
        )
        ToggleRow(
            title = "Music only",
            detail = "Hides podcasts, mixes and other non-song results from Browse and Search.",
            checked = settings.musicOnly,
            onCheckedChange = { on -> onUpdate { it.copy(musicOnly = on) } },
        )
        SliderRow(
            title = "Playback speed",
            value = settings.playbackSpeed,
            onValueChange = { speed -> onUpdate { it.copy(playbackSpeed = speed) } },
            valueLabel = "%.2fx".format(settings.playbackSpeed),
            range = 0.5f..2.0f,
            steps = 14,
            enabled = engineAvailable,
        )
        SliderRow(
            title = "Volume",
            value = settings.volume,
            onValueChange = { volume -> onUpdate { it.copy(volume = volume) } },
            valueLabel = "${(settings.volume * 100).toInt()}%",
            steps = 19,
        )
        SliderRow(
            title = "Crossfade",
            value = settings.crossfadeSeconds.toFloat(),
            onValueChange = { seconds -> onUpdate { it.copy(crossfadeSeconds = seconds.toInt()) } },
            valueLabel = if (settings.crossfadeSeconds == 0) "Off" else "${settings.crossfadeSeconds}s",
            range = 0f..12f,
            steps = 11,
        )
        ToggleRow(
            title = "Smart fade",
            detail = "Fades out over the track's own ending when one is detected, instead of " +
                "always using the full crossfade length.",
            checked = settings.smartFade,
            onCheckedChange = { on -> onUpdate { it.copy(smartFade = on) } },
            enabled = settings.crossfadeSeconds > 0,
        )
        EnumRow(
            title = "Repeat",
            detail = "Also on the transport bar.",
            options = RepeatMode.entries,
            selected = settings.repeatMode,
            labelOf = { mode ->
                when (mode) {
                    RepeatMode.OFF -> "Off"
                    RepeatMode.ALL -> "Repeat queue"
                    RepeatMode.ONE -> "Repeat track"
                }
            },
            onSelect = { mode -> onUpdate { it.copy(repeatMode = mode) } },
        )
        ToggleRow(
            title = "Shuffle",
            checked = settings.shuffle,
            onCheckedChange = { on -> onUpdate { it.copy(shuffle = on) } },
        )
        EnumRow(
            title = "Open at start-up on",
            detail = "Where the window lands when Free Music opens.",
            options = LastPlayerScreen.entries,
            selected = settings.lastPlayerScreen,
            labelOf = { it.label },
            onSelect = { target -> onUpdate { it.copy(lastPlayerScreen = target) } },
        )
    }
}

@Composable
private fun AppearanceGroup(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit) {
    SettingsGroup(
        title = "Appearance",
        detail = "The window chrome. Free Music follows the Fluent design system, so these " +
            "map onto what Windows itself does.",
    ) {
        EnumRow(
            title = "Theme",
            options = ThemePreference.entries,
            selected = settings.theme,
            labelOf = { it.label },
            onSelect = { theme -> onUpdate { it.copy(theme = theme) } },
        )
        ToggleRow(
            title = "Accent from artwork",
            detail = "Takes the accent colour from the playing track's cover. This is the " +
                "default because it is what makes the window feel alive.",
            checked = settings.dynamicAccent,
            onCheckedChange = { on -> onUpdate { it.copy(dynamicAccent = on) } },
        )
        ToggleRow(
            title = "Use the Free Music brand accent",
            detail = "Pins the accent to the app's own colour instead of the Windows accent. " +
                "Takes precedence over artwork tinting.",
            checked = settings.brandAccent,
            onCheckedChange = { on -> onUpdate { it.copy(brandAccent = on) } },
        )
        ToggleRow(
            title = "Mica backdrop",
            detail = "Blurs the desktop wallpaper and the windows behind into the chrome, the " +
                "way Explorer does. Off falls back to a plain solid surface.",
            checked = settings.micaBackdrop,
            onCheckedChange = { on -> onUpdate { it.copy(micaBackdrop = on) } },
        )
        ToggleRow(
            title = "Reduce animation",
            detail = "Shortens or removes transitions. Turn this on if motion is uncomfortable.",
            checked = settings.reduceAnimation,
            onCheckedChange = { on -> onUpdate { it.copy(reduceAnimation = on) } },
        )
        ToggleRow(
            title = "Reduce transparency",
            detail = "Drops the blur behind the side bar and transport bar, which also helps on " +
                "a machine with a slow GPU.",
            checked = settings.reduceBlur,
            onCheckedChange = { on -> onUpdate { it.copy(reduceBlur = on) } },
        )
        ToggleRow(
            title = "Show diagnostics",
            detail = "Adds a log viewer and engine status page to the side bar.",
            checked = settings.showDiagnostics,
            onCheckedChange = { on -> onUpdate { it.copy(showDiagnostics = on) } },
        )
        ToggleRow(
            title = "Show playback technical detail",
            detail = "Adds a codec, bitrate and buffer readout to the now-playing screen.",
            checked = settings.showNerdStats,
            onCheckedChange = { on -> onUpdate { it.copy(showNerdStats = on) } },
        )
        TextRow(
            title = "Interface language",
            detail = "The language Free Music asks YouTube Music for. Interface strings are in " +
                "English for now.",
            value = settings.language,
            onValueChange = { code -> onUpdate { it.copy(language = code.trim()) } },
            placeholder = "en",
            modifier = Modifier.fillMaxWidth(),
        )
        TextRow(
            title = "Region",
            detail = "Sets which charts Browse shows and which results are available.",
            value = settings.region,
            onValueChange = { code -> onUpdate { it.copy(region = code.trim().uppercase()) } },
            placeholder = "US",
            modifier = Modifier.fillMaxWidth(),
        )
        EnumRow(
            title = "Artwork animation",
            detail = "Source Only keeps the still cover and animates only when a track has a " +
                "canvas video. Off is the calmest setting and the cheapest on a laptop.",
            options = CanvasMode.entries,
            selected = settings.canvasMode,
            labelOf = { it.label },
            onSelect = { mode -> onUpdate { it.copy(canvasMode = mode) } },
        )
        ToggleRow(
            title = "Full-bleed artwork",
            detail = "Fills the now-playing pane edge to edge with the cover instead of " +
                "framing it on a card.",
            checked = settings.fullBleedArtwork,
            onCheckedChange = { on -> onUpdate { it.copy(fullBleedArtwork = on) } },
            enabled = settings.canvasMode != CanvasMode.OFF,
        )
        SliderRow(
            title = "Hide artwork controls after",
            detail = "Seconds of stillness before the transport controls over the artwork " +
                "fade out. Move the mouse to bring them back.",
            value = settings.canvasAutoHideSeconds.toFloat(),
            onValueChange = { seconds -> onUpdate { it.copy(canvasAutoHideSeconds = seconds.toInt()) } },
            valueLabel = if (settings.canvasAutoHideSeconds <= 0) "Never" else "${settings.canvasAutoHideSeconds}s",
            range = 0f..30f,
            steps = 5,
            enabled = settings.canvasMode != CanvasMode.OFF && !settings.reduceAnimation,
        )
        ToggleRow(
            title = "Mesh gradient behind the artwork",
            detail = "Draws a soft multi-colour wash derived from the cover behind the " +
                "artwork. Needs the animation setting above to be on.",
            checked = settings.meshGradient,
            onCheckedChange = { on -> onUpdate { it.copy(meshGradient = on) } },
            enabled = settings.canvasMode != CanvasMode.OFF && !settings.reduceAnimation,
        )
    }
}

@Composable
private fun EqualizerGroup(
    settings: Settings,
    onUpdate: ((Settings) -> Settings) -> Unit,
    engineAvailable: Boolean,
) {
    SettingsGroup(
        title = "Equalizer",
        detail = "The curve itself is edited on its own screen, where the sliders and the tone " +
            "pad have room. These are the switches that decide whether it runs at all.",
    ) {
        ToggleRow(
            title = "Enable the equalizer",
            detail = "Off bypasses the filter entirely rather than running a flat curve, which " +
                "is measurably cheaper.",
            checked = settings.equalizerEnabled,
            onCheckedChange = { on -> onUpdate { it.copy(equalizerEnabled = on) } },
            enabled = engineAvailable,
        )
        EnumRow(
            title = "Editing mode",
            detail = "The tone pad moves every band from one gesture; manual sets them one at a " +
                "time.",
            options = EqualizerMode.entries,
            selected = settings.equalizerMode,
            labelOf = { it.label },
            onSelect = { mode -> onUpdate { it.copy(equalizerMode = mode) } },
            enabled = engineAvailable && settings.equalizerEnabled,
        )
        TextRow(
            title = "Current curve",
            detail = "The preset the curve was last taken from.",
            value = settings.equalizerPreset,
            onValueChange = { name -> onUpdate { it.copy(equalizerPreset = name) } },
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
        )
        SliderRow(
            title = "Headroom",
            detail = "How much gain is held back to stop a boosted band clipping.",
            value = settings.equalizerHeadroomDb,
            onValueChange = { db -> onUpdate { it.copy(equalizerHeadroomDb = db) } },
            valueLabel = "%.1f dB".format(settings.equalizerHeadroomDb),
            range = 0f..6f,
            steps = 11,
            enabled = engineAvailable && settings.equalizerEnabled,
        )
        SliderRow(
            title = "Balance",
            value = settings.equalizerBalance,
            onValueChange = { balance -> onUpdate { it.copy(equalizerBalance = balance) } },
            valueLabel = balanceLabel(settings.equalizerBalance),
            range = -1f..1f,
            steps = 19,
            enabled = engineAvailable && settings.equalizerEnabled,
        )
        ActionRow {
            Text(
                text = "${settings.savedEqualizerCurves.size} saved " +
                    if (settings.savedEqualizerCurves.size == 1) "curve" else "curves",
                style = FluentTheme.typography.body,
                color = FluentTheme.colors.text.text.secondary,
            )
        }
    }
}

@Composable
private fun LyricsGroup(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit) {
    val enabled = settings.lyricsSources.toSet()
    SettingsGroup(
        title = "Lyrics",
        detail = "Providers are tried in order until one has the song. " +
            "${enabled.size} of ${LyricsSource.entries.size} are enabled.",
    ) {
        ToggleRow(
            title = "Show lyrics",
            detail = "Adds timings and the scrolling view. Off leaves the providers unused.",
            checked = settings.syncedLyrics,
            onCheckedChange = { on -> onUpdate { it.copy(syncedLyrics = on) } },
        )
        ToggleRow(
            title = "Prefer word-by-word timing",
            detail = "When two providers have the song, the one with per-word timings wins even " +
                "if it comes later in the order.",
            checked = settings.prioritizeSyllableSync,
            onCheckedChange = { on -> onUpdate { it.copy(prioritizeSyllableSync = on) } },
            enabled = settings.syncedLyrics,
        )
        ToggleRow(
            title = "Fade lines that are not current",
            detail = "Dims everything except the line being sung, which is what makes the view " +
                "readable at a glance.",
            checked = settings.lyricsBlur,
            onCheckedChange = { on -> onUpdate { it.copy(lyricsBlur = on) } },
            enabled = settings.syncedLyrics,
        )
        SliderRow(
            title = "Timing offset",
            detail = "Moves every line earlier or later. Negative shows lyrics sooner.",
            value = settings.lyricsOffsetMs.toFloat(),
            onValueChange = { ms -> onUpdate { it.copy(lyricsOffsetMs = ms.toInt()) } },
            valueLabel = "${settings.lyricsOffsetMs} ms",
            range = -5000f..5000f,
            steps = 99,
            enabled = settings.syncedLyrics,
        )
        TextRow(
            title = "Translation language",
            detail = "Asks the providers for a translation alongside the original. Leave blank " +
                "for none.",
            value = settings.translationLanguage,
            onValueChange = { code -> onUpdate { it.copy(translationLanguage = code.trim()) } },
            placeholder = "e.g. hi, en, es",
            enabled = settings.syncedLyrics,
            modifier = Modifier.fillMaxWidth(),
        )
        TextRow(
            title = "PaxSenix API key",
            detail = "Only needed for the two PaxSenix providers that require one. The keyless " +
                "PaxSenix provider works without it.",
            value = settings.paxSenixApiKey,
            onValueChange = { key -> onUpdate { it.copy(paxSenixApiKey = key.trim()) } },
            secret = true,
            enabled = settings.syncedLyrics,
            modifier = Modifier.fillMaxWidth(),
        )
        ActionRow {
            Text(
                text = "Enable, disable and reorder the providers on the Lyrics screen - the " +
                    "order is the fallback chain, so it is edited where the list is visible.",
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.text.text.secondary,
            )
        }
    }
}

@Composable
private fun LibrariesGroup(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit) {
    SettingsGroup(
        title = "Libraries",
        detail = "How the library and local music screens are laid out and sorted.",
    ) {
        EnumRow(
            title = "Library layout",
            options = LibraryViewType.entries,
            selected = settings.localMusicViewType,
            labelOf = { it.label },
            onSelect = { kind -> onUpdate { it.copy(localMusicViewType = kind) } },
        )
        EnumRow(
            title = "Library sort",
            options = LibrarySort.entries,
            selected = settings.librarySort,
            labelOf = { it.label },
            onSelect = { sort -> onUpdate { it.copy(librarySort = sort) } },
        )
        EnumRow(
            title = "Downloaded music layout",
            options = LibraryViewType.entries,
            selected = settings.downloadedMusicViewType,
            labelOf = { it.label },
            onSelect = { kind -> onUpdate { it.copy(downloadedMusicViewType = kind) } },
        )
        EnumRow(
            title = "Downloaded music sort",
            options = LocalMusicSort.entries,
            selected = settings.downloadedMusicSort,
            labelOf = { it.label },
            onSelect = { sort -> onUpdate { it.copy(downloadedMusicSort = sort) } },
        )
        EnumRow(
            title = "Local music sort",
            options = LocalMusicSort.entries,
            selected = settings.localMusicSort,
            labelOf = { it.label },
            onSelect = { sort -> onUpdate { it.copy(localMusicSort = sort) } },
        )
        EnumRow(
            title = "Home shelf layout",
            detail = "The rows of covers on Browse.",
            options = LibraryViewType.entries,
            selected = settings.homeRecentsViewType,
            labelOf = { it.label },
            onSelect = { kind -> onUpdate { it.copy(homeRecentsViewType = kind) } },
        )
        PathRow(
            title = "Local music folders",
            path = "${settings.localMusicFolders.size} added",
            detail = "Managed on the Local music screen, where the scan can be started.",
            buttonLabel = "Manage",
            onBrowse = { },
        )
        TextRow(
            title = "WebDAV address",
            detail = "A WebDAV share to browse as if it were local. The full connection is " +
                "tested on the Remote library screen.",
            value = settings.webdavUrl,
            onValueChange = { url -> onUpdate { it.copy(webdavUrl = url.trim()) } },
            placeholder = "https://example.com/dav/music",
            modifier = Modifier.fillMaxWidth(),
        )
        TextRow(
            title = "WebDAV user name",
            value = settings.webdavUsername,
            onValueChange = { name -> onUpdate { it.copy(webdavUsername = name) } },
            modifier = Modifier.fillMaxWidth(),
        )
        TextRow(
            title = "WebDAV password",
            detail = "Stored in settings.json in plain text. Use a share-scoped password.",
            value = settings.webdavPassword,
            onValueChange = { pass -> onUpdate { it.copy(webdavPassword = pass) } },
            secret = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextRow(
            title = "SMB server",
            detail = "SMB uses the signed-in Windows account, so there is no password here.",
            value = settings.smbHost,
            onValueChange = { host -> onUpdate { it.copy(smbHost = host.trim()) } },
            placeholder = "nas or 192.168.1.10",
            modifier = Modifier.fillMaxWidth(),
        )
        TextRow(
            title = "SMB share",
            value = settings.smbShare,
            onValueChange = { share -> onUpdate { it.copy(smbShare = share.trim()) } },
            placeholder = "music",
            modifier = Modifier.fillMaxWidth(),
        )
        TextRow(
            title = "SMB folder",
            detail = "Optional sub-folder inside the share.",
            value = settings.smbBasePath,
            onValueChange = { path -> onUpdate { it.copy(smbBasePath = path.trim()) } },
            placeholder = "Lossless",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DownloadsGroup(
    settings: Settings,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onPickDownloadsFolder: () -> Unit,
    onPickExportFolder: () -> Unit,
) {
    SettingsGroup(
        title = "Downloads",
        detail = "Kept for offline listening. Quality is chosen per download as well, from the " +
            "Downloads screen.",
    ) {
        EnumRow(
            title = "Quality",
            detail = "Original avoids a re-encode. The lower settings save space and are not " +
                "noticeably worse on a phone speaker, but are on a desktop setup.",
            options = DownloadQuality.entries,
            selected = settings.downloadQuality,
            labelOf = { it.label },
            onSelect = { quality -> onUpdate { it.copy(downloadQuality = quality) } },
        )
        SliderRow(
            title = "Download at once",
            detail = "More parallel downloads finish sooner but are more likely to be rate " +
                "limited by the source.",
            value = settings.downloadConcurrency.toFloat(),
            onValueChange = { count -> onUpdate { it.copy(downloadConcurrency = count.toInt()) } },
            valueLabel = "${settings.downloadConcurrency}",
            range = 1f..8f,
            steps = 6,
        )
        ToggleRow(
            title = "Pause on a metered connection",
            detail = "Detected per connection, not per network name.",
            checked = settings.wifiOnlyDownloads,
            onCheckedChange = { on -> onUpdate { it.copy(wifiOnlyDownloads = on) } },
        )
        ToggleRow(
            title = "Write tags into the files",
            detail = "Fills in title, artist, album and cover art so the files read correctly in " +
                "any other player.",
            checked = settings.tagDownloads,
            onCheckedChange = { on -> onUpdate { it.copy(tagDownloads = on) } },
        )
        ToggleRow(
            title = "Save .lrc alongside",
            detail = "Writes the timed lyrics next to each file, which is what makes every other " +
                "player show them.",
            checked = settings.writeLrcSidecar,
            onCheckedChange = { on -> onUpdate { it.copy(writeLrcSidecar = on) } },
        )
        PathRow(
            title = "Download folder",
            path = settings.effectiveDownloadsDirectory,
            detail = "Where new downloads are written.",
            buttonLabel = "Change",
            onBrowse = onPickDownloadsFolder,
        )
        PathRow(
            title = "Also copy to",
            path = settings.effectiveExportDirectory,
            detail = "An optional second location, for a synced folder or a portable drive.",
            buttonLabel = "Change",
            onBrowse = onPickExportFolder,
        )
    }
}

@Composable
private fun SourcesGroup(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit) {
    val enabledCount = settings.addonSourceOrder.size
    SettingsGroup(
        title = "Sources",
        detail = "Where search looks. Addons are installed and reordered on the Sources screen.",
    ) {
        ActionRow {
            Text(
                text = "$enabledCount source${if (enabledCount == 1) "" else "s"} enabled, " +
                    "${settings.addons.size} addon${if (settings.addons.size == 1) "" else "s"} " +
                    "installed.",
                style = FluentTheme.typography.body,
                color = FluentTheme.colors.text.text.secondary,
            )
        }
        ToggleRow(
            title = "Prefer the higher-quality source",
            detail = "When several sources have the same song, the one with the better stream " +
                "wins even if it is slower to answer.",
            checked = settings.addonPreferHigherQuality,
            onCheckedChange = { on -> onUpdate { it.copy(addonPreferHigherQuality = on) } },
        )
        ToggleRow(
            title = "Allow plain HTTP addons",
            detail = "Off by default. Only turn this on for an addon you wrote yourself, since " +
                "an unencrypted addon can be changed in transit.",
            checked = settings.addonAllowHttp,
            onCheckedChange = { on -> onUpdate { it.copy(addonAllowHttp = on) } },
        )
    }
}

@Composable
private fun ScrobblingGroup(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit) {
    SettingsGroup(
        title = "Scrobbling",
        detail = "What Free Music reports to Last.fm and ListenBrainz, and when. Credentials " +
            "are entered on the Account screen.",
    ) {
        ToggleRow(
            title = "Last.fm",
            checked = settings.lastfmEnabled,
            onCheckedChange = { on -> onUpdate { it.copy(lastfmEnabled = on) } },
        )
        ToggleRow(
            title = "ListenBrainz",
            checked = settings.listenBrainzEnabled,
            onCheckedChange = { on -> onUpdate { it.copy(listenBrainzEnabled = on) } },
        )
        ToggleRow(
            title = "Send \"now playing\"",
            detail = "Tells the service what is playing as soon as it starts, before it counts " +
                "as a scrobble.",
            checked = settings.lastfmNowPlaying,
            onCheckedChange = { on -> onUpdate { it.copy(lastfmNowPlaying = on) } },
            enabled = settings.lastfmEnabled,
        )
        ToggleRow(
            title = "Only the first artist",
            detail = "For a track credited to several artists, reports only the first. Useful " +
                "when a service keeps splitting them into new entries.",
            checked = settings.lastfmPrimaryArtistOnly,
            onCheckedChange = { on -> onUpdate { it.copy(lastfmPrimaryArtistOnly = on) } },
            enabled = settings.lastfmEnabled,
        )
        ToggleRow(
            title = "Count when a track starts",
            detail = "Reports the play as soon as playback begins rather than waiting for the " +
                "threshold below. Faster, but a skipped track still counts.",
            checked = settings.lastfmScrobbleEnabled,
            onCheckedChange = { on -> onUpdate { it.copy(lastfmScrobbleEnabled = on) } },
            enabled = settings.lastfmEnabled,
        )
        SliderRow(
            title = "Minimum length",
            detail = "Anything shorter is never scrobbled, which is what keeps intros and " +
                "interludes out of the history.",
            value = settings.scrobbleMinDurationSeconds.toFloat(),
            onValueChange = { seconds -> onUpdate { it.copy(scrobbleMinDurationSeconds = seconds.toInt()) } },
            valueLabel = "${settings.scrobbleMinDurationSeconds}s",
            range = 0f..120f,
            steps = 23,
        )
        SliderRow(
            title = "Count after",
            detail = "The proportion of a track that has to play before it counts.",
            value = settings.scrobbleDelayPercent.toFloat(),
            onValueChange = { percent -> onUpdate { it.copy(scrobbleDelayPercent = percent.toInt()) } },
            valueLabel = "${settings.scrobbleDelayPercent}%",
            range = 10f..90f,
            steps = 15,
        )
        SliderRow(
            title = "Or after at most",
            detail = "A ceiling, so a very long track still counts before it ends.",
            value = settings.scrobbleDelaySeconds.toFloat(),
            onValueChange = { seconds -> onUpdate { it.copy(scrobbleDelaySeconds = seconds.toInt()) } },
            valueLabel = "${settings.scrobbleDelaySeconds / 60} min",
            range = 30f..600f,
            steps = 18,
        )
        ActionRow {
            Text(
                text = "A track counts once BOTH thresholds are met, so the effective rule is " +
                    "the larger of the two.",
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.text.text.secondary,
            )
        }
    }
}

@Composable
private fun DiscordGroup(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit) {
    SettingsGroup(
        title = "Discord",
        detail = "Shows what is playing on your Discord profile. Needs the Discord desktop app " +
            "to be running.",
    ) {
        ToggleRow(
            title = "Rich Presence",
            checked = settings.discordRpcEnabled,
            onCheckedChange = { on -> onUpdate { it.copy(discordRpcEnabled = on) } },
        )
        ToggleRow(
            title = "Show the audio quality",
            detail = "Adds the stream quality under the track.",
            checked = settings.discordShowAudioQuality,
            onCheckedChange = { on -> onUpdate { it.copy(discordShowAudioQuality = on) } },
            enabled = settings.discordRpcEnabled,
        )
        ToggleRow(
            title = "Clear the status while paused",
            detail = "Removes the activity instead of showing a paused track.",
            checked = settings.discordIdleWhenPaused,
            onCheckedChange = { on -> onUpdate { it.copy(discordIdleWhenPaused = on) } },
            enabled = settings.discordRpcEnabled,
        )
        EnumRow(
            title = "Activity type",
            detail = "\"Listening\" is the correct one for a player; the others make Discord say " +
                "\"Playing\" or \"Watching\".",
            options = DiscordActivityType.entries,
            selected = settings.discordActivityType,
            labelOf = { it.label },
            onSelect = { kind -> onUpdate { it.copy(discordActivityType = kind) } },
            enabled = settings.discordRpcEnabled,
        )
        ToggleRow(
            title = "Advanced fields",
            detail = "Replaces the automatic line with your own text, and enables the buttons.",
            checked = settings.discordAdvancedMode,
            onCheckedChange = { on -> onUpdate { it.copy(discordAdvancedMode = on) } },
            enabled = settings.discordRpcEnabled,
        )
        TextRow(
            title = "Status text",
            detail = "Overrides the automatic \"by artist\" line.",
            value = settings.discordActivityName,
            onValueChange = { name -> onUpdate { it.copy(discordActivityName = name) } },
            placeholder = "by artist",
            enabled = settings.discordRpcEnabled && settings.discordAdvancedMode,
            modifier = Modifier.fillMaxWidth(),
        )
        TextRow(
            title = "Button one label",
            detail = "Leave blank to hide the button.",
            value = settings.discordButton1Text,
            onValueChange = { text -> onUpdate { it.copy(discordButton1Text = text) } },
            placeholder = "Listen on YouTube Music",
            enabled = settings.discordRpcEnabled && settings.discordAdvancedMode,
            modifier = Modifier.fillMaxWidth(),
        )
        ToggleRow(
            title = "Show button one",
            checked = settings.discordButton1Visible,
            onCheckedChange = { on -> onUpdate { it.copy(discordButton1Visible = on) } },
            enabled = settings.discordRpcEnabled && settings.discordAdvancedMode,
        )
        TextRow(
            title = "Button two label",
            value = settings.discordButton2Text,
            onValueChange = { text -> onUpdate { it.copy(discordButton2Text = text) } },
            enabled = settings.discordRpcEnabled && settings.discordAdvancedMode,
            modifier = Modifier.fillMaxWidth(),
        )
        ToggleRow(
            title = "Show button two",
            checked = settings.discordButton2Visible,
            onCheckedChange = { on -> onUpdate { it.copy(discordButton2Visible = on) } },
            enabled = settings.discordRpcEnabled && settings.discordAdvancedMode,
        )
    }
}

@Composable
private fun ListenTogetherGroup(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit) {
    SettingsGroup(
        title = "Listen Together",
        detail = "A shared queue that keeps several machines on the same second of the same " +
            "track.",
    ) {
        TextRow(
            title = "Server address",
            detail = "Free Music speaks a documented protocol but ships no server, so this has " +
                "to point at one you host. Rooms are joined from the Listen Together screen.",
            value = settings.partyServerUrl,
            onValueChange = { url -> onUpdate { it.copy(partyServerUrl = url.trim()) } },
            placeholder = "wss://example.com/party",
            modifier = Modifier.fillMaxWidth(),
        )
        TextRow(
            title = "Display name",
            detail = "How the other listeners see you.",
            value = settings.partyDisplayName,
            onValueChange = { name -> onUpdate { it.copy(partyDisplayName = name) } },
            placeholder = "Himanshu",
            modifier = Modifier.fillMaxWidth(),
        )
        TextRow(
            title = "Last invite code",
            value = settings.partyInviteCode,
            onValueChange = { code -> onUpdate { it.copy(partyInviteCode = code.trim()) } },
            modifier = Modifier.fillMaxWidth(),
        )
        ToggleRow(
            title = "Let guests change the music",
            detail = "Off makes the room follow-only unless you are the host.",
            checked = settings.partyAllowGuestControl,
            onCheckedChange = { on -> onUpdate { it.copy(partyAllowGuestControl = on) } },
        )
    }
}

@Composable
private fun StorageGroup(
    settings: Settings,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onClearCache: () -> Unit,
    cacheBytes: Long,
) {
    SettingsGroup(
        title = "Storage",
        detail = "What Free Music keeps on disk and for how long.",
    ) {
        SliderRow(
            title = "Audio cache limit",
            detail = "Streamed audio kept so a replay does not re-download. Streaming only - " +
                "downloads are not cached, they are files.",
            value = (settings.audioCacheLimitBytes / (512L * 1024 * 1024)).toFloat(),
            onValueChange = { blocks ->
                onUpdate { it.copy(audioCacheLimitBytes = (blocks * 512L * 1024 * 1024).toLong()) }
            },
            valueLabel = if (settings.audioCacheLimitBytes < 1024L * 1024 * 1024) {
                "${settings.audioCacheLimitBytes / (1024 * 1024)} MB"
            } else {
                "%.1f GB".format(settings.audioCacheLimitBytes / (1024.0 * 1024 * 1024))
            },
            range = 1f..16f,
            steps = 14,
        )
        SliderRow(
            title = "Keep this many plays",
            detail = "Older entries are dropped once the history reaches the limit.",
            value = settings.historyLimit.toFloat(),
            onValueChange = { count -> onUpdate { it.copy(historyLimit = count.toInt()) } },
            valueLabel = "${settings.historyLimit}",
            range = 100f..5000f,
            steps = 48,
        )
        ToggleRow(
            title = "Clear the cache on exit",
            detail = "Reclaims the space but makes the next few plays slower.",
            checked = settings.clearCacheOnExit,
            onCheckedChange = { on -> onUpdate { it.copy(clearCacheOnExit = on) } },
        )
        ToggleRow(
            title = "High performance mode",
            detail = "Raises the decode and analysis thread counts. Worth trying if the interface " +
                "stutters while audio plays.",
            checked = settings.highPerformanceMode,
            onCheckedChange = { on -> onUpdate { it.copy(highPerformanceMode = on) } },
        )
        PathRow(
            title = "Cache",
            path = "${formatCacheSize(cacheBytes)} in use",
            detail = AppPaths.cacheDir,
            buttonLabel = "Clear",
            onBrowse = onClearCache,
        )
        ActionRow {
            Text(
                text = "${settings.playlists.size} playlists, " +
                    "${settings.searchHistory.size} recent searches, " +
                    "${settings.pinnedPlaylists.size} pinned.",
                style = FluentTheme.typography.body,
                color = FluentTheme.colors.text.text.secondary,
            )
        }
    }
}

@Composable
private fun AboutGroup(onResetSettings: () -> Unit) {
    SettingsGroup(
        title = "About",
        detail = "Free Music for Windows - GPL-3.0-or-later. The Android app and this port are " +
            "both by Himanshu Nayak.",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                FluentGlyphs.Info,
                contentDescription = null,
                tint = FluentTheme.colors.text.text.secondary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text("Free Music for Windows", style = FluentTheme.typography.bodyStrong)
                Text(
                    text = "Version $APP_VERSION - Fluent design",
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                )
                Text(
                    text = "Built by Himanshu Nayak",
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                )
            }
        }
        PathRow(
            title = "Settings file",
            path = AppPaths.settingsFile,
            detail = "Editable by hand. A value that fails to parse falls back to its default " +
                "rather than losing the rest.",
            buttonLabel = "Reveal",
            onBrowse = { openInExplorer(AppPaths.settingsFile) },
        )
        PathRow(
            title = "Logs",
            path = AppPaths.logDir,
            buttonLabel = "Reveal",
            onBrowse = { openInExplorer(AppPaths.logDir) },
        )
        PathRow(
            title = "Data folder",
            path = AppPaths.rootDir,
            detail = "Downloads, history, playlists and the download queue all live here.",
            buttonLabel = "Reveal",
            onBrowse = { openInExplorer(AppPaths.rootDir) },
        )
        ActionRow {
            SubtleButton(onClick = onResetSettings) {
                Icon(FluentGlyphs.Retry, contentDescription = null)
                Text("Reset every setting", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// ## SECTION: Helpers
// ---------------------------------------------------------------------------

/** `Left 40%`, `Right 40%`, or `Centre`. */
private fun balanceLabel(balance: Float): String = when {
    balance <= -0.02f -> "Left ${(-balance * 100).toInt()}%"
    balance >= 0.02f -> "Right ${(balance * 100).toInt()}%"
    else -> "Centre"
}

/** `340 MB`, `2.1 GB`. */
internal fun formatCacheSize(bytes: Long): String {
    if (bytes <= 0L) return "Empty"
    val mb = bytes / (1024.0 * 1024)
    return if (mb < 1024) "%.0f MB".format(mb) else "%.1f GB".format(mb / 1024)
}

/** Opens a folder in Explorer, selecting the file when given one. */
private fun openInExplorer(path: String) {
    runCatching {
        val file = java.io.File(path)
        if (file.isFile) {
            ProcessBuilder("explorer.exe", "/select,${file.absolutePath}").start()
        } else {
            if (!file.exists()) file.mkdirs()
            ProcessBuilder("explorer.exe", file.absolutePath).start()
        }
    }.onFailure { Log.w("could not open $path: ${it.message}", tag = "settings") }
}

/** The app version, resolved from the JAR manifest with a sensible fallback. */
val APP_VERSION: String = runCatching {
    val pkg = Log::class.java.`package`
    val impl = pkg?.implementationVersion
    if (impl.isNullOrBlank()) "1.1.0" else impl
}.getOrDefault("1.1.0")
