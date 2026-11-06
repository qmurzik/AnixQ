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
import kotlinx.coroutines.delay
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Title

/** Where a title's preview clip comes from: episode 1 (or the one you're on), right after the opening. */
object PreviewClips {
    private val cache = HashMap<Int, Pair<String, Double>?>()

    suspend fun clip(t: Title): Pair<String, Double>? {
        if (cache.containsKey(t.id)) return cache[t.id]
        val r = runCatching {
            val eps = Api.episodes(t.slug)
            val mine = Store.lastEntry(t.id)?.let { e -> eps.firstOrNull { it.id == e.episodeId } }
            val ep = mine ?: eps.firstOrNull() ?: return@runCatching null
            val p = Api.players(ep.id).firstOrNull { it.isNative } ?: return@runCatching null
            // Small quality: it's a muted background, decode must stay cheap on TV boxes.
            val key = p.hrefs.keys.sortedBy { it.removeSuffix("p").toIntOrNull() ?: 9999 }.firstOrNull { (it.removeSuffix("p").toIntOrNull() ?: 0) >= 480 }
                ?: p.hrefs.keys.first()
            val href = p.hrefs.getValue(key)
            val server = Api.rankServers(href).firstOrNull() ?: return@runCatching null
            val opening = p.segments.firstOrNull { it.isOpening }
            val start = if (mine != null && Store.resume(ep.id) != null) (Store.resume(ep.id)!! - 20).coerceAtLeast(0.0)
                else opening?.let { it.to + 20 } ?: 300.0
            Api.videoUrl(server, href) to start
        }.getOrNull()
        cache[t.id] = r
        return r
    }
}

/**
 * Muted clip that fades in over the backdrop when the cursor rests on a title (Netflix-style).
 * Needs the ad-free AnimeLib player, i.e. a signed-in account.
 */
@Composable
fun HoverVideo(title: Title?, modifier: Modifier = Modifier, waitMs: Long = 1800, playMs: Long = 35_000) {
    if (title == null || Store.libToken == null || !Store.hoverPreview) return
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
    LaunchedEffect(title.id) {
        visible = false
        exo.stop()
        delay(waitMs)
        val (url, start) = PreviewClips.clip(title) ?: return@LaunchedEffect
        exo.setMediaItem(MediaItem.fromUri(url), (start * 1000).toLong())
        exo.prepare(); exo.playWhenReady = true
        delay(playMs)
        visible = false
        delay(800); exo.pause()
    }
    val a by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 900 else 500), label = "preview")
    if (a > 0f || visible) AndroidView(factory = { ctx -> TextureView(ctx).also { exo.setVideoTextureView(it) } }, modifier = modifier.alpha(a))
}
