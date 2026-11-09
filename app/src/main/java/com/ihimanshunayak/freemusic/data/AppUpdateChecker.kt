package com.ihimanshunayak.freemusic.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.ihimanshunayak.freemusic.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File

/**
 * Free Music ships as a sideloaded APK off GitHub Releases rather than through
 * a store, so there's nothing to push an update notice on its own — this
 * polls the repo's "latest release" once per launch and compares its tag
 * against the running build.
 *
 * The update itself is also handled here: the release's `.apk` asset is
 * downloaded into the app's cache and handed to the system package installer,
 * so the whole round trip stays inside the app instead of bouncing out to a
 * browser.
 */
object AppUpdateChecker {

    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        val apkUrl: String?,
        /** The release's own Markdown body, shown as this update's "what's new". */
        val notes: String?,
    )

    private const val CACHE_SUBDIR = "updates"

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/ihimanshunayak/FreeMusic/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available = _available.asStateFlow()

    /** Where this update's APK download currently stands, for the dialog's progress row. */
    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val fraction: Float) : DownloadState
        data class Ready(val file: File) : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download = _download.asStateFlow()

    /** Set from the UI thread when the user cancels; polled between network reads. */
    @Volatile
    private var downloadCancelled = false

    /**
     * Guards the one network read, so a manual check and the launch-time check
     * cannot both be in flight — two concurrent reads of the same endpoint
     * would race on [_available] and the slower one would win, potentially
     * overwriting a newer answer with a staler one.
     */
    private val checkMutex = Mutex()

    /** Outcome of the most recent check, so the UI can say what happened. */
    enum class CheckOutcome {
        /** A newer release exists; [available] holds it. */
        UpdateAvailable,

        /** The installed build is already the newest published release. */
        UpToDate,

        /** The check could not be completed (offline, API error, bad payload). */
        Failed,
    }

    private val _lastCheck = MutableStateFlow<CheckOutcome?>(null)

    /** What the most recent check concluded, or null if none has run yet. */
    val lastCheck = _lastCheck.asStateFlow()

    private val _checking = MutableStateFlow(false)

    /** True while a check is in flight, so the UI can say so instead of going quiet. */
    val checking = _checking.asStateFlow()

    /**
     * Polls GitHub for the newest release and compares it with this build.
     *
     * Returns what it concluded so a caller that asked for the check (the
     * Settings row) can report it, while the launch-time caller ignores the
     * return value and simply reads [available].
     */
    suspend fun check(): CheckOutcome = withContext(Dispatchers.IO) {
        // Set before the lock, so a second caller that is waiting its turn
        // still shows as "checking" rather than looking like it did nothing.
        _checking.value = true
        try {
            checkMutex.withLock {
                val result = try {
                    val request = Request.Builder().url(LATEST_RELEASE_URL).build()
                    val body = Http.client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) null else response.body?.string()
                    }
                    val release = body
                        ?.let { json.parseToJsonElement(it) as? JsonObject }
                    val tag = release?.get("tag_name")?.jsonPrimitive?.contentOrNull
                    val url = release?.get("html_url")?.jsonPrimitive?.contentOrNull
                    if (release == null || tag == null || url == null) {
                        CheckOutcome.Failed
                    } else {
                        val apkUrl = apkAssetUrl(release)
                        val notes = release["body"]?.jsonPrimitive?.contentOrNull
                        val latest = tag.removePrefix("v")
                        if (isNewer(latest, BuildConfig.VERSION_NAME)) {
                            _available.value = UpdateInfo(latest, url, apkUrl, notes)
                            CheckOutcome.UpdateAvailable
                        } else {
                            // This build is the newest one published, so any update
                            // carried over from an earlier check is stale — clearing
                            // it takes the top bar's upgrade icon away with it.
                            _available.value = null
                            CheckOutcome.UpToDate
                        }
                    }
                } catch (cancelled: CancellationException) {
                    // A cancelled check is a cancelled check, not a failed one:
                    // swallowing it here would report "couldn't reach GitHub" for
                    // a request that was only abandoned.
                    throw cancelled
                } catch (_: Exception) {
                    CheckOutcome.Failed
                }
                // A failed read is a failed check, not an "up to date" — silently
                // claiming there is nothing new would hide the fact that the check
                // never happened.
                _lastCheck.value = result
                result
            }
        } finally {
            _checking.value = false
        }
    }

    /**
     * Wipes any APK left over from a previous run. Called once at cold start
     * so a downloaded update is only ever "Install Now" for the session that
     * downloaded it — the next launch starts clean rather than trying to work
     * out whether a leftover file is still good.
     */
    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        File(context.cacheDir, CACHE_SUBDIR).listFiles()?.forEach { it.delete() }
    }

    /**
     * Picks this device's best-fitting APK out of the release.
     *
     * A release carries a universal build plus one split per ABI, and GitHub
     * hands assets back in name order rather than by relevance — so taking the
     * first `.apk` would feed an `arm64-v8a` split to a 32-bit phone. Walk the
     * device's own ABI preference instead, then fall back to the universal
     * build, then to any uploaded `.apk`. A release with no usable asset
     * (source-only draft, renamed file) leaves [UpdateInfo.apkUrl] null and the
     * UI opens the releases page as before.
     */
    private fun apkAssetUrl(release: JsonObject): String? = runCatching {
        val apks = release["assets"]?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()

        pickApkForAbis(apks, Build.SUPPORTED_ABIS)
            ?.get("browser_download_url")
            ?.jsonPrimitive
            ?.contentOrNull
    }.getOrNull()

    /**
     * Chooses from [assets] by walking [supportedAbis] in the device's own
     * preference order, then falls back to a universal build, then to whatever
     * usable APK is left. Non-APK and not-yet-uploaded assets are filtered out
     * first so no fallback can ever point at a source archive. Kept free of
     * framework calls so it can be unit-tested off-device.
     */
    internal fun pickApkForAbis(assets: List<JsonObject>, supportedAbis: Array<String>): JsonObject? {
        val apks = assets.filter(::isUploadedApk)
        return supportedAbis.firstNotNullOfOrNull { abi ->
            apks.firstOrNull { assetName(it).contains(abi, ignoreCase = true) }
        }
            ?: apks.firstOrNull { assetName(it).contains("universal", ignoreCase = true) }
            ?: apks.firstOrNull()
    }

    private fun assetName(asset: JsonObject): String =
        asset["name"]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun isUploadedApk(asset: JsonObject): Boolean =
        assetName(asset).endsWith(".apk", ignoreCase = true) &&
            asset["state"]?.jsonPrimitive?.contentOrNull == "uploaded"

    /**
     * Streams the current update's APK into the app cache, reporting progress
     * through [download]. A finished file survives a cancelled dialog: until
     * the state is reset, "Install Now" comes straight back without a second
     * download.
     */
    suspend fun downloadApk(context: Context): Unit = withContext(Dispatchers.IO) {
        val info = _available.value ?: return@withContext
        val url = info.apkUrl ?: return@withContext
        downloadCancelled = false
        _download.value = DownloadState.Downloading(0f)

        runCatching {
            val dir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
            // Drop anything left over from an earlier attempt.
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "freemusic-${info.version}.apk")

            val request = Request.Builder().url(url).build()
            Http.client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
                val body = response.body ?: error("Empty download body")
                val total = body.contentLength().takeIf { it > 0 }

                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var readTotal = 0L
                        while (true) {
                            if (downloadCancelled) {
                                _download.value = DownloadState.Idle
                                return@withContext
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            readTotal += read
                            total?.let {
                                _download.value =
                                    DownloadState.Downloading((readTotal.toFloat() / it).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            _download.value = DownloadState.Ready(target)
        }.onFailure { error ->
            _download.value = if (downloadCancelled) {
                DownloadState.Idle
            } else {
                DownloadState.Failed(error.message ?: "Download failed")
            }
        }
    }

    /** Stops an in-flight download; the next read loop sees this and bails. */
    fun cancelDownload() {
        downloadCancelled = true
    }

    /** Back to square one after a failure, so the dialog offers Download again. */
    fun resetDownload() {
        _download.value = DownloadState.Idle
    }

    /**
     * Hands a downloaded APK to the system installer.
     *
     * Sideloaded apps need the user's blessing per app ("install unknown apps");
     * without it the installer intent silently does nothing on most ROMs, so
     * the user is sent to that one switch first and taps Install again after.
     */
    fun installApk(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                ),
        )
    }

    /** A version split into its numeric dotted parts and whether it carries a "-suffix" (e.g. "-beta2"). */
    private data class ParsedVersion(
        val parts: List<Int>,
        val isPreRelease: Boolean,
        /**
         * The calendar date this version names, when it uses the date-based
         * scheme (`DD.MM.YY[.N]`), or null when it doesn't.
         */
        val date: VersionDate?,
    )

    /**
     * A calendar date as the date-based version scheme spells it.
     *
     * The scheme is `DD.MM.YY` with an optional same-day counter — see
     * `scripts/compute-date-version.ps1` and `docs/VERSION_DATE_SYSTEM.md`.
     */
    private data class VersionDate(
        val year: Int,
        val month: Int,
        val day: Int,
        val counter: Int,
    )

    private fun parseVersion(raw: String): ParsedVersion {
        val dash = raw.indexOf('-')
        val base = if (dash >= 0) raw.substring(0, dash) else raw
        val parts = base.split(".").map { it.toIntOrNull() ?: 0 }
        return ParsedVersion(parts, dash >= 0, parseVersionDate(parts))
    }

    /**
     * Recognises the date-based scheme and reads it as a calendar date.
     *
     * The scheme is `DD.MM.YY` optionally followed by a same-day counter
     * (`.2`, `.3`, ...). Two digits of day, two of month and two of year is
     * what makes it unambiguous against the legacy `1.20` scheme, which has
     * one or two parts and never three — so anything with three parts is
     * read as a date.
     *
     * Returns null for anything that isn't a plausible date, which is what
     * keeps the legacy scheme on its own comparison path.
     */
    private fun parseVersionDate(parts: List<Int>): VersionDate? {
        if (parts.size < 3) return null
        val day = parts[0]
        val month = parts[1]
        val year = parts[2]
        if (day !in 1..31 || month !in 1..12) return null
        val counter = parts.getOrElse(3) { 1 }
        return VersionDate(year, month, day, counter)
    }

    /**
     * Whether [latest] is a newer release than the running [current] build.
     *
     * Two schemes have to be compared here, and they do not mean the same
     * thing:
     *
     *  - The date-based scheme (`DD.MM.YY[.N]`) is a *calendar* date, so it
     *    is compared year, then month, then day, then the same-day counter.
     *    Comparing it as plain numbers — the obvious reading of a dotted
     *    version — is wrong the moment the day rolls over: `08.11.26` (8
     *    November) would look *older* than `28.10.26` (28 October) because 8
     *    is less than 28, and a user on the October build would never be told
     *    about the November release at all. That is the bug this ordering
     *    exists to fix.
     *
     *  - The legacy scheme (`1.20`, `1.9`) is a plain number, and there
     *    "1.10" really does outrank "1.9", so it keeps the numeric
     *    comparison.
     *
     * A build on one scheme is always treated as older than a release on the
     * other: the date scheme came later, so anything still reporting `1.N`
     * predates every date-based build and must be offered the update.
     *
     * The "-betaN" suffix (see the debug build type's `versionNameSuffix` in
     * app/build.gradle.kts) marks a pre-release, and a pre-release is treated
     * as older than a plain release of the same numbers — the beta by
     * definition predates the tag it was testing toward.
     */
    internal fun isNewer(latest: String, current: String): Boolean {
        val l = parseVersion(latest)
        val c = parseVersion(current)
        return when {
            l.date != null && c.date != null -> isNewerDate(l, c)
            // A date-based release is newer than a legacy-numbered build, and
            // a legacy-numbered release is never newer than a date-based build.
            l.date != null -> true
            c.date != null -> false
            else -> isNewerNumeric(l, c)
        }
    }

    /** Calendar comparison for two date-based versions. */
    private fun isNewerDate(l: ParsedVersion, c: ParsedVersion): Boolean {
        val ld = l.date!!
        val cd = c.date!!
        return when {
            ld.year != cd.year -> ld.year > cd.year
            ld.month != cd.month -> ld.month > cd.month
            ld.day != cd.day -> ld.day > cd.day
            ld.counter != cd.counter -> ld.counter > cd.counter
            else -> c.isPreRelease && !l.isPreRelease
        }
    }

    /** Numeric comparison for two legacy-numbered versions. */
    private fun isNewerNumeric(l: ParsedVersion, c: ParsedVersion): Boolean {
        for (i in 0 until maxOf(l.parts.size, c.parts.size)) {
            val a = l.parts.getOrElse(i) { 0 }
            val b = c.parts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return c.isPreRelease && !l.isPreRelease
    }
}
