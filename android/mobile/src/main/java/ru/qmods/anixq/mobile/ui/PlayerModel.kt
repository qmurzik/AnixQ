package ru.qmods.anixq.mobile.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.qmods.anixq.mobile.data.PlaybackHistory
import ru.qmods.anixq.tv.data.AniSkip
import ru.qmods.anixq.tv.data.sources.AnimeSources
import ru.qmods.anixq.tv.data.sources.qualityHeight
import ru.qmods.anixq.tv.data.PlaybackLabels
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.Episode
import ru.qmods.anixq.tv.data.Player
import ru.qmods.anixq.tv.data.Remote
import ru.qmods.anixq.tv.data.Segment
import ru.qmods.anixq.tv.data.Shiki
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Sync
import ru.qmods.anixq.tv.data.Title
import ru.qmods.anixq.tv.ui.StallRecovery
import ru.qmods.anixq.tv.ui.Engine
import ru.qmods.anixq.tv.ui.ExoEngine
import ru.qmods.anixq.tv.ui.KodikEngine

/** Phone player logic: same choices and progress rules as the TV player, so both sides agree. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerModel(private val context: Context, private val scope: CoroutineScope, val title: Title, private val route: PlayerRoute) :
    ru.qmods.anixq.tv.data.RoomPlayer {
    /** Watch together: what the others need to follow us (same format as the TV, iPhone and the web page). */
    override fun roomState(): org.json.JSONObject = org.json.JSONObject()
        .put("slug", title.slug).put("titleName", title.title).put("episodeId", episode?.id ?: 0)
        .put("catalogSource", if (title.slug.startsWith("yummy:")) "yummy" else "animelib")
        .put("playbackSource", player?.source ?: "animelib").put("balancer", player?.balancer ?: "").put("dubbing", player?.team?.name ?: "")
        .put("episodeNumber", episode?.number ?: "").put("teamId", player?.team?.id ?: 0).put("team", player?.team?.name ?: "")
        .put("playing", playing).put("position", pos).put("rate", speed.toDouble())
        .apply {
            // AniLibria's HLS: the web page finds it by itself (and plays it with hls.js), so no "stream" here.
            if (native && player?.isLibria != true && player?.isExternalEmbed != true) currentUrl?.takeIf { it.startsWith("http") }?.let { put("stream", it) }
            val k = if (player?.isExternalEmbed == true) null else if (!native) currentUrl else players.firstOrNull { it.team.id == player?.team?.id && it.isKodik }?.kodikSrc?.let { Api.kodikUrl(it) }
                ?: players.firstOrNull { it.isKodik }?.kodikSrc?.let { Api.kodikUrl(it) }
            k?.let { put("kodik", it) }
        }

    override fun roomApply(m: org.json.JSONObject) {
        if (m.optString("slug") != title.slug) { say("В комнате смотрят «${m.optString("titleName")}»"); return }
        val eid = m.optInt("episodeId"); val tid = m.optInt("teamId")
        if (eid != episode?.id) {
            val i = episodes.indexOfFirst { it.id == eid }.takeIf { it >= 0 } ?: episodes.indexOfFirst { it.number == m.optString("episodeNumber") }
            if (i >= 0) load(i, teamId = tid.takeIf { it != 0 }, keepPos = m.optDouble("position", 0.0))
            return
        }
        if (!loading && m.has("playbackSource")) {
            val choice = players.firstOrNull { it.source == m.optString("playbackSource") && it.team.name == m.optString("dubbing") && it.balancer.orEmpty() == m.optString("balancer") }
            if (choice != null && (choice.source != player?.source || choice.team.id != player?.team?.id)) {
                load(index, choice.team.id, keepPos = m.optDouble("position", pos)); return
            }
        }
        val remotePlaying = m.optBoolean("playing")
        var target = m.optDouble("position", pos)
        if (remotePlaying) target += (System.currentTimeMillis() / 1000.0 + Sync.serverClockOffset - m.optDouble("sentAt", 0.0))
            .coerceIn(0.0, 10.0) * m.optDouble("rate", 1.0)
        if (kotlin.math.abs(target - pos) > 2) { engine?.seek(target); pos = target }
        if (remotePlaying != playing) toggle()
    }

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

    var pos by mutableDoubleStateOf(0.0)
    var dur by mutableDoubleStateOf(0.0)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var ended by mutableStateOf(false)
    data class SeekUndo(val episodeId: Int, val position: Double, val expiresAt: Long)
    var seekUndo by mutableStateOf<SeekUndo?>(null)
        private set
    var speed by mutableFloatStateOf(1f)
    var subtitlesOn by mutableStateOf(Store.subtitles)

    var openingSkipped by mutableStateOf(false)
    var nextDismissed by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    private var toastAt by mutableLongStateOf(0L)

    val unsupportedBalancers get() = players.filter { !AnimeSources.supported(it) }.mapNotNull { it.balancer }.distinct()
    val sourceOptions get() = players.filter(AnimeSources::supported).map { it.source }.distinct()
    val balancerOptions get() = players.filter { it.source == player?.source && AnimeSources.supported(it) }.mapNotNull { it.balancer }.distinct()
    val dubOptions get() = players.filter { it.source == player?.source && it.balancer == player?.balancer && AnimeSources.supported(it) }
        .groupBy { PlaybackLabels.dubKey(it.team) }.values.map { ps -> ps.firstOrNull { it.team.id == player?.team?.id } ?: ps.firstOrNull { it.isNative } ?: ps.first() }
    fun selectSource(source: String) {
        val p = AnimeSources.chooseSource(players, player, source) ?: return
        load(index, p.team.id, keepPos = engine?.position ?: pos)
    }
    fun selectBalancer(name: String) {
        val choices = players.filter { it.source == player?.source && it.balancer == name && AnimeSources.supported(it) }
        val p = choices.firstOrNull { player?.team?.let { old -> PlaybackLabels.dubKey(it.team) == PlaybackLabels.dubKey(old) } == true } ?: choices.firstOrNull() ?: return
        load(index, p.team.id, keepPos = engine?.position ?: pos)
    }
    private var servers: List<String> = emptyList()
    private var serverIdx = 0
    private var lastSaved = 0L
    private var lastRemote = 0L
    private var viewedSent = false
    private var prefetched = false
    private var nextLooked = false
    /** Next episode's players, looked up early so switching doesn't wait for AnimeLib. */
    private val playersCache = HashMap<Int, List<Player>>()
    private var startedOnce = false
    private var loadJob: Job? = null
    private var loadGeneration = 0
    var loading by mutableStateOf(true)
        private set
    private var autoAdvancedEpisode: Int? = null
    private val recovery = StallRecovery()
    private var currentUrl: String? = null
    /** What the scrubber's frame previews read: the file being played (progressive video only, not HLS). */
    val previewSource: Pair<String, Map<String, String>>? get() = currentUrl?.takeIf { native && !it.substringBefore('?').endsWith(".m3u8") }?.let { it to (player?.headers ?: emptyMap()) }

    val engine: Engine? get() = if (native) exo else kodik
    val episode: Episode? get() = episodes.getOrNull(index)
    val hasNext get() = index + 1 < episodes.size
    val hasPrev get() = index > 0
    var extraSegments by mutableStateOf<List<Segment>>(emptyList())
    private var aniSkipAsked = -1
    val segments: List<Segment> get() = player?.segments.orEmpty() + extraSegments
    val opening: Segment? get() = segments.firstOrNull { it.isOpening }
    val ending: Segment? get() = segments.firstOrNull { it.isEnding }
    val inOpening get() = opening?.let { pos >= it.from - 0.5 && pos < it.to - 2 } == true
    /** "Previously on…" (AniSkip), skippable like the opening. */
    var recap by mutableStateOf<Segment?>(null)
    private var recapAsked = -1
    var recapSkipped by mutableStateOf(false)
    val inRecap get() = recap?.let { pos >= it.from - 0.5 && pos < it.to - 2 } == true
    fun skipRecap(auto: Boolean = false) {
        val r = recap ?: return
        engine?.seek(r.to); pos = r.to
        recapSkipped = true
        say(if (auto) "Пересказ пропущен" else "Пропущено")
    }
    val inEnding get() = ending?.let { pos >= it.from } == true
    val remaining get() = if (dur > 0) dur - pos else Double.MAX_VALUE
    val showNext get() = !loading && !failed && seekUndo == null && hasNext && !nextDismissed && dur > 0 && (ended || inEnding || (ending == null && remaining <= 90) || remaining <= 15)
    val countdown get() = !loading && !failed && (Store.autoNext || Store.marathon) && !breakPrompt && (ended || inEnding || remaining <= 12)
    /** Marathon: a break is suggested instead of the next episode every few in a row. */
    var breakPrompt by mutableStateOf(false)
    private var marathonRun = 0
    private var endingSkipped = false

    // MARK: sleep timer, rating, timeline comments, own subtitles (iPhone parity)

    /** Sleep timer: pause at [sleepAt] (ms) or at the end of this episode. */
    var sleepAt by mutableLongStateOf(0L)
    var sleepAfterEpisode by mutableStateOf(false)
    fun setSleep(minutes: Int?) {
        when {
            minutes == null -> { sleepAt = 0; sleepAfterEpisode = false; say("Таймер сна выключен") }
            minutes == 0 -> { sleepAt = 0; sleepAfterEpisode = true; say("Пауза в конце серии") }
            else -> { sleepAfterEpisode = false; sleepAt = System.currentTimeMillis() + minutes * 60_000L; say("Пауза через $minutes мин") }
        }
    }
    val sleepLabel: String? get() = when {
        sleepAfterEpisode -> "до конца серии"
        sleepAt > 0 -> "${((sleepAt - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)} мин"
        else -> null
    }

    /** "Как вам?" at the end of the last episode of a finished title. */
    var ratePrompt by mutableStateOf(false)

    var timed by mutableStateOf<List<ru.qmods.anixq.tv.data.Api.TimedComment>>(emptyList())
    private var timedFor = -1

    /** Subtitles from a file on the phone (SRT / ASS / VTT), added to this episode. */
    fun addSubtitles(uri: String, name: String) {
        val p = player ?: return
        val ext = name.substringAfterLast('.', "srt").lowercase()
        val sub = ru.qmods.anixq.tv.data.Subtitle(uri, if (ext == "ass" || ext == "ssa") "ass" else ext, name.substringBeforeLast('.'))
        val updated = p.copy(subtitles = listOf(sub) + p.subtitles)
        player = updated
        subtitlesOn = true; Store.subtitles = true
        exo?.load(currentUrl ?: return, pos, updated.subtitles)
        exo?.setSubtitles(true)
        say("Субтитры: ${sub.label}")
    }

    fun autoAdvance() {
        if (loading || failed || seekUndo != null || !hasNext) return
        val id = episode?.id ?: return
        if (autoAdvancedEpisode == id) return
        autoAdvancedEpisode = id
        if (sleepAfterEpisode) { sleepAfterEpisode = false; pause(); say("Таймер сна: пауза"); return }
        marathonRun += 1
        if (Store.marathon && marathonRun >= ru.qmods.anixq.tv.data.Marathon.BREAK_AFTER) {
            marathonRun = 0
            pause()
            breakPrompt = true
            return
        }
        next()
    }
    val qualities: List<String> get() = player?.hrefs?.keys?.sortedByDescending { qualityHeight(it) } ?: emptyList()

    fun say(s: String) { toast = s; toastAt = System.currentTimeMillis() }

    fun start() {
        scope.launch { PlaybackHistory.classify(title) }
        scope.launch {
            try {
                Sync.pullFresh()
                // Offline: the episode list saved with the downloads.
                val eps = runCatching { Api.episodes(title.slug) }.getOrNull()
                    ?: ru.qmods.anixq.mobile.data.Downloads.episodes(title.id) ?: throw Exception("Нет связи с AnimeLib")
                if (eps.isEmpty()) { message = "Серий нет"; failed = true; return@launch }
                episodes = eps
                val latest = if (route.resume) PlaybackHistory.lastEntry(title) else null
                val epId = latest?.episodeId ?: route.episodeId
                val next = if (latest != null) latest.progress > 0.92 else route.next
                var i = epId?.let { id -> eps.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
                    ?: latest?.let { l -> eps.indexOfFirst { it.number == l.ep } }?.takeIf { it >= 0 } ?: 0
                if (next && i + 1 < eps.size) i += 1
                load(i, teamId = latest?.teamId ?: route.teamId)
            } catch (e: Exception) { message = e.message ?: "Ошибка"; failed = true }
        }
    }

    fun load(i: Int, teamId: Int? = player?.team?.id, keepPos: Double? = null, preferKodik: Boolean = false, qualityKey: String? = null) {
        if (i !in episodes.indices) return
        saveProgress(force = true)
        loadJob?.cancel()
        val generation = ++loadGeneration
        val sameEpisode = i == index && episodes.isNotEmpty() && keepPos != null
        loading = true
        seekUndo = null
        if (!sameEpisode) autoAdvancedEpisode = null
        engine?.pause()
        playing = false
        ended = false
        recovery.reset(android.os.SystemClock.elapsedRealtime(), clearHistory = !sameEpisode)
        if (keepPos != null) pos = keepPos
        index = i
        val ep = episodes[i]
        val previousChoice = player
        if (!sameEpisode) player = null
        if (!sameEpisode) { avoidLibria = false; recap = null; recapSkipped = false; endingSkipped = false; extraSegments = emptyList(); openingSkipped = false; nextDismissed = false; viewedSent = false; prefetched = false; nextLooked = false; pos = 0.0; dur = 0.0; ended = false }
        message = if (sameEpisode) "Переключаем…" else "Серия ${ep.number}"
        failed = false
        buffering = true
        loadJob = scope.launch {
            try {
                // A downloaded episode plays from the phone: instant, no traffic, works offline.
                val offline = ru.qmods.anixq.mobile.data.Downloads.offlinePlayer(ep.id)
                val offlinePlayers = offline?.let { listOf(it) }
                if (offline != null && !sameEpisode) say("Скачанная серия — без интернета")
                val includeExternal = Store.prefersExternal(title) || title.id < 0 ||
                    teamId?.let { it < 0 } == true || previousChoice?.isExternalEmbed == true
                val list = if (sameEpisode && players.isNotEmpty()) players else
                    offlinePlayers ?: playersCache.remove(ep.id)?.takeUnless { includeExternal }
                        ?: AnimeSources.players(title, ep, includeExternal = includeExternal)
                players = list
                if (!includeExternal && title.id > 0 && list.any(AnimeSources::supported) && list.none { it.isExternalEmbed }) launch {
                    val extra = AnimeSources.additional(title, ep)
                    if (generation == loadGeneration && episode?.id == ep.id) players = (players + extra).distinctBy { "${it.source}:${it.team.id}" }
                }
                if (list.isEmpty()) { message = "Для этой серии нет видео"; failed = true; return@launch }
                val forTeam = { id: Int? -> list.filter { it.team.id == id }.ifEmpty { list.filter { previousChoice != null && it.source == previousChoice.source && it.balancer == previousChoice.balancer && PlaybackLabels.dubKey(it.team) == PlaybackLabels.dubKey(previousChoice.team) } } }
                val preferredChoice = Store.playbackChoice(title, list)
                val favourite = list.firstOrNull { p -> Store.dubs.any { it.equals(p.team.name, true) } }?.team?.id
                val candidates = forTeam(teamId).ifEmpty { preferredChoice?.let { listOf(it) }.orEmpty() }.ifEmpty { forTeam(favourite) }.ifEmpty { list.filter { it.isNative }.ifEmpty { list } }
                val first = (if (preferKodik) candidates.firstOrNull { it.isKodik } else null)
                    ?: candidates.firstOrNull { it.isNative || (it.canResolve && AnimeSources.supported(it)) } ?: candidates.firstOrNull { it.isKodik }
                    ?: list.firstOrNull { it.isNative || (it.canResolve && AnimeSources.supported(it)) } ?: list.firstOrNull { it.isKodik || it.isNative }
                if (first == null) { message = "Для этой серии нет видео"; failed = true; return@launch }
                player = first
                var chosen: Player = AnimeSources.resolve(first, title, ep, cached = previousChoice.takeIf { sameEpisode && qualityKey != null })
                // AniLibria dub: their own servers when they have the episode (no ads, often faster than AnimeLib).
                if (offline == null && !preferKodik && !avoidLibria && !chosen.isExternalEmbed && !Store.hasPlaybackChoice(title) && Store.libriaDirect && ru.qmods.anixq.tv.data.Libria.isLibria(chosen.team.name)) {
                    ru.qmods.anixq.tv.data.Libria.race(title, ep.number, chosen, probeAt = keepPos ?: savedPosition(ep) ?: 0.0)?.let { chosen = it; if (!sameEpisode) say("AniLibria: видео с их сервера") }
                }
                currentCoroutineContext().ensureActive()
                if (generation != loadGeneration) return@launch
                player = chosen
                Store.setPlaybackChoice(title, chosen)
                val local = savedPosition(ep)
                val remote = chosen.serverTimecode?.takeIf { PlaybackHistory.canRecord(title) && it > 5 && (route.episodeId != ep.id || local == null) }
                var startAt = keepPos ?: (if (!startedOnce) route.startAt else null) ?: local ?: remote ?: 0.0
                var recapNote: String? = null
                if (keepPos == null && route.startAt == null && PlaybackHistory.canRecord(title)) ru.qmods.anixq.tv.data.Recap.adjust(title.id, ep.id, startAt).let { (a, n) -> startAt = a; recapNote = n }
                startedOnce = true
                if (chosen.isNative) startNative(chosen, startAt, qualityKey) else startKodik(chosen, startAt)
                ru.qmods.anixq.tv.data.Room.sendState()
                if (recapNote != null) say(recapNote!!) else if (startAt > 5 && keepPos == null) say("Продолжаем с ${fmt(startAt)}")
                currentCoroutineContext().ensureActive()
                if (generation == loadGeneration) message = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == loadGeneration) { message = e.message ?: "Не удалось загрузить серию"; failed = true }
            } finally {
                if (generation == loadGeneration) loading = false
            }
        }
    }

    private suspend fun startNative(p: Player, startAt: Double, qualityKey: String?) {
        val keys = p.hrefs.keys.sortedByDescending { qualityHeight(it) }
        // Keeps the quality of the previous episode (so the file queued in advance is the one played).
        val q = qualityKey?.takeIf { it in keys } ?: quality?.takeIf { it in keys }
            ?: keys.firstOrNull { (qualityHeight(it)) <= Store.preferredQuality } ?: keys.last()
        quality = q
        val href = p.hrefs.getValue(q)
        // Downloaded file or a full link (AniLibria): played as is, no AnimeLib mirrors.
        val local = href.startsWith("file:") || href.startsWith("http")
        servers = if (local) listOf("") else Api.rankServers(href)
        serverIdx = 0
        kodik?.release(); kodik = null
        native = true
        val e = exo ?: ExoEngine(context) { err -> onNativeError(err) }.also {
            it.exo.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            exo = it
        }
        currentUrl = if (local) href else Api.videoUrl(servers[0], href)
        e.load(currentUrl!!, startAt, p.subtitles, 0, p.headers)
        e.setAutomaticQuality(q.equals("auto", true) || Store.automaticQuality)
        e.setSubtitles(subtitlesOn)
        if (speed != 1f) e.exo.setPlaybackSpeed(speed)
    }

    private fun startKodik(p: Player, startAt: Double) {
        exo?.pause()
        native = false
        quality = null
        val k = kodik ?: KodikEngine(context).also { kodik = it }
        currentUrl = Api.kodikUrl(p.kodikSrc!!)
        k.load(currentUrl!!, startAt)
    }

    /** AniLibria's server failed for this episode: AnimeLib's copy of the same dub from here on. */
    private val refreshAttempts = HashMap<String, Int>()
    private var avoidLibria = false
    private var startWatchdog = 0L

    private fun onNativeError(err: String) {
        val p = player ?: return
        if (exo?.effectsActive == true && exo?.effectFailure == true) {
            say("Обработка изображения отключена после ошибки GPU")
            load(index, keepPos = maxOf(pos, exo?.position ?: 0.0), qualityKey = quality)
            return
        }
        if (p.isExternalEmbed) {
            val at = exo?.position?.takeIf { it > 0 } ?: pos
            pos = at
            val key = "${episode?.id}:${p.team.id}"
            val count = refreshAttempts[key] ?: 0
            if (count < 1) {
                refreshAttempts[key] = count + 1
                load(index, teamId = p.team.id, keepPos = at)
            } else { message = "${p.balancer ?: "Источник"}: $err. Выберите другой источник или озвучку"; failed = true }
            return
        }
        if (p.isLibria) {
            avoidLibria = true
            ru.qmods.anixq.tv.data.Libria.markFailed()
            say("AniLibria не отвечает — берём видео с AnimeLib")
            load(index, teamId = players.firstOrNull { it.source == "animelib" && it.team.name == p.team.name }?.team?.id, keepPos = exo?.position ?: pos)
            return
        }
        val href = quality?.let { p.hrefs[it] } ?: return
        val at = exo?.position ?: pos
        if (serverIdx + 1 < servers.size) {
            serverIdx += 1
            say("Пробуем другой сервер…")
            exo?.load(Api.videoUrl(servers[serverIdx], href), at, p.subtitles, 0)
            return
        }
        val k = players.firstOrNull { it.team.id == p.team.id && it.isKodik } ?: players.firstOrNull { it.isKodik }
        if (k != null) { say("Видео не грузится — включаем Kodik"); player = k; startKodik(k, at); return }
        message = "Видео не загружается ($err)"; failed = true
    }

    /** Every 250 ms while the player is open. */
    fun tick() {
        if (seekUndo?.let { android.os.SystemClock.elapsedRealtime() >= it.expiresAt } == true) seekUndo = null
        if (loading || failed) return
        val e = engine ?: return
        pos = e.position; dur = e.duration; playing = e.isPlaying; buffering = e.isBuffering; ended = e.ended
        // Source race, second half: no picture 9 s after the start — the next source takes over (other mirror,
        // AnimeLib instead of AniLibria, Kodik last) instead of a spinner forever.
        exo?.let { x ->
            if (native && !x.firstFrame && x.loadedAt > 0 && x.loadedAt != startWatchdog && x.exo.playWhenReady && System.currentTimeMillis() - x.loadedAt > 9_000) {
                startWatchdog = x.loadedAt
                onNativeError("нет картинки 9 с")
                return
            }
        }
        // An accidental tap into the ending must remain undoable before auto-skip or marking watched.
        if (seekUndo != null) return
        val ep0 = episode
        // Opening / ending from every provider (AniLibria, AniSkip…) when the source's own marks miss one of them.
        val own = player?.segments.orEmpty()
        if (ep0 != null && (dur > 60 || (dur <= 0 && pos > 15)) && aniSkipAsked != ep0.id && (own.none { it.isOpening } || own.none { it.isEnding })) {
            aniSkipAsked = ep0.id
            scope.launch { val g = loadGeneration; val segments = ru.qmods.anixq.tv.data.SkipTimes.find(title, ep0.number, dur, own); if (g == loadGeneration && episode?.id == ep0.id) extraSegments = segments }
        }
        if (toast != null && System.currentTimeMillis() - toastAt > 2500) toast = null
        if (ep0 != null && dur > 60 && recapAsked != ep0.id) {
            recapAsked = ep0.id
            (title.malId ?: title.shikiId)?.let { mal -> scope.launch { val g = loadGeneration; val segment = ru.qmods.anixq.tv.data.AniSkip.recap(mal, ep0.number, dur); if (g == loadGeneration && episode?.id == ep0.id) recap = segment } }
        }
        if ((Store.autoSkipOpening || Store.marathon) && !recapSkipped && inRecap) skipRecap(auto = true)
        if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) { sleepAt = 0; pause(); say("Таймер сна: пауза") }
        if (sleepAfterEpisode && ended) { sleepAfterEpisode = false; say("Таймер сна: пауза") }
        if (ep0 != null && Store.danmaku && native && timedFor != ep0.id) {
            timedFor = ep0.id; timed = emptyList()
            scope.launch { val l = ru.qmods.anixq.tv.data.Api.timedComments(ep0.id); if (episode?.id == ep0.id) timed = l }
        }
        if (!hasNext && !ratePrompt && dur > 300 && (ended || inEnding || dur - pos <= 45) && title.statusId == 2
            && Store.rating(title.id) == null && !Store.ratePrompted(title.id)) {
            Store.markRatePrompted(title.id); ratePrompt = true
        }
        val op = opening
        if ((Store.autoSkipOpening || Store.marathon) && op != null && !openingSkipped && pos >= op.from && pos < op.to - 3) skipOpening(auto = true)
        val ed = ending
        // "Пропускать эндинг": straight to the next episode once the ending starts (the timer still wins).
        if (Store.autoSkipEnding && !Store.marathon && hasNext && ed != null && !endingSkipped && !breakPrompt && pos >= ed.from + 1 && pos < ed.to) {
            endingSkipped = true
            recordViewed()
            say("Эндинг пропущен")
            autoAdvance()
            return
        }
        if (Store.marathon && hasNext && ed != null && !endingSkipped && !breakPrompt && pos >= ed.from + 1 && pos < ed.to) {
            endingSkipped = true
            recordViewed()
            autoAdvance()
            return
        }
        if (dur > 60 && pos / dur >= 0.9) recordViewed(markShiki = true)
        // Next episode: where it is (right after this one starts), then its video queued 3 minutes before the end.
        if (hasNext && player?.isExternalEmbed != true && !nextLooked && native && pos > 15) {
            nextLooked = true
            val next = episodes[index + 1]
            scope.launch { runCatching { Api.players(next.id) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { playersCache[next.id] = it } }
        }
        if (hasNext && player?.isExternalEmbed != true && !prefetched && native && dur > 0 && dur - pos < 180) {
            prefetched = true
            val next = episodes[index + 1]
            val generation = loadGeneration
            val sourceEngine = exo
            scope.launch {
                runCatching {
                    val ps = playersCache[next.id] ?: AnimeSources.players(title, next).also { playersCache[next.id] = it }
                    var p = ps.firstOrNull { it.team.id == player?.team?.id && it.isNative }
                    // AniLibria now: queue their file of the next episode (the one the player will pick).
                    if (player?.isLibria == true) {
                        val base = p ?: ps.firstOrNull { it.team.id == player?.team?.id } ?: return@runCatching
                        p = ru.qmods.anixq.tv.data.Libria.player(title, next.number, base)
                    }
                    if (p == null) return@runCatching
                    val keys = p.hrefs.keys.sortedByDescending { qualityHeight(it) }
                    // The same quality the player will pick, so the queued file is exactly the one played next.
                    val q = quality?.takeIf { it in keys }
                        ?: keys.firstOrNull { (qualityHeight(it)) <= Store.preferredQuality } ?: keys.last()
                    val href = p.hrefs.getValue(q)
                    if (generation != loadGeneration || loading || sourceEngine !== exo) return@runCatching
                    if (href.startsWith("http")) { sourceEngine?.queueNext(href, p.subtitles, p.headers); return@runCatching }
                    val server = Api.rankServers(href).firstOrNull() ?: return@runCatching
                    if (generation == loadGeneration && !loading && sourceEngine === exo)
                        sourceEngine?.queueNext(Api.videoUrl(server, href), p.subtitles, p.headers)
                }
            }
        }
        watchStalls()
        saveProgress()
    }

    // Only sustained buffering counts; quality changes are opt-in and share a conservative cooldown.
    private fun watchStalls() {
        if (!native || loading || message != null) return
        exo?.setAutomaticQuality(quality.equals("auto", true) || Store.automaticQuality)
        val action = recovery.update(android.os.SystemClock.elapsedRealtime(),
            buffering && exo?.exo?.playWhenReady == true && pos > 2, Store.automaticQuality)
        if (action == StallRecovery.Action.NONE) return
        val p = player ?: return
        val keys = p.hrefs.keys.sortedByDescending { qualityHeight(it) }
        val i = keys.indexOf(quality)
        val href = quality?.let { p.hrefs[it] } ?: return
        if (href.startsWith("file:")) return
        if (action == StallRecovery.Action.LOWER_QUALITY && i >= 0 && i + 1 < keys.size) {
            say("Сеть долго нестабильна — включаем ${keys[i + 1]}")
            load(index, keepPos = pos, qualityKey = keys[i + 1])
            return
        }
        if (p.isLibria || p.isExternalEmbed) { onNativeError("stall"); return }
        if (servers.size < 2) return
        serverIdx = (serverIdx + 1) % servers.size
        say("Видео подвисло — пробуем другой сервер в том же качестве")
        exo?.load(Api.videoUrl(servers[serverIdx], href), pos, p.subtitles)
    }

    private fun savedPosition(ep: Episode) =
        if (PlaybackHistory.canRecord(title)) Store.resumeEpisode(title, ep) else null

    private fun recordViewed(markShiki: Boolean = false) {
        if (viewedSent || !PlaybackHistory.canRecord(title)) return
        val ep = episode ?: return
        val activePlayer = player
        viewedSent = true
        Store.markWatched(title, ep)
        if (activePlayer?.isExternalEmbed != true) {
            scope.launch { runCatching { Api.markViewed(ep.id, activePlayer?.playerId) } }
        }
        if (markShiki) Shiki.markEpisode(title.shikiId, ep.number)
    }

    fun saveProgress(force: Boolean = false) {
        if (loading || seekUndo != null || !PlaybackHistory.canRecord(title)) return
        val ep = episode ?: return
        val p = player ?: return
        if (dur <= 0 || pos < 3) return
        val now = System.currentTimeMillis()
        // Every 5 s while playing: the live channel carries it to the TV at once (the gist is written rarely).
        if (force || (playing && now - lastSaved > 5_000)) {
            lastSaved = now
            Store.updateProgress(title, ep, p.team, pos, dur, p)
        }
        if (force || (playing && now - lastRemote > 30_000)) {
            lastRemote = now
            val at = pos
            scope.launch { runCatching { if (!p.isExternalEmbed) Api.saveTimecode(ep.id, p.playerId, at) } }
        }
    }

    fun toggle() {
        if (loading) return
        val e = engine ?: return
        if (playing) { e.pause(); playing = false; saveProgress(force = true) } else { e.play(); playing = true }
        ru.qmods.anixq.tv.data.Room.sendState()
    }

    fun pause() { if (playing) toggle() }

    fun seekBy(d: Double) {
        seekTo(pos + d)
        if (d < 0) nextDismissed = false
    }

    fun seekTo(t: Double) {
        if (loading) return
        seekUndo = null
        val e = engine ?: return
        val target = t.coerceIn(0.0, if (dur > 0) dur - 1 else Double.MAX_VALUE)
        e.seek(target); pos = target
        ru.qmods.anixq.tv.data.Room.sendState()
    }

    /** Keep the exact pre-tap position independently of controls visibility or phone rotation. */
    fun seekFromTimeline(target: Double) {
        if (loading || dur <= 0) return
        val id = episode?.id ?: return
        val before = engine?.position ?: pos
        seekTo(target)
        seekUndo = SeekUndo(id, before, android.os.SystemClock.elapsedRealtime() + 8_000)
    }

    fun undoTimelineSeek() {
        val undo = seekUndo ?: return
        seekUndo = null
        if (loading || episode?.id != undo.episodeId || android.os.SystemClock.elapsedRealtime() >= undo.expiresAt) return
        seekTo(undo.position)
        nextDismissed = false
        say("Перемотка отменена")
    }

    fun skipOpening(auto: Boolean = false) {
        val op = opening ?: return
        engine?.seek(op.to); pos = op.to
        openingSkipped = true
        say(if (auto) "Опенинг пропущен" else "Пропущено")
    }

    fun next() { if (hasNext) load(index + 1) }
    fun prev() { if (hasPrev) load(index - 1) }

    fun changeSpeed(f: Float) { speed = f; exo?.exo?.setPlaybackSpeed(f); say(if (f == 1f) "Обычная скорость" else "Скорость ${f}×") }
    fun toggleSubtitles() { subtitlesOn = !subtitlesOn; Store.subtitles = subtitlesOn; exo?.setSubtitles(subtitlesOn); say(if (subtitlesOn) "Субтитры включены" else "Субтитры выключены") }
    fun changeQuality(q: String) { Store.preferredQuality = qualityHeight(q).takeIf { it != Int.MAX_VALUE && it > 0 } ?: Store.preferredQuality; load(index, keepPos = pos, qualityKey = q) }
    fun setTeam(id: Int) { players.firstOrNull { it.team.id == id }?.team?.name?.let { Store.useDub(it) }; load(index, teamId = id, keepPos = pos) }

    /** Colour: live while the GPU stage is in place; neutral ↔ tuned reloads at the same moment. */
    fun setPicture(p: ru.qmods.anixq.tv.ui.Picture, commit: Boolean) {
        exo?.allowVideoEffects()
        ru.qmods.anixq.tv.ui.ColorTuning.set(p)
        if (commit && native && exo?.colorNeedsReload() == true) load(index, keepPos = pos)
    }

    fun setSound(s: ru.qmods.anixq.tv.ui.SoundMode) { ru.qmods.anixq.tv.ui.SoundMode.set(s); say("Звук: ${s.title}") }

    /** Hand the episode over to the TV at this exact second. */
    fun sendToTv() {
        val ep = episode ?: return
        saveProgress(force = true)
        engine?.pause(); playing = false
        Remote.playOnTv(title, ep.id, player?.team?.id, pos)
    }

    fun release() {
        saveProgress(force = true)
        ++loadGeneration
        loadJob?.cancel()
        Sync.flush()
        exo?.release(); kodik?.release()
        exo = null; kodik = null
    }
}
