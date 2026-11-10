package ru.qmods.anixq.tv.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reports: where exactly the app failed, written to disk at the moment of the crash and
 * put into the user's private GitHub Gist (next to the sync file) on the next start — easy to forward.
 */
object Crashes {
    private lateinit var file: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun install(context: Context, app: String, version: String) {
        file = File(context.filesDir, "crashes.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file.appendText("=== $stamp · $app $version · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${thread.name}\n$sw\n")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Text of the reports not sent yet (null when there are none). */
    fun pending(): String? = if (::file.isInitialized && file.exists() && file.length() > 0) file.readText() else null

    /** Sends saved reports to the gist (when sync is on) and clears them. */
    fun upload() {
        val text = pending() ?: return
        if (!Sync.isOn) return
        scope.launch {
            if (Sync.appendFile("anixq-crashes-${Store.deviceId}.txt", text.takeLast(60_000))) file.delete()
        }
    }
}
