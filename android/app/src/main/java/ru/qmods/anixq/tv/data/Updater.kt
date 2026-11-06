package ru.qmods.anixq.tv.data

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import ru.qmods.anixq.tv.BuildConfig
import java.io.File

/** New version as published by the "Android TV" workflow (release "tv" → tv-version.json). */
data class Release(val code: Int, val name: String, val notes: String, val apk: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release?) : UpdateState
}

/** Updates the app from the TV itself: check → download → system installer. */
object Updater {
    private const val MANIFEST = "https://github.com/qmurzik/AnixQ/releases/download/tv/tv-version.json"
    const val INSTALL_PAGE = "qmurzik.github.io/AnixQ/tv"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var lastCheck = 0L

    val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val available: Release? get() = when (val s = state.value) {
        is UpdateState.Available -> s.release; is UpdateState.Downloading -> s.release
        is UpdateState.Installing -> s.release; is UpdateState.Failed -> s.release; else -> null
    }

    /** Quiet check at most every few hours (on app start). */
    fun checkSometimes() { if (System.currentTimeMillis() - lastCheck > 3 * 3600_000L) check(quiet = true) }

    fun check(quiet: Boolean = false) {
        if (job?.isActive == true) return
        job = scope.launch {
            lastCheck = System.currentTimeMillis()
            if (!quiet) state.value = UpdateState.Checking
            try {
                val json = withContext(Dispatchers.IO) {
                    Api.http.newCall(Request.Builder().url(MANIFEST).header("Cache-Control", "no-cache").build()).execute().use {
                        if (!it.isSuccessful) throw Exception("сервер ответил ${it.code}")
                        JSONObject(it.body!!.string())
                    }
                }
                val r = Release(json.getInt("code"), json.optString("name"), json.optString("notes"), json.getString("apk"))
                state.value = if (r.code > BuildConfig.VERSION_CODE) UpdateState.Available(r) else UpdateState.UpToDate
            } catch (e: Exception) {
                state.value = if (quiet) UpdateState.Idle else UpdateState.Failed("Не удалось проверить обновления: ${e.message}", null)
            }
        }
    }

    fun install(context: Context) {
        val r = available ?: return
        if (job?.isActive == true && state.value is UpdateState.Downloading) return
        val app = context.applicationContext
        // Android 8+: the user allows this app to install updates once.
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                state.value = UpdateState.Failed("Разрешите AnixQ устанавливать приложения и нажмите «Установить» ещё раз", r)
                return
            } catch (_: ActivityNotFoundException) { /* no such screen on this box: the installer will ask itself */ }
        }
        job = scope.launch {
            try {
                val file = File(app.cacheDir, "update.apk")
                withContext(Dispatchers.IO) { download(r, file) }
                val info = app.packageManager.getPackageArchiveInfo(file.path, 0)
                if (info == null || info.packageName != app.packageName) throw Exception("файл повреждён, попробуйте ещё раз")
                state.value = UpdateState.Installing(r)
                try { withContext(Dispatchers.IO) { commit(app, file) } }
                catch (e: Exception) { if (!viewInstall(app, file)) throw e }
            } catch (e: Exception) {
                state.value = UpdateState.Failed("Не получилось: ${e.message}", r)
            }
        }
    }

    private fun download(r: Release, file: File) {
        Api.http.newCall(Request.Builder().url(r.apk).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("загрузка: ${resp.code}")
            val body = resp.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: 15_000_000L
            var done = 0L
            var shown = -1
            file.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        val pct = (done * 100 / total).toInt()
                        if (pct != shown) { shown = pct; state.value = UpdateState.Downloading(r, (done.toFloat() / total).coerceAtMost(1f)) }
                    }
                }
            }
        }
    }

    private fun commit(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("anixq-tv.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, id, Intent(context, InstallReceiver::class.java), flags)
            s.commit(pi.intentSender)
        }
    }

    /** Plan B for boxes whose installer rejects sessions: open the APK in the regular package installer. */
    private fun viewInstall(context: Context, file: File): Boolean = runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    internal fun result(context: Context, intent: Intent) {
        val r = available
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> state.value = UpdateState.UpToDate
            PackageInstaller.STATUS_FAILURE_ABORTED -> state.value = UpdateState.Available(r ?: return)
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                state.value = UpdateState.Failed("Эта версия подписана другим ключом. Удалите AnixQ и установите заново: $INSTALL_PAGE", r)
            PackageInstaller.STATUS_FAILURE_STORAGE -> state.value = UpdateState.Failed("Не хватает места на телевизоре", r)
            else -> {
                val f = File(context.cacheDir, "update.apk")
                if (!(f.exists() && viewInstall(context, f)))
                    state.value = UpdateState.Failed("Установка не удалась (${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: status})", r)
            }
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updater.result(context, intent)
}
