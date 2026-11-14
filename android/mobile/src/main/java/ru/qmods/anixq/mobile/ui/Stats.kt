package ru.qmods.anixq.mobile.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.qmods.anixq.tv.data.Bookmark
import ru.qmods.anixq.tv.data.Shiki
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Title
import java.util.Calendar

/** Viewing numbers from the phone's library (and Shikimori's totals when signed in) — like the iPhone's "Статистика". */
object StatsCalc {
    data class Stats(val seconds: Double, val episodes: Int, val titles: Int, val months: IntArray, val top: List<Pair<Title, Int>>, val teams: List<Pair<String, Int>>)

    private fun year(at: Double) = Calendar.getInstance().apply { timeInMillis = (at * 1000).toLong() }.get(Calendar.YEAR)

    fun compute(year: Int?): Stats {
        val months = IntArray(12)
        var seconds = 0.0; var episodes = 0
        val epTitle = Store.history.associate { it.episodeId to it.title.id }
        val perTitle = HashMap<Int, Int>()
        Store.positions.forEach { (eid, p) ->
            if (year != null && year(p.at) != year) return@forEach
            seconds += minOf(p.pos, p.dur)
            if (p.dur > 0 && p.pos / p.dur > 0.85) {
                episodes++
                months[Calendar.getInstance().apply { timeInMillis = (p.at * 1000).toLong() }.get(Calendar.MONTH)]++
                epTitle[eid]?.let { perTitle[it] = (perTitle[it] ?: 0) + 1 }
            }
        }
        if (year == null) episodes = maxOf(episodes, Store.watched.values.sumOf { it.size })
        val entries = Store.history.filter { year == null || year(it.at) == year }
        val byId = entries.associate { it.title.id to it.title }
        val top = perTitle.entries.sortedByDescending { it.value }.mapNotNull { (id, n) -> byId[id]?.let { it to n } }.take(5)
        val teams = entries.groupingBy { it.teamName }.eachCount().filterKeys { it.isNotBlank() }.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
        return Stats(seconds, episodes, entries.map { it.title.id }.toSet().size, months, top, teams)
    }

    data class Achievement(val title: String, val detail: String, val icon: ImageVector, val value: Double, val goal: Double) {
        val unlocked get() = value >= goal
        val progress get() = (value / goal).coerceIn(0.0, 1.0).toFloat()
    }

    fun achievements(): List<Achievement> {
        val finished = Store.positions.values.filter { it.dur > 0 && it.pos / it.dur > 0.85 }
        val episodes = maxOf(finished.size, Store.watched.values.sumOf { it.size }).toDouble()
        val hours = Store.positions.values.sumOf { minOf(it.pos, it.dur) } / 3600
        val completed = Store.titles(Bookmark.COMPLETED).size.toDouble()
        fun cal(at: Double) = Calendar.getInstance().apply { timeInMillis = (at * 1000).toLong() }
        val night = finished.any { cal(it.at).get(Calendar.HOUR_OF_DAY) in 2..5 }
        val perDay = finished.groupingBy { cal(it.at).let { c -> c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR) } }.eachCount().values.maxOrNull() ?: 0
        val teams = Store.history.groupingBy { it.teamName }.eachCount()
        val loyal = Store.history.size >= 10 && (teams.values.maxOrNull() ?: 0).toDouble() / Store.history.size >= 0.8
        return listOf(
            Achievement("Первая серия", "Посмотреть серию до конца", Icons.Default.PlayCircle, episodes, 1.0),
            Achievement("Сотня", "100 серий", Icons.Default.Movie, episodes, 100.0),
            Achievement("Отаку", "500 серий", Icons.Default.LocalFireDepartment, episodes, 500.0),
            Achievement("Легенда", "1000 серий", Icons.Default.WorkspacePremium, episodes, 1000.0),
            Achievement("Сутки в аниме", "24 часа просмотра", Icons.Default.Schedule, hours, 24.0),
            Achievement("Сто часов", "100 часов просмотра", Icons.Default.HourglassBottom, hours, 100.0),
            Achievement("Довожу до конца", "10 тайтлов в «Просмотрено»", Icons.Default.CheckCircle, completed, 10.0),
            Achievement("Коллекционер", "50 тайтлов в «Просмотрено»", Icons.Default.EmojiEvents, completed, 50.0),
            Achievement("Марафонец", "8 серий за один день", Icons.Default.Bolt, perDay.toDouble(), 8.0),
            Achievement("Ночная смена", "Досмотреть серию между 2 и 5 утра", Icons.Default.NightsStay, if (night) 1.0 else 0.0, 1.0),
            Achievement("Верный озвучке", "80% просмотров с одной озвучкой", Icons.Default.RecordVoiceOver, if (loyal) 1.0 else 0.0, 1.0),
            Achievement("Планировщик", "20 тайтлов в «Запланировано»", Icons.Default.CalendarMonth, Store.titles(Bookmark.PLANNED).size.toDouble(), 20.0),
        )
    }
}

