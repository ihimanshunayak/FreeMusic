// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - listening history.
//
// NAME
//     ListeningStats.kt - the replay log, its aggregation and its summaries.
//
// DESCRIPTION
//     Ports the Android app's listening statistics. The Android version stores
//     buckets in SharedPreferences and prunes them on a schedule; here the same
//     buckets are written as JSON under the app's data directory, which is both
//     easier to inspect when something looks wrong and survives a reinstall.
//
//     The model deliberately keeps *every* play rather than counters. "How many
//     times did I play this" is a question that can be answered from plays, but
//     "did I play this more this month than last" cannot be answered from a
//     counter that was never dated - and the second question is the one a
//     statistics screen exists to answer.
//
// RESPONSIBILITIES
//     - Record a play once a track has been listened to long enough to count.
//     - Aggregate plays into songs, albums, artists and genres.
//     - Produce per-period summaries (week, month, year, all time).
//     - Persist and reload the log, pruning what is past its retention.
//
// DEPENDENCIES
//     - `kotlinx.serialization` for the on-disk shape.
//
// INTEGRATION NOTES
//     - A play counts when the track reaches [ListeningRecorder.MIN_PLAY_FRACTION]
//       or [ListeningRecorder.MIN_PLAY_SECONDS], matching how every scrobbler
//       defines a listen. Recording on track *start* would inflate the history
//       with everything the user skipped past.
//     - The recorder is fed by the player's position poll, so it needs no
//       event wiring of its own beyond a start/stop pair per track.

package com.ihimanshunayak.freemusic.desktop.data.stats

import com.ihimanshunayak.freemusic.desktop.model.Track
import com.ihimanshunayak.freemusic.desktop.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One completed listen. */
@Serializable
data class PlayRecord(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    /** Milliseconds since the epoch; a play has no timezone of its own. */
    val playedAtMs: Long,
    val listenedSeconds: Int = 0,
    /** ISO-8601 date, denormalised so a period query does not re-derive it. */
    val date: String,
) {
    /** The local date this play belongs to, for day-level grouping. */
    val localDate: LocalDate? get() = runCatching { LocalDate.parse(date) }.getOrNull()
}

/** A ranking entry: how many plays, and how much time they took. */
@Serializable
data class RankedEntry(
    val name: String,
    val plays: Int,
    val seconds: Long,
    /** Present for songs, null for aggregates. */
    val trackId: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
)

/** Everything a summary screen draws, computed once per period. */
data class ReplaySummary(
    val period: ReplayPeriod,
    val totalPlays: Int,
    val totalSeconds: Long,
    val distinctSongs: Int,
    val distinctArtists: Int,
    val distinctAlbums: Int,
    val topSongs: List<RankedEntry>,
    val topArtists: List<RankedEntry>,
    val topAlbums: List<RankedEntry>,
    /** Play counts per day, oldest first, for the activity chart. */
    val dailyPlays: List<Pair<LocalDate, Int>>,
) {
    val totalHours: Double get() = totalSeconds / 3600.0
    val averagePerDay: Double
        get() = if (dailyPlays.isEmpty()) 0.0 else totalPlays.toDouble() / dailyPlays.size

    companion object {
        val Empty = ReplaySummary(
            period = ReplayPeriod.ALL_TIME,
            totalPlays = 0,
            totalSeconds = 0,
            distinctSongs = 0,
            distinctArtists = 0,
            distinctAlbums = 0,
            topSongs = emptyList(),
            topArtists = emptyList(),
            topAlbums = emptyList(),
            dailyPlays = emptyList(),
        )
    }
}

/** The windows the statistics screen offers. */
enum class ReplayPeriod(val label: String) {
    WEEK("Last 7 days"),
    MONTH("Last 30 days"),
    QUARTER("Last 90 days"),
    YEAR("Last year"),
    ALL_TIME("All time"),
    ;

    /** How many days back the window reaches, or null for all time. */
    val days: Int?
        get() = when (this) {
            WEEK -> 7
            MONTH -> 30
            QUARTER -> 90
            YEAR -> 365
            ALL_TIME -> null
        }
}

/**
 * Decides when a listen has happened, and records it once.
 *
 * The threshold is deliberately the same one the scrobblers use: half the track
 * or four minutes, whichever comes first. Anything shorter counts as a skip, and
 * a history full of skipped intros is worse than no history at all.
 */
class ListeningRecorder(private val stats: ListeningStats) {

