package ru.qmods.anixq.tv.ui.panorama

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun PanoramaTitle(nav: Nav, start: Title) {
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
    val focusScope = "panorama:title:${start.id}"
    LaunchedEffect(start.slug, attempt) {
        loading = true; error = null
        coroutineScope {
            launch {
                try { Api.detail(start.slug).also { detail = it; TitleCache.detail[start.slug] = it } }
                catch(e: CancellationException) { throw e } catch(e: Exception) { if (detail == null) error = e.message }
            }
            launch { Api.related(start.slug).filter { it.first.id != start.id }.also { related = it; TitleCache.related[start.slug] = it } }
            launch { Api.similar(start.slug).also { similar = it; TitleCache.similar[start.slug] = it } }
            try {
                val eps = Api.episodes(start.slug)
                episodes = eps; TitleCache.episodes[start.slug] = eps
                val target = resumePlan(start, eps)?.episode
                if (target != null) {
                    val players = AnimeSources.players(start, target).filter(AnimeSources::supported)
                    options = PlaybackChoices.options(players); TitleCache.teams[start.slug] = options
                    teamId = Store.playbackOption(start, options)?.team?.id ?: teamId?.takeIf { id -> options.any { it.team.id == id } }
                        ?: options.firstOrNull { option -> Store.dubs.any { PlaybackChoices.sameDub(it, option.team.name) } }?.team?.id
                        ?: options.firstOrNull()?.team?.id
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { error = e.message ?: "Не удалось получить серии" }
            finally { loading = false }
        }
    }
    LaunchedEffect(title.shikiId) {
        title.shikiId?.let { id ->
            try {
                val nodes = Franchise.nodes(id)
                val main = nodes.filter { it.isMain }.ifEmpty { nodes }
                franchise = coroutineScope { main.map { node -> async { Franchise.title(node) } }.mapNotNull { it.await() } }
            } catch(e: CancellationException) { throw e } catch(_: Exception) { }
        }
    }
    val plan = remember(episodes, version) { episodes?.let { resumePlan(title, it) } }
    val bookmark = remember(version) { Store.bookmark(start.slug) }
    val selected = options.firstOrNull { it.team.id == teamId } ?: options.firstOrNull()
    val sources = PlaybackChoices.sources(options)
    val balancers = PlaybackChoices.balancers(options, selected)
    val voices = PlaybackChoices.dubs(options, selected)
    fun choose(option: TeamOption) { teamId = option.team.id; Store.setPlaybackOption(title, option); Store.useDub(option.team.name) }
    val scroll = rememberLazyListState()
    LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().background(P.bg).testTag("panorama-title"), contentPadding = PaddingValues(bottom = 50.dp)) {
        item("hero") {
            Box(Modifier.fillMaxWidth().heightIn(min = 355.dp)) {
                Cover(detail?.background ?: title.poster, Modifier.align(Alignment.TopEnd).width(790.dp).height(365.dp))
                Box(Modifier.fillMaxWidth().height(375.dp).background(Brush.horizontalGradient(0f to P.bg, .42f to P.bg.copy(alpha = .97f), 1f to P.bg.copy(alpha = .1f))))
                Box(Modifier.fillMaxWidth().height(375.dp).background(Brush.verticalGradient(.5f to Color.Transparent, 1f to P.bg)))
                Column(Modifier.padding(start = 52.dp, end = 52.dp, top = 24.dp, bottom = 24.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        PButton("Назад", Icons.AutoMirrored.Rounded.ArrowBack, modifier = Modifier.testTag("panorama-title-back")) { nav.pop() }
                        Eyebrow(if (title.slug.startsWith("yummy:")) "YUMMYANIME / ANIXQ" else "ANIMELIB / ANIXQ", Modifier.weight(1f).padding(start = 20.dp))
                        PClock()
                    }
                    Column(Modifier.padding(top = 25.dp).width(700.dp)) {
                        Eyebrow(when (title.statusId) { 1 -> "В ЭФИРЕ"; 2 -> "ЗАВЕРШЕНО"; 3 -> "СКОРО"; else -> "АНИМЕ" })
                        Text(title.title, color = P.ink, fontSize = 39.sp, lineHeight = 43.sp, fontWeight = FontWeight.Medium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        Text((title.name ?: title.engName).orEmpty().takeIf { it != title.title }.orEmpty(), color = P.muted, fontSize = 12.sp,
                            maxLines = 1, modifier = Modifier.padding(top = 7.dp))
                        Box(Modifier.padding(top = 12.dp)) { PMeta(title) }
                        detail?.genres?.takeIf { it.isNotEmpty() }?.let { Text(it.take(5).joinToString(" / "), color = P.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)) }
                        Text(detail?.summary?.replace("\n", " ")?.trim().orEmpty(), color = P.muted, fontSize = 13.sp, lineHeight = 20.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
                        Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (plan != null) PButton(plan.label, Icons.Rounded.PlayArrow, primary = true,
                                modifier = Modifier.memo(focusScope, "play", initial = true).testTag("panorama-title-play")) {
                                nav.play(title, plan.episode.id, teamId, resume = Store.lastEntry(title.id) != null)
                            } else if (error != null) PButton("Повторить", modifier = Modifier.memo(focusScope, "retry", initial = true)) { attempt++ }
                            else if (loading) Text("Загружаем серии…", color = P.muted, fontSize = 14.sp)
                            else Text("Серий пока нет", color = P.muted, fontSize = 14.sp)
                            PButton(bookmark?.title ?: "В коллекцию", if (bookmark == null) Icons.Rounded.Add else Icons.Rounded.Check,
                                modifier = Modifier.memo(focusScope, "bookmark").testTag("panorama-title-bookmark")) { picker = "bookmark" }
                            if (!detail?.summary.isNullOrBlank()) PButton("О тайтле", Icons.Rounded.Notes) { Focus.reset("panorama:synopsis"); picker = "synopsis" }
                        }
                        plan?.sub?.let { Text(it, color = P.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp)) }
                        Recap.daysAway(start.id)?.let { days -> Text("Прошло $days дн. — начнём на 30 секунд раньше", color = P.accent, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)) }
                    }
                }
            }
        }
        if (options.isNotEmpty()) item("sources") {
            Row(Modifier.fillMaxWidth().padding(start = 52.dp, end = 52.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PChoiceField("Источник", selected?.sourceLabel.orEmpty(), Modifier.weight(1f).testTag("panorama-title-source"), sources.size > 1) { picker = "source" }
                PChoiceField("Балансер", selected?.balancerLabel.orEmpty(), Modifier.weight(1f), balancers.size > 1) { picker = "balancer" }
                PChoiceField(if (selected?.team?.isSubtitles == true) "Субтитры" else "Озвучка", selected?.dubLabel.orEmpty(), Modifier.weight(1.6f), voices.size > 1) { picker = "voice" }
            }
        }
        error?.let { message -> item("error") { PMessage("Источник сообщил об ошибке", message, "Повторить") { attempt++ } } }
        if (!YummyAccount.signedIn) item("yummy-hint") {
            Text("Больше озвучек YummyAnime — после входа в настройках", color = P.muted, fontSize = 11.sp, modifier = Modifier.padding(start = 52.dp, bottom = 16.dp))
        }
        episodes?.takeIf { it.isNotEmpty() }?.let { eps -> item("episodes") {
            PanoramaEpisodes(title, eps, plan?.episode?.id, focusScope, onPlay = { nav.play(title, it.id, teamId) }, onMenu = { menuEpisode = it })
        } }
        if (franchise.size > 1) item("franchise") { PTitleRow("Порядок просмотра", franchise, focusScope, nav) }
        if (related.isNotEmpty()) item("related") { PTitleRow("Другие части", related.map { it.first }, focusScope, nav) }
        if (similar.isNotEmpty()) item("similar") { PTitleRow("Останьтесь в этом настроении", similar, focusScope, nav) }
    }
    when(picker) {
        "source" -> ChoiceDialog("Источник", sources.map { Choice(it.label, it.key == selected?.source) }, { picker = null }) { picker = null; PlaybackChoices.chooseSource(options, selected, sources[it].key)?.let(::choose) }
        "balancer" -> ChoiceDialog("Балансер", balancers.map { Choice(it.label, it.key == selected?.balancerKey) }, { picker = null }) { picker = null; PlaybackChoices.chooseBalancer(options, selected, balancers[it].key)?.let(::choose) }
        "voice" -> ChoiceDialog("Озвучка", voices.map { Choice(it.dubLabel, it.team.id == selected?.team?.id, if (it.team.isSubtitles) "Субтитры" else null) }, { picker = null }) { picker = null; choose(voices[it]) }
        "bookmark" -> ChoiceDialog("Ваша коллекция", Bookmark.entries.map { Choice(it.title, it == bookmark) } + if (bookmark != null) listOf(Choice("Убрать из коллекции")) else emptyList(), { picker = null }) {
            picker = null; Store.setBookmark(title, Bookmark.entries.getOrNull(it))
        }
        "synopsis" -> Dialog(onDismissRequest = { picker = null }) {
            Column(Modifier.width(650.dp).background(P.panel, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(28.dp)) {
                Text(title.title, color = P.ink, fontSize = 25.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                SynopsisBody(detail?.summary.orEmpty())
                PButton("Закрыть", modifier = Modifier.memo("panorama:synopsis", "close")) { picker = null }
            }
        }
    }
    menuEpisode?.let { ep ->
        val resume = Store.resume(ep.id)
        val watched = Store.isWatched(title.id, ep.number) || Store.progress(ep.id) > .92
        val choices = listOf("play" to Choice(if (resume != null) "Продолжить с ${fmt(resume)}" else "Смотреть")) +
            (if (resume != null || watched) listOf("restart" to Choice("Смотреть с начала")) else emptyList()) +
            (if (!watched) listOf("watched" to Choice("Отметить просмотренной")) else emptyList())
        ChoiceDialog("Серия ${ep.number}", choices.map { it.second }, { menuEpisode = null }) { index ->
            menuEpisode = null
            when(choices[index].first) {
                "restart" -> nav.push(PlayerRoute(title, ep.id, teamId, fromStart = true))
                "watched" -> Store.markWatched(title, ep)
                else -> nav.play(title, ep.id, teamId)
            }
        }
    }
}

@Composable
private fun PChoiceField(label: String, value: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label.uppercase(), fontSize = 9.sp, letterSpacing = 1.sp, color = P.muted)
                Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
            }
            if (enabled) Icon(Icons.Rounded.UnfoldMore, null, Modifier.size(16.dp))
        }
    }
    if (enabled) PSurface(onClick, modifier, content = content)
    else Box(modifier.background(P.panel, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))) { content() }
}

