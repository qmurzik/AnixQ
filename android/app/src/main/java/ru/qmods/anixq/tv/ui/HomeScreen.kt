package ru.qmods.anixq.tv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Login
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.Bookmark
import ru.qmods.anixq.tv.data.CatalogQuery
import ru.qmods.anixq.tv.data.CatalogSort
import ru.qmods.anixq.tv.data.Detail
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Title

/** Home rows survive tab switches and returning from the player. */
object HomeData {
    val rows = MutableStateFlow<List<Pair<String, List<Title>>>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    private var loadedAt = 0L

    suspend fun load(force: Boolean = false) {
        if (rows.value.isEmpty()) rows.value = Store.loadHomeCache()   // instant start with last run's rows
        if (!force && loadedAt > 0 && System.currentTimeMillis() - loadedAt < 15 * 60_000) return
        try {
            coroutineScope {
                val specs = listOf(
                    "Новые серии" to CatalogQuery(sort = CatalogSort.UPDATED, status = 1),
                    "Популярные онгоинги" to CatalogQuery(sort = CatalogSort.POPULAR, status = 1),
                    "Популярное" to CatalogQuery(sort = CatalogSort.POPULAR),
                    "Высокий рейтинг" to CatalogQuery(sort = CatalogSort.RATING),
                    "Недавно добавлены" to CatalogQuery(sort = CatalogSort.NEWEST),
                )
                val parts = specs.map { (name, q) -> async { name to runCatching { Api.catalog(q, 1).first }.getOrDefault(emptyList()) } }
                val fresh = parts.map { it.await() }.filter { it.second.isNotEmpty() }
                if (fresh.isNotEmpty()) { rows.value = fresh; Store.saveHomeCache(fresh) }
            }
            error.value = if (rows.value.isEmpty()) "Не удалось загрузить каталог. Проверьте интернет." else null
            loadedAt = System.currentTimeMillis()
        } catch (e: Exception) {
            error.value = e.message
        }
    }
}

/** Description + wide art for the title under the cursor (fetched once, kept for the session). */
object Spotlight {
    private val cache = HashMap<String, Detail?>()
    fun cached(slug: String) = cache[slug]
    suspend fun get(slug: String): Detail? {
        if (cache.containsKey(slug)) return cache[slug]
        return runCatching { Api.detail(slug) }.getOrNull().also { cache[slug] = it }
    }
}

/** Home genre tiles: popular genres, matched by name against AnimeLib's list. */
object GenreTiles {
    private val wanted = listOf("Экшен", "Романтика", "Комедия", "Фэнтези", "Исекай", "Повседневность", "Драма", "Сёнэн",
        "Детектив", "Ужасы", "Спорт", "Меха", "Психологическое", "Школа")
    val list = MutableStateFlow<List<Pair<Int, String>>>(emptyList())
    suspend fun load() {
        if (list.value.isNotEmpty()) return
        val all = CatalogState.genres.ifEmpty { Api.genres().also { CatalogState.genres = it } }
        list.value = wanted.mapNotNull { w -> all.firstOrNull { it.second.equals(w, true) } }
    }
    val colors = listOf(0xFFFF4D7A, 0xFF8B5CFF, 0xFF34C3FF, 0xFFFF9F43, 0xFF2ED47A, 0xFFFF5C5C, 0xFF7C83FD)
}

/** "Похоже на «X»": similar titles for the two shows you watched last, minus what you already know. */
object ForYou {
    val rows = MutableStateFlow<List<Pair<String, List<Title>>>>(emptyList())
    private var basedOn: List<Int> = emptyList()
    suspend fun refresh(history: List<ru.qmods.anixq.tv.data.WatchEntry>) = coroutineScope {
        val seeds = history.map { it.title }.distinctBy { it.id }.take(2)
        if (seeds.map { it.id } == basedOn) return@coroutineScope
        val known = (history.map { it.title.id } + Store.bookmarks.values.map { it.title.id }).toHashSet()
        val got = seeds.map { t -> async { t to Api.similar(t.slug).filter { it.id !in known } } }.map { it.await() }
            .filter { it.second.size >= 3 }.map { (t, list) -> "Похоже на «${t.title.take(40)}»" to list }
        if (got.isNotEmpty()) { rows.value = got; basedOn = seeds.map { it.id } }
    }
}

