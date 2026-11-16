package ru.qmods.anixq.tv.ui.panorama

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.qmods.anixq.tv.data.*
import ru.qmods.anixq.tv.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PanoramaLibrary(nav: Nav) {
    val version by Store.version.collectAsState()
    var section by rememberSaveable { mutableStateOf<Bookmark?>(null) }
    val history = remember(version) { Store.continueList }
    val titles = remember(version, section) { section?.let(Store::titles).orEmpty() }
    val counts by EpisodeCounts.after.collectAsState()
    var shiki by remember { mutableStateOf(Shiki.totals) }
    LaunchedEffect(Unit) { if (Store.shikiJson != null) shiki = Shiki.loadTotals() }
    val stats = remember(version, shiki) {
        Store.stats().let { (episodes, hours, completed) -> Triple(maxOf(episodes, shiki?.episodes ?: 0), maxOf(hours, ((shiki?.minutes ?: 0.0) / 60).toInt()), maxOf(completed, shiki?.completed ?: 0)) }
    }
    LazyVerticalGrid(columns = GridCells.Adaptive(if (section == null) 320.dp else 142.dp), modifier = Modifier.fillMaxSize().testTag("panorama-library"),
        contentPadding = PaddingValues(start = 38.dp, end = 38.dp, bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "heading") {
            Column(Modifier.padding(top = 34.dp)) {
                Eyebrow("ЛИЧНОЕ ПРОСТРАНСТВО")
                Row(Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 25.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Ваша коллекция", color = P.ink, fontSize = 34.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    PClock()
                }
                Row(Modifier.fillMaxWidth().background(P.panel, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(vertical = 20.dp)) {
                    listOf("${stats.first}" to "СЕРИЙ ПРОСМОТРЕНО", "${stats.second} ч" to "ВРЕМЯ ПРОСМОТРА", "${stats.third}" to "ТАЙТЛОВ ЗАВЕРШЕНО", "${Store.bookmarks.size}" to "В КОЛЛЕКЦИИ").forEach { (value, label) ->
                        Column(Modifier.weight(1f).padding(start = 22.dp)) {
                            Text(value, color = P.accent, fontSize = 28.sp, fontWeight = FontWeight.Light)
                            Text(label, color = P.muted, fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                Row(Modifier.padding(top = 24.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    PButton("Продолжить", selected = section == null, modifier = Modifier.memo("panorama:library", "history", initial = true)) { section = null }
                    Bookmark.entries.forEach { b ->
                        val count = Store.bookmarks.values.count { it.status == b }
                        PButton("${b.title}  $count", selected = section == b) { section = b }
                    }
                }
            }
        }
        if (section == null) items(history, key = { "history/${it.title.id}" }) { e ->
            PContinue(e, counts[e.title.id], onClick = {
                if (e.progress > .92 && counts[e.title.id] == 0) nav.open(e.title)
                else nav.play(e.title, e.episodeId, e.teamId, next = e.progress > .92, resume = true)
            }, onLongClick = { nav.open(e.title) }, modifier = Modifier.memo("panorama:library", "history/${e.title.id}"))
        } else items(titles, key = { it.id }) { t -> PPoster(t, { nav.open(t) }, Modifier.memo("panorama:library", "title/${t.id}"), width = null) }
        if ((section == null && history.isEmpty()) || (section != null && titles.isEmpty())) item(span = { GridItemSpan(maxLineSpan) }) {
            PMessage(if (section == null) "Всё начинается с первой серии" else "Пока ни одной истории", "Добавляйте аниме в коллекцию на странице тайтла. История и закладки используются общие для обоих интерфейсов.", "Найти аниме") { nav.tab = Tab.CATALOG }
        }
    }
}

@Composable
fun PanoramaSchedule(nav: Nav) {
    var schedule by remember { mutableStateOf<List<Schedule.Item>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var selectedDay by rememberSaveable { mutableIntStateOf(-1) }
    var retry by remember { mutableIntStateOf(0) }
    var opening by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(retry) { schedule = try { Schedule.load(force = retry > 0) } catch(e: CancellationException) { throw e } catch(_: Exception) { emptyList() } }
    val groups = remember(schedule) { schedule.orEmpty().groupBy { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(it.at)) }.toSortedMap().values.toList() }
    LaunchedEffect(groups) {
        if (groups.isNotEmpty() && selectedDay !in groups.indices) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
            selectedDay = groups.indexOfFirst { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(it.first().at)) >= today }.coerceAtLeast(0)
        }
    }
    val day = groups.getOrNull(selectedDay) ?: groups.firstOrNull().orEmpty()
    Column(Modifier.fillMaxSize().testTag("panorama-schedule")) {
        PageHeading("На этой неделе", "Расписание новых серий · время вашего устройства") {
            PButton(if (schedule == null) "Загружаем…" else "Обновить", Icons.Rounded.Refresh,
                modifier = Modifier.memo("panorama:schedule", "refresh", initial = true)) {
                if (schedule != null) { schedule = null; retry++ }
            }
            Spacer(Modifier.width(18.dp))
            PClock()
        }
        if (groups.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(start = 38.dp, end = 38.dp, bottom = 22.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            groups.forEachIndexed { index, items ->
                val label = SimpleDateFormat("EEE, d MMM", Locale("ru")).format(Date(items.first().at)).replaceFirstChar { it.uppercase() }
                PButton(label, selected = index == selectedDay, modifier = Modifier.memo("panorama:schedule", "day/$index")) { selectedDay = index }
            }
        }
        note?.let { Text(it, color = P.accent, fontSize = 13.sp, modifier = Modifier.padding(start = 38.dp, bottom = 12.dp)) }
        if (schedule == null) PMessage("Загружаем расписание", "Проверяем ближайшие выпуски…")
        else if (groups.isEmpty()) PMessage("Пока нет расписания", "Сервис не вернул ближайшие выпуски.", "Обновить") { schedule = null; retry++ }
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 38.dp, end = 38.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(day.sortedWith(compareBy({ !Schedule.isMine(it.shikiId) }, { it.at })), key = { "${it.shikiId}/${it.episode}" }) { item ->
                PSurface(onClick = {
                    if (opening == null) {
                        opening = item.shikiId; note = "Ищем «${item.title}» в каталоге…"
                        scope.launch {
                            try {
                                val title = Franchise.titleFor(item.shikiId, listOfNotNull(item.russian, item.name))
                                note = if (title == null) "«${item.title}» пока не найдено на AnimeLib" else null
                                title?.let(nav::open)
                            } catch(e: CancellationException) { throw e } catch(e: Exception) { note = e.message }
                            finally { opening = null }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().height(94.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.at)), fontSize = 22.sp, fontWeight = FontWeight.Light,
                            modifier = Modifier.width(100.dp).padding(start = 22.dp))
                        Cover(item.asTitle().poster, Modifier.width(62.dp).fillMaxHeight())
                        Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                            Text(item.title, fontSize = 18.sp, maxLines = 1, fontWeight = FontWeight.Medium)
                            Text("Серия ${item.episode}" + if (Schedule.isMine(item.shikiId)) "  ·  В ВАШЕМ СПИСКЕ" else "", fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
                        }
                        Text(if (opening == item.shikiId) "ИЩЕМ…" else "ОТКРЫТЬ  →", fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.padding(end = 24.dp))
                    }
                }
            }
        }
    }
}
