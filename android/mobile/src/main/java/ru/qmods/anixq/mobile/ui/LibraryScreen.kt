package ru.qmods.anixq.mobile.ui

import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.qmods.anixq.tv.data.Bookmark
import ru.qmods.anixq.tv.data.Remote
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Sync

/** "Медиатека": tiles, bookmark folders, history — like the iPhone's. */
@Composable
fun LibraryScreen(nav: Nav) {
    val version by Store.version.collectAsState()
    val online by Remote.online.collectAsState()
    val syncStatus by Sync.status.collectAsState()
    val history = remember(version) { Store.history.sortedByDescending { it.at }.take(30) }
    var shikiTotals by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(ru.qmods.anixq.tv.data.Shiki.totals) }
            androidx.compose.runtime.LaunchedEffect(Unit) { if (Store.shikiJson != null) ru.qmods.anixq.tv.data.Shiki.loadTotals()?.let { shikiTotals = it } }
            // Shikimori knows everything ever watched (not only in AnixQ): take the bigger numbers.
            val (eps, hours, titles) = remember(version, shikiTotals) { Store.stats().let { (e, h, t) ->
                val st = shikiTotals
                if (st == null) Triple(e, h, t) else Triple(maxOf(e, st.episodes), maxOf(h, (st.minutes / 60).toInt()), maxOf(t, st.completed)) } }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        LargeTitle("Медиатека") {
            IconButton(onClick = { nav.push(SettingsRoute) }) { Icon(Icons.Default.Settings, "Настройки", tint = Pal.pink) }
        }
        if (Store.libToken == null) Group {
            GroupRow("Войти в AnimeLib", "Серии 18+ и отметки на сайте", Icons.Default.PersonAdd, Pal.pink, divider = false, chevron = true) { nav.push(LibLoginRoute) }
        }
        Row(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Синхронизация", if (Sync.isOn) syncStatus else "Телефон ↔ ТВ ↔ iPhone", Icons.Default.CloudSync, listOf(Pal.blue, Pal.teal), Modifier.weight(1f)) {
                nav.push(if (Sync.isOn) SettingsRoute else SyncRoute)
            }
            Tile("Пульт ТВ", when { Store.remoteChannel.isEmpty() -> "Нужна синхронизация"; online -> "На связи"; else -> "Подключаемся…" },
                Icons.Default.SettingsRemote, listOf(Pal.pink, Pal.purple), Modifier.weight(1f)) { nav.push(if (Store.remoteChannel.isEmpty()) SyncRoute else RemoteRoute) }
        }
        Row(Modifier.padding(horizontal = 16.dp).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Статистика", "$eps ${plural(eps, "серия", "серии", "серий")} · $hours ч", Icons.Default.BarChart, listOf(Pal.orange, Pal.gold), Modifier.weight(1f)) { nav.push(StatsRoute) }
            Tile("История", "$titles ${plural(titles, "тайтл", "тайтла", "тайтлов")}", Icons.Default.History, listOf(Pal.green, Pal.teal), Modifier.weight(1f)) { nav.push(HistoryRoute) }
        }
        val dls by ru.qmods.anixq.mobile.data.Downloads.items.collectAsState()
        Row(Modifier.padding(horizontal = 16.dp).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val active = dls.values.count { it.state == ru.qmods.anixq.mobile.data.Downloads.State.RUNNING || it.state == ru.qmods.anixq.mobile.data.Downloads.State.QUEUED }
            Tile("Загрузки", when { active > 0 -> "Качается: $active"; dls.isNotEmpty() -> "${dls.size} ${plural(dls.size, "серия", "серии", "серий")} без интернета"; else -> "Смотрите офлайн" },
                Icons.Default.Download, listOf(Pal.blue, Pal.indigo), Modifier.weight(1f)) { nav.push(DownloadsRoute) }
            val cols by ru.qmods.anixq.mobile.data.Collections.all.collectAsState()
            Tile("Подборки", if (cols.isEmpty()) "Свои списки со ссылкой" else "${cols.size} · поделиться ссылкой",
                Icons.Default.CollectionsBookmark, listOf(Pal.purple, Pal.pink), Modifier.weight(1f)) { nav.push(CollectionsRoute) }
        }
        Row(Modifier.padding(horizontal = 16.dp).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Расписание", "Когда выйдут новые серии", Icons.Default.CalendarMonth, listOf(Pal.teal, Pal.blue), Modifier.weight(1f)) { nav.push(ScheduleRoute) }
            Tile("Для вас", "Советы по оценкам и продолжения", Icons.Default.AutoAwesome, listOf(Pal.pink, Pal.orange), Modifier.weight(1f)) { nav.push(ForYouRoute) }
        }
        Row(Modifier.padding(horizontal = 16.dp).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Друзья", if (Store.shikiJson != null) "Что смотрят на Shikimori" else "Нужен вход в Shikimori", Icons.Default.Group, listOf(Pal.indigo, Pal.purple), Modifier.weight(1f)) { nav.push(FriendsRoute) }
            Tile("Достижения", "${StatsCalc.achievements().count { it.unlocked }} открыто", Icons.Default.EmojiEvents, listOf(Pal.gold, Pal.green), Modifier.weight(1f)) { nav.push(AchievementsRoute) }
        }
        Group("Закладки") {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val locked = remember(version) { AppLock.lockedFolders(ctx) }
            Bookmark.entries.forEachIndexed { i, b ->
                val n = remember(version) { Store.titles(b).size }
                GroupRow(b.title, icon = if (b in locked) Icons.Default.Lock else b.icon, iconColor = b.color, value = if (b in locked) null else "$n", chevron = true,
                    divider = i < Bookmark.entries.lastIndex) { AppLock.openFolder(ctx as android.app.Activity, b) { nav.push(FolderRoute(b)) } }
            }
        }
        Group("История") {
            if (history.isEmpty()) Text("Здесь появятся тайтлы, которые вы начнёте смотреть — на телефоне, ТВ или iPhone",
                color = Pal.muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            history.forEachIndexed { i, e ->
                Column {
                    Row(Modifier.fillMaxWidth().pressable { nav.open(e.title) }.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        PosterImage(e.title.thumb, Modifier.width(46.dp), 6.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(e.title.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Серия ${e.ep}" + (if (e.teamName.isNotBlank()) " · ${e.teamName}" else ""), fontSize = 12.sp, color = Pal.muted, maxLines = 1)
                            ProgressLine(e.progress.toFloat(), Modifier.padding(top = 7.dp), 3.dp)
                        }
                        androidx.compose.material3.IconButton({ Store.removeFromHistory(e.title.id) }) {
                            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Close, "Убрать из истории", tint = Pal.faint)
                        }
                    }
                    if (i < history.lastIndex) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 72.dp), thickness = 0.5.dp, color = Pal.stroke)
                }
            }
        }
    }
}

