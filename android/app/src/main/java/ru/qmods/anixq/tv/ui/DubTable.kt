package ru.qmods.anixq.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import ru.qmods.anixq.tv.data.DubCompare
import ru.qmods.anixq.tv.data.Episode
import ru.qmods.anixq.tv.data.Title

/**
 * "Какую озвучку выбрать": a card per team — up to which episode, ad-free or Kodik, how fast its episodes come out.
 * OK picks the team for watching. [card] draws a focusable card in the current interface's style.
 */
@Composable
fun DubTable(title: Title, episodes: List<Episode>, selected: Int?, scope: String, accent: Color, muted: Color,
             card: @Composable (onClick: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) -> Unit,
             header: @Composable () -> Unit, onPick: (Int) -> Unit) {
    val rows by produceState<List<DubCompare.Row>?>(null, title.id, episodes.size) { value = runCatching { DubCompare.load(title, episodes) }.getOrDefault(emptyList()) }
    val list = rows?.takeIf { it.size > 1 } ?: return
    Column(Modifier.padding(bottom = 24.dp)) {
        header()
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(list.take(14), key = { it.team.id }) { r ->
                card({ onPick(r.team.id) }, Modifier.width(250.dp).memo(scope, "dub/${r.team.id}")) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.team.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (r.team.id == selected) Text("✓", fontSize = 16.sp, color = accent, fontWeight = FontWeight.Bold)
                        }
                        Text(if (r.team.isSubtitles) "субтитры" else "озвучка", fontSize = 11.sp, color = muted)
                        Text(if (r.latestIndex == r.total - 1) "есть все вышедшие серии" else "до серии ${r.latest}", fontSize = 13.sp,
                            color = if (r.latestIndex == r.total - 1) Color(0xFF30D158) else Color(0xFFFF9F0A), modifier = Modifier.padding(top = 8.dp))
                        Text(if (r.adFree) "без рекламы" else "плеер Kodik (реклама)", fontSize = 12.sp, color = if (r.adFree) accent else muted)
                        r.delayLabel?.let { Text("новые серии $it", fontSize = 12.sp, color = muted) }
                    }
                }
            }
        }
    }
}
