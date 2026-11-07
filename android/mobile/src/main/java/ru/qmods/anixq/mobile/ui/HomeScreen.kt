package ru.qmods.anixq.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.CatalogQuery
import ru.qmods.anixq.tv.data.CatalogSort
import ru.qmods.anixq.tv.data.Remote
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Title
import ru.qmods.anixq.tv.data.WatchEntry

private object HomeCache { var rows: List<Pair<String, List<Title>>> = emptyList() }

@Composable
fun HomeScreen(nav: Nav) {
    val version by Store.version.collectAsState()
    val online by Remote.online.collectAsState()
    var rows by remember { mutableStateOf(HomeCache.rows.ifEmpty { Store.loadHomeCache() }) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            val fresh = coroutineScope {
                val specs = listOf(
                    "Новые серии" to CatalogQuery(CatalogSort.UPDATED),
                    "Популярное" to CatalogQuery(CatalogSort.POPULAR),
                    "Онгоинги" to CatalogQuery(CatalogSort.POPULAR, status = 1),
                    "Лучшие по рейтингу" to CatalogQuery(CatalogSort.RATING),
                    "Новинки каталога" to CatalogQuery(CatalogSort.NEWEST),
                )
                specs.map { (n, q) -> async { n to runCatching { Api.catalog(q, 1).first }.getOrDefault(emptyList()) } }.map { it.await() }
            }.filter { it.second.isNotEmpty() }
            if (fresh.isNotEmpty()) { rows = fresh; HomeCache.rows = fresh; Store.saveHomeCache(fresh) }
            else if (rows.isEmpty()) error = "Не удалось загрузить каталог. Проверьте интернет."
        } catch (e: Exception) { if (rows.isEmpty()) error = e.message }
    }
    val cont = remember(version) { Store.continueList }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("AnixQ", fontSize = 28.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                if (Store.remoteChannel.isNotEmpty()) IconButton(onClick = { nav.push(RemoteRoute) }) {
                    Box {
                        Icon(Icons.Default.SettingsRemote, "Пульт ТВ", tint = Pal.text)
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (online) Pal.green else Pal.faint).align(Alignment.TopEnd))
                    }
                }
            }
        }
        item { NowOnTvCard(nav) }
        if (cont.isNotEmpty()) {
            item { SectionTitle("Продолжить") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(cont, key = { it.title.id }) { e -> ContinueCard(e) { nav.play(PlayerRoute(e.title, resume = true)) } }
                }
            }
        }
        if (rows.isEmpty()) item { if (error != null) Centered { Text(error!!, color = Pal.muted) } else Box(Modifier.height(240.dp)) { Loading() } }
        rows.forEach { (name, ts) -> item(key = name) { TitleRow(name, ts) { nav.open(it) } } }
    }
}

@Composable
private fun ContinueCard(e: WatchEntry, onClick: () -> Unit) {
    val finished = e.progress > 0.92
    Column(Modifier.width(260.dp).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(146.dp).clip(RoundedCornerShape(14.dp)).background(Pal.card)) {
            AsyncImage(e.title.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))))
            Box(Modifier.align(Alignment.Center).size(46.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Text(if (finished) "Дальше: серия после ${e.ep}" else "Серия ${e.ep}" + (e.epName.takeIf { it.isNotBlank() && it != e.ep }?.let { " · $it" } ?: ""),
                    fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!finished && e.dur > 0) {
                    Text("Осталось ${((e.dur - e.pos) / 60).toInt().coerceAtLeast(1)} мин", fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.height(5.dp))
                    ProgressLine(e.progress.toFloat())
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(e.title.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
