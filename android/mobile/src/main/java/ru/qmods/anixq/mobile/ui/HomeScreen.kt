package ru.qmods.anixq.mobile.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.Bookmark
import ru.qmods.anixq.tv.data.CatalogQuery
import ru.qmods.anixq.tv.data.CatalogSort
import ru.qmods.anixq.tv.data.Remote
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Title
import ru.qmods.anixq.tv.data.WatchEntry

/** Home rows survive tab switches and are shown instantly at the next start (Store cache). */
object HomeData {
    var rows by mutableStateOf<Map<String, List<Title>>>(emptyMap())
    var hero by mutableStateOf<List<Pair<Title, String>>>(emptyList())
    var loadedAt = 0L
    var error by mutableStateOf<String?>(null)

    val specs = listOf(
        "updated" to CatalogQuery(CatalogSort.UPDATED),
        "ongoing" to CatalogQuery(CatalogSort.POPULAR, status = 1),
        "popular" to CatalogQuery(CatalogSort.POPULAR),
        "top" to CatalogQuery(CatalogSort.RATING),
        "newest" to CatalogQuery(CatalogSort.NEWEST),
    )

    fun restore() {
        if (rows.isNotEmpty()) return
        val cached = Store.loadHomeCache()
        rows = cached.filter { it.first != "hero" }.toMap()
        cached.firstOrNull { it.first == "hero" }?.second?.let { hs -> hero = hs.mapNotNull { t -> t.cover?.let { t to it } } }
    }

    suspend fun load(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - loadedAt < 15 * 60_000 && rows.isNotEmpty()) return
        error = null
        val fresh = coroutineScope {
            specs.map { (k, q) -> async { k to runCatching { Api.catalog(q, 1).first }.getOrDefault(emptyList()) } }.map { it.await() }
        }.filter { it.second.isNotEmpty() }.toMap()
        if (fresh.isEmpty()) { if (rows.isEmpty()) error = "Не удалось загрузить AnimeLib. Проверьте подключение."; return }
        rows = rows + fresh
        loadedAt = System.currentTimeMillis()
        // Hero: popular ongoings with their wide background art.
        val picks = (fresh["ongoing"] ?: fresh["popular"] ?: emptyList()).take(7)
        val withArt = coroutineScope { picks.map { t -> async { runCatching { Api.detail(t.slug) }.getOrNull()?.background?.let { t to it } } }.mapNotNull { it.await() } }
        if (withArt.isNotEmpty()) hero = withArt
        // The hero's art goes into the title's "cover" field of the cache (restored as the background).
        Store.saveHomeCache(rows.toList() + ("hero" to hero.map { (t, bg) -> t.copy(cover = bg) }))
    }
}

@Composable
fun HomeScreen(nav: Nav) {
    val version by Store.version.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { HomeData.restore(); HomeData.load() }
    val rows = HomeData.rows
    val cont = remember(version) { Store.continueList.take(15) }
    val watching = remember(version) { Store.titles(Bookmark.WATCHING) }
    fun catalog(name: String, key: String) = HomeData.specs.first { it.first == key }.second.let { q -> { nav.push(CatalogRoute(name, q)) } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item(key = "hero") {
            if (HomeData.hero.isNotEmpty()) Hero(nav, HomeData.hero)
            else Box(Modifier.statusBarsPadding().fillMaxWidth().height(70.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                Text("AnixQ", fontSize = 34.sp, fontWeight = FontWeight.Black)
            }
        }
        item(key = "discover") { DiscoverStrip(nav) }
        item(key = "tv") { Box(Modifier.padding(top = 18.dp).animateContentSize()) { NowOnTvCard(nav) } }
        if (cont.isNotEmpty()) item(key = "continue") {
            Column(Modifier.padding(top = 26.dp)) {
                SectionHeader("Продолжить просмотр")
                Spacer(Modifier.height(10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(cont, key = { it.title.id }) { e -> ContinueCard(e, onOpen = { nav.open(e.title) }) { nav.play(PlayerRoute(e.title, resume = true)) } }
                }
            }
        }
        if (Store.remoteChannel.isNotEmpty()) item(key = "remote") { RemoteBanner(nav) }
        if (rows.isEmpty()) {
            item { HomeData.error?.let { ErrorState(it) { scope.launch { HomeData.load(force = true) } } } ?: Column { ShelfSkeleton(); ShelfSkeleton() } }
        }
        item(key = "watching") { Shelf("Смотрю", watching) { nav.open(it) } }
        item(key = "updated") { Shelf("Новые серии", rows["updated"].orEmpty(), catalog("Новые серии", "updated")) { nav.open(it) } }
        item(key = "ongoing") { Shelf("Онгоинги", rows["ongoing"].orEmpty(), catalog("Онгоинги", "ongoing")) { nav.open(it) } }
        item(key = "top10") { TopTenShelf("Топ-10 сейчас", rows["popular"].orEmpty(), catalog("Популярное", "popular")) { nav.open(it) } }
        item(key = "top") { Shelf("Лучшее по рейтингу", rows["top"].orEmpty(), catalog("Лучшее по рейтингу", "top")) { nav.open(it) } }
        item(key = "newest") { Shelf("Новинки каталога", rows["newest"].orEmpty().filter { it.statusId != 3 }, catalog("Новинки", "newest")) { nav.open(it) } }
    }
}

@Composable
private fun Hero(nav: Nav, items: List<Pair<Title, String>>) {
    val pager = rememberPagerState { items.size }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage, items.size) {
        delay(7000)
        if (items.size > 1) pager.animateScrollToPage((pager.currentPage + 1) % items.size)
    }
    val version by Store.version.collectAsState()
    Box(Modifier.fillMaxWidth().height(540.dp)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
            val (t, bg) = items[i]
            Box(Modifier.fillMaxSize()) {
                Img(bg, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.35f), 0.25f to Color.Transparent, 0.45f to Color.Transparent,
                    0.8f to Pal.bg.copy(alpha = 0.85f), 1f to Pal.bg)))
                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ОНГОИНГ НЕДЕЛИ", fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.6.sp, color = Color.White,
                        modifier = Modifier.clip(CircleShape).background(Pal.pink.copy(alpha = 0.9f)).padding(horizontal = 10.dp, vertical = 4.dp))
                    Text(t.title, fontSize = 30.sp, fontWeight = FontWeight.Black, lineHeight = 34.sp, textAlign = TextAlign.Center, maxLines = 3,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        t.rating?.takeIf { it > 0 }?.let { MetaChip("%.1f".format(it), Icons.Default.Star, Pal.gold) }
                        t.typeLabel?.let { MetaChip(it) }
                        t.year?.let { MetaChip(it.toString()) }
                    }
                    Row(Modifier.padding(horizontal = 40.dp).padding(top = 16.dp).widthIn(max = 520.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton(Modifier.weight(1f), onClick = { nav.open(t) }) {
                            Icon(Icons.Default.PlayArrow, null, tint = Color.White)
                            Text(" Смотреть", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Color.White)
                        }
                        Box {
                            val has = remember(version) { Store.bookmark(t.slug) != null }
                            GlassButton(if (has) Icons.Default.Check else Icons.Default.Add, "В закладки") { menu = true }
                            if (pager.currentPage == i) BookmarkMenu(t, menu) { menu = false }
                        }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            items.indices.forEach { i ->
                Box(Modifier.size(width = if (i == pager.currentPage) 18.dp else 6.dp, height = 6.dp).clip(CircleShape)
                    .background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = 0.3f)))
            }
        }
    }
}

