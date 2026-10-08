package com.ihimanshunayak.freemusic

import com.ihimanshunayak.freemusic.data.collab.CollabCredential
import com.ihimanshunayak.freemusic.data.collab.CollabDelta
import com.ihimanshunayak.freemusic.data.collab.CollabException
import com.ihimanshunayak.freemusic.data.collab.CollabMember
import com.ihimanshunayak.freemusic.data.collab.CollabPlaylistType
import com.ihimanshunayak.freemusic.data.collab.CollabPlaylists
import com.ihimanshunayak.freemusic.data.collab.CollabRevision
import com.ihimanshunayak.freemusic.data.collab.CollabRole
import com.ihimanshunayak.freemusic.data.collab.CollabSnapshot
import com.ihimanshunayak.freemusic.data.collab.CollabSummary
import com.ihimanshunayak.freemusic.data.collab.CollabTrack
import com.ihimanshunayak.freemusic.data.model.QueueTier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire types and the pure logic over them.
 *
 * Nothing here talks to a server: these are the decisions the app makes locally
 * about what it was sent, and they are worth pinning because each one is a place
 * where being wrong is not obvious on screen — a card that says "0 tracks", a
 * Blend that offers a delete it cannot perform, a token that arrives uppercased.
 */
class CollabModelsTest {

