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

    private fun toTrack(file: File): Track? {
        val extension = file.extension.lowercase(Locale.ROOT)
        if (extension !in SUPPORTED_EXTENSIONS) return null
        // Hidden files and macOS resource forks are never music the user meant.
        val name = file.name
        if (name.startsWith(".") || name.startsWith("._")) return null
        return Track(
            id = "local:" + file.absolutePath,
            title = name.substringBeforeLast('.').replace('_', ' ').trim(),
            artist = file.parentFile?.name ?: "Unknown artist",
            album = file.parentFile?.parentFile?.name,
            durationSeconds = 0,
            thumbnailUrl = null,
            source = SourceKind.LOCAL_FILE,
            videoId = null,
            localPath = file.absolutePath,
        )
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
