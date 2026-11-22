package ru.qmods.anixq.tv.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancels the socket operation when the caller leaves a screen or exceeds its time budget. */
internal suspend fun <T> Call.awaitResult(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use(read) }
            result.fold(continuation::resume, continuation::resumeWithException)
        }
    })
}
