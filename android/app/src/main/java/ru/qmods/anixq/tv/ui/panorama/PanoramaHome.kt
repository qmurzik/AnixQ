package ru.qmods.anixq.tv.ui.panorama

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
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
fun PanoramaHome(nav: Nav) {
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
    var slot by remember { mutableIntStateOf(0) }
    val candidates = remember(rows, history) { (rows.firstOrNull()?.second.orEmpty().take(6) + history.map { it.title }).distinctBy { it.id } }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { HomeData.load(); runCatching { GenreTiles.load() } }
    LaunchedEffect(history.map { it.title.id to it.episodeId }) {
        runCatching { EpisodeCounts.refresh(history) }
        runCatching { ForYou.refresh(history) }
    }
    LaunchedEffect(rows) {
        if (Store.hoverPreview && !Store.weakDevice) {
            delay(2500)
            PreviewClips.prefetch((history.map { it.title } + rows.firstOrNull()?.second.orEmpty().take(6)).distinctBy { it.id })
        }
    }
    val list = rememberLazyListState()
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("panorama-home"), contentPadding = PaddingValues(bottom = 50.dp)) {
        item("hero") {
            PanoramaHero(featured ?: candidates.getOrNull(slot % candidates.size.coerceAtLeast(1)), nav,
                scope = "panorama:home", hasMore = candidates.size > 1, onNext = { featured = null; slot++ })
        }
        if (Updater.available != null) item("update") { Box(Modifier.padding(horizontal = 38.dp, vertical = 8.dp)) { UpdateBanner() } }
        if (history.isNotEmpty()) item("continue") {
            Column(Modifier.padding(bottom = 24.dp)) {
                Row(Modifier.padding(start = 38.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Вернуться в историю", color = P.ink, fontSize = 21.sp, fontWeight = FontWeight.Medium)
                    Text("ПРОДОЛЖИТЬ ПРОСМОТР", color = P.muted, letterSpacing = 1.sp, fontSize = 9.sp)
                }
                Pivot { LazyRow(contentPadding = PaddingValues(horizontal = 38.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(history, key = { it.title.id }) { e ->
                        PContinue(e, counts[e.title.id], onClick = {
                            if (e.progress > .92 && counts[e.title.id] == 0) nav.open(e.title)
                            else nav.play(e.title, e.episodeId, e.teamId, next = e.progress > .92, resume = true)
                        }, onLongClick = { nav.open(e.title) }, modifier = Modifier.memo("panorama:home", "continue/${e.title.id}"))
                    }
                } }
            }
        }
        if (watching.isNotEmpty()) item("watching") { PTitleRow("Сейчас в вашем списке", watching, "panorama:home", nav, onFocus = { featured = it }) }
        rows.forEachIndexed { i, (name, titles) ->
            item("catalog/$name") { if (i < 2) CinemaRow(name, titles, "panorama:home", nav, onFocus = { featured = it }) else PTitleRow(name, titles, "panorama:home", nav, ranked = i == 2, onFocus = { featured = it }) }
            if (i == 1 && genres.isNotEmpty()) item("genres") {
                Column(Modifier.padding(bottom = 26.dp)) {
                    Text("Какое сегодня настроение?", color = P.ink, fontSize = 21.sp, modifier = Modifier.padding(start = 38.dp, bottom = 14.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 38.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { PButton("Мне повезёт", Icons.Rounded.Shuffle) {
                            scope.launch { runCatching { Api.catalog(CatalogQuery(sort = CatalogSort.RATING), (1..5).random()).first.randomOrNull() }.getOrNull()?.let(nav::open) }
                        } }
                        items(genres, key = { it.first }) { (id, name) -> PButton(name) { CatalogState.openWith(nav, CatalogQuery(genre = id)) } }
                    }
                }
            }
        }
        recommendations.forEach { (name, titles) -> item("for-you/$name") { PTitleRow(name, titles, "panorama:home", nav, onFocus = { featured = it }) } }
        if (planned.isNotEmpty()) item("planned") { PTitleRow("На другой вечер", planned, "panorama:home", nav, onFocus = { featured = it }) }
        if (rows.isEmpty()) item("empty") {
            if (error != null) PMessage("Не удалось обновить каталог", error!!, "Повторить") { scope.launch { HomeData.load(force = true) } }
            else PMessage("Подбираем аниме", "Каталог появится здесь после загрузки.")
        }
        if (Store.libToken == null) item("login") {
            Row(Modifier.padding(38.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                PButton("Войти в AnimeLib", Icons.Rounded.Login) { nav.push(LoginRoute) }
                Text("Ваши закладки и доступные источники воспроизведения", color = P.muted, fontSize = 12.sp)
            }
        }
    }
}

private data class HeroFrame(val title: Title?, val detail: Detail?)

@Composable
internal fun PanoramaHero(target: Title?, nav: Nav, scope: String, hasMore: Boolean = false, onNext: () -> Unit = {}) {
    val displayed by produceState(target, target?.slug) { delay(if (value == null) 0 else 260); value = target }
    val title = displayed
    val detail by produceState<Detail?>(null, title?.slug) {
        val slug = title?.slug ?: run { value = null; return@produceState }
        value = TitleCache.detail[slug] ?: Spotlight.cached(slug)
        if (value == null) { delay(240); value = Spotlight.get(slug) }
    }
    val currentDetail = detail?.takeIf { it.title.id == title?.id }
    val duration = motionMillis(750)
    val depth = remember(title?.slug) { androidx.compose.animation.core.Animatable(if (duration == 0) 1f else 1.055f) }
    LaunchedEffect(title?.slug) { depth.animateTo(1f, tween(duration * 2)) }
    Box(Modifier.fillMaxWidth().height(442.dp)) {
        Crossfade(HeroFrame(title, currentDetail), animationSpec = tween(duration), label = "cinema-scene") { frame ->
            val art = frame.title
            if (art != null) Box(Modifier.fillMaxSize()) {
                if (frame.detail?.background != null) {
                    Cover(frame.detail.background, Modifier.align(Alignment.TopEnd).fillMaxWidth(.91f).fillMaxHeight()
                        .graphicsLayer { scaleX = depth.value; scaleY = depth.value })
                } else {
                    // Preserve portrait composition when the service has no wide art.
                    Cover(art.poster, Modifier.align(Alignment.CenterEnd).padding(end = 100.dp).height(400.dp).aspectRatio(.69f)
                        .graphicsLayer { rotationZ = -3f; shadowElevation = 24.dp.toPx() }.clip(RoundedCornerShape(12.dp)))
                    Ambient(art.thumb, Modifier.fillMaxSize(), alpha = .17f)
                }
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to P.bg, .24f to P.bg.copy(alpha=.97f), .55f to P.bg.copy(alpha=.55f), 1f to P.bg.copy(alpha=.08f))))
            }
        }
        if (title != null && Store.hoverPreview) HoverVideo(title,Modifier.align(Alignment.TopEnd).fillMaxWidth(.82f).fillMaxHeight(),waitMs=2200)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to P.bg, .33f to P.bg.copy(alpha=.95f), .73f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to P.bg.copy(alpha=.18f), .62f to Color.Transparent, 1f to P.bg)))
        Row(Modifier.fillMaxWidth().padding(horizontal=42.dp,vertical=28.dp),verticalAlignment=Alignment.CenterVertically) {
            Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(22.dp).height(2.dp).background(P.accent))
                Eyebrow("ВАША СЛЕДУЮЩАЯ ИСТОРИЯ")
            }
            PClock()
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start=42.dp,bottom=38.dp).width(660.dp)) {
            if(title!=null) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(if(title.isOngoing) "НОВЫЕ СЕРИИ" else "В ЦЕНТРЕ ВНИМАНИЯ",color=P.accent,letterSpacing=2.sp,fontSize=9.sp,fontWeight=FontWeight.Bold)
                    Box(Modifier.width(34.dp).height(1.dp).background(P.accent.copy(alpha=.45f)))
                    Text(title.typeLabel.orEmpty(),color=P.muted,fontSize=10.sp)
                }
                Text(title.title,color=P.ink,fontSize=49.sp,lineHeight=54.sp,fontWeight=FontWeight.SemiBold,
                    maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=12.dp))
                Box(Modifier.padding(top=15.dp)) { PMeta(title) }
                Text(detail?.takeIf { it.title.id==title.id }?.summary?.replace("\n"," ")?.trim().orEmpty(),color=P.muted,
                    fontSize=13.sp,lineHeight=20.sp,maxLines=2,overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=14.dp).width(540.dp).height(40.dp))
            } else {
                Text("Здесь начинается\nваш кинозал.",color=P.ink,fontSize=49.sp,lineHeight=56.sp,fontWeight=FontWeight.Medium)
            }
            // Keep one focus target alive while the initial catalogue/scene loads.
            Row(Modifier.padding(top=24.dp),horizontalArrangement=Arrangement.spacedBy(11.dp)) {
                val last=title?.let { Store.lastEntry(it.id) }
                val label=if(title==null) "Выбрать аниме" else if(last!=null&&last.progress<=.92) "Продолжить просмотр" else "Открыть тайтл"
                PButton(label,Icons.Rounded.PlayArrow,primary=true,
                    modifier=Modifier.memo(scope,"hero-primary",initial=true).testTag("panorama-hero-primary")) {
                    if(title==null)nav.tab=Tab.CATALOG
                    else if(last!=null&&last.progress<=.92)nav.play(title,last.episodeId,last.teamId,resume=true)
                    else nav.open(title)
                }
                if(hasMore&&title!=null)PButton("Следующая история",Icons.Rounded.ArrowForward,onClick=onNext)
            }
        }
        Text("ANIXQ / CINEMA",color=P.muted.copy(alpha=.5f),fontSize=9.sp,letterSpacing=2.sp,
            modifier=Modifier.align(Alignment.BottomEnd).padding(end=42.dp,bottom=36.dp))
    }
}
