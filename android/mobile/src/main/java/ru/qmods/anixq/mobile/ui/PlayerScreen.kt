package ru.qmods.anixq.mobile.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.util.Rational
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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

internal enum class Panel(val title: String) {
    EPISODES("Серии"), TEAMS("Озвучка"), QUALITY("Качество"), SPEED("Скорость"), SUBS("Субтитры"), SLEEP("Таймер сна"), TUNE("Картинка и звук")
}

/** The player is always dark (like cinema apps), whatever the app's theme. */
private val Ink = Color(0xFF0B0B0F)
private val Glass = Color.White.copy(alpha = 0.12f)
private val Dim = Color.White.copy(alpha = 0.65f)

/**
 * Phone player, built after the iPhone's V2: portrait shows the picture on top with the episode, settings and the
 * list of episodes below; landscape is the whole screen with a scrubber, chips and a side drawer; lock, PiP, gestures.
 */
@Composable
fun PlayerScreen(nav: Nav, route: PlayerRoute) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val m = remember(route) { PlayerModel(context.applicationContext, scope, route.title, route) }
    var controls by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var locked by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var moreOpen by remember { mutableStateOf(false) }
    var roomOpen by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var who by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var listen by remember { mutableStateOf(false) }
    var noteOpen by remember { mutableStateOf(false) }
    var fill by remember { mutableStateOf(false) }
    val roomId by ru.qmods.anixq.tv.data.Room.roomId.collectAsState()
    fun touched() { lastTouch = System.currentTimeMillis() }
    fun orientation(o: Int) { activity.requestedOrientation = o }

    DisposableEffect(m) {
        m.start()
        Pip.model = m
        ru.qmods.anixq.tv.data.Room.player = m
        val before = activity.requestedOrientation
        // Follows the phone's rotation (if auto-rotate is on); the fullscreen button forces landscape.
        orientation(if (Store.playerFullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_FULL_USER)
        onDispose {
            ru.qmods.anixq.mobile.NearTv.sync(context)
            Pip.model = null
            if (ru.qmods.anixq.tv.data.Room.player === m) ru.qmods.anixq.tv.data.Room.player = null
            ru.qmods.anixq.mobile.PlaybackService.detach(context)
            m.release()
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                show(WindowInsetsCompat.Type.systemBars())
                // Back to the app's theme (the player is always dark).
                isAppearanceLightStatusBars = Looks.light; isAppearanceLightNavigationBars = Looks.light
            }
            activity.requestedOrientation = if (before == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else before
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = -1f }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            // Leaving the app (not into picture-in-picture): pause and save, so the TV/iPhone get the exact moment.
            if (e == Lifecycle.Event.ON_STOP) {
                if (!(Store.backgroundAudio && m.native)) m.pause(); m.saveProgress(force = true); Sync.flush()
                ru.qmods.anixq.mobile.NearTv.sync(context)
            }
            if (e == Lifecycle.Event.ON_RESUME) {
                controls = true; touched()
                // Some phones bring the bars back over the video after returning to the app.
                if (activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && !Pip.inPip)
                    WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(m) {
        while (true) {
            m.tick()
            if (controls && m.playing && panel == null && System.currentTimeMillis() - lastTouch > 3500) controls = false
            delay(250)
        }
    }
    val autoGo = m.showNext && m.countdown
    LaunchedEffect(autoGo, m.index) {
        if (autoGo) { delay(if (Store.marathon) 3000 else 6000); if (m.showNext && m.countdown) m.autoAdvance() }
    }
    LaunchedEffect(m.exo) { m.exo?.let { if (Store.backgroundAudio) ru.qmods.anixq.mobile.PlaybackService.attach(context, it.exo) } }
    LaunchedEffect(m.exo, fill) {
        m.exo?.view?.resizeMode = if (fill) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        m.exo?.styleSubtitles(Store.subtitleScale, Store.subtitleBox)
    }
    val pickSubs = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        m.addSubtitles(uri.toString(), displayName(context, uri) ?: "Субтитры.srt")
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(if (Pip.inPip) Color.Black else Ink)) {
        val land = maxWidth > maxHeight
        BackHandler {
            when {
                panel != null -> panel = null
                locked -> { locked = false; controls = true }
                land && !Pip.inPip -> orientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT)
                else -> nav.pop()
            }
        }
        LaunchedEffect(land) {
            val ctl = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (land) ctl.hide(WindowInsetsCompat.Type.systemBars()) else { ctl.show(WindowInsetsCompat.Type.systemBars()); ctl.isAppearanceLightStatusBars = false }
            if (!land && panel != null) panel = null
        }
        val video: @Composable (Modifier) -> Unit = { mod ->
            VideoArea(m, nav, land, mod, controls, locked, listen,
                onControls = { controls = it; touched() }, touched = { touched() },
                onLock = { locked = it; controls = !it; touched() },
                onPanel = { panel = it; touched() }, onMore = { moreOpen = true },
                onRoom = { roomOpen = true }, roomActive = roomId != null, fill = fill, onFill = { fill = !fill },
                onFullscreen = { full -> orientation(if (full) ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT) },
                onListen = { listen = it })
        }
        if (Pip.inPip) { video(Modifier.fillMaxSize()); return@BoxWithConstraints }
        if (land) {
            video(Modifier.fillMaxSize())
            // Settings slide in from the right over the picture.
            AnimatedVisibility(panel != null, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(remember { MutableInteractionSource() }, null) { panel = null })
            }
            AnimatedVisibility(panel != null, Modifier.align(Alignment.CenterEnd), enter = slideInHorizontally { it }, exit = slideOutHorizontally { it }) {
                panel?.let { p ->
                    Column(Modifier.fillMaxHeight().width(360.dp).background(Color(0xF2141418)).safeDrawingPadding().padding(top = 8.dp)) {
                        Text(p.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                        PanelContent(p, m, { panel = null }, { pickSubs.launch(arrayOf("*/*")) })
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                video(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                Details(m, nav, { panel = it }, { roomOpen = true }, roomId != null)
            }
            panel?.let { p -> PanelSheet(p, m, { panel = null }, { pickSubs.launch(arrayOf("*/*")) }) }
        }
        RoomOverlay(Modifier.fillMaxSize())
        if (roomOpen) RoomPanel { roomOpen = false }
        if (m.breakPrompt) BreakCard(m)
        if (moreOpen) PlayerMoreSheet(m, nav, listen, { listen = it }, { frame = it }, { who = it }, { noteOpen = true }) { moreOpen = false }
        frame?.let { FrameSheet(m, it) { frame = null } }
        who?.let { WhoIsThisSheet(it) { who = null } }
        if (m.ratePrompt) RateSheet(m)
        if (noteOpen) m.episode?.let { ep ->
            TextDialog("Заметка к серии ${ep.number}", ru.qmods.anixq.mobile.data.Notes.get(m.title.id).episodes[ep.number] ?: "", "Что запомнилось на ${fmt(m.pos)}", { noteOpen = false }) {
                ru.qmods.anixq.mobile.data.Notes.setEpisode(m.title.id, ep.number, it); noteOpen = false
            }
        }
    }
}

// MARK: - Picture with gestures and controls

@Composable
private fun VideoArea(
    m: PlayerModel, nav: Nav, land: Boolean, modifier: Modifier, controls: Boolean, locked: Boolean, listen: Boolean,
    onControls: (Boolean) -> Unit, touched: () -> Unit, onLock: (Boolean) -> Unit, onPanel: (Panel) -> Unit, onMore: () -> Unit,
    onRoom: () -> Unit, roomActive: Boolean, fill: Boolean, onFill: () -> Unit, onFullscreen: (Boolean) -> Unit, onListen: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as Activity
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    var brightness by remember { mutableFloatStateOf(activity.window.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f) }
    var hud by remember { mutableStateOf<Triple<ImageVector, String, Float?>?>(null) }
    var hudAt by remember { mutableLongStateOf(0L) }
    var ripple by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
    var rippleAt by remember { mutableLongStateOf(0L) }
    var seekPreview by remember { mutableStateOf<Double?>(null) }
    var unlockShown by remember { mutableStateOf(false) }
    LaunchedEffect(unlockShown) { if (unlockShown) { delay(2500); unlockShown = false } }
    LaunchedEffect(hudAt) { if (hud != null) { delay(900); if (System.currentTimeMillis() - hudAt >= 850) hud = null } }
    LaunchedEffect(rippleAt) { if (ripple != null) { delay(750); if (System.currentTimeMillis() - rippleAt >= 700) ripple = null } }

    Box(modifier.background(Color.Black)) {
        m.exo?.let { e -> if (m.native) AndroidView(factory = { e.view }, modifier = Modifier.fillMaxSize()) }
        m.kodik?.let { k -> if (!m.native) AndroidView(factory = { k.view }, modifier = Modifier.fillMaxSize()) }
        if (Pip.inPip) return@Box
        if (!listen) DanmakuLayer(m, Modifier.fillMaxSize())
        if (listen) ListenOverlay(m) { onListen(false) }

        // Gestures (the AnimeLib player; Kodik's page has its own).
        if (m.native && !listen) BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
            Box(Modifier.fillMaxSize()
                .pointerInput(m, w, h, locked) {
                    detectTapGestures(
                        onTap = { if (locked) unlockShown = true else onControls(!controls) },
                        onDoubleTap = { o ->
                            if (locked) return@detectTapGestures
                            val left = o.x < w * 0.4f; val right = o.x > w * 0.6f
                            if (!left && !right) { m.toggle(); return@detectTapGestures }
                            m.seekBy(if (left) -10.0 else 10.0)
                            ripple = left to ((ripple?.takeIf { it.first == left }?.second ?: 0) + 1); rippleAt = System.currentTimeMillis()
                            touched()
                        },
                    )
                }
                .pointerInput(m, w, h, locked) {
                    var left = true; var active = false
                    // Swipes from the screen edges belong to Android (shade, home, back) — never brightness / volume.
                    val edgeY = if (land) maxOf(48.dp.toPx(), h * 0.12f) else 16.dp.toPx(); val edgeX = 28.dp.toPx()
                    detectVerticalDragGestures(
                        onDragStart = { o -> left = o.x < w / 2; active = !locked && Store.gestureLevels && o.y > edgeY && o.y < h - edgeY && o.x > edgeX && o.x < w - edgeX && activity.hasWindowFocus() },
                        onDragEnd = { active = false }, onDragCancel = { active = false },
                    ) { change, dy ->
                        if (!active || !activity.hasWindowFocus()) { active = false; return@detectVerticalDragGestures }
                        change.consume()
                        val delta = -dy / (h * 0.85f)
                        if (left) {
                            brightness = (brightness + delta).coerceIn(0.02f, 1f)
                            activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness }
                            hud = Triple(Icons.Default.Brightness6, "${(brightness * 100).toInt()}%", brightness)
                        } else {
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
                            volume = (volume + delta * max).coerceIn(0f, max)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume.toInt(), 0)
                            hud = Triple(Icons.AutoMirrored.Filled.VolumeUp, "${(volume / max * 100).toInt()}%", volume / max)
                        }
                        hudAt = System.currentTimeMillis()
                    }
                }
                .pointerInput(m, w, h, locked) {
                    var start = 0.0; var active = false
                    val edgeY = if (land) maxOf(48.dp.toPx(), h * 0.12f) else 16.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { o -> active = !locked && Store.gestureSeek && o.y > edgeY && o.y < h - edgeY && m.dur > 0; start = m.pos; if (active) seekPreview = start },
                        onDragEnd = { seekPreview?.let { if (active) m.seekTo(it) }; seekPreview = null; active = false },
                        onDragCancel = { seekPreview = null; active = false },
                    ) { change, dx ->
                        if (!active) return@detectHorizontalDragGestures
                        change.consume()
                        seekPreview = ((seekPreview ?: start) + dx / w * 90.0).coerceIn(0.0, m.dur)
                        touched()
                    }
                })
        }

        // Double-tap ripple on the side.
        ripple?.let { (left, n) ->
            Box(Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.32f)
                .clip(if (left) RoundedCornerShape(topEnd = 400.dp, bottomEnd = 400.dp) else RoundedCornerShape(topStart = 400.dp, bottomStart = 400.dp))
                .background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (left) Icons.Default.Replay10 else Icons.Default.Forward10, null, tint = Color.White, modifier = Modifier.size(34.dp))
                    Text("${n * 10} с", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        // Loading / error.
        if (m.message != null || (m.buffering && m.native && m.exo != null)) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!m.failed) CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(42.dp))
                m.message?.let { Text(it, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp)) }
                if (m.failed) TextButton({ m.load(m.index, keepPos = m.pos) }) {
                    Icon(Icons.Default.Refresh, null, tint = Pal.pink); Text(" Повторить", color = Pal.pink)
                }
            }
        }

        // HUD: brightness, volume, seek by swipe.
        val hudText = seekPreview?.let { t -> Triple(if (t >= m.pos) Icons.Default.FastForward else Icons.Default.Replay10,
            "${fmt(t)}  ${if (t >= m.pos) "+" else "−"}${fmt(kotlin.math.abs(t - m.pos))}", null) } ?: hud
        hudText?.let { (icon, text, value) ->
            Row(Modifier.align(Alignment.TopCenter).padding(top = if (land) 40.dp else 16.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
                if (value != null) Box(Modifier.padding(horizontal = 10.dp).width(110.dp).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).fillMaxHeight().background(Color.White))
                }
                Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = if (value == null) 8.dp else 0.dp))
            }
        }

        // Kodik: its own controls; we only offer close / fullscreen.
        if (!m.native) {
            Row(Modifier.align(Alignment.TopStart).then(if (land) Modifier.safeDrawingPadding() else Modifier).padding(6.dp)) {
                CircleIcon(Icons.Default.KeyboardArrowDown, "Закрыть") { if (land) onFullscreen(false) else nav.pop() }
            }
        }

        // Controls.
        AnimatedVisibility(controls && m.native && !locked && !listen, enter = fadeIn(), exit = fadeOut()) {
            Controls(m, land, onPanel, onMore, onRoom, roomActive, fill, onFill, onFullscreen, touched,
                onClose = { if (land) onFullscreen(false) else nav.pop() }, onLock = { onLock(true) },
                onTv = {
                    m.sendToTv()
                    Toast.makeText(context, "Продолжаем на телевизоре с ${fmt(m.pos)}", Toast.LENGTH_SHORT).show()
                    nav.pop(); nav.push(RemoteRoute)
                },
                onPip = {
                    if (Build.VERSION.SDK_INT >= 26) runCatching { activity.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
                })
        }

        // Skip buttons stay reachable with the controls hidden.
        if (m.native && !locked && !listen) {
            Column(Modifier.align(Alignment.BottomEnd).then(if (land) Modifier.safeDrawingPadding() else Modifier)
                .padding(end = 14.dp, bottom = if (controls) (if (land) 128.dp else 58.dp) else 16.dp), horizontalAlignment = Alignment.End) {
                when {
                    m.inRecap && !m.recapSkipped -> SkipPill("Пропустить пересказ") { m.skipRecap() }
                    m.inOpening && !m.openingSkipped -> SkipPill("Пропустить опенинг") { m.skipOpening() }
                    m.showNext -> SkipPill(if (m.countdown) "Следующая серия · сейчас" else "Серия ${m.episodes.getOrNull(m.index + 1)?.number ?: ""}", Icons.Default.SkipNext) { m.next() }
                }
            }
        }

        // Locked: only an unlock button, shown for a moment after a tap.
        if (locked) {
            AnimatedVisibility(unlockShown, Modifier.align(Alignment.CenterStart).then(if (land) Modifier.safeDrawingPadding() else Modifier).padding(16.dp), enter = fadeIn(), exit = fadeOut()) {
                Row(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).clickable { onLock(false) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LockOpen, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text("  Разблокировать", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Thin progress line while the controls are hidden (portrait).
        if (!land && !controls && m.native && m.dur > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth((m.pos / m.dur).toFloat().coerceIn(0f, 1f)).height(2.dp).background(Pal.pink))
    }
}

@Composable
internal fun Controls(
    m: PlayerModel, land: Boolean, onPanel: (Panel) -> Unit, onMore: () -> Unit, onRoom: () -> Unit, roomActive: Boolean, fill: Boolean,
    onFill: () -> Unit, onFullscreen: (Boolean) -> Unit, touched: () -> Unit, onClose: () -> Unit, onLock: () -> Unit, onTv: () -> Unit, onPip: () -> Unit,
) {
    var scrub by remember { mutableStateOf<Double?>(null) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.7f), 0.28f to Color.Black.copy(alpha = 0.12f),
            0.62f to Color.Black.copy(alpha = 0.12f), 1f to Color.Black.copy(alpha = 0.8f))))
        Column(Modifier.fillMaxSize().then(if (land) Modifier.safeDrawingPadding().padding(horizontal = 10.dp, vertical = 4.dp) else Modifier.padding(4.dp))) {
            // Top bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                BarIcon(Icons.Default.KeyboardArrowDown, "Свернуть", onClose)
                if (land) Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(m.title.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Серия ${m.episode?.number ?: ""}" + (m.player?.team?.name?.let { " · $it" } ?: ""), color = Dim, fontSize = 12.sp, maxLines = 1)
                } else Spacer(Modifier.weight(1f))
                if (land) BarIcon(Icons.Default.Groups, "Смотреть вместе", onRoom, if (roomActive) Pal.green else Color.White)
                if (Store.remoteChannel.isNotEmpty()) BarIcon(Icons.Default.Tv, "Смотреть на ТВ", onTv)
                if (Build.VERSION.SDK_INT >= 26) BarIcon(Icons.Default.PictureInPictureAlt, "Картинка в картинке", onPip)
                if (land) BarIcon(Icons.Default.Lock, "Заблокировать экран", onLock)
                BarIcon(Icons.Default.MoreHoriz, "Ещё", onMore, if (m.sleepLabel != null) Pal.pink else Color.White)
            }
            Spacer(Modifier.weight(1f))
            // Bottom
            if (land) Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(fmt(scrub ?: m.pos), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Scrubber(m, scrub, { scrub = it; touched() }, { m.seekTo(it); scrub = null }, Modifier.weight(1f).padding(horizontal = 12.dp))
                Text("−" + fmt((m.dur - (scrub ?: m.pos)).coerceAtLeast(0.0)), color = Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            } else Scrubber(m, scrub, { scrub = it; touched() }, { m.seekTo(it); scrub = null }, Modifier.fillMaxWidth().padding(horizontal = 10.dp))
            Row(Modifier.fillMaxWidth().padding(top = if (land) 8.dp else 0.dp, bottom = if (land) 4.dp else 0.dp, start = 6.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (land) {
                    LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { Chip("Серии", Icons.Default.ViewList) { onPanel(Panel.EPISODES) } }
                        if (m.players.distinctBy { it.team.id }.size > 1) item { Chip(m.player?.team?.name ?: "Озвучка", Icons.Default.GraphicEq) { onPanel(Panel.TEAMS) } }
                        item { Chip(if (m.speed == 1f) "1×" else "${m.speed}×", Icons.Default.Speed) { onPanel(Panel.SPEED) } }
                        if (m.qualities.size > 1) item { Chip(m.quality ?: "Качество", Icons.Default.HighQuality) { onPanel(Panel.QUALITY) } }
                        item { Chip(if (m.subtitlesOn && m.player?.subtitles?.isNotEmpty() == true) "Субтитры ✓" else "Субтитры", Icons.Default.ClosedCaption) { onPanel(Panel.SUBS) } }
                        item { Chip("Картинка", Icons.Default.Tune) { onPanel(Panel.TUNE) } }
                    }
                    BarIcon(Icons.Default.ZoomOutMap, if (fill) "Вписать" else "Заполнить экран", onFill, if (fill) Pal.pink else Color.White)
                    BarIcon(Icons.Default.FullscreenExit, "Выйти из полноэкранного", { onFullscreen(false) })
                } else {
                    Text("${fmt(scrub ?: m.pos)} / ${if (m.dur > 0) fmt(m.dur) else "--:--"}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    BarIcon(Icons.Default.Fullscreen, "Во весь экран", { onFullscreen(true) })
                }
            }
        }
        // Centre: back 10 / play / forward 10 (previous / next at the very start / end).
        if (m.message == null && !(m.buffering && m.exo != null)) Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(if (land) 56.dp else 36.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val prev = m.hasPrev && m.pos < 5; val next = m.hasNext && m.dur > 0 && m.dur - m.pos < 120
            RoundButton(if (prev) Icons.Default.SkipPrevious else Icons.Default.Replay10, if (land) 56 else 44) { if (prev) m.prev() else m.seekBy(-10.0); touched() }
            RoundButton(if (m.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (land) 76 else 58, big = true) { m.toggle(); touched() }
            RoundButton(if (next) Icons.Default.SkipNext else Icons.Default.Forward10, if (land) 56 else 44) { if (next) m.next() else m.seekBy(10.0); touched() }
        }
    }
}

/** Progress, what's buffered, the opening (marked) and comment ticks; drag or tap to seek with a time bubble. */
@Composable
private fun Scrubber(m: PlayerModel, scrub: Double?, onScrub: (Double) -> Unit, onDone: (Double) -> Unit, modifier: Modifier) {
    val dur = m.dur.coerceAtLeast(1.0)
    val shown = scrub ?: m.pos
    val buffered = (m.exo?.exo?.bufferedPosition ?: 0L) / 1000.0
    val op = m.opening
    val accent = Pal.pink
    BoxWithConstraints(modifier.height(28.dp)) {
        val wPx = with(LocalDensity.current) { maxWidth.toPx() }
        fun at(x: Float) = (x / wPx).coerceIn(0f, 1f) * dur
        Canvas(Modifier.fillMaxSize()
            .pointerInput(dur) { detectTapGestures { o -> onDone(at(o.x)) } }
            .pointerInput(dur) {
                var last = 0.0
                detectHorizontalDragGestures(onDragStart = { o -> last = at(o.x); onScrub(last) }, onDragEnd = { onDone(last) }, onDragCancel = { onDone(last) }) { c, _ ->
                    c.consume(); last = at(c.position.x); onScrub(last)
                }
            }) {
            val h = if (scrub != null) 6.dp.toPx() else 4.dp.toPx()
            val y = size.height / 2 - h / 2
            val r = CornerRadius(h / 2)
            drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, y), Size(size.width, h), r)
            drawRoundRect(Color.White.copy(alpha = 0.38f), Offset(0f, y), Size(size.width * (buffered / dur).toFloat().coerceIn(0f, 1f), h), r)
            op?.let { drawRect(Color(0xFFFFD60A).copy(alpha = 0.55f), Offset(size.width * (it.from / dur).toFloat(), y), Size(size.width * ((it.to - it.from) / dur).toFloat(), h)) }
            drawRoundRect(accent, Offset(0f, y), Size(size.width * (shown / dur).toFloat().coerceIn(0f, 1f), h), r)
            if (Store.danmaku) m.timed.forEach { c -> drawCircle(Color.White.copy(alpha = 0.7f), 1.6.dp.toPx(), Offset(size.width * (c.time / dur).toFloat(), y - 4.dp.toPx())) }
            drawCircle(Color.White, if (scrub != null) 9.dp.toPx() else 6.5.dp.toPx(), Offset(size.width * (shown / dur).toFloat().coerceIn(0f, 1f), size.height / 2))
        }
        if (scrub != null) {
            val x = (wPx * (scrub / dur)).toFloat()
            Text(fmt(scrub), color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.offset { IntOffset((x - 26.dp.toPx()).toInt().coerceIn(0, (wPx - 52.dp.toPx()).toInt().coerceAtLeast(0)), (-26).dp.roundToPx()) }
                    .clip(RoundedCornerShape(8.dp)).background(Color.White).padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }
}

// MARK: - Portrait: under the picture

@Composable
internal fun Details(m: PlayerModel, nav: Nav, onPanel: (Panel) -> Unit, onRoom: () -> Unit, roomActive: Boolean) {
    val list = rememberLazyListState()
    val version by Store.version.collectAsState()
    LaunchedEffect(m.index, m.episodes.size) { if (m.episodes.isNotEmpty()) list.animateScrollToItem((m.index + 4).coerceAtMost(m.episodes.size + 3)) }
    LazyColumn(Modifier.fillMaxSize(), list, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text(m.title.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { nav.pop(); nav.open(m.title) })
                val ep = m.episode
                Text(listOfNotNull(ep?.let { "Серия ${it.number}" }, ep?.displayTitle?.takeIf { it.isNotBlank() && !it.startsWith("Серия") }).joinToString(" · "),
                    color = Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                if (!m.native && m.player != null) Text("Плеер Kodik: в начале может быть реклама. Озвучки без рекламы — в списке «Озвучка».",
                    color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { BigChip(m.player?.team?.name ?: "—", if (m.player?.isLibria == true) "AniLibria" else if (m.native) "без рекламы" else "Kodik", Icons.Default.GraphicEq) { onPanel(Panel.TEAMS) } }
                if (m.qualities.size > 1) item { BigChip(m.quality ?: "—", "качество", Icons.Default.HighQuality) { onPanel(Panel.QUALITY) } }
                if (m.native) item { BigChip(if (m.speed == 1f) "1×" else "${m.speed}×", "скорость", Icons.Default.Speed) { onPanel(Panel.SPEED) } }
                if (m.native) item { BigChip(if (m.subtitlesOn && m.player?.subtitles?.isNotEmpty() == true) "Вкл" else "Выкл", "субтитры", Icons.Default.ClosedCaption) { onPanel(Panel.SUBS) } }
                item { BigChip(m.sleepLabel ?: "Выкл", "таймер сна", Icons.Default.Bedtime) { onPanel(Panel.SLEEP) } }
                if (m.native) item { BigChip("Настроить", "картинка и звук", Icons.Default.Equalizer) { onPanel(Panel.TUNE) } }
            }
        }
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (m.native) WideButton(if (roomActive) "Комната открыта" else "Смотреть вместе", Icons.Default.Groups, roomActive, Modifier.weight(1f), onRoom)
                if (m.hasNext) WideButton("Серия ${m.episodes[m.index + 1].number}", Icons.Default.SkipNext, false, Modifier.weight(1f)) { m.next() }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Серии", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("  ${m.episodes.size}", color = Color.White.copy(alpha = 0.45f), fontSize = 14.sp)
            }
        }
        itemsIndexed(m.episodes, key = { _, e -> e.id }) { i, ep -> EpisodeRow(m, i, ep, version) }
    }
}

