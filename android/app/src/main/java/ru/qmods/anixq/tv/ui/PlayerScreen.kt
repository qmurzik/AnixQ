package ru.qmods.anixq.tv.ui

import android.content.Context
import android.view.KeyEvent as AKey
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.Episode
import ru.qmods.anixq.tv.data.Player
import ru.qmods.anixq.tv.data.Segment
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Title

/** Everything the player screen knows; Compose reads the `by mutableStateOf` fields. */
class PlayerModel(private val context: Context, private val scope: CoroutineScope, val title: Title, private val route: PlayerRoute) {
    var episodes by mutableStateOf<List<Episode>>(emptyList())
    var index by mutableIntStateOf(0)
    var players by mutableStateOf<List<Player>>(emptyList())
    var player by mutableStateOf<Player?>(null)
    var quality by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>("Загрузка…")
    var failed by mutableStateOf(false)
    var exo by mutableStateOf<ExoEngine?>(null)
    var kodik by mutableStateOf<KodikEngine?>(null)
    var native by mutableStateOf(true)

    // Polled from the engine
    var pos by mutableDoubleStateOf(0.0)
    var dur by mutableDoubleStateOf(0.0)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var ended by mutableStateOf(false)

    var openingSkipped by mutableStateOf(false)
    var nextDismissed by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    var toastAt by mutableLongStateOf(0L)

    private var servers: List<String> = emptyList()
    private var serverIdx = 0
    private var lastSaved = 0L
    private var lastRemote = 0L
    private var viewedSent = false
    private var prefetched = false
    private var loadJob: Job? = null

    val engine: Engine? get() = if (native) exo else kodik
    val episode: Episode? get() = episodes.getOrNull(index)
    val hasNext get() = index + 1 < episodes.size
    val hasPrev get() = index > 0
    val segments: List<Segment> get() = player?.segments ?: emptyList()
    val opening: Segment? get() = segments.firstOrNull { it.isOpening }
    val ending: Segment? get() = segments.firstOrNull { it.isEnding }
    val inOpening get() = opening?.let { pos >= it.from - 0.5 && pos < it.to - 2 } == true
    val inEnding get() = ending?.let { pos >= it.from } == true
    val remaining get() = if (dur > 0) dur - pos else Double.MAX_VALUE
    /** "Next episode" card: from the ending (or the last 90 s when we don't know where it starts). */
    val showNext get() = hasNext && !nextDismissed && dur > 0 &&
        (ended || inEnding || (ending == null && remaining <= 90) || remaining <= 15)
    /** The card counts down by itself only when we're sure it's the end. */
    val countdown get() = Store.autoNext && (ended || inEnding || remaining <= 12)

    fun say(s: String) { toast = s; toastAt = System.currentTimeMillis() }

