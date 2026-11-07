package ru.qmods.anixq.mobile.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import ru.qmods.anixq.tv.data.Remote
import ru.qmods.anixq.tv.data.Store

/** Remote for the TV: works from anywhere over the internet (same encrypted channel as the iPhone remote). */
@Composable
fun RemoteScreen(nav: Nav) {
    val state by Remote.tvState.collectAsState()
    val online by Remote.online.collectAsState()
    val view = LocalView.current
    rememberTicker()
    val tv = tvNow(state)
    var drag by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) { Remote.ensureConnected(); Remote.send("ping") }
    fun cmd(c: String, extra: JSONObject = JSONObject()) { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); Remote.send(c, extra) }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        TopBar(tv?.device?.let { "Пульт · $it" } ?: "Пульт ТВ", { nav.pop() }) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (online) Pal.green else Pal.gold))
            Text(if (online) " на связи" else " связь…", fontSize = 12.sp, color = Pal.muted, modifier = Modifier.padding(end = 14.dp))
        }
        if (Store.remoteChannel.isEmpty()) {
            Centered {
                Text("Включите синхронизацию: Медиатека → Настройки → «Войти через GitHub» (тот же аккаунт, что на ТВ) или «Взять вход с телевизора». Пульт найдёт ТВ сам.",
                    color = Pal.muted, textAlign = TextAlign.Center)
            }
            return@Column
        }
        if (tv == null) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Tv, null, Modifier.size(64.dp), tint = Pal.muted)
                Spacer(Modifier.height(12.dp))
                Text("На телевизоре сейчас ничего не играет", fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text("Откройте тайтл и нажмите «На ТВ» — серия включится на телевизоре, где бы вы ни были.",
                    color = Pal.muted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
            return@Column
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PosterImage(tv.title.thumb, Modifier.padding(top = 8.dp).width(170.dp), 16.dp)
            Text(tv.title.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 14.dp).clickable { nav.open(tv.title) })
            Text(listOf("Серия ${tv.ep}", tv.team).filter { it.isNotBlank() }.joinToString(" · "), color = Pal.muted, fontSize = 13.sp)
            val shown = drag?.toDouble() ?: tv.pos
            Slider(
                (shown / tv.dur.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f),
                { drag = (it * tv.dur).toFloat() },
                onValueChangeFinished = { drag?.let { cmd("seekTo", JSONObject().put("to", it.toDouble())) }; drag = null },
                modifier = Modifier.padding(top = 10.dp),
                colors = SliderDefaults.colors(thumbColor = Pal.pink, activeTrackColor = Pal.pink, inactiveTrackColor = Pal.cardHi),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(fmt(shown), fontSize = 12.sp, color = Pal.muted, modifier = Modifier.weight(1f))
                Text(if (tv.dur > 0) "-" + fmt(tv.dur - shown) else "", fontSize = 12.sp, color = Pal.muted)
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Key(Icons.Default.SkipPrevious, 52, tv.hasPrev) { cmd("prev") }
                Key(Icons.Default.Replay10, 58) { cmd("seek", JSONObject().put("by", -10)) }
                Key(if (tv.playing) Icons.Default.Pause else Icons.Default.PlayArrow, 78, accent = true) { cmd("toggle") }
                Key(Icons.Default.Forward10, 58) { cmd("seek", JSONObject().put("by", 10)) }
                Key(Icons.Default.SkipNext, 52, tv.hasNext) { cmd("next") }
            }
            if (tv.inOpening) Button(onClick = { cmd("skip") }, Modifier.padding(top = 16.dp), colors = ButtonDefaults.buttonColors(containerColor = Pal.violet)) {
                Text("Пропустить опенинг")
            }
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Key(Icons.AutoMirrored.Filled.VolumeDown, 50) { cmd("volume", JSONObject().put("dir", -1)) }
                Key(Icons.Default.Stop, 50) { cmd("stop") }
                Key(Icons.AutoMirrored.Filled.VolumeUp, 50) { cmd("volume", JSONObject().put("dir", 1)) }
            }
            OutlinedButton(
                onClick = {
                    // Handoff TV → phone: pause there, continue here from the same second.
                    if (tv.playing) cmd("pause")
                    nav.pop(); nav.play(PlayerRoute(tv.title, tv.episodeId.takeIf { it > 0 }, startAt = tv.pos))
                },
                Modifier.padding(top = 22.dp).fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            ) { Icon(Icons.Default.PhoneAndroid, null, Modifier.size(18.dp)); Text("  Досмотреть на телефоне") }
        }
    }
}

@Composable
private fun Key(icon: ImageVector, size: Int, enabled: Boolean = true, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(if (accent) Pal.pink else Pal.card).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size((size * 0.5f).dp), tint = if (enabled) Color.White else Pal.faint) }
}
