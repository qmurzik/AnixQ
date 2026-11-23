package ru.qmods.anixq.tv.ui.neon

import ru.qmods.anixq.tv.ui.tvContinueEntries
import ru.qmods.anixq.tv.data.playbackSourceLabel
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.qmods.anixq.tv.data.*
import ru.qmods.anixq.tv.ui.nextTvCatalog
import ru.qmods.anixq.tv.ui.searchTvCatalog
import ru.qmods.anixq.tv.ui.tvCatalogSources
import ru.qmods.anixq.tv.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun Heading(kanji: String, title: String, sub: String?, color: Color, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 30.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Glow(kanji, color, 44.sp, Modifier.padding(end = 18.dp))
        Column(Modifier.weight(1f)) {
            Glow(title, N.ink, 32.sp, glow = .35f)
            sub?.let { Text(it, color = N.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
        }
        trailing()
        Spacer(Modifier.width(18.dp)); NClock(color)
    }
}

// MARK: - Catalog: the market street

@Composable
fun NeonCatalog(nav: Nav) {
    var query by remember { mutableStateOf(CatalogState.query) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var genres by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    val grid = rememberLazyGridState()
    fun load(reset: Boolean = false) {
        if (reset) { generation++; job?.cancel(); CatalogState.items.clear(); CatalogState.page = 0; CatalogState.more = true; error = null }
        if ((!reset && loading) || !CatalogState.more || error != null) return
        val requested = query; val number = CatalogState.page + 1; val revision = generation
        loading = true
        job = scope.launch {
            try {
                val (items, more) = Api.catalog(requested, number)
                if (revision == generation) {
                    val seen = CatalogState.items.map { it.id }.toHashSet()
                    CatalogState.items.addAll(items.filter { seen.add(it.id) }); CatalogState.page = number; CatalogState.more = more && items.isNotEmpty()
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (revision == generation) error = e.message ?: "Ошибка загрузки" }
            finally { if (revision == generation) loading = false }
        }
    }
    fun apply(v: CatalogQuery) { query = v; CatalogState.query = v; load(true); scope.launch { grid.scrollToItem(0) } }
    LaunchedEffect(Unit) { if (CatalogState.items.isEmpty()) load() }
    LaunchedEffect(query.source) { genres = try { Api.genres(query.source) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() } }
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { if (it >= grid.layoutInfo.totalItemsCount - 12 && CatalogState.items.isNotEmpty()) load() }
    }
    val statuses = listOf(null to "Любой статус", 1 to "Онгоинги", 2 to "Вышедшие", 3 to "Анонсы")
    val types = listOf(null to "Любой формат", 16 to "TV", 17 to "Фильмы", 20 to "OVA", 21 to "ONA", 19 to "Спешлы", 18 to "Короткие")
    LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize().testTag("neon-catalog"), grid, PaddingValues(start = 44.dp, end = 30.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "heading") {
            Column {
                Heading("店", "Рынок историй", "${playbackSourceLabel(query.source)} · ${CatalogState.items.size}${if (CatalogState.more) "+" else ""} тайтлов", N.cyan)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NButton(playbackSourceLabel(query.source), Icons.Rounded.SwapHoriz, N.cyan,
                        modifier = Modifier.memo("neon:catalog", "source", initial = true)) { apply(CatalogQuery(source = nextTvCatalog(query.source))) }
                    NButton(query.sort.title, Icons.Rounded.Sort, N.cyan) { dialog = "sort" }
                    NButton(statuses.firstOrNull { it.first == query.status }?.second ?: "Статус", color = N.pink, selected = query.status != null) { dialog = "status" }
                    NButton(genres.firstOrNull { it.first == query.genre }?.second ?: "Жанр", color = N.violet, selected = query.genre != null) { dialog = "genre" }
                    NButton(types.firstOrNull { it.first == query.type }?.second ?: "Формат", color = N.amber, selected = query.type != null) { dialog = "type" }
                    if (query != CatalogQuery(source = query.source)) NButton("Сбросить", Icons.Rounded.Close, N.muted) { apply(CatalogQuery(source = query.source)) }
                }
            }
        }
        items(CatalogState.items, key = { it.id }) { t -> NPoster(t, { nav.open(t) }, Modifier.memo("neon:catalog", "title/${t.id}"), width = null, color = N.cyan, onFocus = { NeonSky.tint = N.cyan }) }
        item(span = { GridItemSpan(maxLineSpan) }, key = "state") {
            when {
                error != null -> NMessage("Рынок закрыт", error!!, "Повторить") { error = null; load() }
                loading -> Glow("Загружаем…", N.cyan, 14.sp, Modifier.padding(vertical = 20.dp), glow = .5f)
                CatalogState.items.isEmpty() -> NMessage("Ничего не нашлось", "Попробуйте другой жанр или сбросьте фильтры.", "Сбросить") { apply(CatalogQuery(source = query.source)) }
            }
        }
    }
    val sorts = CatalogSort.entries.filter { query.source != "yummy" || it != CatalogSort.UPDATED }
    when (dialog) {
        "sort" -> ChoiceDialog("Порядок", sorts.map { Choice(it.title, it == query.sort) }, { dialog = null }) { dialog = null; apply(query.copy(sort = sorts[it])) }
        "status" -> ChoiceDialog("Статус", statuses.map { Choice(it.second, it.first == query.status) }, { dialog = null }) { dialog = null; apply(query.copy(status = statuses[it].first)) }
        "type" -> ChoiceDialog("Формат", types.map { Choice(it.second, it.first == query.type) }, { dialog = null }) { dialog = null; apply(query.copy(type = types[it].first)) }
        "genre" -> {
            val choices = listOf<Pair<Int?, String>>(null to "Все жанры") + genres.sortedBy { it.second }
            ChoiceDialog("Жанр", choices.map { Choice(it.second, it.first == query.genre) }, { dialog = null }) { dialog = null; apply(query.copy(genre = choices[it].first)) }
        }
    }
}

