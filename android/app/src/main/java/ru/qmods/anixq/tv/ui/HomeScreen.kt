package ru.qmods.anixq.tv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.Bookmark
import ru.qmods.anixq.tv.data.CatalogQuery
import ru.qmods.anixq.tv.data.CatalogSort
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Sync
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

@Composable
fun HomeScreen(nav: Nav) {
    val version by Store.version.collectAsState()
    val rows by HomeData.rows.collectAsState()
    val error by HomeData.error.collectAsState()
    val syncStatus by Sync.status.collectAsState()
    var spot by remember { mutableStateOf<Title?>(null) }
    LaunchedEffect(Unit) { HomeData.load() }

    val cont = remember(version) { Store.continueList }
    val watching = remember(version) { Store.titles(Bookmark.WATCHING) }
    val planned = remember(version) { Store.titles(Bookmark.PLANNED) }
    val shown = spot ?: cont.firstOrNull()?.title ?: rows.firstOrNull()?.second?.firstOrNull()

    Box(Modifier.fillMaxSize()) {
        // Backdrop of whatever is focused.
        Crossfade(targetState = shown?.poster, label = "spot") { url ->
            Box(Modifier.fillMaxSize()) {
                if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.62f).height(380.dp).alpha(0.55f))
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.3f to C.bg, 0.75f to C.bg.copy(alpha = 0.35f), 1f to Color.Transparent)))
                Box(Modifier.fillMaxWidth().height(400.dp).background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to C.bg)))
            }
        }
        Column(Modifier.fillMaxSize()) {
            Spot(shown, Modifier.height(200.dp))
            val list = rememberLazyListState()
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp)) {
                item("update") { UpdateBanner() }
                if (Store.libToken == null) item("login") {
                    Row(Modifier.padding(start = 48.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        PillButton("Войти в AnimeLib — плеер без рекламы", Icons.Rounded.PhoneIphone, primary = true,
                            modifier = Modifier.memo("home", "login", initial = cont.isEmpty())) { nav.push(LoginRoute) }
                    }
                }
                if (cont.isNotEmpty()) item("continue") {
                    Shelf("Продолжить просмотр") {
                        items(cont, key = { "c" + it.title.id }) { e ->
                            ContinueCard(e,
                                onClick = { nav.play(e.title, e.episodeId, e.teamId, next = e.progress > 0.92) },
                                onLongClick = { nav.open(e.title) },
                                modifier = Modifier.memo("home", "c${e.title.id}", initial = e == cont.first()),
                                onFocus = { spot = e.title })
                        }
                    }
                }
                if (watching.isNotEmpty()) item("watching") { TitleShelf("Смотрю", watching, "home", { spot = it }) { nav.open(it) } }
                rows.forEachIndexed { i, (name, titles) ->
                    item(name) {
                        TitleShelf(name, titles, "home", { spot = it }, initialKey = if (i == 0 && cont.isEmpty() && Store.libToken != null) titles.first().id else null) { nav.open(it) }
                    }
                }
                if (planned.isNotEmpty()) item("planned") { TitleShelf("Запланировано", planned, "home", { spot = it }) { nav.open(it) } }
                if (rows.isEmpty()) item("state") {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        if (error != null) PillButton(error!! + "  ·  Повторить", modifier = Modifier.memo("home", "retry", initial = true)) {
                            HomeData.error.value = null
                        } else Text("Загрузка…", color = C.muted, fontSize = 18.sp)
                    }
                }
                item("sync") {
                    Text(if (Store.githubToken != null) "Синхронизация: $syncStatus" else "", color = C.muted, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 48.dp))
                }
            }
        }
    }
    LaunchedEffect(error) { if (error == null && rows.isEmpty()) HomeData.load(force = true) }
}

@Composable
private fun Spot(t: Title?, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(start = 48.dp, top = 34.dp, end = 48.dp), verticalArrangement = Arrangement.Top) {
        Text("AnixQ", fontSize = 15.sp, fontWeight = FontWeight.Black, color = C.pink)
        Spacer(Modifier.height(10.dp))
        if (t != null) {
            Text(t.title, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(640.dp), lineHeight = 38.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (t.isOngoing) Text("ОНГОИНГ", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.background(C.pink, androidx.compose.foundation.shape.RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                Text(t.meta, fontSize = 15.sp, color = C.muted)
                t.age?.let { Text(it, fontSize = 15.sp, color = C.muted) }
            }
        }
    }
}
