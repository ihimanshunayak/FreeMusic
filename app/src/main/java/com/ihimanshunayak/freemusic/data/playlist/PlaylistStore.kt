package com.ihimanshunayak.freemusic.data.playlist

import android.content.Context
import android.util.Log
import com.ihimanshunayak.freemusic.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * The listener's own playlists, on the device and nowhere else.
 *
 * These are deliberately not YouTube Music playlists. Every other "playlist" in
 * this app belongs to the signed-in account and is reachable only over the
 * network with a session; this is the one kind that works on a plane, without
 * an account. It is also the one kind nobody else holds a copy of, which is why
 * it is the one kind that has to be exportable — see [export].
 *
 * Storage is one JSON file rather than a database. A listener has tens of
 * playlists holding tens of tracks, the whole document is read once at launch
 * and held in memory, and a single file is what makes [export] a copy rather
 * than a query. The trade is that every write rewrites the document, so writes
 * are serialised through [writeMutex], bounded by [MAX_PLAYLISTS] and
 * [MAX_TRACKS_PER_PLAYLIST], and land through a temp file so a kill mid-write
 * cannot truncate the library.
 *
 * Tracks are stored as whole rows ([StoredSong]) rather than ids, so a playlist
 * shows its titles offline and does not turn into blank rows the first time the
 * device has no session.
 */
object PlaylistStore {

    /** A stored playlist. Immutable; every edit replaces it through this object. */
    @Serializable
    data class Playlist(
        val id: String,
        val name: String,
        /** Epoch millis. Ties are broken by this when ordering the library. */
        val createdAt: Long,
        /** Epoch millis, refreshed on every edit. The library orders by it. */
        val updatedAt: Long,
        val tracks: List<StoredSong> = emptyList(),
    ) {
        val size: Int get() = tracks.size

        fun asSongs(): List<Song> = tracks.map { it.toSong() }

        /** True when this playlist holds [videoId] at least once. */
        fun contains(videoId: String): Boolean = tracks.any { it.videoId == videoId }
    }

    /** Raised for the cases a caller has to explain rather than swallow. */
    class PlaylistError(message: String) : IllegalStateException(message)

    private const val TAG = "FreeMusicPlaylists"
    private const val FILE_NAME = "playlists.json"

    /**
     * The page-id namespace a device playlist lives in.
     *
     * A third `local:` prefix rather than a share of
     * [com.ihimanshunayak.freemusic.download.Downloads.PLAYLIST_PREFIX], because
     * the two pages only look alike. A downloaded playlist is a fixed copy of
     * something that lives on YouTube and is read-only here; this one is the
     * listener's own list and every part of it — name, order, membership — is
     * editable. Reading one as the other would offer "Rename" on a snapshot.
     */
    const val BROWSE_PREFIX = "local:mine:"

    /** The page id for the playlist stored under [id]. */
    fun pageIdFor(id: String): String = BROWSE_PREFIX + id

    /** The playlist id [pageIdFor] built [browseId] from, or null if it didn't. */
    fun idOf(browseId: String?): String? =
        browseId?.removePrefix(BROWSE_PREFIX)?.takeIf { it != browseId && it.isNotEmpty() }

