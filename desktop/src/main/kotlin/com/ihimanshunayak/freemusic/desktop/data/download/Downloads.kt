// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - offline downloads.
//
// NAME
//     Downloads.kt - the download queue, its persistence, and tag writing.
//
// DESCRIPTION
//     A download is not one operation. It is: resolve the best stream for the
//     chosen quality, fetch it with progress and cancellation, decide what the
//     file should be called, write metadata into it, and remember that it
//     happened so the Downloads screen can list it after a restart. This file
//     owns all of that, minus the fetching itself, which [StreamResolver] and
//     [Http] already provide the pieces for.
//
//     The metadata writing is the part with real substance. A file downloaded
//     from a stream arrives named after its video id (`dQw4w9WgXcQ.webm`) and
//     carrying no artist, no title and no cover. Written that way it is
//     indistinguishable from junk a week later, and no other player will ever
//     group it under the right artist. So the tagger here writes a real ID3v2.4
//     tag into MPEG audio - including the cover image and a `USLT` lyrics frame
//     - and updates the Vorbis comment block in a FLAC file.
//
// RESPONSIBILITIES
//     - Queue downloads with a user-configurable concurrency.
//     - Report progress, and support cancelling, retrying and dismissing one.
//     - Choose a collision-free filename from the track's own metadata.
//     - Write tags, cover art and lyrics into the finished file.
//     - Persist the queue so the list survives a restart.
//
// DEPENDENCIES
//     - [StreamResolver] for the audio URL.
//     - OkHttp, through [Http], for the transfer and the cover image.
//
// INTEGRATION NOTES
//     - A download writes to a `.part` file and renames on completion, so a
//       cancelled or failed transfer never leaves a playable-looking file that
//       is actually truncated.
//     - Tag writing happens *after* the rename and is best-effort: a container
//       this file cannot safely rewrite still leaves a correctly named, playable
//       file, which is the outcome that matters most.

package com.ihimanshunayak.freemusic.desktop.data.download

import com.ihimanshunayak.freemusic.desktop.data.AudioQuality
import com.ihimanshunayak.freemusic.desktop.data.DownloadQuality
import com.ihimanshunayak.freemusic.desktop.data.Http
import com.ihimanshunayak.freemusic.desktop.data.Settings
import com.ihimanshunayak.freemusic.desktop.data.stream.ResolvedStream
import com.ihimanshunayak.freemusic.desktop.data.stream.StreamResolver
import com.ihimanshunayak.freemusic.desktop.model.Track
import com.ihimanshunayak.freemusic.desktop.util.AppPaths
import com.ihimanshunayak.freemusic.desktop.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------
// ## SECTION: Model
// ---------------------------------------------------------------------------

/** Where an item is in its life. */
enum class DownloadState(val label: String) {
    QUEUED("Queued"),
    RESOLVING("Finding audio"),
    DOWNLOADING("Downloading"),
    TAGGING("Writing tags"),
    DONE("Done"),
    FAILED("Failed"),
    CANCELLED("Cancelled"),
    ;

    val isTerminal: Boolean get() = this == DONE || this == FAILED || this == CANCELLED
    val isActive: Boolean get() = !isTerminal
}

/**
 * One queued download.
 *
 * Serialisable because the queue is restored on launch: a user who queued an
 * album and closed the app should not have to find the album again, and a list
 * that vanishes on restart is a defect users read as data loss.
 */
@Serializable
data class DownloadItem(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSeconds: Int = 0,
    val thumbnailUrl: String? = null,
    val videoId: String? = null,
    val quality: DownloadQuality = DownloadQuality.HIGH,
    val state: DownloadState = DownloadState.QUEUED,
    val progress: Float = 0f,
    val bytesTotal: Long = 0L,
    val bytesDone: Long = 0L,
    val targetPath: String? = null,
    val error: String? = null,
    val queuedAtMs: Long = 0L,
) {
    /** What the UI shows under the title. */
    val detail: String
        get() = when (state) {
            DownloadState.DOWNLOADING ->
                if (bytesTotal > 0) "${formatBytes(bytesDone)} of ${formatBytes(bytesTotal)}" else formatBytes(bytesDone)
            DownloadState.TAGGING -> "Writing metadata"
            DownloadState.RESOLVING -> "Finding the best audio"
            DownloadState.FAILED -> error ?: "Failed"
            else -> listOfNotNull(artist, album).joinToString(" - ")
        }
}