    fun start() {
        scope.launch {
            try {
                val eps = Api.episodes(title.slug)
                if (eps.isEmpty()) { message = "Серий нет"; failed = true; return@launch }
                episodes = eps
                var i = route.episodeId?.let { id -> eps.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0
                if (route.next && i + 1 < eps.size) i += 1
                load(i, teamId = route.teamId)
            } catch (e: Exception) { message = e.message ?: "Ошибка"; failed = true }
        }
    }

    /** Switch episode / team / quality. [keepPos] keeps the moment when only the source changes. */
    fun load(i: Int, teamId: Int? = player?.team?.id, keepPos: Double? = null, preferKodik: Boolean = false, qualityKey: String? = null) {
        saveProgress(force = true)
        loadJob?.cancel()
        val sameEpisode = i == index && episodes.isNotEmpty() && keepPos != null
        index = i
        val ep = episodes[i]
        if (!sameEpisode) { openingSkipped = false; nextDismissed = false; viewedSent = false; prefetched = false; pos = 0.0; dur = 0.0; ended = false }
        message = if (sameEpisode) "Переключаем…" else "Серия ${ep.number}"
        failed = false
        buffering = true
        loadJob = scope.launch {
            try {
                val list = if (sameEpisode && players.isNotEmpty()) players else Api.players(ep.id)
                players = list
                if (list.isEmpty()) { message = "Для этой серии нет видео"; failed = true; return@launch }
                val forTeam = { id: Int? -> list.filter { it.team.id == id } }
                val favourite = list.firstOrNull { p -> Store.dubs.any { it.equals(p.team.name, true) } }?.team?.id
                val candidates = forTeam(teamId).ifEmpty { forTeam(favourite) }.ifEmpty { list.filter { it.isNative }.ifEmpty { list } }
                val chosen = (if (preferKodik) candidates.firstOrNull { it.isKodik } else null)
                    ?: candidates.firstOrNull { it.isNative } ?: candidates.firstOrNull { it.isKodik }
                    ?: list.firstOrNull { it.isNative } ?: list.first { it.isKodik || it.isNative }
                player = chosen
                val local = Store.resume(ep.id)
                val remote = chosen.serverTimecode?.takeIf { it > 5 && (route.episodeId != ep.id || local == null) }
                val startAt = keepPos ?: local ?: remote ?: 0.0
                if (chosen.isNative) startNative(chosen, startAt, qualityKey) else startKodik(chosen, startAt)
                if (startAt > 5 && keepPos == null) say("Продолжаем с ${fmt(startAt)}")
                message = null
            } catch (e: Exception) {
                message = e.message ?: "Не удалось загрузить серию"; failed = true
            }
        }
    }

    private suspend fun startNative(p: Player, startAt: Double, qualityKey: String?) {
        val keys = p.hrefs.keys.sortedByDescending { it.removeSuffix("p").toIntOrNull() ?: 0 }
        val q = qualityKey?.takeIf { it in keys }
            ?: keys.firstOrNull { (it.removeSuffix("p").toIntOrNull() ?: 0) <= Store.preferredQuality } ?: keys.last()
        quality = q
        val href = p.hrefs.getValue(q)
        servers = Api.rankServers(href)
        serverIdx = 0
        kodik?.release(); kodik = null
        native = true
        val e = exo ?: ExoEngine(context) { err -> onNativeError(err) }.also { exo = it }
        e.load(Api.videoUrl(servers[0], href), startAt)
    }

    private fun startKodik(p: Player, startAt: Double) {
        exo?.pause()
        native = false
        quality = null
        val k = kodik ?: KodikEngine(context).also { kodik = it }
        k.load(Api.kodikUrl(p.kodikSrc!!), startAt)
    }

    private fun onNativeError(err: String) {
        val p = player ?: return
        val href = quality?.let { p.hrefs[it] } ?: return
        val at = exo?.position ?: pos
        if (serverIdx + 1 < servers.size) {
            serverIdx += 1
            say("Зеркало ${serverIdx + 1}…")
            exo?.load(Api.videoUrl(servers[serverIdx], href), at)
            return
        }
        val k = players.firstOrNull { it.team.id == p.team.id && it.isKodik } ?: players.firstOrNull { it.isKodik }
        if (k != null) { say("Видео не грузится — включаем Kodik"); player = k; startKodik(k, at); return }
        message = "Видео не загружается ($err)"; failed = true
    }

    /** Called every 250 ms from the screen. */
    fun tick() {
        val e = engine ?: return
        pos = e.position; dur = e.duration; playing = e.isPlaying; buffering = e.isBuffering; ended = e.ended
        if (toast != null && System.currentTimeMillis() - toastAt > 2500) toast = null
        // Auto-skip the opening once per episode.
        val op = opening
        if (Store.autoSkipOpening && op != null && !openingSkipped && pos >= op.from && pos < op.to - 3) skipOpening(auto = true)
        // Watched at 90 %.
        if (!viewedSent && dur > 60 && pos / dur >= 0.9) {
            viewedSent = true
            episode?.let { ep -> Store.markWatched(title, ep); scope.launch { Api.markViewed(ep.id, player?.playerId) } }
        }
        // Warm up the next episode a few minutes before the end, so "next" starts instantly.
        if (hasNext && !prefetched && dur > 0 && dur - pos < 180) {
            prefetched = true
            val next = episodes[index + 1]
            scope.launch {
                runCatching {
                    val ps = Api.players(next.id)
                    ps.firstOrNull { it.team.id == player?.team?.id && it.isNative }?.hrefs?.values?.firstOrNull()?.let { Api.rankServers(it) }
                }
            }
        }
        saveProgress()
    }

    fun saveProgress(force: Boolean = false) {
        val ep = episode ?: return
        val p = player ?: return
        if (dur <= 0 || pos < 3) return
        val now = System.currentTimeMillis()
        if (force || (playing && now - lastSaved > 10_000)) {
            lastSaved = now
            Store.updateProgress(title, ep, p.team, pos, dur)
        }
        if (force || (playing && now - lastRemote > 30_000)) {
            lastRemote = now
            val at = pos
            scope.launch { Api.saveTimecode(ep.id, p.playerId, at) }
        }
    }

    fun toggle() { val e = engine ?: return; if (playing) { e.pause(); playing = false; saveProgress(force = true) } else { e.play(); playing = true } }

    fun seekBy(d: Double) {
        val e = engine ?: return
        val target = (pos + d).coerceIn(0.0, if (dur > 0) dur - 1 else Double.MAX_VALUE)
        e.seek(target); pos = target
        if (d < 0) nextDismissed = false
    }

    fun skipOpening(auto: Boolean = false) {
        val op = opening ?: return
        engine?.seek(op.to); pos = op.to
        openingSkipped = true
        say(if (auto) "Опенинг пропущен" else "Пропущено")
    }

    fun next() { if (hasNext) load(index + 1) }
    fun prev() { if (hasPrev) load(index - 1) }

    fun release() {
        saveProgress(force = true)
        exo?.release(); kodik?.release()
        exo = null; kodik = null
    }
}

private enum class Panel { NONE, EPISODES }

@Composable
fun PlayerScreen(nav: Nav, route: PlayerRoute) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val m = remember(route) { PlayerModel(context, scope, route.title, route) }
    var controls by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var seekAcc by remember { mutableDoubleStateOf(0.0) }
    var seekAt by remember { mutableLongStateOf(0L) }
    val root = remember { FocusRequester() }
    val playBtn = remember { FocusRequester() }
    val skipBtn = remember { FocusRequester() }