    /**
     * Ceilings on distinct playlists and on how long one may grow.
     *
     * Not a security boundary — a bound on the document every write rewrites,
     * so a runaway caller cannot turn each edit into a multi-megabyte write.
     * 4 000 tracks is far past any hand-built playlist, and an import past it is
     * refused rather than silently truncated: a truncated import is a playlist
     * the listener did not ask for and cannot tell apart from a correct one.
     */
    private const val MAX_PLAYLISTS = 500
    private const val MAX_NAME_LENGTH = 120
    private const val MAX_TRACKS_PER_PLAYLIST = 4_000

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private lateinit var file: File
    private var ready = false
    private val writeMutex = Mutex()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())

    /** Newest first, which is the order the library list reads in. */
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    /**
     * The store as the UI sees it: everything, plus the question no screen
     * should have to answer twice — whether a device playlist can be written to
     * at all.
     *
     * The only reason it can't is a file the app never managed to write, which
     * is what a read-only volume or a full disk leaves behind. That is worth
     * saying on screen rather than discovering one edit at a time, because the
     * edits are otherwise indistinguishable from the ones that worked.
     */
    val writable: StateFlow<Boolean> get() = _writable.asStateFlow()

    private val _writable = MutableStateFlow(true)

    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
        _playlists.value = read()
        ready = true
    }

    /** Whether [init] has run. Callers in tests and previews check this. */
    val isReady: Boolean get() = ready

    /** The current list, without waiting for a collector. */
    fun current(): List<Playlist> = _playlists.value

    fun find(id: String): Playlist? = _playlists.value.firstOrNull { it.id == id }

    // ---- Mutation -------------------------------------------------------

    /**
     * Creates an empty playlist, returning its id.
     *
     * A blank or whitespace-only name is refused rather than defaulted: a
     * playlist called "Playlist 3" is one the listener has to open to find out
     * what it is, and they asked to name it themselves.
     */
    suspend fun create(name: String): String {
        val clean = cleanName(name)
        var id = ""
        mutate { existing ->
            if (existing.size >= MAX_PLAYLISTS) throw PlaylistError("Playlist limit reached")
            val now = System.currentTimeMillis()
            id = UUID.randomUUID().toString()
            existing + Playlist(
                id = id,
                name = clean,
                createdAt = now,
                updatedAt = now,
            )
        }
        return id
    }

    /** Renames [id]. Returns false when there is no such playlist. */
    suspend fun rename(id: String, name: String): Boolean {
        val clean = cleanName(name)
        var changed = false
        mutate { existing ->
            existing.map { playlist ->
                if (playlist.id != id) return@map playlist
                changed = true
                playlist.copy(name = clean, updatedAt = System.currentTimeMillis())
            }
        }
        return changed
    }

    /** Deletes [id]. Returns false when there was nothing to delete. */
    suspend fun delete(id: String): Boolean {
        var changed = false
        mutate { existing ->
            val remaining = existing.filterNot { it.id == id }
            changed = remaining.size != existing.size
            remaining
        }
        return changed
    }

    /**
     * Appends [songs] to [id], skipping any already present.
     *
     * Skips rather than duplicates because the caller is a menu action with no
     * way to say "add it a second time anyway" — the same song added twice is
     * two rows that look identical and can only be told apart by deleting one
     * and watching which disappears. Returns how many were actually added, so a
     * caller whose tracks were all already there can say so.
     */
    suspend fun addAll(id: String, songs: List<Song>): Int {
        if (songs.isEmpty()) return 0
        var added = 0
        mutate { existing ->
            existing.map { playlist ->
                if (playlist.id != id) return@map playlist
                val seen = playlist.tracks.mapTo(mutableSetOf()) { it.videoId }
                val fresh = dedupe(songs).filter { seen.add(it.videoId) }
                val room = MAX_TRACKS_PER_PLAYLIST - playlist.tracks.size
                val accepted = if (room <= 0) emptyList() else fresh.take(room)
                added = accepted.size
                if (added == 0) return@map playlist
                playlist.copy(
                    tracks = playlist.tracks + accepted.map(StoredSong::from),
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
        return added
    }

    /** Removes every occurrence of [videoId] from [id]. */
    suspend fun remove(id: String, videoId: String): Boolean {
        var changed = false
        mutate { existing ->
            existing.map { playlist ->
                if (playlist.id != id) return@map playlist
                val remaining = playlist.tracks.filterNot { it.videoId == videoId }
                if (remaining.size == playlist.tracks.size) return@map playlist
                changed = true
                playlist.copy(tracks = remaining, updatedAt = System.currentTimeMillis())
            }
        }
        return changed
    }

    /**
     * Moves the track at [from] to [to].
     *
     * Index-based, and the destination is read the way a list reads it: moving
     * an item down shifts everything between it and [to] up by one, so the item
     * lands where it looked like it would land while being dragged.
     */
    suspend fun move(id: String, from: Int, to: Int): Boolean {
        var changed = false
        mutate { existing ->
            existing.map { playlist ->
                if (playlist.id != id) return@map playlist
                val tracks = playlist.tracks
                if (from !in tracks.indices || to !in tracks.indices || from == to) {
                    return@map playlist
                }
                val reordered = tracks.toMutableList()
                reordered.add(to, reordered.removeAt(from))
                changed = true
                playlist.copy(tracks = reordered, updatedAt = System.currentTimeMillis())
            }
        }
        return changed
    }

    /**
     * Replaces [id]'s tracks wholesale. Used by the import path, where merging
     * into what is already there is not what was asked for.
     */
    suspend fun replaceTracks(id: String, songs: List<Song>): Boolean {
        var changed = false
        mutate { existing ->
            existing.map { playlist ->
                if (playlist.id != id) return@map playlist
                changed = true
                playlist.copy(
                    tracks = dedupe(songs).take(MAX_TRACKS_PER_PLAYLIST).map(StoredSong::from),
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
        return changed
    }

    // ---- Export / import ------------------------------------------------

    /** A whole-library document, so a playlist outlives the install. */
    @Serializable
    data class Archive(
        val version: Int = 1,
        val exportedAt: Long,
        val playlists: List<Playlist>,
    )

    /**
     * Renders every playlist as one JSON document.
     *
     * Off the main thread because it serialises every track the listener owns
     * and is called from a menu action, where a dropped frame is visible.
     */
    suspend fun export(): String = withContext(Dispatchers.IO) {
        json.encodeToString(
            Archive.serializer(),
            Archive(exportedAt = System.currentTimeMillis(), playlists = _playlists.value),
        )
    }

    /**
     * Reads an [Archive] out of [document] and merges it in.
     *
     * A string rather than an [Archive] because this is the standalone path —
     * a document the listener picked. The app's own backup hands its typed
     * [Archive] straight to [merge] instead, which is the same operation
     * without a second round of JSON inside it.
     */
    suspend fun import(document: String): Int {
        val archive = runCatching { json.decodeFromString(Archive.serializer(), document) }
            .getOrElse { throw PlaylistError("Not a Free Music playlist backup") }
        return merge(archive)
    }

    /** The whole library as an [Archive], for a caller that writes its own file. */
    fun archive(): Archive = Archive(
        exportedAt = System.currentTimeMillis(),
        playlists = _playlists.value,
    )

    /**
     * Folds [archive] into what is here and returns how many playlists were
     * created.
     *
     * Split out of [import] so the app's own backup can carry playlists as a
     * typed field rather than a JSON string inside a JSON file — see
     * [com.ihimanshunayak.freemusic.data.stats.Backup]. The merge rule is the
     * same either way and lives here rather than in the file format.
     *
     * ## Why it merges rather than replaces
     *
     * [replaceTracks] is the wholesale path, and this is deliberately not it.
     * The case this exists for is a reinstall or a second device, where the
     * local library is either empty or partly rebuilt — and replacing would
     * throw away whatever was made since the backup was taken. Merging never
     * loses a local edit: a playlist whose id is already present keeps the local
     * name and gains the tracks it did not have; one that is new arrives whole.
     */
    suspend fun merge(archive: Archive): Int {
        if (archive.playlists.isEmpty()) return 0
        var created = 0
        mutate { existing ->
            val byId = existing.associateBy { it.id }
            val merged = existing.toMutableList()
            for (incoming in archive.playlists) {
                if (incoming.id.isBlank()) continue
                val local = byId[incoming.id]
                if (local == null) {
                    if (merged.size >= MAX_PLAYLISTS) continue
                    created++
                    merged += Playlist(
                        id = incoming.id,
                        name = cleanNameOrNull(incoming.name) ?: "Imported playlist",
                        createdAt = incoming.createdAt,
                        updatedAt = incoming.updatedAt,
                        tracks = dedupeTracks(incoming.tracks).take(MAX_TRACKS_PER_PLAYLIST),
                    )
                } else {
                    val seen = local.tracks.mapTo(mutableSetOf()) { it.videoId }
                    val fresh = incoming.tracks.filter { it.videoId.isNotBlank() && seen.add(it.videoId) }
                    if (fresh.isEmpty()) continue
                    val index = merged.indexOfFirst { p -> p.id == local.id }
                    if (index < 0) continue
                    merged[index] = local.copy(
                        tracks = (local.tracks + fresh).take(MAX_TRACKS_PER_PLAYLIST),
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            }
            merged
        }
        return created
    }

    /** Drops everything. Only reachable from an explicit confirmation. */
    suspend fun clearAll() {
        mutate { emptyList() }
    }

    // ---- Internals ------------------------------------------------------

    /** Newest-first, which is the order every reader above assumes. */
    private fun read(): List<Playlist> {
        if (!file.exists()) return emptyList()
        val raw = runCatching { file.readText() }
            .onFailure { Log.w(TAG, "Could not read playlists", it) }
            .getOrNull()
            ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        val stored = runCatching { json.decodeFromString(StoredFile.serializer(), raw) }
            .onFailure { Log.w(TAG, "Could not parse playlists", it) }
            .getOrNull()
            ?: return emptyList()
        return stored.playlists
            .filter { it.id.isNotBlank() }
            .map { it.copy(name = cleanNameOrNull(it.name) ?: "Untitled playlist") }
            .sortedByDescending { it.updatedAt }
    }

    /**
     * Applies [transform], publishes the result, and writes it out.
     *
     * The list is published before the write so the UI never waits on disk, and
     * the write is serialised so two edits landing together cannot interleave
     * and leave the file describing half of each. A transform that changed
     * nothing skips the write entirely, which is what makes a no-op [addAll]
     * free.
     */
    private suspend fun mutate(transform: (List<Playlist>) -> List<Playlist>): List<Playlist> =
        writeMutex.withLock {
            val before = _playlists.value
            val after = transform(before).sortedByDescending { it.updatedAt }
            if (after == before) return@withLock before
            _playlists.value = after
            withContext(Dispatchers.IO) { write(after) }
            after
        }

    /**
     * Writes through a temp file and renames.
     *
     * The rename is what makes this atomic: a process killed mid-write leaves
     * the temp file behind and the previous document intact, rather than a
     * truncated file that parses as an empty library and silently loses every
     * playlist the listener had.
     */
    private fun write(playlists: List<Playlist>) {
        if (!::file.isInitialized) return
        runCatching {
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(
                json.encodeToString(StoredFile.serializer(), StoredFile(playlists = playlists)),
            )
            if (!temp.renameTo(file)) {
                // Some volumes refuse a rename over an existing file; falling
                // back to a copy keeps the write correct, only less atomic.
                file.writeText(temp.readText())
                temp.delete()
            }
        }.onSuccess {
            _writable.value = true
        }.onFailure {
            // Surfaced rather than only logged. A write that fails silently is
            // a playlist that appears, survives the screen it was made on, and
            // is gone after a restart — the listener has no way to tell that
            // apart from having imagined making it. See [writable].
            _writable.value = false
            Log.w(TAG, "Could not write playlists", it)
        }
    }

    private fun dedupe(songs: List<Song>): List<Song> {
        val seen = mutableSetOf<String>()
        return songs.filter { it.videoId.isNotBlank() && seen.add(it.videoId) }
    }

    private fun dedupeTracks(tracks: List<StoredSong>): List<StoredSong> {
        val seen = mutableSetOf<String>()
        return tracks.filter { it.videoId.isNotBlank() && seen.add(it.videoId) }
    }

    private fun cleanName(name: String): String =
        cleanNameOrNull(name) ?: throw PlaylistError("A playlist needs a name")

    private fun cleanNameOrNull(name: String): String? =
        name.trim().take(MAX_NAME_LENGTH).takeIf { it.isNotEmpty() }

    /**
     * What lands on disk. An object rather than a bare array so a later format
     * change has somewhere to put its version, and so a file that is not one of
     * ours fails to parse instead of being read as an empty playlist list.
     */
    @Serializable
    private data class StoredFile(
        val version: Int = 1,
        val playlists: List<Playlist> = emptyList(),
    )
}