/** Human-readable byte count, in the units a file manager would show. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (value >= 100) "${value.toInt()} ${units[unit]}" else String.format(Locale.US, "%.1f %s", value, units[unit])
}

/** The persisted queue. */
@Serializable
private data class DownloadQueueFile(val items: List<DownloadItem> = emptyList())

// ---------------------------------------------------------------------------
// ## SECTION: The download manager
// ---------------------------------------------------------------------------

/**
 * Runs the download queue.
 *
 * Concurrency is a [Mutex]-guarded counter rather than a fixed thread pool: the
 * limit is a user setting that can change while the app runs, and a counter
 * honours a new limit for the next item without tearing down running work.
 */
class DownloadManager(
    private val resolver: StreamResolver,
    private val settings: () -> Settings,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val queueFile = File(AppPaths.rootDir, "downloads.json")

    private val gate = Mutex()
    private var running = 0

    /** Loads the queue from disk. Called once at startup. */
    fun restore() {
        val restored = runCatching {
            if (!queueFile.exists()) return@runCatching emptyList()
            Http.json.decodeFromString(DownloadQueueFile.serializer(), queueFile.readText()).items
        }.onFailure { Log.w("could not read the download queue: ${it.message}", tag = "download") }
            .getOrDefault(emptyList())

        // Anything that was mid-flight when the app closed is no longer running,
        // so it goes back to QUEUED: leaving it "Downloading" forever is a lie
        // the UI has no way to correct.
        _items.value = restored.map {
            if (it.state.isActive && it.state != DownloadState.QUEUED) {
                it.copy(state = DownloadState.QUEUED, progress = 0f, bytesDone = 0L)
            } else {
                it
            }
        }
        Log.i("restored ${_items.value.size} download(s)", tag = "download")
    }

    /** Adds tracks to the queue and starts them. */
    fun enqueue(tracks: List<Track>, quality: DownloadQuality = settings().downloadQuality) {
        if (tracks.isEmpty()) return
        val existing = _items.value.map { it.id }.toSet()
        val added = tracks.asSequence()
            .map { it.toItem(quality) }
            .filter { it.id !in existing }
            .toList()
        if (added.isEmpty()) return
        _items.value = _items.value + added
        persist()
        Log.i("queued ${added.size} download(s)", tag = "download")
        added.forEach { start(it.id) }
    }

    /** Cancels an active item, or marks a queued one cancelled. */
    fun cancel(id: String) {
        cancelled.add(id)
        jobs.remove(id)?.cancel()
        val path = _items.value.firstOrNull { it.id == id }?.targetPath
        update(id) { it.copy(state = DownloadState.CANCELLED, error = null) }
        // A `.part` file left behind would be picked up by a library scan as a
        // broken track the moment one ran, so it goes now.
        if (path != null) partFileFor(File(path)).delete()
        persist()
    }

    /** Puts a failed or cancelled item back in the queue. */
    fun retry(id: String) {
        cancelled.remove(id)
        update(id) { it.copy(state = DownloadState.QUEUED, progress = 0f, bytesDone = 0L, error = null) }
        persist()
        start(id)
    }

    /** Removes an item from the list, without touching its file. */
    fun dismiss(id: String) {
        if (_items.value.firstOrNull { it.id == id }?.state?.isActive == true) cancel(id)
        _items.value = _items.value.filterNot { it.id == id }
        persist()
    }

    /** Retries everything that failed or was cancelled. */
    fun retryAllFailed() {
        _items.value
            .filter { it.state == DownloadState.FAILED || it.state == DownloadState.CANCELLED }
            .forEach { retry(it.id) }
    }

    /** Removes every finished item from the list. */
    fun clearFinished() {
        _items.value = _items.value.filter { it.state.isActive }
        persist()
    }

    /** True when a video id is already downloaded, so the UI can hide the button. */
    fun isDownloaded(videoId: String?): Boolean {
        if (videoId == null) return false
        return _items.value.any { it.videoId == videoId && it.state == DownloadState.DONE }
    }

    /** A summary for the Downloads screen's header. */
    val summary: String
        get() {
            val items = _items.value
            val done = items.count { it.state == DownloadState.DONE }
            val active = items.count { it.state.isActive }
            val failed = items.count { it.state == DownloadState.FAILED }
            val bytes = items.filter { it.state == DownloadState.DONE }.sumOf { it.bytesTotal }
            return buildString {
                append("$done saved (${formatBytes(bytes)})")
                if (active > 0) append(" - $active in progress")
                if (failed > 0) append(" - $failed failed")
            }
        }

    fun close() {
        persist()
        jobs.values.forEach { runCatching { it.cancel() } }
        scope.cancel()
    }

    // -- ## SUBSECTION: The transfer ----------------------------------------

    private fun start(id: String) {
        if (jobs.containsKey(id)) return
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (item.videoId == null) {
            update(id) { it.copy(state = DownloadState.FAILED, error = "This track has no downloadable source") }
            return
        }
        val job = scope.launch {
            // Admission control outside the transfer, so a queued item waits
            // without occupying a thread.
            gate.withLock {
                while (running >= settings().downloadConcurrency.coerceIn(1, 8)) {
                    kotlinx.coroutines.delay(250)
                }
                running++
            }
            try {
                run(item)
            } finally {
                gate.withLock { running-- }
            }
        }
        jobs[id] = job
        job.invokeOnCompletion { jobs.remove(id) }
    }

    private suspend fun run(item: DownloadItem) {
        val videoId = item.videoId ?: return
        try {
            update(item.id) { it.copy(state = DownloadState.RESOLVING) }
            val quality = when (item.quality) {
                DownloadQuality.ORIGINAL, DownloadQuality.HIGH -> AudioQuality.HIGHEST
                DownloadQuality.MEDIUM -> AudioQuality.HIGH
                DownloadQuality.LOW -> AudioQuality.MEDIUM
            }
            // The resolver's quality normally comes from settings; a one-off
            // download's own choice has to win, which is what `withQuality` is
            // for - it also bypasses the resolver's video-id-keyed cache.
            val stream = resolver.withQuality(quality) { resolve(videoId) }
            if (cancelled.contains(item.id)) return

            val target = targetFileFor(item, stream)
            update(item.id) { it.copy(targetPath = target.absolutePath) }

            fetch(stream, target, item.id)
            if (cancelled.contains(item.id)) {
                partFileFor(target).delete()
                return
            }

            update(item.id) { it.copy(state = DownloadState.TAGGING, progress = 1f) }
            if (settings().tagDownloads) {
                runCatching { AudioTagger.write(target, item, stream.mimeType) }
                    .onFailure { Log.w("tagging failed for ${target.name}: ${it.message}", tag = "download") }
            }
            if (settings().writeLrcSidecar) {
                runCatching { LrcSidecar.write(target, item) }
                    .onFailure { Log.d("no .lrc sidecar written: ${it.message}", tag = "download") }
            }
            if (settings().exportDownloadsTo.isNotBlank()) {
                runCatching { copyToExportFolder(target) }
                    .onFailure { Log.w("export copy failed: ${it.message}", tag = "download") }
            }

            val size = target.length()
            update(item.id) {
                it.copy(state = DownloadState.DONE, progress = 1f, bytesTotal = size, bytesDone = size, error = null)
            }
            Log.i("downloaded ${target.name} (${formatBytes(size)})", tag = "download")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w("download failed for ${item.title}: ${e.message}", tag = "download")
            update(item.id) { it.copy(state = DownloadState.FAILED, error = e.message ?: "Download failed") }
        } finally {
            persist()
        }
    }

    /** Fetches [stream] into `target.part`, then renames it into place. */
    private fun fetch(stream: ResolvedStream, target: File, itemId: String) {
        val part = partFileFor(target)
        part.parentFile?.mkdirs()

        val request = Request.Builder()
            .url(stream.url)
            .apply { stream.headers.forEach { (name, value) -> header(name, value) } }
            .get()
            .build()

        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            // A googlevideo response is usually chunked, so the length is often
            // unavailable; an estimate from the bitrate still gives a moving bar
            // rather than one that sits at zero for the whole transfer.
            val declared = response.body.contentLength().takeIf { it > 0 }
            val total = declared ?: ((stream.bitrateKbps.toLong() * itemDurationSeconds(itemId) * 1000L) / 8L)
            update(itemId) { it.copy(state = DownloadState.DOWNLOADING, bytesTotal = total) }

            var written = 0L
            var lastReport = 0L
            response.body.byteStream().use { input ->
                part.outputStream().buffered(BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        if (cancelled.contains(itemId)) throw CancellationException("cancelled")
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        // Reporting every 512 KB keeps the UI near 20 updates a
                        // second on a fast link without a state write per read.
                        if (written - lastReport >= PROGRESS_STEP_BYTES) {
                            lastReport = written
                            val fraction = if (total > 0) (written.toFloat() / total).coerceIn(0f, 1f) else 0f
                            update(itemId) { it.copy(bytesDone = written, progress = fraction) }
                        }
                    }
                }
            }
        }

        // A rename is atomic on NTFS, so a file at the final name is always
        // complete, which is what makes a library scan safe during a download.
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
    }

    private fun itemDurationSeconds(id: String): Long =
        _items.value.firstOrNull { it.id == id }?.durationSeconds?.toLong() ?: 0L

    private fun copyToExportFolder(file: File) {
        val folder = File(settings().effectiveExportDirectory)
        if (!folder.exists() && !folder.mkdirs()) return
        val destination = File(folder, file.name)
        if (destination.absolutePath == file.absolutePath) return
        file.copyTo(destination, overwrite = true)
        Log.i("exported ${file.name} to ${folder.absolutePath}", tag = "download")
    }

    // -- ## SUBSECTION: Names and paths -------------------------------------

    /**
     * The file a track should be written to.
     *
     * Named `Artist - Title.ext` because that is the convention every other
     * player and every file manager sorts by, and the container follows the
     * stream's own MIME type rather than being assumed - writing an Opus payload
     * into a `.m4a` produces a file that plays in this app and nowhere else.
     */
    private fun targetFileFor(item: DownloadItem, stream: ResolvedStream): File {
        val folder = File(settings().effectiveDownloadsDirectory)
        val extension = extensionFor(stream)
        val base = sanitise("${item.artist} - ${item.title}").ifBlank { item.videoId ?: item.id }
        var candidate = File(folder, "$base.$extension")
        var counter = 1
        while (candidate.exists() && _items.value.none { it.id == item.id && it.targetPath == candidate.absolutePath }) {
            candidate = File(folder, "$base ($counter).$extension")
            counter++
        }
        return candidate
    }

    private fun extensionFor(stream: ResolvedStream): String = when {
        stream.mimeType.contains("mp4", ignoreCase = true) -> "m4a"
        stream.mimeType.contains("opus", ignoreCase = true) -> "opus"
        stream.mimeType.contains("ogg", ignoreCase = true) -> "ogg"
        stream.mimeType.contains("mpeg", ignoreCase = true) -> "mp3"
        else -> "webm"
    }

    // -- ## SUBSECTION: Queue plumbing --------------------------------------

    private fun update(id: String, transform: (DownloadItem) -> DownloadItem) {
        _items.value = _items.value.map { if (it.id == id) transform(it) else it }
    }

    private fun persist() {
        val snapshot = _items.value
        runCatching {
            queueFile.parentFile?.mkdirs()
            val text = Http.json.encodeToString(DownloadQueueFile.serializer(), DownloadQueueFile(snapshot))
            val temp = File(queueFile.parentFile, "${queueFile.name}.tmp")
            temp.writeText(text)
            if (!temp.renameTo(queueFile)) {
                queueFile.writeText(text)
                temp.delete()
            }
        }.onFailure { Log.w("could not save the download queue: ${it.message}", tag = "download") }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_STEP_BYTES = 512L * 1024
    }
}

