package ru.qmods.anixq.tv.data.sources

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in network smoke tests. No mocking, and no claim of Android decoding/playback. */
class LiveSourcesTest {
    private fun check(name: String) = runBlocking {
        val url = System.getenv("ANIXQ_LIVE_$name").orEmpty()
        assumeTrue("Set ANIXQ_LIVE_$name to a public, permitted iframe URL", url.isNotBlank())
        // Resolve the explicitly supplied public iframe independently of Yummy's account.
        // Aksor and AnimeGO may publish links without requiring a Yummy session.
        val extractor = AnimeSources.extractors.single { it.name.equals(name, true) }
        val stream = extractor.resolve(url)
        assertTrue(stream.qualities.isNotEmpty())
        val best = stream.qualities.entries.maxBy { qualityHeight(it.key) }
        val request = Request.Builder().url(best.value).apply {
            stream.headers.forEach { (key, value) -> header(key, value) }
            // Media3 loads manifests normally; a media byte-range probe is wrong for
            // a CDN that rejects Range on an MPD/HLS document.
            val path = best.value.toHttpUrl().encodedPath.lowercase()
            if (!path.endsWith(".mpd") && !path.endsWith(".m3u8")) header("Range", "bytes=0-1023")
        }.build()
        SourceHttp().client.newCall(request).execute().use { response ->
            println("$name: qualities=${stream.qualities.keys}, HTTP=${response.code}, type=${response.header("Content-Type")}")
            assertTrue("Stream must serve media, not an iframe", response.isSuccessful)
            val bytes = ByteArray(1024)
            val read = response.body!!.byteStream().read(bytes)
            assertTrue(read > 0)
            val prefix = String(bytes, 0, read).trimStart()
            assertFalse(prefix.startsWith("<!DOCTYPE", true) || prefix.startsWith("<html", true))
            val path = best.value.toHttpUrl().encodedPath.lowercase()
            if (path.endsWith(".mpd")) assertTrue("Expected a DASH manifest", prefix.contains("<MPD"))
            if (path.endsWith(".m3u8")) assertTrue("Expected an HLS playlist", prefix.startsWith("#EXTM3U"))
        }
    }
    @Test fun liveAksor() = check("AKSOR")
    @Test fun liveCvh() = check("CVH")
    @Test fun liveKodik() = check("KODIK")
}