@Composable
private fun Tile(title: String, subtitle: String, icon: ImageVector, colors: List<Color>, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Pal.card)
        .background(Brush.linearGradient(listOf(colors[0].copy(alpha = 0.16f), colors.last().copy(alpha = 0.05f))))
        .pressable(onClick = onClick).padding(14.dp)) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Brush.linearGradient(colors)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(21.dp), tint = Color.White)
        }
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(subtitle, fontSize = 12.sp, color = Pal.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun FolderScreen(nav: Nav, status: Bookmark) {
    val version by Store.version.collectAsState()
    val list = remember(version, status) { Store.titles(status) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar(status.title, { nav.pop() })
        LazyVerticalGrid(GridCells.Adaptive(105.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(list, key = { it.id }) { t -> PosterCard(t) { nav.open(t) } }
            if (list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(status.icon, "Пусто", "Добавляйте тайтлы кнопкой «+» на их странице") }
        }
    }
}

@Composable
fun HistoryScreen(nav: Nav) {
    val version by Store.version.collectAsState()
    val list = remember(version) { Store.history.sortedByDescending { it.at } }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar("История", { nav.pop() })
        LazyVerticalGrid(GridCells.Adaptive(105.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(list, key = { it.title.id }) { e -> PosterCard(e.title, subtitle = "Серия ${e.ep}", progress = e.progress.toFloat()) { nav.open(e.title) } }
            if (list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(Icons.Default.VideoLibrary, "Пока пусто") }
        }
    }
}
