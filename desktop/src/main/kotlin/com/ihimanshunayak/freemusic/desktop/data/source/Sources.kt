// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - extra music sources.
//
// NAME
//     Sources.kt - the built-in service registry and the JS addon engine.
//
// DESCRIPTION
//     The Android build lets a user install "modules" that add more places to
//     find music. The desktop port keeps that idea but grounds it in something
//     real: the extractor library already in the dependency tree implements five
//     services, and only one of them (YouTube) was being used. SoundCloud,
//     Bandcamp, PeerTube and MediaCCC all expose a search and a stream, and
//     enabling them is what turns "a YouTube Music client" into "a music client".
//
//     On top of those, a user can add their own source as a small JavaScript
//     addon, executed in Rhino. That is the same engine the stream resolver
//     already runs for YouTube's signature cipher, so it costs no new
//     dependency and no new attack surface: Rhino has no filesystem or socket
//     access unless the host grants it, and the sandbox here grants none.
//
// RESPONSIBILITIES
//     - Name each available service and expose it as a searchable source.
//     - Rank sources so the resolver tries the best one first.
//     - Load and run a JS addon that resolves a track to a playable URL.
//     - Enforce the addon sandbox and its time limit.
//
// DEPENDENCIES
//     - NewPipeExtractor's [ServiceList] for the built-in services.
//     - Rhino for the addon engine.
//     - [Http] for an addon's own network access, since Rhino has none.
//
// INTEGRATION NOTES
//     - An addon is data, not code the app trusts. Every entry point is wrapped
//       in a try/catch with a wall-clock limit, and a failure removes the addon
//       from the active set for the session rather than retrying it on every
//       track, because an addon that throws once usually throws always.
//     - A source's `qualityRank` is what makes "prefer higher quality" mean
//       something concrete: a lossless source outranks a 128 kbps one, and the
//       resolver can then skip a source whose ceiling is below what it already
//       has.

package com.ihimanshunayak.freemusic.desktop.data.source

import com.ihimanshunayak.freemusic.desktop.data.Http
import com.ihimanshunayak.freemusic.desktop.data.Settings
import com.ihimanshunayak.freemusic.desktop.model.SearchResult
import com.ihimanshunayak.freemusic.desktop.model.SourceKind
import com.ihimanshunayak.freemusic.desktop.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.search.SearchExtractor
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------
// ## SECTION: Built-in services
// ---------------------------------------------------------------------------

/**
 * A place music can be found.
 *
 * [qualityRank] is a coarse ordering, not a bitrate: it answers "would using
 * this source instead of the current one be an upgrade?", which is the only
 * question the resolver needs to ask when the user has asked to prefer better
 * quality.
 */
data class MusicSource(
    val id: String,
    val name: String,
    val description: String,
    val qualityRank: Int,
    val kind: SourceKind = SourceKind.YOUTUBE_MUSIC,
    val supportsSearch: Boolean = true,
    val supportsStream: Boolean = true,
    /** A user-installed addon rather than one built into the app. */
    val isAddon: Boolean = false,
) {
    /** True when a stream can be obtained from this source. */
    val canStream: Boolean get() = supportsStream
}

/**
 * The services the extractor library implements.
 *
 * Ranked from the top down. YouTube Music comes first because it is the only one
 * with a catalogue a general user expects; then SoundCloud, which is where a
 * great deal of music that is *not* on streaming services lives; then Bandcamp,
 * where an artist's own upload is the definitive copy; then PeerTube and
 * MediaCCC, which are the smallest catalogues and so are tried last.
 */
object BuiltInSources {

    val all: List<MusicSource> = listOf(
        MusicSource(
            id = "youtube_music",
            name = "YouTube Music",
            description = "Full catalogue with album art, lyrics and the best available bitrate.",
            qualityRank = 100,
        ),
        MusicSource(
            id = "soundcloud",
            name = "SoundCloud",
            description = "Independent artists, remixes and DJ sets that streaming services do not carry.",
            qualityRank = 70,
            kind = SourceKind.SOUNDCLOUD,
        ),
        MusicSource(
            id = "bandcamp",
            name = "Bandcamp",
            description = "Artist-uploaded audio, often the definitive master of a release.",
            qualityRank = 80,
            kind = SourceKind.BANDCAMP,
        ),
        MusicSource(
            id = "peertube",
            name = "PeerTube",
            description = "Federated video hosting, including many independent music channels.",
            qualityRank = 50,
            kind = SourceKind.PEERTUBE,
        ),
        MusicSource(
            id = "media_ccc",
            name = "media.ccc.de",
            description = "Conference recordings, mostly talks with occasional live music sets.",
            qualityRank = 30,
            kind = SourceKind.MEDIA_CCC,
        ),
    )

