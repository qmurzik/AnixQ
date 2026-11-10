package ru.qmods.anixq.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import ru.qmods.anixq.tv.data.Franchise
import ru.qmods.anixq.tv.data.Schedule
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** "Расписание": next episodes of this week by day; titles you follow come first in each day. */
@Composable
fun ScheduleScreen(nav: Nav) {
    var items by remember { mutableStateOf<List<Schedule.Item>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { items = Schedule.load() }
    val days = items?.groupBy { day(it.at) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = TopBarHeight, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Box(Modifier.padding(start = 56.dp)) { ScreenTitle("Расписание") } }
        note?.let { n -> item { Text(n, fontSize = 16.sp, color = C.pink, modifier = Modifier.padding(start = 56.dp)) } }
        when {
            items == null -> item { Text("Загружаем…", color = C.muted, fontSize = 18.sp, modifier = Modifier.padding(start = 56.dp)) }
            items!!.isEmpty() -> item { Text("Расписание не загрузилось", color = C.muted, fontSize = 18.sp, modifier = Modifier.padding(start = 56.dp)) }
            else -> days!!.forEach { (d, list) ->
                val sorted = list.sortedWith(compareBy({ !Schedule.isMine(it.shikiId) }, { it.at }))
                item(key = d) {
                    Shelf(d) {
                        items(sorted, key = { "${it.shikiId}-${it.episode}" }) { s ->
                            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(s.at))
                            PosterCard(s.asTitle(), badge = (if (Schedule.isMine(s.shikiId)) "★ " else "") + "$time · ${s.episode} сер.", onClick = {
                                note = "Ищем «${s.title}» на AnimeLib…"
                                scope.launch {
                                    val t = Franchise.titleFor(s.shikiId, listOfNotNull(s.russian, s.name))
                                    note = if (t == null) "«${s.title}» нет на AnimeLib" else null
                                    if (t != null) nav.open(t)
                                }
                            })
                        }
                    }
                }
            }
        }
    }
}

private fun day(at: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = at; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
    val t = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
    return when (((c.timeInMillis - t.timeInMillis) / 86_400_000L).toInt()) {
        0 -> "Сегодня"; 1 -> "Завтра"
        else -> SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(Date(at)).replaceFirstChar { it.uppercase() }
    }
}
