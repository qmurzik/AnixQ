package ru.qmods.anixq.mobile.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.qmods.anixq.tv.data.Franchise
import ru.qmods.anixq.tv.data.Schedule
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** When the next episodes come out (Shikimori calendar); yours are marked and can be shown alone. */
@Composable
fun ScheduleScreen(nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Schedule.Item>?>(null) }
    var mine by rememberSaveable { mutableStateOf(false) }
    var opening by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { items = Schedule.load() }
    val shown = items?.filter { !mine || Schedule.isMine(it.shikiId) }
    val days = shown?.groupBy { dayLabel(it.at) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar("Расписание", { nav.pop() })
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Capsule("Все", selected = !mine) { mine = false }
            Spacer(Modifier.width(8.dp))
            Capsule("Мои", selected = mine) { mine = true }
        }
        when {
            items == null -> Loading()
            shown.isNullOrEmpty() -> EmptyState(Icons.Default.CalendarMonth, if (mine) "Ваших онгоингов на этой неделе нет" else "Расписание не загрузилось",
                if (mine) "Отмечайте тайтлы «Смотрю» — их серии появятся здесь" else null)
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                days!!.forEach { (day, list) ->
                    item(key = day) { Text(day, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp)) }
                    items(list, key = { "${it.shikiId}-${it.episode}" }) { it ->
                        val my = Schedule.isMine(it.shikiId)
                        Row(Modifier.fillMaxWidth().pressable {
                            // Find the AnimeLib page for this Shikimori entry, then open it.
                            opening = it.shikiId
                            scope.launch {
                                val t = Franchise.titleFor(it.shikiId, listOfNotNull(it.russian, it.name))
                                opening = null
                                if (t != null) nav.open(t) else Toast.makeText(context, "Этого тайтла нет на AnimeLib", Toast.LENGTH_SHORT).show()
                            }
                        }.padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.at)), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                color = if (my) Pal.pink else Pal.muted, modifier = Modifier.width(52.dp))
                            PosterImage(it.image, Modifier.width(44.dp), 6.dp)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(it.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("Серия ${it.episode}" + (it.total?.takeIf { t -> t > 0 }?.let { t -> " из $t" } ?: ""), fontSize = 12.sp, color = Pal.muted)
                            }
                            if (opening == it.shikiId) CircularProgressIndicator(Modifier.size(18.dp), color = Pal.pink, strokeWidth = 2.dp)
                            else if (my) Text("Смотрю", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Pal.pink,
                                modifier = Modifier.clip(CircleShape).background(Pal.pink.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                }
            }
        }
    }
}

fun dayLabel(at: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = at }
    val today = Calendar.getInstance()
    val diff = ((c.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis -
        today.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis) / 86_400_000L).toInt()
    return when (diff) {
        0 -> "Сегодня"; 1 -> "Завтра"
        else -> SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(Date(at)).replaceFirstChar { it.uppercase() }
    }
}
