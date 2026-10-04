// Copyright (c) A|iens. All rights reserved.
//
// Free Music — notifications model
//
// Name:     Notifications.kt
// Version:  1.0
// Author:   Himanshu Nayak
// Requires: Kotlin 2.x, Compose Runtime
// Purpose:  The row model behind the top bar's notification button.
//
// The notification list is composed on the device rather than fetched. YouTube
// Music's own notification menu answers signed-out sessions with an empty popup
// and carries no payload even signed in through this client, so a surface that
// waited on it would be a button that never has anything in it. What the app
// can honestly report instead is what it already knows: an update it has found,
// transfers it is running, and the newest releases it has fetched. Those are
// states rather than events, which is exactly why they can be read out here
// without a server telling us about them.

package com.ihimanshunayak.freemusic.data.model

/**
 * What kind of thing a notification is about.
 *
 * Kept as a type rather than a colour or an icon so the drawing end decides
 * how each kind looks, and a new kind does not need a new field here.
 */
enum class NotificationKind {
    /** A newer release of the app itself is available. */
    UPDATE,

    /** A track is being saved to the device. */
    DOWNLOAD,

    /** A track is being sent to the WebDAV server. */
    UPLOAD,

    /** Something arrived in the newest-releases shelf. */
    RELEASE,

    /** A transfer that did not finish. */
    FAILED,
}

/**
 * One row in the notification list.
 *
 * [key] is stable across rebuilds of the list — the icon and the row identity
 * both hang off it — while [id] is what the row opens when tapped, and is null
 * for a row that is purely informational.
 */
data class NotificationEntry(
    val key: String,
    val kind: NotificationKind,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    /** Fraction complete for a transfer in flight; ignored by every other kind. */
    val progress: Float? = null,
    /** The detail page to open, when the row points at one. */
    val browseId: String? = null,
    /** The track to play, when the row points at one. */
    val videoId: String? = null,
)
