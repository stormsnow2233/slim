package com.storm.shijuan

import com.storm.shijuan.data.AniListComicCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AniListComicCatalogTest {
    @Test
    fun parsesMangaMetadataAndSkipsEntriesWithoutTitlesOrLinks() {
        val result = AniListComicCatalog.parseResponse(
            """
            {
              "data": {
                "Page": {
                  "media": [
                    {
                      "title": {"userPreferred": "Title", "english": "English"},
                      "coverImage": {"large": "https://img.example/cover.jpg"},
                      "description": "A story",
                      "siteUrl": "https://anilist.co/manga/1"
                    },
                    {
                      "title": {"english": "Missing link"},
                      "siteUrl": ""
                    },
                    {
                      "title": {},
                      "siteUrl": "https://anilist.co/manga/2"
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(1, result.size)
        assertEquals("Title", result.single().name)
        assertEquals("https://img.example/cover.jpg", result.single().cover)
        assertEquals("https://anilist.co/manga/1", result.single().url)
        assertEquals("anilist", result.single().sourceId)
    }

    @Test
    fun surfacesGraphQlErrors() {
        try {
            AniListComicCatalog.parseResponse("""{"errors":[{"message":"Rate limited"}]}""")
            throw AssertionError("Expected API errors to be surfaced")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("Rate limited"))
        }
    }
}
