// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - user settings.
//
// NAME
//     Settings.kt - the settings model, its enums, and the JSON-backed store.
//
// DESCRIPTION
//     This file is the single source of truth for everything the user can
//     configure. Each screen reads a field from [Settings] rather than holding
//     its own copy of a preference, which is what lets the Windows build keep
//     the same semantics as the Android build for a setting named the same way.
//
//     Stored as JSON next to the logs, not in the registry, so a user can
//     inspect, back up, hand-edit or delete their configuration without a tool.
//     Every field carries a default, so a file written by an older build is
//     still readable: missing keys fall back, unknown keys are ignored.
//
// RESPONSIBILITIES
//     - Model every user-visible preference, grouped by the screen owning it.
//     - Persist them atomically, so a crash cannot leave a half-written file.
//     - Expose changes as a [StateFlow] so the UI recomposes on a write.
//
// DEPENDENCIES
//     - kotlinx.serialization for the on-disk format.
//     - [AppPaths] for the file location and default download folder.
//
// INTEGRATION NOTES
//     - Never read the file directly; go through [SettingsStore.flow] or
//       [SettingsStore.current] so a running app sees the live value.
//     - Adding a field is always safe. *Renaming* one is not: it silently drops
//       the user's saved value, so prefer adding a field and migrating.
//     - Enums are serialised by name. Reordering constants is safe; renaming one
//       is a breaking change to the file format.

package com.ihimanshunayak.freemusic.desktop.data

import com.ihimanshunayak.freemusic.desktop.model.RepeatMode
import com.ihimanshunayak.freemusic.desktop.data.source.AddonSource
import com.ihimanshunayak.freemusic.desktop.util.AppPaths
import com.ihimanshunayak.freemusic.desktop.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

// ---------------------------------------------------------------------------
// ## SECTION: Constants shared with the audio, lyrics and library layers
// ---------------------------------------------------------------------------

/** Ten bands, matching the ISO octave centres libVLC's equalizer exposes. */
const val EQUALIZER_BAND_COUNT = 10

/**
 * The lyric providers enabled on a fresh install.
 *
 * Ordered by hit rate, not by completeness: LRCLIB is first because it is the
 * only provider that is both free and needs no account, so a first run gets
 * lyrics without the user configuring anything.
 */
val DEFAULT_LYRIC_SOURCES: List<String> = listOf(
    "LRCLIB",
    "YOUTUBE_MUSIC",
    "PAXSENIX",
    "BETTER_LYRICS",
    "SIMPMUSIC",
    "UNISON",
    "LYRICS_PLUS",
    "MUSIXMATCH",
    "KUGOU",
    "GENIUS",
)

// ---------------------------------------------------------------------------
// ## SECTION: The settings model
// ---------------------------------------------------------------------------

/**
 * Every preference the app persists.
 *
 * Grouped by the screen that owns the setting rather than alphabetically: a
 * hundred-field list is only navigable if a field sits where the reader expects
 * to find it.
 */
