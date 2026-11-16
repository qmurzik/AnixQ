package ru.qmods.anixq.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import ru.qmods.anixq.tv.data.*

/** The "фон" mode: openings of your titles one after another, with sound — like a radio with pictures. */
data object RadioRoute : Route { override val scope get() = "radio" }

private data class Track(val title: Title, val url: String, val from: Double, val to: Double, val headers: Map<String, String>)

/** The opening of a title: AnimeLib's own player (marks from AnimeLib or AniSkip), else AniLibria's stream. */
private suspend fun openingOf(t: Title): Track? = runCatching {
    val eps = Api.episodes(t.slug)
    val ep = eps.getOrNull(1) ?: eps.firstOrNull() ?: return null   // the 2nd episode almost always has the full opening
    val players = Api.players(ep.id)
    players.firstOrNull { it.isNative }?.let { p ->
        val keys = p.hrefs.keys.sortedBy { it.removeSuffix("p").toIntOrNull() ?: 9999 }
        val key = keys.firstOrNull { (it.removeSuffix("p").toIntOrNull() ?: 0) >= 720 } ?: keys.last()
        val href = p.hrefs.getValue(key)
        val server = Api.rankServers(href).firstOrNull() ?: return@let null
        val op = p.segments.firstOrNull { it.isOpening }
            ?: (t.shikiId)?.let { AniSkip.segments(it, ep.number, 1420.0).firstOrNull { s -> s.isOpening } }
            ?: return@let null
        Track(t, Api.videoUrl(server, href), op.from, op.to, mapOf("Referer" to "${Api.SITE}/", "Origin" to Api.SITE))
    } ?: players.firstOrNull { Libria.isLibria(it.team.name) }?.let { base ->
        val lp = Libria.player(t, ep.number, base) ?: return@let null
        val op = lp.segments.firstOrNull { it.isOpening } ?: return@let null
        Track(t, lp.hrefs.values.first(), op.from, op.to, emptyMap())
    }
}.getOrNull()

@Composable
fun OpeningsRadio(nav: Nav) {
    val context = LocalContext.current
    val playlist = remember {
        (Store.continueList.map { it.title } + Store.titles(Bookmark.FAVORITE) + Store.titles(Bookmark.WATCHING) + Store.titles(Bookmark.COMPLETED))
            .distinctBy { it.id }.shuffled().take(40)
    }
    val exo = remember {
        androidx.media3.exoplayer.ExoPlayer.Builder(context)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(Api.http)
                    .setDefaultRequestProperties(mapOf("Referer" to "${Api.SITE}/", "Origin" to Api.SITE))))
            .build()
    }
    var track by remember { mutableStateOf<Track?>(null) }
    var status by remember { mutableStateOf("Ищем опенинги ваших тайтлов…") }
    var skip by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var video by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0.0) }
    val focus = remember { FocusRequester() }
    DisposableEffect(exo) {
        val l = object : androidx.media3.common.Player.Listener { override fun onRenderedFirstFrame() { video = true } }
        exo.addListener(l)
        onDispose { exo.removeListener(l); exo.release() }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler { nav.pop() }
    // The playlist: each opening from its start to its end, then the next title.
    LaunchedEffect(playlist) {
        if (playlist.isEmpty()) { status = "Добавьте тайтлы в закладки — здесь заиграют их опенинги"; return@LaunchedEffect }
        var i = 0; var misses = 0
        while (misses < playlist.size) {
            val t = playlist[i % playlist.size]; i++
            val tr = openingOf(t)
            if (tr == null) { misses++; continue }
            misses = 0; video = false; track = tr; status = ""
            exo.setMediaItem(androidx.media3.common.MediaItem.fromUri(tr.url), (tr.from * 1000).toLong())
            exo.prepare(); exo.playWhenReady = !paused
            val started = skip
            var waited = 0
            while (!video && waited < 10_000 && skip == started) { delay(250); waited += 250 }
            if (!video) { exo.stop(); continue }
            while (skip == started && exo.currentPosition / 1000.0 < tr.to - 0.3) { pos = exo.currentPosition / 1000.0; delay(250) }
            exo.stop()
        }
        if (track == null) status = "У ваших тайтлов не нашлось опенингов с разметкой — попробуйте позже"
    }
    LaunchedEffect(paused) { exo.playWhenReady = !paused }
    val a by animateFloatAsState(if (video) 1f else 0f, tween(900), label = "radio")
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable().onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionRight, Key.MediaNext -> { skip++; true }
            Key.DirectionCenter, Key.Enter, Key.MediaPlayPause, Key.Spacebar -> { paused = !paused; true }
            else -> false
        }
    }) {
        Crossfade(track?.title, animationSpec = tween(900), label = "radioArt") { t -> if (t != null) Ambient(t.thumb, Modifier.fillMaxSize(), alpha = .5f) }
        AndroidView({ c -> android.view.TextureView(c).also { exo.setVideoTextureView(it) } }, Modifier.fillMaxSize().alpha(a))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.55f to Color.Transparent, 1f to Color.Black.copy(alpha = .8f))))
        Text("AnixQ · фон", color = Color.White.copy(alpha = .5f), fontSize = 13.sp, letterSpacing = 2.sp, modifier = Modifier.align(Alignment.TopStart).padding(40.dp))
        track?.let { tr ->
            Row(Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 48.dp, end = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(tr.title.poster, Modifier.width(74.dp).height(104.dp).clip(RoundedCornerShape(10.dp)))
                Column(Modifier.padding(start = 18.dp).weight(1f)) {
                    Text("ОПЕНИНГ", color = C.pink, fontSize = 11.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
                    Text(tr.title.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Box(Modifier.padding(top = 10.dp).width(420.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .25f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(((pos - tr.from) / (tr.to - tr.from).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)).background(Color.White))
                    }
                }
                Text(if (paused) "⏸  ОК — продолжить" else "ОК — пауза   ▶ — следующий", color = Color.White.copy(alpha = .6f), fontSize = 13.sp)
            }
        }
        if (status.isNotEmpty()) Text(status, color = Color.White.copy(alpha = .8f), fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
    }
}