@Composable
private fun PanoramaEpisodes(title: Title, episodes: List<Episode>, current: Int?, scope: String, onPlay: (Episode) -> Unit, onMenu: (Episode) -> Unit) {
    val version by Store.version.collectAsState()
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (episodes.indexOfFirst { it.id == current } - 1).coerceAtLeast(0))
    val coroutine = rememberCoroutineScope()
    Column(Modifier.padding(bottom = 30.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 52.dp, end = 52.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Серии", color = P.ink, fontSize = 23.sp, fontWeight = FontWeight.Medium)
            Text("  /  ${episodes.size}", color = P.muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (episodes.size > 30) Row(Modifier.widthIn(max = 700.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                episodes.indices.chunked(if (episodes.size > 400) 100 else 25).forEach { range -> PButton("${episodes[range.first()].number}–${episodes[range.last()].number}") {
                    coroutine.launch { list.animateScrollToItem(range.first()) }
                } }
            }
        }
        Pivot { LazyRow(state = list, contentPadding = PaddingValues(horizontal = 52.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(episodes, key = { it.id }) { ep ->
                val progress = remember(version) { Store.progress(ep.id) }
                val watched = remember(version) { Store.isWatched(title.id, ep.number) || progress > .92 }
                PSurface({ onPlay(ep) }, Modifier.width(198.dp).height(111.dp).memo(scope, "episode/${ep.id}").testTag("panorama-episode-${ep.id}"),
                    selected = ep.id == current, onLongClick = { onMenu(ep) }) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ep.number, fontSize = 28.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
                            if (watched) Icon(Icons.Rounded.CheckCircle, "Просмотрено", Modifier.size(17.dp))
                            else if (ep.id == current) Text("ДАЛЕЕ", fontSize = 8.sp, letterSpacing = 1.sp)
                        }
                        Text(Spoilers.title(ep, watched), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, modifier = Modifier.padding(top = 9.dp))
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.fillMaxWidth().height(2.dp).background(P.muted.copy(alpha = .25f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(if (watched) 1f else progress.toFloat().coerceIn(0f, 1f)).background(P.accent))
                        }
                    }
                }
            }
        } }
    }
}


/** D-pad scrolls long descriptions, then can leave the body for the Close button. */
@Composable
internal fun SynopsisBody(text: String) {
    val synopsis = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    LazyColumn(state = synopsis, modifier = Modifier.heightIn(max = 300.dp).padding(vertical = 20.dp)
        .testTag("panorama-synopsis")
        .border(1.dp, if (focused) P.accent.copy(alpha=.6f) else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
        .memo("panorama:synopsis", "text", initial = true)
        .onFocusChanged { focused = it.isFocused }
        .onPreviewKeyEvent { event ->
            val down = event.key == Key.DirectionDown && synopsis.canScrollForward
            val up = event.key == Key.DirectionUp && synopsis.canScrollBackward
            if (event.type == KeyEventType.KeyDown && (down || up)) {
                scope.launch { synopsis.animateScrollBy(if (down) 150f else -150f) }
                true
            } else false
        }.focusable()) {
        item { Text(text, color = P.muted, fontSize = 16.sp, lineHeight = 25.sp, modifier = Modifier.padding(10.dp)) }
    }
}
