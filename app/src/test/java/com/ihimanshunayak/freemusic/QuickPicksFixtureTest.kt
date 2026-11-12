/*
 * Copyright (c) A|iens. All rights reserved.
 *
 * Name:    QuickPicksFixtureTest
 * Version: 1.0.0
 * Author:  A'nil Cyborg
 * Author URI: https://github.com/ihimanshunayak
 *
 * Description:
 *   Proves, against a payload taken off the wire, that a home feed's
 *   "Quick picks" card keeps the catalogue play tally.
 *
 *   This exists because the tally was parsed and then thrown away, and a
 *   tally that is thrown away leaves nothing on screen to fail a test
 *   against. The fixture is the real `musicCarouselShelfRenderer` from a
 *   live `FEmusic_home` response, trimmed to one row, so the assertion is
 *   about the shape YouTube actually sends rather than a shape guessed
 *   from a doc.
 *
 * Responsibilities:
 *   - Cover the Quick picks row's tally, which rides in the *second* flex
 *     column alongside the artist — "Ella Langley • 362M plays" — rather
 *     than in a column of its own as a search row has it.
 *   - Cover the card's own subtitle being the artist alone, so the tally
 *     has to come from the column and not from the subtitle.
 *
 * Dependencies:
 *   - InnertubeParser (data/innertube)
 *   - ShelfItem (data/model)
 *
 * Integration Notes:
 *   Run with `:app:testProdDebugUnitTest`.
 */
package com.ihimanshunayak.freemusic

import com.ihimanshunayak.freemusic.data.innertube.InnertubeParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPicksFixtureTest {

    /**
     * One row of a real "Quick picks" carousel: the title, the artist with
     * the tally beside it, and the album.
     */
    private val quickPicksRow = """
    {
      "musicCarouselShelfRenderer": {
        "header": {
          "musicCarouselShelfBasicHeaderRenderer": {
            "title": { "runs": [ { "text": "Quick picks" } ] }
          }
        },
        "contents": [
        {
      "musicResponsiveListItemRenderer": {
        "thumbnail": {
          "musicThumbnailRenderer": {
            "thumbnail": {
              "thumbnails": [
                { "url": "https://lh3.googleusercontent.com/card-544.jpg", "width": 544, "height": 544 }
              ]
            }
          }
        },
        "flexColumns": [
          {
            "musicResponsiveListItemFlexColumnRenderer": {
              "text": {
                "runs": [{
                  "text": "Choosin' Texas",
                  "navigationEndpoint": {
                    "watchEndpoint": { "videoId": "xukbqwRuN5w" }
                  }
                }]
              }
            }
          },
          {
            "musicResponsiveListItemFlexColumnRenderer": {
              "text": {
                "runs": [
                  {
                    "text": "Ella Langley",
                    "navigationEndpoint": {
                      "browseEndpoint": {
                        "browseId": "UCecnyZYofHiBVDJpx1XNYOQ",
                        "browseEndpointContextSupportedConfigs": {
                          "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_ARTIST" }
                        }
                      }
                    }
                  },
                  { "text": " • " },
                  { "text": "362M plays" }
                ]
              },
              "accessibility": {
                "accessibilityData": { "label": "Ella Langley • 362 million plays" }
              }
            }
          },
          {
            "musicResponsiveListItemFlexColumnRenderer": {
              "text": {
                "runs": [{
                  "text": "Dandelion",
                  "navigationEndpoint": {
                    "browseEndpoint": {
                      "browseId": "MPREb_QC0L98k1tGe",
                      "params": "ggMEgAL5BQ%3D%3D",
                      "browseEndpointContextSupportedConfigs": {
                        "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_ALBUM" }
                      }
                    }
                  }
                }]
              }
            }
          }
        ],
        "playlistItemData": { "videoId": "xukbqwRuN5w" }
      }
    }
        ]
      }
    }
    """

    @Test
    fun `a quick picks card keeps the tally that sits beside the artist`() {
        val root = Json.parseToJsonElement(quickPicksRow) as JsonObject
        val shelves = InnertubeParser.parseHomeContinuation(root)
        assertEquals(1, shelves.size)
        val card = shelves.first().items.single()
        assertEquals("Choosin' Texas", card.title)
        // The tally rides in the second column, next to the artist.
        assertEquals("362M plays", card.playCount)
    }

    @Test
    fun `the card's own subtitle is the artist alone`() {
        val root = Json.parseToJsonElement(quickPicksRow) as JsonObject
        val card = InnertubeParser.parseHomeContinuation(root).first().items.single()
        // Nothing but the artist — so a card that reads the tally from its
        // subtitle would find nothing here.
        assertTrue(card.subtitle.isNotBlank())
        assertEquals("Ella Langley", card.subtitle)
    }

    @Test
    fun `the tally is not mistaken for the album name`() {
        val root = Json.parseToJsonElement(quickPicksRow) as JsonObject
        val card = InnertubeParser.parseHomeContinuation(root).first().items.single()
        assertNotNull(card.playCount)
        assertTrue(!card.playCount!!.contains("Dandelion"))
    }
}
