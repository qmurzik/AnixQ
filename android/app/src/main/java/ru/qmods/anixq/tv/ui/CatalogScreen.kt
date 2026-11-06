package ru.qmods.anixq.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.CatalogQuery
import ru.qmods.anixq.tv.data.CatalogSort
import ru.qmods.anixq.tv.data.Title

private val statuses = listOf(null to "Все", 1 to "Онгоинги", 2 to "Вышедшие", 3 to "Анонсы")
private val types = listOf(null to "Любой тип", 16 to "TV", 17 to "Фильмы", 20 to "OVA", 21 to "ONA", 19 to "Спешлы", 18 to "Короткие")

/** Loaded pages, kept while the app runs so returning from a title is instant. */
private object CatalogState {
    var query = CatalogQuery()
    val items = mutableStateListOf<Title>()
    var page = 0
    var more = true
    var genres: List<Pair<Int, String>> = emptyList()
}

@Composable
fun CatalogScreen(nav: Nav) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(CatalogState.query) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var genres by remember { mutableStateOf(CatalogState.genres) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun loadMore(reset: Boolean = false) {
        if (reset) { job?.cancel(); CatalogState.items.clear(); CatalogState.page = 0; CatalogState.more = true; Focus.reset("catalog") }
        if (!CatalogState.more || (loading && !reset)) return
        loading = true
        job = scope.launch {
            try {
                val (list, more) = Api.catalog(query, CatalogState.page + 1)
                val known = CatalogState.items.map { it.id }.toHashSet()
                CatalogState.items.addAll(list.filter { it.id !in known })
                CatalogState.page += 1
                CatalogState.more = more && list.isNotEmpty()
                error = null
            } catch (e: Exception) { error = e.message ?: "Ошибка" }
            loading = false
        }
    }

    fun apply(q: CatalogQuery) { query = q; CatalogState.query = q; loadMore(reset = true) }

    LaunchedEffect(Unit) {
        if (CatalogState.items.isEmpty()) loadMore(reset = true)
        if (genres.isEmpty()) { genres = Api.genres(); CatalogState.genres = genres }
    }

    val grid = rememberLazyGridState()
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= grid.layoutInfo.totalItemsCount - 12) loadMore()
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp), state = grid, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "head") { ScreenTitle("Каталог") }
        item(span = { GridItemSpan(maxLineSpan) }, key = "filters") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                CatalogSort.entries.forEach { s ->
                    Chip(s.title, query.sort == s) { apply(query.copy(sort = s)) }
                }
                Box(Modifier.padding(horizontal = 6.dp))
                Chip(statuses.first { it.first == query.status }.second, query.status != null) { dialog = "status" }
                Chip(types.firstOrNull { it.first == query.type }?.second ?: "Тип", query.type != null) { dialog = "type" }
                Chip(genres.firstOrNull { it.first == query.genre }?.second ?: "Жанр", query.genre != null) { dialog = "genre" }
                if (query != CatalogQuery()) Chip("Сбросить", false) { apply(CatalogQuery()) }
            }
        }
        items(CatalogState.items, key = { it.id }) { t ->
            PosterCard(t, onClick = { nav.open(t) }, modifier = Modifier.memo("catalog", t.id.toString(), initial = t == CatalogState.items.first()))
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "foot") {
            Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                when {
                    error != null -> PillButton("$error · Повторить") { error = null; loadMore() }
                    loading -> Text("Загрузка…", color = C.muted, fontSize = 16.sp)
                    CatalogState.items.isEmpty() -> Text("Ничего не найдено", color = C.muted, fontSize = 16.sp)
                }
            }
        }
    }

    when (dialog) {
        "status" -> ChoiceDialog("Статус", statuses.map { Choice(it.second, it.first == query.status) }, { dialog = null }) {
            dialog = null; apply(query.copy(status = statuses[it].first))
        }
        "type" -> ChoiceDialog("Тип", types.map { Choice(it.second, it.first == query.type) }, { dialog = null }) {
            dialog = null; apply(query.copy(type = types[it].first))
        }
        "genre" -> {
            val list = listOf<Pair<Int?, String>>(null to "Все жанры") + genres.sortedBy { it.second }
            ChoiceDialog("Жанр", list.map { Choice(it.second, it.first == query.genre) }, { dialog = null }) {
                dialog = null; apply(query.copy(genre = list[it].first))
            }
        }
    }
}
