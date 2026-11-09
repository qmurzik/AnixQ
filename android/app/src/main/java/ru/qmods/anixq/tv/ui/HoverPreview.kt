package ru.qmods.anixq.tv.ui

import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Title

/** Where a title's preview clip comes from: episode 1 (or the one you're on), right after the opening. */
object PreviewClips {
    private val cache = java.util.concurrent.ConcurrentHashMap<Int, Pair<String, Double>>()
    private val none = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())
    /** The screensaver is on: previews must give the video decoder back (TV boxes have one or two). */
    var saverActive by mutableStateOf(false)

    fun cached(t: Title): Pair<String, Double>? = cache[t.id]

    /** Look up clips ahead for the titles on screen, so a preview starts the moment the cursor rests. */
    suspend fun prefetch(titles: List<Title>) {
        for (t in titles.take(24)) { if (!cache.containsKey(t.id) && t.id !in none) clip(t) }
    }

    suspend fun clip(t: Title): Pair<String, Double>? {
        cache[t.id]?.let { return it }
        if (t.id in none) return null
        var answered = false
        val r = runCatching {
            val eps = Api.episodes(t.slug)
            answered = true
            val mine = Store.lastEntry(t.id)?.let { e -> eps.firstOrNull { it.id == e.episodeId } }
            val ep = mine ?: eps.firstOrNull() ?: return@runCatching null
            val p = Api.players(ep.id).firstOrNull { it.isNative } ?: return@runCatching null
            // Small quality: it's a muted background, decode must stay cheap on TV boxes (weak ones: the lowest).
            val sorted = p.hrefs.keys.sortedBy { it.removeSuffix("p").toIntOrNull() ?: 9999 }
            val key = (if (Store.weakDevice) sorted.firstOrNull() else sorted.firstOrNull { (it.removeSuffix("p").toIntOrNull() ?: 0) >= 480 })
                ?: p.hrefs.keys.first()
            val href = p.hrefs.getValue(key)
            val server = Api.rankServers(href).firstOrNull() ?: return@runCatching null
            val opening = p.segments.firstOrNull { it.isOpening }
            val start = if (mine != null && Store.resume(ep.id) != null) (Store.resume(ep.id)!! - 20).coerceAtLeast(0.0)
                else opening?.let { it.to + 20 } ?: 300.0
            Api.videoUrl(server, href) to start
        }.getOrNull()
        // "No ad-free video" is remembered; a network hiccup is not (it'll be tried again).
        if (r != null) cache[t.id] = r else if (answered) none.add(t.id)
        return r
    }
}

/**
 * Muted clip that fades in over the backdrop when the cursor rests on a title (Netflix-style).
 * Uses AnimeLib's own player (no Kodik ads); titles without it simply show no clip.
 */
@Composable
fun HoverVideo(title: Title?, modifier: Modifier = Modifier, waitMs: Long = 1200, playMs: Long = 35_000) {
    if (title == null || !Store.hoverPreview) return
    val context = LocalContext.current
    val exo = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(
                OkHttpDataSource.Factory(Api.http).setDefaultRequestProperties(mapOf("Referer" to "${Api.SITE}/", "Origin" to Api.SITE))))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 12_000, 800, 1_500).build())
            .build().apply { volume = 0f; repeatMode = Player.REPEAT_MODE_OFF }
    }
    var visible by remember { mutableStateOf(false) }
    DisposableEffect(exo) {
        val l = object : Player.Listener { override fun onRenderedFirstFrame() { visible = true } }
        exo.addListener(l)
        onDispose { exo.removeListener(l); exo.release() }
    }
    LaunchedEffect(title.id, PreviewClips.saverActive) {
        visible = false
        exo.stop()
        if (PreviewClips.saverActive) return@LaunchedEffect
        // Find the clip while waiting (not after): ready the moment the cursor has rested long enough.
        val found = kotlinx.coroutines.coroutineScope {
            val c = async { PreviewClips.cached(title) ?: PreviewClips.clip(title) }
            delay(waitMs)
            c.await()
        } ?: return@LaunchedEffect
        exo.setMediaItem(MediaItem.fromUri(found.first), (found.second * 1000).toLong())
        exo.prepare(); exo.playWhenReady = true
        delay(playMs)
        visible = false
        // stop(), not pause(): frees the hardware decoder for the player / screensaver.
        delay(800); exo.stop()
    }
    val a by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 900 else 500), label = "preview")
    // The surface must exist before the first frame can be drawn (it fades in once that happens).
    AndroidView(factory = { ctx -> TextureView(ctx).also { exo.setVideoTextureView(it) } }, modifier = modifier.alpha(a))
}