    // Booleans derived from the playback position: the screen redraws when they flip, not 4× a second.
    val showNext by remember(m) { derivedStateOf { m.showNext } }
    val inOpening by remember(m) { derivedStateOf { m.inOpening && !m.openingSkipped } }
    val countdown by remember(m) { derivedStateOf { m.countdown } }

    LaunchedEffect(m) { m.start() }
    DisposableEffect(m) { onDispose { m.release() } }
    LaunchedEffect(m) {
        while (true) {
            m.tick()
            if (seekAcc != 0.0 && System.currentTimeMillis() - seekAt > 1200) seekAcc = 0.0
            if (controls && panel == Panel.NONE && dialog == null && m.playing && System.currentTimeMillis() - lastInput > 5000) controls = false
            delay(250)
        }
    }
    // Countdown to the next episode.
    var count by remember { mutableIntStateOf(10) }
    val counting = showNext && countdown && dialog == null && panel == Panel.NONE
    LaunchedEffect(counting, m.index) {
        count = if (m.ended) 3 else 10
        while (counting && count > 0) { delay(1000); count -= 1 }
        if (counting && count == 0) m.next()
    }
    LaunchedEffect(controls, panel, dialog) {
        delay(60)
        if (dialog != null || panel != Panel.NONE) return@LaunchedEffect
        runCatching { if (controls) (if (inOpening) skipBtn else playBtn).requestFocus() else root.requestFocus() }
    }

    fun show() { controls = true; lastInput = System.currentTimeMillis() }
    fun exit() { m.release(); nav.pop() }