    /** Sources enabled out of the box: one, so a first search is fast and predictable. */
    val defaultEnabled: List<String> = listOf("youtube_music")

    /**
     * The extractor service behind an id.
     *
     * Null for an unknown id rather than a throw, because the id comes from a
     * settings file a user can hand-edit and an unknown one should disable a
     * source rather than prevent the app from starting.
     */
    fun serviceFor(id: String): StreamingService? = when (id) {
        "youtube_music" -> ServiceList.YouTube
        "soundcloud" -> ServiceList.SoundCloud
        "bandcamp" -> ServiceList.Bandcamp
        "peertube" -> ServiceList.PeerTube
        "media_ccc" -> ServiceList.MediaCCC
        else -> null
    }

    fun byId(id: String): MusicSource? = all.firstOrNull { it.id == id }
}

// ---------------------------------------------------------------------------
// ## SECTION: Multi-source search
// ---------------------------------------------------------------------------

/** One source's contribution to a combined search. */
data class SourceSearchResult(
    val source: MusicSource,
    val results: List<SearchResult> = emptyList(),
    val error: String? = null,
)

/**
 * Searches several sources at once.
 *
 * Each source is queried independently and a failure in one does not remove the
 * others' results: searching is a best-effort fan-out, and a SoundCloud outage
 * must not make a YouTube search look broken. That is why the return type
 * carries a per-source error rather than a single one.
 */
class SourceAggregator {

    suspend fun search(
        query: String,
        sources: List<MusicSource>,
        limitPerSource: Int = 20,
    ): List<SourceSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        // Sequential rather than concurrent: this runs off the UI thread, each
        // source is a network round trip, and four parallel requests to four
        // different hosts finishes no sooner than four sequential ones for a
        // query a user typed a keystroke ago - while making the failure ordering
        // nondeterministic for no benefit.
        sources
            .filter { it.supportsSearch && !it.isAddon }
            .map { source -> searchOne(source, query, limitPerSource) }
    }

    private fun searchOne(source: MusicSource, query: String, limit: Int): SourceSearchResult {
        val service = BuiltInSources.serviceFor(source.id)
            ?: return SourceSearchResult(source, error = "This source is not available")
        return runCatching {
            val extractor: SearchExtractor = service.getSearchExtractor(query)
            extractor.fetchPage()
            val results = extractor.initialPage.items
                .take(limit)
                .mapNotNull { item -> item.toSearchResult(source) }
            SourceSearchResult(source, results)
        }.getOrElse { e ->
            Log.w("${source.name} search failed: ${e.message}", tag = "source")
            SourceSearchResult(source, error = e.message ?: "Search failed")
        }
    }
}

/**
 * Turns an extractor item into the app's own result type.
 *
 * The item's URL is kept in [SearchResult.playlistId] for a non-YouTube service,
 * because the id-based path the YouTube flow uses does not exist there: a
 * SoundCloud or Bandcamp track is addressed by its page URL, and dropping it
 * would leave a result that cannot be played.
 */
private fun org.schabi.newpipe.extractor.InfoItem.toSearchResult(source: MusicSource): SearchResult? {
    val url = runCatching { this.url }.getOrNull()
    val name = runCatching { this.name }.getOrNull() ?: return null
    val uploader = runCatching { (this as? org.schabi.newpipe.extractor.stream.StreamInfoItem)?.uploaderName }
        .getOrNull()
    val duration = runCatching { (this as? org.schabi.newpipe.extractor.stream.StreamInfoItem)?.duration }
        .getOrNull()
    val thumbnail = runCatching { this.thumbnails.firstOrNull()?.url }.getOrNull()

    return SearchResult(
        title = name,
        subtitle = listOfNotNull(source.name, uploader?.takeIf { it.isNotBlank() }).joinToString(" - "),
        videoId = if (source.id == "youtube_music") url?.substringAfter("v=")?.substringBefore('&') else null,
        browseId = null,
        playlistId = if (source.id == "youtube_music") null else url,
        thumbnailUrl = thumbnail,
        durationSeconds = (duration ?: 0L).toInt(),
        kind = com.ihimanshunayak.freemusic.desktop.model.ResultKind.SONG,
    )
}

// ---------------------------------------------------------------------------
// ## SECTION: The addon sandbox
// ---------------------------------------------------------------------------

