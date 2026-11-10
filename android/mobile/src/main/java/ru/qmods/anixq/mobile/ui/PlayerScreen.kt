package ru.qmods.anixq.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Groups
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Sync

/** Picture-in-picture bridge between the activity and the player screen. */
object Pip {
    var inPip by mutableStateOf(false)
    /** The open player (null when none): the activity enters PiP on Home only while it plays. */
    @Volatile var model: PlayerModel? = null
}

@Composable
fun PlayerScreen(nav: Nav, route: PlayerRoute) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val m = remember(route) { PlayerModel(context.applicationContext, scope, route.title, route) }
    var controls by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var episodesOpen by remember { mutableStateOf(false) }
    var tuneOpen by remember { mutableStateOf(false) }
    var roomOpen by remember { mutableStateOf(false) }
    val roomId by ru.qmods.anixq.tv.data.Room.roomId.collectAsState()
    var seekDrag by remember { mutableStateOf<Float?>(null) }
    var gesture by remember { mutableStateOf<Pair<ImageVector, Float>?>(null) }
    var gestureAt by remember { mutableLongStateOf(0L) }
    var tapSeek by remember { mutableStateOf(0.0) }
    var tapSeekAt by remember { mutableLongStateOf(0L) }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    var brightness by remember { mutableFloatStateOf(activity.window.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f) }
    fun touched() { lastTouch = System.currentTimeMillis() }

    DisposableEffect(m) {
        m.start()
        Pip.model = m
        ru.qmods.anixq.tv.data.Room.player = m
        val before = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val ctl = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            Pip.model = null
            if (ru.qmods.anixq.tv.data.Room.player === m) ru.qmods.anixq.tv.data.Room.player = null
            ru.qmods.anixq.mobile.PlaybackService.detach(context)
            m.release()
            ctl.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = if (before == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else before
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = -1f }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            // Leaving the app (not into picture-in-picture): pause and save, so the TV/iPhone get the exact moment.
            // "Звук в фоне" keeps the AnimeLib player going (not Kodik's web page); otherwise pause.
            if (e == Lifecycle.Event.ON_STOP) { if (!(Store.backgroundAudio && m.native)) m.pause(); m.saveProgress(force = true); Sync.flush() }
            // Back in the app: some phones bring the status / navigation bars back over the video — hide them again.
            if (e == Lifecycle.Event.ON_RESUME && !Pip.inPip) {
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(m) {
        while (true) {
            m.tick()
            if (controls && m.playing && seekDrag == null && System.currentTimeMillis() - lastTouch > 4000 && !episodesOpen) controls = false
            if (gesture != null && System.currentTimeMillis() - gestureAt > 900) gesture = null
            if (tapSeek != 0.0 && System.currentTimeMillis() - tapSeekAt > 800) tapSeek = 0.0
            delay(250)
        }
    }
    // "Next episode" starts by itself a few seconds after the ending begins (autoNext setting).
    val autoGo = m.showNext && m.countdown
    LaunchedEffect(autoGo, m.index) {
        if (autoGo) { delay(if (Store.marathon) 3000 else 6000); if (m.showNext && m.countdown) m.autoAdvance() }
    }
    // Lock-screen / headphone controls and sound with the screen off.
    LaunchedEffect(m.exo) { m.exo?.let { if (Store.backgroundAudio) ru.qmods.anixq.mobile.PlaybackService.attach(context, it.exo) } }
    BackHandler { if (episodesOpen) episodesOpen = false else if (tuneOpen) tuneOpen = false else if (roomOpen) roomOpen = false else nav.pop() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        m.exo?.let { e -> if (m.native) AndroidView(factory = { e.view }, modifier = Modifier.fillMaxSize()) }
        m.kodik?.let { k -> if (!m.native) AndroidView(factory = { k.view }, modifier = Modifier.fillMaxSize()) }
        if (Pip.inPip) return@Box

        // Gesture layer (native player only: Kodik's page has its own touch controls).
        if (m.native) BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            Box(Modifier.fillMaxSize()
                .pointerInput(m, w, h) {
                    detectTapGestures(
                        onTap = { controls = !controls; touched() },
                        onDoubleTap = { o ->
                            val d = when { o.x < w / 3 -> -10.0; o.x > w * 2 / 3 -> 10.0; else -> 0.0 }
                            if (d == 0.0) m.toggle() else {
                                tapSeek = (if (tapSeek * d > 0) tapSeek else 0.0) + d; tapSeekAt = System.currentTimeMillis(); m.seekBy(d)
                            }
                            touched()
                        },
                    )
                }
                .pointerInput(m, w, h) {
                    var left = true
                    var active = false
                    // Swipes that start at the screen edges belong to Android (notification shade, home / back
                    // gestures): never turn them into brightness / volume — same rule as the iPhone player.
                    val edgeY = maxOf(48.dp.toPx(), h * 0.12f)
                    val edgeX = 28.dp.toPx()
                    detectVerticalDragGestures(
                        onDragStart = { o ->
                            left = o.x < w / 2
                            active = o.y > edgeY && o.y < h - edgeY && o.x > edgeX && o.x < w - edgeX && activity.hasWindowFocus()
                        },
                        onDragEnd = { active = false }, onDragCancel = { active = false },
                    ) { change, dy ->
                        // The shade or the recents screen took over mid-swipe.
                        if (!active || !activity.hasWindowFocus()) { active = false; return@detectVerticalDragGestures }
                        change.consume()
                        val delta = -dy / (h * 0.8f)
                        if (left) {
                            brightness = (brightness + delta).coerceIn(0.02f, 1f)
                            activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness }
                            gesture = Icons.Default.Brightness6 to brightness
                        } else {
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
                            volume = (volume + delta * max).coerceIn(0f, max)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume.toInt(), 0)
                            gesture = Icons.AutoMirrored.Filled.VolumeUp to volume / max
                        }
                        gestureAt = System.currentTimeMillis()
                    }
                })
        }

        if (m.message != null || (m.buffering && m.native && m.exo != null)) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!m.failed) CircularProgressIndicator(color = Pal.pink)
                m.message?.let { Text(it, color = Color.White, modifier = Modifier.padding(top = 12.dp)) }
                if (m.failed) TextButton(onClick = { m.load(m.index, keepPos = m.pos) }) { Text("Повторить", color = Pal.pink) }
            }
        }
        gesture?.let { (icon, v) ->
            Row(Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Color.White)
                Box(Modifier.padding(start = 10.dp).width(120.dp)) { ProgressLine(v) }
            }
        }
        if (tapSeek != 0.0) Text(if (tapSeek > 0) "+${tapSeek.toInt()} с" else "${tapSeek.toInt()} с", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(if (tapSeek > 0) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 64.dp)
                .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(12.dp))

        AnimatedVisibility(controls || !m.native, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(110.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))))
                if (m.native) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(130.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))))
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    PlayerTopBar(m, onBack = { nav.pop() }, onEpisodes = { episodesOpen = true; touched() }, onTune = { tuneOpen = true; touched() }, onRoom = { roomOpen = true; touched() }, roomActive = roomId != null, onTv = {
                        m.sendToTv()
                        Toast.makeText(context, "Продолжаем на телевизоре с ${fmt(m.pos)}", Toast.LENGTH_SHORT).show()
                        nav.pop(); nav.push(RemoteRoute)
                    }, touched = { touched() })
                    if (m.native) {
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Round(Icons.Default.SkipPrevious, m.hasPrev, 44) { m.prev(); touched() }
                            Spacer(Modifier.width(22.dp))
                            Round(Icons.Default.FastRewind, true, 48) { m.seekBy(-10.0); touched() }
                            Spacer(Modifier.width(22.dp))
                            Round(if (m.playing) Icons.Default.Pause else Icons.Default.PlayArrow, true, 66) { m.toggle(); touched() }
                            Spacer(Modifier.width(22.dp))
                            Round(Icons.Default.FastForward, true, 48) { m.seekBy(10.0); touched() }
                            Spacer(Modifier.width(22.dp))
                            Round(Icons.Default.SkipNext, m.hasNext, 44) { m.next(); touched() }
                        }
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            val shown = seekDrag?.toDouble() ?: m.pos
                            Text(fmt(shown), color = Color.White, fontSize = 12.sp)
                            Slider(
                                value = (shown / m.dur.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f),
                                onValueChange = { seekDrag = (it * m.dur).toFloat(); touched() },
                                onValueChangeFinished = { seekDrag?.let { m.seekTo(it.toDouble()) }; seekDrag = null },
                                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                                colors = SliderDefaults.colors(thumbColor = Pal.pink, activeTrackColor = Pal.pink, inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
                            )
                            Text(if (m.dur > 0) fmt(m.dur) else "--:--", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Skip opening / next episode
        Column(Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(end = 16.dp, bottom = if (controls) 70.dp else 20.dp), horizontalAlignment = Alignment.End) {
            if (m.inRecap && !m.recapSkipped) Pill("Пропустить пересказ") { m.skipRecap() }
            if (m.inOpening && !m.openingSkipped) Pill("Пропустить опенинг") { m.skipOpening() }
            if (m.showNext) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { m.nextDismissed = true }) { Text("Скрыть", color = Color.White.copy(alpha = 0.7f)) }
                    Pill(if (m.countdown) "Следующая серия сейчас начнётся ▸" else "Следующая серия ▸") { m.next() }
                }
            }
        }
        m.toast?.let {
            Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 56.dp)
                .clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 14.dp, vertical = 8.dp))
        }
        if (episodesOpen) EpisodesPanel(m) { episodesOpen = false }
        if (tuneOpen) TunePanel(m) { tuneOpen = false }
        RoomOverlay(Modifier.fillMaxSize())
        if (roomOpen) RoomPanel { roomOpen = false }
        if (m.breakPrompt && !Pip.inPip) BreakCard(m)
    }
}