    private var currentTrack: Track? = null
    private var listenedSeconds: Int = 0

    /**
     * Seconds accumulated on the current track.
     *
     * Exposed so the scrobbler can advance by a delta rather than re-deriving
     * elapsed time from the position, which would double-count a seek.
     */
    val listenedSecondsPublic: Int get() = listenedSeconds
    private var lastPositionMs: Long = 0
    private var recorded: Boolean = false

    /** Called when a new track starts, with the previous one's final position. */
    fun onTrackStarted(track: Track) {
        // A track change while the previous one was still short of the
        // threshold is a skip, and a skip is not recorded.
        currentTrack = track
        listenedSeconds = 0
        lastPositionMs = 0
        recorded = false
    }

    /**
     * Called from the position poll.
     *
     * Elapsed time is measured as the *increase* in position, so seeking forward
     * does not inflate the count and pausing does not inflate it either. A
     * backwards jump is a seek and contributes nothing.
     */
    fun onPosition(positionMs: Long, durationMs: Long) {
        val track = currentTrack ?: return
        val delta = positionMs - lastPositionMs
        if (delta in 1..MAX_PLAUSIBLE_DELTA_MS) {
            listenedSeconds += (delta / 1000).toInt()
        }
        lastPositionMs = positionMs

        if (recorded) return
        val durationSeconds = (durationMs / 1000).toInt()

        // A stream of unknown length cannot satisfy the fraction rule - half of
        // nothing is nothing - but the four-minute rule stands on its own. Bailing
        // out on an unknown duration instead, as this once did, meant a live stream
        // never reached the history at all.
        val enoughTime = listenedSeconds >= MIN_PLAY_SECONDS
        val enoughFraction = durationSeconds > 0 &&
            listenedSeconds >= (durationSeconds * MIN_PLAY_FRACTION).toInt()

        if (enoughTime || enoughFraction) {
            recorded = true
            stats.record(track, listenedSeconds)
        }
    }

    /** Called when playback stops or the track changes, to flush a near-miss. */
    fun onStopped() {
        currentTrack = null
        lastPositionMs = 0
        listenedSeconds = 0
    }

    companion object {
        /** Half the track counts as a listen, matching every scrobbler. */
        const val MIN_PLAY_FRACTION = 0.5

        /** Or four minutes, whichever comes first - for long tracks and mixes. */
        const val MIN_PLAY_SECONDS = 240

        /**
         * Anything larger than this in one poll is a seek rather than elapsed
         * playback. The poll runs about four times a second, so even a heavily
         * throttled one cannot legitimately advance more than this.
         */
        private const val MAX_PLAUSIBLE_DELTA_MS = 5_000L
    }
}

/**
 * The play log, its persistence and its aggregation.
 */
