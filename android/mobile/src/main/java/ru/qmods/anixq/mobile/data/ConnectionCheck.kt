package ru.qmods.anixq.mobile.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.awaitResult
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

internal enum class ProbeContent { HOST, JSON, JPEG, HEALTH }

internal data class ConnectionProbe(
    val name: String,
    val description: String,
    val url: String,
    val content: ProbeContent = ProbeContent.HOST,
    val animeLib: Boolean = false,
) {
    val host = url.toHttpUrl().host

    fun request(): Request {
        val target = url.toHttpUrl()
        return if (animeLib && content == ProbeContent.JSON) {
            Api.publicRequest(target).build()
        } else {
            Request.Builder().url(target).apply {
                if (animeLib) header("Referer", "${Api.SITE}/")
            }.build()
        }
    }
}

internal data class ConnectionResult(val success: Boolean, val message: String, val elapsedMs: Long) {
    val display: String get() = "$message · $elapsedMs мс"
}

internal object ConnectionCheck {
    private const val EDGE = "https://anixq-edge.qmurzik7.workers.dev"
    private const val CONSTANTS = "/api/constants?fields%5B%5D=videoServers"
    private const val COVER = "/uploads/anime/11/cover/bbf77bf3-26e9-40ad-b21b-57a6b90a4b0d_thumb.jpg"

    val probes = listOf(
        ConnectionProbe("AnixQ кэш", "доступ к сервису", "$EDGE/health", ProbeContent.HEALTH),
        ConnectionProbe("Каталог через AnixQ", "публичный поиск AnimeLib", "$EDGE/api/anime?site_id%5B%5D=5&page=1&q=Naruto", ProbeContent.JSON, animeLib = true),
        ConnectionProbe("Список видеосерверов через AnixQ", "зеркала AnimeLib", "$EDGE$CONSTANTS", ProbeContent.JSON, animeLib = true),
    ) + listOf("api.cdnlibs.org", "api.lib.social", "api.imglib.info", "hapi.hentaicdn.org").map { host ->
        ConnectionProbe("AnimeLib API", host, "https://$host$CONSTANTS", ProbeContent.JSON, animeLib = true)
    } + listOf("cover.mangalib.me", "cover.cdnlibs.org", "cover.imglib.info").map { host ->
        ConnectionProbe("Обложки AnimeLib", host, "https://$host$COVER", ProbeContent.JPEG, animeLib = true)
    } + listOf("video1.cdnlibs.org", "video2.cdnlibs.org", "video1.imglib.info").map { host ->
        ConnectionProbe("Видео AnimeLib", host, "https://$host/", animeLib = true)
    } + listOf(
        ConnectionProbe("AniLibria", "состояние API", "https://anilibria.top/api/v1/app/status", ProbeContent.JSON),
        ConnectionProbe("Видео AniLibria", "cache.libria.fun", "https://cache.libria.fun/"),
        ConnectionProbe("Kodik", "kodikplayer.com", "https://kodikplayer.com/"),
        ConnectionProbe("Kodik", "kodik.info", "https://kodik.info/"),
        ConnectionProbe("Shikimori", "расписание", "https://shikimori.io/api/calendar", ProbeContent.JSON),
        ConnectionProbe("GitHub", "доступ к API синхронизации", "https://api.github.com/", ProbeContent.JSON),
    )

    fun client(): OkHttpClient = Api.http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()

    suspend fun probe(client: OkHttpClient, probe: ConnectionProbe): ConnectionResult {
        val start = System.nanoTime()
        fun result(success: Boolean, message: String) = ConnectionResult(success, message, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
        return try {
            client.newCall(probe.request()).awaitResult { response ->
                val status = response.code
                when {
                    status == 403 -> result(false, "HTTP 403 · доступ запрещён")
                    status == 401 -> result(false, "HTTP 401 · требуется вход")
                    status == 429 -> result(false, "HTTP 429 · лимит запросов")
                    !response.isSuccessful -> result(false, "HTTP $status")
                    probe.content == ProbeContent.HOST -> result(true, "HTTP $status · ответ хоста")
                    probe.content == ProbeContent.JPEG -> {
                        val header = response.peekBody(3).bytes()
                        val jpeg = header.contentEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()))
                        result(jpeg, if (jpeg) "HTTP $status · JPEG" else "HTTP $status · ожидалось JPEG")
                    }
                    probe.content == ProbeContent.HEALTH -> {
                        val healthy = response.peekBody(128).string().trim() == "AnixQ edge ok"
                        result(healthy, if (healthy) "HTTP $status · сервис доступен" else "HTTP $status · неожиданный ответ")
                    }
                    else -> {
                        val body = response.peekBody(512 * 1024).string()
                        val payload = runCatching { JSONTokener(body).nextValue() }.getOrNull()
                        val json = if (probe.animeLib) {
                            val data = (payload as? JSONObject)?.opt("data")
                            data is JSONObject || data is JSONArray
                        } else payload is JSONObject || payload is JSONArray
                        val cache = when (response.header("x-anixq-cache")) {
                            "hit" -> "кэш"
                            "stale", "fallback" -> "сохранённый ответ"
                            "miss" -> "ответ AnimeLib"
                            else -> "JSON"
                        }
                        result(json, if (json) "HTTP $status · $cache" else "HTTP $status · ожидался JSON")
                    }
                }
            }
        } catch (_: UnknownHostException) {
            result(false, "DNS · адрес не найден")
        } catch (_: InterruptedIOException) {
            result(false, "Таймаут")
        } catch (_: SSLException) {
            result(false, "Ошибка TLS")
        } catch (_: ConnectException) {
            result(false, "Ошибка подключения")
        } catch (_: IOException) {
            result(false, "Ошибка сети")
        }
    }
}
