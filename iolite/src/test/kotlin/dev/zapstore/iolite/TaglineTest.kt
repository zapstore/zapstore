package dev.zapstore.iolite

import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.assertEquals

class TaglineTest {
    @Test
    fun firstProseParagraphSkipsHeadingsImagesAndUrls() {
        val markdown = """
            # My App

            ![logo](https://example.com/logo.png)

            https://example.com

            A **fast** notes app with [sync](https://example.com/sync)
            and snake_case tags.

            Second paragraph.
        """.trimIndent()
        assertEquals("A fast notes app with sync and snake_case tags.", plainParagraph(markdown))
    }

    @Test
    fun emptyWhenThereIsNoProse() {
        assertEquals("", plainParagraph("# Title\n\n![x](y)"))
    }

    @Test
    fun cardLineIsTheGeneratedAboutThenTheListingThenTheSummary() {
        val listing = JSONObject().put("description", "# Listing\n\nThe developer's words.\n\nMore.")
        assertEquals("What it does.", app(about = "What it does.\n\nMore.", metadata = listing, summary = "Short").tagline)
        assertEquals("The developer's words.", app(about = "", metadata = listing, summary = "Short").tagline)
        assertEquals("Short", app(about = "", metadata = JSONObject(), summary = "Short").tagline)
        assertEquals("", app(about = "", metadata = JSONObject(), summary = "").tagline)
    }

    private fun app(about: String, metadata: JSONObject, summary: String) = AppRecord(
        id = ByteArray(32),
        catalogId = 1,
        appId = "com.example.app",
        certificateHash = "ab".repeat(32),
        createdAt = 0,
        proofPubkey = null,
        eventPubkey = "d".repeat(64),
        catalogManifestPubkey = null,
        name = "Example",
        summary = summary,
        repository = null,
        version = "1",
        versionCode = 1,
        channel = "main",
        metadata = metadata,
        about = about,
    )
}
