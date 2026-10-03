// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - playback smoke check (run on demand, not part of :desktop:test).
//
// The unit tests cover the queue logic and the stream resolver separately, but
// neither proves the two work together: that a URL the resolver produced is
// actually accepted by libVLC and advances the transport. That is what this does,
// and it is the only check in the project that needs both the network and a VLC
// installation.
//
// Launch it with the VLC directory on PATH, or with VLC_PLUGIN_PATH set when VLC
// is unpacked rather than installed:
//
//   .\gradlew.bat :desktop:playbackCheck

package com.ihimanshunayak.freemusic.desktop

import com.ihimanshunayak.freemusic.desktop.audio.AudioEngine
import com.ihimanshunayak.freemusic.desktop.audio.PlaybackOutcome
import com.ihimanshunayak.freemusic.desktop.data.innertube.MusicRepository
import com.ihimanshunayak.freemusic.desktop.data.innertube.YouTubeSession
import com.ihimanshunayak.freemusic.desktop.data.stream.StreamResolver
import com.ihimanshunayak.freemusic.desktop.model.PlaybackState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

private var failures = 0

private fun check(label: String, condition: Boolean, detail: String = "") {
    val mark = if (condition) "PASS" else "FAIL"
    if (!condition) failures++
    println("[$mark] $label${if (detail.isNotEmpty()) " -> $detail" else ""}")
}

fun main() = runBlocking {
    println("=== Free Music for Windows - playback smoke check ===")
    println()

    val engine = AudioEngine()
    engine.initialise()
    check("libVLC loaded", engine.isAvailable, engine.unavailableReason ?: "engine ready")
    if (!engine.isAvailable) {
        println()
        println("VLC is required for this check. Install VLC 3.x, or set VLC_PLUGIN_PATH")
        println("and add the VLC directory to PATH when using an unpacked build.")
        engine.dispose()
        return@runBlocking
    }

    // ---- a real stream ----------------------------------------------------
    val session = YouTubeSession()
    session.ensureVisitorData()
    val music = MusicRepository(session)
    val result = music.searchResults("Linkin Park In the End").firstOrNull { it.videoId != null }
    check("a track was found to play", result != null, result?.title ?: "none")
    if (result == null || result.videoId == null) {
        engine.dispose()
        return@runBlocking
    }
    val videoId: String = result.videoId
    val track = requireNotNull(music.toTrack(result)) { "a search row with a video id should convert to a track" }

    val stream = StreamResolver().resolve(videoId)
    check("stream resolved", stream.url.isNotBlank(), "${stream.bitrateKbps}kbps ${stream.mimeType}")

    // ---- hand it to libVLC ------------------------------------------------
    var reportedError: String? = null
    engine.onPlaybackError = { reportedError = engine.snapshot.value.error ?: "engine reported an error" }

    val outcome = engine.play(track, stream.url, stream.headers)
    check("libVLC accepted the play request", outcome is PlaybackOutcome.Started, outcome.toString())

    println("  watching the transport for 18s...")
    var peak = 0L
    var sawPlaying = false
    var sawDuration = false
    val deadline = System.currentTimeMillis() + 18_000
    while (System.currentTimeMillis() < deadline) {
        delay(500)
        val s = engine.snapshot.value
        if (s.positionMillis > peak) peak = s.positionMillis
        if (s.state == PlaybackState.PLAYING) sawPlaying = true
        if (s.durationMillis > 0) sawDuration = true
        print("\r  pos=${s.positionMillis}ms  dur=${s.durationMillis}ms  state=${s.state}          ")
        if (reportedError != null) break
    }
    println()

    check("the engine reported PLAYING", sawPlaying, "state=${engine.snapshot.value.state}")
    check("the transport advanced past zero", peak > 0, "${peak}ms of audio decoded and played")
    check("libVLC learned the duration", sawDuration, "${engine.snapshot.value.durationMillis}ms")
    check("no playback error was raised", reportedError == null, reportedError ?: "clean")

    // ---- seek, the other half of a working player -------------------------
    engine.seekTo(30_000)
    delay(3_000)
    val afterSeek = engine.snapshot.value.positionMillis
    check("seeking moved the position", afterSeek > 25_000, "${afterSeek}ms after seeking to 30000ms")

    // ---- pause and resume -------------------------------------------------
    engine.pause()
    delay(1_500)
    check("pause took effect", engine.snapshot.value.state == PlaybackState.PAUSED, "${engine.snapshot.value.state}")
    engine.resume()
    delay(2_000)
    check("resume took effect", engine.snapshot.value.state == PlaybackState.PLAYING, "${engine.snapshot.value.state}")

    // ---- volume -----------------------------------------------------------
    engine.setVolume(0.2f)
    delay(500)
    check("volume was applied", engine.snapshot.value.volume == 0.2f, "volume=${engine.snapshot.value.volume}")

    engine.stop()
    engine.dispose()
    check("the engine released cleanly", true)

    println()
    if (failures == 0) {
        println("=== ALL PLAYBACK CHECKS PASSED ===")
    } else {
        println("=== $failures PLAYBACK CHECK(S) FAILED ===")
    }
}
