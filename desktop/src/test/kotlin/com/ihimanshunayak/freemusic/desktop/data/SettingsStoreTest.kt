// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - settings store tests.
//
// The store writes on every change, so the two failure modes worth testing are
// a round-trip that loses a field and a corrupt file that takes the app down on
// launch. Both are covered here against a temporary file rather than the real
// %LOCALAPPDATA% path.

package com.ihimanshunayak.freemusic.desktop.data

import com.ihimanshunayak.freemusic.desktop.model.RepeatMode
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsStoreTest {

    private val tempDir: File = Files.createTempDirectory("freemusic-settings").toFile()

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `defaults are returned when no file exists yet`() {
        val store = SettingsStore(File(tempDir, "settings.json"))

        assertEquals(AudioQuality.HIGH, store.current.audioQuality)
        assertEquals(ThemePreference.SYSTEM, store.current.theme)
        assertEquals(RepeatMode.OFF, store.current.repeatMode)
    }

    @Test
    fun `a change is written and read back by a new store`() {
        val file = File(tempDir, "settings.json")
        val store = SettingsStore(file)

        store.update { it.copy(audioQuality = AudioQuality.HIGHEST, theme = ThemePreference.DARK, volume = 0.42f) }

        val reloaded = SettingsStore(file)
        assertEquals(AudioQuality.HIGHEST, reloaded.current.audioQuality)
        assertEquals(ThemePreference.DARK, reloaded.current.theme)
        assertEquals(0.42f, reloaded.current.volume)
    }

    @Test
    fun `every field survives a round trip`() {
        val file = File(tempDir, "settings.json")
        val store = SettingsStore(file)

        val changed = store.update {
            it.copy(
                audioQuality = AudioQuality.MEDIUM,
                volume = 0.31f,
                muted = true,
                repeatMode = RepeatMode.ONE,
                shuffle = true,
                crossfadeSeconds = 4,
                playbackSpeed = 1.25f,
                normalizeVolume = true,
                lastVolumeBeforeMute = 0.31f,
                theme = ThemePreference.LIGHT,
                language = "hi",
                region = "IN",
                showDiagnostics = true,
                downloadsDirectory = "D:/Music",
            )
        }

        val reloaded = SettingsStore(file).current
        assertEquals(changed, reloaded, "a field was lost between save and load")
    }

    @Test
    fun `a corrupt file falls back to defaults instead of throwing`() {
        val file = File(tempDir, "settings.json")
        file.writeText("{ this is not json")

        val store = SettingsStore(file)

        assertEquals(AudioQuality.HIGH, store.current.audioQuality)
        // The damaged file is left alone so it can be inspected, which is the
        // behaviour the log message promises.
        assertTrue(file.exists(), "the corrupt file should not be deleted")
    }

    @Test
    fun `an empty file falls back to defaults`() {
        val file = File(tempDir, "settings.json")
        file.writeText("")

        assertEquals(AudioQuality.HIGH, SettingsStore(file).current.audioQuality)
    }

    @Test
    fun `a missing parent directory is created on save`() {
        val nested = File(File(tempDir, "a/b/c"), "settings.json")
        val store = SettingsStore(nested)

        store.update { it.copy(volume = 0.5f) }

        assertTrue(nested.isFile, "the store should have created ${nested.parent}")
    }

    @Test
    fun `the flow reflects a change immediately`() {
        val store = SettingsStore(File(tempDir, "settings.json"))

        store.update { it.copy(shuffle = true) }

        assertTrue(store.flow.value.shuffle)
        assertTrue(store.current.shuffle)
    }

    @Test
    fun `the downloads directory falls back to the app folder when blank`() {
        val settings = Settings()

        assertEquals("", settings.downloadsDirectory)
        assertTrue(
            settings.effectiveDownloadsDirectory.isNotBlank(),
            "a blank downloads directory must resolve to a real path",
        )
    }

    @Test
    fun `a chosen downloads directory is used as-is`() {
        val settings = Settings(downloadsDirectory = "D:/Music")

        assertEquals("D:/Music", settings.effectiveDownloadsDirectory)
    }
}