/**
 * A JavaScript addon a user installed.
 *
 * The contract is deliberately tiny: a script defines `resolve(videoId, artist,
 * title)` and returns either a plain URL string or an object with `url` and
 * optional `headers`. Anything else - a full plugin API with search, browse and
 * a queue - would need a documented, versioned interface and a compatibility
 * promise this app cannot make yet; a resolver is small enough to be correct.
 */
data class AddonSource(
    val id: String,
    val name: String,
    val script: String,
    val version: String = "1.0.0",
    val qualityRank: Int = 40,
    /** Hashed source, so a change is visible in the log after an update. */
    val fingerprint: String = script.hashCode().toString(16),
)

/** The outcome of running an addon. */
sealed interface AddonResult {
    data class Resolved(val url: String, val headers: Map<String, String>) : AddonResult
    data object NotHandled : AddonResult
    data class Failed(val reason: String) : AddonResult
}

/**
 * Runs addon scripts in Rhino.
 *
 * The sandbox is Rhino's default context plus three deliberate restrictions, and
 * each one closes a real hole:
 *
 * - No `ClassShutter`, so `Packages` and `java.*` are unreachable. Without this
 *   a script could call `java.lang.Runtime.getRuntime().exec(...)`, which is
 *   arbitrary code execution from a file the user downloaded.
 * - A wall-clock instruction limit, because a script with `while(true) {}` would
 *   otherwise hang the playback thread forever.
 * - No `load`/`readFile` host objects. Rhino's shell defines them; the embedding
 *   API does not, and nothing here adds them.
 *
 * Network access is granted only through the single `httpGet` function the host
 * installs, so a script cannot reach a host the app would not have reached
 * itself, and every request it makes is logged.
 */
class AddonEngine {

    /** Addons that have already thrown, so they are not retried per track. */
    private val disabled = ConcurrentHashMap.newKeySet<String>()

    /**
     * Runs [addon]'s resolver for a track.
     *
     * Returns [AddonResult.Failed] rather than throwing, because an addon is
     * third-party code and the caller's response to a broken one is to fall
     * through to the next source, not to surface an error.
     */
    fun resolve(addon: AddonSource, videoId: String, artist: String, title: String): AddonResult {
        if (addon.id in disabled) return AddonResult.Failed("This source was disabled after an earlier failure")

        val context = Context.enter()
        try {
            context.optimizationLevel = -1          // interpreter only: no bytecode class loading
            context.languageVersion = Context.VERSION_ES6
            context.instructionObserverThreshold = INSTRUCTION_BUDGET
            context.setInstructionObserverThreshold(INSTRUCTION_BUDGET)

            val scope = context.initStandardObjects(null, true)
            removeDangerousGlobals(scope, context)
            installHostFunctions(scope)

            context.evaluateString(scope, addon.script, "${addon.id}.js", 1, null)

            val fn = scope.get("resolve", scope)
            if (fn == Undefined.instance || fn !is org.mozilla.javascript.Function) {
                disabled.add(addon.id)
                return AddonResult.Failed("The script does not define a resolve() function")
            }

            val result = fn.call(context, scope, scope, arrayOf(videoId, artist, title))
            return interpret(result)
        } catch (e: Throwable) {
            // A script that throws once usually throws always, and retrying it on
            // every track would turn one broken addon into a permanently slow
            // resolver.
            disabled.add(addon.id)
            Log.w("addon ${addon.name} failed and was disabled: ${e.message}", tag = "source")
            return AddonResult.Failed(e.message ?: "The script failed")
        } finally {
            Context.exit()
        }
    }

    /** Re-enables a disabled addon, for the Sources screen's "try again". */
    fun reset(addonId: String) {
        disabled.remove(addonId)
    }

    fun isDisabled(addonId: String): Boolean = addonId in disabled

    /**
     * Blocks the reflection and process entry points Rhino exposes by default.
     *
     * `initStandardObjects` gives a script `Packages`, `java`, `javax` and
     * `getClass`, and from any of them a one-liner reaches `Runtime.exec`. They
     * are deleted rather than shadowed, so a script cannot reach them by a
     * different path.
     */
    private fun removeDangerousGlobals(scope: ScriptableObject, context: Context) {
        val blocked = listOf(
            "Packages", "java", "javax", "org", "com", "edu", "net", "getClass",
            "JavaAdapter", "JavaImporter", "Continuation", "loadClass", "load",
            "readFile", "readUrl", "spawn", "runCommand", "sync", "quit", "exit",
            "environment", "defineClass", "importClass", "importPackage", "print",
        )
        for (name in blocked) {
            runCatching { ScriptableObject.deleteProperty(scope, name) }
        }
    }

