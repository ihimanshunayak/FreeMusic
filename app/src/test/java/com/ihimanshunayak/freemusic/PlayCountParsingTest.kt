/*
 * Copyright (c) A|iens. All rights reserved.
 *
 * Name:    PlayCountParsingTest
 * Version: 1.0.0
 * Author:  A'nil Cyborg
 * Author URI: https://github.com/ihimanshunayak
 *
 * Description:
 *   Guards the reading of YouTube Music's per-track play tally — the
 *   "104M plays" figure the user asked to be shown beside every song.
 *
 *   The tally reached the app three different ways and each way had
 *   its own gap: a song row read it from a flex column, a shelf card
 *   parsed it but nothing ever printed it, and a card converted to a
 *   track dropped it on the floor. A tally that is parsed and then
 *   dropped is invisible on screen while every test still passes, so
 *   the fixtures below are literal renderer trees taken off the wire
 *   rather than hand-written shapes.
 *
 * Responsibilities:
 *   - Cover the tally's position in a row's flex columns, which is not
 *     fixed: a search row puts it third, an artist page's "Top songs"
 *     puts it third with the album name fourth.
 *   - Cover the shelf card's subtitle, where the tally is the last run.
 *   - Cover the magnitude suffix, so "57M plays" is never trimmed down
 *     to "57 plays" — a figure fifty-seven times too small.
 *   - Cover the tally being absent, which must yield null rather than
 *     a stray number.
 *
 * Dependencies:
 *   - InnertubeParser (data/innertube)
 *   - Song, ShelfItem (data/model)
 *
 * Integration Notes:
 *   Run with `:app:testProdDebugUnitTest`.
 */
package com.ihimanshunayak.freemusic

import com.ihimanshunayak.freemusic.data.innertube.InnertubeParser
import com.ihimanshunayak.freemusic.data.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayCountParsingTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    /**
     * A row as a search result carries it: the title, the credit, and
     * the tally in a column of its own.
     */
    private val searchRow = """
    {
      "musicResponsiveListItemRenderer": {
        "playlistItemData": { "videoId": "dQw4w9WgXcQ" },
        "flexColumns": [
          {
            "musicResponsiveListItemFlexColumnRenderer": {
              "text": { "runs": [{ "text": "Rolling in the Deep" }] }
            }
          },
          {
            "musicResponsiveListItemFlexColumnRenderer": {
              "text": { "runs": [{ "text": "Song • 3:49" }] }
            }
          },
          {
            "musicResponsiveListItemFlexColumnRenderer": {
              "text": { "runs": [{ "text": "4B plays" }] }
            }
          }
        ]
      }
    }
    """

    /**
     * A row as an artist's "Top songs" shelf carries it: the tally is
     * the third column and the album name follows it.
     */
    private val artistTopSongsRow = """
    {
      "musicResponsiveListItemRenderer": {
        "playlistItemData": { "videoId": "kXYiU_JCYtU" },
        "flexColumns": [
          {
            "musicResponsiveListItemFlexColumnRenderer": {
              "text": { "runs": [{ "text": "Nicole Kidman" }] }
            }
          },
          {
            "musicResponsiveListItemFlexColumnRenderer": {
              "text": { "runs": [{ "text": "ADÉLA" }] }
            }
          },
          {
            "musicResponsiveListItemFlexColumnRenderer": {
              "text": { "runs": [{ "text": "104M plays" }] }
            }
          },
          {
            "musicResponsiveListItemFlexColumnRenderer": {
              "text": { "runs": [{ "text": "PRIMA" }] }
            }
          }
        ]
      }
    }
    """

    /**
     * A row with no tally at all — a playlist's own rows, whose third
     * column is the album name.
     */
    private val rowWithoutTally = """
    {
      "musicResponsiveListItemRenderer": {
        "playlistItemData": { "videoId": "9bZkp7q19f0" },
        "flexColumns": [
          {
            "musicResponsiveListItemFlexColumnRenderer": {
              "text": { "runs": [{ "text": "Title" }] }
            }
          },
          {
            "musicResponsiveListItemFlexColumnRenderer": {
              "text": { "runs": [{ "text": "Artist" }] }
            }
          },
          {
            "musicResponsiveListItemFlexColumnRenderer": {
              "text": { "runs": [{ "text": "Album" }] }
            }
          }
        ]
      }
    }
    """

    /** A shelf card, whose subtitle ends in the tally. */
    private val twoRowCard = """
    {
      "musicTwoRowItemRenderer": {
        "title": { "runs": [{ "text": "MOST ROMANTIC ALBUM" }] },
        "subtitle": { "runs": [
          { "text": "Siddh Shah" },
          { "text": " • " },
          { "text": "4.9M views" }
        ] },
        "navigationEndpoint": {
          "browseEndpoint": { "browseId": "VLPLabcdefgh" }
        }
      }
    }
    """

    // ---- Song rows ---------------------------------------------------------

    @Test
    fun `the tally is read from the third column of a search row`() {
        val song = InnertubeParser.collectSongsDeep(json(searchRow)).single()
        assertEquals("4B plays", song.globalPlayCount)
    }

    @Test
    fun `the tally is read from an artist top songs row and the album name is not mistaken for it`() {
        val song = InnertubeParser.collectSongsDeep(json(artistTopSongsRow)).single()
        assertEquals("104M plays", song.globalPlayCount)
        // The album name sits after the tally and must not leak into it.
        assertEquals("ADÉLA", song.artist)
    }

    @Test
    fun `a row with no tally reports none`() {
        val song = InnertubeParser.collectSongsDeep(json(rowWithoutTally)).single()
        assertNull(song.globalPlayCount)
    }

    // ---- Shelf cards -------------------------------------------------------

    @Test
    fun `a shelf card keeps the tally from the end of its subtitle`() {
        val item = InnertubeParser.parseLibraryItems(json(twoRowCard)).singleOrNull()
        assertEquals("4.9M views", item?.playCount)
    }

    // ---- The tally itself --------------------------------------------------

    @Test
    fun `a magnitude suffix is kept with its figure`() {
        // The figure and the suffix travel together: cutting "M" off leaves
        // "57 plays" — a number fifty-seven times too small, printed as
        // though YouTube had said it.
        val song = InnertubeParser.collectSongsDeep(
            json(
                searchRow.replace(
                    """{ "text": "4B plays" }""",
                    """{ "text": "57M plays" }""",
                ),
            ),
        ).single()
        assertEquals("57M plays", song.globalPlayCount)
    }

    @Test
    fun `the tally survives on a song that is copied rather than rebuilt`() {
        val song = Song(
            videoId = "abc",
            title = "Title",
            artist = "Artist",
            thumbnailUrl = null,
            globalPlayCount = "9.9M plays",
        )
        assertEquals("9.9M plays", song.copy().globalPlayCount)
    }
}
