package ru.qmods.anixq.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * "Продолжить просмотр" row of the Android TV / Google TV home screen: the show you're on, one
 * press resumes it without opening AnixQ first.
 */
object WatchNext {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var app: Context? = null
    private val lastPush = HashMap<Int, Long>()

    fun init(context: Context) { app = context.applicationContext }

    fun update(title: Title, ep: Episode, teamId: Int, pos: Double, dur: Double, hasNext: Boolean, force: Boolean = false) {
        val ctx = app ?: return
        if (Build.VERSION.SDK_INT < 26 || dur <= 0) return
        val now = System.currentTimeMillis()
        if (!force && now - (lastPush[title.id] ?: 0) < 60_000) return
        lastPush[title.id] = now
        scope.launch {
            try {
                val prefs = ctx.getSharedPreferences("watchnext", Context.MODE_PRIVATE)
                val id = prefs.getLong("p${title.id}", -1)
                val done = pos / dur > 0.92
                if (done && !hasNext) { remove(ctx, title.id); return@launch }
                val launch = Intent(ctx, Class.forName("ru.qmods.anixq.tv.MainActivity")).setAction(Intent.ACTION_VIEW)
                    .putExtra("rc_play", JSONObject().put("title", title.toSync()).put("episodeId", ep.id).put("teamId", teamId)
                        .put("resume", true).toString())
                val b = WatchNextProgram.Builder()
                    .setType(TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE)
                    .setWatchNextType(if (done) TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                    .setLastEngagementTimeUtcMillis(now)
                    .setTitle(title.title)
                    .setEpisodeTitle(if (done) "Следующая серия · после ${ep.number}" else "Серия ${ep.number}")
                    .setEpisodeNumber(ep.numberValue.toInt())
                    .setDurationMillis((dur * 1000).toInt())
                    .setLastPlaybackPositionMillis(if (done) 0 else (pos * 1000).toInt())
                    .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
                    .setInternalProviderId(title.id.toString())
                    .setIntentUri(Uri.parse(launch.toUri(Intent.URI_INTENT_SCHEME)))
                title.poster?.let { b.setPosterArtUri(Uri.parse(it)) }
                val values = b.build().toContentValues()
                val updated = if (id > 0) ctx.contentResolver.update(TvContractCompat.buildWatchNextProgramUri(id), values, null, null) else 0
                if (updated < 1) {
                    ctx.contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)?.let { uri ->
                        prefs.edit().putLong("p${title.id}", android.content.ContentUris.parseId(uri)).apply()
                    }
                }
            } catch (e: Exception) { Log.d("AnixQ", "watch next: ${e.message}") }   // boxes without the TV provider
        }
    }

    private var lastRefresh = 0L

    /**
     * Fill the row from the whole library (also what was watched on the phone / iPhone): unfinished
     * episodes to continue, and the next episode of finished ones — including newly released ones.
     */
    fun refreshFromLibrary() {
        if (app == null || Build.VERSION.SDK_INT < 26) return
        val now = System.currentTimeMillis()
        if (now - lastRefresh < 3 * 3600_000L) return
        lastRefresh = now
        scope.launch {
            for (e in Store.continueList.take(8)) {
                val eps = runCatching { Api.episodes(e.title.slug) }.getOrNull() ?: continue
                val i = eps.indexOfFirst { it.id == e.episodeId }.takeIf { it >= 0 } ?: eps.indexOfFirst { it.number == e.ep }.takeIf { it >= 0 } ?: continue
                update(e.title, eps[i], e.teamId, e.pos, e.dur, hasNext = i + 1 < eps.size, force = true)
            }
        }
    }

    private fun remove(ctx: Context, titleId: Int) {
        val prefs = ctx.getSharedPreferences("watchnext", Context.MODE_PRIVATE)
        val id = prefs.getLong("p$titleId", -1)
        if (id > 0) runCatching { ctx.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null) }
        prefs.edit().remove("p$titleId").apply()
    }
}
