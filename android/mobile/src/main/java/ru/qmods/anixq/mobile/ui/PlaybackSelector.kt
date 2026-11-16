package ru.qmods.anixq.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.qmods.anixq.tv.data.*

/** Three bounded pickers instead of a dropdown containing every source × voice combination. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSelector(options: List<TeamOption>, selectedId: Int?, onSelect: (TeamOption) -> Unit) {
    val selected = options.firstOrNull { it.team.id == selectedId } ?: options.firstOrNull() ?: return
    var picker by remember { mutableStateOf<String?>(null) }
    val sources = PlaybackChoices.sources(options)
    val balancers = PlaybackChoices.balancers(options, selected)
    val dubs = PlaybackChoices.dubs(options, selected)
    Column(Modifier.testTag("playback-selector").padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().background(Pal.cardHi, RoundedCornerShape(16.dp))) {
        PlaybackSettingRow("Источник", selected.sourceLabel, sources.size > 1, "playback-source") { picker = "source" }
        HorizontalDivider(color = Pal.muted.copy(alpha = 0.12f), modifier = Modifier.padding(horizontal = 14.dp))
        PlaybackSettingRow("Балансер", selected.balancerLabel, balancers.size > 1, "playback-balancer") { picker = "balancer" }
        HorizontalDivider(color = Pal.muted.copy(alpha = 0.12f), modifier = Modifier.padding(horizontal = 14.dp))
        PlaybackSettingRow("Озвучка", selected.dubLabel + if (selected.team.isSubtitles) " · субтитры" else "", dubs.size > 1, "playback-dub") { picker = "dub" }
    }
    val step = picker ?: return
    var query by remember(step) { mutableStateOf("") }
    val rows = when(step) {
        "source" -> sources.map { PickerRow(it.key, it.label, "Доступно: ${it.count}", it.key == selected.source) }
        "balancer" -> balancers.map { PickerRow(it.key, it.label, "Доступно: ${it.count}", it.key == selected.balancerKey) }
        else -> dubs.map { PickerRow(it.team.id.toString(), it.dubLabel, if (it.team.isSubtitles) "Субтитры" else null, it.team.id == selected.team.id) }
    }
    ModalBottomSheet(onDismissRequest = { picker = null }, containerColor = Pal.card,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(when(step) { "source" -> "Источник"; "balancer" -> "Балансер"; else -> "Озвучка" }, fontSize = 22.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (rows.size > 8) OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Найти озвучку") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                items(rows.filter { it.label.contains(query, true) }, key = { it.key }) { row ->
                    Row(Modifier.fillMaxWidth().clickable {
                        val choice = when(step) {
                            "source" -> PlaybackChoices.chooseSource(options, selected, row.key)
                            "balancer" -> PlaybackChoices.chooseBalancer(options, selected, row.key)
                            else -> dubs.firstOrNull { it.team.id.toString() == row.key }
                        }
                        picker = null; choice?.let(onSelect)
                    }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.label, color = if (row.selected) Pal.pink else Pal.text, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            row.hint?.let { Text(it, color = Pal.muted, fontSize = 12.sp) }
                        }
                        if (row.selected) Icon(Icons.Default.Check, null, tint = Pal.pink, modifier = Modifier.padding(start = 12.dp).size(20.dp))
                    }
                }
            }
        }
    }
}
private data class PickerRow(val key: String, val label: String, val hint: String?, val selected: Boolean)

@Composable
private fun PlaybackSettingRow(label: String, value: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Pal.muted, fontSize = 13.sp, modifier = Modifier.width(88.dp))
        Text(value, color = Pal.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (enabled) Icon(Icons.Default.ChevronRight, null, tint = Pal.muted, modifier = Modifier.padding(start = 8.dp).size(18.dp))
    }
}