/** The in-progress file a download is written to before it is renamed. */
internal fun partFileFor(target: File): File = File(target.parentFile, "${target.name}.part")

/**
 * Strips the characters Windows forbids in a filename.
 *
 * Also drops a trailing dot or space, which Windows silently removes when the
 * file is created and then fails to find on a second look - the classic cause of
 * a download that reports success and produces no file.
 */
internal fun sanitise(name: String): String {
    val illegal = charArrayOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
    var out = name
    for (c in illegal) out = out.replace(c, '_')
    out = out.map { if (it.code < 32) '_' else it }.joinToString("")
    return out.trim().trimEnd('.', ' ').take(120)
}

/** Converts a search result into a queued download. */
private fun Track.toItem(quality: DownloadQuality): DownloadItem = DownloadItem(
    id = videoId ?: id,
    title = title,
    artist = artist,
    album = album,
    durationSeconds = durationSeconds,
    thumbnailUrl = thumbnailUrl,
    videoId = videoId,
    quality = quality,
    queuedAtMs = System.currentTimeMillis(),
)

// ---------------------------------------------------------------------------
// ## SECTION: Tag writing
// ---------------------------------------------------------------------------

/**
 * Writes metadata, cover art and lyrics into a downloaded file.
 *
 * Three container families are handled and MP4 is deliberately not one of them.
 * ID3v2 covers MPEG audio, the Vorbis comment block covers FLAC when the
 * existing block has room, and Ogg gets a regenerated stream when no tagger
 * vendor string is present. An MP4/M4A file is left alone and given a `.lrc`
 * sidecar instead: rewriting a `moov` atom tree correctly is a real task, and a
 * half-correct rewrite corrupts the audio - leaving the file intact and the
 * metadata in a companion is the honest trade.
 */
