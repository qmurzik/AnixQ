package ru.qmods.anixq.tv.ui.panorama

import ru.qmods.anixq.tv.data.playbackSourceLabel
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun PanoramaCatalog(nav: Nav) {
    var query by remember { mutableStateOf(CatalogState.query) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var genres by remember { mutableStateOf<List<Pair<Int,String>>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    val grid = rememberLazyGridState()
    fun load(reset: Boolean = false) {
        if (reset) { generation++; job?.cancel(); CatalogState.items.clear(); CatalogState.page = 0; CatalogState.more = true; error = null }
        if ((!reset && loading) || !CatalogState.more || error != null) return
        val requested = query
        val number = CatalogState.page + 1
        val revision = generation
        loading = true
        job = scope.launch {
            try {
                val (items, more) = Api.catalog(requested, number)
                if (revision == generation) {
                    val seen = CatalogState.items.map { it.id }.toHashSet()
                    CatalogState.items.addAll(items.filter { seen.add(it.id) })
                    CatalogState.page = number; CatalogState.more = more && items.isNotEmpty()
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if (revision == generation) error = e.message ?: "Ошибка загрузки" }
            finally { if (revision == generation) loading = false }
        }
    }
    fun apply(value: CatalogQuery) { query = value; CatalogState.query = value; load(true); scope.launch { grid.scrollToItem(0) } }
    LaunchedEffect(Unit) { if (CatalogState.items.isEmpty()) load() }
    LaunchedEffect(query.source) { genres = try { Api.genres(query.source) } catch(e: CancellationException) { throw e } catch(_: Exception) { emptyList() } }
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect {
            if (it >= grid.layoutInfo.totalItemsCount - 12 && CatalogState.items.isNotEmpty()) load()
        }
    }
    val statuses = listOf(null to "Любой статус", 1 to "Онгоинги", 2 to "Вышедшие", 3 to "Анонсы")
    val types = listOf(null to "Любой формат", 16 to "TV", 17 to "Фильмы", 20 to "OVA", 21 to "ONA", 19 to "Спешлы", 18 to "Короткие")
    LazyVerticalGrid(columns = GridCells.Adaptive(142.dp), state = grid, modifier = Modifier.fillMaxSize().testTag("panorama-catalog"),
        contentPadding = PaddingValues(start = 38.dp, end = 38.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "heading") {
            Column {
                // The grid owns horizontal insets; the heading is intentionally full bleed inside it.
                Text("КАТАЛОГ / ANIXQ", color = P.accent, letterSpacing = 2.sp, fontSize = 10.sp, modifier = Modifier.padding(top = 34.dp))
                Row(Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Найдите свою историю", color = P.ink, fontSize = 34.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    PClock()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 14.dp)) {
                    PButton(playbackSourceLabel(query.source), Icons.Rounded.SwapHoriz,
                        modifier = Modifier.memo("panorama:catalog", "source", initial = true).testTag("panorama-catalog-source")) {
                        apply(CatalogQuery(source = nextTvCatalog(query.source)))
                    }
                    PButton(query.sort.title, Icons.Rounded.Sort) { dialog = "sort" }
                    PButton(statuses.firstOrNull { it.first == query.status }?.second ?: "Статус", selected = query.status != null) { dialog = "status" }
                    PButton(genres.firstOrNull { it.first == query.genre }?.second ?: "Жанр", selected = query.genre != null) { dialog = "genre" }
                    PButton(types.firstOrNull { it.first == query.type }?.second ?: "Формат", selected = query.type != null) { dialog = "type" }
                    if (query != CatalogQuery(source = query.source)) PButton("Сбросить", Icons.Rounded.Close) { apply(CatalogQuery(source = query.source)) }
                }
                Text("${playbackSourceLabel(query.source)}  /  ${CatalogState.items.size}${if (CatalogState.more) "+" else ""} тайтлов загружено", color = P.muted, fontSize = 11.sp)
            }
        }
        items(CatalogState.items, key = { it.id }) { title -> PPoster(title, { nav.open(title) },
            Modifier.memo("panorama:catalog", "title/${title.id}"), width = null) }
        item(span = { GridItemSpan(maxLineSpan) }, key = "state") {
            when {
                error != null -> PMessage("Каталог недоступен", error!!, "Повторить") { error = null; load() }
                loading -> Text("Загружаем…", color = P.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 22.dp))
                CatalogState.items.isEmpty() -> PMessage("Ничего не нашлось", "Попробуйте другой жанр или сбросьте фильтры.", "Сбросить фильтры") { apply(CatalogQuery(source = query.source)) }
            }
        }
    }
    val sorts = CatalogSort.entries.filter { query.source != "yummy" || it != CatalogSort.UPDATED }
    when(dialog) {
        "sort" -> ChoiceDialog("Порядок тайтлов", sorts.map { Choice(it.title, it == query.sort) }, { dialog = null }) { dialog = null; apply(query.copy(sort = sorts[it])) }
        "status" -> ChoiceDialog("Статус", statuses.map { Choice(it.second, it.first == query.status) }, { dialog = null }) { dialog = null; apply(query.copy(status = statuses[it].first)) }
        "type" -> ChoiceDialog("Формат", types.map { Choice(it.second, it.first == query.type) }, { dialog = null }) { dialog = null; apply(query.copy(type = types[it].first)) }
        "genre" -> {
            val choices = listOf<Pair<Int?,String>>(null to "Все жанры") + genres.sortedBy { it.second }
            ChoiceDialog("Жанр", choices.map { Choice(it.second, it.first == query.genre) }, { dialog = null }) { dialog = null; apply(query.copy(genre = choices[it].first)) }
        }
    }
}

@Composable
fun PanoramaSearch(nav: Nav) {
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
    LaunchedEffect(source) {
        popular = emptyList()
        try { popular = Api.catalog(CatalogQuery(source = source, sort = CatalogSort.POPULAR, status = 1), 1).first }
        catch(e: CancellationException) { throw e } catch(_: Exception) { }
    }
    LaunchedEffect(text, source) {
        val q = text.trim(); busy = false; error = null; corrected = null
        if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(350); busy = true
        try {
            val found = searchTvCatalog(q, source)
            results = found.first; corrected = found.second
            if (results.isNotEmpty()) { recent = (listOf(q) + recent.filter { it != q }).take(8); Store.recentSearches = recent }
            if (SearchRequest.playFirst && results.isNotEmpty()) { SearchRequest.playFirst = false; nav.open(results.first()) }
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { results = emptyList(); error = e.message ?: "Ошибка поиска" }
        finally { busy = false }
    }
    val context = LocalContext.current
    val intent = remember { Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU").putExtra(RecognizerIntent.EXTRA_PROMPT, "Название аниме") }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) it.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { query -> text = query }
    }
    val hasVoice = remember { intent.resolveActivity(context.packageManager) != null }
    Row(Modifier.fillMaxSize().testTag("panorama-search")) {
        Column(Modifier.width(352.dp).fillMaxHeight().padding(start = 38.dp, end = 18.dp, top = 34.dp)) {
            Eyebrow("НАЙДЁТСЯ ТА САМАЯ ИСТОРИЯ")
            Text("Поиск", color = P.ink, fontSize = 34.sp, modifier = Modifier.padding(top = 7.dp, bottom = 20.dp))
            Box(Modifier.fillMaxWidth().background(P.panel, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(14.dp)) {
                Text(text.ifBlank { "Название аниме…" }, color = if (text.isBlank()) P.muted else P.ink, fontSize = 17.sp, maxLines = 1)
            }
            Row(Modifier.padding(top = 12.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tvCatalogSources.forEach { key ->
                    PButton(playbackSourceLabel(key), selected = source == key) { source = key }
                }
            }
            val keys = if (latin) listOf("abcdef", "ghijkl", "mnopqr", "stuvwx", "yz1234", "567890") else
                listOf("абвгде", "ёжзийк", "лмнопр", "стуфхц", "чшщъыь", "эюя123", "456789")
            keys.forEachIndexed { row, chars ->
                Row(Modifier.padding(bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    chars.forEachIndexed { col, ch -> PSurface({ text += ch },
                        Modifier.width(43.dp).height(37.dp).memo("panorama:search", "key/$row/$col", initial = row == 0 && col == 0), radius = 6.dp) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(ch.toString(), fontSize = 16.sp) }
                    } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                PSurface({ text += " " }, Modifier.width(91.dp).height(35.dp), radius = 6.dp) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.SpaceBar, "Пробел", Modifier.size(20.dp)) } }
                PSurface({ text = text.dropLast(1) }, Modifier.width(91.dp).height(35.dp), radius = 6.dp) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.Backspace, "Стереть", Modifier.size(18.dp)) } }
                PSurface({ latin = !latin }, Modifier.width(91.dp).height(35.dp), radius = 6.dp) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (latin) "РУС" else "ENG", fontSize = 12.sp) } }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hasVoice) PButton("Голос", Icons.Rounded.Mic) { runCatching { voice.launch(intent) } }
                PButton("Очистить") { text = "" }
            }
        }
        val shown = if (text.trim().length < 2) popular else results
        LazyVerticalGrid(columns = GridCells.Adaptive(128.dp), modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(start = 18.dp, end = 38.dp, top = 36.dp, bottom = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(when { busy -> "Ищем…"; text.trim().length < 2 -> "Сейчас смотрят"; else -> "Результаты · ${results.size}" },
                            color = P.ink, fontSize = 24.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        PClock()
                    }
                    corrected?.let { Text("По запросу «$it»", color = P.muted, fontSize = 12.sp) }
                    if (text.isBlank() && recent.isNotEmpty()) Row(Modifier.padding(top = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        recent.take(4).forEach { query -> PButton(query) { text = query } }
                    }
                    Box(Modifier.padding(top = 18.dp).fillMaxWidth().height(1.dp).background(P.line))
                }
            }
            items(shown, key = { it.id }) { title -> PPoster(title, { nav.open(title) }, width = null) }
            if (!busy && text.trim().length >= 2 && shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                PMessage(if (error != null) "Поиск недоступен" else "Не удалось найти аниме", error ?: "Попробуйте другое название или другой каталог.")
            }
        }
    }
}