private val monthNames = listOf("Я", "Ф", "М", "А", "М", "И", "И", "А", "С", "О", "Н", "Д")

@Composable
fun StatsScreen(nav: Nav) {
    val version by Store.version.collectAsState()
    val now = Calendar.getInstance().get(Calendar.YEAR)
    var year by remember { mutableIntStateOf(now) }
    var shiki by remember { mutableStateOf(Shiki.totals) }
    LaunchedEffect(Unit) { if (Store.shikiJson != null) Shiki.loadTotals()?.let { shiki = it } }
    val all = remember(version) { StatsCalc.compute(null) }
    val yearly = remember(version, year) { StatsCalc.compute(year) }
    val achievements = remember(version) { StatsCalc.achievements() }
    Column(Modifier.fillMaxSize().background(Pal.bg).statusBarsPadding()) {
        TopBar("Статистика", { nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Group {
                GroupRow("Итоги года — картинка для сторис", icon = Icons.Default.AutoAwesome, iconColor = Pal.pink, chevron = true) { nav.push(YearCardRoute) }
                GroupRow("Достижения", icon = Icons.Default.EmojiEvents, iconColor = Pal.gold, value = "${achievements.count { it.unlocked }} из ${achievements.size}",
                    chevron = true, divider = false) { nav.push(AchievementsRoute) }
            }
            val local = (Store.history.map { it.title.id } + Bookmark.entries.flatMap { Store.titles(it) }.map { it.id }).toSet().size
            Group("За всё время", if (shiki != null) "С учётом всего, что отмечено в профиле Shikimori." else if (Store.shikiJson == null) "Войдите в Shikimori — часы посчитаются по всему, что вы когда-либо смотрели." else null) {
                Row(Modifier.padding(vertical = 14.dp)) {
                    StatTile(hours(maxOf(all.seconds, (shiki?.minutes ?: 0.0) * 60)), "часов")
                    StatTile("${maxOf(all.episodes, shiki?.episodes ?: 0)}", "серий")
                    StatTile("${maxOf(local, shiki?.completed ?: 0)}", if (shiki != null) "просмотрено" else "тайтлов")
                }
            }
            Group("Итоги $year года") {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ if (year > 2015) year-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Раньше", tint = Pal.pink) }
                    Text("$year", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton({ if (year < now) year++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Позже", tint = if (year < now) Pal.pink else Pal.faint) }
                }
                Row(Modifier.padding(bottom = 10.dp)) {
                    StatTile(hours(yearly.seconds), "часов"); StatTile("${yearly.episodes}", "серий"); StatTile("${yearly.titles}", "тайтлов")
                }
                MonthChart(yearly.months)
                yearly.top.forEachIndexed { i, (t, n) ->
                    Row(Modifier.fillMaxWidth().clickable { nav.open(t) }.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", fontWeight = FontWeight.Bold, color = Pal.pink, modifier = Modifier.width(24.dp))
                        Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("$n эп.", color = Pal.muted, fontSize = 13.sp)
                    }
                }
            }
            if (all.teams.isNotEmpty()) Group("Озвучки") { Bars(all.teams) }
        }
    }
}

fun hours(seconds: Double): String = (seconds / 3600).let { if (it < 10) "%.1f".format(it).replace('.', ',') else it.toInt().toString() }

@Composable
private fun androidx.compose.foundation.layout.RowScope.StatTile(value: String, label: String) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Black)
        Text(label, fontSize = 12.sp, color = Pal.muted)
    }
}