private data class Discover(val title: String, val icon: ImageVector, val colors: List<Color>, val query: CatalogQuery)

@Composable
private fun DiscoverStrip(nav: Nav) {
    val items = listOf(
        Discover("Онгоинги", Icons.Default.Sensors, listOf(Pal.orange, Pal.red), CatalogQuery(CatalogSort.POPULAR, status = 1)),
        Discover("Топ", Icons.Default.EmojiEvents, listOf(Pal.gold, Pal.orange), CatalogQuery(CatalogSort.RATING)),
        Discover("Фильмы", Icons.Default.Movie, listOf(Pal.blue, Pal.indigo), CatalogQuery(CatalogSort.RATING, type = 17)),
        Discover("Новые серии", Icons.Default.AutoAwesome, listOf(Pal.pink, Pal.purple), CatalogQuery(CatalogSort.UPDATED)),
        Discover("Новинки", Icons.Default.NewReleases, listOf(Pal.green, Pal.teal), CatalogQuery(CatalogSort.NEWEST)),
    )
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { it.title }) { d ->
            Row(Modifier.clip(CircleShape).background(Pal.card).border(0.5.dp, Pal.stroke, CircleShape).pressable { nav.push(CatalogRoute(d.title, d.query)) }
                .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(Brush.linearGradient(d.colors)), contentAlignment = Alignment.Center) {
                    Icon(d.icon, null, Modifier.size(16.dp), tint = Color.White)
                }
                Text(d.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** 16:9 "Up Next" card with progress, like the iPhone's. Tap plays; the title opens the page. */
@Composable
fun ContinueCard(e: WatchEntry, width: Int = 260, onOpen: () -> Unit, onPlay: () -> Unit) {
    val finished = e.progress > 0.92
    Column(Modifier.width(width.dp)) {
        Box(Modifier.fillMaxWidth().height((width * 9 / 16).dp).clip(RoundedCornerShape(14.dp)).background(Pal.card)
            .border(0.5.dp, Pal.stroke, RoundedCornerShape(14.dp)).pressable(onClick = onPlay)) {
            Img(e.title.poster, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f))))
            Box(Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))
                .border(0.5.dp, Color.White.copy(alpha = 0.25f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (finished) Icons.Default.SkipNext else Icons.Default.PlayArrow, null, Modifier.size(14.dp), tint = Color.White)
                    val n = e.ep.toIntOrNull()
                    Text(if (finished && n != null) " Дальше · серия ${n + 1}" else " Серия ${e.ep}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    if (!finished && e.dur > 0) Text(" · осталось ${((e.dur - e.pos) / 60).toInt().coerceAtLeast(1)} мин", fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.75f), maxLines = 1)
                }
                if (!finished) ProgressLine(e.progress.toFloat(), Modifier.padding(top = 6.dp))
            }
        }
        Text(e.title.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp).pressable(onClick = onOpen))
        if (e.teamName.isNotBlank()) Text(e.teamName, fontSize = 12.sp, color = Pal.muted, maxLines = 1)
    }
}

@Composable
private fun RemoteBanner(nav: Nav) {
    val online by Remote.online.collectAsState()
    Row(Modifier.padding(horizontal = 16.dp).padding(top = 22.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.card)
        .pressable { nav.push(RemoteRoute) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.SettingsRemote, null, tint = Pal.pink, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text("Пульт телевизора", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(if (online) "На связи — управляйте ТВ откуда угодно" else "Подключаемся к ТВ…", fontSize = 12.sp, color = Pal.muted, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Pal.faint)
    }
}
