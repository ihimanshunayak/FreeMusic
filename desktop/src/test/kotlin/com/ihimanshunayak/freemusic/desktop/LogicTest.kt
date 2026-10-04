// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - pure-logic tests.
//
// NAME
//     LogicTest.kt - the parts of the port that can be checked without a window.
//
// DESCRIPTION
//     Every function tested here is a pure transformation that the UI depends on
//     for something the user can see going wrong: a wrong clock in a listening
//     room, an EQ curve that clips, a byte count that reads as nonsense. None of
//     them need a device, a network or a rendering surface, so none of them have
//     an excuse to be untested.
//
//     The tests are deliberately about *behaviour the user would notice* rather
//     than about coverage. A test that asserts an internal constant never catches
//     a regression; a test that asserts "a three-hour track reads 3:00:00" does.
//
// RESPONSIBILITIES
//     - Pin the equalizer curve, tone-pad and preamp maths.
//     - Pin the listening-room clock derivation and endpoint normalisation.
//     - Pin the UI formatting helpers that appear in more than one screen.
//
// DEPENDENCIES
//     - kotlin.test only. No Compose, no coroutines, no filesystem.
//
// INTEGRATION NOTES
//     - The party tests use [PartyState] directly rather than a live socket. The
//       clock maths is the part that breaks, and it is separable from the
//       transport by design - which is what makes this possible.

package com.ihimanshunayak.freemusic.desktop

