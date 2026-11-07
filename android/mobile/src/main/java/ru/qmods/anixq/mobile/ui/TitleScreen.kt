package ru.qmods.anixq.mobile.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.CatalogQuery
import ru.qmods.anixq.tv.data.CatalogSort
import ru.qmods.anixq.tv.data.Detail
import ru.qmods.anixq.tv.data.Episode
import ru.qmods.anixq.tv.data.Remote
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.TeamOption
import ru.qmods.anixq.tv.data.Title

/** What a title page loaded, so going back to it (e.g. after the player) is instant. */
object TitleCache {
    val detail = HashMap<String, Detail>()
    val episodes = HashMap<String, List<Episode>>()
    val teams = HashMap<String, List<TeamOption>>()
    val related = HashMap<String, List<Pair<Title, String>>>()
    val similar = HashMap<String, List<Title>>()
}

/** Which episode the big button starts (same rule as the TV and iPhone). */
fun nextIndex(title: Title, eps: List<Episode>): Int {
    if (eps.isEmpty()) return 0
    val last = Store.lastEntry(title.id)
    val i = last?.let { e -> eps.indexOfFirst { it.id == e.episodeId }.takeIf { it >= 0 } ?: eps.indexOfFirst { it.number == e.ep }.takeIf { it >= 0 } }
    if (last != null && i != null) {
        val done = last.progress > 0.92 || Store.progress(eps[i].id) > 0.92
        return if (done && i + 1 < eps.size) i + 1 else i
    }
    return eps.indexOfFirst { !Store.isWatched(title.id, it.number) }.takeIf { it >= 0 } ?: 0
}