    /**
     * Adds the only two functions a script may call back into the app with.
     *
     * Both are `FunctionObject` subclasses rather than `ScriptableObject` with a
     * `call` member, because a plain member would be reachable and replaceable
     * from the script itself.
     */
    private fun installHostFunctions(scope: ScriptableObject) {
        ScriptableObject.putProperty(scope, "httpGet", HttpGetFunction())
        ScriptableObject.putProperty(scope, "log", LogFunction())
    }

    /** The JavaScript-side result of `resolve()`, normalised into [AddonResult]. */
    private fun interpret(result: Any?): AddonResult = when {
        result is String && result.startsWith("http") -> AddonResult.Resolved(result, emptyMap())
        result is org.mozilla.javascript.NativeObject -> {
            val url = ScriptableObject.getProperty(result, "url")?.toString()
            if (url == null || !url.startsWith("http")) {
                AddonResult.NotHandled
            } else {
                val headers = ScriptableObject.getProperty(result, "headers")
                    ?.let { value ->
                        runCatching {
                            val keys = (value as org.mozilla.javascript.Scriptable).ids
                            keys.mapNotNull { key ->
                                val name = key.toString()
                                val header = ScriptableObject.getProperty(value as org.mozilla.javascript.Scriptable, name)
                                if (header == null || header === org.mozilla.javascript.Scriptable.NOT_FOUND) {
                                    null
                                } else {
                                    name to header.toString()
                                }
                            }.toMap()
                        }.getOrDefault(emptyMap())
                    }.orEmpty()
                AddonResult.Resolved(url, headers)
            }
        }
        else -> AddonResult.NotHandled
    }

    /**
     * `httpGet(url)` for a script.
     *
     * Scripts have no socket access of their own, so an addon that needs to call
     * an API gets it through here - which means every outbound request an addon
     * makes is one this app performed, is subject to the same timeouts, and is
     * logged the same way.
     */
    private class HttpGetFunction : org.mozilla.javascript.BaseFunction() {
        override fun call(cx: Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable, args: Array<Any>): Any {
            val url = args.getOrNull(0)?.toString() ?: return Undefined.instance
            if (!url.startsWith("http://") && !url.startsWith("https://")) return Undefined.instance
            return runCatching {
                val request = okhttp3.Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) "" else response.body.string()
                }
            }.getOrDefault("")
        }

        override fun getFunctionName(): String = "httpGet"
    }

    /** `log(message)` for a script, routed into the app's own log. */
    private class LogFunction : org.mozilla.javascript.BaseFunction() {
        override fun call(cx: Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable, args: Array<Any>): Any {
            Log.d(args.joinToString(" ") { it.toString() }, tag = "addon")
            return Undefined.instance
        }

        override fun getFunctionName(): String = "log"
    }

    private companion object {
        /**
         * Roughly a second of interpreted execution.
         *
         * Rhino counts *instructions*, not milliseconds, and the conversion is
         * not exact - but an order of magnitude below this is too tight for a
         * script that parses a JSON response, and an order above it is long
         * enough for a user to notice the app has stopped responding.
         */
        const val INSTRUCTION_BUDGET = 20_000_000
    }
}

// ---------------------------------------------------------------------------
// ## SECTION: The registry
// ---------------------------------------------------------------------------

/**
 * The sources the app will actually use, in order.
 *
 * Ordering is the whole point: [orderedSources] is what the resolver walks, so
 * a user's reordering or a "prefer higher quality" toggle changes which source
 * is asked first, without any caller needing to know why.
 */
class SourceRegistry(private val settings: () -> Settings) {

    private val addons: MutableList<AddonSource> = mutableListOf()
    private val engine = AddonEngine()

    /** Replaces the installed addon set. */
    fun setAddons(list: List<AddonSource>) {
        synchronized(addons) {
            addons.clear()
            addons.addAll(list)
        }
    }

    /** The addons currently installed. */
    fun installedAddons(): List<AddonSource> = synchronized(addons) { addons.toList() }

