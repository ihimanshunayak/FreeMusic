package com.ihimanshunayak.freemusic

import com.ihimanshunayak.freemusic.data.model.QueueTier
import com.ihimanshunayak.freemusic.data.model.Song
import com.ihimanshunayak.freemusic.data.playlist.PlaylistStore
import com.ihimanshunayak.freemusic.data.playlist.StoredSong
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device-playlist store's logic, exercised without a [android.content.Context].
 *
 * [PlaylistStore.init] is deliberately not called here. It takes a `Context` to
 * find `filesDir`, and this suite's `android.jar` is a stub that returns
 * defaults rather than a filesystem — so a test that called it would be testing
 * the stub. Everything worth pinning is reachable without it: the mutators run
 * against the store's own published list and the write is skipped when the
 * store was never initialised (see `write`'s `::file.isInitialized` guard),
 * which is what makes these tests exercise real logic rather than a mock.
 *
 * That leaves the file format itself untested here. The round trip it protects —
 * a document written by one build read back by the next — is covered by
 * [storedSongSurvivesARoundTripThroughJson] at the level it actually can be
 * tested, which is the serialisable type rather than the file behind it.
 *
 * Every test clears the store first, because it is an `object`: the state
 * outlives a single test method, and one that left a playlist behind would
 * change what the next one sees.
 */
class PlaylistStoreTest {

    private fun setUpEmptyStore() = runBlocking {
        PlaylistStore.clearAll()
        assertTrue("Store did not start empty", PlaylistStore.current().isEmpty())
    }

    private fun song(id: String, title: String = "Track $id") = Song(
        videoId = id,
        title = title,
        artist = "Artist $id",
        thumbnailUrl = "https://example.test/$id.jpg",
        durationText = "3:00",
    )

    // ---- Threading ------------------------------------------------------

    @Test
    fun mutationsAreVisibleImmediatelyWithoutInit() = runBlocking {
        setUpEmptyStore()
        val id = PlaylistStore.create("Late night")
        assertEquals(1, PlaylistStore.current().size)
        assertEquals("Late night", PlaylistStore.find(id)?.name)
    }

    @Test
    fun anUnwrittenStoreIsStillReadable() = runBlocking {
        setUpEmptyStore()
        PlaylistStore.create("One")
        PlaylistStore.create("Two")
        // No file was ever written, so these only exist in memory. That is the
        // right answer for a session, and it is what makes the write failing
        // worth its own flag rather than a crash.
        assertEquals(2, PlaylistStore.current().size)
    }

    // ---- Naming ---------------------------------------------------------

    @Test
    fun aBlankNameIsRefused() = runBlocking {
        setUpEmptyStore()
        assertThrowsPlaylistError { PlaylistStore.create("") }
        assertThrowsPlaylistError { PlaylistStore.create("   ") }
        assertThrowsPlaylistError { PlaylistStore.create("\n\t ") }
        assertTrue("A refused create must not leave a playlist", PlaylistStore.current().isEmpty())
    }

    @Test
    fun namesAreTrimmed() = runBlocking {
        setUpEmptyStore()
        val id = PlaylistStore.create("  Road trip  ")
        assertEquals("Road trip", PlaylistStore.find(id)?.name)
    }

    @Test
    fun namesAreCappedRatherThanRefused() = runBlocking {
        setUpEmptyStore()
        val id = PlaylistStore.create("x".repeat(500))
        // Capped, not thrown: a pasted name is a legitimate name that happens to
        // be long, and refusing it would be a save button that did nothing.
        assertEquals(120, PlaylistStore.find(id)?.name?.length)
    }

    @Test
    fun playlistsAreDistinctById() = runBlocking {
        setUpEmptyStore()
        val first = PlaylistStore.create("Same name")
        val second = PlaylistStore.create("Same name")
        assertNotEquals(first, second)
        assertEquals(2, PlaylistStore.current().size)
    }

    // ---- Adding ---------------------------------------------------------

    @Test
    fun addingReturnsHowManyWereActuallyAdded() = runBlocking {
        setUpEmptyStore()
        val id = PlaylistStore.create("Mix")
        assertEquals(2, PlaylistStore.addAll(id, listOf(song("a"), song("b"))))
        // One new, one already there.
        assertEquals(1, PlaylistStore.addAll(id, listOf(song("b"), song("c"))))
        assertEquals(3, PlaylistStore.find(id)?.size)
    }

    @Test
    fun aDuplicateIsSkippedNotAppended() = runBlocking {
        setUpEmptyStore()
        val id = PlaylistStore.create("Mix")
        PlaylistStore.addAll(id, listOf(song("a")))
        assertEquals(0, PlaylistStore.addAll(id, listOf(song("a"))))
        assertEquals(1, PlaylistStore.find(id)?.size)
    }

    @Test
    fun aDuplicateWithinOneCallIsCollapsed() = runBlocking {
        setUpEmptyStore()
        val id = PlaylistStore.create("Mix")
        assertEquals(1, PlaylistStore.addAll(id, listOf(song("a"), song("a"), song("a"))))
        assertEquals(1, PlaylistStore.find(id)?.size)
    }

    @Test
    fun addingToAnUnknownPlaylistAddsNothing() = runBlocking {
        setUpEmptyStore()
        assertEquals(0, PlaylistStore.addAll("no-such-id", listOf(song("a"))))
        assertTrue(PlaylistStore.current().isEmpty())
    }

    @Test
    fun addingNothingIsANoOp() = runBlocking {
        setUpEmptyStore()
        val id = PlaylistStore.create("Mix")
        assertEquals(0, PlaylistStore.addAll(id, emptyList()))
        assertEquals(0, PlaylistStore.find(id)?.size)
    }

    @Test
    fun aSongsMatchIsKeptInStoredOrder() = runBlocking {
        setUpEmptyStore()
        val id = PlaylistStore.create("Mix")
        PlaylistStore.addAll(id, listOf(song("c"), song("a"), song("b")))
        // Appended in the order given — a playlist added to is a list the
        // listener is building, and re-sorting it would lose the order they
        // chose.
        assertEquals(
            listOf("c", "a", "b"),
            PlaylistStore.find(id)?.tracks?.map { it.videoId },
        )
    }

    // ---- Removing -------------------------------------------------------

    @Test
    fun removingTakesTheTrackOut() = runBlocking {
        setUpEmptyStore()
        val id = PlaylistStore.create("Mix")
        PlaylistStore.addAll(id, listOf(song("a"), song("b")))
        assertTrue(PlaylistStore.remove(id, "a"))
        assertEquals(listOf("b"), PlaylistStore.find(id)?.tracks?.map { it.videoId })
    }

    @Test
    fun removingSomethingAbsentReportsNoChange() = runBlocking {
        setUpEmptyStore()
        val id = PlaylistStore.create("Mix")
        PlaylistStore.addAll(id, listOf(song("a")))
        assertFalse(PlaylistStore.remove(id, "zzz"))
        assertEquals(1, PlaylistStore.find(id)?.size)
    }

    // ---- Reordering -----------------------------------------------------

    @Test
    fun movingDownLandsWhereItLookedLikeItWould() = runBlocking {
        setUpEmptyStore()
        val id = PlaylistStore.create("Mix")
        PlaylistStore.addAll(id, listOf(song("a"), song("b"), song("c")))
        // Index-based, read the way a list reads it: "a" dragged to slot 2 puts
        // it after "b" and shifts "b" up.
        assertTrue(PlaylistStore.move(id, 0, 2))
        assertEquals(
            listOf("b", "c", "a"),
            PlaylistStore.find(id)?.tracks?.map { it.videoId },
        )
    }

    @Test
    fun movingUpLeavesTheRestInOrder() = runBlocking {
        setUpEmptyStore()
        val id = PlaylistStore.create("Mix")
        PlaylistStore.addAll(id, listOf(song("a"), song("b"), song("c")))
        assertTrue(PlaylistStore.move(id, 2, 0))
        assertEquals(
            listOf("c", "a", "b"),
            PlaylistStore.find(id)?.tracks?.map { it.videoId },
        )
    }

    @Test
    fun aMoveThatChangesNothingIsReportedAsSuch() = runBlocking {
        setUpEmptyStore()
        val id = PlaylistStore.create("Mix")
        PlaylistStore.addAll(id, listOf(song("a"), song("b")))
        assertFalse(PlaylistStore.move(id, 1, 1))
        assertFalse("Out of range", PlaylistStore.move(id, 0, 5))
        assertFalse("Out of range", PlaylistStore.move(id, -1, 0))
        assertEquals(
            listOf("a", "b"),
            PlaylistStore.find(id)?.tracks?.map { it.videoId },
        )
    }

    @Test
    fun movingOnAnEmptyPlaylistIsSafe() = runBlocking {
        setUpEmptyStore()
        val id = PlaylistStore.create("Empty")
        assertFalse(PlaylistStore.move(id, 0, 0))
    }

    // ---- Rename / delete ------------------------------------------------

    @Test
    fun renameTakesAndRefusesTheSameNamesCreateDoes() = runBlocking {
        setUpEmptyStore()
        val id = PlaylistStore.create("Before")
        assertTrue(PlaylistStore.rename(id, "  After  "))
        assertEquals("After", PlaylistStore.find(id)?.name)
        assertThrowsPlaylistError { PlaylistStore.rename(id, "   ") }
        // Refused, so the old name must survive — a rename that threw after
        // applying would leave the playlist nameless.
        assertEquals("After", PlaylistStore.find(id)?.name)
    }

    @Test
    fun renamingAnUnknownPlaylistReportsNoChange() = runBlocking {
        setUpEmptyStore()
        assertFalse(PlaylistStore.rename("no-such-id", "Nope"))
    }

    @Test
    fun deleteRemovesOnlyTheOneAskedFor() = runBlocking {
        setUpEmptyStore()
        val keep = PlaylistStore.create("Keep")
        val drop = PlaylistStore.create("Drop")
        assertTrue(PlaylistStore.delete(drop))
        assertNull(PlaylistStore.find(drop))
        assertEquals("Keep", PlaylistStore.find(keep)?.name)
        assertFalse("Already gone", PlaylistStore.delete(drop))
    }

    // ---- Round trip through the stored form -----------------------------

    @Test
    fun storedSongSurvivesARoundTripThroughJson() {
        // The file format's whole promise: a playlist written by this build is
        // read back by the next one. Pinned at the type that actually crosses
        // the boundary, since the file behind it needs a Context to test.
        val json = Json { ignoreUnknownKeys = true }
        val original = song("abc")
        val restored = json.decodeFromString(
            StoredSong.serializer(),
            json.encodeToString(StoredSong.serializer(), StoredSong.from(original)),
        ).toSong()

        assertEquals(original.videoId, restored.videoId)
        assertEquals(original.title, restored.title)
        assertEquals(original.artist, restored.artist)
        assertEquals(original.thumbnailUrl, restored.thumbnailUrl)
        assertEquals(original.durationText, restored.durationText)
    }

    @Test
    fun aStoredSongBecomesAContextRowAndNothingElse() {
        val restored = StoredSong.from(song("abc")).toSong()
        // The two fields that must NOT be invented on the way back: an entry id
        // belongs to the queue, and anything but CONTEXT would make a playlist
        // row claim to be part of some other tier's timeline.
        assertNull("A stored row must not carry a queue entry id", restored.queueEntryId)
        assertEquals(QueueTier.CONTEXT, restored.queueTier)
    }

    @Test
    fun aDocumentMissingNewerFieldsStillParses() {
        // What an older build's file looks like to this one. Everything but the
        // three required fields is absent, and it must still open rather than
        // taking the whole library down with it.
        val minimal = """{"videoId":"v1","title":"T","artist":"A"}"""
        val restored = Json { ignoreUnknownKeys = true }
            .decodeFromString(StoredSong.serializer(), minimal)
            .toSong()
        assertEquals("v1", restored.videoId)
        assertNull(restored.thumbnailUrl)
        assertNull(restored.albumId)
    }

    // ---- Page ids -------------------------------------------------------

    @Test
    fun pageIdsRoundTrip() {
        val id = "3f2a-1b"
        val pageId = PlaylistStore.pageIdFor(id)
        assertEquals("local:mine:3f2a-1b", pageId)
        assertEquals(id, PlaylistStore.idOf(pageId))
    }

    @Test
    fun somethingElseIsNotADevicePlaylist() {
        // The prefixes this must not swallow. `local:playlist:` is a downloaded
        // playlist — a read-only copy of something on YouTube — and reading it
        // as one of these would offer Rename on a snapshot. Everything else is
        // not ours at all.
        assertNull(PlaylistStore.idOf(null))
        assertNull(PlaylistStore.idOf(""))
        assertNull(PlaylistStore.idOf("local:mine:"))
        assertNull(PlaylistStore.idOf("local:playlist:MPREb_abc"))
        assertNull(PlaylistStore.idOf("local:downloads"))
        assertNull(PlaylistStore.idOf("local:all"))
        assertNull(PlaylistStore.idOf("PLabc123"))
        assertNull(PlaylistStore.idOf("UCsomeChannel"))
    }

    @Test
    fun aPageIdWithAnAwkwardIdStillRoundTrips() {
        // Nothing stops an imported archive from carrying an id with a colon in
        // it, and the prefix must come off exactly once rather than repeatedly.
        val id = "a:b:c"
        assertEquals(id, PlaylistStore.idOf(PlaylistStore.pageIdFor(id)))
    }

    // ---- Export / import round trip -------------------------------------

    @Test
    fun exportThenImportIntoAnEmptyStoreRestoresEveryPlaylist() = runBlocking {
        setUpEmptyStore()
        val first = PlaylistStore.create("Road trip")
        PlaylistStore.addAll(first, listOf(song("a"), song("b")))
        val second = PlaylistStore.create("Focus")
        PlaylistStore.addAll(second, listOf(song("c")))

        val document = PlaylistStore.export()
        PlaylistStore.clearAll()
        assertTrue(PlaylistStore.current().isEmpty())

        assertEquals(2, PlaylistStore.import(document))
        assertEquals(2, PlaylistStore.current().size)
        assertEquals(listOf("a", "b"), PlaylistStore.find(first)?.tracks?.map { it.videoId })
        assertEquals(listOf("c"), PlaylistStore.find(second)?.tracks?.map { it.videoId })
        assertEquals("Road trip", PlaylistStore.find(first)?.name)
    }

    @Test
    fun importMergesIntoThePlaylistThatIsAlreadyHere() = runBlocking {
        // The reinstall case: the same playlist comes back with tracks the
        // local copy doesn't have yet, and the two must become one playlist
        // rather than two with the same name.
        setUpEmptyStore()
        val id = PlaylistStore.create("Mix")
        PlaylistStore.addAll(id, listOf(song("a")))
        val document = PlaylistStore.export()

        PlaylistStore.addAll(id, listOf(song("b")))
        assertEquals("Nothing new to create", 0, PlaylistStore.import(document))

        assertEquals(1, PlaylistStore.current().size)
        // Local order wins, and the track the archive didn't have is still there.
        assertEquals(listOf("a", "b"), PlaylistStore.find(id)?.tracks?.map { it.videoId })
    }

    @Test
    fun importKeepsTheLocalName() = runBlocking {
        // A playlist renamed on this device must not be renamed back by an
        // older backup of the same playlist.
        setUpEmptyStore()
        val id = PlaylistStore.create("Old name")
        PlaylistStore.addAll(id, listOf(song("a")))
        val document = PlaylistStore.export()

        PlaylistStore.rename(id, "New name")
        PlaylistStore.import(document)

        assertEquals("New name", PlaylistStore.find(id)?.name)
    }

    @Test
    fun importRefusesSomethingThatIsNotABackup() = runBlocking {
        setUpEmptyStore()
        assertThrowsPlaylistError { PlaylistStore.import("not json at all") }
        assertThrowsPlaylistError { PlaylistStore.import("""{"hello":"world"}""") }
        assertTrue(PlaylistStore.current().isEmpty())
    }

    @Test
    fun anEmptyArchiveImportsNothing() = runBlocking {
        setUpEmptyStore()
        assertEquals(0, PlaylistStore.import("""{"exportedAt":1,"playlists":[]}"""))
        assertTrue(PlaylistStore.current().isEmpty())
    }

    @Test
    fun importSkipsPlaylistsWithNoId() = runBlocking {
        setUpEmptyStore()
        val document = """{"exportedAt":1,"playlists":[{"id":"","name":"Nameless","createdAt":1,"updatedAt":1,"tracks":[]}]}"""
        assertEquals(0, PlaylistStore.import(document))
        assertTrue(PlaylistStore.current().isEmpty())
    }

    // ---- The writable flag ----------------------------------------------

    @Test
    fun anUninitialisedStoreIsNotReportedUnwritable() = runBlocking {
        setUpEmptyStore()
        // Writes are skipped entirely without a file, which is not a failure —
        // reporting it as one would put a warning on screen in every test and
        // preview that never called init.
        PlaylistStore.create("Anything")
        assertTrue(PlaylistStore.writable.value)
    }

    // ---- The archive, as the app's backup carries it ---------------------

    @Test
    fun archiveCarriesEveryPlaylistAndItsTracks() = runBlocking {
        setUpEmptyStore()
        val id = PlaylistStore.create("Road trip")
        PlaylistStore.addAll(id, listOf(song("a"), song("b")))

        val archive = PlaylistStore.archive()
        assertEquals(1, archive.playlists.size)
        assertEquals("Road trip", archive.playlists.first().name)
        assertEquals(
            listOf("a", "b"),
            archive.playlists.first().tracks.map { it.videoId },
        )
        assertTrue("An archive has to say when it was taken", archive.exportedAt > 0)
    }

    @Test
    fun anArchiveSurvivesJsonWhenNestedInsideAnotherDocument() = runBlocking {
        // What the app's backup actually does: this archive is a field inside
        // the backup file, not a document of its own. Pinned because the
        // playlist type is serialised from inside another serialisable type
        // there, and a missing serializer only shows up at that depth.
        setUpEmptyStore()
        val id = PlaylistStore.create("Nested")
        PlaylistStore.addAll(id, listOf(song("a")))

        val json = Json { ignoreUnknownKeys = true }
        val document = json.encodeToString(
            PlaylistStore.Archive.serializer(),
            PlaylistStore.archive(),
        )
        val restored = json.decodeFromString(PlaylistStore.Archive.serializer(), document)

        assertEquals(1, restored.playlists.size)
        assertEquals("Nested", restored.playlists.first().name)
        assertEquals("a", restored.playlists.first().tracks.first().videoId)
    }

    @Test
    fun mergeIsTheSameOperationImportRuns() = runBlocking {
        setUpEmptyStore()
        val id = PlaylistStore.create("Mix")
        PlaylistStore.addAll(id, listOf(song("a")))
        val archive = PlaylistStore.archive()

        PlaylistStore.clearAll()
        assertEquals(1, PlaylistStore.merge(archive))
        assertEquals("Mix", PlaylistStore.find(id)?.name)
        assertEquals(listOf("a"), PlaylistStore.find(id)?.tracks?.map { it.videoId })
    }

    @Test
    fun mergingAnEmptyArchiveChangesNothing() = runBlocking {
        setUpEmptyStore()
        PlaylistStore.create("Untouched")
        assertEquals(
            0,
            PlaylistStore.merge(
                PlaylistStore.Archive(exportedAt = 1, playlists = emptyList()),
            ),
        )
        assertEquals(1, PlaylistStore.current().size)
    }

    // ---- Helpers --------------------------------------------------------

    private fun assertThrowsPlaylistError(block: suspend () -> Unit) {
        val thrown = runCatching { runBlocking { block() } }.exceptionOrNull()
        assertTrue(
            "Expected a PlaylistError, got ${thrown?.javaClass?.simpleName}",
            thrown is PlaylistStore.PlaylistError,
        )
    }
}