    BackHandler {
        when {
            dialog != null -> dialog = null
            panel != Panel.NONE -> panel = Panel.NONE
            controls -> controls = false
            showNext && !m.nextDismissed -> m.nextDismissed = true
            else -> exit()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPreviewKeyEvent { ev ->
                lastInput = System.currentTimeMillis()
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key.nativeKeyCode) {
                    AKey.KEYCODE_MEDIA_PLAY_PAUSE, AKey.KEYCODE_MEDIA_PLAY, AKey.KEYCODE_MEDIA_PAUSE -> { m.toggle(); true }
                    AKey.KEYCODE_MEDIA_FAST_FORWARD -> { m.seekBy(30.0); true }
                    AKey.KEYCODE_MEDIA_REWIND -> { m.seekBy(-30.0); true }
                    AKey.KEYCODE_MEDIA_NEXT, AKey.KEYCODE_CHANNEL_UP -> { m.next(); true }
                    AKey.KEYCODE_MEDIA_PREVIOUS, AKey.KEYCODE_CHANNEL_DOWN -> { m.prev(); true }
                    else -> false
                }
            }
            .focusRequester(root)
            .onKeyEvent { ev ->
                if (controls || panel != Panel.NONE || ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                val repeat = ev.nativeKeyEvent.repeatCount
                when (ev.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        when {
                            m.failed -> m.load(m.index)
                            showNext -> m.next()
                            inOpening -> m.skipOpening()
                            else -> { m.toggle(); if (!m.playing) show() }
                        }
                        true
                    }
                    Key.DirectionLeft, Key.DirectionRight -> {
                        val step = if (repeat > 8) 30.0 else 10.0
                        val d = if (ev.key == Key.DirectionRight) step else -step
                        m.seekBy(d); seekAcc += d; seekAt = System.currentTimeMillis()
                        true
                    }
                    Key.DirectionUp -> { show(); true }
                    Key.DirectionDown -> { panel = Panel.EPISODES; true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        // Video
        m.exo?.let { e -> if (m.native) AndroidView(factory = { e.view }, modifier = Modifier.fillMaxSize()) }
        m.kodik?.let { k -> if (!m.native) AndroidView(factory = { k.view }, modifier = Modifier.fillMaxSize()) }

        // Loading / error
        if (m.message != null || (m.buffering && m.native && m.exo != null)) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Spinner()
                Spacer(Modifier.height(14.dp))
                m.message?.let { Text(it, fontSize = 18.sp, color = Color.White) }
                if (m.failed) {
                    Spacer(Modifier.height(10.dp))
                    Text("OK — попробовать снова · Назад — выйти", fontSize = 14.sp, color = C.muted)
                }
            }
        }

        // Seek feedback without the full panel: slim bar + time at the bottom, like a TV box player.
        if (seekAcc != 0.0 && !controls) SeekPreview(m, seekAcc, Modifier.align(Alignment.BottomCenter))

        // Toast
        m.toast?.let {
            Text(it, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 10.dp))
        }

        // Paused badge when controls are hidden
        if (!controls && !m.playing && m.message == null && !showNext && panel == Panel.NONE && seekAcc == 0.0) {
            Box(Modifier.align(Alignment.Center).size(96.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(60.dp), tint = Color.White)
            }
        }

        // Skip opening — big and obvious, OK does it
        if (!controls && panel == Panel.NONE && inOpening && !Store.autoSkipOpening && seekAcc == 0.0) {
            ActionCard("Пропустить опенинг", "нажмите OK", Modifier.align(Alignment.BottomEnd).padding(48.dp))
        }

        // Next episode
        if (!controls && panel == Panel.NONE && showNext && seekAcc == 0.0) {
            val ep = m.episodes.getOrNull(m.index + 1)
            ActionCard("Серия ${ep?.number ?: ""}" + (ep?.displayTitle?.takeIf { !it.startsWith("Серия") }?.let { " · $it" } ?: ""),
                if (countdown) "через $count с  ·  OK — сейчас" else "OK — смотреть следующую",
                Modifier.align(Alignment.BottomEnd).padding(48.dp), progress = if (countdown) count / if (m.ended) 3f else 10f else null)
        }

        // Controls
        AnimatedVisibility(controls && panel == Panel.NONE, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Controls(m, playBtn, skipBtn, onAny = { lastInput = System.currentTimeMillis() },
                onEpisodes = { panel = Panel.EPISODES }, onDub = { dialog = "dub" }, onQuality = { dialog = "quality" })
        }

        // Episodes
        AnimatedVisibility(panel == Panel.EPISODES, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)) {
            EpisodesPanel(m) { i -> panel = Panel.NONE; controls = false; m.load(i) }
        }
    }

    when (dialog) {
        "dub" -> {
            val teams = m.players.groupBy { it.team.id }.values.map { ps -> ps.first().team to ps }
            ChoiceDialog("Озвучка", teams.map { (t, ps) ->
                Choice(t.name, t.id == m.player?.team?.id, listOfNotNull(if (t.isSubtitles) "субтитры" else "озвучка",
                    if (ps.any { it.isNative }) "без рекламы" else "Kodik").joinToString(" · "))
            }, { dialog = null }) { i ->
                dialog = null
                val t = teams[i].first
                Store.setDubs(listOf(t.name) + Store.dubs.filter { !it.equals(t.name, true) })
                m.load(m.index, teamId = t.id, keepPos = m.pos)
            }
        }
        "quality" -> {
            val p = m.player
            val keys = p?.hrefs?.keys?.sortedByDescending { it.removeSuffix("p").toIntOrNull() ?: 0 } ?: emptyList()
            val kodikToo = m.players.any { it.team.id == p?.team?.id && it.isKodik }
            val opts = keys.map { Choice(it, m.native && it == m.quality) } + listOfNotNull(if (kodikToo) Choice("Плеер Kodik", !m.native, "если основной не грузится") else null)
            ChoiceDialog("Качество", opts, { dialog = null }) { i ->
                dialog = null
                if (i < keys.size) {
                    keys[i].removeSuffix("p").toIntOrNull()?.let { Store.preferredQuality = it }
                    m.load(m.index, keepPos = m.pos, qualityKey = keys[i])
                } else m.load(m.index, keepPos = m.pos, preferKodik = true)
            }
        }
    }
}