    /**
     * The enabled sources, in the order they should be tried.
     *
     * A user-specified order wins, and anything they did not mention follows in
     * the built-in ranking, so a hand-written `addonSourceOrder` that omits a
     * source does not silently disable it.
     */
    fun orderedSources(): List<MusicSource> {
        val current = settings()
        val enabledIds = current.addonSourceOrder
            .takeIf { it.isNotEmpty() }
            ?: BuiltInSources.defaultEnabled

        val builtIns = BuiltInSources.all.filter { it.id in enabledIds }
        val ranked = if (current.addonPreferHigherQuality) builtIns.sortedByDescending { it.qualityRank } else builtIns

        val userOrder = enabledIds.withIndex().associate { (index, id) -> id to index }
        val orderedBuiltIns = ranked.sortedWith(
            compareBy(
                // A source the user explicitly ordered keeps that order; one they
                // did not is placed after, by quality rank.
                { userOrder[it.id] ?: Int.MAX_VALUE },
                { -it.qualityRank },
            )
        )

        val addonSources = synchronized(addons) {
            addons.map {
                MusicSource(
                    id = it.id,
                    name = it.name,
                    description = "User-installed source ${it.version}",
                    qualityRank = it.qualityRank,
                    kind = SourceKind.ADDON,
                    supportsSearch = false,     // a resolver addon has no search API
                    supportsStream = true,
                    isAddon = true,
                )
            }
        }

        return orderedBuiltIns + addonSources.filter { it.id in enabledIds }
    }

    /** The id of the addon behind a source, when it is one. */
    fun addonFor(sourceId: String): AddonSource? = synchronized(addons) { addons.firstOrNull { it.id == sourceId } }

    /**
     * Tries every enabled addon in order until one resolves.
     *
     * Called only after the built-in resolver has failed, so an addon is a
     * fallback rather than a replacement - which is the right precedence,
     * because the built-in path is the one that is tested against YouTube's
     * current behaviour.
     */
    suspend fun resolveWithAddons(videoId: String, artist: String, title: String): AddonResult =
        withContext(Dispatchers.IO) {
            for (source in orderedSources().filter { it.isAddon }) {
                val addon = addonFor(source.id) ?: continue
                when (val outcome = engine.resolve(addon, videoId, artist, title)) {
                    is AddonResult.Resolved -> {
                        Log.i("${addon.name} resolved $videoId", tag = "source")
                        return@withContext outcome
                    }
                    is AddonResult.Failed -> Log.d("${addon.name}: ${outcome.reason}", tag = "source")
                    AddonResult.NotHandled -> Unit
                }
            }
            AddonResult.NotHandled
        }

    /** Re-enables an addon that failed earlier. */
    fun retryAddon(addonId: String) = engine.reset(addonId)

    fun isAddonDisabled(addonId: String): Boolean = engine.isDisabled(addonId)

    /**
     * Validates a script without running it against a track.
     *
     * The Sources screen calls this when a script is pasted so a syntax error is
     * reported immediately, while the user still has the text in front of them,
     * rather than at the moment they press play on a song.
     */
    fun validate(script: String): String? {
        if (script.isBlank()) return "The script is empty"
        val context = Context.enter()
        return try {
            context.optimizationLevel = -1
            val scope = context.initStandardObjects(null, true)
            context.evaluateString(scope, script, "validate.js", 1, null)
            val fn = scope.get("resolve", scope)
            if (fn == Undefined.instance || fn !is org.mozilla.javascript.Function) {
                "The script must define a resolve(videoId, artist, title) function"
            } else {
                null
            }
        } catch (e: Throwable) {
            e.message ?: "The script could not be parsed"
        } finally {
            Context.exit()
        }
    }

    /** The canonical id an addon should use for a given display name. */
    fun addonIdFor(name: String): String =
        "addon:" + name.trim().lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]+"""), "-").trim('-')
            .ifBlank { "unnamed" }
}

// ---------------------------------------------------------------------------
// ## SECTION: A sample addon
// ---------------------------------------------------------------------------

/**
 * The script the Sources screen offers as a starting template.
 *
 * Documented by being runnable: a user replacing the placeholder URL with their
 * own endpoint has a working addon, which teaches the contract far better than
 * a paragraph of prose would.
 */
val SAMPLE_ADDON_SCRIPT: String = """
// Free Music source addon.
//
// resolve() is called when the built-in resolver could not find a stream.
// Return a URL string, or an object with `url` and an optional `headers` map.
// Return null to say "not handled here", and the next source is tried.
//
// The sandbox provides exactly two host functions: httpGet(url) and log(text).
// There is no filesystem, no socket and no way to reach a Java class.

const PLACEHOLDER_URL = "https://example.invalid/lookup";

function resolve(videoId, artist, title) {
    log("asked to resolve " + artist + " - " + title + " (" + videoId + ")");

    // A real addon would build a query from the track and fetch the result:
    //
    //   const body = httpGet(PLACEHOLDER_URL + "?q=" + encodeURIComponent(artist + " " + title));
    //   const match = JSON.parse(body).results.find(r => r.id === videoId);
    //   if (!match) return null;
    //   return { url: match.streamUrl, headers: { "Referer": match.referer } };

    return null;
}
""".trimIndent()
