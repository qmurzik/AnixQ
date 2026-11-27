package ru.qmods.anixq.mobile.ui.cinema

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.qmods.anixq.mobile.data.Downloads
import ru.qmods.anixq.mobile.data.PlaybackHistory
import ru.qmods.anixq.mobile.ui.*
import ru.qmods.anixq.tv.data.*
import ru.qmods.anixq.tv.data.sources.SourceIds

@Composable
internal fun CinemaHome(nav: Nav) {
    val revision by PlaybackHistory.revision.collectAsState()
    val version by Store.version.collectAsState()
    val history = remember(version, revision) { PlaybackHistory.continueEntries() }
    val source = nav.defaultCatalog
    val rows = HomeData.rows
    val hero = HomeData.hero.firstOrNull()
    val featured = hero?.first ?: rows["ongoing"]?.firstOrNull() ?: rows["popular"]?.firstOrNull()
    val scope = rememberCoroutineScope()
    LaunchedEffect(source) { HomeData.load(source) }

    LazyColumn(Modifier.fillMaxSize().background(Film.canvas), contentPadding = PaddingValues(bottom = 18.dp)) {
        item("header") {
            CinemaHeader("AnixQ") {
                CinemaIcon(Icons.Default.SettingsRemote, "Телевизор") { nav.push(RemoteRoute) }
                CinemaIcon(Icons.Default.Settings, "Настройки") { nav.push(SettingsRoute) }
            }
        }
        if (featured != null) item("feature") {
            CinemaFeature(featured, hero?.second ?: featured.poster) { nav.open(featured) }
        } else item("status") {
            CinemaStatus(HomeData.error, loading = HomeData.error == null, action = HomeData.error?.let {
                { scope.launch { HomeData.load(source, force = true) }; Unit }
            })
        }
        if (history.isNotEmpty()) {
            item("continue-heading") { CinemaHeading("Продолжить", Modifier.padding(Film.edge), 28) }
            items(history.take(3), key = { "continue-${it.title.id}" }) { entry ->
                CinemaWatch(entry, onOpen = { nav.open(entry.title) }, onPlay = { nav.play(PlayerRoute(entry.title, resume = true)) })
            }
            item("home-download") { HomeDownloadCard(history.first()) }
        }
        item("program") {
            Column(Modifier.padding(Film.edge), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CinemaRow("Расписание", "Выход ближайших серий", Icons.Default.CalendarMonth, { nav.push(ScheduleRoute) })
                CinemaRow("Загрузки", "Серии для просмотра без сети", Icons.Default.Download, { nav.push(DownloadsRoute) })
            }
        }
        for ((key, label) in listOf("updated" to "Новые серии", "popular" to "Популярное", "top" to "Высокий рейтинг", "newest" to "Новинки")) {
            val list = rows[key].orEmpty()
            if (list.isNotEmpty()) item("shelf-$key") {
                val query = HomeData.specs.first { it.first == key }.second.copy(source = source)
                CinemaShelf(label, list, nav) { nav.push(CatalogRoute(label, query)) }
            }
        }
    }
}

@Composable
private fun CinemaFeature(title: Title, art: String?, onOpen: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().heightIn(min = 310.dp, max = 460.dp)
            .aspectRatio(.98f).clip(RoundedCornerShape(22.dp)).clickable(onClick = onOpen),
    ) {
        CinemaArt(art, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            listOf(Color.Black.copy(alpha = .06f), Color.Transparent, Color(0xF5111511)))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(19.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CinemaLabel(title.meta, color = Film.ticket)
            Text(title.title, color = Film.paper, fontFamily = FontFamily.Serif, fontSize = 33.sp,
                lineHeight = 34.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            CinemaAction("Открыть тайтл", onOpen, icon = Icons.Default.ArrowForward, filled = true)
        }
    }
}

@Composable
private fun CinemaShelf(label: String, titles: List<Title>, nav: Nav, onAll: () -> Unit) {
    Column(Modifier.padding(bottom = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Film.edge, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            CinemaHeading(label, Modifier.weight(1f), 26)
            CinemaIcon(Icons.Default.ArrowForward, "Все — $label", onAll)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = Film.edge), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(titles.take(14), key = { it.id }) { title -> CinemaPoster(title, Modifier.width(120.dp)) { nav.open(title) } }
        }
    }
}

