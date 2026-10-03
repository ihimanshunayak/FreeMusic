// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - local library tests.
//
// The scanner runs against a real temporary directory rather than a mocked file
// system, because the behaviour that matters is exactly the filesystem one:
// which extensions are accepted, whether a hidden file is skipped, and whether
// a folder the user removed really stops contributing tracks.

package com.ihimanshunayak.freemusic.desktop.data.library

import com.ihimanshunayak.freemusic.desktop.model.SourceKind
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalLibraryRepositoryTest {

    private val root: File = Files.createTempDirectory("freemusic-library").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun file(relative: String): File =
        File(root, relative).apply {
            parentFile.mkdirs()
            writeText("not really audio")
        }

    @Test
    fun `supported audio extensions are picked up`() = runTest {
        file("song.mp3")
        file("song.flac")
        file("song.m4a")
        file("song.opus")
        file("song.ogg")
        file("song.wav")

        val repo = LocalLibraryRepository()
        repo.addFolder(root.absolutePath)

        assertEquals(6, repo.tracks.value.size)
    }

    @Test
    fun `non audio files are ignored`() = runTest {
        file("song.mp3")
        file("cover.jpg")
        file("notes.txt")
        file("clip.mp4")
        file("archive.zip")

        val repo = LocalLibraryRepository()
        repo.addFolder(root.absolutePath)

        assertEquals(1, repo.tracks.value.size)
        assertEquals("song", repo.tracks.value[0].title)
    }

    @Test
    fun `hidden and resource fork files are skipped`() = runTest {
        file("song.mp3")
        file(".hidden.mp3")
        file("._resource.mp3")

        val repo = LocalLibraryRepository()
        repo.addFolder(root.absolutePath)

        assertEquals(1, repo.tracks.value.size)
    }

    @Test
    fun `nested folders are walked`() = runTest {
        file("Artist/Album/01 - Track.flac")
        file("Artist/Album/Disc 2/02 - Track.flac")

        val repo = LocalLibraryRepository()
        repo.addFolder(root.absolutePath)

        assertEquals(2, repo.tracks.value.size)
    }

    @Test
    fun `a local track carries the fields the UI and player need`() = runTest {
        file("Artist/Album/Song_Name.mp3")

        val repo = LocalLibraryRepository()
        repo.addFolder(root.absolutePath)

        val track = repo.tracks.value.single()
        assertEquals(SourceKind.LOCAL_FILE, track.source)
        assertNotNull(track.localPath, "a local track must carry its path")
        assertTrue(track.localPath!!.endsWith("Song_Name.mp3"))
        // Underscores are turned into spaces because they are an artefact of the
        // filename, not part of the song's title.
        assertEquals("Song Name", track.title)
        assertEquals("Album", track.artist)
        assertEquals("Artist", track.album)
        assertEquals(null, track.videoId)
    }

    @Test
    fun `tracks are sorted by artist then title`() = runTest {
        file("Zed/song.mp3")
        file("Alpha/b.mp3")
        file("Alpha/a.mp3")

        val repo = LocalLibraryRepository()
        repo.addFolder(root.absolutePath)

        assertEquals(listOf("a", "b", "song"), repo.tracks.value.map { it.title })
        assertEquals(listOf("Alpha", "Alpha", "Zed"), repo.tracks.value.map { it.artist })
    }

    @Test
    fun `adding the same folder twice does not duplicate it`() = runTest {
        file("song.mp3")
        val repo = LocalLibraryRepository()

        repo.addFolder(root.absolutePath)
        repo.addFolder(root.absolutePath)

        assertEquals(1, repo.folders.value.size)
        assertEquals(1, repo.tracks.value.size)
    }

    @Test
    fun `removing a folder removes its tracks`() = runTest {
        file("song.mp3")
        val other = Files.createTempDirectory("freemusic-library-2").toFile()
        File(other, "other.mp3").writeText("x")

        try {
            val repo = LocalLibraryRepository()
            repo.addFolder(root.absolutePath)
            repo.addFolder(other.absolutePath)
            assertEquals(2, repo.tracks.value.size)

            repo.removeFolder(other.absolutePath)

            assertEquals(1, repo.tracks.value.size)
            assertEquals("song", repo.tracks.value[0].title)
        } finally {
            other.deleteRecursively()
        }
    }

    @Test
    fun `the folder track count is reported back`() = runTest {
        file("a.mp3")
        file("b.mp3")
        file("c.flac")

        val repo = LocalLibraryRepository()
        repo.addFolder(root.absolutePath)

        assertEquals(3, repo.folders.value.single().trackCount)
    }

    @Test
    fun `a folder that does not exist is tolerated`() = runTest {
        val repo = LocalLibraryRepository()

        repo.addFolder(File(root, "does-not-exist").absolutePath)

        assertEquals(0, repo.tracks.value.size)
    }

    @Test
    fun `a folder name is used as the display label`() = runTest {
        val nested = File(root, "My Music").apply { mkdirs() }
        File(nested, "a.mp3").writeText("x")

        val repo = LocalLibraryRepository()
        repo.addFolder(nested.absolutePath)

        assertEquals("My Music", repo.folders.value.single().name)
    }

    @Test
    fun `clear empties both folders and tracks`() = runTest {
        file("song.mp3")
        val repo = LocalLibraryRepository()
        repo.addFolder(root.absolutePath)

        repo.clear()

        assertTrue(repo.folders.value.isEmpty())
        assertTrue(repo.tracks.value.isEmpty())
    }

    @Test
    fun `scanning is not left set after a scan finishes`() = runTest {
        file("song.mp3")
        val repo = LocalLibraryRepository()

        repo.addFolder(root.absolutePath)

        assertTrue(!repo.scanning.value, "the scanning flag must be cleared when the scan ends")
    }
}