@Composable
private fun EpisodeRow(m: PlayerModel, i: Int, ep: ru.qmods.anixq.tv.data.Episode, @Suppress("UNUSED_PARAMETER") version: Int, onPick: () -> Unit = {}) {
    val current = i == m.index
    val watched = Store.isWatched(m.title.id, ep.number)
    val progress = Store.progress(ep.id).toFloat()
    Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(if (current) Color.White.copy(alpha = 0.07f) else Color.Transparent)
        .clickable { onPick(); if (!current) m.load(i) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)).background(if (current) Pal.accent else Brush.linearGradient(listOf(Glass, Glass))),
            contentAlignment = Alignment.Center) {
            if (current && m.playing) Icon(Icons.Default.GraphicEq, null, tint = Color.White, modifier = Modifier.size(20.dp))
            else Text(ep.number, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(if (current) ep.displayTitle else ru.qmods.anixq.tv.data.Spoilers.title(ep, watched), color = Color.White, fontSize = 14.sp,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (progress > 0.02f && !watched && !current) Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Pal.pink))
            } else Text(if (current) "Сейчас" else if (watched) "Просмотрено" else "Серия ${ep.number}", fontSize = 12.sp,
                color = if (current) Pal.pink else Color.White.copy(alpha = 0.45f))
        }
        if (watched) Icon(Icons.Default.CheckCircle, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
    }
}