@Composable
private fun CinemaWatch(entry: WatchEntry, onOpen: () -> Unit, onPlay: (() -> Unit)? = null, onRemove: (() -> Unit)? = null) {
    Row(Modifier.padding(horizontal = Film.edge, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(13.dp))
        .background(Film.panel).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CinemaArt(entry.title.thumb, Modifier.size(51.dp, 68.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpen))
        Column(Modifier.weight(1f).clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.title.title, color = Film.paper, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            CinemaLabel("Серия ${entry.ep} · ${fmt(entry.pos)}")
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(5.dp)).background(Film.rule)) {
                Box(Modifier.fillMaxWidth(entry.progress.toFloat()).fillMaxHeight().background(Film.ticket))
            }
        }
        if (onPlay != null) CinemaIcon(Icons.Default.PlayArrow, "Продолжить ${entry.title.title}", onPlay)
        if (onRemove != null) CinemaIcon(Icons.Default.Close, "Убрать ${entry.title.title} из истории", onRemove)
    }
}

@Composable
internal fun CinemaCatalog(nav: Nav, route: CatalogRoute? = null) {
    val query = route?.query ?: nav.catalogQuery
    val loader = route?.loader ?: nav.catalogLoader
    var filters by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(query, loader) { loader.ensureLoaded(query) }
    if (filters && route == null) FilterSheet(query, { nav.catalogQuery = it }) { filters = false }

    Column(Modifier.fillMaxSize().background(Film.canvas)) {
        CinemaHeader(route?.title ?: "Каталог", if (route != null) nav::pop else null) {
            if (route == null) CinemaIcon(Icons.Default.Settings, "Настройки") { nav.push(SettingsRoute) }
        }
        if (route == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Film.edge, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    CinemaAction(playbackSourceLabel(query.source), { sources = true }, Modifier.fillMaxWidth(), filled = true)
                    DropdownMenu(sources, { sources = false }, containerColor = Film.panel) {
                        MobileCatalogSources.forEach { (source, label) ->
                            DropdownMenuItem(text = { Text(label, color = Film.paper) }, onClick = {
                                sources = false
                                if (query.source != source) nav.catalogQuery = CatalogQuery(source = source)
                            })
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    CinemaAction(query.sort.title, { sorting = true }, Modifier.fillMaxWidth())
                    DropdownMenu(sorting, { sorting = false }, containerColor = Film.panel) {
                        CatalogSort.entries.filterNot { query.source == "yummy" && it == CatalogSort.UPDATED }.forEach { sort ->
                            DropdownMenuItem(text = { Text(sort.title, color = Film.paper) }, onClick = {
                                sorting = false
                                nav.catalogQuery = query.copy(sort = sort)
                            })
                        }
                    }
                }
                CinemaIcon(Icons.Default.Tune, if (query.extraFilters == 0) "Фильтры" else "Фильтры, выбрано ${query.extraFilters}") {
                    filters = true
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = Film.edge, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf(1 to "Онгоинги", 2 to "Вышедшие", 3 to "Анонсы"), key = { it.first }) { (id, name) ->
                    CinemaAction(name, { nav.catalogQuery = query.copy(status = if (query.status == id) null else id) }, filled = query.status == id)
                }
                items(listOf(16 to "Сериалы", 17 to "Фильмы", 20 to "OVA", 21 to "ONA", 19 to "Спешлы"), key = { it.first }) { (id, name) ->
                    CinemaAction(name, { nav.catalogQuery = query.copy(type = if (query.type == id) null else id) }, filled = query.type == id)
                }
                if (query.status != null || query.type != null || query.extraFilters > 0) item {
                    CinemaAction("Сбросить фильтры", { nav.catalogQuery = CatalogQuery(sort = query.sort, source = query.source) })
                }
            }
        }
        if (loader.items.isEmpty()) {
            CinemaStatus(loader.error ?: if (!loader.loading && !loader.more) "В этом разделе пока пусто" else null,
                loading = loader.loading, action = loader.error?.let { { scope.launch { loader.reset(query) }; Unit } })
        } else {
            val state = loader.scroll
            val last by remember(state) { derivedStateOf { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 } }
            LaunchedEffect(last, loader.items.size) {
                if (last >= loader.items.lastIndex - 5 && loader.error == null) loader.loadMore()
            }
            LazyVerticalGrid(GridCells.Adaptive(103.dp), Modifier.fillMaxSize(), state,
                contentPadding = PaddingValues(horizontal = Film.edge, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(loader.items, key = { it.id }) { title -> CinemaPoster(title) { nav.open(title) } }
                if (loader.error != null) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    CinemaStatus(loader.error, action = { scope.launch { loader.loadMore() } })
                }
            }
        }
    }
}

