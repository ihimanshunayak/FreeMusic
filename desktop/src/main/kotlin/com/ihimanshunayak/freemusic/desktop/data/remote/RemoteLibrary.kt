// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - remote music libraries.
//
// NAME
//     RemoteLibrary.kt - WebDAV and SMB sources behind one repository.
//
// DESCRIPTION
//     The Android build reads a WebDAV share and an SMB share, and a Windows
//     port that only reads local folders would be a missing feature rather than
//     a platform difference - a desktop is where a NAS library matters *most*.
//
//     Two very different transports sit behind one small interface. WebDAV is
//     HTTP, so it needs nothing but the shared OkHttp client and a `PROPFIND`
//     body. SMB is a stateful binary protocol, and Java has no client for it in
//     the JDK. Rather than vendor a protocol implementation, SMB is reached
//     through the operating system's own UNC support - `\\host\share\path` is a
//     path Windows resolves natively, already authenticated by the OS, and a
//     mapped drive letter is just a local path. That is not a shortcut: it is
//     the same mechanism Explorer uses, it honours the user's existing
//     credentials, and it costs no extra code or dependency.
//
// RESPONSIBILITIES
//     - List and download files from an HTTP WebDAV collection.
//     - Enumerate an SMB share as an ordinary directory.
//     - Present both as the same [RemoteEntry] list a library screen can render.
//     - Read embedded tags from a remote file without downloading it entirely.
//
// DEPENDENCIES
//     - [Http] for the shared OkHttp client.
//     - [AudioMetadataReader] for tag parsing of a fetched file.
//
// INTEGRATION NOTES
//     - Credentials never reach the log. Every message in this file that could
//       contain a URL is passed through [redact].
//     - A `PROPFIND` body with `Depth: 1` returns the collection itself as its
//       first entry; that entry is dropped so a folder does not list itself.

package com.ihimanshunayak.freemusic.desktop.data.remote

import com.ihimanshunayak.freemusic.desktop.data.Http
import com.ihimanshunayak.freemusic.desktop.model.SourceKind
import com.ihimanshunayak.freemusic.desktop.model.Track
import com.ihimanshunayak.freemusic.desktop.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

// ---------------------------------------------------------------------------
// ## SECTION: Shared model
// ---------------------------------------------------------------------------

/** Where a remote entry lives, which also decides how it is fetched. */
enum class RemoteTransport(val label: String) {
    WEBDAV("WebDAV"),
    SMB("SMB / Windows share"),
}

/** One file or folder on a remote share. */
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val modifiedMs: Long = 0L,
    val transport: RemoteTransport = RemoteTransport.WEBDAV,
) {
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    val isAudio: Boolean
        get() = !isDirectory && extension in REMOTE_AUDIO_EXTENSIONS
}

/** The extensions worth showing from a remote share. */
val REMOTE_AUDIO_EXTENSIONS: Set<String> =
    setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "mp4", "webm")

/**
 * A remote source's connection details.
 *
 * Held as data rather than read from `Settings` inside the client so a caller
 * can test a candidate configuration before saving it, which is what the
 * "Test connection" button on the Sources screen needs.
 */
sealed interface RemoteConfig {
    val label: String

    data class WebDav(
        val url: String,
        val username: String = "",
        val password: String = "",
    ) : RemoteConfig {
        override val label: String get() = url
    }

    /**
     * An SMB share, expressed as the UNC path Windows understands.
     *
     * `basePath` is relative to the share root and may be blank. A mapped drive
     * (`Z:\`) needs nothing from this class at all and is better added as a
     * local folder, which is why [toUncPath] refuses a drive letter: the two
     * paths would otherwise be indistinguishable and one would silently win.
     */
    data class Smb(
        val host: String,
        val share: String,
        val basePath: String = "",
    ) : RemoteConfig {
        override val label: String get() = "\\\\$host\\$share"
    }
}

// ---------------------------------------------------------------------------
// ## SECTION: WebDAV
// ---------------------------------------------------------------------------