@Serializable
data class Settings(

    // -- ## SUBSECTION: Playback --------------------------------------------

    /** Target stream bitrate for YouTube Music resolution. */
    val audioQuality: AudioQuality = AudioQuality.HIGH,

    /** How the audio device is opened. See [OutputBackend]. */
    val outputBackend: OutputBackend = OutputBackend.AUTO,

    /** 0..1, applied by libVLC's own software volume, not the OS mixer. */
    val volume: Float = 0.8f,

    val muted: Boolean = false,

    /** Remembered so unmuting returns to the level the user actually chose. */
    val lastVolumeBeforeMute: Float = 0.8f,

    val repeatMode: RepeatMode = RepeatMode.OFF,

    val shuffle: Boolean = false,

    /** 0 disables crossfade entirely; anything else fades tail into head. */
    val crossfadeSeconds: Int = 0,

    /**
     * Lets the engine choose a crossfade per track pair.
     *
     * Only meaningful while [crossfadeSeconds] is 0, because an explicit
     * duration is the user overriding the automatic decision.
     */
    val smartFade: Boolean = false,

    val playbackSpeed: Float = 1.0f,

    /**
     * ReplayGain-style levelling, computed per track from the decoded samples.
     *
     * Costs a decode pass, so it is off by default.
     */
    val normalizeVolume: Boolean = false,

    /** Skips leading and trailing near-silence once a track is analysed. */
    val skipSilence: Boolean = false,

    /** Continue with a related track when the queue drains. */
    val autoplay: Boolean = true,

    /** Excludes podcasts and covers from autoplay and from search results. */
    val musicOnly: Boolean = false,

    // -- ## SUBSECTION: Themes and appearance -------------------------------

    val theme: ThemePreference = ThemePreference.SYSTEM,

    /** Derives the accent from the current track's artwork. */
    val dynamicAccent: Boolean = true,

    /** Pins the accent to the brand pink instead of following the artwork. */
    val brandAccent: Boolean = false,

    /** Disables the animated material transitions the shell uses. */
    val reduceAnimation: Boolean = false,

    /** Skips blur passes, for machines without GPU cycles to spare. */
    val reduceBlur: Boolean = false,

    /** Draws the DWM Mica backdrop; off means an opaque window. */
    val micaBackdrop: Boolean = true,

    /** List, grid or compact rows for each library surface. */
    val localMusicViewType: LibraryViewType = LibraryViewType.LIST,
    val downloadedMusicViewType: LibraryViewType = LibraryViewType.LIST,
    val homeRecentsViewType: LibraryViewType = LibraryViewType.GRID,

    /** Grouping and ordering for the library surfaces. */
    val librarySort: LibrarySort = LibrarySort.RECENTLY_ADDED,
    val localMusicSort: LocalMusicSort = LocalMusicSort.TITLE,
    val downloadedMusicSort: LocalMusicSort = LocalMusicSort.RECENTLY_ADDED,

    /** The screen the window restores to on launch. */
    val lastPlayerScreen: LastPlayerScreen = LastPlayerScreen.HOME,

    // -- ## SUBSECTION: Equalizer -------------------------------------------

    val equalizerEnabled: Boolean = false,

    /** `DYNAMIC` drives the bands from [equalizerToneX]/[equalizerToneY]. */
    val equalizerMode: EqualizerMode = EqualizerMode.MANUAL,

    /** Tone-pad coordinates, both -1..1. */
    val equalizerToneX: Float = 0f,
    val equalizerToneY: Float = 0f,

    /** True once the user has moved the pad, so the bands should follow it. */
    val equalizerFocused: Boolean = false,

    /** -1 fully left, +1 fully right. */
    val equalizerBalance: Float = 0f,

    val equalizerBands: List<Float> = List(EQUALIZER_BAND_COUNT) { 0f },

    /** The preset the bands last matched, for the row's label. */
    val equalizerPreset: String = "FLAT",

    /** Headroom the automatic preamp keeps, so a boosted curve cannot clip. */
    val equalizerHeadroomDb: Float = 1.5f,

    /** Curves the user named and saved. */
    val savedEqualizerCurves: List<SavedCurve> = emptyList(),

    // -- ## SUBSECTION: Lyrics ----------------------------------------------

    val syncedLyrics: Boolean = true,

    /** Providers that may be queried, in [lyricsSourceOrder] order. */
    val lyricsSources: List<String> = DEFAULT_LYRIC_SOURCES,

    /** Manual override of the provider order; empty means "use the default". */
    val lyricsSourceOrder: List<String> = emptyList(),

    /** Word-level timing when a provider supplies it, not line-level. */
    val prioritizeSyllableSync: Boolean = true,

    /** Positive values delay the lyrics, matching the Android behaviour. */
    val lyricsOffsetMs: Int = 0,

    /** Blurs the lyric lines either side of the active one. */
    val lyricsBlur: Boolean = true,

    /** Language code for translated lyrics; blank disables the second line. */
    val translationLanguage: String = "",

    /** Your own PaxSenix key, since the bundled one is rate limited. */
    val paxSenixApiKey: String = "",

    // -- ## SUBSECTION: Canvas ----------------------------------------------

    /**
     * Animated cover loops.
     *
     * Windows has no `MediaStore` to read a canvas from, so this uses the
     * provider's own loop when one exists and otherwise animates the still
     * cover.
     */
    val canvasMode: CanvasMode = CanvasMode.AUTO,

    val fullBleedArtwork: Boolean = true,

    /** Puts an animated gradient behind the player instead of a flat fill. */
    val meshGradient: Boolean = true,

    /** Hides the canvas after this many idle seconds; 0 never hides. */
    val canvasAutoHideSeconds: Int = 20,

    // -- ## SUBSECTION: Local and remote libraries --------------------------

    /** Manually added folders, including network paths. */
    val localMusicFolders: List<String> = emptyList(),

    val webdavUrl: String = "",
    val webdavUsername: String = "",
    val webdavPassword: String = "",

    val smbHost: String = "",
    val smbShare: String = "",
    val smbBasePath: String = "",
    val smbUsername: String = "",
    val smbPassword: String = "",

    // -- ## SUBSECTION: Downloads -------------------------------------------

    val downloadQuality: DownloadQuality = DownloadQuality.HIGH,

    /** Refuses to start a download on a metered link. */
    val wifiOnlyDownloads: Boolean = false,

    /** Path a finished download is copied to, so it can land on a NAS. */
    val exportDownloadsTo: String = "",

    val downloadsDirectory: String = "",

    /** Writes tags, cover art and lyrics into the downloaded file. */
    val tagDownloads: Boolean = true,

    /** Also writes an `.lrc` sidecar next to the audio file. */
    val writeLrcSidecar: Boolean = true,

    /** Concurrent downloads; 1 keeps a slow link usable for streaming. */
    val downloadConcurrency: Int = 2,

    // -- ## SUBSECTION: Scrobbling ------------------------------------------

    val lastfmEnabled: Boolean = false,
    val lastfmUsername: String = "",
    val lastfmSessionKey: String = "",
    val lastfmApiKey: String = "",
    val lastfmSecret: String = "",
    val lastfmEndpoint: String = "https://ws.audioscrobbler.com/2.0/",
    val lastfmScrobbleEnabled: Boolean = true,
    val lastfmNowPlaying: Boolean = true,
    val lastfmPrimaryArtistOnly: Boolean = false,

    val listenBrainzEnabled: Boolean = false,
    val listenBrainzToken: String = "",
    val listenBrainzPrimaryArtistOnly: Boolean = false,

    /** Ignore anything shorter than this, matching Last.fm's own rule. */
    val scrobbleMinDurationSeconds: Int = 30,

    /** Submit once this share of the track has played... */
    val scrobbleDelayPercent: Int = 50,

    /** ...or after this many seconds, whichever comes first. */
    val scrobbleDelaySeconds: Int = 240,

    // -- ## SUBSECTION: Discord ---------------------------------------------

    val discordRpcEnabled: Boolean = false,
    val discordShowAudioQuality: Boolean = false,
    val discordAdvancedMode: Boolean = false,
    val discordActivityType: DiscordActivityType = DiscordActivityType.LISTENING,
    val discordActivityName: String = "",
    val discordIdleWhenPaused: Boolean = true,
    val discordButton1Text: String = "",
    val discordButton1Visible: Boolean = true,
    val discordButton2Text: String = "",
    val discordButton2Visible: Boolean = false,

    // -- ## SUBSECTION: Listen Together -------------------------------------

    val partyDisplayName: String = "",
    val partyInviteCode: String = "",
    val partyAllowGuestControl: Boolean = true,

    /** The room server this client connects to. No server is bundled with the app. */
    val partyServerUrl: String = "",

    // -- ## SUBSECTION: Sources ---------------------------------------------

    /** Extra stream sources, in the order the resolver should try them. */
    val addonSourceOrder: List<String> = emptyList(),

    /** Script addons the user installed, kept verbatim so they survive restarts. */
    val addons: List<AddonRecord> = emptyList(),

    /** A source returning a worse stream than the built-in one is skipped. */
    val addonPreferHigherQuality: Boolean = true,

    val addonAllowHttp: Boolean = false,

    // -- ## SUBSECTION: Advanced and diagnostics ----------------------------

    val language: String = "en",
    val region: String = "US",
    val showDiagnostics: Boolean = false,
    val showNerdStats: Boolean = false,
    val highPerformanceMode: Boolean = false,

    /** Cache budget for downloaded-if-missing audio, in bytes. */
    val audioCacheLimitBytes: Long = 2L * 1024 * 1024 * 1024,

    /** How many entries the search and play history keep. */
    val historyLimit: Int = 500,

    /** Deletes the on-disk cache when the app exits. */
    val clearCacheOnExit: Boolean = false,

    /**
     * Sleep timer, in seconds, or zero for none.
     *
     * Stored rather than held in memory because the Android app treats it as a
     * preference and a user who set thirty minutes expects it to survive a
     * restart. The player counts it down.
     */
    val sleepTimerSeconds: Int = 0,

    // -- ## SUBSECTION: Library data ----------------------------------------

    val pinnedPlaylists: List<String> = emptyList(),
    val playlists: List<PlaylistRecord> = emptyList(),
    val searchHistory: List<String> = emptyList(),
) {
    /** Where downloads actually land, falling back to the app's own folder. */
    val effectiveDownloadsDirectory: String
        get() = downloadsDirectory.ifBlank { AppPaths.downloadsDir }

    /** Where exports are copied, falling back to the plain downloads folder. */
    val effectiveExportDirectory: String
        get() = exportDownloadsTo.ifBlank { AppPaths.downloadsDir }

    /** True when a provider list has been hand-ordered. */
    val hasCustomLyricsOrder: Boolean get() = lyricsSourceOrder.isNotEmpty()

    /**
     * The provider order actually used for a lookup.
     *
     * The enabled set is filtered against the desired order rather than sorted
     * by it, because a sort would leave a disabled provider's position occupied
     * and a filter keeps the remaining order stable.
     */
    val effectiveLyricsOrder: List<String>
        get() {
            val enabled = lyricsSources.toSet()
            val preferred = lyricsSourceOrder.filter { it in enabled }.ifEmpty { lyricsSources }
            val rest = lyricsSources.filter { it !in preferred }
            return preferred + rest
        }
}