// MARK: - Search: a neon keyboard

@Composable
fun NeonSearch(nav: Nav) {
    var text by rememberSaveable { mutableStateOf("") }
    var latin by rememberSaveable { mutableStateOf(false) }
    var source by rememberSaveable(nav.defaultCatalog) { mutableStateOf(nav.defaultCatalog) }
    var results by remember { mutableStateOf<List<Title>>(emptyList()) }
    var popular by remember { mutableStateOf<List<Title>>(emptyList()) }
    var recent by remember { mutableStateOf(Store.recentSearches) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var corrected by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(SearchRequest.pending) { SearchRequest.pending?.let { text = it; SearchRequest.pending = null } }
    LaunchedEffect(source) { popular = runCatching { Api.catalog(CatalogQuery(source = source, sort = CatalogSort.POPULAR, status = 1), 1).first }.getOrDefault(emptyList()) }
    LaunchedEffect(text, source) {
        val q = text.trim(); busy = false; error = null; corrected = null
        if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(350); busy = true
        try {
            val found = searchTvCatalog(q, source)
            results = found.first; corrected = found.second
            if (results.isNotEmpty()) { recent = (listOf(q) + recent.filter { it != q }).take(8); Store.recentSearches = recent }
            if (SearchRequest.playFirst && results.isNotEmpty()) { SearchRequest.playFirst = false; nav.open(results.first()) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { results = emptyList(); error = e.message ?: "Ошибка поиска" }
        finally { busy = false }
    }
    val context = LocalContext.current
    val intent = remember { Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU").putExtra(RecognizerIntent.EXTRA_PROMPT, "Название аниме") }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) it.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { q -> text = q }
    }
    val hasVoice = remember { intent.resolveActivity(context.packageManager) != null }
    Row(Modifier.fillMaxSize().testTag("neon-search")) {
        Column(Modifier.width(360.dp).fillMaxHeight().padding(start = 44.dp, end = 16.dp, top = 30.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Glow("探", N.amber, 40.sp); Glow("  Поиск", N.ink, 30.sp, glow = .35f) }
            // The query as a lit sign with a blinking cursor.
            var blink by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) { while (true) { delay(530); blink = !blink } }
            Box(Modifier.padding(top = 16.dp).fillMaxWidth().neonOutline(N.amber, .7f, 10.dp).background(N.night, RoundedCornerShape(10.dp)).padding(14.dp)) {
                Glow((text.ifBlank { "" }) + if (blink) "▌" else " ", N.amber, 18.sp, glow = .7f)
                if (text.isBlank()) Text("    Название аниме…", color = N.muted, fontSize = 15.sp)
            }
            Row(Modifier.padding(top = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tvCatalogSources.forEach { key ->
                    NButton(playbackSourceLabel(key), color = N.amber, selected = source == key) { source = key }
                }
            }
            val keys = if (latin) listOf("abcdef", "ghijkl", "mnopqr", "stuvwx", "yz1234", "567890") else
                listOf("абвгде", "ёжзийк", "лмнопр", "стуфхц", "чшщъыь", "эюя123", "456789")
            keys.forEachIndexed { r, chars ->
                Row(Modifier.padding(bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    chars.forEachIndexed { c, ch -> NSurface({ text += ch }, Modifier.width(44.dp).height(36.dp).memo("neon:search", "key/$r/$c", initial = r == 0 && c == 0), N.amber, radius = 6.dp) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(ch.toString(), fontSize = 16.sp) }
                    } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                NSurface({ text += " " }, Modifier.width(92.dp).height(34.dp), N.amber, radius = 6.dp) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.SpaceBar, "Пробел", Modifier.size(20.dp)) } }
                NSurface({ text = text.dropLast(1) }, Modifier.width(92.dp).height(34.dp), N.amber, radius = 6.dp) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.Backspace, "Стереть", Modifier.size(18.dp)) } }
                NSurface({ latin = !latin }, Modifier.width(92.dp).height(34.dp), N.amber, radius = 6.dp) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (latin) "РУС" else "ENG", fontSize = 12.sp) } }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hasVoice) NButton("Голос", Icons.Rounded.Mic, N.amber) { runCatching { voice.launch(intent) } }
                NButton("Очистить", color = N.muted) { text = "" }
            }
        }
        val shown = if (text.trim().length < 2) popular else results
        LazyVerticalGrid(GridCells.Adaptive(136.dp), Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(start = 16.dp, end = 30.dp, top = 34.dp, bottom = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Glow(when { busy -> "Ищем…"; text.trim().length < 2 -> "Сейчас смотрят"; else -> "Найдено · ${results.size}" }, N.ink, 22.sp, glow = .3f)
                    corrected?.let { Text("По запросу «$it»", color = N.muted, fontSize = 12.sp) }
                    if (text.isBlank() && recent.isNotEmpty()) Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        recent.take(4).forEach { q -> NButton(q, color = N.amber) { text = q } }
                    }
                }
            }
            items(shown, key = { it.id }) { t -> NPoster(t, { nav.open(t) }, width = null, color = N.amber) }
            if (!busy && text.trim().length >= 2 && shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                NMessage(if (error != null) "Поиск недоступен" else "Не нашли", error ?: "Попробуйте другое название или другой каталог.")
            }
        }
    }
}