@Composable
internal fun Controls(m: PlayerModel, playBtn: FocusRequester, skipBtn: FocusRequester, onAny: () -> Unit,
                     onEpisodes: () -> Unit, onDub: () -> Unit, onQuality: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(170.dp).background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.8f), 1f to Color.Black.copy(alpha = 0f))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(320.dp)
            .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0f), 0.55f to Color.Black.copy(alpha = 0.7f), 1f to Color.Black.copy(alpha = 0.92f))))
        Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()), fontSize = 20.sp,
            color = Color.White.copy(alpha = 0.85f), modifier = Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 48.dp))
        Column(Modifier.padding(start = 48.dp, top = 30.dp, end = 160.dp)) {
            Text(m.title.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val ep = m.episode
            Text(listOfNotNull(ep?.let { "Серия ${it.number}" + (it.displayTitle.takeIf { t -> !t.startsWith("Серия") }?.let { t -> " · $t" } ?: "") },
                m.player?.team?.name, m.quality ?: if (!m.native) "Kodik" else null).joinToString("  ·  "),
                fontSize = 16.sp, color = Color.White.copy(alpha = 0.75f))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 48.dp, vertical = 36.dp)) {
            if (m.inOpening && !m.openingSkipped) {
                PillButton("Пропустить опенинг", Icons.Rounded.FastForward, primary = true, modifier = Modifier.focusRequester(skipBtn)) { m.skipOpening() }
                Spacer(Modifier.height(16.dp))
            }
            SeekBar(m, onAny)
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (m.hasPrev) RoundButton(Icons.Rounded.SkipPrevious) { m.prev() }
                RoundButton(Icons.Rounded.FastRewind) { m.seekBy(-10.0) }
                RoundButton(if (m.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, big = true, modifier = Modifier.focusRequester(playBtn)) { m.toggle() }
                RoundButton(Icons.Rounded.FastForward) { m.seekBy(10.0) }
                if (m.hasNext) RoundButton(Icons.Rounded.SkipNext) { m.next() }
                Spacer(Modifier.width(16.dp))
                if (m.opening == null) PillButton("+85 с") { m.seekBy(85.0) }
                PillButton("Серии", Icons.Rounded.VideoLibrary, onClick = onEpisodes)
                if (m.players.map { it.team.id }.distinct().size > 1) PillButton("Озвучка", Icons.Rounded.RecordVoiceOver, onClick = onDub)
                PillButton(m.quality ?: "Kodik", Icons.Rounded.HighQuality, onClick = onQuality)
            }
        }
    }
}

@Composable
private fun SeekBar(m: PlayerModel, onAny: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).height(28.dp)
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                    onAny()
                    val step = if (ev.nativeKeyEvent.repeatCount > 8) 30.0 else 10.0
                    when (ev.key) {
                        Key.DirectionLeft -> { m.seekBy(-step); true }
                        Key.DirectionRight -> { m.seekBy(step); true }
                        Key.DirectionCenter, Key.Enter -> { m.toggle(); true }
                        else -> false
                    }
                }
                .focusable(),
            contentAlignment = Alignment.CenterStart,
        ) {
            val h = if (focused) 8.dp else 5.dp
            Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.25f)))
            val frac = if (m.dur > 0) (m.pos / m.dur).toFloat().coerceIn(0f, 1f) else 0f
            // Opening/ending marks
            m.segments.forEach { s ->
                if (m.dur > 0) Box(Modifier.fillMaxWidth((s.to / m.dur).toFloat().coerceIn(0f, 1f)).height(h)) {
                    Box(Modifier.align(Alignment.CenterEnd).fillMaxWidth((((s.to - s.from) / s.to)).toFloat().coerceIn(0f, 1f)).height(h)
                        .background(Color(0xFFFFC94D).copy(alpha = 0.55f)))
                }
            }
            Box(Modifier.fillMaxWidth(frac).height(h).clip(RoundedCornerShape(50)).background(C.grad))
            if (focused) Box(Modifier.fillMaxWidth(frac)) {
                Box(Modifier.align(Alignment.CenterEnd).offset(x = 9.dp).size(18.dp).clip(CircleShape).background(Color.White))
            }
        }
        Spacer(Modifier.width(18.dp))
        Text("${fmt(m.pos)} / ${fmt(m.dur)}", fontSize = 16.sp, color = Color.White)
    }
    if (focused) Text("← → перемотка · OK пауза", fontSize = 12.sp, color = C.muted, modifier = Modifier.padding(top = 4.dp))
}