    /**
     * The same configuration `CollabApi` installs.
     *
     * Duplicated rather than shared so a change to the client's leniency has to
     * be made in two places and one of them is a test that will fail — a client
     * that quietly started rejecting unknown keys would break against a server
     * that added a field, and this test would still be green if it read the
     * client's own Json instance.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ---- Deserialization -------------------------------------------------

    @Test
    fun `server field names map onto the Kotlin spellings`() {
        // Every field spelled the way the Go struct spells it, because a mismatch
        // here is silent: kotlinx.serialization takes the default and the card
        // draws empty rather than failing.
        val raw = """
            {
              "id": "pl_abc",
              "playlistType": "COLLABORATIVE",
              "name": "Road trip",
              "description": "for the drive",
              "coverUrl": "https://example.com/cover.jpg",
              "ownerId": "u_owner",
              "revision": 7,
              "createdAtMs": 1000,
              "updatedAtMs": 2000,
              "viewerRole": "OWNER",
              "members": [
                {
                  "userId": "u_owner",
                  "displayName": "Himanshu",
                  "avatarUrl": null,
                  "role": "OWNER",
                  "joinedAtMs": 1000,
                  "lastActiveMs": 2000
                }
              ],
              "tracks": [
                {
                  "entryId": "tr_1",
                  "videoId": "v1",
                  "title": "Song",
                  "artist": "Artist",
                  "album": "Album",
                  "thumbnailUrl": "https://example.com/t.jpg",
                  "durationMs": 180000,
                  "addedByUserId": "u_owner",
                  "addedByName": "Himanshu",
                  "addedAtMs": 1500,
                  "position": 0
                }
              ]
            }
        """.trimIndent()

        val snapshot = json.decodeFromString<CollabSnapshot>(raw)

        assertEquals("pl_abc", snapshot.id)
        assertEquals(CollabPlaylistType.COLLABORATIVE, snapshot.playlistType)
        assertEquals("Road trip", snapshot.name)
        assertEquals(7L, snapshot.revision)
        assertEquals(CollabRole.OWNER, snapshot.viewerRole)
        assertEquals(1, snapshot.members.size)
        assertEquals("Himanshu", snapshot.members.first().displayName)
        assertEquals(1, snapshot.tracks.size)
        assertEquals("tr_1", snapshot.tracks.first().entryId)
        assertEquals(180000L, snapshot.tracks.first().durationMs)
    }

    @Test
    fun `an unknown server field does not break an older app`() {
        // The posture the whole file takes: the server is free to add a field and
        // an app that predates it must keep working. If this ever fails, every
        // server deploy becomes a breaking change for every install.
        val raw = """{"id":"pl_x","name":"n","somethingNew":{"nested":true}}"""
        val snapshot = json.decodeFromString<CollabSnapshot>(raw)
        assertEquals("pl_x", snapshot.id)
        assertEquals("n", snapshot.name)
    }

    @Test
    fun `a missing optional field takes its default rather than failing`() {
        val raw = """{"id":"pl_x"}"""
        val snapshot = json.decodeFromString<CollabSnapshot>(raw)
        assertEquals("", snapshot.name)
        assertEquals(0L, snapshot.revision)
        assertTrue(snapshot.tracks.isEmpty())
        assertTrue(snapshot.members.isEmpty())
        assertNull(snapshot.blend)
    }

    @Test
    fun `a playlist type the app has never seen is not silently a collaborative one`() {
        // Decoded as PERSONAL here is a real outcome of kotlinx's enum handling
        // for an unknown value being an error — the point of the test is that the
        // failure is loud (an exception) rather than a `when` that falls through
        // to a default and offers edit controls on something it does not know.
        var threw = false
        try {
            json.decodeFromString<CollabSnapshot>("""{"id":"x","playlistType":"SOMETHING_NEW"}""")
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("an unknown playlist type should not decode silently", threw)
    }

    // ---- Snapshot behaviour ----------------------------------------------

    private fun snapshot(
        type: CollabPlaylistType = CollabPlaylistType.COLLABORATIVE,
        role: CollabRole = CollabRole.COLLABORATOR,
        tracks: List<CollabTrack> = emptyList(),
        members: List<CollabMember> = emptyList(),
    ) = CollabSnapshot(
        id = "pl_1",
        playlistType = type,
        name = "Test",
        revision = 3,
        ownerId = "u_owner",
        viewerRole = role,
        tracks = tracks,
        members = members,
    )

    @Test
    fun `a blend is never editable and a collaborative playlist always is`() {
        assertFalse(snapshot(type = CollabPlaylistType.BLEND).isEditable)
        assertTrue(snapshot(type = CollabPlaylistType.COLLABORATIVE).isEditable)
    }

    @Test
    fun `ownership comes from the viewer role not from the owner id`() {
        // The two can disagree: a member looking at a playlist they did not create
        // has a viewerRole of COLLABORATOR and an ownerId that is not theirs, and
        // the server is the one that decided which. Reading ownership off the id
        // would be the client overruling the server on an authorisation question.
        assertTrue(snapshot(role = CollabRole.OWNER).isOwner)
        assertFalse(snapshot(role = CollabRole.COLLABORATOR).isOwner)
    }

    @Test
    fun `a track is found by its entry id and not by its video id`() {
        // The same song can appear twice, added by two people. Looking it up by
        // videoId would find the first and both removes would hit the same entry.
        val first = CollabTrack(entryId = "tr_1", videoId = "same", position = 0)
        val second = CollabTrack(entryId = "tr_2", videoId = "same", position = 1)
        val s = snapshot(tracks = listOf(first, second))

        assertEquals("tr_1", s.trackByEntryId("tr_1")?.entryId)
        assertEquals("tr_2", s.trackByEntryId("tr_2")?.entryId)
        assertNull(s.trackByEntryId("same"))
        assertNull(s.trackByEntryId("tr_3"))
    }

    // ---- toSong ----------------------------------------------------------

    @Test
    fun `a track becomes a song the queue can accept`() {
        val song = CollabTrack(
            entryId = "tr_1",
            videoId = "v1",
            title = "Title",
            artist = "Artist",
            album = "Album",
            thumbnailUrl = "https://example.com/t.jpg",
        ).toSong()

        assertEquals("v1", song.videoId)
        assertEquals("Title", song.title)
        assertEquals("Artist", song.artist)
        assertEquals("Album", song.albumName)
        assertEquals("https://example.com/t.jpg", song.thumbnailUrl)
    }

    @Test
    fun `a shared playlist track carries no queue entry id of its own`() {
        // queueEntryId is assigned when a track actually enters the queue. Setting
        // one here would either be overwritten — making the field a lie — or
        // collide with the queue's own numbering.
        val song = CollabTrack(videoId = "v1").toSong()
        assertNull(song.queueEntryId)
        assertEquals(QueueTier.CONTEXT, song.queueTier)
    }

    // ---- toSummary -------------------------------------------------------

    @Test
    fun `the card form counts what the snapshot holds`() {
        val s = snapshot(
            role = CollabRole.OWNER,
            tracks = listOf(
                CollabTrack(entryId = "a"),
                CollabTrack(entryId = "b"),
                CollabTrack(entryId = "c"),
            ),
            members = listOf(
                CollabMember(userId = "u1"),
                CollabMember(userId = "u2"),
            ),
        )

        val summary = s.toSummary()

        assertEquals("pl_1", summary.id)
        assertEquals(3, summary.trackCount)
        assertEquals(2, summary.memberCount)
        assertEquals(CollabRole.OWNER, summary.viewerRole)
        assertEquals(3L, summary.revision)
    }

    @Test
    fun `the card form takes at most four distinct thumbnails`() {
        // Four is what the stacked-card treatment draws. A fifth is not a
        // rounding error: it is another URL kept alive in a list that scrolls.
        val s = snapshot(
            tracks = listOf(
                CollabTrack(entryId = "a", thumbnailUrl = "u1"),
                CollabTrack(entryId = "b", thumbnailUrl = "u1"), // duplicate
                CollabTrack(entryId = "c", thumbnailUrl = "u2"),
                CollabTrack(entryId = "d", thumbnailUrl = "u3"),
                CollabTrack(entryId = "e", thumbnailUrl = "u4"),
                CollabTrack(entryId = "f", thumbnailUrl = "u5"),
            ),
        )

        val thumbs = s.toSummary().coverThumbs

        assertEquals(4, thumbs.size)
        assertEquals(listOf("u1", "u2", "u3", "u4"), thumbs)
    }

    @Test
    fun `a blank thumbnail is not a cover`() {
        // An empty string reaching an image loader is a request for the app's own
        // host, which answers 404 and puts a broken frame on the card.
        val s = snapshot(
            tracks = listOf(
                CollabTrack(entryId = "a", thumbnailUrl = ""),
                CollabTrack(entryId = "b", thumbnailUrl = null),
                CollabTrack(entryId = "c", thumbnailUrl = "u1"),
            ),
        )
        assertEquals(listOf("u1"), s.toSummary().coverThumbs)
    }

    @Test
    fun `an empty playlist summarizes as an empty card rather than failing`() {
        val summary: CollabSummary = snapshot().toSummary()
        assertEquals(0, summary.trackCount)
        assertEquals(0, summary.memberCount)
        assertTrue(summary.coverThumbs.isEmpty())
    }

    // ---- Credentials -----------------------------------------------------

    @Test
    fun `the owner token is preferred and either one is a token`() {
        val owner = CollabCredential("pl", ownerToken = "o", memberToken = "m")
        assertEquals("o", owner.token)
        assertTrue(owner.isOwner)

        val member = CollabCredential("pl", memberToken = "m")
        assertEquals("m", member.token)
        assertFalse(member.isOwner)

        assertNull(CollabCredential("pl").token)
    }

    // ---- Exceptions ------------------------------------------------------

    @Test
    fun `a revision conflict is recognized and is not treated as a lost credential`() {
        val conflict = CollabException(
            code = CollabException.CODE_REVISION_CONFLICT,
            httpStatus = 409,
            message = "stale",
        )

        assertTrue(conflict.isConcurrencyConflict)
        // The distinction matters: a conflict means reload and retry, whereas a
        // rejected credential means forget the playlist. Treating a conflict as
        // the latter would delete a perfectly good token because somebody else
        // added a song.
        assertFalse(conflict.isCredentialRejected)
    }

    @Test
    fun `a refused or missing playlist reads as a lost credential`() {
        // Both statuses mean the same thing to the client — present a different
        // token or stop offering the playlist — and the server answers 404 rather
        // than 403 in places so that a guess cannot confirm a playlist exists.
        assertTrue(CollabException("forbidden", 403, "no").isCredentialRejected)
        assertTrue(CollabException("no_such_playlist", 404, "no").isCredentialRejected)
        assertFalse(CollabException("bad_request", 400, "no").isCredentialRejected)
    }

    @Test
    fun `an invitation that ran out is not a broken credential`() {
        // A spent invitation means "ask for a new link", which is a message for a
        // person, not a reason to forget a token this device may hold for other
        // playlists.
        val spent = CollabException(CollabException.CODE_INVITE_USED, 410, "used")
        assertFalse(spent.isCredentialRejected)
        assertFalse(spent.isConcurrencyConflict)
        assertEquals("used", spent.message)
    }

    // ---- Deltas ----------------------------------------------------------

    @Test
    fun `a resync delta carries a snapshot and no change list`() {
        val snapshot = CollabSnapshot(id = "pl", revision = 9)
        val delta = CollabDelta(
            playlistId = "pl",
            fromRevision = 2,
            toRevision = 9,
            resync = true,
            snapshot = snapshot,
        )

        assertTrue(delta.resync)
        assertTrue(delta.changes.isEmpty())
        assertEquals(9L, delta.snapshot?.revision)
    }

    @Test
    fun `a delta's payload survives as unparsed json`() {
        // The client deliberately does not interpret a change payload: what a
        // rename means is decided by the server, and re-deriving it here would be
        // a second implementation of the same rule that could disagree.
        val raw = """
            {
              "playlistId": "pl",
              "fromRevision": 1,
              "toRevision": 2,
              "changes": [
                {
                  "revision": 2,
                  "kind": "tracks.add",
                  "atMs": 5000,
                  "byUserId": "u2",
                  "payload": {"entryId": "tr_9", "videoId": "v9"}
                }
              ]
            }
        """.trimIndent()

        val delta = json.decodeFromString<CollabDelta>(raw)

        assertEquals(1, delta.changes.size)
        val change: CollabRevision = delta.changes.first()
        assertEquals("tracks.add", change.kind)
        assertEquals("u2", change.byUserId)
        val payload = change.payload["entryId"] as? JsonObject
        assertNull("entryId is a string, not an object", payload)
        assertEquals("tr_9", (change.payload["entryId"] as JsonPrimitive).jsonPrimitive.content)
    }

    // ---- Browse ids ------------------------------------------------------

    @Test
    fun `a shared page id round trips and does not collide with a device one`() {
        val pageId = CollabPlaylists.pageIdFor("pl_123")
        assertEquals("shared:pl_123", pageId)
        assertEquals("pl_123", CollabPlaylists.idOf(pageId))
    }

    @Test
    fun `a device playlist id is not read as a shared one and the reverse`() {
        // The two are dispatched to different screens by these two helpers, so an
        // id that answered true for both would be drawn by whichever check ran
        // first — and the two pages disagree about whether an edit can be
        // refused.
        val shared = CollabPlaylists.pageIdFor("pl_123")
        assertNull(com.ihimanshunayak.freemusic.data.playlist.PlaylistStore.idOf(shared))
        assertNull(CollabPlaylists.idOf("local:mine:xyz"))
    }

    @Test
    fun `an id that carries no prefix at all is not a shared page`() {
        // A YouTube playlist id, a video id, an album id — all of them reach this
        // helper on the way to `browseTypeOf`, and none of them may be read as a
        // shared playlist.
        assertNull(CollabPlaylists.idOf("PLabc123"))
        assertNull(CollabPlaylists.idOf("dQw4w9WgXcQ"))
        assertNull(CollabPlaylists.idOf("local:downloads"))
        assertNull(CollabPlaylists.idOf(null))
    }

    @Test
    fun `the shared prefix itself is not an id`() {
        // `shared:` with nothing after it would dispatch a screen that asks the
        // server about the empty string.
        assertNull(CollabPlaylists.idOf(CollabPlaylists.BROWSE_PREFIX))
    }
}
