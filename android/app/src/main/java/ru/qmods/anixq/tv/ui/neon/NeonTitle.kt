package ru.qmods.anixq.tv.ui.neon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import ru.qmods.anixq.tv.data.*
import ru.qmods.anixq.tv.data.sources.AnimeSources
import ru.qmods.anixq.tv.data.sources.YummyAccount
import ru.qmods.anixq.tv.ui.*

/** Title page: the poster on a lit wall, info in neon, the episodes as stations on a metro line. */
@Composable
fun NeonTitle(nav: Nav, start: Title) {
    val version by Store.version.collectAsState()
    var detail by remember(start.slug) { mutableStateOf(TitleCache.detail[start.slug] ?: Spotlight.cached(start.slug)) }
    var episodes by remember(start.slug) { mutableStateOf(TitleCache.episodes[start.slug]) }
    var options by remember(start.slug) { mutableStateOf(TitleCache.teams[start.slug].orEmpty()) }
    var related by remember(start.slug) { mutableStateOf(TitleCache.related[start.slug].orEmpty()) }
    var similar by remember(start.slug) { mutableStateOf(TitleCache.similar[start.slug].orEmpty()) }
    var franchise by remember(start.slug) { mutableStateOf<List<Title>>(emptyList()) }
    var teamId by remember(start.slug) { mutableStateOf(Store.lastEntry(start.id)?.teamId) }
    var error by remember(start.slug) { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf<String?>(null) }
    var menuEpisode by remember { mutableStateOf<Episode?>(null) }
    val title = detail?.title ?: start
    val scope = "neon:title:${start.id}"
    val color = if (title.isOngoing) N.pink else N.cyan
    LaunchedEffect(start.slug, attempt) {
        loading = true; error = null
        coroutineScope {
            launch {
                try { Api.detail(start.slug).also { detail = it; TitleCache.detail[start.slug] = it } }
                catch (e: CancellationException) { throw e } catch (e: Exception) { if (detail == null) error = e.message }
            }
            launch { runCatching { Api.related(start.slug).filter { it.first.id != start.id } }.getOrNull()?.let { related = it; TitleCache.related[start.slug] = it } }
            launch { runCatching { Api.similar(start.slug) }.getOrNull()?.let { similar = it; TitleCache.similar[start.slug] = it } }
            try {
                val eps = Api.episodes(start.slug)
                episodes = eps; TitleCache.episodes[start.slug] = eps
                val target = resumePlan(start, eps)?.episode
                if (target != null) {
                    val players = AnimeSources.players(start, target).filter(AnimeSources::supported)
                    options = PlaybackChoices.options(players); TitleCache.teams[start.slug] = options
                    teamId = Store.playbackOption(start, options)?.team?.id ?: teamId?.takeIf { id -> options.any { it.team.id == id } }
                        ?: options.firstOrNull { o -> Store.dubs.any { PlaybackChoices.sameDub(it, o.team.name) } }?.team?.id ?: options.firstOrNull()?.team?.id
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Не удалось получить серии" }
            finally { loading = false }
        }
    }
    LaunchedEffect(title.shikiId) {
        title.shikiId?.let { id ->
            try {
                val nodes = Franchise.nodes(id)
                val main = nodes.filter { it.isMain }.ifEmpty { nodes }
                franchise = coroutineScope { main.map { n -> async { Franchise.title(n) } }.mapNotNull { it.await() } }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    LaunchedEffect(color) { NeonSky.tint = color }
    val plan = remember(episodes, version) { episodes?.let { resumePlan(title, it) } }
    val bookmark = remember(version) { Store.bookmark(start.slug) }
    val selected = options.firstOrNull { it.team.id == teamId } ?: options.firstOrNull()
    val sources = PlaybackChoices.sources(options)
    val balancers = PlaybackChoices.balancers(options, selected)
    val voices = PlaybackChoices.dubs(options, selected)
    fun choose(o: TeamOption) { teamId = o.team.id; Store.setPlaybackOption(title, o); Store.useDub(o.team.name) }

    Box(Modifier.fillMaxSize().background(N.night)) {
        NightCity(color)
        LazyColumn(Modifier.fillMaxSize().testTag("neon-title"), contentPadding = PaddingValues(bottom = 50.dp)) {
            item("hero") {
                Row(Modifier.fillMaxWidth().padding(start = 52.dp, end = 52.dp, top = 26.dp, bottom = 20.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NButton("Назад", Icons.AutoMirrored.Rounded.ArrowBack, color, modifier = Modifier.testTag("neon-title-back")) { nav.pop() }
                            Spacer(Modifier.width(18.dp))
                            Glow(when (title.statusId) { 1 -> "放送中 · В ЭФИРЕ"; 2 -> "完結 · ЗАВЕРШЕНО"; 3 -> "予告 · СКОРО"; else -> "アニメ" }, color, 11.sp, letterSpacing = 2.sp)
                            Spacer(Modifier.weight(1f)); NClock()
                        }
                        Glow(title.title, N.ink, 40.sp, Modifier.padding(top = 22.dp), maxLines = 2, lineHeight = 46.sp, glow = .45f)
                        (title.name ?: title.engName)?.takeIf { it != title.title }?.let { Text(it, color = N.muted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp)) }
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            title.rating?.takeIf { it > 0 }?.let { Glow("★ %.1f".format(it), N.amber, 14.sp) }
                            Text(listOfNotNull(title.year?.toString(), title.typeLabel, title.episodeInfo, title.age).joinToString("  ·  "), color = N.muted, fontSize = 12.sp)
                        }
                        detail?.genres?.takeIf { it.isNotEmpty() }?.let { g ->
                            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                g.take(5).forEach { Text(it, fontSize = 11.sp, color = color, modifier = Modifier.neonOutline(color, .35f, 10.dp).padding(horizontal = 10.dp, vertical = 4.dp)) }
                            }
                        }
                        Text(detail?.summary?.replace("\n", " ")?.trim().orEmpty(), color = N.muted, fontSize = 13.sp, lineHeight = 20.sp, maxLines = 3,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp).widthIn(max = 640.dp))
                        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (plan != null) NButton(plan.label, Icons.Rounded.PlayArrow, color, primary = true,
                                modifier = Modifier.memo(scope, "play", initial = true).testTag("neon-title-play")) {
                                nav.play(title, plan.episode.id, teamId, resume = Store.lastEntry(title.id) != null)
                            } else if (error != null) NButton("Повторить", Icons.Rounded.Refresh, color, modifier = Modifier.memo(scope, "retry", initial = true)) { attempt++ }
                            else Text(if (loading) "Ждём поезд…" else "Серий пока нет", color = N.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                            NButton(bookmark?.title ?: "В коллекцию", if (bookmark == null) Icons.Rounded.Add else Icons.Rounded.Check, N.violet,
                                modifier = Modifier.memo(scope, "bookmark")) { picker = "bookmark" }
                            if (!detail?.summary.isNullOrBlank()) NButton("О тайтле", Icons.Rounded.Notes, N.cyan) { Focus.reset("panorama:synopsis"); picker = "synopsis" }
                        }
                        plan?.sub?.let { Text(it, color = N.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp)) }
                        Recap.daysAway(start.id)?.let { d -> Glow("Прошло $d дн. — начнём на 30 секунд раньше", N.amber, 11.sp, Modifier.padding(top = 8.dp), glow = .4f) }
                    }
                    // Poster on the wall, slightly tilted, lit from below.
                    Box(Modifier.padding(start = 30.dp, top = 40.dp).width(230.dp).aspectRatio(.69f)
                        .graphicsLayer { rotationZ = 2.5f }.neonOutline(color, .9f, 10.dp).clip(RoundedCornerShape(10.dp))) {
                        Cover(title.poster, Modifier.fillMaxSize())
                    }
                }
            }
            if (options.isNotEmpty()) item("sources") {
                Row(Modifier.fillMaxWidth().padding(start = 52.dp, end = 52.dp, bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("Источник", selected?.sourceLabel.orEmpty(), Modifier.weight(1f), sources.size > 1, N.cyan) { picker = "source" }
                    Field("Балансер", selected?.balancerLabel.orEmpty(), Modifier.weight(1f), balancers.size > 1, N.violet) { picker = "balancer" }
                    Field(if (selected?.team?.isSubtitles == true) "Субтитры" else "Озвучка", selected?.dubLabel.orEmpty(), Modifier.weight(1.6f), voices.size > 1, N.pink) { picker = "voice" }
                }
            }
            error?.let { m -> item("error") { NMessage("Линия закрыта", m, "Повторить") { attempt++ } } }
            if (!YummyAccount.signedIn) item("yummy") { Text("Aksor доступен без входа, если серия открыта. Для остальных озвучек — вход в YummyAnime в настройках", color = N.muted, fontSize = 11.sp, modifier = Modifier.padding(start = 52.dp, bottom = 14.dp)) }
            episodes?.takeIf { it.isNotEmpty() }?.let { eps -> item("line") {
                MetroLine(title, eps, plan?.episode?.id, scope, color, onPlay = { nav.play(title, it.id, teamId) }, onMenu = { menuEpisode = it })
            } }
            episodes?.takeIf { it.size > 1 && options.size > 1 }?.let { eps -> item("dubs") {
                DubTable(title, eps, teamId, scope, N.pink, N.muted,
                    card = { onClick, mod, content -> NSurface(onClick, mod, N.violet) { content() } },
                    header = { NSign("声", "Какую озвучку выбрать", N.violet, Modifier.padding(start = 52.dp, end = 52.dp, bottom = 6.dp)) }) { id ->
                    options.firstOrNull { it.team.id == id }?.let(::choose)
                }
            } }
            if (franchise.size > 1) item("franchise") { NRow("系", "Порядок просмотра", franchise, scope, nav, N.violet) }
            if (related.isNotEmpty()) item("related") { NRow("連", "Другие части", related.map { it.first }, scope, nav, N.cyan) }
            if (similar.isNotEmpty()) item("similar") { NRow("似", "В том же настроении", similar, scope, nav, N.pink) }
        }
    }
    when (picker) {
        "source" -> ChoiceDialog("Источник", sources.map { Choice(it.label, it.key == selected?.source) }, { picker = null }) { picker = null; PlaybackChoices.chooseSource(options, selected, sources[it].key)?.let(::choose) }
        "balancer" -> ChoiceDialog("Балансер", balancers.map { Choice(it.label, it.key == selected?.balancerKey) }, { picker = null }) { picker = null; PlaybackChoices.chooseBalancer(options, selected, balancers[it].key)?.let(::choose) }
        "voice" -> ChoiceDialog("Озвучка", voices.map { Choice(it.dubLabel, it.team.id == selected?.team?.id, if (it.team.isSubtitles) "Субтитры" else null) }, { picker = null }) { picker = null; choose(voices[it]) }
        "bookmark" -> ChoiceDialog("Ваша коллекция", Bookmark.entries.map { Choice(it.title, it == bookmark) } + if (bookmark != null) listOf(Choice("Убрать из коллекции")) else emptyList(), { picker = null }) {
            picker = null; Store.setBookmark(title, Bookmark.entries.getOrNull(it))
        }
        "synopsis" -> Dialog(onDismissRequest = { picker = null }) {
            Column(Modifier.width(650.dp).neonOutline(color, .8f, 16.dp).background(N.panel, RoundedCornerShape(16.dp)).padding(28.dp)) {
                Glow(title.title, N.ink, 25.sp, maxLines = 2, glow = .4f)
                ru.qmods.anixq.tv.ui.panorama.SynopsisBody(detail?.summary.orEmpty())
                NButton("Закрыть", color = color, modifier = Modifier.memo("panorama:synopsis", "close")) { picker = null }
            }
        }
    }
    menuEpisode?.let { ep ->
        val resume = Store.resume(ep.id)
        val watched = Store.isWatched(title.id, ep.number) || Store.progress(ep.id) > .92
        val choices = listOf("play" to Choice(if (resume != null) "Продолжить с ${fmt(resume)}" else "Смотреть")) +
            (if (resume != null || watched) listOf("restart" to Choice("Смотреть с начала")) else emptyList()) +
            (if (!watched) listOf("watched" to Choice("Отметить просмотренной")) else emptyList())
        ChoiceDialog("Станция ${ep.number}", choices.map { it.second }, { menuEpisode = null }) { i ->
            menuEpisode = null
            when (choices[i].first) {
                "restart" -> nav.push(PlayerRoute(title, ep.id, teamId, fromStart = true))
                "watched" -> Store.markWatched(title, ep)
                else -> nav.play(title, ep.id, teamId)
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier, enabled: Boolean, color: Color, onClick: () -> Unit) {
    val body: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label.uppercase(), fontSize = 9.sp, letterSpacing = 2.sp, color = color)
                Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            if (enabled) Icon(Icons.Rounded.UnfoldMore, null, Modifier.size(16.dp), tint = color)
        }
    }
    if (enabled) NSurface(onClick, modifier, color, content = body) else Box(modifier.background(N.panel.copy(alpha = .8f), RoundedCornerShape(12.dp))) { body() }
}