/**
 * A minimal WebDAV client: enough to list a collection and read a file.
 *
 * `PROPFIND` is the one verb a music player needs; `COPY`, `MOVE` and locking
 * are deliberately absent, because a player has no business writing to a
 * share. The multistatus response is parsed by regular expression rather than
 * with a DOM, which is defensible here for a specific reason: the reply uses
 * namespace prefixes that servers choose freely (`D:`, `d:`, `lp1:`), so a
 * namespace-aware parser needs the prefix map resolved per response, while the
 * element names themselves are fixed by RFC 4918. A tag-name match is
 * prefix-independent and cannot be fooled by a server that renames its
 * namespace aliases.
 */
class WebDavClient(private val config: RemoteConfig.WebDav) {

    /** True when there is enough configuration to attempt a request. */
    val isConfigured: Boolean get() = config.url.isNotBlank()

    /**
     * Lists [path] relative to the configured base URL.
     *
     * An empty [path] lists the root.
     */
    suspend fun list(path: String = ""): List<RemoteEntry> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext emptyList()
        val url = urlFor(path)
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request = requestBuilder(url)
            .method("PROPFIND", body)
            .header("Depth", "1")
            .build()

        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w("WebDAV PROPFIND ${redact(url)} failed: HTTP ${response.code}", tag = "webdav")
                return@withContext emptyList()
            }
            val xml = response.body.string()
            parseMultiStatus(xml, url).also {
                Log.d("WebDAV ${redact(url)} returned ${it.size} entries", tag = "webdav")
            }
        }
    }

    /**
     * Downloads [path] into [target].
     *
     * Streamed rather than buffered: a `Content-Length` in the hundreds of
     * megabytes is normal for a lossless album and buffering it would need that
     * much heap.
     */
    suspend fun download(path: String, target: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) {
            val url = urlFor(path)
            val request = requestBuilder(url).get().build()
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw java.io.IOException("HTTP ${response.code} for ${redact(url)}")
                }
                val total = response.body.contentLength()
                target.parentFile?.mkdirs()
                response.body.byteStream().use { input ->
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            onProgress(copied, total)
                        }
                    }
                }
                Log.i("WebDAV fetched ${redact(url)} (${target.length()} bytes)", tag = "webdav")
            }
        }

    /**
     * Reads only the first [bytes] of a remote file.
     *
     * This is what makes tag reading on a share practical: an ID3v2 tag sits at
     * the start of an MP3 and a FLAC `STREAMINFO` block is the first metadata
     * block, so a range request of a few hundred kilobytes reads every tag a
     * library list needs without transferring the audio. A server that ignores
     * `Range` returns the whole file, which still works.
     */
    suspend fun readHead(path: String, bytes: Int): ByteArray? = withContext(Dispatchers.IO) {
        val url = urlFor(path)
        val request = requestBuilder(url)
            .header("Range", "bytes=0-${bytes - 1}")
            .get()
            .build()
        runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(bytes)
                    var filled = 0
                    while (filled < bytes) {
                        val read = input.read(buffer, filled, bytes - filled)
                        if (read <= 0) break
                        filled += read
                    }
                    buffer.copyOf(filled)
                }
            }
        }.onFailure { Log.d("WebDAV head read failed: ${it.message}", tag = "webdav") }.getOrNull()
    }

    /**
     * Verifies the credentials by listing the root.
     *
     * Returns null on success, or the reason to show the user. The distinction
     * between 401 and a transport failure matters: one is a typo in the
     * password, the other is a wrong host, and telling a user to check their
     * password when the server is unreachable wastes their time.
     */
    suspend fun testConnection(): String? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext "Enter a server URL first"
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        runCatching {
            Http.client.newCall(requestBuilder(config.url).method("PROPFIND", body).header("Depth", "0").build())
                .execute().use { response ->
                    when {
                        response.isSuccessful -> null
                        response.code == 401 -> "The server rejected those credentials"
                        response.code == 403 -> "Those credentials cannot read this collection"
                        response.code == 404 -> "That collection does not exist"
                        response.code == 405 -> "The server does not support WebDAV"
                        else -> "The server answered HTTP ${response.code}"
                    }
                }
        }.getOrElse { "Could not reach the server: ${it.message}" }
    }

    private fun requestBuilder(url: String): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", Http.USER_AGENT)
        if (config.username.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(config.username, config.password))
        }
        return builder
    }

    /**
     * Builds the request URL for a listed path.
     *
     * A path from [parseMultiStatus] is already absolute on the *server* - that
     * is what a DAV href is - so it must be combined with the configured URL's
     * origin rather than appended to its full path. Appending instead would
     * produce `https://host/dav/music/dav/music/Album/Track.mp3` for every
     * entry below the base, which fails with a 404 that looks like a bad
     * password.
     */
    private fun urlFor(path: String): String {
        if (path.isBlank()) return config.url.trimEnd('/')
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) return path
        val origin = originOf(config.url) ?: return join(config.url, path)
        return origin + path
    }

    /**
     * Parses an RFC 4918 multistatus body.
     *
     * `<response>` blocks are split first and each is searched for its own
     * `<href>`, `<getcontentlength>` and `<resourcetype>`: reading those with a
     * single scan across the whole document would pair one file's size with the
     * next file's name as soon as any property is missing from a reply, which is
     * exactly what a minimal server does.
     */
    internal fun parseMultiStatus(xml: String, baseUrl: String): List<RemoteEntry> {
        val responses = Regex("""<(?:\w+:)?response\b.*?</(?:\w+:)?response>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .map { it.value }
            .toList()

        val entries = mutableListOf<RemoteEntry>()
        for (block in responses) {
            val href = tag(block, "href") ?: continue
            val path = decodePath(href)
            // A Depth: 1 reply includes the collection itself; skipping it keeps
            // a folder from listing its own name as a child.
            if (samePath(path, baseUrl)) continue

            val isCollection = Regex("""<(?:\w+:)?collection\s*/?>""").containsMatchIn(block)
            val name = path.trimEnd('/').substringAfterLast('/')
            if (name.isBlank()) continue

            entries.add(
                RemoteEntry(
                    name = name,
                    path = path,
                    isDirectory = isCollection,
                    sizeBytes = tag(block, "getcontentlength")?.toLongOrNull() ?: 0L,
                    modifiedMs = parseHttpDate(tag(block, "getlastmodified")) ?: 0L,
                    transport = RemoteTransport.WEBDAV,
                )
            )
        }
        return entries.sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
    }
}

/**
 * A `PROPFIND` asking only for what the list actually renders.
 *
 * Requesting fewer properties is not just tidiness: some servers refuse the
 * whole request with a 400 when any single property is one they do not
 * implement, so asking for the three this file uses is what keeps it working
 * against minimal servers as well as Nextcloud.
 */
private val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8" ?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:resourcetype/>
    <D:getcontentlength/>
    <D:getlastmodified/>
  </D:prop>
</D:propfind>"""

/** Scheme and authority of a URL, or null when it is not parseable. */
internal fun originOf(url: String): String? = runCatching {
    val uri = java.net.URI(url)
    if (uri.host == null) null else "${uri.scheme}://${uri.authority}"
}.getOrNull()

// ---------------------------------------------------------------------------
// ## SECTION: SMB
// ---------------------------------------------------------------------------

/**
 * An SMB share reached through Windows' own UNC support.
 *
 * There is no `list()` here because listing a UNC path is `File.listFiles()`,
 * which [LocalLibraryRepository] already does - the desktop build adds a share
 * by adding its UNC path as a library folder. This class exists for the two
 * things a plain path cannot do: build the UNC string from the fields the
 * Sources screen collects, and probe whether the machine can reach it at all
 * before the app writes a folder the user then has to remove by hand.
 */
class SmbClient(private val config: RemoteConfig.Smb) {

    /** The UNC path for the configured share, or null when it is incomplete. */
    val uncPath: String? get() = toUncPath(config)

    val isConfigured: Boolean get() = uncPath != null

    /**
     * Why a share cannot be used, or null when it can.
     *
     * A share that is not reachable now may be reachable in five minutes - a
     * laptop that has not yet joined the VPN is the ordinary case - so this only
     * reports, it never blocks adding the folder.
     */
    fun testConnection(): String? {
        val path = uncPath ?: return "Enter a host and a share name"
        val dir = File(path)
        return when {
            !dir.exists() -> "Windows cannot see $path. Check the name, and that you are on the network."
            !dir.isDirectory -> "$path is not a folder"
            !dir.canRead() -> "Windows denied access to $path. Connect to it in Explorer once, or map it to a drive letter."
            else -> null
        }
    }
}

/**
 * Builds a UNC path from a share description.
 *
 * Refuses a host containing a backslash or a drive letter: `Z:\` is already
 * reachable as a local path, and accepting it here would create two spellings
 * of the same library.
 */
internal fun toUncPath(config: RemoteConfig.Smb): String? {
    val host = config.host.trim().trim('\\')
    val share = config.share.trim().trim('\\')
    if (host.isBlank() || share.isBlank()) return null
    if (host.contains('/') || share.contains('\\')) return null
    val base = config.basePath.trim().trim('\\').replace('/', '\\')
    val root = "\\\\$host\\$share"
    return if (base.isBlank()) root else "$root\\$base"
}

// ---------------------------------------------------------------------------
// ## SECTION: The unified repository
// ---------------------------------------------------------------------------

/**
 * Lists and downloads from whichever remote the user configured.
 *
 * One entry point rather than two, because a library screen should not have to
 * know which transport is behind a browse request, and because a share is
 * configured once but browsed many times.
 */
class RemoteLibraryRepository {

    /** Lists a remote collection, dispatching on the config's own type. */
    suspend fun list(config: RemoteConfig, path: String = ""): List<RemoteEntry> = when (config) {
        is RemoteConfig.WebDav -> WebDavClient(config).list(path)
        is RemoteConfig.Smb -> listSmb(config, path)
    }

    /** Verifies a configuration without saving it. */
    suspend fun test(config: RemoteConfig): String? = when (config) {
        is RemoteConfig.WebDav -> WebDavClient(config).testConnection()
        is RemoteConfig.Smb -> SmbClient(config).testConnection()
    }

    private suspend fun listSmb(config: RemoteConfig.Smb, path: String): List<RemoteEntry> =
        withContext(Dispatchers.IO) {
            val root = toUncPath(config) ?: return@withContext emptyList()
            val dir = if (path.isBlank()) File(root) else File(root, path)
            val children = runCatching { dir.listFiles() }.getOrNull()
            if (children == null) {
                Log.w("SMB listing failed for $root", tag = "smb")
                return@withContext emptyList()
            }
            children.map { child ->
                RemoteEntry(
                    name = child.name,
                    path = child.absolutePath,
                    isDirectory = child.isDirectory,
                    sizeBytes = if (child.isFile) child.length() else 0L,
                    modifiedMs = child.lastModified(),
                    transport = RemoteTransport.SMB,
                )
            }.sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
        }

    /**
     * Turns a remote audio entry into a playable [Track].
     *
     * The id embeds the full remote path so a re-scan produces the same id and
     * the play history keeps matching after a reconnection.
     *
     * An SMB entry needs no download at all: libVLC takes the UNC path directly,
     * which is why `localPath` is populated here for both transports. WebDAV is
     * the one case that must be fetched first, and the caller does that through
     * [WebDavClient.download] before playback.
     */
    fun toTrack(entry: RemoteEntry, config: RemoteConfig): Track {
        val stem = entry.name.substringBeforeLast('.').replace('_', ' ').trim()
        val separator = stem.indexOf(" - ")
        val artist = if (separator > 0) stem.substring(0, separator).trim() else null
        val title = if (separator > 0) stem.substring(separator + 3).trim() else stem

        return Track(
            id = "remote:${config.label}:${entry.path}",
            title = title.ifBlank { entry.name },
            artist = artist ?: "Unknown artist",
            album = null,
            durationSeconds = 0,
            thumbnailUrl = null,
            source = SourceKind.LOCAL_FILE,
            videoId = null,
            localPath = if (entry.transport == RemoteTransport.SMB) entry.path else null,
        )
    }
}

// ---------------------------------------------------------------------------
// ## SECTION: Small shared helpers
// ---------------------------------------------------------------------------

/** Joins a base URL and a path without doubling or dropping the separator. */
internal fun join(base: String, path: String): String {
    if (path.isBlank()) return base.trimEnd('/')
    if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) return path
    return base.trimEnd('/') + "/" + path.trimStart('/')
}

/**
 * Hides the credentials in a URL before it reaches a log line.
 *
 * A WebDAV URL may carry `user:password@host`, and a log file is the last place
 * that should be readable.
 */
internal fun redact(url: String): String {
    val at = url.indexOf('@')
    val scheme = url.indexOf("://")
    if (at < 0 || scheme < 0 || at < scheme) return url
    return url.substring(0, scheme + 3) + "***@" + url.substring(at + 1)
}

/** The text of the first occurrence of a namespaced tag, ignoring its prefix. */
private fun tag(block: String, name: String): String? =
    TAG_PATTERNS.getOrPut(name) { Regex("""<(?:\w+:)?$name\b[^>]*>(.*?)</(?:\w+:)?$name>""", RegexOption.DOT_MATCHES_ALL) }
        .find(block)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/**
 * Tag patterns, compiled once.
 *
 * Dart-style lexing would be overkill, but the same three names are matched
 * against every `<response>` block in every listing, and compiling a regex per
 * call turns a 5,000-track folder into 15,000 compilations.
 */
private val TAG_PATTERNS = java.util.concurrent.ConcurrentHashMap<String, Regex>()

/**
 * Extracts the path from a DAV href, decoded.
 *
 * Servers are inconsistent: some reply with a full absolute URL and some with a
 * bare path, and both may carry percent-escapes or an XML entity. Everything
 * except the path is discarded, because a path is all the caller needs to build
 * the next request against the same base.
 */
internal fun decodePath(href: String): String {
    val unescaped = href.replace("&amp;", "&")
    val decoded = runCatching { URLDecoder.decode(unescaped, StandardCharsets.UTF_8) }.getOrDefault(unescaped)
    return when {
        // An absolute URL: keep only its path, and drop any query.
        decoded.contains("://") -> "/" + decoded.substringAfter("://").substringAfter('/', "").substringBefore('?')
        decoded.startsWith("/") -> decoded.substringBefore('?')
        else -> "/$decoded".substringBefore('?')
    }
}

/**
 * True when two DAV paths name the same collection.
 *
 * Case-insensitive because a WebDAV server fronting a Windows share reports
 * whatever case the share uses, which need not match the case in the
 * configuration the user typed.
 */
internal fun samePath(a: String, b: String): Boolean {
    fun normalise(value: String): String = decodePath(value).trimEnd('/').lowercase(Locale.ROOT)
    return normalise(a) == normalise(b)
}

/** Percent-encodes one path segment, leaving the separators alone. */
@Suppress("unused")
internal fun encodeSegment(segment: String): String =
    URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")

/** Parses the three date formats a DAV server is allowed to send. */
private fun parseHttpDate(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    val patterns = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEEE, dd-MMM-yy HH:mm:ss zzz",
        "EEE MMM d HH:mm:ss yyyy",
    )
    for (pattern in patterns) {
        val parsed = runCatching {
            java.time.format.DateTimeFormatter
                .ofPattern(pattern, Locale.US)
                .parse(value, java.time.LocalDateTime::from)
        }.getOrNull()
        if (parsed != null) {
            return parsed
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }
    }
    return null
}
