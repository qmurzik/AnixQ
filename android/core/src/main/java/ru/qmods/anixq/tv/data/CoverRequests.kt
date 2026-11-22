package ru.qmods.anixq.tv.data

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The CDN rejects image requests without the AnimeLib site referer. */
internal object CoverRequests : Interceptor {
    private val mirrors = listOf("cover.mangalib.me", "cover.cdnlibs.org", "cover.imglib.info")
    private val libDomains = listOf("imglib.info", "cdnlibs.org", "lib.social", "anilib.me", "mangalib.me")

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val url = if (original.url.scheme == "http") original.url.newBuilder().scheme("https").build() else original.url
        val request = original.newBuilder().url(url).apply {
            if (libDomains.any { url.host == it || url.host.endsWith(".$it") }) {
                header("Referer", "${Api.SITE}/")
            }
        }.build()

        if (request.method != "GET" || url.host !in mirrors || url.port != 443 || !url.encodedPath.startsWith("/uploads/anime/")) {
            return chain.proceed(request)
        }

        val hosts = listOf(url.host) + mirrors.filterNot { it == url.host }
        val bounded = chain.withConnectTimeout(4, TimeUnit.SECONDS).withReadTimeout(6, TimeUnit.SECONDS)
        for ((index, host) in hosts.withIndex()) {
            val attempt = request.newBuilder().url(url.newBuilder().host(host).build()).build()
            val response = try {
                bounded.proceed(attempt)
            } catch (e: IOException) {
                if (index == hosts.lastIndex || chain.call().isCanceled()) throw e
                continue
            }
            val html = response.isSuccessful && response.header("Content-Type").orEmpty().contains("text/html", ignoreCase = true)
            val retry = html || response.code in setOf(403, 404, 429) || response.code >= 500
            if (!retry || index == hosts.lastIndex || chain.call().isCanceled()) {
                return if (html) response.newBuilder().code(502).message("Image CDN returned HTML").build() else response
            }
            response.close()
        }
        error("Missing image CDN")
    }
}
