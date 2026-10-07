package dev.zapstore.app.install

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class ApkDownloaderTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private lateinit var dest: File

    @Before
    fun start() {
        server.start()
        dest = File.createTempFile("apk", ".apk")
        dest.delete()
    }

    @After
    fun stop() {
        dest.delete()
        server.shutdown()
    }

    @Test
    fun followsARedirectAndKeepsAMatchingBody() = runBlocking {
        val bytes = "apk-bytes".toByteArray()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/file").toString()))
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        ApkDownloader.download(
            calls = client,
            urls = listOf(server.url("/start").toString()),
            dest = dest,
            expectedHash = sha256(bytes),
            freeBytes = { Long.MAX_VALUE },
        )
        assertTrue(dest.readBytes().contentEquals(bytes))
        assertEquals("/start", server.takeRequest().path)
        assertEquals("/file", server.takeRequest().path)
    }

    @Test
    fun hashMismatchFallsThroughToTheNextUrl() = runBlocking {
        val good = "good-apk".toByteArray()
        server.enqueue(MockResponse().setBody("bad-apk"))
        server.enqueue(MockResponse().setBody(Buffer().write(good)))
        ApkDownloader.download(
            calls = client,
            urls = listOf(server.url("/first").toString(), server.url("/second").toString()),
            dest = dest,
            expectedHash = sha256(good),
            freeBytes = { Long.MAX_VALUE },
        )
        assertTrue(dest.readBytes().contentEquals(good))
    }

    @Test
    fun resumesWithRangeWhenTheServerAllowsIt() = runBlocking {
        val body = "abcdefghij".toByteArray()
        dest.writeBytes(body.copyOfRange(0, 4))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setBody(Buffer().write(body.copyOfRange(4, body.size))),
        )
        ApkDownloader.download(
            calls = client,
            urls = listOf(server.url("/apk").toString()),
            dest = dest,
            expectedHash = sha256(body),
            freeBytes = { Long.MAX_VALUE },
        )
        assertEquals("bytes=4-", server.takeRequest().getHeader("Range"))
        assertTrue(dest.readBytes().contentEquals(body))
    }

    @Test
    fun refusesWhenFreeSpaceIsShort() {
        server.enqueue(MockResponse().setBody("hi"))
        val error = runCatching {
            runBlocking {
                ApkDownloader.download(
                    calls = client,
                    urls = listOf(server.url("/apk").toString()),
                    dest = dest,
                    expectedHash = sha256("hi".toByteArray()),
                    freeBytes = { 0 },
                )
            }
        }.exceptionOrNull()
        assertTrue(error is InstallException && error.kind == InstallException.Kind.Space)
        assertTrue(!dest.exists())
    }

    @Test
    fun cdnUrlUsesTheLowercaseHashAndSkipsADuplicate() {
        assertEquals(listOf("https://cdn.zapstore.dev/ab"), ApkDownloader.urls(null, "AB"))
        assertEquals(
            listOf("https://example.com/a.apk", "https://cdn.zapstore.dev/ab"),
            ApkDownloader.urls("https://example.com/a.apk", "ab"),
        )
        assertEquals(
            listOf("https://cdn.zapstore.dev/ab"),
            ApkDownloader.urls("https://cdn.zapstore.dev/AB", "ab"),
        )
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