@Composable
private fun MonthChart(months: IntArray) {
    val max = (months.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(110.dp).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
        months.forEachIndexed { i, n ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Box(Modifier.fillMaxWidth().height((70f * n / max).dp.coerceAtLeast(2.dp)).clip(RoundedCornerShape(4.dp)).background(if (n > 0) Pal.pink else Pal.cardHi))
                Text(monthNames[i], fontSize = 10.sp, color = Pal.muted)
            }
        }
    }
}

@Composable
private fun Bars(items: List<Pair<String, Int>>) {
    val max = items.maxOf { it.second }.coerceAtLeast(1)
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (name, n) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(120.dp))
                Box(Modifier.weight(1f).height(10.dp).clip(CircleShape).background(Pal.cardHi)) {
                    Box(Modifier.fillMaxWidth(n.toFloat() / max).fillMaxHeight().clip(CircleShape).background(Pal.accent))
                }
                Text(" $n", fontSize = 12.sp, color = Pal.muted)
            }
        }
    }
}

@Composable
fun AchievementsScreen(nav: Nav) {
    val list = remember { StatsCalc.achievements() }
    Column(Modifier.fillMaxSize().background(Pal.bg).statusBarsPadding()) {
        TopBar("Достижения", { nav.pop() })
        Text("Открыто ${list.count { it.unlocked }} из ${list.size}", color = Pal.muted, modifier = Modifier.padding(horizontal = 16.dp))
        LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list) { a ->
                Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Pal.card).padding(14.dp)) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(if (a.unlocked) Pal.accent else Brush.linearGradient(listOf(Pal.cardHi, Pal.cardHi))),
                        contentAlignment = Alignment.Center) { Icon(a.icon, null, tint = if (a.unlocked) Color.White else Pal.faint) }
                    Text(a.title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp), color = if (a.unlocked) Pal.text else Pal.muted)
                    Text(a.detail, fontSize = 12.sp, color = Pal.muted, minLines = 2)
                    if (!a.unlocked) ProgressLine(a.progress, Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

/** "Итоги года": a poster-like card, shared as a picture (stories). */
@Composable
fun YearCardScreen(nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val year = Calendar.getInstance().get(Calendar.YEAR)
    val s = remember { StatsCalc.compute(year) }
    val layer = rememberGraphicsLayer()
    Column(Modifier.fillMaxSize().background(Pal.bg).statusBarsPadding()) {
        TopBar("Итоги года", { nav.pop() }) {
            IconButton({
                scope.launch {
                    val bmp = layer.toImageBitmap().asAndroidBitmap()
                    saveFrame(context, bmp, "AnixQ итоги $year")?.let { uri ->
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                    }
                }
            }) { Icon(Icons.Default.Share, "Поделиться", tint = Pal.pink) }
        }
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.aspectRatio(9f / 16f).drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }
                .clip(RoundedCornerShape(24.dp)).background(Brush.verticalGradient(listOf(Color(0xFF2A0B3D), Color(0xFF0B0B1A), Color(0xFF000000))))
                .padding(24.dp)) {
                Text("AnixQ", color = Pal.pink, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text("Мой $year в аниме", color = Color.White, fontWeight = FontWeight.Black, fontSize = 30.sp, lineHeight = 34.sp, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(20.dp))
                listOf(hours(s.seconds) to "часов", "${s.episodes}" to "серий", "${s.titles}" to "тайтлов").forEach { (v, l) ->
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(v, color = Color.White, fontWeight = FontWeight.Black, fontSize = 40.sp)
                        Text("  $l", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (s.top.isNotEmpty()) Text("Больше всего смотрел(а)", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                s.top.take(3).forEachIndexed { i, (t, n) ->
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Img(t.thumb, Modifier.width(34.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(5.dp)))
                        Text("  ${i + 1}. ${t.title}", color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("$n эп.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                s.teams.firstOrNull()?.let { Text("Любимая озвучка — ${it.first}", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp) }
                Text("qmurzik.github.io/AnixQ", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
