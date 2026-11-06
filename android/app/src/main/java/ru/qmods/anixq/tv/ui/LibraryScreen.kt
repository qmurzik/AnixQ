package ru.qmods.anixq.tv.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import ru.qmods.anixq.tv.data.Bookmark
import ru.qmods.anixq.tv.data.Store

@Composable
fun LibraryScreen(nav: Nav) {
    val version by Store.version.collectAsState()
    var section by rememberSaveable { mutableStateOf(-1) } // -1 = history
    val history = remember(version) { Store.continueList }
    val titles = remember(version, section) { if (section < 0) emptyList() else Store.titles(Bookmark.entries[section]) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (section < 0) 330.dp else 128.dp), modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 56.dp, end = 56.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ScreenTitle("Моё", "Закладки и история общие с iPhone")
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Chip("История", section < 0) { section = -1 }
                Bookmark.entries.forEachIndexed { i, b ->
                    val n = Store.bookmarks.values.count { it.status == b }
                    if (n > 0 || b == Bookmark.WATCHING || b == Bookmark.PLANNED) Chip("${b.title} $n", section == i) { section = i }
                }
            }
        }
        if (section < 0) {
            items(history, key = { "h" + it.title.id }) { e ->
                ContinueCard(e, onClick = { nav.play(e.title, e.episodeId, e.teamId, next = e.progress > 0.92) }, onLongClick = { nav.open(e.title) },
                    modifier = Modifier.memo("library", "h${e.title.id}"))
            }
        } else {
            items(titles, key = { it.id }) { t -> PosterCard(t, onClick = { nav.open(t) }, width = null, modifier = Modifier.memo("library", "b${t.id}")) }
        }
        if ((section < 0 && history.isEmpty()) || (section >= 0 && titles.isEmpty())) item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Text(if (section < 0) "Здесь появится то, что вы смотрите — здесь и на iPhone" else "Пока пусто", color = C.muted, fontSize = 17.sp)
            }
        }
    }
}
