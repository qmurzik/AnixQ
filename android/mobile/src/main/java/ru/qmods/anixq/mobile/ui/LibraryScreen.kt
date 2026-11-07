package ru.qmods.anixq.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.qmods.anixq.tv.data.Bookmark
import ru.qmods.anixq.tv.data.Store

/** "Моё": history and the lists — the same ones the TV and iPhone show. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(nav: Nav) {
    val version by Store.version.collectAsState()
    val tabs = listOf<Bookmark?>(null) + Bookmark.entries
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val (eps, hours, titles) = remember(version) { Store.stats() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column {
                Text("Моё", fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text("$eps серий · $hours ч · $titles тайтлов", color = Pal.muted, fontSize = 13.sp)
            }
        }
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 16.dp, containerColor = Pal.bg) {
            tabs.forEachIndexed { i, b -> Tab(tab == i, { tab = i }, text = { Text(b?.title ?: "История") }) }
        }
        val status = tabs[tab]
        val h = remember(version) { Store.history.sortedByDescending { it.at } }
        val list = remember(version, status) { status?.let { Store.titles(it) } ?: emptyList() }
        LazyVerticalGrid(GridCells.Adaptive(108.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (status == null) {
                items(h, key = { it.title.id }) { e ->
                    PosterCard(e.title, width = 200.dp, subtitle = "Серия ${e.ep}", progress = e.progress.toFloat()) { nav.open(e.title) }
                }
                if (h.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Centered { Text("Здесь появится всё, что вы смотрите — на телефоне, телевизоре или iPhone.", color = Pal.muted) } }
            } else {
                items(list, key = { it.id }) { t -> PosterCard(t, width = 200.dp) { nav.open(t) } }
                if (list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Centered { Text("Список пуст", color = Pal.muted) } }
            }
        }
    }
}