// ---------------------------------------------------------------------------
// ## SECTION: Enums
// ---------------------------------------------------------------------------

@Serializable
enum class AudioQuality(val label: String, val approxKbps: Int) {
    LOW("Low (64 kbps)", 64),
    MEDIUM("Medium (128 kbps)", 128),
    HIGH("High (256 kbps)", 256),
    HIGHEST("Highest available", 9999),
}

@Serializable
enum class ThemePreference(val label: String) {
    SYSTEM("System"), LIGHT("Light"), DARK("Dark")
}

/**
 * Which libVLC audio output module to open.
 *
 * [AUTO] lets libVLC pick, which is almost always right. The others exist
 * because a user with an external DAC may prefer WASAPI's shared mode, which
 * resamples less on some drivers than the legacy DirectSound path, and because
 * "the sound changed when I touched this" is only answerable if the setting
 * exists to be put back.
 */
@Serializable
enum class OutputBackend(val label: String, val module: String?) {
    AUTO("Automatic", null),
    DIRECT_SOUND("DirectSound", "directsound"),
    WASAPI("WASAPI", "mmdevice"),
    WAVEOUT("WaveOut (most compatible)", "waveout"),
}

@Serializable
enum class EqualizerMode(val label: String) {
    /** Bands are derived from one point on the tone pad. */
    DYNAMIC("Tone pad"),