@Composable
private fun PlayerTopBar(m: PlayerModel, onBack: () -> Unit, onEpisodes: () -> Unit, onTune: () -> Unit, onRoom: () -> Unit, roomActive: Boolean,
                         onTv: () -> Unit, touched: () -> Unit) {
    var menu by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White) }
        Column(Modifier.weight(1f)) {
            Text(m.title.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(m.episode?.let { "Серия ${it.number}" }, m.player?.team?.name, m.quality, if (!m.native && m.player != null) "Kodik" else null).joinToString(" · "),
                color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (Store.remoteChannel.isNotEmpty()) IconButton(onClick = onTv) { Icon(Icons.Default.Tv, "Смотреть на ТВ", tint = Color.White) }
        IconButton(onClick = onEpisodes) { Icon(Icons.Default.FormatListNumbered, "Серии", tint = Color.White) }
        if (m.native) IconButton(onClick = onTune) { Icon(Icons.Default.Tune, "Картинка и звук", tint = Color.White) }
        IconButton(onClick = onRoom) { Icon(Icons.Default.Groups, "Смотреть вместе", tint = if (roomActive) Pal.green else Color.White) }
        val teams = m.players.distinctBy { it.team.id }
        if (teams.size > 1) Box {
            IconButton(onClick = { menu = "dub"; touched() }) { Icon(Icons.Default.RecordVoiceOver, "Озвучка", tint = Color.White) }
            DropdownMenu(menu == "dub", { menu = null }) {
                teams.forEach { p -> DropdownMenuItem({ Text(p.team.name + if (p.team.isSubtitles) " · субтитры" else "", color = if (p.team.id == m.player?.team?.id) Pal.pink else Pal.text) },
                    onClick = { menu = null; m.setTeam(p.team.id) }) }
            }
        }
        if (m.native && m.qualities.size > 1) Box {
            IconButton(onClick = { menu = "q"; touched() }) { Icon(Icons.Default.HighQuality, "Качество", tint = Color.White) }
            DropdownMenu(menu == "q", { menu = null }) {
                m.qualities.forEach { q -> DropdownMenuItem({ Text(q, color = if (q == m.quality) Pal.pink else Pal.text) }, onClick = { menu = null; m.changeQuality(q) }) }
            }
        }
        if (m.native) Box {
            IconButton(onClick = { menu = "s"; touched() }) { Icon(Icons.Default.Speed, "Скорость", tint = Color.White) }
            DropdownMenu(menu == "s", { menu = null }) {
                listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { f -> DropdownMenuItem({ Text(if (f == 1f) "Обычная" else "${f}×", color = if (f == m.speed) Pal.pink else Pal.text) },
                    onClick = { menu = null; m.changeSpeed(f) }) }
            }
        }
        if (m.native && m.player?.subtitles?.isNotEmpty() == true) IconButton(onClick = { m.toggleSubtitles(); touched() }) {
            Icon(if (m.subtitlesOn) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionDisabled, "Субтитры", tint = Color.White)
        }
    }
}

