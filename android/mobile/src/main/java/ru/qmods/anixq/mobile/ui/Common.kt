package ru.qmods.anixq.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import org.json.JSONObject
import ru.qmods.anixq.tv.data.Remote
import ru.qmods.anixq.tv.data.Sync
import ru.qmods.anixq.tv.data.Title

@Composable
fun Cover(url: String?, modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(Pal.card)) {
        if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun ProgressLine(value: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(3.dp).background(Pal.accent))
    }
}

@Composable
fun PosterCard(t: Title, width: Dp = 116.dp, subtitle: String? = null, progress: Float? = null, onClick: () -> Unit) {
    Column(Modifier.width(width).clickable(onClick = onClick)) {
        Box {
            Cover(t.thumb, Modifier.fillMaxWidth().aspectRatio(0.7f))
            if (progress != null && progress > 0f) ProgressLine(progress, Modifier.align(Alignment.BottomCenter).padding(6.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(t.title, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
        val sub = subtitle ?: t.meta
        if (sub.isNotEmpty()) Text(sub, fontSize = 11.sp, color = Pal.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SectionTitle(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action, color = Pal.pink) }
    }
}

@Composable
fun TitleRow(name: String, titles: List<Title>, onOpen: (Title) -> Unit) {
    if (titles.isEmpty()) return
    SectionTitle(name)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(titles, key = { it.id }) { t -> PosterCard(t) { onOpen(t) } }
    }
}

@Composable
fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
fun Loading() = Centered { CircularProgressIndicator(color = Pal.pink) }

fun fmt(s: Double): String {
    val t = s.toInt().coerceAtLeast(0)
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

/** What the TV is doing now, from its retained state message; null when nothing plays there. */
data class TvNow(val title: Title, val ep: String, val epName: String, val episodeId: Int, val team: String,
                 val pos: Double, val dur: Double, val playing: Boolean, val device: String, val hasNext: Boolean,
                 val hasPrev: Boolean, val inOpening: Boolean, val at: Double)

fun tvNow(s: JSONObject?): TvNow? {
    if (s == null) return null
    val st = s.optString("state")
    if (st != "playing" && st != "paused") return null
    val id = s.optInt("titleId"); val slug = s.optString("slug")
    if (id <= 0 || slug.isEmpty()) return null
    val t = Title(id = id, slug = slug, rusName = s.optString("title").ifEmpty { null }, coverThumb = s.optString("cover").ifEmpty { null })
    var pos = s.optDouble("pos", 0.0)
    val at = s.optDouble("at", 0.0)
    val now = System.currentTimeMillis() / 1000.0 + Sync.serverClockOffset
    // Between the TV's updates (every 5 s) the position moves on by itself while it plays.
    if (st == "playing" && at > 0) pos += (now - at).coerceIn(0.0, 30.0)
    val dur = s.optDouble("dur", 0.0)
    return TvNow(t, s.optString("ep"), s.optString("epName"), s.optInt("episodeId"), s.optString("team"),
        if (dur > 0) pos.coerceAtMost(dur) else pos, dur, st == "playing", s.optString("device").ifEmpty { "ТВ" },
        s.optBoolean("hasNext"), s.optBoolean("hasPrev"), s.optBoolean("inOpening"), at)
}

/** Ticks once a second while shown (for positions that move between TV updates). */
@Composable
fun rememberTicker(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    return now
}

/** "Сейчас на ТВ" card: continue right here from the TV's exact moment, or open the remote. */
@Composable
fun NowOnTvCard(nav: Nav) {
    val state by Remote.tvState.collectAsState()
    rememberTicker()
    val tv = tvNow(state) ?: return
    // A stale "paused" state from yesterday isn't interesting.
    val now = System.currentTimeMillis() / 1000.0 + Sync.serverClockOffset
    if (!tv.playing && now - tv.at > 6 * 3600) return
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(Pal.pink.copy(alpha = 0.28f), Pal.violet.copy(alpha = 0.28f))))
            .clickable { nav.push(RemoteRoute) }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(tv.title.thumb, Modifier.width(54.dp).aspectRatio(0.7f), 8.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tv, null, Modifier.size(14.dp), tint = Pal.text)
                Text(" ${if (tv.playing) "Сейчас на" else "На паузе:"} ${tv.device}", fontSize = 12.sp, color = Pal.text)
            }
            Text(tv.title.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Серия ${tv.ep} · ${fmt(tv.pos)}${if (tv.dur > 0) " / " + fmt(tv.dur) else ""}", fontSize = 12.sp, color = Pal.muted)
            if (tv.dur > 0) ProgressLine((tv.pos / tv.dur).toFloat(), Modifier.padding(top = 6.dp))
        }
        Box(
            Modifier.clip(CircleShape).background(Pal.accent).clickable {
                // Handoff: pause the TV and go on from the same second here.
                if (tv.playing) Remote.send("pause")
                nav.play(PlayerRoute(tv.title, tv.episodeId.takeIf { it > 0 }, startAt = tv.pos))
            }.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp), tint = Color.White)
                Text("Здесь", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
