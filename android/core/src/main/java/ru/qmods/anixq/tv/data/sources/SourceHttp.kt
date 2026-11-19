package ru.qmods.anixq.tv.data.sources

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Separate client: never inherits AnimeLib authorization, cookies, Origin or Referer. */
class SourceHttp(val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
    .callTimeout(18, TimeUnit.SECONDS).build()) {
    class HttpError(val status: Int) : IOException(when(status) {
        401 -> "Требуется вход в аккаунт источника"
        403 -> "Источник ограничил доступ"
        420 -> "Сервис запросил CAPTCHA. Войдите на сайте YummyAnime и используйте свой токен в настройках."
        else -> "Источник ответил HTTP $status"
    })
    data class Reply(val text: String, val headers: Headers, val status: Int)
    suspend fun request(url: String, headers: Map<String, String> = emptyMap(), body: RequestBody? = null, acceptRedirect: Boolean = false): Reply =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url).header("User-Agent", UA).apply {
                headers.forEach { (key, value) -> header(key, value) }
                body?.let { post(it) }
            }.build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (acceptRedirect && it.code in 300..399) {
                                if (continuation.isActive) continuation.resume(Reply("", it.headers, it.code))
                                return
                            }
                            if (!it.isSuccessful) throw HttpError(it.code)
                            val text = it.body?.string() ?: throw IOException("Пустой ответ источника")
                            if (continuation.isActive) continuation.resume(Reply(text, it.headers, it.code))
                        } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                    }
                }
            })
        }
    companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
