package ru.qmods.anixq.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.qmods.anixq.mobile.data.Downloads

/** Saved episodes, grouped by title: play offline, watch progress, delete. */
@Composable
fun DownloadsScreen(nav: Nav) {
    val items by Downloads.items.collectAsState()
    val groups = items.values.groupBy { it.title.id }.values.sortedBy { it.first().title.title }
    val totalMb = items.values.sumOf { it.bytes } / 1_000_000
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar("Загрузки", { nav.pop() })
        if (items.isEmpty()) {
            EmptyState(Icons.Default.Download, "Пока ничего не скачано", "На странице тайтла нажмите ↓ рядом с «Серии» или удерживайте серию — она будет доступна без интернета")
            return@Column
        }
        Text("${items.size} ${plural(items.size, "серия", "серии", "серий")} · $totalMb МБ", color = Pal.muted, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            groups.forEach { g ->
                val t = g.first().title
                item(key = "t${t.id}") {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        PosterImage(t.thumb, Modifier.width(40.dp), 6.dp)
                        Text(t.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 12.dp).pressable { nav.open(t) })
                        IconButton(onClick = { Downloads.removeTitle(t.id) }) { Icon(Icons.Default.Delete, "Удалить все", tint = Pal.muted) }
                    }
                }
                items(g.sortedBy { it.episode.index }, key = { it.key }) { d ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.card)
                        .pressable(enabled = d.state == Downloads.State.DONE) { nav.play(PlayerRoute(d.title, d.episode.id, d.team.id)) }
                        .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                            when (d.state) {
                                Downloads.State.DONE -> Icon(Icons.Default.CheckCircle, null, tint = Pal.green)
                                Downloads.State.FAILED -> Icon(Icons.Default.ErrorOutline, null, tint = Pal.red)
                                else -> CircularProgressIndicator(progress = { d.progress }, color = Pal.pink, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("Серия ${d.episode.number}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(listOf(d.team.name, d.quality, when (d.state) {
                                Downloads.State.DONE -> "${d.bytes / 1_000_000} МБ"
                                Downloads.State.FAILED -> "ошибка — удалите и скачайте заново"
                                Downloads.State.RUNNING -> "${(d.progress * 100).toInt()}%"
                                Downloads.State.QUEUED -> "в очереди"
                            }).joinToString(" · "), fontSize = 12.sp, color = Pal.muted, maxLines = 1)
                        }
                        IconButton(onClick = { Downloads.remove(d.key) }) { Icon(Icons.Default.Delete, "Удалить", tint = Pal.faint) }
                    }
                }
            }
        }
    }
}
