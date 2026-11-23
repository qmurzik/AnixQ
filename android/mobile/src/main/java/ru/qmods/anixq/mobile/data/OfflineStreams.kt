package ru.qmods.anixq.mobile.data

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.Util
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import ru.qmods.anixq.tv.data.Api
import java.io.File

/** One cache per downloaded episode so deleting a dub cannot remove another episode's segments. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OfflineStreams(context: Context) {
    private val database = StandaloneDatabaseProvider(context.applicationContext)
    private val caches = mutableMapOf<File, SimpleCache>()

    @Synchronized
    private fun cache(directory: File) = caches.getOrPut(directory) {
        SimpleCache(directory, NoOpCacheEvictor(), database)
    }

    fun readOnly(directory: File): DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache(directory))
        .setCacheWriteDataSinkFactory(null)

    fun downloader(directory: File, url: String, headers: Map<String, String>, mimeType: String? = null) =
        DefaultDownloaderFactory(
            CacheDataSource.Factory()
                .setCache(cache(directory))
                .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(Api.http).setDefaultRequestProperties(headers))
        ).createDownloader(DownloadRequest.Builder(directory.name, Uri.parse(url)).setMimeType(mimeType).build())

    @Synchronized
    fun close(directory: File) {
        caches.remove(directory)?.release()
    }

    @Synchronized
    fun delete(directory: File) {
        close(directory)
        SimpleCache.delete(directory, database)
    }

    companion object {
        fun isAdaptive(url: String, mimeType: String? = null): Boolean =
            Util.inferContentTypeForUriAndMimeType(Uri.parse(url), mimeType) in setOf(C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_DASH)
    }
}