/** How many episodes come after the one you're on (to say "ещё 3 серии" / "ждём новую серию"). */
object EpisodeCounts {
    val after = MutableStateFlow<Map<Int, Int>>(emptyMap())
    suspend fun refresh(entries: List<ru.qmods.anixq.tv.data.WatchEntry>) = coroutineScope {
        entries.take(14).map { e ->
            async {
                val eps = runCatching { Api.episodes(e.title.slug) }.getOrNull() ?: return@async null
                val i = eps.indexOfFirst { it.id == e.episodeId }.takeIf { it >= 0 } ?: eps.indexOfFirst { it.number == e.ep }
                if (i < 0) null else e.title.id to (eps.size - i - 1)
            }
        }.mapNotNull { it.await() }.let { got -> after.value = after.value + got }
    }
}

val HeroHeight = 250.dp

@Composable
fun HomeScreen(nav: Nav) {
    val version by Store.version.collectAsState()
    val rows by HomeData.rows.collectAsState()
    // Previews: find the clips of what's on screen in the background, so they start without waiting.
    LaunchedEffect(rows.size) {
        if (!Store.hoverPreview || rows.isEmpty()) return@LaunchedEffect
        delay(2_500)
        PreviewClips.prefetch((Store.continueList.map { it.title } + rows.take(2).flatMap { it.second.take(10) }).distinctBy { it.id })
    }
    val error by HomeData.error.collectAsState()
    // Written by cards, read only by the hero: moving the cursor doesn't redraw the rows.
    val spot = remember { mutableStateOf<Title?>(null) }
    LaunchedEffect(Unit) { HomeData.load() }
    LaunchedEffect(error) { if (error == null && rows.isEmpty()) HomeData.load(force = true) }

    val cont = remember(version) { Store.continueList }
    val watching = remember(version) { Store.titles(Bookmark.WATCHING).filter { w -> cont.none { it.title.id == w.id } } }
    val planned = remember(version) { Store.titles(Bookmark.PLANNED) }
    val fallback = cont.firstOrNull()?.title ?: rows.firstOrNull()?.second?.firstOrNull()
    val loggedIn = remember(version) { Store.libToken != null }
    val counts by EpisodeCounts.after.collectAsState()
    val genres by GenreTiles.list.collectAsState()
    val forYou by ForYou.rows.collectAsState()
    LaunchedEffect(cont.take(2).map { it.title.id }) { if (cont.isNotEmpty()) ForYou.refresh(cont) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { GenreTiles.load() }
    LaunchedEffect(cont.map { it.title.id to it.episodeId }) { EpisodeCounts.refresh(cont) }

    Box(Modifier.fillMaxSize()) {
        Hero(spot, fallback)
        val list = rememberLazyListState()
        Pivot {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(top = TopBarHeight + HeroHeight),
                contentPadding = PaddingValues(bottom = 200.dp)) {
                item("update") { UpdateBanner() }
                if (!loggedIn) item("login") {
                    Row(Modifier.padding(start = 56.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        PillButton("Войти в AnimeLib", Icons.Rounded.Login, primary = true,
                            modifier = Modifier.memo("home", "login", initial = cont.isEmpty())) { nav.push(LoginRoute) }
                        Text("   плеер без рекламы и ваши закладки", fontSize = 14.sp, color = C.muted)
                    }
                }
                if (cont.isNotEmpty()) item("continue") {
                    Shelf("Продолжить просмотр") {
                        items(cont, key = { "c" + it.title.id }) { e ->
                            val left = counts[e.title.id]
                            ContinueCard(e, left,
                                onClick = {
                                    if (e.progress > 0.92 && left == 0) nav.open(e.title)   // nothing new yet: show the title page
                                    else nav.play(e.title, e.episodeId, e.teamId, next = e.progress > 0.92, resume = true)
                                },
                                onLongClick = { nav.open(e.title) },
                                modifier = Modifier.memo("home", "c${e.title.id}", initial = e == cont.first()),
                                onFocus = { spot.value = e.title })
                        }
                    }
                }
                forYou.forEach { (name, titles) -> item("fy-$name") { TitleShelf(name, titles, "home", { spot.value = it }) { nav.open(it) } } }
                if (watching.isNotEmpty()) item("watching") { TitleShelf("Смотрю", watching, "home", { spot.value = it }) { nav.open(it) } }
                rows.forEachIndexed { i, (name, titles) ->
                    item(name) {
                        TitleShelf(name, titles, "home", { spot.value = it },
                            initialKey = if (i == 0 && cont.isEmpty() && loggedIn) titles.first().id else null) { nav.open(it) }
                    }
                    if (i == 1) {
                    if (genres.isNotEmpty()) item("genres") {
                        Shelf("Жанры") {
                            item("lucky") { GenreTile("Мне повезёт", "случайный хит", 0xFFFFC94D) { scope.launch {
                                val page = (1..5).random()
                                runCatching { Api.catalog(CatalogQuery(sort = CatalogSort.RATING), page).first }.getOrNull()?.randomOrNull()?.let { nav.open(it) }
                            } } }
                            items(genres.size) { i ->
                                val (id, name) = genres[i]
                                GenreTile(name, null, GenreTiles.colors[i % GenreTiles.colors.size]) { CatalogState.openWith(nav, CatalogQuery(genre = id)) }
                            }
                        }
                    }
                    }
                }
                if (planned.isNotEmpty()) item("planned") { TitleShelf("Запланировано", planned, "home", { spot.value = it }) { nav.open(it) } }
                if (rows.isEmpty()) item("state") {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        if (error != null) PillButton(error!! + "  ·  Повторить", modifier = Modifier.memo("home", "retry", initial = true)) {
                            HomeData.error.value = null
                        } else Text("Загрузка…", color = C.muted, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

/** Backdrop + description of the focused title. Waits until the cursor settles so fast scrolling stays smooth. */
@Composable
private fun Hero(spot: MutableState<Title?>, fallback: Title?) {
    val target = spot.value ?: fallback
    val shown by produceState(target, target?.id) { delay(if (value == null) 0 else 260); value = target }
    val t = shown ?: return
    val detail by produceState(Spotlight.cached(t.slug), t.slug) { value = Spotlight.get(t.slug) }
    val area = TopBarHeight + HeroHeight + 130.dp
    // Everything is drawn only over the hero area; below it the plain background shows (less overdraw).
    Box(Modifier.fillMaxWidth().height(area)) {
        Crossfade(targetState = t.id to detail?.background, animationSpec = tween(300), label = "backdrop") { (_, bg) ->
            Box(Modifier.fillMaxSize()) {
                if (bg != null) {
                    Cover(bg, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.78f).fillMaxHeight())
                } else {
                    Ambient(t.thumb, Modifier.fillMaxSize(), alpha = 0.55f)
                    Cover(t.poster, Modifier.align(Alignment.TopEnd).padding(top = TopBarHeight + 6.dp, end = 64.dp).height(HeroHeight + 20.dp)
                        .aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)))
                }
            }
        }
        // A muted clip of the show fades in when the cursor rests on it.
        HoverVideo(t, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.78f).aspectRatio(16f / 9f))
        Box(Modifier.fillMaxSize().drawBehind {
            drawRect(C.scrimLeft)
            drawRect(Brush.verticalGradient(0.55f to androidx.compose.ui.graphics.Color.Transparent, 1f to C.bg))
        })
    }
    Box(Modifier.fillMaxSize()) {
        AnimatedContent(targetState = t, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "hero",
            modifier = Modifier.padding(start = 56.dp, top = TopBarHeight + 14.dp).width(640.dp).height(HeroHeight - 14.dp)) { title ->
            Column {
                Text(title.title, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                MetaLine(title)
                val d = if (detail?.title?.id == title.id) detail else null
                d?.genres?.takeIf { it.isNotEmpty() }?.let {
                    Text(it.take(4).joinToString(" · "), fontSize = 14.sp, color = C.muted, modifier = Modifier.padding(top = 8.dp))
                }
                d?.summary?.let {
                    Text(it.replace("\n\n", " ").trim(), fontSize = 15.sp, lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = C.text.copy(alpha = 0.78f), modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}


@Composable
private fun GenreTile(name: String, sub: String?, color: Long, onClick: () -> Unit) {
    val c = androidx.compose.ui.graphics.Color(color)
    Focusable(onClick = onClick, modifier = Modifier.width(190.dp).height(96.dp), container = c.copy(alpha = 0.22f), focusedContainer = c) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c.copy(alpha = 0.55f), androidx.compose.ui.graphics.Color.Transparent))).padding(14.dp)) {
            Column(Modifier.align(Alignment.BottomStart)) {
                Text(name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White)
                sub?.let { Text(it, fontSize = 12.sp, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f)) }
            }
        }
    }
}