private fun teamLabel(o: TeamOption) = (if (o.native) "⚡ " else "") + o.team.name + (if (o.native) "" else " · Kodik, реклама")

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TitleScreen(nav: Nav, start: Title) {
    val context = LocalContext.current
    val version by Store.version.collectAsState()
    var detail by remember { mutableStateOf(TitleCache.detail[start.slug]) }
    var episodes by remember { mutableStateOf(TitleCache.episodes[start.slug]) }
    var teams by remember { mutableStateOf(TitleCache.teams[start.slug] ?: emptyList()) }
    var teamId by remember { mutableStateOf(Store.lastEntry(start.id)?.teamId) }
    var error by remember { mutableStateOf<String?>(null) }
    var related by remember { mutableStateOf(TitleCache.related[start.slug] ?: emptyList()) }
    var similar by remember { mutableStateOf(TitleCache.similar[start.slug] ?: emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var teamMenu by remember { mutableStateOf(false) }
    var bookmarkMenu by remember { mutableStateOf(false) }
    var allEpisodes by remember { mutableStateOf(false) }
    var epMenu by remember { mutableStateOf<Episode?>(null) }
    var reload by remember { mutableStateOf(0) }
    val title = detail?.title?.let { d -> d.copy(cover = d.cover ?: start.cover, coverThumb = d.coverThumb ?: start.coverThumb) } ?: start
    val hasTv = Store.remoteChannel.isNotEmpty()

    LaunchedEffect(Unit) { runCatching { GenreIds.load() } }
    LaunchedEffect(start.slug, reload) {
        error = null
        launch { runCatching { Api.related(start.slug) }.getOrNull()?.filter { it.first.id != start.id }?.let { related = it; TitleCache.related[start.slug] = it } }
        launch { runCatching { Api.similar(start.slug) }.getOrNull()?.let { similar = it; TitleCache.similar[start.slug] = it } }
        launch { runCatching { Api.detail(start.slug) }.getOrNull()?.let { detail = it; TitleCache.detail[start.slug] = it } }
        try {
            val eps = Api.episodes(start.slug)
            episodes = eps; TitleCache.episodes[start.slug] = eps
            if (eps.isEmpty()) return@LaunchedEffect
            // Teams can differ between early and recent episodes: look at both ends.
            val target = eps[nextIndex(start, eps)]
            val players = Api.players(target.id) + (if (eps.size > 1 && eps.last().id != target.id) runCatching { Api.players(eps.last().id) }.getOrDefault(emptyList()) else emptyList())
            val order = LinkedHashMap<Int, TeamOption>()
            players.forEach { p ->
                val o = order[p.team.id]
                order[p.team.id] = TeamOption(p.team, (o?.native ?: false) || p.isNative, (o?.kodik ?: false) || p.isKodik)
            }
            val opts = order.values.let { v -> v.filter { it.native && !it.team.isSubtitles } + v.filter { it.native && it.team.isSubtitles } + v.filter { !it.native } }
            teams = opts; TitleCache.teams[start.slug] = opts
            launch { players.firstOrNull { it.isNative && (teamId == null || it.team.id == teamId) }?.hrefs?.values?.firstOrNull()?.let { runCatching { Api.rankServers(it) } } }
            if (teamId == null || opts.none { it.team.id == teamId }) {
                teamId = opts.firstOrNull { o -> Store.dubs.any { it.equals(o.team.name, true) } }?.team?.id ?: opts.firstOrNull()?.team?.id
            }
        } catch (e: Exception) { error = e.message ?: "Ошибка загрузки" }
    }

    val eps = episodes.orEmpty()
    val idx = remember(version, episodes) { nextIndex(title, eps) }
    val bookmark = remember(version) { Store.bookmark(start.slug) }
    val team = teams.firstOrNull { it.team.id == teamId }
    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 900 } }
    fun play(i: Int) { eps.getOrNull(i)?.let { ep -> nav.play(PlayerRoute(title, ep.id, teamId)) } }
    val playLabel = when {
        eps.isEmpty() -> if (error != null) "Не удалось загрузить" else if (episodes == null) "Загрузка" else "Нет серий"
        Store.resume(eps[idx].id) != null -> "Продолжить · серия ${eps[idx].number}"
        eps.size == 1 -> "Смотреть"
        else -> "Смотреть · серия ${eps[idx].number}"
    }

    Box(Modifier.fillMaxSize().background(Pal.bg)) {
        LazyColumn(Modifier.fillMaxSize(), list, contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "header") {
                Box(Modifier.fillMaxWidth().height(520.dp)) {
                    val bg = detail?.background
                    if (bg != null) Img(bg, Modifier.fillMaxSize())
                    else Img(title.poster, Modifier.fillMaxSize().blur(18.dp), alpha = 0.8f)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f), 0.3f to Color.Transparent, 0.72f to Pal.bg.copy(alpha = 0.8f), 1f to Pal.bg)))
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (bg == null) PosterImage(title.poster, Modifier.width(130.dp).shadow(16.dp, RoundedCornerShape(12.dp)))
                        Text(title.title, fontSize = 28.sp, fontWeight = FontWeight.Black, lineHeight = 32.sp, textAlign = TextAlign.Center, maxLines = 3,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            title.rating?.takeIf { it > 0 }?.let { item { MetaChip("%.1f".format(it), Icons.Default.Star, Pal.gold) } }
                            title.typeLabel?.let { item { MetaChip(it) } }
                            title.year?.let { item { MetaChip(it.toString()) } }
                            if (eps.isNotEmpty()) item { MetaChip("${eps.size} эп.", Icons.Default.Movie) }
                            if (title.isOngoing) item { MetaChip("Онгоинг", Icons.Default.Sensors, Pal.green) }
                            title.age?.let { item { MetaChip(it) } }
                        }
                        Row(Modifier.padding(horizontal = 16.dp).padding(top = 14.dp).widthIn(max = 560.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton(Modifier.weight(1f), enabled = eps.isNotEmpty() && team != null, onClick = { play(idx) }) {
                                if (episodes == null) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                else Icon(Icons.Default.PlayArrow, null, tint = Color.White)
                                Text(" $playLabel", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Box {
                                GlassButton(if (bookmark != null) Icons.Default.Check else Icons.Default.Add, "В закладки") { bookmarkMenu = true }
                                BookmarkMenu(title, bookmarkMenu) { bookmarkMenu = false }
                            }
                            if (hasTv) GlassButton(Icons.Default.Tv, "Смотреть на ТВ") {
                                eps.getOrNull(idx)?.let { ep ->
                                    Remote.playOnTv(title, ep.id, teamId, Store.resume(ep.id))
                                    Toast.makeText(context, "Включаем серию ${ep.number} на телевизоре", Toast.LENGTH_SHORT).show()
                                    nav.push(RemoteRoute)
                                }
                            }
                        }
                        bookmark?.let { b ->
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(b.icon, null, Modifier.size(13.dp), tint = Pal.muted)
                                Text(" ${b.title}", fontSize = 12.sp, color = Pal.muted)
                            }
                        }
                    }
                }
            }
            detail?.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                item(key = "about") {
                    Column(Modifier.padding(horizontal = 16.dp).padding(top = 18.dp)) {
                        Text(summary, fontSize = 15.sp, lineHeight = 21.sp, color = Pal.muted, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                        Text(if (expanded) "Свернуть" else "Ещё", color = Pal.pink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 6.dp).clickable { expanded = !expanded })
                    }
                }
            }
            detail?.genres?.takeIf { it.isNotEmpty() }?.let { genres ->
                item(key = "genres") {
                    LazyRow(Modifier.padding(top = 10.dp), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(genres) { g ->
                            Text(g, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(CircleShape).background(Pal.cardHi)
                                .clickable {
                                    scopeGenre(g)?.let { id -> nav.push(CatalogRoute(g, CatalogQuery(CatalogSort.POPULAR, genre = id))) }
                                }.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }
            }
            item(key = "episodes") {
                Column(Modifier.padding(top = 26.dp)) {
                    SectionHeader("Серии", if (eps.size > 6) "Все ${eps.size}" else null) { allEpisodes = true }
                    if (teams.isNotEmpty()) Box(Modifier.padding(horizontal = 16.dp).padding(top = 10.dp)) {
                        Row(Modifier.clip(CircleShape).background(Pal.cardHi).clickable { teamMenu = true }.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (team?.team?.isSubtitles == true) Icons.Default.ClosedCaption else Icons.Default.GraphicEq, null, Modifier.size(16.dp))
                            Text(" " + (team?.let(::teamLabel) ?: "Озвучка"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
                            Icon(Icons.Default.UnfoldMore, null, Modifier.size(14.dp))
                        }
                        DropdownMenu(teamMenu, { teamMenu = false }, containerColor = Pal.cardHi) {
                            teams.forEach { o ->
                                DropdownMenuItem({ Text(teamLabel(o)) }, onClick = { teamMenu = false; teamId = o.team.id; Store.useDub(o.team.name) },
                                    leadingIcon = { Icon(if (o.team.id == teamId) Icons.Default.Check else if (o.team.isSubtitles) Icons.Default.ClosedCaption else Icons.Default.GraphicEq, null,
                                        tint = if (o.team.id == teamId) Pal.pink else Pal.muted) })
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    when {
                        episodes == null && error == null -> Loading(Modifier.fillMaxWidth().height(120.dp))
                        eps.isEmpty() && error != null -> ErrorState(error!!) { reload++ }
                        eps.isEmpty() -> Text("Серий пока нет", color = Pal.muted, modifier = Modifier.padding(horizontal = 16.dp))
                        else -> {
                            val row = rememberLazyListState(initialFirstVisibleItemIndex = (idx - 1).coerceAtLeast(0))
                            LazyRow(state = row, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                itemsIndexed(eps, key = { _, e -> e.id }) { i, ep ->
                                    Box {
                                        EpisodeCard(ep, detail?.background ?: title.poster, Store.progress(ep.id).toFloat(), Store.isWatched(title.id, ep.number),
                                            Modifier.combinedClickable(onClick = { play(i) }, onLongClick = { epMenu = ep }))
                                        DropdownMenu(epMenu == ep, { epMenu = null }, containerColor = Pal.cardHi) {
                                            if (hasTv) DropdownMenuItem({ Text("Смотреть на ТВ") }, leadingIcon = { Icon(Icons.Default.Tv, null) }, onClick = {
                                                epMenu = null; Remote.playOnTv(title, ep.id, teamId, Store.resume(ep.id)); nav.push(RemoteRoute)
                                            })
                                            if (!Store.isWatched(title.id, ep.number)) DropdownMenuItem({ Text("Отметить просмотренной") },
                                                leadingIcon = { Icon(Icons.Default.CheckCircle, null) }, onClick = { epMenu = null; Store.markWatched(title, ep) })
                                            DropdownMenuItem({ Text("Смотреть с начала") }, leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                                                onClick = { epMenu = null; nav.play(PlayerRoute(title, ep.id, teamId, startAt = 0.0)) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (related.isNotEmpty()) item(key = "related") {
                Column(Modifier.padding(top = 26.dp)) {
                    SectionHeader("Связанные")
                    Spacer(Modifier.height(10.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(related, key = { it.first.id }) { (t, kind) -> PosterCard(t, Modifier.width(118.dp), subtitle = kind) { nav.open(t) } }
                    }
                }
            }
            item(key = "similar") { Shelf("Похожие", similar) { nav.open(it) } }
        }

        // Floating bar: round buttons over the art; a solid bar with the name once scrolled.
        Box(Modifier.fillMaxWidth().then(if (scrolled) Modifier.background(Pal.bg.copy(alpha = 0.96f)) else Modifier).statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)) {
            CircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Назад", Modifier.align(Alignment.CenterStart)) { nav.pop() }
            AnimatedVisibility(scrolled, Modifier.align(Alignment.Center).padding(horizontal = 52.dp), enter = fadeIn(), exit = fadeOut()) {
                Text(title.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CircleButton(Icons.Default.Share, "Поделиться", Modifier.align(Alignment.CenterEnd)) {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "${title.title} — https://anilib.me/ru/anime/${title.slug}"), null))
            }
        }
    }

    if (allEpisodes) ModalBottomSheet({ allEpisodes = false }, containerColor = Pal.card) {
        Text("Серии", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        LazyColumn(Modifier.navigationBarsPadding(), rememberLazyListState((idx - 2).coerceAtLeast(0))) {
            itemsIndexed(eps, key = { _, e -> e.id }) { i, ep ->
                val p = Store.progress(ep.id)
                Row(Modifier.fillMaxWidth().clickable { allEpisodes = false; play(i) }.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ep.number, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp), color = if (i == idx) Pal.pink else Pal.text)
                    Column(Modifier.weight(1f)) {
                        Text(ep.displayTitle, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p > 0.02) ProgressLine(p.toFloat(), Modifier.padding(top = 6.dp), 3.dp)
                    }
                    if (Store.isWatched(title.id, ep.number)) Icon(Icons.Default.CheckCircle, null, Modifier.padding(start = 8.dp).size(20.dp), tint = Pal.muted)
                }
                HorizontalDivider(Modifier.padding(start = 64.dp), thickness = 0.5.dp, color = Color.White.copy(alpha = 0.08f))
            }
        }
    }
}

private fun scopeGenre(name: String): Int? = GenreIds.byName[name.lowercase()]

/** Genre names → ids (from the catalog constants), so genre chips on a title page open the catalog. */
object GenreIds {
    var byName: Map<String, Int> = emptyMap()
    suspend fun load() { if (byName.isEmpty()) byName = Api.genres().associate { it.second.lowercase() to it.first } }
}

@Composable
fun EpisodeCard(ep: Episode, image: String?, progress: Float, watched: Boolean, modifier: Modifier = Modifier) {
    Column(Modifier.width(200.dp)) {
        Box(modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(10.dp)).background(Pal.card)) {
            Img(image, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (watched) 0.55f else 0.3f)))
            Text(ep.number, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
            if (progress > 0.02f && progress < 0.95f) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxWidth(progress).height(3.dp).background(Color.White))
            }
            if (watched) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.CheckCircle, "Просмотрено", Modifier.size(20.dp), tint = Pal.pink)
            }
        }
        Text(ep.displayTitle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (watched) Pal.muted else Pal.text, modifier = Modifier.padding(top = 6.dp))
    }
}
