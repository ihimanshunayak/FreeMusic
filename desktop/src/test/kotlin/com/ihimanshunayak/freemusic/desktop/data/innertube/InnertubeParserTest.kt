// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - InnertubeParser tests.
//
// The parser is the only part of the app that has to survive YouTube changing
// its response shape, so it is tested against literal renderer trees rather than
// a live call: a fixture keeps the test honest about what the code handles and
// makes a failure mean "the parser broke", never "the network broke".
//
// The shapes below are trimmed copies of real `youtubei/v1/search` and
// `youtubei/v1/browse` payloads - the nesting and key names are verbatim, only
// the number of rows is reduced.

package com.ihimanshunayak.freemusic.desktop.data.innertube

import com.ihimanshunayak.freemusic.desktop.model.ResultKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InnertubeParserTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    // ---- search ------------------------------------------------------------

    private val searchPayload = """
    {
      "contents": {
        "tabbedSearchResultsRenderer": {
          "tabs": [{
            "tabRenderer": {
              "content": {
                "sectionListRenderer": {
                  "contents": [{
                    "musicShelfRenderer": {
                      "title": { "runs": [{ "text": "Songs" }] },
                      "contents": [
                        {
                          "musicResponsiveListItemRenderer": {
                            "thumbnail": {
                              "musicThumbnailRenderer": {
                                "thumbnail": {
                                  "thumbnails": [{
                                    "url": "https://lh3.googleusercontent.com/song-120.jpg",
                                    "width": 120, "height": 120
                                  }]
                                }
                              }
                            },
                            "flexColumns": [
                              {
                                "musicResponsiveListItemFlexColumnRenderer": {
                                  "text": { "runs": [{ "text": "Sunflower" }] }
                                }
                              },
                              {
                                "musicResponsiveListItemFlexColumnRenderer": {
                                  "text": {
                                    "runs": [
                                      { "text": "Post Malone" },
                                      { "text": " - " },
                                      { "text": "Spider-Man" },
                                      { "text": " - " },
                                      { "text": "2:38" }
                                    ]
                                  }
                                }
                              }
                            ],
                            "playlistItemData": { "videoId": "ApXoWvfEYVU" }
                          }
                        },
                        {
                          "musicResponsiveListItemRenderer": {
                            "flexColumns": [
                              {
                                "musicResponsiveListItemFlexColumnRenderer": {
                                  "text": { "runs": [{ "text": "Sicko Mode" }] }
                                }
                              },
                              {
                                "musicResponsiveListItemFlexColumnRenderer": {
                                  "text": { "runs": [{ "text": "Travis Scott" }] }
                                }
                              }
                            ],
                            "playlistItemData": { "videoId": "6ONRf7h3Mdk" }
                          }
                        }
                      ]
                    }
                  }]
                }
              }
            }
          }]
        }
      }
    }
    """.trimIndent()

    @Test
    fun `search rows are parsed with title subtitle and video id`() {
        val results = InnertubeParser.parseSearchResults(json(searchPayload))

        assertEquals(2, results.size)
        val first = results[0]
        assertEquals("Sunflower", first.title)
        assertEquals("ApXoWvfEYVU", first.videoId)
        assertTrue(first.subtitle.contains("Post Malone"), "subtitle was '${first.subtitle}'")
        assertEquals(158, first.durationSeconds, "2:38 should be 158 seconds")
        assertEquals("https://lh3.googleusercontent.com/song-120.jpg", first.thumbnailUrl)
    }

    @Test
    fun `a row without a thumbnail still parses`() {
        val results = InnertubeParser.parseSearchResults(json(searchPayload))

        assertEquals(2, results.size)
        assertEquals("Sicko Mode", results[1].title)
        assertNull(results[1].thumbnailUrl)
    }

    @Test
    fun `an empty response yields no rows rather than throwing`() {
        assertEquals(emptyList(), InnertubeParser.parseSearchResults(json("""{}""")))
        assertEquals(emptyList(), InnertubeParser.parseSearchResults(json("""{"contents":{}}""")))
    }

    @Test
    fun `two-row carousel items are read as search results`() {
        val payload = """
        {
          "musicTwoRowItemRenderer": {
            "title": { "runs": [{ "text": "SOS" }] },
            "subtitle": { "runs": [{ "text": "Album - SZA" }] },
            "thumbnailRenderer": {
              "musicThumbnailRenderer": {
                "thumbnail": {
                  "thumbnails": [{ "url": "https://lh3.googleusercontent.com/sos.jpg" }]
                }
              }
            },
            "navigationEndpoint": {
              "browseEndpoint": { "browseId": "MPREb_abc123" }
            }
          }
        }
        """.trimIndent()

        val results = InnertubeParser.parseSearchResults(json(payload))

        assertEquals(1, results.size)
        assertEquals("SOS", results[0].title)
        assertEquals("MPREb_abc123", results[0].browseId)
        assertEquals(ResultKind.ALBUM, results[0].kind, "subtitle 'Album - SZA' should classify as ALBUM")
    }

    @Test
    fun `a song row is classified from its own subtitle label`() {
        val payload = """
        {
          "musicResponsiveListItemRenderer": {
            "flexColumns": [
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "In the End" }] } } },
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [
                  { "text": "Song" }, { "text": " - " }, { "text": "Linkin Park" },
                  { "text": " - " }, { "text": "3.3B plays" }
              ] } } }
            ],
            "playlistItemData": { "videoId": "BLZWkjBXfN8" },
            "menu": { "musicMultiSelectMenuItemRenderer": {
                "title": { "runs": [{ "text": "Go to album" }] },
                "navigationEndpoint": { "browseEndpoint": { "browseId": "MPREb_abc" } }
            } }
          }
        }
        """.trimIndent()

        val results = InnertubeParser.parseSearchResults(json(payload))

        // The row opens a song even though the only browse id anywhere inside it
        // belongs to the "Go to album" entry in its overflow menu. Reading that
        // id as the row's own type is the bug this asserts against.
        assertEquals(ResultKind.SONG, results.single().kind, "the row's own subtitle says Song")
    }

    @Test
    fun `an artist row is classified from its own subtitle label`() {
        val payload = """
        {
          "musicResponsiveListItemRenderer": {
            "flexColumns": [
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Linkin Park" }] } } },
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [
                  { "text": "Artist" }, { "text": " - " }, { "text": "66.8M monthly audience" }
              ] } } }
            ],
            "navigationEndpoint": { "browseEndpoint": { "browseId": "UCZU9T1ceaOgwfLRq7OKFU4Q" } }
          }
        }
        """.trimIndent()

        assertEquals(ResultKind.ARTIST, InnertubeParser.parseSearchResults(json(payload)).single().kind)
    }

    @Test
    fun `musicVideoType separates an uploaded video from a licensed song`() {
        fun row(videoType: String) = """
        {
          "musicResponsiveListItemRenderer": {
            "flexColumns": [
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Some Track" }] } } },
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Channel" }] } } }
            ],
            "playlistItemData": { "videoId": "abcdefghijk" },
            "overlay": { "musicItemThumbnailOverlayRenderer": {
                "content": { "musicPlayButtonRenderer": {
                    "playNavigationEndpoint": { "watchEndpoint": { "videoId": "abcdefghijk" } }
                } }
            } },
            "musicVideoType": "$videoType"
          }
        }
        """.trimIndent()

        assertEquals(ResultKind.SONG, InnertubeParser.parseSearchResults(json(row("MUSIC_VIDEO_TYPE_ATV"))).single().kind)
        assertEquals(ResultKind.VIDEO, InnertubeParser.parseSearchResults(json(row("MUSIC_VIDEO_TYPE_OMV"))).single().kind)
    }

    @Test
    fun `a label token must be the whole first word`() {
        // "Songwriter" starts with a label but is an artist name, so a prefix match
        // would misclassify it. The endpoint is an album browse id, which is the
        // fallback signal, so ALBUM can only come out if the subtitle contributed
        // nothing - a SONG here would mean "Songwriter" was read as "Song".
        val payload = """
        {
          "musicResponsiveListItemRenderer": {
            "flexColumns": [
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Untitled" }] } } },
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Songwriter Collective" }] } } }
            ],
            "navigationEndpoint": { "browseEndpoint": { "browseId": "MPREb_abcdefghijk" } }
          }
        }
        """.trimIndent()

        assertEquals(ResultKind.ALBUM, InnertubeParser.parseSearchResults(json(payload)).single().kind)
    }

    // ---- continuation ------------------------------------------------------

    @Test
    fun `continuation token is found when present`() {
        val payload = """
        {
          "continuationContents": {
            "musicShelfContinuation": {
              "continuations": [{
                "nextContinuationData": { "continuation": "CBQSCA" }
              }]
            }
          }
        }
        """.trimIndent()

        assertEquals("CBQSCA", InnertubeParser.findContinuation(json(payload)))
    }

    @Test
    fun `a continuationCommand token is also accepted`() {
        val payload = """
        {
          "musicShelfRenderer": {
            "contents": [],
            "continuations": [{
              "button": {
                "buttonRenderer": {
                  "command": {
                    "continuationCommand": { "token": "TOKEN-42" }
                  }
                }
              }
            }]
          }
        }
        """.trimIndent()

        assertEquals("TOKEN-42", InnertubeParser.findContinuation(json(payload)))
    }

    @Test
    fun `no continuation returns null rather than an empty string`() {
        assertNull(InnertubeParser.findContinuation(json("""{"contents":{}}""")))
    }

    // ---- suggestions -------------------------------------------------------

    @Test
    fun `search suggestions are read from their own renderer`() {
        val payload = """
        {
          "contents": [{
            "searchSuggestionsSectionRenderer": {
              "contents": [
                { "searchSuggestionRenderer": { "suggestion": { "runs": [{ "text": "sunflower post malone" }] } } },
                { "searchSuggestionRenderer": { "suggestion": { "runs": [{ "text": "sunflower lyrics" }] } } }
              ]
            }
          }]
        }
        """.trimIndent()

        val suggestions = InnertubeParser.parseSuggestions(json(payload))

        assertEquals(listOf("sunflower post malone", "sunflower lyrics"), suggestions)
    }

    @Test
    fun `suggestions fall back to responsive rows when the section renderer is absent`() {
        val suggestions = InnertubeParser.parseSuggestions(json(searchPayload))

        assertEquals(listOf("Sunflower", "Sicko Mode"), suggestions)
    }

    // ---- home shelves ------------------------------------------------------

    @Test
    fun `home shelves are parsed in order with their own rows`() {
        val payload = """
        {
          "contents": {
            "singleColumnBrowseResultsRenderer": {
              "tabs": [{
                "tabRenderer": {
                  "content": {
                    "sectionListRenderer": {
                      "contents": [
                        {
                          "musicCarouselShelfRenderer": {
                            "header": {
                              "musicCarouselShelfBasicHeaderRenderer": {
                                "title": { "runs": [{ "text": "Quick picks" }] }
                              }
                            },
                            "contents": [{
                              "musicTwoRowItemRenderer": {
                                "title": { "runs": [{ "text": "Blinding Lights" }] },
                                "subtitle": { "runs": [{ "text": "The Weeknd" }] },
                                "navigationEndpoint": { "watchEndpoint": { "videoId": "4NRXx6U8ABQ" } }
                              }
                            }]
                          }
                        },
                        {
                          "musicShelfRenderer": {
                            "title": { "runs": [{ "text": "Trending" }] },
                            "contents": [{
                              "musicResponsiveListItemRenderer": {
                                "flexColumns": [
                                  {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": { "runs": [{ "text": "Kesariya" }] }
                                    }
                                  },
                                  {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": { "runs": [{ "text": "Arijit Singh" }] }
                                    }
                                  }
                                ],
                                "playlistItemData": { "videoId": "BddP6PYo2gs" }
                              }
                            }]
                          }
                        }
                      ]
                    }
                  }
                }
              }]
            }
          }
        }
        """.trimIndent()

        val shelves = InnertubeParser.parseShelves(json(payload))

        assertEquals(listOf("Quick picks", "Trending"), shelves.map { it.title })
        assertEquals(1, shelves[0].items.size)
        assertEquals("4NRXx6U8ABQ", shelves[0].items[0].videoId)
        assertEquals(1, shelves[1].items.size)
        assertEquals("Kesariya", shelves[1].items[0].title)
    }

    @Test
    fun `a shelf with no parseable title is skipped rather than shown unnamed`() {
        val payload = """
        {
          "musicCarouselShelfRenderer": {
            "contents": [{
              "musicTwoRowItemRenderer": {
                "title": { "runs": [{ "text": "Something" }] },
                "navigationEndpoint": { "watchEndpoint": { "videoId": "abc" } }
              }
            }]
          }
        }
        """.trimIndent()

        assertEquals(emptyList(), InnertubeParser.parseShelves(json(payload)))
    }

    @Test
    fun `an empty feed produces no shelves`() {
        assertEquals(emptyList(), InnertubeParser.parseShelves(json("""{"contents":{}}""")))
    }

    // ---- duration and classification helpers -------------------------------

    @Test
    fun `duration labels are read in both minute and hour forms`() {
        assertEquals(158, InnertubeParser.secondsFromLabel("2:38"))
        assertEquals(216, InnertubeParser.secondsFromLabel("3:36"))
        assertEquals(3869, InnertubeParser.secondsFromLabel("1:04:29"))
        assertEquals(59, InnertubeParser.secondsFromLabel("0:59"))
    }

    @Test
    fun `a non duration label yields null rather than a wrong number`() {
        assertNull(InnertubeParser.secondsFromLabel("Post Malone"))
        assertNull(InnertubeParser.secondsFromLabel(""))
        assertNull(InnertubeParser.secondsFromLabel("2024"))
    }

    @Test
    fun `duration-like and year-like text are told apart`() {
        assertTrue(InnertubeParser.isDurationLike("3:07"))
        assertTrue(InnertubeParser.isDurationLike("1:02:44"))
        // A bare year looks numeric but is a release year, not a length.
        assertTrue(InnertubeParser.isYearLike("2024"))
        assertTrue(!InnertubeParser.isDurationLike("2024"))
        assertTrue(!InnertubeParser.isYearLike("3:07"))
    }

    // ---- moods and genres --------------------------------------------------

    /**
     * The shape `FEmusic_moods_and_genres` actually answers with.
     *
     * Two nested tabs wrap a `sectionListRenderer` whose sections each hold a
     * `gridRenderer` of `musicNavigationButtonRenderer` buttons. Only the
     * endpoint's own `browseId` and `params` are usable downstream; a category id
     * assembled from the label is rejected by the service.
     */
    private val moodsPayload = """
    {
      "contents": {
        "singleColumnBrowseResultsRenderer": {
          "tabs": [{
            "tabRenderer": {
              "content": {
                "sectionListRenderer": {
                  "contents": [
                    {
                      "gridRenderer": {
                        "header": {
                          "gridHeaderRenderer": {
                            "title": { "runs": [{ "text": "Moods" }] }
                          }
                        },
                        "items": [
                          {
                            "musicNavigationButtonRenderer": {
                              "buttonText": { "runs": [{ "text": "Chill" }] },
                              "clickCommand": {
                                "browseEndpoint": {
                                  "browseId": "FEmusic_moods_and_genres_category",
                                  "params": "ggMPOg1DQUFFU0Fod0xjU3dQ"
                                }
                              }
                            }
                          },
                          {
                            "musicNavigationButtonRenderer": {
                              "buttonText": { "runs": [{ "text": "Energy" }] },
                              "clickCommand": {
                                "browseEndpoint": {
                                  "browseId": "FEmusic_moods_and_genres_category",
                                  "params": "ggMPOg1DQUFFU0Fod0xjU3dQAA"
                                }
                              }
                            }
                          }
                        ]
                      }
                    },
                    {
                      "gridRenderer": {
                        "header": {
                          "gridHeaderRenderer": {
                            "title": { "runs": [{ "text": "Genres" }] }
                          }
                        },
                        "items": [
                          {
                            "musicNavigationButtonRenderer": {
                              "buttonText": { "runs": [{ "text": "Rock" }] },
                              "clickCommand": {
                                "browseEndpoint": {
                                  "browseId": "FEmusic_moods_and_genres_category",
                                  "params": "ggMPOg1DQUFFUm9jaw"
                                }
                              }
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }
            }
          }]
        }
      }
    }
    """

    @Test
    fun `moods and genres are read out of the browse response`() {
        val sections = InnertubeParser.parseMoodAndGenres(json(moodsPayload))

        assertEquals(2, sections.size)
        assertEquals("Moods", sections[0].title)
        assertEquals("Genres", sections[1].title)
        assertEquals(listOf("Chill", "Energy"), sections[0].items.map { it.title })
        assertEquals(listOf("Rock"), sections[1].items.map { it.title })
    }

    @Test
    fun `a mood keeps the browse id and params the service sent`() {
        val sections = InnertubeParser.parseMoodAndGenres(json(moodsPayload))
        val chill = sections[0].items.first()

        // The id is the same for every tile; the params are what select the mood,
        // so dropping them would open the generic category page for all of them.
        assertEquals("FEmusic_moods_and_genres_category", chill.browseId)
        assertEquals("ggMPOg1DQUFFU0Fod0xjU3dQ", chill.params)
    }

    @Test
    fun `the older navigationEndpoint spelling is read too`() {
        val older = """
        {
          "contents": { "sectionListRenderer": { "contents": [
            { "gridRenderer": {
              "header": { "gridHeaderRenderer": { "title": { "simpleText": "Moods" } } },
              "items": [{ "musicNavigationButtonRenderer": {
                "buttonText": { "simpleText": "Focus" },
                "navigationEndpoint": { "browseEndpoint": {
                  "browseId": "FEmusic_moods_and_genres_category",
                  "params": "abc"
                } }
              } }]
            } }
          ] } }
        }
        """

        val sections = InnertubeParser.parseMoodAndGenres(json(older))
        assertEquals(1, sections.size)
        assertEquals("Focus", sections[0].items.single().title)
        assertEquals("abc", sections[0].items.single().params)
    }

    @Test
    fun `a button with no endpoint is dropped rather than guessed at`() {
        val noEndpoint = """
        {
          "contents": { "sectionListRenderer": { "contents": [
            { "gridRenderer": {
              "header": { "gridHeaderRenderer": { "title": { "simpleText": "Moods" } } },
              "items": [
                { "musicNavigationButtonRenderer": {
                  "buttonText": { "simpleText": "Orphan" }
                } },
                { "musicNavigationButtonRenderer": {
                  "buttonText": { "simpleText": "Chill" },
                  "clickCommand": { "browseEndpoint": { "browseId": "FEmusic_x" } }
                } }
              ]
            } }
          ] } }
        }
        """

        val sections = InnertubeParser.parseMoodAndGenres(json(noEndpoint))
        // A tile that cannot be opened is worse than a missing tile: clicking it
        // would send a request the service answers with a 400.
        assertEquals(listOf("Chill"), sections.single().items.map { it.title })
    }

    @Test
    fun `a grid with no usable buttons is dropped entirely`() {
        val empty = """
        { "contents": { "sectionListRenderer": { "contents": [
          { "gridRenderer": {
            "header": { "gridHeaderRenderer": { "title": { "simpleText": "Moods" } } },
            "items": []
          } }
        ] } } }
        """

        assertTrue(InnertubeParser.parseMoodAndGenres(json(empty)).isEmpty())
    }

    @Test
    fun `a payload with no grid at all yields an empty grid rather than throwing`() {
        // The Home payload has no gridRenderer. Parsing it as moods must not crash.
        assertTrue(InnertubeParser.parseMoodAndGenres(json(searchPayload)).isEmpty())
    }
}