import com.ihimanshunayak.freemusic.desktop.audio.dsp.EqualizerPreset
import com.ihimanshunayak.freemusic.desktop.audio.dsp.TonePadSetting
import com.ihimanshunayak.freemusic.desktop.audio.dsp.curveFor
import com.ihimanshunayak.freemusic.desktop.audio.dsp.padFor
import com.ihimanshunayak.freemusic.desktop.audio.dsp.preampFor
import com.ihimanshunayak.freemusic.desktop.data.party.PartyClient
import com.ihimanshunayak.freemusic.desktop.data.party.PartyPlayback
import com.ihimanshunayak.freemusic.desktop.data.party.PartyState
import com.ihimanshunayak.freemusic.desktop.ui.component.formatMillis
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogicTest {

    // ---- equalizer --------------------------------------------------------

    @Test
    fun `every menu preset carries a band per ISO centre`() {
        // A preset with the wrong band count would silently shift the curve.
        val expected = 10
        EqualizerPreset.menuOrder.forEach { preset ->
            assertEquals(
                expected,
                preset.bandsDb.size,
                "${preset.name} has the wrong number of bands",
            )
        }
    }

    @Test
    fun `flat is the identity curve and matching finds it`() {
        val flat = EqualizerPreset.FLAT.bandsDb
        assertTrue(flat.all { it == 0f }, "FLAT must be all zeroes")
        assertEquals(EqualizerPreset.FLAT, EqualizerPreset.matching(flat.toList()))
    }

    @Test
    fun `matching tolerates a rounding error but not a different curve`() {
        val bass = EqualizerPreset.BASS_BOOST.bandsDb
        // Matching is exact to within half a decibel by design - the same
        // tolerance the Android build uses - so a curve nudged by less than that
        // is still the preset. Anything a real slider drag produces is larger,
        // which is correct: dragging a preset's band *is* leaving the preset.
        val nudged = bass.map { it + 0.04f }
        assertEquals(
            EqualizerPreset.BASS_BOOST,
            EqualizerPreset.matching(nudged),
            "drift inside the tolerance is still the preset",
        )
        val nudgedOut = bass.map { it + 0.4f }
        assertEquals(
            EqualizerPreset.CUSTOM,
            EqualizerPreset.matching(nudgedOut),
            "half a decibel is past the tolerance and is the user's own curve",
        )

        val wrong = bass.mapIndexed { index, db -> if (index == 0) db + 9f else db }
        assertEquals(
            EqualizerPreset.CUSTOM,
            EqualizerPreset.matching(wrong),
            "a curve nine decibels off is not the preset",
        )
    }

    @Test
    fun `matching returns custom for a band count that matches no preset`() {
        assertEquals(EqualizerPreset.CUSTOM, EqualizerPreset.matching(listOf(0f, 0f, 0f)))
        assertEquals(EqualizerPreset.CUSTOM, EqualizerPreset.matching(emptyList()))
    }

    @Test
    fun `the tone pad is a round trip through the curve`() {
        // A pad position has to survive being turned into bands and back, or every
        // reopen of the equalizer would move the user's dot. This holds exactly
        // because `curveFor` is a combination of the same two shapes `padFor` fits
        // against - if either one is edited alone, this test is what notices.
        val corners = listOf(
            TonePadSetting.Neutral,
            TonePadSetting(0f, 1f),
            TonePadSetting(1f, 0f),
            TonePadSetting(0.5f, 0.5f),
            TonePadSetting(-1f, -1f),
            TonePadSetting(-0.35f, 0.8f),
        )
        corners.forEach { pad ->
            val recovered = padFor(curveFor(pad))
            assertTrue(
                abs(recovered.x - pad.x) < 0.02f && abs(recovered.y - pad.y) < 0.02f,
                "pad $pad came back as $recovered",
            )
        }
    }

    @Test
    fun `the pad recovers a bass lift as body rather than as tilt`() {
        // The old inverse read only the bass and treble averages, so a curve that
        // lifted the bass without touching the treble looked like a downward tilt
        // and the pad dot flew into the corner whenever a bass-heavy preset was
        // chosen. Both coordinates have to come from the same fit.
        val recovered = padFor(EqualizerPreset.BASS_BOOST.bandsDb.toList())
        assertTrue(
            recovered.y > 0f,
            "a bass boost must read as a full pad, not as a tilt (got $recovered)",
        )
    }

    @Test
    fun `the neutral pad produces a flat curve`() {
        curveFor(TonePadSetting.Neutral).forEachIndexed { index, db ->
            assertTrue(abs(db) < 0.001f, "band $index should be flat but was $db")
        }
    }

    @Test
    fun `preamp is never positive so a boosted curve cannot clip`() {
        // The whole point of the preamp is to buy back the headroom a boost spends.
        val boosted = EqualizerPreset.BASS_BOOST.bandsDb.toList()
        val preamp = preampFor(boosted)
        assertTrue(preamp <= 0f, "preamp must attenuate, was $preamp")

        // A fully cut curve needs no headroom at all.
        val cut = List(10) { -12f }
        assertEquals(0f, preampFor(cut), "a cut-only curve needs no preamp")
    }

    @Test
    fun `a bigger boost asks for more attenuation`() {
        val gentle = preampFor(List(10) { 3f })
        val harsh = preampFor(List(10) { 9f })
        assertTrue(
            harsh < gentle,
            "9 dB of boost must attenuate more than 3 dB (got $harsh vs $gentle)",
        )
    }

    @Test
    fun `the preamp takes back exactly the boost the curve spends`() {
        // With no margin requested the preamp is the negative of the largest
        // boost, so a clipped signal becomes an unclipped one and nothing else
        // changes. It is *not* a switch: a curve that boosts 5 dB needs 5 dB back
        // whether or not the user asked for extra safety margin.
        val boosted = EqualizerPreset.ROCK.bandsDb.toList()
        val peak = boosted.max()
        assertEquals(-peak, preampFor(boosted, headroomDb = 0f))

        // The margin is subtracted on top of that, so the default is stricter.
        assertEquals(-(peak + 1.5f), preampFor(boosted))

        // A curve that only cuts has nothing to take back - adding gain to
        // compensate for a cut would be guessing at the mix's level rather than
        // correcting for it.
        assertEquals(0f, preampFor(listOf(-3f, -6f, -9f, -4f, -2f, -1f, 0f, 0f, 0f, 0f)))
        assertEquals(0f, preampFor(emptyList()))
    }

    // ---- listening room clock ---------------------------------------------

    @Test
    fun `a paused host reports the position it sent`() {
        val state = partyState(playing = false, positionMsAtHost = 42_000L)
        assertEquals(42_000L, state.positionNowMs(localNowMs = 999_999L))
    }

    @Test
    fun `a playing host advances on the local clock`() {
        // The host started at T; we ask 5 seconds later on our own clock.
        val state = partyState(
            playing = true,
            startedAtHostMs = 100_000L,
            clockOffsetMs = 0L,
        )
        assertEquals(5_000L, state.positionNowMs(localNowMs = 105_000L))
        assertEquals(65_000L, state.positionNowMs(localNowMs = 165_000L))
    }

    @Test
    fun `a clock offset is subtracted rather than added`() {
        // The offset is the host's clock minus ours, so a *positive* offset means
        // the host is ahead. Our clock is three seconds behind, so the host's start
        // instant lands three seconds earlier on ours and the elapsed time is
        // larger - eight seconds rather than the five a naive reading would give.
        // Getting the sign backwards would seek to the wrong place in every room.
        val behind = partyState(
            playing = true,
            startedAtHostMs = 100_000L,
            clockOffsetMs = 3_000L,
        )
        assertEquals(8_000L, behind.positionNowMs(localNowMs = 105_000L))

        // And the mirror image: a host three seconds behind us yields less elapsed
        // time, not more.
        val ahead = partyState(
            playing = true,
            startedAtHostMs = 100_000L,
            clockOffsetMs = -3_000L,
        )
        assertEquals(2_000L, ahead.positionNowMs(localNowMs = 105_000L))
    }

    @Test
    fun `the derived position never goes negative`() {
        // A message can arrive before the instant it names if the clocks are
        // still converging; a negative seek would throw.
        val state = partyState(
            playing = true,
            startedAtHostMs = 500_000L,
            clockOffsetMs = 0L,
        )
        assertEquals(0L, state.positionNowMs(localNowMs = 100_000L))
    }

    @Test
    fun `an empty room has no position`() {
        assertEquals(0L, PartyState().positionNowMs(localNowMs = 1_000_000L))
    }

    @Test
    fun `only a guest following a host has remote playback`() {
        val following = partyState(playing = true, startedAtHostMs = 0L)
            .copy(connected = true, isHost = false)
        assertTrue(following.hasRemotePlayback)

        assertFalse(following.copy(isHost = true).hasRemotePlayback, "the host is not following")
        assertFalse(following.copy(connected = false).hasRemotePlayback, "a dead socket is not synced")
        assertFalse(
            following.copy(playback = PartyPlayback()).hasRemotePlayback,
            "nothing playing means nothing to follow",
        )
    }

    @Test
    fun `the clock is only trusted inside a sane round trip`() {
        val base = partyState(playing = true)
        assertTrue(base.copy(roundTripMs = 90L).clockIsCalibrated)
        assertFalse(base.copy(roundTripMs = 0L).clockIsCalibrated, "an unmeasured clock is not calibrated")
        assertFalse(
            base.copy(roundTripMs = 5_000L).clockIsCalibrated,
            "a five-second round trip is too noisy to correct against",
        )
    }

    // ---- endpoint normalisation -------------------------------------------

    @Test
    fun `an http endpoint becomes a websocket one`() {
        assertEquals("ws://localhost:8080/room/ABCD", party.normaliseEndpoint("http://localhost:8080", "ABCD"))
        assertEquals(
            "wss://music.example.com/room/ABCD",
            party.normaliseEndpoint("https://music.example.com", "ABCD"),
        )
    }

    @Test
    fun `a bare host guesses the scheme from the name`() {
        // Getting this wrong is a TLS handshake failure with no useful message,
        // which is why the loopback distinction is spelled out rather than implied.
        assertEquals("ws://localhost:8080/room/X1", party.normaliseEndpoint("localhost:8080", "X1"))
        assertEquals("ws://127.0.0.1:9000/room/X1", party.normaliseEndpoint("127.0.0.1:9000", "X1"))
        assertEquals("ws://[::1]:9000/room/X1", party.normaliseEndpoint("[::1]:9000", "X1"))
        assertEquals("wss://party.example.com/room/X1", party.normaliseEndpoint("party.example.com", "X1"))
    }

    @Test
    fun `an explicit scheme is never overridden`() {
        assertEquals("wss://localhost/room/A", party.normaliseEndpoint("wss://localhost", "A"))
        assertEquals("ws://party.example.com/room/A", party.normaliseEndpoint("ws://party.example.com", "A"))
    }

    @Test
    fun `trailing slashes and surrounding spaces are absorbed`() {
        assertEquals("ws://localhost:8080/room/A", party.normaliseEndpoint("  http://localhost:8080/  ", " A "))
        assertEquals("ws://localhost/room/A", party.normaliseEndpoint("http://localhost///", "A"))
    }

    @Test
    fun `a missing host or room code yields no endpoint`() {
        // Returning "" is how the caller knows to refuse the connection rather
        // than dial a nonsense URL.
        assertEquals("", party.normaliseEndpoint("", "A"))
        assertEquals("", party.normaliseEndpoint("   ", "A"))
        assertEquals("", party.normaliseEndpoint("localhost", ""))
        assertEquals("", party.normaliseEndpoint("localhost", "   "))
    }

    // ---- formatting -------------------------------------------------------

    @Test
    fun `a position label never shows a negative or a zero-length track`() {
        assertEquals("0:00", formatMillis(0L))
        assertEquals("0:00", formatMillis(-1L))
        assertEquals("0:00", formatMillis(-99_000L))
    }

    @Test
    fun `a position label gains an hours field past sixty minutes`() {
        assertEquals("0:07", formatMillis(7_000L))
        assertEquals("3:07", formatMillis(187_000L))
        assertEquals("59:59", formatMillis(3_599_000L))
        assertEquals("1:00:00", formatMillis(3_600_000L))
        assertEquals("1:02:44", formatMillis(3_764_000L))
        assertEquals("3:00:00", formatMillis(10_800_000L))
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * A client only for its endpoint builder.
     *
     * `normaliseEndpoint` is an instance method because it sits next to the socket
     * it feeds, but the behaviour is a pure function of its arguments. Constructing
     * the client without connecting leaves the socket untouched.
     */
    private val party = PartyClient()

    private fun partyState(
        playing: Boolean,
        positionMsAtHost: Long = 0L,
        startedAtHostMs: Long = 0L,
        clockOffsetMs: Long = 0L,
    ): PartyState = PartyState(
        playback = PartyPlayback(
            videoId = "vid",
            playing = playing,
            positionMsAtHost = positionMsAtHost,
            startedAtHostMs = startedAtHostMs,
        ),
        clockOffsetMs = clockOffsetMs,
    )
}