class ListeningStats(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val _plays = MutableStateFlow<List<PlayRecord>>(emptyList())
    val plays: StateFlow<List<PlayRecord>> = _plays.asStateFlow()

    /** Monotonic counter so the UI can recompute summaries when something changed. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Serialises writes so a burst of plays cannot interleave two saved files. */
    private val writeLock = Any()

    suspend fun load() = withContext(Dispatchers.IO) {
        val loaded = runCatching {
            if (!file.isFile) emptyList()
            else json.decodeFromString<List<PlayRecord>>(file.readText())
        }.onFailure { Log.w("stats load failed: ${it.message}", tag = "stats") }.getOrDefault(emptyList())

        _plays.value = prune(loaded)
        _revision.value = _revision.value + 1
        Log.i("stats loaded: ${_plays.value.size} plays", tag = "stats")
    }

    /**
     * Records a play and saves.
     *
     * Saving on every play rather than on exit is deliberate: a desktop app is
     * closed by a window manager more often than it is quit cleanly, and losing
     * a session's history to a kill is worse than writing a small file often.
     */
    fun record(track: Track, listenedSeconds: Int) {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val record = PlayRecord(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            playedAtMs = now.toEpochMilli(),
            listenedSeconds = listenedSeconds,
            date = now.atZone(zone).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
        )
        _plays.value = prune(_plays.value + record)
        _revision.value = _revision.value + 1
        save()
    }

    /** Forgets everything, used by the settings screen's reset. */
    fun clear() {
        _plays.value = emptyList()
        _revision.value = _revision.value + 1
        save()
    }

    /** Imported records are merged rather than replacing, so a restore is additive. */
    fun importAll(records: List<PlayRecord>) {
        val existing = _plays.value.toMutableList()
        val known = existing.mapTo(HashSet()) { it.trackId to it.playedAtMs }
        records.forEach { record ->
            if (known.add(record.trackId to record.playedAtMs)) existing += record
        }
        _plays.value = prune(existing)
        _revision.value = _revision.value + 1
        save()
    }

    /** Every play inside a period, oldest first. */
    fun playsIn(period: ReplayPeriod): List<PlayRecord> {
        val days = period.days ?: return _plays.value
        val cutoff = LocalDate.now().minusDays(days.toLong() - 1)
        return _plays.value.filter { record ->
            val date = record.localDate ?: return@filter false
            !date.isBefore(cutoff)
        }
    }

    /**
     * Aggregates [period] into everything a summary screen needs.
     *
     * Computed on demand rather than cached, because the period is changed far
     * less often than a play is recorded and a stale summary is a visible bug.
     * The input is bounded by the retention window, so this stays cheap.
     */
    fun summarise(period: ReplayPeriod, artworkFor: ((String) -> String?)? = null): ReplaySummary {
        val window = playsIn(period)
        if (window.isEmpty()) return ReplaySummary.Empty.copy(period = period)

        val bySong = window.groupBy { it.trackId }
        val byArtist = window.groupBy { it.artist.ifBlank { "Unknown artist" } }
        val byAlbum = window.groupBy { it.album?.takeIf { name -> name.isNotBlank() } ?: "Singles" }

        fun rank(
            groups: Map<String, List<PlayRecord>>,
            withArtwork: Boolean,
        ): List<RankedEntry> = groups.entries
            .map { (name, records) ->
                val sample = records.first()
                RankedEntry(
                    name = if (!withArtwork) name else sample.title,
                    plays = records.size,
                    seconds = records.sumOf { it.listenedSeconds.toLong() },
                    trackId = if (withArtwork) sample.trackId else null,
                    artist = sample.artist,
                    album = sample.album,
                    artworkUrl = if (withArtwork) artworkFor?.invoke(sample.trackId) else null,
                )
            }
            .sortedWith(compareByDescending<RankedEntry> { it.plays }.thenBy { it.name.lowercase() })

        // A day with no plays still belongs on the chart: a gap in a listening
        // history is information, and omitting it makes a quiet week look busy.
        val days = period.days
        val daily = if (days == null) {
            window.groupBy { it.date }
                .entries
                .mapNotNull { (date, records) -> runCatching { LocalDate.parse(date) }.getOrNull()?.let { it to records.size } }
                .sortedBy { it.first }
        } else {
            val cutoff = LocalDate.now().minusDays(days.toLong() - 1)
            val counts = window.groupingBy { it.date }.eachCount()
            (0 until days).map { offset ->
                val date = cutoff.plusDays(offset.toLong())
                date to (counts[date.format(DateTimeFormatter.ISO_LOCAL_DATE)] ?: 0)
            }
        }

        return ReplaySummary(
            period = period,
            totalPlays = window.size,
            totalSeconds = window.sumOf { it.listenedSeconds.toLong() },
            distinctSongs = bySong.size,
            distinctArtists = byArtist.size,
            distinctAlbums = byAlbum.size,
            topSongs = rank(bySong, withArtwork = true).take(TOP_COUNT),
            topArtists = rank(byArtist, withArtwork = false).take(TOP_COUNT),
            topAlbums = rank(byAlbum, withArtwork = false).take(TOP_COUNT),
            dailyPlays = daily,
        )
    }

    /**
     * Drops plays older than the retention window.
     *
     * Bounded on purpose: an unbounded history grows without limit and the whole
     * file is parsed at startup, so a decade of plays would eventually be a
     * visible startup cost for statistics nobody scrolls back to.
     */
    private fun prune(records: List<PlayRecord>): List<PlayRecord> {
        val cutoff = LocalDate.now().minusDays(RETENTION_DAYS)
        return records.filter { record ->
            val date = record.localDate ?: return@filter false
            !date.isBefore(cutoff)
        }
    }

    private fun save() {
        val snapshot = _plays.value
        synchronized(writeLock) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString(snapshot))
            }.onFailure { Log.w("stats save failed: ${it.message}", tag = "stats") }
        }
    }

    private companion object {
        /** Two years: long enough for a "this time last year" comparison. */
        const val RETENTION_DAYS = 730L

        /** Rows shown per ranking before the UI's own scrolling takes over. */
        const val TOP_COUNT = 50
    }
}
