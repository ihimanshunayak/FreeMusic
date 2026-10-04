// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - local (on-disk) library.
//
// The Android app scans MediaStore; a desktop app has no MediaStore, so this
// walks folders the user pointed at. Only the formats libVLC can actually decode
// are accepted, because offering a file that fails on play is worse than not
// listing it.

package com.ihimanshunayak.freemusic.desktop.data.library

import com.ihimanshunayak.freemusic.desktop.data.local.AudioMetadataReader
import com.ihimanshunayak.freemusic.desktop.model.SourceKind
import com.ihimanshunayak.freemusic.desktop.model.Track
import com.ihimanshunayak.freemusic.desktop.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.Locale

/**
 * A folder the user added to the library.
 *
 * [trackCount] is cached rather than recomputed on every draw because a folder
 * can hold thousands of files and the sidebar shows the number.
 */
data class LibraryFolder(
    val path: String,
    val trackCount: Int = 0,
) {
    val name: String get() = File(path).name.ifBlank { path }
}

/**
 * Scans folders for playable audio and exposes the result as a flat list.
 *
 * Scanning is deliberately shallow-recursive with a depth cap: a user who adds
 * `C:\` by accident should not be able to hang the app, and real music folders
 * are rarely more than a few levels deep.
 */
class LocalLibraryRepository {

    private val _folders = MutableStateFlow<List<LibraryFolder>>(emptyList())
    val folders: StateFlow<List<LibraryFolder>> = _folders.asStateFlow()

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Adds a folder and rescans everything. Adding a duplicate is a no-op. */
    suspend fun addFolder(path: String) {
        val normalised = File(path).absolutePath
        if (_folders.value.any { it.path.equals(normalised, ignoreCase = true) }) return
        _folders.value = _folders.value + LibraryFolder(normalised)
        scan()
    }

    suspend fun removeFolder(path: String) {
        _folders.value = _folders.value.filterNot { it.path.equals(path, ignoreCase = true) }
        scan()
    }

    suspend fun refresh() = scan()

    private suspend fun scan() = withContext(Dispatchers.IO) {
        _scanning.value = true
        val started = System.currentTimeMillis()
        val found = mutableListOf<Track>()
        val counts = mutableMapOf<String, Int>()

        _folders.value.forEach { folder ->
            val root = File(folder.path)
            if (!root.isDirectory) {
                Log.w("library folder is gone: ${folder.path}", tag = "library")
                return@forEach
            }
            var count = 0
            walk(root, depth = 0) { file ->
                val track = toTrack(file) ?: return@walk
                found.add(track)
                count++
            } 
            counts[folder.path] = count
        }

        _tracks.value = found.sortedWith(
            compareBy({ it.artist.lowercase(Locale.ROOT) }, { it.title.lowercase(Locale.ROOT) })
        )
        _folders.value = _folders.value.map { it.copy(trackCount = counts[it.path] ?: 0) }
        _scanning.value = false
        Log.i(
            "library scan found ${found.size} tracks in ${System.currentTimeMillis() - started}ms",
            tag = "library",
        )
    }

    /**
     * Depth-first walk with a depth cap.
     *
     * Symlinked folders are skipped: a link back up the tree would otherwise
     * recurse until the depth cap on every scan.
     */
    private fun walk(dir: File, depth: Int, action: (File) -> Unit) {
        if (depth > MAX_DEPTH) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            if (child.isDirectory) {
                if (isSymlink(child)) continue
                walk(child, depth + 1, action)
            } else {
                action(child)
            }
        }
    }

    /**
     * True only for a real filesystem link / junction.
     *
     * This must not be inferred by comparing `canonicalPath` with `absolutePath`:
     * that comparison also fires for the valid case where the two spellings of
     * the same path differ, and on Windows they routinely do - `%TEMP%` is handed
     * to the JVM as the 8.3 short name (`C:\Users\HIMANS~1\...`) while the
     * canonical form is the long name. Treating that as a link silently skipped
     * every subfolder of any library rooted under a short-named path.
     */
    private fun isSymlink(file: File): Boolean =
        runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    /**
     * Reads the file's own tags, falling back to what the filename says.
     *
     * Reading tags is what makes the library correct rather than merely
     * populated: a downloaded file is all too often called `01 track.mp3`, and
     * only the tags inside it know the artist. The filename remains the fallback
     * because a tagless rip is common too, and "Artist - Title" from the name is
     * a better guess than the raw stem.
     */
    private fun toTrack(file: File): Track? {
        val extension = file.extension.lowercase(Locale.ROOT)
        if (extension !in SUPPORTED_EXTENSIONS) return null
        // Hidden files and macOS resource forks are never music the user meant.
        val name = file.name
        if (name.startsWith(".") || name.startsWith("._")) return null

        val stem = name.substringBeforeLast('.').replace('_', ' ').trim()
        val tags = AudioMetadataReader.read(file, wantCover = false)

        val fromName = splitArtistTitle(stem)
        val title = tags?.title?.takeIf { it.isNotBlank() } ?: fromName.second
        val artist = tags?.artist?.takeIf { it.isNotBlank() }
            ?: fromName.first
            ?: file.parentFile?.name
            ?: "Unknown artist"
        val album = tags?.album?.takeIf { it.isNotBlank() } ?: file.parentFile?.parentFile?.name

        return Track(
            id = "local:" + file.absolutePath,
            title = title,
            artist = artist,
            album = album,
            durationSeconds = tags?.durationSeconds ?: 0,
            thumbnailUrl = null,
            source = SourceKind.LOCAL_FILE,
            videoId = null,
            localPath = file.absolutePath,
        )
    }

    /**
     * Splits a filename into artist and title.
     *
     * "Artist - Title" is the near-universal convention for a downloaded track,
     * so it is worth honouring; anything else is treated as a bare title, since
     * guessing an artist out of a name with no separator would invent data.
     * A leading track number is stripped first, because "01 Artist - Title" is
     * just as common.
     */
    private fun splitArtistTitle(stem: String): Pair<String?, String> {
        val withoutNumber = stem.replace(Regex("""^\s*\d{1,3}[\s._-]+"""), "").trim()
        val separator = withoutNumber.indexOf(" - ")
        if (separator <= 0) return null to withoutNumber.ifBlank { stem }
        val artist = withoutNumber.substring(0, separator).trim()
        val title = withoutNumber.substring(separator + 3).trim()
        return if (artist.isBlank() || title.isBlank()) {
            null to withoutNumber.ifBlank { stem }
        } else {
            artist to title
        }
    }

    fun clear() {
        _tracks.value = emptyList()
        _folders.value = emptyList()
    }

    private companion object {
        /** Enough for `Artist/Album/Disc/Track.flac`, not enough to walk a whole drive. */
        const val MAX_DEPTH = 6

        /**
         * Formats libVLC decodes without an extra codec pack. Video containers
         * that happen to carry audio are left out on purpose - this is a music app.
         */
        val SUPPORTED_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "aac", "opus", "ogg", "oga", "wav", "wma", "aiff", "aif", "ape", "mka",
        )
    }
}