@Composable
internal fun CinemaSearch(nav: Nav) {
    val focus = LocalFocusManager.current
    val query = CatalogQuery(text = nav.searchText.trim(), source = nav.searchSource)
    val cached = nav.searchResult?.takeIf { it.query == query }
    var error by remember(query) { mutableStateOf<String?>(null) }
    var retry by remember(query) { mutableIntStateOf(0) }
    LaunchedEffect(query, retry) {
        if (query.text.length < 2 || cached != null) return@LaunchedEffect
        error = null
        delay(300)
        try {
            val result = if (query.source == "animelib") {
                val primary = withTimeoutOrNull(5_000) { SmartSearch.search(query.text, useShikiCorrection = false) }
                primary?.takeIf { it.first.isNotEmpty() } ?: (Api.catalog(query.copy(source = "shiki"), 1).first to null)
            } else Api.catalog(query, 1).first to null
            nav.searchResult = SearchResult(query, result.first, result.second)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Поиск временно недоступен" }
    }

    Column(Modifier.fillMaxSize().background(Film.canvas)) {
        CinemaHeader("Найти аниме")
        Row(Modifier.padding(horizontal = Film.edge, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(13.dp))
            .background(Film.raised).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, tint = Film.ticket)
            BasicTextField(nav.searchText, { nav.searchText = it }, modifier = Modifier.weight(1f),
                singleLine = true, textStyle = TextStyle(color = Film.paper, fontSize = 16.sp), cursorBrush = SolidColor(Film.ticket),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (query.text.isNotBlank()) Store.recentSearches = (listOf(query.text) + Store.recentSearches.filterNot { it.equals(query.text, true) }).take(8)
                    focus.clearFocus()
                }), decorationBox = { field ->
                    if (nav.searchText.isEmpty()) CinemaLabel("Название тайтла")
                    field()
                })
            if (nav.searchText.isNotEmpty()) CinemaIcon(Icons.Default.Close, "Очистить") { nav.searchText = "" }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = Film.edge, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(MobileCatalogSources, key = { it.first }) { (source, name) ->
                CinemaAction(name, { nav.searchSource = source }, filled = source == query.source)
            }
        }
        val scroll = remember(nav, query) { nav.searchScrollFor(query) }
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(horizontal = Film.edge, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (query.text.length < 2) {
                item { CinemaRow("Поиск по кадру", null, Icons.Default.ImageSearch, { nav.push(TraceRoute) }) }
                item { CinemaRow("Случайный тайтл", null, Icons.Default.Casino, { nav.push(RandomRoute) }) }
                item { CinemaRow("Сезоны", null, Icons.Default.CalendarMonth, { nav.push(SeasonRoute) }) }
                if (Store.recentSearches.isNotEmpty()) item { CinemaHeading("Недавние запросы", size = 24) }
                items(Store.recentSearches) { recent -> CinemaRow(recent, null, Icons.Default.History, { nav.searchText = recent }) }
            } else if (error != null) item { CinemaStatus(error, action = { retry++ }) }
            else if (cached == null) item { CinemaStatus(null, loading = true) }
            else if (cached.titles.isEmpty()) item { CinemaStatus("Ничего не найдено") }
            else {
                if (query.source == "animelib" && cached.titles.any { SourceIds.isShiki(it.slug) }) {
                    item { CinemaLabel("Результаты из Шикимори") }
                }
                items(cached.titles, key = { it.id }) { title ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Film.panel)
                        .clickable { focus.clearFocus(); nav.open(title) }.padding(9.dp),
                        horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        CinemaArt(title.thumb, Modifier.size(53.dp, 74.dp).clip(RoundedCornerShape(8.dp)))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(title.title, color = Film.paper, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            CinemaLabel(title.meta)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = Film.secondary)
                    }
                }
            }
        }
    }
}

