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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.qmods.anixq.tv.data.AniSkip
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.Episode
import ru.qmods.anixq.tv.data.Player
import ru.qmods.anixq.tv.data.Remote
import ru.qmods.anixq.tv.data.Segment
import ru.qmods.anixq.tv.data.Shiki
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Sync
import ru.qmods.anixq.tv.data.Title
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
        .put("episodeNumber", episode?.number ?: "").put("teamId", player?.team?.id ?: 0).put("team", player?.team?.name ?: "")
        .put("playing", playing).put("position", pos).put("rate", speed.toDouble())
        .apply {
            // AniLibria's HLS: the web page finds it by itself (and plays it with hls.js), so no "stream" here.
            if (native && player?.isLibria != true) currentUrl?.takeIf { it.startsWith("http") }?.let { put("stream", it) }
            val k = if (!native) currentUrl else players.firstOrNull { it.team.id == player?.team?.id && it.isKodik }?.kodikSrc?.let { Api.kodikUrl(it) }
                ?: players.firstOrNull { it.isKodik }?.kodikSrc?.let { Api.kodikUrl(it) }
            k?.let { put("kodik", it) }
        }

    override fun roomApply(m: org.json.JSONObject) {
        if (m.optString("slug") != title.slug) { say("В комнате смотрят «${m.optString("titleName")}»"); return }
        val eid = m.optInt("episodeId"); val tid = m.optInt("teamId")
        if (eid != episode?.id) {
            val i = episodes.indexOfFirst { it.id == eid }
            if (i >= 0) load(i, teamId = tid.takeIf { it > 0 }, keepPos = m.optDouble("position", 0.0))
            return
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
    var speed by mutableFloatStateOf(1f)
    var subtitlesOn by mutableStateOf(Store.subtitles)

    var openingSkipped by mutableStateOf(false)
    var nextDismissed by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    private var toastAt by mutableLongStateOf(0L)

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
    private var currentUrl: String? = null

    val engine: Engine? get() = if (native) exo else kodik
    val episode: Episode? get() = episodes.getOrNull(index)
    val hasNext get() = index + 1 < episodes.size
    val hasPrev get() = index > 0
    var extraSegments by mutableStateOf<List<Segment>>(emptyList())
    private var aniSkipAsked = -1
    val segments: List<Segment> get() = player?.segments?.takeIf { it.isNotEmpty() } ?: extraSegments
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
    val showNext get() = hasNext && !nextDismissed && dur > 0 && (ended || inEnding || (ending == null && remaining <= 90) || remaining <= 15)
    val countdown get() = (Store.autoNext || Store.marathon) && !breakPrompt && (ended || inEnding || remaining <= 12)
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
    val qualities: List<String> get() = player?.hrefs?.keys?.sortedByDescending { it.removeSuffix("p").toIntOrNull() ?: 0 } ?: emptyList()

    fun say(s: String) { toast = s; toastAt = System.currentTimeMillis() }

    fun start() {
        scope.launch {
            try {
                Sync.pullFresh()
                // Offline: the episode list saved with the downloads.
                val eps = runCatching { Api.episodes(title.slug) }.getOrNull()
                    ?: ru.qmods.anixq.mobile.data.Downloads.episodes(title.id) ?: throw Exception("Нет связи с AnimeLib")
                if (eps.isEmpty()) { message = "Серий нет"; failed = true; return@launch }
                episodes = eps
                val latest = if (route.resume) Store.lastEntry(title.id) else null
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
        saveProgress(force = true)
        loadJob?.cancel()
        val sameEpisode = i == index && episodes.isNotEmpty() && keepPos != null
        index = i
        val ep = episodes[i]
        if (!sameEpisode) { avoidLibria = false; recap = null; recapSkipped = false; endingSkipped = false; extraSegments = emptyList(); openingSkipped = false; nextDismissed = false; viewedSent = false; prefetched = false; nextLooked = false; pos = 0.0; dur = 0.0; ended = false }
        message = if (sameEpisode) "Переключаем…" else "Серия ${ep.number}"
        failed = false
        buffering = true
        loadJob = scope.launch {
            try {
                // A downloaded episode plays from the phone: instant, no traffic, works offline.
                val offline = ru.qmods.anixq.mobile.data.Downloads.offlinePlayer(ep.id)
                val list = if (sameEpisode && players.isNotEmpty()) players else offline?.let { listOf(it) } ?: playersCache.remove(ep.id) ?: Api.players(ep.id)
                if (offline != null && !sameEpisode) say("Скачанная серия — без интернета")
                players = list
                if (list.isEmpty()) { message = "Для этой серии нет видео"; failed = true; return@launch }
                val forTeam = { id: Int? -> list.filter { it.team.id == id } }
                val favourite = list.firstOrNull { p -> Store.dubs.any { it.equals(p.team.name, true) } }?.team?.id
                val candidates = forTeam(teamId).ifEmpty { forTeam(favourite) }.ifEmpty { list.filter { it.isNative }.ifEmpty { list } }
                val first = (if (preferKodik) candidates.firstOrNull { it.isKodik } else null)
                    ?: candidates.firstOrNull { it.isNative } ?: candidates.firstOrNull { it.isKodik }
                    ?: list.firstOrNull { it.isNative } ?: list.firstOrNull { it.isKodik || it.isNative }
                if (first == null) { message = "Для этой серии нет видео"; failed = true; return@launch }
                var chosen: Player = first
                // AniLibria dub: their own servers when they have the episode (no ads, often faster than AnimeLib).
                if (offline == null && !preferKodik && !avoidLibria && Store.libriaDirect && ru.qmods.anixq.tv.data.Libria.isLibria(chosen.team.name)) {
                    ru.qmods.anixq.tv.data.Libria.player(title, ep.number, chosen, probeAt = keepPos ?: Store.resume(ep.id) ?: 0.0)?.let { chosen = it; if (!sameEpisode) say("AniLibria: видео с их сервера") }
                }
                player = chosen
                val local = Store.resume(ep.id)
                val remote = chosen.serverTimecode?.takeIf { it > 5 && (route.episodeId != ep.id || local == null) }
                var startAt = keepPos ?: (if (!startedOnce) route.startAt else null) ?: local ?: remote ?: 0.0
                var recapNote: String? = null
                if (keepPos == null && route.startAt == null) ru.qmods.anixq.tv.data.Recap.adjust(title.id, ep.id, startAt).let { (a, n) -> startAt = a; recapNote = n }
                startedOnce = true
                if (chosen.isNative) startNative(chosen, startAt, qualityKey) else startKodik(chosen, startAt)
                ru.qmods.anixq.tv.data.Room.sendState()
                if (recapNote != null) say(recapNote!!) else if (startAt > 5 && keepPos == null) say("Продолжаем с ${fmt(startAt)}")
                message = null
            } catch (e: Exception) {
                message = e.message ?: "Не удалось загрузить серию"; failed = true
            }
        }
    }

    private suspend fun startNative(p: Player, startAt: Double, qualityKey: String?) {
        val keys = p.hrefs.keys.sortedByDescending { it.removeSuffix("p").toIntOrNull() ?: 0 }
        // Keeps the quality of the previous episode (so the file queued in advance is the one played).
        val q = qualityKey?.takeIf { it in keys } ?: quality?.takeIf { it in keys }
            ?: keys.firstOrNull { (it.removeSuffix("p").toIntOrNull() ?: 0) <= Store.preferredQuality } ?: keys.last()
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
        e.load(currentUrl!!, startAt, p.subtitles, 0)
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
    private var avoidLibria = false

    private fun onNativeError(err: String) {
        val p = player ?: return
        if (p.isLibria) {
            avoidLibria = true
            ru.qmods.anixq.tv.data.Libria.markFailed()
            say("AniLibria не отвечает — берём видео с AnimeLib")
            load(index, teamId = p.team.id, keepPos = exo?.position ?: pos)
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
        val e = engine ?: return
        pos = e.position; dur = e.duration; playing = e.isPlaying; buffering = e.isBuffering; ended = e.ended
        val ep0 = episode
        if (ep0 != null && dur > 60 && aniSkipAsked != ep0.id && player?.segments.isNullOrEmpty()) {
            aniSkipAsked = ep0.id
            title.shikiId?.let { mal -> scope.launch { extraSegments = AniSkip.segments(mal, ep0.number, dur) } }
        }
        if (toast != null && System.currentTimeMillis() - toastAt > 2500) toast = null
        if (ep0 != null && dur > 60 && recapAsked != ep0.id) {
            recapAsked = ep0.id
            title.shikiId?.let { mal -> scope.launch { recap = ru.qmods.anixq.tv.data.AniSkip.recap(mal, ep0.number, dur) } }
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
            if (!viewedSent) episode?.let { ep -> viewedSent = true; Store.markWatched(title, ep); scope.launch { runCatching { Api.markViewed(ep.id, player?.playerId) } } }
            say("Эндинг пропущен")
            autoAdvance()
            return
        }
        if (Store.marathon && hasNext && ed != null && !endingSkipped && !breakPrompt && pos >= ed.from + 1 && pos < ed.to) {
            endingSkipped = true
            if (!viewedSent) episode?.let { ep -> viewedSent = true; Store.markWatched(title, ep); scope.launch { runCatching { Api.markViewed(ep.id, player?.playerId) } } }
            autoAdvance()
            return
        }
        if (!viewedSent && dur > 60 && pos / dur >= 0.9) {
            viewedSent = true
            episode?.let { ep ->
                Store.markWatched(title, ep)
                scope.launch { runCatching { Api.markViewed(ep.id, player?.playerId) } }
                Shiki.markEpisode(title.shikiId, ep.number)
            }
        }
        // Next episode: where it is (right after this one starts), then its video queued 3 minutes before the end.
        if (hasNext && !nextLooked && native && pos > 15) {
            nextLooked = true
            val next = episodes[index + 1]
            scope.launch { runCatching { Api.players(next.id) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { playersCache[next.id] = it } }
        }
        if (hasNext && !prefetched && native && dur > 0 && dur - pos < 180) {
            prefetched = true
            val next = episodes[index + 1]
            scope.launch {
                runCatching {
                    val ps = playersCache[next.id] ?: Api.players(next.id).also { playersCache[next.id] = it }
                    var p = ps.firstOrNull { it.team.id == player?.team?.id && it.isNative }
                    // AniLibria now: queue their file of the next episode (the one the player will pick).
                    if (player?.isLibria == true) {
                        val base = p ?: ps.firstOrNull { it.team.id == player?.team?.id } ?: return@runCatching
                        p = ru.qmods.anixq.tv.data.Libria.player(title, next.number, base)
                    }
                    if (p == null) return@runCatching
                    val keys = p.hrefs.keys.sortedByDescending { it.removeSuffix("p").toIntOrNull() ?: 0 }
                    // The same quality the player will pick, so the queued file is exactly the one played next.
                    val q = quality?.takeIf { it in keys }
                        ?: keys.firstOrNull { (it.removeSuffix("p").toIntOrNull() ?: 0) <= Store.preferredQuality } ?: keys.last()
                    val href = p.hrefs.getValue(q)
                    if (p.isLibria) { exo?.queueNext(href, p.subtitles); return@runCatching }
                    val server = Api.rankServers(href).firstOrNull() ?: return@runCatching
                    exo?.queueNext(Api.videoUrl(server, href), p.subtitles)
                }
            }
        }
        watchStalls()
        saveProgress()
    }

    // Bad network: a stall of 8 s → another mirror from the same moment; two stalls in 90 s → one quality step down.
    private var stallSince = 0L
    private val stalls = ArrayList<Long>()
    private var lastQualityDrop = 0L

    private fun watchStalls() {
        if (!native || message != null) { stallSince = 0; return }
        val now = System.currentTimeMillis()
        val stalled = buffering && (exo?.exo?.playWhenReady == true) && pos > 2
        if (!stalled) { stallSince = 0; return }
        if (stallSince == 0L) {
            stallSince = now
            stalls.removeAll { now - it > 90_000 }
            stalls.add(now)
            val p = player ?: return
            val keys = p.hrefs.keys.sortedByDescending { it.removeSuffix("p").toIntOrNull() ?: 0 }
            val i = keys.indexOf(quality)
            if (stalls.size >= 2 && i >= 0 && i + 1 < keys.size && now - lastQualityDrop > 60_000 && !p.hrefs.getValue(keys[i]).startsWith("file:")) {
                lastQualityDrop = now; stalls.clear()
                say("Сеть медленная — включаем ${keys[i + 1]}")
                load(index, keepPos = pos, qualityKey = keys[i + 1])
            }
            return
        }
        if (now - stallSince > 8_000) {
            stallSince = 0
            val p = player ?: return
            val href = quality?.let { p.hrefs[it] } ?: return
            if (p.isLibria) { onNativeError("stall"); return }
            if (href.startsWith("file:") || servers.size < 2) return
            serverIdx = (serverIdx + 1) % servers.size
            say("Видео подвисло — переподключаемся")
            exo?.load(Api.videoUrl(servers[serverIdx], href), pos, p.subtitles)
        }
    }

    fun saveProgress(force: Boolean = false) {
        val ep = episode ?: return
        val p = player ?: return
        if (dur <= 0 || pos < 3) return
        val now = System.currentTimeMillis()
        // Every 5 s while playing: the live channel carries it to the TV at once (the gist is written rarely).
        if (force || (playing && now - lastSaved > 5_000)) {
            lastSaved = now
            Store.updateProgress(title, ep, p.team, pos, dur)
        }
        if (force || (playing && now - lastRemote > 30_000)) {
            lastRemote = now
            val at = pos
            scope.launch { runCatching { Api.saveTimecode(ep.id, p.playerId, at) } }
        }
    }

    fun toggle() {
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
        val e = engine ?: return
        val target = t.coerceIn(0.0, if (dur > 0) dur - 1 else Double.MAX_VALUE)
        e.seek(target); pos = target
        ru.qmods.anixq.tv.data.Room.sendState()
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
    fun changeQuality(q: String) { Store.preferredQuality = q.removeSuffix("p").toIntOrNull() ?: Store.preferredQuality; load(index, keepPos = pos, qualityKey = q) }
    fun setTeam(id: Int) { players.firstOrNull { it.team.id == id }?.team?.name?.let { Store.useDub(it) }; load(index, teamId = id, keepPos = pos) }

    /** Colour: live while the GPU stage is in place; neutral ↔ tuned reloads at the same moment. */
    fun setPicture(p: ru.qmods.anixq.tv.ui.Picture, commit: Boolean) {
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
        Sync.flush()
        exo?.release(); kodik?.release()
        exo = null; kodik = null
    }
}
