// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - user settings.
//
// Stored as JSON next to the logs, not in the registry, so a user can inspect,
// back up or delete their configuration without a tool.

package com.ihimanshunayak.freemusic.desktop.data

import com.ihimanshunayak.freemusic.desktop.model.RepeatMode
import com.ihimanshunayak.freemusic.desktop.util.AppPaths
import com.ihimanshunayak.freemusic.desktop.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class Settings(
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    val volume: Float = 0.8f,
    val muted: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffle: Boolean = false,
    val crossfadeSeconds: Int = 0,
    val playbackSpeed: Float = 1.0f,
    val normalizeVolume: Boolean = false,
    val lastVolumeBeforeMute: Float = 0.8f,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val language: String = "en",
    val region: String = "US",
    val showDiagnostics: Boolean = false,
    val downloadsDirectory: String = "",
) {
    /** Where downloads actually land, falling back to the app's own folder. */
    val effectiveDownloadsDirectory: String
        get() = downloadsDirectory.ifBlank { AppPaths.downloadsDir }
}

@Serializable
enum class AudioQuality(val label: String, val approxKbps: Int) {
    LOW("Low (64 kbps)", 64),
    MEDIUM("Medium (128 kbps)", 128),
    HIGH("High (256 kbps)", 256),
    HIGHEST("Highest available", 9999),
}

@Serializable
enum class ThemePreference(val label: String) {
    SYSTEM("System"), LIGHT("Light"), DARK("Dark")
}

/**
 * Settings holder with a JSON-backed store.
 *
 * Reads are channel-agnostic: [flow] is what the UI collects, and every mutation
 * both updates the flow and schedules a save, so the window reflects a change
 * immediately and the file catches up without the caller thinking about it.
 */
class SettingsStore(private val file: File = File(AppPaths.settingsFile)) {

    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<Settings> = _flow.asStateFlow()

    val current: Settings get() = _flow.value

    @Synchronized
    fun update(transform: (Settings) -> Settings): Settings {
        val next = transform(_flow.value)
        _flow.value = next
        save(next)
        return next
    }

    private fun load(): Settings = runCatching {
        if (!file.exists()) return Settings()
        val text = file.readText()
        if (text.isBlank()) return Settings()
        Http.json.decodeFromString(Settings.serializer(), text)
    }.onFailure {
        // A corrupt file is not worth losing the app over; defaults are safe and
        // the original is left untouched so it can be inspected.
        Log.w("could not read settings (${it.message}); using defaults", tag = "settings")
    }.getOrDefault(Settings())

    @Synchronized
    private fun save(settings: Settings) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(Http.json.encodeToString(Settings.serializer(), settings))
        }.onFailure { Log.w("could not save settings: ${it.message}", tag = "settings") }
    }
}