@Composable
internal fun SeekPreview(m: PlayerModel, delta: Double, modifier: Modifier) {
    Column(modifier.fillMaxWidth().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0f), 0.5f to Color.Black.copy(alpha = 0.6f), 1f to Color.Black.copy(alpha = 0.88f)))
        .padding(start = 48.dp, end = 48.dp, top = 60.dp, bottom = 34.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmt(m.pos), fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("  /  ${fmt(m.dur)}", fontSize = 18.sp, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 4.dp))
            Spacer(Modifier.weight(1f))
            Text((if (delta > 0) "+" else "−") + "${kotlin.math.abs(delta).toInt()} с", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = C.pink)
        }
        Spacer(Modifier.height(10.dp))
        val frac = if (m.dur > 0) (m.pos / m.dur).toFloat().coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxWidth(frac).height(6.dp).background(C.grad))
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, big: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val s = if (big) 64.dp else 50.dp
    Focusable(onClick = onClick, modifier = modifier.size(s), radius = s / 2, scale = 1.12f,
        container = Color.White.copy(alpha = 0.14f), focusedContainer = Color.White) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, null, modifier = Modifier.size(if (big) 36.dp else 28.dp))
        }
    }
}

@Composable
internal fun ActionCard(title: String, sub: String, modifier: Modifier, progress: Float? = null) {
    Column(modifier.width(340.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)
        .border(2.dp, Color.White, RoundedCornerShape(16.dp))) {
        Row(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Text(sub, fontSize = 14.sp, color = Color.Black.copy(alpha = 0.6f))
            }
            Icon(Icons.Rounded.SkipNext, null, tint = Color.Black, modifier = Modifier.size(34.dp))
        }
        progress?.let { Box(Modifier.fillMaxWidth(it.coerceIn(0f, 1f)).height(5.dp).background(C.grad)) }
    }
}

@Composable
internal fun EpisodesPanel(m: PlayerModel, onPick: (Int) -> Unit) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (m.index - 1).coerceAtLeast(0))
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.95f))))
        .padding(top = 60.dp, bottom = 36.dp)) {
        Text("Серии", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 48.dp, bottom = 12.dp))
        LazyRow(state = list, contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(m.episodes, key = { _, e -> e.id }) { i, e ->
                val fr = remember { FocusRequester() }
                if (i == m.index) LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
                val prog = Store.progress(e.id)
                val watched = Store.isWatched(m.title.id, e.number) || prog > 0.92
                Column(Modifier.width(190.dp)) {
                    Focusable(onClick = { onPick(i) }, modifier = Modifier.fillMaxWidth().height(106.dp).focusRequester(fr),
                        container = if (i == m.index) C.pink.copy(alpha = 0.35f) else C.card) {
                        Box(Modifier.fillMaxSize()) {
                            Text(e.number, fontSize = 38.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = if (watched && i != m.index) 0.35f else 0.95f),
                                modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
                            if (i == m.index) Text("СЕЙЧАС", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp))
                            if (prog > 0.01 && prog <= 0.92) ProgressLine(prog, Modifier.align(Alignment.BottomCenter))
                        }
                    }
                    Text(e.displayTitle, fontSize = 13.sp, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun Spinner() {
    var a by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(120); a = (a + 1) % 8 } }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i -> Box(Modifier.size(10.dp).clip(CircleShape).background(if ((a / 2) % 3 == i) C.pink else Color.White.copy(alpha = 0.3f))) }
    }
}
