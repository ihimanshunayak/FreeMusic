package com.ihimanshunayak.freemusic

import com.ihimanshunayak.freemusic.data.innertube.InnertubeParser
import com.ihimanshunayak.freemusic.data.model.HomeChipFeed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The page-wide picture YouTube paints behind Home, and the filter-specific
 * version of it that arrives on a chip's own response.
 *
 * These fixtures are cut down from real responses captured off the live API —
 * the shape matters as much as the values, since the whole parser is a walk
 * down a fixed path and a path that is one key wrong silently yields null
 * rather than failing loudly.
 */
class HomeBackgroundTest {

    private fun root(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `reads the picture off the response root`() {
        val response = root(
            """
            {
              "background": {
                "musicThumbnailRenderer": {
                  "thumbnail": {
                    "thumbnails": [
                      { "url": "https://example.test/bg=w1500-h844-l90-rj",
                        "width": 1500, "height": 844 }
                    ]
                  },
                  "thumbnailCrop": "MUSIC_THUMBNAIL_CROP_UNSPECIFIED",
                  "trackingParams": "CAEQhL8CIhMI"
                }
              },
              "contents": { "singleColumnBrowseResultsRenderer": { "tabs": [] } }
            }
            """.trimIndent(),
        )

        assertEquals(
            "https://example.test/bg=w1500-h844-l90-rj",
            InnertubeParser.parseBackground(response),
        )
    }

    @Test
    fun `a page with no picture yields null rather than an error`() {
        // The ordinary case: most chips and most pages carry no background at
        // all, and the caller reads that as "leave the page its own colour".
        val response = root("""{ "contents": { "singleColumnBrowseResultsRenderer": { "tabs": [] } } }""")

        assertNull(InnertubeParser.parseBackground(response))
    }

    @Test
    fun `ignores the client-side fullbleed name`() {
        // YouTube Music's own page renders this slot as
        // `musicFullbleedThumbnailRenderer`. That name is the client's markup,
        // not the API's — a parser that read it would come back empty on every
        // real response, so a fixture that offers only that name must not be
        // mistaken for a picture.
        val response = root(
            """
            {
              "background": {
                "musicFullbleedThumbnailRenderer": {
                  "thumbnail": {
                    "thumbnails": [{ "url": "https://example.test/client-only", "width": 1500, "height": 844 }]
                  }
                }
              }
            }
            """.trimIndent(),
        )

        assertNull(InnertubeParser.parseBackground(response))
    }

    @Test
    fun `picks the largest of several sizes`() {
        // The same picture at several sizes, smallest first, as the server
        // sends it. Reaching for the last one rather than the first is what
        // makes the header sharp on a large display.
        val response = root(
            """
            {
              "background": {
                "musicThumbnailRenderer": {
                  "thumbnail": {
                    "thumbnails": [
                      { "url": "https://example.test/small", "width": 360, "height": 202 },
                      { "url": "https://example.test/medium", "width": 720, "height": 405 },
                      { "url": "https://example.test/large", "width": 1500, "height": 844 }
                    ]
                  }
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals("https://example.test/large", InnertubeParser.parseBackground(response))
    }

    @Test
    fun `an empty thumbnail list yields null`() {
        val response = root(
            """
            {
              "background": {
                "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [] } }
              }
            }
            """.trimIndent(),
        )

        assertNull(InnertubeParser.parseBackground(response))
    }

    @Test
    fun `a chip feed carries a picture and shelves independently`() {
        // The two travel together off one response but neither implies the
        // other: a filtered page can have shelves and no picture, and the
        // picture can arrive while the shelves are still being walked.
        val withPicture = HomeChipFeed(shelves = emptyList(), backgroundUrl = "https://example.test/bg")
        val withoutPicture = HomeChipFeed(shelves = emptyList())

        assertEquals("https://example.test/bg", withPicture.backgroundUrl)
        assertNull(withoutPicture.backgroundUrl)
        assertEquals(emptyList<Any>(), withoutPicture.shelves)
    }
}
