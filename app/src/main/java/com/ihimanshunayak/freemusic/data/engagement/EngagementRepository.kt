package com.ihimanshunayak.freemusic.data.engagement

import android.content.Context
import com.ihimanshunayak.freemusic.data.DebugLog as Log
import com.ihimanshunayak.freemusic.data.innertube.Innertube
import com.ihimanshunayak.freemusic.data.innertube.InnertubeParser
import com.ihimanshunayak.freemusic.data.model.CommentPage
import com.ihimanshunayak.freemusic.data.model.TrackEngagement
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The like, comment and view figures for a track, and its comments.
 *
 * Both are read from YouTube's watch-queue response, which is a large one —
 * about 180 kB for the engagement half, because it carries a whole watch
 * queue with every recommendation in it. That is why nothing here is fetched
 * per row that scrolls past: the player asks once for the track it is
 * actually playing, and the answer is kept.
 *
 * Cached twice over. In memory for the session, and on disk so a track that
 * was opened yesterday still has its figures this morning — a like count
 * that moved by a few hundred overnight is not worth a request, and a
 * listener who skips through tracks should not be paying for one per track.
 *
 * The disk copy expires: the figures are public and they move, and a count
 * that is a week stale is worse than one that is fetched late.
 */
object EngagementRepository {

    private const val TAG = "Free Music"
    private const val CACHE_DIRECTORY = "engagement"
    /** How long a figure on disk is trusted. */
    private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
    /** The most tracks kept on disk at once. */
    private const val MAX_DISK_ENTRIES = 200

    private val memory = ConcurrentHashMap<String, TrackEngagement>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<TrackEngagement?>>()
    private val claimed = ConcurrentHashMap.newKeySet<String>()
    private val inFlightLock = Mutex()

    @Volatile
    private var cacheDirectory: File? = null

    /**
     * Opens the disk cache. Called once at startup, the same way the other
     * on-disk caches are — see `FreeMusicApplication.onCreate`.
     */
    fun init(context: Context) {
        cacheDirectory = File(context.cacheDir, CACHE_DIRECTORY)
    }

    /**
     * The figures for [videoId], or null when YouTube states none.
     *
     * Never throws: a track with no counts is a normal outcome, not an error,
     * and the player's chip row simply draws nothing for it.
     */
    suspend fun engagement(videoId: String): TrackEngagement? {
        if (videoId.isBlank()) return null
        memory[videoId]?.let { return it }
        diskHit(videoId)?.let {
            memory[videoId] = it
            return it
        }
        // One request per track even when the player and a comment sheet ask
        // at the same moment.
        val job = inFlightLock.withLock {
            inFlight.getOrPut(videoId) { CompletableDeferred() }
        }
        if (claimed.add(videoId)) {
            try {
                val result = fetch(videoId)
                if (result != null) {
                    memory[videoId] = result
                    writeDisk(videoId, result)
                }
                job.complete(result)
            } catch (e: Throwable) {
                job.completeExceptionally(e)
                throw e
            } finally {
                claimed.remove(videoId)
                inFlightLock.withLock { inFlight.remove(videoId, job) }
            }
        }
        return runCatching { job.await() }.getOrNull()
    }

    private suspend fun fetch(videoId: String): TrackEngagement? = withContext(Dispatchers.IO) {
        runCatching {
            InnertubeParser.parseEngagement(Innertube.engagement(videoId))
        }.onFailure {
            Log.w(TAG, "engagement for $videoId failed: ${it.message}")
        }.getOrNull()
    }

    /**
     * One page of [videoId]'s comments.
     *
     * The page's token comes out of the same watch-queue response as the
     * figures, so the two are fetched together the first time and the
     * comments come from the cached response afterwards.
     */
    suspend fun comments(videoId: String): Result<CommentPage> = withContext(Dispatchers.IO) {
        runCatching {
            val token = engagement(videoId)?.commentToken
                ?: return@runCatching CommentPage(emptyList(), null)
            InnertubeParser.parseComments(Innertube.comments(token))
        }.onFailure {
            Log.w(TAG, "comments for $videoId failed: ${it.message}")
        }
    }

    /**
     * The next page of comments, given the token the last page ended with.
     */
    suspend fun commentsPage(token: String): Result<CommentPage> = withContext(Dispatchers.IO) {
        runCatching {
            InnertubeParser.parseComments(Innertube.comments(token))
        }.onFailure {
            Log.w(TAG, "comment page failed: ${it.message}")
        }
    }

    // ---- Disk cache --------------------------------------------------------

    private fun fileFor(videoId: String): File? =
        cacheDirectory?.resolve("$videoId.json")

    private fun diskHit(videoId: String): TrackEngagement? {
        val file = fileFor(videoId) ?: return null
        if (!file.isFile) return null
        if (System.currentTimeMillis() - file.lastModified() > CACHE_TTL_MS) {
            file.delete()
            return null
        }
        return runCatching {
            val json = Json.parseToJsonElement(file.readText()).jsonObject
            TrackEngagement(
                likeCount = json["likeCount"]?.let {
                    (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                },
                likeLabel = json["likeLabel"]?.let { (it as? JsonPrimitive)?.contentOrNull },
                commentCount = json["commentCount"]?.let { (it as? JsonPrimitive)?.contentOrNull },
                viewCount = json["viewCount"]?.let { (it as? JsonPrimitive)?.contentOrNull },
                commentToken = json["commentToken"]?.let { (it as? JsonPrimitive)?.contentOrNull },
            )
        }.getOrNull()
    }

    private suspend fun writeDisk(videoId: String, engagement: TrackEngagement) =
        withContext(Dispatchers.IO) {
            val file = fileFor(videoId) ?: return@withContext
            runCatching {
                cacheDirectory?.mkdirs()
                val json = buildJsonObject {
                    engagement.likeCount?.let { put("likeCount", it) }
                    engagement.likeLabel?.let { put("likeLabel", it) }
                    engagement.commentCount?.let { put("commentCount", it) }
                    engagement.viewCount?.let { put("viewCount", it) }
                    engagement.commentToken?.let { put("commentToken", it) }
                }
                file.writeText(json.toString())
                evict()
            }.onFailure { Log.w(TAG, "engagement cache write failed: ${it.message}") }
        }

    /**
     * Keeps the disk cache bounded. The oldest entries go first — a figure
     * is worth keeping least once another track has been played since.
     */
    private fun evict() {
        val directory = cacheDirectory ?: return
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?: return
        if (files.size <= MAX_DISK_ENTRIES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_DISK_ENTRIES)
            .forEach { it.delete() }
    }

    /** Drops everything cached — used when the account changes under the app. */
    fun clear() {
        memory.clear()
        cacheDirectory?.listFiles()?.forEach { it.delete() }
    }
}