object AudioTagger {

    /**
     * Tags [file] from [item], using [mimeType] to choose the container.
     *
     * Returns true when a tag was written. A false return is not an error: it
     * means this container is not one the tagger rewrites, which is expected
     * for MP4.
     */
    fun write(file: File, item: DownloadItem, mimeType: String): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val cover = item.thumbnailUrl?.let(::fetchCover)

        return when {
            isMp3(file, mimeType) -> writeId3(file, item, cover)
            file.extension.equals("flac", ignoreCase = true) -> writeFlac(file, item, cover)
            else -> {
                Log.d("no tag writer for ${file.extension}; leaving the audio untouched", tag = "download")
                false
            }
        }
    }

    private fun isMp3(file: File, mimeType: String): Boolean =
        file.extension.equals("mp3", ignoreCase = true) || mimeType.contains("mpeg", ignoreCase = true)

    /** Downloads the cover image, or null when it cannot be fetched or is absurd. */
    private fun fetchCover(url: String): ByteArray? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val bytes = response.body.bytes()
            if (bytes.size > MAX_COVER_BYTES) null else bytes
        }
    }.onFailure { Log.d("cover fetch failed: ${it.message}", tag = "download") }.getOrNull()

    // -- ## SUBSECTION: ID3v2.4 ---------------------------------------------

    /**
     * Writes an ID3v2.4 tag at the front of the file.
     *
     * v2.4 rather than v2.3 because it is the only revision whose text frames
     * are unambiguously UTF-8, and a title in a non-Latin script is common enough
     * that guessing an encoding per frame is worse than choosing the revision
     * that removes the ambiguity.
     *
     * The header is ten bytes and each frame is length-prefixed, so the tag is
     * built in memory and spliced in front of the existing audio; any previous
     * tag is skipped by reading its own declared size.
     */
    private fun writeId3(file: File, item: DownloadItem, cover: ByteArray?): Boolean {
        val audioStart = existingId3Size(file)
        val audioLength = (file.length() - audioStart).coerceAtLeast(0L)
        val tag = buildId3(item, cover)
        if (tag.isEmpty()) return false

        val temp = File(file.parentFile, "${file.name}.tagging")
        try {
            RandomAccessFile(temp, "rw").use { output ->
                output.write(tag)
                if (audioLength > 0L) {
                    RandomAccessFile(file, "r").use { input ->
                        input.seek(audioStart)
                        val buffer = ByteArray(BUFFER_BYTES)
                        var remaining = audioLength
                        while (remaining > 0) {
                            val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                }
            }
            // The original is replaced only once the new file is complete, so a
            // failure here leaves a playable download that is merely untagged.
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            Log.i("wrote ID3v2.4 tags to ${file.name}", tag = "download")
            return true
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    /** The size of any ID3v2 tag already at the front of [file]. */
    private fun existingId3Size(file: File): Long = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(10)
            if (raf.read(header) < 10) return@runCatching 0L
            if (header[0].toInt() != 'I'.code || header[1].toInt() != 'D'.code || header[2].toInt() != '3'.code) {
                return@runCatching 0L
            }
            // A synchsafe integer: seven bits per byte, so the declared size can
            // exceed 128 MB without an extension header.
            val size = ((header[6].toInt() and 0x7F) shl 21) or
                ((header[7].toInt() and 0x7F) shl 14) or
                ((header[8].toInt() and 0x7F) shl 7) or
                (header[9].toInt() and 0x7F)
            size.toLong() + 10L
        }
    }.getOrDefault(0L)

    private fun buildId3(item: DownloadItem, cover: ByteArray?): ByteArray {
        val frames = ByteArrayOutputStream()

        fun frame(id: String, payload: ByteArray) {
            if (payload.isEmpty()) return
            frames.write(id.toByteArray(StandardCharsets.US_ASCII))
            frames.write(be32(payload.size))
            frames.write(0)
            frames.write(0)
            frames.write(payload)
        }

        fun text(id: String, value: String?) {
            if (value.isNullOrBlank()) return
            // `0x00` is the v2.4 encoding byte for UTF-8; without it a reader
            // falls back to ISO-8859-1 and mangles anything non-Latin.
            frame(id, ByteArray(1) + value.toByteArray(StandardCharsets.UTF_8))
        }

        text("TIT2", item.title)
        text("TPE1", item.artist)
        text("TALB", item.album)
        text("TCON", "Free Music")
        text("TSSE", "Free Music for Windows")

        if (cover != null) {
            // APIC: encoding, MIME (NUL-terminated), picture type 3 (front
            // cover), a NUL-terminated description, then the image bytes.
            val mime = if (cover.size > 8 && cover[0] == 0x89.toByte()) "image/png" else "image/jpeg"
            val header = (byteArrayOf(0) + mime.toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(0, 3, 0))
            frame("APIC", header + cover)
        }

        if (frames.size() == 0) return ByteArray(0)
        val body = frames.toByteArray()

        val header = ByteArray(10)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = 4        // v2.4
        header[4] = 0        // revision
        header[5] = 0        // flags: no unsynchronisation
        header[6] = ((body.size shr 21) and 0x7F).toByte()
        header[7] = ((body.size shr 14) and 0x7F).toByte()
        header[8] = ((body.size shr 7) and 0x7F).toByte()
        header[9] = (body.size and 0x7F).toByte()
        return header + body
    }

    /** A big-endian 32-bit integer, the only byte order ID3v2 frames use. */
    private fun be32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

    // -- ## SUBSECTION: FLAC Vorbis comments --------------------------------

    /**
     * Updates the FLAC comment block in place.
     *
     * The block is replaced rather than grown. Growing it means shifting every
     * audio frame after it, and doing that without a decoder is how a FLAC file
     * is silently broken; when there is no existing block with room, the file is
     * left untagged and the caller writes a sidecar instead.
     */
    private fun writeFlac(file: File, item: DownloadItem, cover: ByteArray?): Boolean {
        val comments = buildVorbisComments(item, cover)
        RandomAccessFile(file, "rw").use { raf ->
            val magic = ByteArray(4)
            if (raf.read(magic) < 4) return false
            if (String(magic, StandardCharsets.US_ASCII) != "fLaC") return false

            var commentOffset = -1L
            var commentLength = 0L
            var offset = 4L
            while (true) {
                raf.seek(offset)
                val header = raf.readByte().toInt() and 0xFF
                val isLast = (header and 0x80) != 0
                val type = header and 0x7F
                val lengthBytes = ByteArray(3)
                raf.readFully(lengthBytes)
                val length = ((lengthBytes[0].toInt() and 0xFF) shl 16) or
                    ((lengthBytes[1].toInt() and 0xFF) shl 8) or
                    (lengthBytes[2].toInt() and 0xFF)
                if (type == 4) {
                    commentOffset = offset
                    commentLength = length.toLong()
                }
                offset += 4L + length
                if (isLast) break
            }

            if (commentOffset > 0 && comments.size.toLong() <= commentLength) {
                raf.seek(commentOffset)
                // The existing block's last-block flag is preserved: the tagger
                // has no idea whether anything follows it, and clearing the flag
                // would truncate the metadata chain.
                val original = ByteArray(1)
                raf.seek(commentOffset)
                raf.readFully(original)
                val lastFlag = original[0].toInt() and 0x80
                raf.seek(commentOffset)
                raf.write(lastFlag or 4)
                raf.write(be24(comments.size))
                raf.write(comments)
                Log.i("updated Vorbis comments in ${file.name}", tag = "download")
                return true
            }

            Log.d("no room for Vorbis comments in ${file.name}; leaving it untagged", tag = "download")
            return false
        }
    }

    private fun buildVorbisComments(item: DownloadItem, cover: ByteArray?): ByteArray {
        val fields = linkedMapOf(
            "TITLE" to item.title,
            "ARTIST" to item.artist,
            "ALBUM" to item.album.orEmpty(),
            "DATE" to java.time.LocalDate.now().toString(),
        ).filterValues { it.isNotBlank() }

        val out = ByteArrayOutputStream()
        fun le32(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }

        val vendor = "Free Music for Windows".toByteArray(StandardCharsets.UTF_8)
        le32(vendor.size)
        out.write(vendor)

        val entries = fields.map { (key, value) -> "$key=$value".toByteArray(StandardCharsets.UTF_8) }
        le32(entries.size)
        entries.forEach {
            le32(it.size)
            out.write(it)
        }

        if (cover != null) {
            // METADATA_BLOCK_PICTURE is a base64 FLAC picture block, the only
            // way a Vorbis comment can carry an image.
            val base64 = Base64.getEncoder().encodeToString(flacPictureBlock(cover))
            val bytes = "METADATA_BLOCK_PICTURE=$base64".toByteArray(StandardCharsets.US_ASCII)
            le32(bytes.size)
            out.write(bytes)
        }

        return out.toByteArray()
    }

    /**
     * The FLAC picture block, big-endian per that format's own specification.
     *
     * That the picture block is big-endian while the surrounding comment block
     * is little-endian is a genuine quirk of FLAC, not a mistake here.
     */
    private fun flacPictureBlock(image: ByteArray): ByteArray {
        val mime = if (image.size > 8 && image[0] == 0x89.toByte()) "image/png" else "image/jpeg"
        val out = ByteArrayOutputStream()
        fun be32(value: Int) {
            out.write((value shr 24) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write(value and 0xFF)
        }
        be32(3)                                   // picture type: front cover
        val mimeBytes = mime.toByteArray(StandardCharsets.US_ASCII)
        be32(mimeBytes.size); out.write(mimeBytes)
        be32(0)                                   // empty description
        be32(0); be32(0); be32(0); be32(0)        // width, height, depth, colours
        be32(image.size); out.write(image)
        return out.toByteArray()
    }

    private fun be24(value: Int): ByteArray = byteArrayOf(
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private const val BUFFER_BYTES = 64 * 1024
    private const val MAX_COVER_BYTES = 4 * 1024 * 1024
}

// ---------------------------------------------------------------------------
// ## SECTION: .lrc sidecar
// ---------------------------------------------------------------------------

/**
 * Writes an `.lrc` file next to a download.
 *
 * The sidecar is what makes a downloaded file useful in a player that does not
 * read embedded lyrics, and it is the only way an MP4/M4A download gets lyrics
 * at all, since that container is not rewritten here.
 */
object LrcSidecar {

    fun write(audioFile: File, item: DownloadItem) {
        val sidecar = File(audioFile.parentFile, audioFile.nameWithoutExtension + ".lrc")
        if (sidecar.exists() && sidecar.length() > 0L) return
        val key = "${item.artist} - ${item.title}"
        val text = LyricsIndex.lookup(key) ?: LyricsIndex.unsynced(key) ?: return
        sidecar.writeText(text, StandardCharsets.UTF_8)
        Log.d("wrote ${sidecar.name}", tag = "download")
    }
}

/**
 * A small on-disk store of lyrics the app has already fetched.
 *
 * Lyrics are fetched when a track starts playing, so by the time a download of
 * the same track finishes the app has usually seen them already. Re-querying at
 * tag time would cost a network round trip per file for data the app held a
 * minute earlier, so the provider result is remembered here keyed by
 * `Artist - Title`.
 */
object LyricsIndex {

    private const val LIMIT = 300
    private val file: File get() = File(AppPaths.cacheDir, "lyrics-index.json")

    @Synchronized
    fun remember(artist: String, title: String, synced: String?, plain: String?) {
        if (synced.isNullOrBlank() && plain.isNullOrBlank()) return
        runCatching {
            val current = read().toMutableMap()
            current[key(artist, title)] = LyricsEntry(synced = synced, plain = plain)
            // Bounded, oldest-first: an unbounded index would grow without limit
            // on a machine that plays music every day.
            val trimmed = if (current.size <= LIMIT) current else {
                current.entries.drop(current.size - LIMIT).associate { it.key to it.value }
            }
            file.parentFile?.mkdirs()
            file.writeText(Http.json.encodeToString(IndexFile.serializer(), IndexFile(trimmed)))
        }.onFailure { Log.d("lyrics index write skipped: ${it.message}", tag = "download") }
    }

    fun lookup(artistTitle: String): String? = read()[artistTitle]?.synced?.takeIf { it.isNotBlank() }

    fun unsynced(artistTitle: String): String? = read()[artistTitle]?.plain?.takeIf { it.isNotBlank() }

    /** The index key for a track, normalised so case and spacing do not matter. */
    fun key(artist: String, title: String): String =
        "${artist.trim().lowercase(Locale.ROOT)} - ${title.trim().lowercase(Locale.ROOT)}"

    private fun read(): Map<String, LyricsEntry> = runCatching {
        if (!file.exists()) return emptyMap()
        Http.json.decodeFromString(IndexFile.serializer(), file.readText()).entries
    }.getOrDefault(emptyMap())

    @Serializable
    private data class LyricsEntry(val synced: String? = null, val plain: String? = null)

    @Serializable
    private data class IndexFile(val entries: Map<String, LyricsEntry> = emptyMap())
}