// MARK: - Settings panels (drawer in landscape, sheet in portrait)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PanelSheet(p: Panel, m: PlayerModel, onClose: () -> Unit, onPickSubs: () -> Unit) {
    ModalBottomSheet(onClose, containerColor = Color(0xFF16161A), contentColor = Color.White) {
        Text(p.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
        Box(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) { PanelContent(p, m, onClose, onPickSubs) }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PanelContent(p: Panel, m: PlayerModel, onClose: () -> Unit, onPickSubs: () -> Unit) {
    val context = LocalContext.current
    val version by Store.version.collectAsState()
    when (p) {
        Panel.EPISODES -> {
            val st = rememberLazyListState((m.index - 2).coerceAtLeast(0))
            LazyColumn(state = st) { itemsIndexed(m.episodes, key = { _, e -> e.id }) { i, ep -> EpisodeRow(m, i, ep, version, onClose) } }
        }
        Panel.TEAMS -> LazyColumn {
            val teams = m.players.groupBy { it.team.id }.values.map { ps -> ps.first().team to ps }
            teams.forEach { (t, ps) ->
                item {
                    OptionRow(t.name, listOfNotNull(if (ps.any { it.isNative }) "без рекламы" else "Kodik", if (t.isSubtitles) "субтитры" else null,
                        if (ru.qmods.anixq.tv.data.Libria.isLibria(t.name) && Store.libriaDirect) "с сервера AniLibria" else null).joinToString(" · "),
                        t.id == m.player?.team?.id) { onClose(); m.setTeam(t.id) }
                }
            }
        }
        Panel.QUALITY -> Column { m.qualities.forEach { q -> OptionRow(q, if (q == m.qualities.firstOrNull()) "лучшее" else null, q == m.quality) { onClose(); m.changeQuality(q) } } }
        Panel.SPEED -> Column {
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { f -> OptionRow(if (f == 1f) "Обычная" else "${f}×", null, f == m.speed) { onClose(); m.changeSpeed(f) } }
        }
        Panel.SLEEP -> Column {
            listOf<Pair<String, Int?>>("До конца серии" to 0, "15 минут" to 15, "30 минут" to 30, "45 минут" to 45, "1 час" to 60, "Выключить" to null).forEach { (l, v) ->
                OptionRow(l, null, (v == 0 && m.sleepAfterEpisode) || (v == null && m.sleepLabel == null)) { onClose(); m.setSleep(v) }
            }
        }
        Panel.SUBS -> Column(Modifier.padding(horizontal = 18.dp)) {
            if (m.player?.subtitles?.isNotEmpty() == true) SwitchRow("Показывать субтитры", m.subtitlesOn) { if (it != m.subtitlesOn) m.toggleSubtitles() }
            var scale by remember { mutableFloatStateOf(Store.subtitleScale) }
            Text("Размер: ${(scale * 100).toInt()}%", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
            Slider(scale, { scale = it; Store.subtitleScale = it; m.exo?.styleSubtitles(it, Store.subtitleBox) }, valueRange = 0.7f..1.8f,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Pal.pink, inactiveTrackColor = Glass))
            SwitchRow("Тёмная подложка", Store.subtitleBox) { Store.subtitleBox = it; m.exo?.styleSubtitles(Store.subtitleScale, it) }
            TextButton(onPickSubs, Modifier.padding(top = 4.dp)) { Text("Загрузить файл SRT / ASS…", color = Pal.pink) }
        }
        Panel.TUNE -> TuneContent(m, context)
    }
}

@Composable
private fun OptionRow(title: String, sub: String?, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (selected) Pal.pink else Color.White, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            sub?.let { Text(it, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp) }
        }
        if (selected) Icon(Icons.Default.CheckCircle, null, tint = Pal.pink, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SwitchRow(title: String, value: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(value) }
    Row(Modifier.fillMaxWidth().clickable { v = !v; onChange(v) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            sub?.let { Text(it, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp) }
        }
        Switch(v, { v = it; onChange(it) }, colors = SwitchDefaults.colors(checkedTrackColor = Pal.green, checkedThumbColor = Color.White,
            uncheckedTrackColor = Glass, uncheckedBorderColor = Color.Transparent, uncheckedThumbColor = Color.White))
    }
}

/** Colour presets and sliders (live), sound modes, background sound, marathon — same choices as on the iPhone. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun TuneContent(m: PlayerModel, context: Context) {
    var p by remember { mutableStateOf(ru.qmods.anixq.tv.ui.ColorTuning.value) }
    var sound by remember { mutableStateOf(ru.qmods.anixq.tv.ui.SoundMode.current) }
    fun change(n: ru.qmods.anixq.tv.ui.Picture, commit: Boolean) { p = n; m.setPicture(n, commit) }
    LazyColumn(Modifier.padding(horizontal = 18.dp)) {
        item { Text("Картинка", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ru.qmods.anixq.tv.ui.Picture.presets.size) { i ->
                    val (name, value) = ru.qmods.anixq.tv.ui.Picture.presets[i]
                    val sel = p == value
                    Text(name, color = if (sel) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(CircleShape).background(if (sel) Color.White else Glass).clickable { change(value, true) }.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
        item { TuneSlider("Насыщенность", p.saturation, 0.5f..1.6f, 1f, { change(p.copy(saturation = it), false) }) { change(p, true) } }
        item { TuneSlider("Яркость", p.brightness, -0.15f..0.15f, 0f, { change(p.copy(brightness = it), false) }) { change(p, true) } }
        item { TuneSlider("Контраст", p.contrast, 0.8f..1.3f, 1f, { change(p.copy(contrast = it), false) }) { change(p, true) } }
        item { Text("Звук", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)) }
        items(ru.qmods.anixq.tv.ui.SoundMode.entries.size) { i ->
            val s = ru.qmods.anixq.tv.ui.SoundMode.entries[i]
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (s == sound) Glass else Color.Transparent)
                .clickable { sound = s; m.setSound(s) }.padding(10.dp)) {
                Text(s.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (s == sound) Pal.pink else Color.White)
                Text(s.subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
            }
        }
        item {
            Spacer(Modifier.height(10.dp))
            SwitchRow("Звук в фоне", Store.backgroundAudio, "Серия играет с выключенным экраном, управление на экране блокировки") {
                Store.backgroundAudio = it; m.exo?.let { e -> if (it) ru.qmods.anixq.mobile.PlaybackService.attach(context, e.exo) }
            }
            SwitchRow("Режим марафона", Store.marathon, "Без опенингов и эндингов, серии подряд, перерыв каждые ${ru.qmods.anixq.tv.data.Marathon.BREAK_AFTER}") { Store.marathon = it }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TuneSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, neutral: Float, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            val span = maxOf(range.endInclusive - neutral, neutral - range.start)
            val pct = ((value - neutral) / span * 100).toInt()
            Text(if (pct == 0) "0" else "%+d".format(pct), fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.clickable { onChange(neutral); onDone() })
        }
        Slider(value, onChange, valueRange = range, onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Pal.pink, inactiveTrackColor = Glass))
    }
}

// MARK: - Small pieces

@Composable
private fun BarIcon(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = Color.White) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun CircleIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun RoundButton(icon: ImageVector, size: Int, big: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color.Black.copy(alpha = if (big) 0.42f else 0.3f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White, modifier = Modifier.size((size * if (big) 0.5f else 0.46f).dp)) }
}

@Composable
private fun Chip(text: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.clip(CircleShape).background(Glass).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(15.dp))
        Text(" $text", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
    }
}

@Composable
private fun BigChip(title: String, sub: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.08f)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Pal.pink, modifier = Modifier.size(20.dp))
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
            Text(sub, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun WideButton(text: String, icon: ImageVector, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.height(46.dp).clip(RoundedCornerShape(14.dp)).background(if (active) Pal.accent else Brush.linearGradient(listOf(Glass, Glass)))
        .clickable(onClick = onClick).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text("  $text", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SkipPill(text: String, icon: ImageVector = Icons.Default.FastForward, onClick: () -> Unit) {
    Row(Modifier.clip(CircleShape).background(Color.White).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.Black, modifier = Modifier.size(18.dp))
        Text(" $text", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BreakCard(m: PlayerModel) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF1C1C21)).padding(22.dp).width(320.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("☕", fontSize = 34.sp)
            Text("Уже ${ru.qmods.anixq.tv.data.Marathon.BREAK_AFTER} серии подряд", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            Text("Может, перерыв? Глазам и спине тоже нужен отдых.", fontSize = 14.sp, color = Dim, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Перерыв", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(CircleShape).background(Glass)
                    .clickable { m.breakPrompt = false }.padding(horizontal = 18.dp, vertical = 11.dp))
                Text("▶ Ещё одну", fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.clip(CircleShape).background(Color.White)
                    .clickable { m.breakPrompt = false; m.next() }.padding(horizontal = 18.dp, vertical = 11.dp))
            }
        }
    }
}
