package com.ihimanshunayak.freemusic.data.playlist

import com.ihimanshunayak.freemusic.data.model.QueueTier
import com.ihimanshunayak.freemusic.data.model.Song
import kotlinx.serialization.Serializable

/**
 * A [Song] as it is written to disk.
 *
 * [Song] itself is not serializable, and making it so would put the wire
 * format of the catalogue — browse ids, tokens, tier and source plumbing that
 * only a live queue needs — into a file whose job is to remember what the
 * listener wanted to keep. This keeps instead the fields a playlist row draws
 * and a playback request needs, which is what makes a playlist readable years
 * later without a migration for every parser field that comes and goes.
 *
 * Every field is defaulted and every decode goes through [toSong]'s null
 * handling, so a document written by an older build opens in a newer one with
 * the missing fields simply absent rather than refusing to parse at all.
 */
@Serializable
data class StoredSong(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    val isVideo: Boolean = false,
    val isExplicit: Boolean? = null,
    val localUri: String? = null,
    val localPath: String? = null,
) {
    fun toSong(): Song = Song(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
        artistId = artistId,
        albumId = albumId,
        albumName = albumName,
        isVideo = isVideo,
        isExplicit = isExplicit,
        localUri = localUri,
        localPath = localPath,
        // A playlist row is a context, not a queue entry: it has no entry id
        // yet, and stamping one here would collide with the identity
        // QueueCoordinator hands out when the track is actually played. The
        // tier is CONTEXT for the same reason — the track is being played
        // *from* the playlist, so the playlist is the context. Which playlist
        // that was is named by the caller, which is the only place that knows.
        queueTier = QueueTier.CONTEXT,
    )

    companion object {
        fun from(song: Song) = StoredSong(
            videoId = song.videoId,
            title = song.title,
            artist = song.artist,
            thumbnailUrl = song.thumbnailUrl,
            durationText = song.durationText,
            artistId = song.artistId,
            albumId = song.albumId,
            albumName = song.albumName,
            isVideo = song.isVideo,
            isExplicit = song.isExplicit,
            localUri = song.localUri,
            localPath = song.localPath,
        )
    }
}