@Composable
private fun Round(icon: ImageVector, enabled: Boolean, size: Int, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f), modifier = Modifier.size((size * 0.55f).dp))
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
        modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(22.dp)).background(Pal.accent).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp))
}

@Composable
private fun EpisodesPanel(m: PlayerModel, onClose: () -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (m.index - 2).coerceAtLeast(0))
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onClose)) {
        LazyColumn(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(320.dp).background(Pal.bg.copy(alpha = 0.96f)).safeDrawingPadding().clickable(enabled = false) {},
            state = state,
        ) {
            item { Text("Серии", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(16.dp)) }
            itemsIndexed(m.episodes, key = { _, e -> e.id }) { i, ep ->
                val watched = Store.isWatched(m.title.id, ep.number)
                Row(Modifier.fillMaxWidth().clickable { onClose(); m.load(i) }.background(if (i == m.index) Pal.pink.copy(alpha = 0.18f) else Color.Transparent)
                    .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ep.number, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(44.dp), color = if (watched) Pal.muted else Pal.text)
                    Text(if (i == m.index) ep.displayTitle else ru.qmods.anixq.tv.data.Spoilers.title(ep, watched), maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (watched) Pal.muted else Pal.text, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}


/** Colour presets and sliders (live) plus the sound mode — same choices as on the iPhone. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun TunePanel(m: PlayerModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var p by remember { mutableStateOf(ru.qmods.anixq.tv.ui.ColorTuning.value) }
    var sound by remember { mutableStateOf(ru.qmods.anixq.tv.ui.SoundMode.current) }
    fun change(n: ru.qmods.anixq.tv.ui.Picture, commit: Boolean) { p = n; m.setPicture(n, commit) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)).clickable(onClick = onClose)) {
        LazyColumn(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(340.dp).background(Pal.bg.copy(alpha = 0.94f))
                .safeDrawingPadding().clickable(enabled = false) {}.padding(horizontal = 16.dp),
        ) {
            item { Text("Картинка", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)) }
            item {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ru.qmods.anixq.tv.ui.Picture.presets.size) { i ->
                        val (name, value) = ru.qmods.anixq.tv.ui.Picture.presets[i]
                        Capsule(name, selected = p == value) { change(value, true) }
                    }
                }
            }
            item { TuneSlider("Насыщенность", p.saturation, 0.5f..1.6f, 1f, { change(p.copy(saturation = it), false) }) { change(p, true) } }
            item { TuneSlider("Яркость", p.brightness, -0.15f..0.15f, 0f, { change(p.copy(brightness = it), false) }) { change(p, true) } }
            item { TuneSlider("Контраст", p.contrast, 0.8f..1.3f, 1f, { change(p.copy(contrast = it), false) }) { change(p, true) } }
            item { Text("Звук", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp)) }
            items(ru.qmods.anixq.tv.ui.SoundMode.entries.size) { i ->
                val s = ru.qmods.anixq.tv.ui.SoundMode.entries[i]
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (s == sound) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                    .clickable { sound = s; m.setSound(s) }.padding(10.dp)) {
                    Text(s.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (s == sound) Pal.pink else Pal.text)
                    Text(s.subtitle, fontSize = 12.sp, color = Pal.muted)
                }
            }
            item {
                var bg by remember { mutableStateOf(Store.backgroundAudio) }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp).clickable { bg = !bg; Store.backgroundAudio = bg }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Звук в фоне", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text("Серия играет с выключенным экраном, управление на экране блокировки", fontSize = 12.sp, color = Pal.muted)
                    }
                    androidx.compose.material3.Switch(bg, { bg = it; Store.backgroundAudio = it; m.exo?.let { e -> if (it) ru.qmods.anixq.mobile.PlaybackService.attach(context, e.exo) } },
                        colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Pal.green))
                }
            }
            item {
                var marathon by remember { mutableStateOf(Store.marathon) }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp).clickable { marathon = !marathon; Store.marathon = marathon }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Режим марафона", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text("Без опенингов и эндингов, серии подряд, перерыв каждые ${ru.qmods.anixq.tv.data.Marathon.BREAK_AFTER}", fontSize = 12.sp, color = Pal.muted)
                    }
                    androidx.compose.material3.Switch(marathon, { marathon = it; Store.marathon = it },
                        colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Pal.green))
                }
            }
            item { Box(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun TuneSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, neutral: Float, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 14.sp, modifier = Modifier.weight(1f))
            val span = maxOf(range.endInclusive - neutral, neutral - range.start)
            val pct = ((value - neutral) / span * 100).toInt()
            Text(if (pct == 0) "0" else "%+d".format(pct), fontSize = 12.sp, color = Pal.muted,
                modifier = Modifier.clickable { onChange(neutral); onDone() })
        }
        Slider(value, onChange, valueRange = range, onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(thumbColor = Pal.pink, activeTrackColor = Pal.pink, inactiveTrackColor = Color.White.copy(alpha = 0.2f)))
    }
}


@Composable
private fun BreakCard(m: PlayerModel) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp).clip(RoundedCornerShape(22.dp)).background(Pal.card).padding(22.dp).width(320.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("☕", fontSize = 34.sp)
            Text("Уже ${ru.qmods.anixq.tv.data.Marathon.BREAK_AFTER} серии подряд", fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            Text("Может, перерыв? Глазам и спине тоже нужен отдых.", fontSize = 14.sp, color = Pal.muted, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Перерыв", fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
                    .clickable { m.breakPrompt = false }.padding(horizontal = 18.dp, vertical = 11.dp))
                Text("▶ Ещё одну", fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.clip(CircleShape).background(Color.White)
                    .clickable { m.breakPrompt = false; m.next() }.padding(horizontal = 18.dp, vertical = 11.dp))
            }
        }
    }
}
