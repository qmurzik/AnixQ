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
        if (!force && rows.value.isNotEmpty() && System.currentTimeMillis() - loadedAt < 15 * 60_000) return
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
                rows.value = parts.map { it.await() }.filter { it.second.isNotEmpty() }
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

val HeroHeight = 250.dp

@Composable
fun HomeScreen(nav: Nav) {
    val version by Store.version.collectAsState()
    val rows by HomeData.rows.collectAsState()
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
                            ContinueCard(e,
                                onClick = { nav.play(e.title, e.episodeId, e.teamId, next = e.progress > 0.92) },
                                onLongClick = { nav.open(e.title) },
                                modifier = Modifier.memo("home", "c${e.title.id}", initial = e == cont.first()),
                                onFocus = { spot.value = e.title })
                        }
                    }
                }
                if (watching.isNotEmpty()) item("watching") { TitleShelf("Смотрю", watching, "home", { spot.value = it }) { nav.open(it) } }
                rows.forEachIndexed { i, (name, titles) ->
                    item(name) {
                        TitleShelf(name, titles, "home", { spot.value = it },
                            initialKey = if (i == 0 && cont.isEmpty() && loggedIn) titles.first().id else null) { nav.open(it) }
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
    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = t.id to detail?.background, animationSpec = tween(350), label = "backdrop") { (_, bg) ->
            Box(Modifier.fillMaxSize()) {
                if (bg != null) {
                    Cover(bg, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.78f).height(TopBarHeight + HeroHeight + 120.dp))
                } else {
                    Ambient(t.thumb, Modifier.fillMaxSize().alpha(0.55f))
                    Cover(t.poster, Modifier.align(Alignment.TopEnd).padding(top = TopBarHeight + 6.dp, end = 64.dp).height(HeroHeight + 20.dp)
                        .aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)))
                }
                Box(Modifier.fillMaxSize().background(C.scrimLeft))
                Box(Modifier.fillMaxWidth().height(TopBarHeight + HeroHeight + 130.dp)
                    .background(Brush.verticalGradient(0.55f to androidx.compose.ui.graphics.Color.Transparent, 1f to C.bg)))
                Box(Modifier.fillMaxSize().padding(top = TopBarHeight + HeroHeight + 129.dp).background(C.bg))
            }
        }
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