    /** Bands are set one at a time. */
    MANUAL("Manual"),
}

@Serializable
enum class DownloadQuality(val label: String, val approxKbps: Int) {
    ORIGINAL("Original (no re-encode)", 9999),
    HIGH("High (256 kbps)", 256),
    MEDIUM("Medium (128 kbps)", 128),
    LOW("Low (64 kbps)", 64),
}

@Serializable
enum class LibrarySort(val label: String) {
    RECENTLY_ADDED("Recently added"),
    RECENTLY_PLAYED("Recently played"),
    ALPHABETICAL("A to Z"),
    ARTIST("Artist"),
    MOST_PLAYED("Most played"),
}

@Serializable
enum class LocalMusicSort(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    DURATION("Duration"),
    RECENTLY_ADDED("Recently added"),
    FILE_NAME("File name"),
}

@Serializable
enum class LibraryViewType(val label: String) {
    LIST("List"), GRID("Grid"), COMPACT("Compact")
}

@Serializable
enum class LastPlayerScreen(val label: String) {
    HOME("Home"),
    LIBRARY("Library"),
    SEARCH("Search"),
    EXPLORE("Explore"),
    STATISTICS("Statistics"),
}

@Serializable
enum class CanvasMode(val label: String) {
    /** Use a loop when the source has one, otherwise animate the still cover. */
    AUTO("Automatic"),