@Composable
internal fun CinemaLibrary(nav: Nav) {
    val context = LocalContext.current
    val version by Store.version.collectAsState()
    val revision by PlaybackHistory.revision.collectAsState()
    val history = remember(version, revision) { PlaybackHistory.continueEntries() }
    val downloads by Downloads.items.collectAsState()
    LazyColumn(Modifier.fillMaxSize().background(Film.canvas), contentPadding = PaddingValues(bottom = 18.dp)) {
        item { CinemaHeader("Моё") { CinemaIcon(Icons.Default.Settings, "Настройки") { nav.push(SettingsRoute) } } }
        item {
            Column(Modifier.padding(horizontal = Film.edge, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CinemaRow("Загрузки", if (downloads.isEmpty()) null else "${downloads.size} серий",
                    Icons.Default.Download, { nav.push(DownloadsRoute) })
                CinemaRow("История", "Все просмотры", Icons.Default.History, { nav.push(HistoryRoute) })
                CinemaRow("Коллекции", null, Icons.Default.CollectionsBookmark, { nav.push(CollectionsRoute) })
            }
        }
        if (history.isNotEmpty()) item { CinemaHeading("В процессе", Modifier.padding(Film.edge), 27) }
        items(history.take(5), key = { "library-${it.title.id}" }) { entry ->
            CinemaWatch(entry, { nav.open(entry.title) }, onRemove = { Store.removeFromHistory(entry.title.id) })
        }
        item {
            Column(Modifier.padding(Film.edge), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CinemaHeading("Закладки", size = 27)
                for (bookmark in Bookmark.entries) {
                    val count = remember(version, bookmark) { Store.titles(bookmark).size }
                    val locked = bookmark in AppLock.lockedFolders(context)
                    CinemaRow(bookmark.title, count.takeIf { it > 0 && !locked }?.let { "$it тайтлов" },
                        if (locked) Icons.Default.Lock else Icons.Default.BookmarkBorder) {
                        AppLock.openFolder(context as android.app.Activity, bookmark) { nav.push(FolderRoute(bookmark)) }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = Film.edge), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Store.libToken == null) CinemaRow("Войти в AnimeLib", null, Icons.Default.PersonAdd, { nav.push(LibLoginRoute) })
                CinemaRow("Для вас", null, Icons.Default.AutoAwesome, { nav.push(ForYouRoute) })
                CinemaRow("Достижения", null, Icons.Default.EmojiEvents, { nav.push(AchievementsRoute) })
                CinemaRow("Статистика", null, Icons.Default.BarChart, { nav.push(StatsRoute) })
                CinemaRow("Друзья", null, Icons.Default.Group, { nav.push(FriendsRoute) })
                CinemaRow("Синхронизация", null, Icons.Default.CloudSync, { nav.push(SyncRoute) })
            }
        }
    }
}

@Composable
internal fun CinemaDownloads(nav: Nav) {
    val context = LocalContext.current
    val items by Downloads.items.collectAsState()
    Column(Modifier.fillMaxSize().background(Film.canvas)) {
        CinemaHeader("Загрузки", nav::pop)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Film.edge, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { HomeNetworkSettings() }
            if (items.isEmpty()) item { CinemaStatus("Скачанные серии появятся здесь") }
            items(items.values.sortedWith(compareBy({ it.title.title }, { it.episode.index })), key = { it.key }) { item ->
                val label = when (item.state) {
                    Downloads.State.QUEUED -> "В очереди"
                    Downloads.State.RUNNING -> "Загрузка · ${(item.progress * 100).toInt()}%"
                    Downloads.State.DONE -> "${item.bytes / 1_000_000} МБ"
                    Downloads.State.FAILED -> "Ошибка · нажмите для повтора"
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Film.panel)
                    .clickable(enabled = item.state in setOf(Downloads.State.DONE, Downloads.State.FAILED)) {
                        if (item.state == Downloads.State.DONE) nav.play(PlayerRoute(item.title, item.episode.id, item.team.id))
                        else Downloads.enqueue(item.title, Downloads.episodes(item.title.id) ?: listOf(item.episode),
                            listOf(item.episode), item.team.id) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                    }.padding(9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CinemaArt(item.title.thumb, Modifier.size(49.dp, 68.dp).clip(RoundedCornerShape(8.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.title.title, color = Film.paper, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        CinemaLabel("Серия ${item.episode.number} · ${item.quality}")
                        CinemaLabel(label, color = Film.ticket)
                    }
                    CinemaIcon(Icons.Default.DeleteOutline, "Удалить загрузку ${item.episode.number}") { Downloads.remove(item.key) }
                }
            }
        }
    }
}

@Composable
internal fun CinemaSettings(nav: Nav) {
    Column(Modifier.fillMaxSize().background(Film.canvas)) {
        CinemaHeader("Настройки", nav::pop)
        LazyColumn(contentPadding = PaddingValues(Film.edge), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item { CinemaHeading("Интерфейс", size = 27) }
            item { CinemaRow("Классический", "Вернуть прежнее оформление", Icons.Default.ViewAgenda, { Looks.setInterface(Looks.Interface.CLASSIC) }) }
            item { CinemaHeading("AnixQ", Modifier.padding(top = 14.dp), 27) }
            item { CinemaRow("Все настройки", "Каталоги, аккаунты, плеер, оформление", Icons.Default.Tune, { nav.push(AdvancedSettingsRoute) }) }
            item { CinemaRow("Проверка связи", null, Icons.Default.NetworkCheck, { nav.push(DiagnosticsRoute) }) }
            item { CinemaRow("Телевизор", null, Icons.Default.SettingsRemote, { nav.push(RemoteRoute) }) }
        }
    }
}

@Composable
internal fun CinemaHistory(nav: Nav) {
    val version by Store.version.collectAsState()
    val revision by PlaybackHistory.revision.collectAsState()
    val entries = remember(version, revision) { PlaybackHistory.entries() }
    Column(Modifier.fillMaxSize().background(Film.canvas)) {
        CinemaHeader("История", nav::pop)
        LazyColumn(contentPadding = PaddingValues(bottom = Film.edge)) {
            if (entries.isEmpty()) item { CinemaStatus("История просмотра пока пуста") }
            items(entries, key = { it.title.id }) { entry ->
                CinemaWatch(entry, { nav.open(entry.title) }, onRemove = { Store.removeFromHistory(entry.title.id) })
            }
        }
    }
}

@Composable
internal fun CinemaFolder(nav: Nav, bookmark: Bookmark) {
    val version by Store.version.collectAsState()
    val titles = remember(version, bookmark) { Store.titles(bookmark) }
    Column(Modifier.fillMaxSize().background(Film.canvas)) {
        CinemaHeader(bookmark.title, nav::pop)
        if (titles.isEmpty()) CinemaStatus("Добавляйте тайтлы в эту папку с их страниц")
        LazyVerticalGrid(GridCells.Adaptive(112.dp), contentPadding = PaddingValues(Film.edge),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
            items(titles, key = { it.id }) { title -> CinemaPoster(title) { nav.open(title) } }
        }
    }
}

@Composable
internal fun CinemaSchedule(nav: Nav) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val version by Store.version.collectAsState()
    var items by remember { mutableStateOf<List<Schedule.Item>?>(null) }
    var mine by rememberSaveable { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) { items = null; items = Schedule.load() }
    val days = remember(items, mine, version) {
        items?.filter { !mine || Schedule.isMine(it.shikiId) }?.groupBy { dayLabel(it.at) }
    }
    Column(Modifier.fillMaxSize().background(Film.canvas)) {
        CinemaHeader("Расписание", nav::pop)
        Row(Modifier.padding(horizontal = Film.edge), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CinemaAction("Все серии", { mine = false }, filled = !mine)
            CinemaAction("Мои онгоинги", { mine = true }, filled = mine)
        }
        when {
            days == null -> CinemaStatus("Загружаем расписание", loading = true)
            days.isEmpty() && mine -> CinemaStatus("В расписании нет ваших онгоингов")
            days.isEmpty() -> CinemaStatus("Расписание недоступно", action = { attempt++ })
            else -> LazyColumn(contentPadding = PaddingValues(Film.edge), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                for ((day, episodes) in days) {
                    item { CinemaHeading(day, size = 25) }
                    items(episodes, key = { "${it.shikiId}-${it.episode}-${it.at}" }) { episode ->
                        val time = remember(episode.at) {
                            java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(episode.at))
                        }
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Film.panel)
                            .clickable(enabled = !opening) {
                                opening = true
                                scope.launch {
                                    try {
                                        val title = Franchise.titleFor(episode.shikiId, listOfNotNull(episode.russian, episode.name))
                                        if (title != null) nav.open(title)
                                        else Toast.makeText(context, "Этого тайтла нет на AnimeLib", Toast.LENGTH_SHORT).show()
                                    } finally {
                                        opening = false
                                    }
                                }
                            }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CinemaArt(episode.image, Modifier.size(44.dp, 59.dp).clip(RoundedCornerShape(6.dp)))
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(episode.title, color = Film.paper, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                CinemaLabel("Серия ${episode.episode}")
                            }
                            CinemaLabel(time, color = Film.ticket)
                        }
                    }
                }
            }
        }
    }
}