/**
 * Episodes as a metro line: stations on a glowing track. Passed stations are filled, the next one pulses,
 * the focused one shows its name in a sign above. Ranges of long series jump like express trains.
 */
@Composable
private fun MetroLine(title: Title, episodes: List<Episode>, current: Int?, scope: String, color: Color, onPlay: (Episode) -> Unit, onMenu: (Episode) -> Unit) {
    val version by Store.version.collectAsState()
    val list = rememberLazyListState((episodes.indexOfFirst { it.id == current } - 2).coerceAtLeast(0))
    val co = rememberCoroutineScope()
    Column(Modifier.padding(bottom = 26.dp)) {
        NSign("線", "Линия серий · ${episodes.size} ${if (episodes.size == 1) "станция" else "станций"}", color, Modifier.padding(start = 52.dp, end = 52.dp, bottom = 6.dp)) {
            if (episodes.size > 30) Row(Modifier.padding(start = 12.dp).widthIn(max = 560.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                episodes.indices.chunked(if (episodes.size > 400) 100 else 25).forEach { r ->
                    NButton("${episodes[r.first()].number}–${episodes[r.last()].number}", color = N.amber) { co.launch { list.animateScrollToItem(r.first()) } }
                }
            }
        }
        Pivot { LazyRow(state = list, contentPadding = PaddingValues(horizontal = 52.dp, vertical = 10.dp)) {
            itemsIndexed(episodes, key = { _, e -> e.id }) { i, ep ->
                val progress = remember(version) { Store.progress(ep.id) }
                val watched = remember(version) { Store.isWatched(title.id, ep.number) || progress > .92 }
                var focused by remember { mutableStateOf(false) }
                val light = flicker(focused)
                Column(Modifier.width(118.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    // Station sign: the episode name appears when focused.
                    Box(Modifier.height(44.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        if (focused) Text(Spoilers.title(ep, watched), fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
                            color = N.ink.copy(alpha = light), modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            val y = size.height / 2
                            val passed = watched || progress > .02
                            if (i > 0) drawLine(if (passed) color else N.line, Offset(0f, y), Offset(size.width / 2, y), 6f)
                            if (i < episodes.lastIndex) drawLine(if (watched) color else N.line, Offset(size.width / 2, y), Offset(size.width, y), 6f)
                            if (watched || ep.id == current) for (k in 3 downTo 1) drawLine(color.copy(alpha = .07f * k), Offset(0f, y), Offset(size.width, y), 6f + k * 6f)
                        }
                        NSurface({ onPlay(ep) }, Modifier.size(40.dp).memo(scope, "station/${ep.id}", initial = ep.id == current).testTag("neon-station-${ep.id}"),
                            color, selected = ep.id == current, onLongClick = { onMenu(ep) }, onFocus = { focused = it }, radius = 20.dp) {
                            Box(Modifier.fillMaxSize().background(if (watched) color.copy(alpha = .85f) else Color.Transparent), contentAlignment = Alignment.Center) {
                                Text(ep.number, fontSize = if (ep.number.length > 3) 10.sp else 13.sp, fontWeight = FontWeight.Black, color = if (watched) N.night else N.ink, maxLines = 1)
                            }
                        }
                    }
                    Text(when { ep.id == current -> "СЛЕДУЮЩАЯ"; watched -> "пройдена"; progress > .02 -> "${(progress * 100).toInt()}%"; else -> "" },
                        fontSize = 9.sp, letterSpacing = 1.sp, color = if (ep.id == current) color else N.muted, modifier = Modifier.padding(top = 4.dp))
                }
            }
        } }
    }
}
