package ru.qmods.anixq.mobile.ui.cinema

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.qmods.anixq.mobile.ui.Nav
import ru.qmods.anixq.mobile.ui.Panel
import ru.qmods.anixq.mobile.ui.PlayerModel
import ru.qmods.anixq.mobile.ui.Scrubber
import ru.qmods.anixq.mobile.ui.fmt
import ru.qmods.anixq.tv.data.PlaybackLabels
import ru.qmods.anixq.tv.data.Spoilers
import ru.qmods.anixq.tv.data.Store

@Composable
internal fun CinemaControls(
    m: PlayerModel, landscape: Boolean, onPanel: (Panel) -> Unit, onMore: () -> Unit,
    onRoom: () -> Unit, roomActive: Boolean, fill: Boolean, onFill: () -> Unit,
    onFullscreen: (Boolean) -> Unit, touched: () -> Unit, onClose: () -> Unit,
    onLock: () -> Unit, onTv: () -> Unit, onPip: () -> Unit,
) {
    var scrub by remember { mutableStateOf<Double?>(null) }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to Color.Black.copy(alpha = .79f),
        .37f to Color.Transparent,
        .58f to Color.Transparent,
        1f to Color.Black.copy(alpha = .92f),
    )).then(if (landscape) Modifier.safeDrawingPadding() else Modifier).padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter), verticalAlignment = Alignment.CenterVertically) {
            CinemaTransport(Icons.Default.KeyboardArrowDown, "Свернуть", onClose)
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                if (landscape) Text(m.title.title, color = Film.paper, fontSize = 18.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                m.episode?.let { CinemaLabel("Серия ${it.number}") }
            }
            if (landscape) {
                CinemaTransport(Icons.Default.Groups, "Смотреть вместе", onRoom, roomActive)
                CinemaTransport(Icons.Default.Lock, "Заблокировать экран", onLock)
                CinemaTransport(Icons.Default.ZoomOutMap, if (fill) "Вписать" else "Заполнить экран", onFill, fill)
            }
            if (Store.remoteChannel.isNotEmpty()) CinemaTransport(Icons.Default.Tv, "Смотреть на ТВ", onTv)
            if (Build.VERSION.SDK_INT >= 26) CinemaTransport(Icons.Default.PictureInPictureAlt, "Картинка в картинке", onPip)
            CinemaTransport(Icons.Default.MoreHoriz, "Ещё", onMore, m.sleepLabel != null)
        }
        if (m.message == null && !(m.buffering && m.exo != null)) {
            Row(Modifier.align(Alignment.Center).clip(RoundedCornerShape(18.dp))
                .background(Film.canvas.copy(alpha = .88f)).padding(horizontal = 3.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CinemaTransport(Icons.Default.Replay10, "На 10 секунд назад", { m.seekBy(-10.0); touched() })
                CinemaTransport(if (m.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (m.playing) "Пауза" else "Воспроизвести", { m.toggle(); touched() }, highlighted = true)
                CinemaTransport(Icons.Default.Forward10, "На 10 секунд вперёд", { m.seekBy(10.0); touched() })
            }
        }
        Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CinemaLabel(fmt(scrub ?: m.pos), color = Film.paper)
                Scrubber(m, scrub, { scrub = it; touched() }, { m.seekFromTimeline(it); scrub = null; touched() },
                    { scrub = null }, Modifier.weight(1f).padding(horizontal = 11.dp), Film.ticket)
                CinemaLabel(if (m.dur > 0) fmt(m.dur) else "—:—", color = Film.paper)
                CinemaTransport(if (landscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    if (landscape) "Выйти из полноэкранного режима" else "Во весь экран", { onFullscreen(!landscape) })
            }
            if (landscape) {
                LazyRow(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { CinemaAction("Серии", { onPanel(Panel.EPISODES) }, icon = Icons.Default.ViewList) }
                    if (m.sourceOptions.size > 1) item { CinemaAction("Источник", { onPanel(Panel.SOURCE) }) }
                    if (m.balancerOptions.size > 1) item { CinemaAction("Балансер", { onPanel(Panel.BALANCER) }) }
                    item { CinemaAction("Озвучка", { onPanel(Panel.TEAMS) }) }
                    if (m.qualities.size > 1) item { CinemaAction("Качество", { onPanel(Panel.QUALITY) }) }
                    if (m.native) {
                        item { CinemaAction("${m.speed}×", { onPanel(Panel.SPEED) }) }
                        item { CinemaAction("Субтитры", { onPanel(Panel.SUBS) }) }
                        item { CinemaAction("Картинка и звук", { onPanel(Panel.TUNE) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CinemaTransport(icon: ImageVector, label: String, onClick: () -> Unit, highlighted: Boolean = false) {
    Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(RoundedCornerShape(12.dp))
        .background(if (highlighted) Film.ticket else Color.Transparent)
        .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(22.dp), tint = if (highlighted) Film.ink else Film.paper)
    }
}

@Composable
internal fun CinemaPlayerDetails(m: PlayerModel, nav: Nav, onPanel: (Panel) -> Unit, onRoom: () -> Unit, roomActive: Boolean) {
    val version by Store.version.collectAsState()
    LazyColumn(Modifier.fillMaxSize().background(Film.canvas),
        contentPadding = PaddingValues(Film.edge), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item("title") {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) {
                nav.minimizePlayer()
                nav.open(m.title)
            }.padding(vertical = 4.dp)) {
                CinemaHeading(m.title.title, size = 27)
                m.episode?.let { CinemaLabel(it.displayTitle, Modifier.padding(top = 5.dp)) }
                if (!m.native && m.player != null) {
                    CinemaLabel("В начале видео Kodik может быть реклама", Modifier.padding(top = 5.dp))
                }
            }
        }
        item("tracks") {
            val player = m.player
            val panels = buildList {
                if (m.sourceOptions.size > 1) add(Panel.SOURCE to player?.sourceLabel)
                if (m.balancerOptions.size > 1) add(Panel.BALANCER to player?.balancer?.let(PlaybackLabels::balancer))
                add(Panel.TEAMS to player?.team?.name?.let(PlaybackLabels::dub))
                if (m.qualities.size > 1) add(Panel.QUALITY to m.quality)
                if (m.native) {
                    add(Panel.SPEED to "${m.speed}×")
                    add(Panel.SUBS to if (m.subtitlesOn && player?.subtitles?.isNotEmpty() == true) "Вкл" else "Выкл")
                    add(Panel.TUNE to null)
                }
                add(Panel.SLEEP to m.sleepLabel)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(panels.size) { i ->
                    val (panel, value) = panels[i]
                    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Film.raised)
                        .clickable(role = Role.Button) { onPanel(panel) }.padding(horizontal = 13.dp, vertical = 10.dp)) {
                        Text(panel.title, color = Film.paper, fontSize = 14.sp)
                        if (value != null) CinemaLabel(value, color = Film.ticket)
                    }
                }
            }
        }
        if (m.native || m.hasNext) item("actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (m.native) CinemaAction(if (roomActive) "Комната открыта" else "Смотреть вместе",
                    onRoom, Modifier.weight(1f), Icons.Default.Groups)
                if (m.hasNext) CinemaAction("Следующая серия", { m.next() }, Modifier.weight(1f), Icons.Default.SkipNext, true)
            }
        }
        if (m.episodes.isNotEmpty()) item("heading") { CinemaHeading("Серии", Modifier.padding(top = 7.dp), 25) }
        itemsIndexed(m.episodes, key = { _, episode -> episode.id }) { i, episode ->
            val current = i == m.index
            val watched = remember(version, episode) { Store.isWatched(m.title.id, episode.number) }
            val progress = remember(version, episode) { Store.progress(episode.id).toFloat().coerceIn(0f, 1f) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(if (current) Film.raised else Film.panel).clickable(role = Role.Button) { if (!current) m.load(i) }
                .padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(39.dp).clip(RoundedCornerShape(7.dp))
                    .background(if (current) Film.ticket else Film.canvas), contentAlignment = Alignment.Center) {
                    Text(episode.number, color = if (current) Film.ink else Film.paper, fontSize = 14.sp)
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(if (current) episode.displayTitle else Spoilers.title(episode, watched),
                        color = Film.paper, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    when {
                        current -> CinemaLabel("Сейчас", color = Film.ticket)
                        watched -> CinemaLabel("Просмотрено")
                        progress > 0f -> Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).background(Film.rule)) {
                            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Film.ticket))
                        }
                    }
                }
                Icon(if (watched) Icons.Default.Check else Icons.Default.PlayArrow, null, Modifier.size(19.dp), tint = Film.secondary)
            }
        }
    }
}
