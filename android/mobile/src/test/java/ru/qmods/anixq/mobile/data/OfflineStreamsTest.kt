package ru.qmods.anixq.mobile.data

import android.net.Uri
import androidx.media3.datasource.DataSpec
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class OfflineStreamsTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun hlsPlaylistAndSegmentRemainReadableOffline() = downloadAndRead("720.mp4:hls:manifest.m3u8", """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:4
        #EXTINF:4,
        segment.bin
        #EXT-X-ENDLIST
    """.trimIndent())

    @Test fun dashManifestAndSegmentRemainReadableOffline() = downloadAndRead("episode.mpd", """
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT4S" minBufferTime="PT1S">
          <Period duration="PT4S">
            <AdaptationSet mimeType="video/mp4" codecs="avc1.42c01e">
              <Representation id="1" bandwidth="800000" width="640" height="360">
                <SegmentList timescale="1" duration="4">
                  <SegmentURL media="segment.bin"/>
                </SegmentList>
              </Representation>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent())

    private fun downloadAndRead(playlist: String, manifest: String) {
        val segment = "fixture segment"
        val referer = "https://player.example/"
        val streams = OfflineStreams(RuntimeEnvironment.getApplication())
        val cache = files.newFolder()
        val urls = MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Referer") != referer) return MockResponse().setResponseCode(403)
                    return when (request.path) {
                        "/$playlist" -> MockResponse().setBody(manifest)
                        "/segment.bin" -> MockResponse().setBody(segment)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            val manifestUrl = server.url("/$playlist").toString()
            val segmentUrl = server.url("/segment.bin").toString()
            assertTrue(OfflineStreams.isAdaptive(manifestUrl))
            streams.downloader(cache, manifestUrl, mapOf("Referer" to referer)).download(null)
            assertEquals(2, server.requestCount)
            manifestUrl to segmentUrl
        }
        streams.close(cache)
        try {
            val factory = streams.readOnly(cache)
            for ((url, expected) in listOf(urls.first to manifest, urls.second to segment)) {
                val source = factory.createDataSource()
                try {
                    val actual = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    source.open(DataSpec(Uri.parse(url)))
                    while (true) {
                        val count = source.read(buffer, 0, buffer.size)
                        if (count < 0) break
                        actual.write(buffer, 0, count)
                    }
                    assertEquals(expected, actual.toString(Charsets.UTF_8.name()))
                } finally {
                    source.close()
                }
            }
        } finally {
            streams.delete(cache)
        }
    }
}