// MARK: - Library

@Composable
fun NeonLibrary(nav: Nav) {
    val version by Store.version.collectAsState()
    var section by rememberSaveable { mutableStateOf<Bookmark?>(null) }
    val history = remember(version) { tvContinueEntries() }
    val titles = remember(version, section) { section?.let(Store::titles).orEmpty() }
    val counts by EpisodeCounts.after.collectAsState()
    var shiki by remember { mutableStateOf(Shiki.totals) }
    LaunchedEffect(Unit) { if (Store.shikiJson != null) shiki = Shiki.loadTotals() }
    val stats = remember(version, shiki) {
        Store.stats().let { (e, h, c) -> Triple(maxOf(e, shiki?.episodes ?: 0), maxOf(h, ((shiki?.minutes ?: 0.0) / 60).toInt()), maxOf(c, shiki?.completed ?: 0)) }
    }
    LazyVerticalGrid(GridCells.Adaptive(if (section == null) 340.dp else 150.dp), Modifier.fillMaxSize().testTag("neon-library"),
        contentPadding = PaddingValues(start = 44.dp, end = 30.dp, bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "heading") {
            Column {
                Heading("蔵", "Моя коллекция", "Общая для телефона, ТВ и iPhone", N.violet)
                // Counters like neon digits on a shop window.
                Row(Modifier.fillMaxWidth().neonOutline(N.violet, .4f, 14.dp).background(N.panel.copy(alpha = .8f), RoundedCornerShape(14.dp)).padding(vertical = 18.dp)) {
                    listOf("${stats.first}" to "СЕРИЙ", "${stats.second} ч" to "ВРЕМЕНИ", "${stats.third}" to "ЗАВЕРШЕНО", "${Store.bookmarks.size}" to "В КОЛЛЕКЦИИ").forEachIndexed { i, (v, l) ->
                        Column(Modifier.weight(1f).padding(start = 22.dp)) {
                            Glow(v, listOf(N.pink, N.cyan, N.amber, N.violet)[i], 30.sp, weight = FontWeight.Medium)
                            Text(l, color = N.muted, fontSize = 9.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                Row(Modifier.padding(top = 20.dp, bottom = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    NButton("Продолжить", color = N.cyan, selected = section == null, modifier = Modifier.memo("neon:library", "history", initial = true)) { section = null }
                    Bookmark.entries.forEach { b -> NButton("${b.title}  ${Store.bookmarks.values.count { it.status == b }}", color = N.violet, selected = section == b) { section = b } }
                }
            }
        }
        if (section == null) items(history, key = { "h/${it.title.id}" }) { e ->
            NTicket(e, counts[e.title.id], onClick = {
                if (e.progress > .92 && counts[e.title.id] == 0) nav.open(e.title) else nav.play(e.title, e.episodeId, e.teamId, next = e.progress > .92, resume = true)
            }, onLongClick = { nav.open(e.title) }, modifier = Modifier.memo("neon:library", "h/${e.title.id}"))
        } else items(titles, key = { it.id }) { t -> NPoster(t, { nav.open(t) }, Modifier.memo("neon:library", "t/${t.id}"), width = null, color = N.violet) }
        if ((section == null && history.isEmpty()) || (section != null && titles.isEmpty())) item(span = { GridItemSpan(maxLineSpan) }) {
            NMessage(if (section == null) "Первый поезд ещё не ушёл" else "Пока пусто", "Добавляйте аниме в коллекцию на странице тайтла.", "Найти аниме") { nav.tab = Tab.CATALOG }
        }
    }
}

// MARK: - Schedule: the departures board

/** Like a station board: amber dot-matrix rows, they flip in one after another. */
@Composable
fun NeonSchedule(nav: Nav) {
    var schedule by remember { mutableStateOf<List<Schedule.Item>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var day by rememberSaveable { mutableIntStateOf(-1) }
    var retry by remember { mutableIntStateOf(0) }
    var opening by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(retry) { schedule = try { Schedule.load(force = retry > 0) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() } }
    val groups = remember(schedule) { schedule.orEmpty().groupBy { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(it.at)) }.toSortedMap().values.toList() }
    LaunchedEffect(groups) {
        if (groups.isNotEmpty() && day !in groups.indices) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
            day = groups.indexOfFirst { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(it.first().at)) >= today }.coerceAtLeast(0)
        }
    }
    val rows = (groups.getOrNull(day) ?: groups.firstOrNull().orEmpty()).sortedWith(compareBy({ !Schedule.isMine(it.shikiId) }, { it.at }))
    val board = Color(0xFFFFB000)
    val mono = FontFamily.Monospace
    Column(Modifier.fillMaxSize().padding(start = 44.dp, end = 30.dp).testTag("neon-schedule")) {
        Heading("駅", "Отправления", "Новые серии · время вашего ТВ", board) {
            NButton(if (schedule == null) "Загружаем…" else "Обновить", Icons.Rounded.Refresh, board, modifier = Modifier.memo("neon:schedule", "refresh", initial = true)) {
                if (schedule != null) { schedule = null; retry++ }
            }
        }
        if (groups.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(bottom = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            groups.forEachIndexed { i, items ->
                NButton(SimpleDateFormat("EEE, d MMM", Locale("ru")).format(Date(items.first().at)).replaceFirstChar { it.uppercase() }, color = board,
                    selected = i == day, modifier = Modifier.memo("neon:schedule", "day/$i")) { day = i }
            }
        }
        note?.let { Glow(it, N.pink, 13.sp, Modifier.padding(bottom = 10.dp), glow = .5f) }
        // Header of the board.
        Row(Modifier.fillMaxWidth().background(Color(0xFF0B0805), RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)).padding(horizontal = 18.dp, vertical = 10.dp)) {
            listOf("ВРЕМЯ" to 100.dp, "НАЗНАЧЕНИЕ" to null, "СЕРИЯ" to 90.dp, "СТАТУС" to 150.dp).forEach { (h, w) ->
                Text(h, color = board.copy(alpha = .6f), fontSize = 10.sp, letterSpacing = 2.sp, fontFamily = mono, modifier = if (w != null) Modifier.width(w) else Modifier.weight(1f))
            }
        }
        when {
            schedule == null -> NMessage("Табло обновляется", "Получаем ближайшие отправления…")
            groups.isEmpty() -> NMessage("Табло пустое", "Сервис не вернул ближайшие выпуски.", "Обновить") { schedule = null; retry++ }
            else -> LazyColumn(Modifier.fillMaxSize().background(Color(0xFF0B0805)), contentPadding = PaddingValues(bottom = 30.dp)) {
                itemsIndexed(rows, key = { _, it -> "${it.shikiId}/${it.episode}" }) { i, item ->
                    // Each row flips in after the previous one.
                    val ms = neonMillis(260)
                    val flip = remember(day) { Animatable(if (ms == 0) 1f else 0f) }
                    LaunchedEffect(day) { delay((i * 45L).coerceAtMost(600)); flip.animateTo(1f, tween(ms)) }
                    val mine = Schedule.isMine(item.shikiId)
                    val past = item.at < System.currentTimeMillis()
                    NSurface({
                        if (opening == null) {
                            opening = item.shikiId; note = "Ищем «${item.title}»…"
                            scope.launch {
                                try {
                                    val t = Franchise.titleFor(item.shikiId, listOfNotNull(item.russian, item.name))
                                    note = if (t == null) "«${item.title}» пока не найдено на AnimeLib" else null
                                    t?.let(nav::open)
                                } catch (e: CancellationException) { throw e } catch (e: Exception) { note = e.message } finally { opening = null }
                            }
                        }
                    }, Modifier.fillMaxWidth().graphicsLayer { rotationX = (1f - flip.value) * 90f; alpha = flip.value }, board, radius = 2.dp) {
                        Row(Modifier.fillMaxWidth().background(Color(0xFF0B0805)).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glow(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.at)), board, 20.sp, Modifier.width(100.dp), glow = .6f)
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Cover(item.asTitle().poster, Modifier.width(30.dp).height(42.dp))
                                Text(item.title.uppercase(), color = if (mine) board else board.copy(alpha = .78f), fontSize = 15.sp, fontFamily = mono, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
                            }
                            Text("№ ${item.episode}", color = board, fontSize = 15.sp, fontFamily = mono, modifier = Modifier.width(90.dp))
                            Text(when { opening == item.shikiId -> "ПОИСК…"; past -> "ПРИБЫЛ"; mine -> "ВАШ ПОЕЗД"; else -> "ПО РАСПИСАНИЮ" },
                                color = when { past -> Color(0xFF39FF88); mine -> N.pink; else -> board.copy(alpha = .6f) }, fontSize = 11.sp, letterSpacing = 1.sp,
                                fontFamily = mono, modifier = Modifier.width(150.dp))
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(board.copy(alpha = .12f)))
                }
            }
        }
    }
}
