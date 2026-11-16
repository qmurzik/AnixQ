package ru.qmods.anixq.tv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import ru.qmods.anixq.tv.data.*

/** Each TV field has one focus target; dialogs contain only values for that field. */
@Composable
fun PlaybackSelector(options: List<TeamOption>, selectedId: Int?, onSelect: (TeamOption) -> Unit) {
    val selected = options.firstOrNull { it.team.id == selectedId } ?: options.firstOrNull() ?: return
    val sources = PlaybackChoices.sources(options)
    val balancers = PlaybackChoices.balancers(options, selected)
    val dubs = PlaybackChoices.dubs(options, selected)
    var picker by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().testTag("tv-playback-selector").padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PlaybackField("Источник", selected.sourceLabel, sources.size > 1, Modifier.weight(1f).testTag("playback-source")) { picker = "source" }
        PlaybackField("Балансер", selected.balancerLabel, balancers.size > 1, Modifier.weight(1f).testTag("playback-balancer")) { picker = "balancer" }
        PlaybackField("Озвучка", selected.dubLabel, dubs.size > 1, Modifier.weight(1.5f).testTag("playback-dub")) { picker = "dub" }
    }
    when(picker) {
        "source" -> ChoiceDialog("Источник", sources.map { Choice(it.label, it.key == selected.source, "Доступно: ${it.count}") }, { picker = null }) { i ->
            picker = null; PlaybackChoices.chooseSource(options, selected, sources[i].key)?.let(onSelect)
        }
        "balancer" -> ChoiceDialog("Балансер", balancers.map { Choice(it.label, it.key == selected.balancerKey, "Доступно: ${it.count}") }, { picker = null }) { i ->
            picker = null; PlaybackChoices.chooseBalancer(options, selected, balancers[i].key)?.let(onSelect)
        }
        "dub" -> ChoiceDialog("Озвучка", dubs.map { Choice(it.dubLabel, it.team.id == selected.team.id, if (it.team.isSubtitles) "Субтитры" else null) }, { picker = null }) { i ->
            picker = null; onSelect(dubs[i])
        }
    }
}

@Composable
private fun PlaybackField(label: String, value: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (enabled) Focusable(onClick = onClick, modifier = modifier, radius = 14.dp, scale = 1.025f, container = C.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, color = C.muted, fontSize = 12.sp)
            Text(value, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else Column(modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label, color = C.muted, fontSize = 12.sp)
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