    /** Only animate when the source really has a loop. */
    SOURCE_ONLY("Source loops only"),

    OFF("Off"),
}

@Serializable
enum class DiscordActivityType(val label: String, val code: Int) {
    /** "Listening to Free Music" - the correct activity for a player. */
    LISTENING("Listening", 2),
    PLAYING("Playing", 0),
    WATCHING("Watching", 3),
}

/** A playlist the user built, stored inline so it travels with their settings. */
@Serializable
data class PlaylistRecord(
    val id: String,
    val name: String,
    val trackIds: List<String> = emptyList(),
    val createdAtMs: Long = 0L,
    val pinned: Boolean = false,
) {
    val size: Int get() = trackIds.size
}

/** A named equalizer curve, mirroring the audio layer's own type on disk. */
@Serializable
data class SavedCurve(
    val name: String,
    val gainsDb: List<Float>,
    val preampDb: Float = 0f,
    val padX: Float = 0f,
    val padY: Float = 0f,
)

/**
 * A user-installed source addon as it is persisted.
 *
 * The script is kept verbatim because it *is* the addon: there is no registry to
 * re-fetch it from, and a user who pasted sixty lines of JavaScript expects them
 * to still be there tomorrow.
 */
@Serializable
data class AddonRecord(
    val id: String,
    val name: String,
    val script: String,
    val version: String = "1.0.0",
    val qualityRank: Int = 40,
    val enabled: Boolean = true,
    val supportsSearch: Boolean = false,
) {
    /** Converts to the runtime type the resolver works with. */
    fun toAddonSource(): AddonSource = AddonSource(
        id = id,
        name = name,
        script = script,
        version = version,
        qualityRank = qualityRank,
    )
}

// ---------------------------------------------------------------------------
// ## SECTION: The store
// ---------------------------------------------------------------------------

/**
 * Settings holder with a JSON-backed store.
 *
 * Reads are channel-agnostic: [flow] is what the UI collects, and every mutation
 * both updates the flow and saves, so the window reflects a change immediately
 * and the file catches up without the caller thinking about it.
 *
 * The save is synchronous. That is a deliberate trade: the file is a few
 * kilobytes, writes are rare and user-initiated, and an asynchronous writer
 * would need a shutdown flush to avoid losing the last change - which is
 * exactly the change a user makes right before closing the window.
 */
class SettingsStore(private val file: File = File(AppPaths.settingsFile)) {

    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<Settings> = _flow.asStateFlow()

    val current: Settings get() = _flow.value

    @Synchronized
    fun update(transform: (Settings) -> Settings): Settings {
        val next = transform(_flow.value)
        _flow.value = next
        save(next)
        return next
    }

    /** Restores every value to its default and persists the result. */
    @Synchronized
    fun reset(): Settings = update { Settings() }

    /** Replaces the whole model, for a settings import. */
    @Synchronized
    fun replace(settings: Settings): Settings = update { settings }

    /** The path this store reads and writes, for the diagnostics screen. */
    val path: String get() = file.absolutePath

    private fun load(): Settings = runCatching {
        if (!file.exists()) return Settings()
        val text = file.readText()
        if (text.isBlank()) return Settings()
        Http.json.decodeFromString(Settings.serializer(), text)
    }.onFailure {
        // A corrupt file is not worth losing the app over; defaults are safe and
        // the original is left untouched so it can be inspected.
        Log.w("could not read settings (${it.message}); using defaults", tag = "settings")
    }.getOrDefault(Settings())

    /**
     * Writes through a temporary file and moves it into place.
     *
     * A direct write is not atomic on Windows: a crash between the truncate and
     * the final byte loses every setting the user ever changed. The rename is
     * atomic on NTFS, so the file a reader sees is always a complete version.
     */
    @Synchronized
    private fun save(settings: Settings) {
        runCatching {
            val parent = file.parentFile
            parent?.mkdirs()
            val temp = File(parent, "${file.name}.tmp")
            temp.writeText(Http.json.encodeToString(Settings.serializer(), settings))
            if (!temp.renameTo(file)) {
                // A reader holding the file open makes the rename fail; a direct
                // write still persists the change, it is just less atomic.
                file.writeText(temp.readText())
                temp.delete()
            }
        }.onFailure { Log.w("could not save settings: ${it.message}", tag = "settings") }
    }
}
