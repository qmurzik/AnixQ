package ru.qmods.anixq.tv.data

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache

/** Covers: AnimeLib serves them only with its site as referer (same as the iOS app does). */
object Images {
    fun loader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .okHttpClient {
            Api.http.newBuilder().addInterceptor { chain ->
                var req = chain.request()
                if (req.url.scheme == "http") req = req.newBuilder().url(req.url.newBuilder().scheme("https").build()).build()
                val host = req.url.host
                if (host.contains("imglib") || host.contains("cdnlibs") || host.contains("lib.social") || host.contains("anilib")) {
                    req = req.newBuilder().header("Referer", "${Api.SITE}/").build()
                }
                chain.proceed(req)
            }.build()
        }
        .memoryCache { MemoryCache.Builder(context).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("covers")).maxSizeBytes(150L * 1024 * 1024).build() }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()
}
