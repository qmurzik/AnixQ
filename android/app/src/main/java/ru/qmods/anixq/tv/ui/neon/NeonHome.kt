package ru.qmods.anixq.tv.ui.neon

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.qmods.anixq.tv.data.*
import ru.qmods.anixq.tv.ui.*

@Composable
fun NeonHome(nav: Nav) {
    val version by Store.version.collectAsState()
    val rows by HomeData.rows.collectAsState()
    val error by HomeData.error.collectAsState()
    val counts by EpisodeCounts.after.collectAsState()
    val recommendations by ForYou.rows.collectAsState()
    val genres by GenreTiles.list.collectAsState()
    val history = remember(version) { Store.continueList }
    val watching = remember(version) { Store.titles(Bookmark.WATCHING).filter { t -> history.none { it.title.id == t.id } } }
    val planned = remember(version) { Store.titles(Bookmark.PLANNED) }
    var featured by remember { mutableStateOf<Title?>(null) }
    val candidates = remember(rows, history) { (rows.firstOrNull()?.second.orEmpty().take(6) + history.map { it.title }).distinctBy { it.id } }
    var slot by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { HomeData.load(); runCatching { GenreTiles.load() } }
    LaunchedEffect(history.map { it.title.id to it.episodeId }) { runCatching { EpisodeCounts.refresh(history) }; runCatching { ForYou.refresh(history) } }
    LaunchedEffect(rows) {
        if (Store.hoverPreview && !Store.weakDevice) { delay(2500); PreviewClips.prefetch((history.map { it.title } + rows.firstOrNull()?.second.orEmpty().take(6)).distinctBy { it.id }) }
    }
    val shown = featured ?: candidates.getOrNull(slot % candidates.size.coerceAtLeast(1))
    val focus: (Title) -> Unit = { featured = it }
    val colors = listOf(N.cyan, N.violet, N.amber, N.pink)
    LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize().testTag("neon-home"), contentPadding = PaddingValues(bottom = 50.dp)) {
        item("billboard") { Billboard(shown, nav, hasMore = candidates.size > 1) { featured = null; slot++ } }
        if (Updater.available != null) item("update") { Box(Modifier.padding(horizontal = 44.dp, vertical = 8.dp)) { UpdateBanner() } }
        if (history.isNotEmpty()) item("continue") {
            Column(Modifier.padding(bottom = 24.dp)) {
                NSign("続", "Продолжить поездку", N.cyan, Modifier.padding(start = 44.dp, end = 44.dp, bottom = 12.dp))
                Pivot { LazyRow(contentPadding = PaddingValues(horizontal = 44.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(history, key = { it.title.id }) { e ->
                        NTicket(e, counts[e.title.id], onClick = {
                            if (e.progress > .92 && counts[e.title.id] == 0) nav.open(e.title)
                            else nav.play(e.title, e.episodeId, e.teamId, next = e.progress > .92, resume = true)
                        }, onLongClick = { nav.open(e.title) }, modifier = Modifier.memo("neon:home", "continue/${e.title.id}"))
                    }
                } }
            }
        }
        if (watching.isNotEmpty()) item("watching") { NRow("観", "Сейчас смотрю", watching, "neon:home", nav, N.violet, focus) }
        rows.forEachIndexed { i, (name, titles) ->
            item("row/$name") { NRow(listOf("新", "熱", "星", "光", "夢")[i % 5], name, titles, "neon:home", nav, colors[i % colors.size], focus) }
            if (i == 1 && genres.isNotEmpty()) item("moods") {
                Column(Modifier.padding(bottom = 24.dp)) {
                    NSign("気", "Какое настроение?", N.amber, Modifier.padding(start = 44.dp, end = 44.dp, bottom = 12.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 44.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { NButton("Режим «фон»", Icons.Rounded.MusicNote, N.pink) { nav.push(RadioRoute) } }
                        item { NButton("Мне повезёт", Icons.Rounded.Casino, N.amber) {
                            scope.launch { runCatching { Api.catalog(CatalogQuery(sort = CatalogSort.RATING), (1..5).random()).first.randomOrNull() }.getOrNull()?.let(nav::open) }
                        } }
                        items(genres, key = { it.first }) { (id, name) -> NButton(name, color = N.amber) { CatalogState.openWith(nav, CatalogQuery(genre = id)) } }
                    }
                }
            }
        }
        recommendations.forEach { (name, titles) -> item("for-you/$name") { NRow("推", name, titles, "neon:home", nav, N.pink, focus) } }
        if (planned.isNotEmpty()) item("planned") { NRow("約", "На другой вечер", planned, "neon:home", nav, N.cyan, focus) }
        if (rows.isEmpty()) item("empty") {
            if (error != null) NMessage("Город не отвечает", error!!, "Повторить") { scope.launch { HomeData.load(force = true) } }
            else NMessage("Зажигаем вывески…", "Каталог появится через секунду.")
        }
        if (Store.libToken == null) item("login") {
            Row(Modifier.padding(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                NButton("Войти в AnimeLib", Icons.Rounded.Login) { nav.push(LoginRoute) }
                Text("Закладки и все источники видео", color = N.muted, fontSize = 12.sp)
            }
        }
    }
}

private data class Frame(val title: Title?, val detail: Detail?)

/**
 * The billboard over the street: the art in a neon frame with corner brackets, a slow scan line passing over it,
 * a vertical kanji tag and the title glowing below. The sky takes the colour of the poster.
 */
@Composable
internal fun Billboard(target: Title?, nav: Nav, hasMore: Boolean, onNext: () -> Unit) {
    val title by produceState(target, target?.slug) { delay(if (value == null) 0 else 240); value = target }
    val detail by produceState<Detail?>(null, title?.slug) {
        val slug = title?.slug ?: run { value = null; return@produceState }
        value = TitleCache.detail[slug] ?: Spotlight.cached(slug)
        if (value == null) { delay(240); value = Spotlight.get(slug) }
    }
    val ms = neonMillis(700)
    val scan = if (ms > 0) rememberInfiniteTransition(label = "scan").animateFloat(0f, 1f, infiniteRepeatable(tween(5200), RepeatMode.Restart), label = "line").value else -1f
    val color = if (title?.isOngoing == true) N.pink else N.cyan
    LaunchedEffect(title?.id) { NeonSky.tint = color }
    Box(Modifier.fillMaxWidth().height(440.dp)) {
        // Frame on the right: the art.
        Box(Modifier.align(Alignment.TopEnd).padding(top = 30.dp, end = 34.dp).width(600.dp).height(338.dp).neonOutline(color, .9f, 6.dp)
            .clip(RoundedCornerShape(6.dp)).background(N.panel).clipToBounds()) {
            Crossfade(Frame(title, detail?.takeIf { it.title.id == title?.id }), animationSpec = tween(ms), label = "billboard") { f ->
                val art = f.detail?.background ?: f.title?.poster
                if (art != null) Cover(art, Modifier.fillMaxSize())
            }
            title?.let { if (Store.hoverPreview) HoverVideo(it, Modifier.fillMaxSize(), waitMs = 2200) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.6f to Color.Transparent, 1f to N.night.copy(alpha = .7f))))
            if (scan >= 0f) Canvas(Modifier.fillMaxSize()) {
                val y = size.height * scan
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, color.copy(alpha = .16f), Color.Transparent), y - 40f, y + 40f), Offset(0f, y - 40f), androidx.compose.ui.geometry.Size(size.width, 80f))
            }
            // Corner brackets.
            Canvas(Modifier.fillMaxSize()) {
                val l = 28f; val w = 4f
                listOf(Offset(10f, 10f) to (1f to 1f), Offset(size.width - 10f, 10f) to (-1f to 1f), Offset(10f, size.height - 10f) to (1f to -1f),
                    Offset(size.width - 10f, size.height - 10f) to (-1f to -1f)).forEach { (o, d) ->
                    drawLine(Color.White, o, Offset(o.x + l * d.first, o.y), w); drawLine(Color.White, o, Offset(o.x, o.y + l * d.second), w)
                }
            }
        }
        // Vertical tag.
        Column(Modifier.padding(start = 44.dp, top = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            (if (title?.isOngoing == true) "放送中" else "今夜の一本").forEach { Glow(it.toString(), color, 22.sp, lineHeight = 26.sp) }
        }
        Row(Modifier.padding(start = 92.dp, top = 34.dp), verticalAlignment = Alignment.CenterVertically) {
            Glow(if (title?.isOngoing == true) "В ЭФИРЕ" else "СЕГОДНЯ ВЕЧЕРОМ", color, 11.sp, letterSpacing = 3.sp)
            Spacer(Modifier.width(18.dp)); NClock()
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 92.dp, bottom = 26.dp).width(560.dp)) {
            val t = title
            if (t != null) {
                Glow(t.title, N.ink, 44.sp, maxLines = 2, lineHeight = 50.sp, glow = .45f)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    t.rating?.takeIf { it > 0 }?.let { Glow("★ %.1f".format(it), N.amber, 14.sp) }
                    Text(listOfNotNull(t.year?.toString(), t.typeLabel, t.episodeInfo, t.age).joinToString("  ·  "), color = N.muted, fontSize = 12.sp, maxLines = 1)
                }
                Text(detail?.takeIf { it.title.id == t.id }?.summary?.replace("\n", " ")?.trim().orEmpty(), color = N.muted, fontSize = 13.sp, lineHeight = 19.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp).height(38.dp))
            } else Glow("Город\nпросыпается", N.pink, 44.sp, maxLines = 2, lineHeight = 50.sp)
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val last = t?.let { Store.lastEntry(it.id) }
                val resume = last != null && last.progress <= .92
                NButton(if (t == null) "В каталог" else if (resume) "Продолжить" else "Открыть", Icons.Rounded.PlayArrow, color, primary = true,
                    modifier = Modifier.memo("neon:home", "billboard", initial = true).testTag("neon-billboard-primary")) {
                    if (t == null) nav.tab = Tab.CATALOG else if (resume) nav.play(t, last!!.episodeId, last.teamId, resume = true) else nav.open(t)
                }
                if (hasMore && t != null) NButton("Следующая вывеска", Icons.Rounded.ArrowForward, color, onClick = onNext)
            }
        }
    }
}
