package ru.qmods.anixq.mobile.ui

import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.CatalogQuery
import ru.qmods.anixq.tv.data.CatalogSort
import ru.qmods.anixq.tv.data.Title

/** Paged catalog results for one query (kept while the screen lives). */
class CatalogLoader {
    var items by mutableStateOf<List<Title>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var more by mutableStateOf(true)
    private var page = 1
    var query: CatalogQuery? = null; private set

    suspend fun reset(q: CatalogQuery) {
        query = q; items = emptyList(); page = 1; more = true; error = null; loading = false
        loadMore()
    }

    suspend fun loadMore() {
        val q = query ?: return
        if (loading || !more) return
        loading = true
        try {
            val (list, m) = Api.catalog(q, page)
            if (q != query) return
            val seen = items.map { it.id }.toHashSet()
            items = items + list.filter { seen.add(it.id) }
            more = m && list.isNotEmpty(); page += 1
        } catch (e: Exception) { if (q == query) error = e.message ?: "Ошибка загрузки" }
        finally { if (q == query) loading = false }
    }
}

/** Grid of posters with endless scrolling; header items go first. */
@Composable
fun PosterGrid(loader: CatalogLoader, onOpen: (Title) -> Unit, onRetry: () -> Unit, header: LazyGridScope.() -> Unit = {}) {
    val state = rememberLazyGridState()
    val nearEnd by remember { androidx.compose.runtime.derivedStateOf {
        val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        last >= state.layoutInfo.totalItemsCount - 6
    } }
    LaunchedEffect(nearEnd, loader.items.size) { if (nearEnd && loader.items.isNotEmpty()) loader.loadMore() }
    LazyVerticalGrid(GridCells.Adaptive(105.dp), Modifier.fillMaxSize(), state,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        header()
        items(loader.items, key = { it.id }) { t -> PosterCard(t) { onOpen(t) } }
        item(span = { GridItemSpan(maxLineSpan) }) {
            when {
                loader.loading -> Loading(Modifier.padding(24.dp))
                loader.error != null -> ErrorState(loader.error!!, onRetry)
                loader.items.isEmpty() -> EmptyState(Icons.Default.FilterList, "Ничего не найдено", "Попробуйте ослабить фильтры")
                else -> Box(Modifier)
            }
        }
    }
}

private val statuses = listOf(1 to "Онгоинг", 2 to "Вышел", 3 to "Анонс")
private val types = listOf(16 to "TV", 17 to "Фильм", 20 to "OVA", 21 to "ONA", 19 to "Спешл")

@Composable
fun CatalogScreen(nav: Nav) {
    var query by remember { mutableStateOf(CatalogQuery()) }
    val loader = remember { CatalogLoader() }
    var sortMenu by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf(false) }
    LaunchedEffect(query) { if (loader.query != query) loader.reset(query) }
    if (filters) FilterSheet(query, { query = it }) { filters = false }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        LargeTitle("Каталог")
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Box {
                    Capsule(query.sort.title, Icons.AutoMirrored.Filled.Sort, selected = true, trailing = Icons.Default.KeyboardArrowDown) { sortMenu = true }
                    DropdownMenu(sortMenu, { sortMenu = false }, containerColor = Pal.cardHi) {
                        CatalogSort.entries.forEach { s -> DropdownMenuItem({ Text(s.title, color = if (s == query.sort) Pal.pink else Pal.text) },
                            onClick = { sortMenu = false; query = query.copy(sort = s) }) }
                    }
                }
            }
            statuses.forEach { (id, name) -> item { Capsule(name, selected = query.status == id) { query = query.copy(status = if (query.status == id) null else id) } } }
            types.forEach { (id, name) -> item { Capsule(name, selected = query.type == id) { query = query.copy(type = if (query.type == id) null else id) } } }
            item { Capsule(if (query.extraFilters > 0) "Фильтры · ${query.extraFilters}" else "Фильтры", Icons.Default.Tune, selected = query.extraFilters > 0) { filters = true } }
            if (query.status != null || query.type != null || query.extraFilters > 0) item { Capsule("Сбросить", Icons.Default.Close) { query = CatalogQuery(sort = query.sort) } }
        }
        PosterGrid(loader, { nav.open(it) }, { scope.launch { loader.reset(query) } })
    }
}

/** "Все" from a home shelf, a genre, a discover chip. */
@Composable
fun CatalogListScreen(nav: Nav, route: CatalogRoute) {
    val loader = remember(route) { CatalogLoader() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(route) { if (loader.query != route.query) loader.reset(route.query) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar(route.title, { nav.pop() })
        PosterGrid(loader, { nav.open(it) }, { scope.launch { loader.reset(route.query) } })
    }
}

@Composable
fun LargeTitle(text: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Inline navigation bar: back chevron + centred title. */
@Composable
fun TopBar(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Box(Modifier.padding(horizontal = 4.dp).padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Pal.pink) }
            Box(Modifier.weight(1f))
            trailing()
        }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp))
    }
}
