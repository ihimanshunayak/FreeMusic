// Copyright (c) A|iens. All rights reserved.
//
// Free Music — notification list composer
//
// Name:     NotificationFeed.kt
// Version:  1.0
// Author:   Himanshu Nayak
// Requires: Kotlin 2.x
// Purpose:  Turns the app's own in-flight and newly-arrived state into the rows
//           the notification surface shows.
//
// Why this exists rather than a server call: YouTube Music's notification menu
// answers this client with an empty popup — see the note at the top of
// NotificationsScreen.kt. So the list is built from what the app already holds,
// which has two consequences worth stating plainly:
//
//   1. It can never be stale, because every input is a live StateFlow.
//   2. It is honest about its own contents — every row is something the app is
//      actually doing, rather than a claim about what YouTube thinks is new.
//
// Ordering is deliberate and fixed rather than by timestamp: a failed transfer
// is the only row that needs the user to do something, so it leads; a transfer
// in flight is next because it is happening now; an update is next because it is
// the one thing here the user may want to act on; releases come last because
// they are the only purely informational kind.

package com.ihimanshunayak.freemusic.ui.screens

import android.content.Context
import com.ihimanshunayak.freemusic.R
import com.ihimanshunayak.freemusic.data.AppUpdateChecker
import com.ihimanshunayak.freemusic.data.model.HomeShelf
import com.ihimanshunayak.freemusic.data.model.NotificationEntry
import com.ihimanshunayak.freemusic.data.model.NotificationKind
import com.ihimanshunayak.freemusic.data.model.ShelfItem
import com.ihimanshunayak.freemusic.data.webdav.WebDavUploads
import com.ihimanshunayak.freemusic.download.DownloadState

/**
 * Builds the notification list. Pure apart from [context], which is only used
 * to resolve the row copy — every input still arrives as a parameter, so the
 * ordering rules above are readable in one place.
 */
object NotificationFeed {
    /** How many newest-release rows are shown before the list starts to sprawl. */
    private const val RELEASE_ROWS = 3

    /**
     * @param context used only to resolve the row copy, so that the strings
     *   live with every other user-visible string in the app.
     * @param update the update the checker found, if any.
     * @param downloads tracks being saved right now, by videoId.
     * @param titles videoId to the track's own title, for naming a row. An id
     *   in [downloads] with no entry here falls back to showing the id, which
     *   is what happens for a transfer that started before its tags were read.
     * @param uploads tracks being sent to the server right now.
     * @param newReleases the newest-releases shelf, when the feed carried one.
     */
    fun build(
        context: Context,
        update: AppUpdateChecker.UpdateInfo?,
        downloads: Map<String, DownloadState>,
        titles: Map<String, String>,
        uploads: Map<String, WebDavUploads.TrackState>,
        newReleases: HomeShelf?,
    ): List<NotificationEntry> {
        // Failures first — see the ordering note at the top of this file. Split
        // by kind rather than sorted, because "failed" is a different reading of
        // the same transfer and belongs above one that is still going.
        val transfers = downloadRows(context, downloads, titles)
        val network = uploadRows(context, uploads)
        val entries = mutableListOf<NotificationEntry>()
        entries += transfers.filter { it.kind == NotificationKind.FAILED }
        entries += network.filter { it.kind == NotificationKind.FAILED }
        entries += transfers.filter { it.kind != NotificationKind.FAILED }
        entries += network.filter { it.kind != NotificationKind.FAILED }

        update?.let { info ->
            entries += NotificationEntry(
                key = "update:${info.version}",
                kind = NotificationKind.UPDATE,
                title = context.getString(R.string.notifications_update_available, info.version),
                // The first non-blank line of the release's own notes, which is
                // the release's headline — the whole body belongs in the update
                // dialog, not on a one-line notification row.
                subtitle = info.notes?.lineSequence()
                    ?.firstOrNull { it.isNotBlank() }
                    ?.trim()
                    .orEmpty(),
            )
        }

        newReleases?.items?.take(RELEASE_ROWS)?.forEachIndexed { index, item ->
            entries += releaseRow(index, item)
        }

        return entries
    }

    private fun downloadRows(
        context: Context,
        states: Map<String, DownloadState>,
        titles: Map<String, String>,
    ): List<NotificationEntry> = states.map { (id, state) ->
        when (state) {
            // A failure is the one state that is not a progress report, so it
            // takes the FAILED kind and inverts to the error colour on the row.
            is DownloadState.Failed -> NotificationEntry(
                key = "download:$id",
                kind = NotificationKind.FAILED,
                title = titles[id] ?: id,
                subtitle = state.reason,
                videoId = id,
            )
            is DownloadState.Running -> NotificationEntry(
                key = "download:$id",
                kind = NotificationKind.DOWNLOAD,
                title = titles[id] ?: id,
                subtitle = context.getString(R.string.notifications_saving, percent(state.fraction)),
                progress = state.fraction,
                videoId = id,
            )
            is DownloadState.Queued -> NotificationEntry(
                key = "download:$id",
                kind = NotificationKind.DOWNLOAD,
                title = titles[id] ?: id,
                subtitle = context.getString(R.string.notifications_queued),
                videoId = id,
            )
        }
    }

    private fun uploadRows(
        context: Context,
        uploads: Map<String, WebDavUploads.TrackState>,
    ): List<NotificationEntry> =
        uploads.mapNotNull { (id, state) ->
            when (state) {
                is WebDavUploads.TrackState.Failed -> NotificationEntry(
                    key = "upload:$id",
                    kind = NotificationKind.FAILED,
                    title = id,
                    subtitle = state.reason,
                )
                is WebDavUploads.TrackState.Running -> NotificationEntry(
                    key = "upload:$id",
                    kind = NotificationKind.UPLOAD,
                    title = id,
                    subtitle = context.getString(R.string.notifications_uploading, percent(state.fraction)),
                    progress = state.fraction,
                )
                is WebDavUploads.TrackState.Queued -> NotificationEntry(
                    key = "upload:$id",
                    kind = NotificationKind.UPLOAD,
                    title = id,
                    subtitle = context.getString(R.string.notifications_queued_upload),
                )
                // Done and Skipped are finished business, not news: the upload
                // sheet is where a finished batch is read, and repeating every
                // completed track here would bury the rows that still matter.
                is WebDavUploads.TrackState.Done, is WebDavUploads.TrackState.Skipped -> null
            }
        }

    private fun releaseRow(index: Int, item: ShelfItem) = NotificationEntry(
        // Indexed rather than keyed off the item's own identity: two rows in a
        // releases shelf can share a title and an id, and a list key has to be
        // unique or the lazy list throws. The index is stable for this build of
        // the list, which is all a row key has to be.
        key = "release:$index",
        kind = NotificationKind.RELEASE,
        title = item.title,
        subtitle = item.subtitle,
        thumbnailUrl = item.thumbnailUrl,
        browseId = item.browseId,
        videoId = item.videoId,
    )

    private fun percent(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100).toInt()
}
