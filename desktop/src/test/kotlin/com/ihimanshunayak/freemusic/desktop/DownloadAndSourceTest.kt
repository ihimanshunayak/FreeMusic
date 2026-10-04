// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - download and source tests.
//
// NAME
//     DownloadAndSourceTest.kt - the file-naming and addon-contract logic.
//
// DESCRIPTION
//     Two areas that fail in ways a user cannot diagnose on their own.
//
//     Filenames: Windows silently strips a trailing dot or space from a file it
//     creates, then cannot find that file by the name it was asked to create. The
//     app would report a successful download and leave nothing on disk. The same
//     goes for the reserved device names - `NUL.mp3` is a write into the void.
//
//     Addons: a script that does not define the resolver function is accepted
//     happily by the JavaScript parser and then throws when a song is played. The
//     validation path exists precisely to catch that while the user is still
//     looking at the editor.
//
// RESPONSIBILITIES
//     - Pin the filename sanitiser and the temporary-file convention.
//     - Pin byte formatting, which appears in the downloads list.
//     - Pin addon validation and id canonicalisation.
//
// DEPENDENCIES
//     - kotlin.test only.
//
// INTEGRATION NOTES
//     - The Rhino-backed tests construct a fresh context per call, which is what
//       the production path does too, so they exercise the same enter/exit pairing
//       rather than a shared interpreter.

package com.ihimanshunayak.freemusic.desktop

import com.ihimanshunayak.freemusic.desktop.data.Settings
import com.ihimanshunayak.freemusic.desktop.data.download.formatBytes
import com.ihimanshunayak.freemusic.desktop.data.download.partFileFor
import com.ihimanshunayak.freemusic.desktop.data.download.sanitise
import com.ihimanshunayak.freemusic.desktop.data.source.SourceRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadAndSourceTest {

    // ---- filenames --------------------------------------------------------

    @Test
    fun `every character Windows forbids is replaced`() {
        val raw = "AC/DC - Back: in \"Black\" <live> | 1979?"
        val safe = sanitise(raw)
        listOf('<', '>', ':', '"', '/', '\\', '|', '?', '*').forEach { illegal ->
            assertFalse(safe.contains(illegal), "'$illegal' survived in '$safe'")
        }
    }

    @Test
    fun `a trailing dot or space is stripped`() {
        // Windows drops these when the file is created, so keeping them would mean
        // the app looks for a name the filesystem never made.
        assertEquals("Track", sanitise("Track."))
        assertEquals("Track", sanitise("Track..."))
        assertEquals("Track", sanitise("Track   "))
        assertEquals("Track", sanitise("  Track . . "))
    }

    @Test
    fun `control characters are replaced rather than kept`() {
        val safe = sanitise("Bad\u0000Name\u001F.mp3")
        assertTrue(safe.none { it.code < 32 }, "a control character survived in '$safe'")
        assertTrue(safe.contains("Bad"), "the readable part was lost")
    }

    @Test
    fun `a very long name is truncated below the path limit`() {
        val long = "x".repeat(400)
        assertEquals(120, sanitise(long).length)
    }

    @Test
    fun `an ordinary name is left alone`() {
        assertEquals("Arijit Singh - Kesariya", sanitise("Arijit Singh - Kesariya"))
        assertEquals("Track (Official Audio) [HQ]", sanitise("Track (Official Audio) [HQ]"))
    }

    @Test
    fun `the part file sits beside its target with a part suffix`() {
        val target = File("C:/Music/Album/track.mp3")
        val part = partFileFor(target)
        assertEquals(target.parentFile, part.parentFile, "the part file must be on the same volume")
        assertEquals("track.mp3.part", part.name)
        // Same directory matters for more than tidiness: a rename across volumes is
        // a copy, which can fail halfway and leave a file that looks complete.
        assertTrue(part.path.startsWith(target.parentFile!!.path))
    }

    // ---- byte formatting --------------------------------------------------

    @Test
    fun `small files read as bytes`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1 B", formatBytes(1))
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test
    fun `a file at or above a kibibyte reads in that unit`() {
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("2.5 KB", formatBytes(2560))
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
        assertEquals("1.0 GB", formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun `large values drop the decimal and never invent a unit`() {
        // Past 100 the decimal is noise, and there is nothing above TB to promote to.
        assertEquals("100 KB", formatBytes(102_400))
        assertEquals("1024 TB", formatBytes(1024L * 1024 * 1024 * 1024 * 1024))
    }

    // ---- addon contract ---------------------------------------------------

    /**
     * A registry only for its validation helpers.
     *
     * Both `validate` and `addonIdFor` are pure apart from reading the settings
     * callback, which the default here never invokes.
     */
    private val registry = SourceRegistry { Settings() }

    @Test
    fun `an empty script is rejected with a readable reason`() {
        assertEquals("The script is empty", registry.validate(""))
        assertEquals("The script is empty", registry.validate("   \n  "))
    }

    @Test
    fun `a script without a resolve function is rejected`() {
        val reason = registry.validate("var x = 1;")
        assertTrue(
            reason != null && reason.contains("resolve"),
            "the reason must name the missing function, was: $reason",
        )
    }

    @Test
    fun `a script whose resolve is not a function is rejected`() {
        val reason = registry.validate("var resolve = 42;")
        assertTrue(reason != null, "a non-function resolve must be refused")
    }

    @Test
    fun `a script that does not parse is rejected with the parser message`() {
        val reason = registry.validate("function resolve( { ")
        assertTrue(reason != null && reason.isNotBlank(), "a syntax error must be reported")
    }

    @Test
    fun `a well formed script passes validation`() {
        assertEquals(
            null,
            registry.validate("function resolve(videoId, artist, title) { return null; }"),
        )
        // Declared as a function expression rather than a declaration: both are
        // valid JavaScript, and the check must not be fooled by the difference.
        assertEquals(
            null,
            registry.validate("var resolve = function(videoId, artist, title) { return null; };"),
        )
    }

    @Test
    fun `a validated script may be validated again`() {
        // Rhino contexts are thread-confined; a missed Context.exit would make the
        // second call on this thread fail rather than the first.
        repeat(3) {
            assertEquals(null, registry.validate("function resolve() { return null; }"))
        }
    }

    @Test
    fun `an addon id is derived from a display name`() {
        assertEquals("addon:mysource", registry.addonIdFor("MySource"))
        assertEquals("addon:my-source", registry.addonIdFor("My Source"))
        assertEquals("addon:my-source-2", registry.addonIdFor("  My   Source 2  "))
        assertEquals("addon:ac-dc-hi-res", registry.addonIdFor("AC/DC (Hi-Res)"))
    }

    @Test
    fun `a name with nothing usable still produces an id`() {
        assertEquals("addon:unnamed", registry.addonIdFor("!!!"))
        assertEquals("addon:unnamed", registry.addonIdFor(""))
    }
}
