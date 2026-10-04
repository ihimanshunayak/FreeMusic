// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - remote artwork loading.
//
// Compose Desktop can decode an image but will not fetch one, so this is the
// fetch: a small async loader with an in-memory LRU and a disk cache. Artwork is
// the single most repeated network call in the app - a home shelf draws thirty
// of them per screen - so the caches are the difference between a snappy window
// and one that re-downloads the same thumbnail on every scroll.

package com.ihimanshunayak.freemusic.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.ihimanshunayak.freemusic.desktop.data.Http
import com.ihimanshunayak.freemusic.desktop.util.AppPaths
import com.ihimanshunayak.freemusic.desktop.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jetbrains.skia.Image
import java.io.File
import java.security.MessageDigest

/**
 * Decoded artwork, keyed by URL.
 *
 * Sized to hold the artwork for several screens worth of shelves. A larger cap
 * would only help a window smaller than the shelf count already covered here.
 */
private object ArtworkCache {

    private const val MAX_ENTRIES = 192

    private val memory = object : LinkedHashMap<String, ImageBitmap>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > MAX_ENTRIES
    }

    private val diskDir: File by lazy { File(AppPaths.cacheDir, "artwork").apply { mkdirs() } }

    /**
     * Looks the URL up everywhere it might already be, then fetches it.
     *
     * The disk layer exists because the memory layer is per-process: relaunching
     * the app should not re-download the home shelf's artwork.
     */
    suspend fun get(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        synchronized(memory) { memory[url] }?.let { return@withContext it }

        val diskFile = File(diskDir, cacheKey(url))
        if (diskFile.isFile && diskFile.length() > 0) {
            decode(diskFile.readBytes())?.let { bitmap ->
                synchronized(memory) { memory[url] = bitmap }
                return@withContext bitmap
            }
            // A truncated cache entry cannot be repaired in place.
            diskFile.delete()
        }

        val bytes = fetch(url) ?: return@withContext null
        val bitmap = decode(bytes)
        if (bitmap != null) {
            runCatching { diskFile.writeBytes(bytes) }
            synchronized(memory) { memory[url] = bitmap }
        }
        bitmap
    }

    private fun fetch(url: String): ByteArray? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Http.USER_AGENT)
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.d("artwork $url returned HTTP ${response.code}", tag = "artwork")
                return null
            }
            response.body?.bytes()
        }
    }.onFailure { Log.d("artwork fetch failed for $url: ${it.message}", tag = "artwork") }.getOrNull()

    private fun decode(bytes: ByteArray): ImageBitmap? = runCatching {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()

    /** A filesystem-safe name; YouTube URLs differ only in their query string. */
    private fun cacheKey(url: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun clearMemory() = synchronized(memory) { memory.clear() }

    fun clearDisk(): Int {
        val files = diskDir.listFiles() ?: return 0
        var removed = 0
        files.forEach { if (it.delete()) removed++ }
        return removed
    }

    fun diskSizeBytes(): Long = diskDir.listFiles()?.sumOf { it.length() } ?: 0L
}

/** The artwork loader, exposed for callers that need the decoded bitmap itself. */
object ImageLoading {

    /**
     * Suspends until [url] is decoded, sharing the same caches as [RemoteImage].
     *
     * This exists for the colour quantiser, which needs the actual pixels rather
     * than something to draw. Going through the same cache means reading an
     * album's palette costs nothing extra when its cover is already on screen -
     * the common case, since a palette is almost always read for artwork the
     * user is looking at.
     */
    suspend fun loadBlocking(url: String): ImageBitmap? = ArtworkCache.get(url)
}

/**
 * Draws [url], or nothing at all while it loads or if it fails.
 *
 * Nothing is drawn on failure rather than a broken-image marker because every
 * caller already paints a placeholder icon underneath: this composable is
 * layered *over* that placeholder, so staying transparent is the correct failure
 * mode and avoids a flash of "no image" on a slow connection.
 */
@Composable
fun RemoteImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val holder = remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        val loaded = ArtworkCache.get(url)
        if (loaded != null) holder.value = loaded
    }

    val image = holder.value
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = contentScale,
        )
    }
}

/** Exposed so the diagnostics screen can report and clear the artwork cache. */
object ArtworkDiagnostics {
    fun clear() {
        ArtworkCache.clearMemory()
    }

    fun clearDisk(): Int = ArtworkCache.clearDisk()

    fun diskBytes(): Long = ArtworkCache.diskSizeBytes()
}
