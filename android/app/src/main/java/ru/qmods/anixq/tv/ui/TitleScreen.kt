package ru.qmods.anixq.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkAdded
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.Bookmark
import ru.qmods.anixq.tv.data.Detail
import ru.qmods.anixq.tv.data.Episode
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.TeamOption
import ru.qmods.anixq.tv.data.Title

/** Which episode the big button starts, and from where. */
data class ResumePlan(val episode: Episode, val label: String, val sub: String?)

fun resumePlan(title: Title, eps: List<Episode>): ResumePlan? {
    if (eps.isEmpty()) return null
    val last = Store.lastEntry(title.id)
    val i = last?.let { e -> eps.indexOfFirst { it.id == e.episodeId }.takeIf { it >= 0 } ?: eps.indexOfFirst { it.number == e.ep } }
    if (last != null && i != null && i >= 0) {
        val done = last.progress > 0.92 || Store.progress(eps[i].id) > 0.92
        if (done && i + 1 < eps.size) return ResumePlan(eps[i + 1], "Следующая серия ${eps[i + 1].number}", null)
        if (!done) return ResumePlan(eps[i], "Продолжить серию ${eps[i].number}", "с ${fmt(last.pos)} из ${fmt(last.dur)}")
        return ResumePlan(eps[i], "Пересмотреть серию ${eps[i].number}", null)
    }
    val firstUnwatched = eps.firstOrNull { !Store.isWatched(title.id, it.number) } ?: eps.first()
    return if (firstUnwatched == eps.first()) ResumePlan(eps.first(), "Смотреть с 1 серии", null)
    else ResumePlan(firstUnwatched, "Смотреть серию ${firstUnwatched.number}", null)
}

fun fmt(s: Double): String {
    val t = s.toInt().coerceAtLeast(0)
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

@Composable
fun TitleScreen(nav: Nav, start: Title) {
    val version by Store.version.collectAsState()
    var detail by remember { mutableStateOf<Detail?>(null) }
    var episodes by remember { mutableStateOf<List<Episode>?>(null) }
    var teams by remember { mutableStateOf<List<TeamOption>>(emptyList()) }
    var teamId by remember { mutableStateOf(Store.lastEntry(start.id)?.teamId) }
    var error by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val title = detail?.title ?: start
    val sc = "title:${start.id}"

    LaunchedEffect(start.slug) {
        try {
            launch { runCatching { Api.detail(start.slug) }.getOrNull()?.let { detail = it } }
            val eps = Api.episodes(start.slug)
            episodes = eps
            val target = resumePlan(start, eps)?.episode ?: return@LaunchedEffect
            val players = Api.players(target.id)
            val opts = players.groupBy { it.team.id }.map { (_, ps) -> TeamOption(ps.first().team, ps.any { it.isNative }, ps.any { it.isKodik }) }
                .sortedWith(compareBy({ !it.native }, { it.team.isSubtitles }))
            teams = opts
            if (teamId == null || opts.none { it.team.id == teamId }) {
                teamId = opts.firstOrNull { o -> Store.dubs.any { it.equals(o.team.name, true) } }?.team?.id ?: opts.firstOrNull()?.team?.id
            }
        } catch (e: Exception) { error = e.message ?: "Ошибка загрузки" }
    }

    val plan = remember(version, episodes) { episodes?.let { resumePlan(title, it) } }
    val bookmark = remember(version) { Store.bookmark(start.slug) }
    val team = teams.firstOrNull { it.team.id == teamId }

    Box(Modifier.fillMaxSize()) {
        AsyncImage(detail?.background ?: title.poster, null, contentScale = ContentScale.Crop,
            modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(if (detail?.background != null) 1f else 0.55f).height(460.dp).alpha(0.6f))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.25f to C.bg, 0.7f to C.bg.copy(alpha = 0.5f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 0.75f to C.bg)))

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 44.dp, bottom = 40.dp)) {
            item("head") {
                Column(Modifier.padding(horizontal = 56.dp).width(720.dp)) {
                    Text(title.title, fontSize = 38.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    (title.name ?: title.engName)?.takeIf { it != title.title }?.let {
                        Text(it, fontSize = 16.sp, color = C.muted, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (title.isOngoing) Text("ОНГОИНГ", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                            modifier = Modifier.background(C.pink, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                        Text(listOfNotNull(title.meta.ifEmpty { null }, episodes?.let { "${it.size} эп." }, title.age).joinToString(" · "),
                            fontSize = 16.sp, color = C.text.copy(alpha = 0.85f))
                    }
                    detail?.genres?.takeIf { it.isNotEmpty() }?.let {
                        Text(it.take(6).joinToString(" · "), fontSize = 14.sp, color = C.muted, modifier = Modifier.padding(top = 6.dp))
                    }
                    detail?.summary?.let {
                        Text(it, fontSize = 16.sp, lineHeight = 22.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                            color = C.text.copy(alpha = 0.85f), modifier = Modifier.padding(top = 14.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        when {
                            plan != null -> PillButton(plan.label, Icons.Rounded.PlayArrow, primary = true,
                                modifier = Modifier.memo(sc, "play", initial = true)) { nav.play(title, plan.episode.id, teamId) }
                            error != null -> PillButton("Повторить", modifier = Modifier.memo(sc, "retry", initial = true)) { nav.pop(); nav.open(start) }
                            episodes?.isEmpty() == true -> Text("Серий пока нет", color = C.muted, fontSize = 17.sp)
                            else -> Text("Загрузка серий…", color = C.muted, fontSize = 17.sp)
                        }
                        if (teams.size > 1) PillButton(team?.label ?: "Озвучка", Icons.Rounded.RecordVoiceOver,
                            modifier = Modifier.memo(sc, "dub")) { dialog = "dub" }
                        PillButton(bookmark?.title ?: "В закладки", if (bookmark != null) Icons.Rounded.BookmarkAdded else Icons.Rounded.BookmarkAdd,
                            modifier = Modifier.memo(sc, "bm")) { dialog = "bookmark" }
                    }
                    plan?.sub?.let { Text(it, fontSize = 13.sp, color = C.muted, modifier = Modifier.padding(start = 20.dp, top = 8.dp)) }
                    error?.let { Text(it, fontSize = 14.sp, color = C.pink, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
            episodes?.takeIf { it.isNotEmpty() }?.let { eps ->
                item("eps") { EpisodeStrip(eps, title, plan?.episode?.id, sc) { ep -> nav.play(title, ep.id, teamId) } }
            }
        }
    }

    when (dialog) {
        "dub" -> ChoiceDialog("Озвучка", teams.map { Choice(it.team.name, it.team.id == teamId,
            listOfNotNull(if (it.team.isSubtitles) "субтитры" else "озвучка", if (it.native) "без рекламы" else "Kodik").joinToString(" · ")) },
            { dialog = null }) { i ->
            dialog = null
            val name = teams[i].team.name
            teamId = teams[i].team.id
            Store.setDubs(listOf(name) + Store.dubs.filter { !it.equals(name, true) })
        }
        "bookmark" -> {
            val opts = Bookmark.entries
            ChoiceDialog("Закладка", opts.map { Choice(it.title, it == bookmark) } + listOfNotNull(if (bookmark != null) Choice("Убрать из закладок") else null),
                { dialog = null }) { i ->
                dialog = null
                Store.setBookmark(title, opts.getOrNull(i))
            }
        }
    }
}

@Composable
private fun EpisodeStrip(eps: List<Episode>, title: Title, current: Int?, sc: String, onPlay: (Episode) -> Unit) {
    val version by Store.version.collectAsState()
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (eps.indexOfFirst { it.id == current } - 1).coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(top = 30.dp)) {
        Row(Modifier.padding(start = 56.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Серии", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (eps.size > 40) {
                Spacer(Modifier.width(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val chunks = eps.indices.chunked(if (eps.size > 400) 100 else 25)
                    itemsIndexed(chunks) { _, c ->
                        Chip("${eps[c.first()].number}–${eps[c.last()].number}", false) { scope.launch { list.scrollToItem(c.first()) } }
                    }
                }
            }
        }
        LazyRow(state = list, contentPadding = PaddingValues(horizontal = 56.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(eps, key = { _, e -> e.id }) { _, e ->
                val prog = remember(version) { Store.progress(e.id) }
                val watched = remember(version) { Store.isWatched(title.id, e.number) || prog > 0.92 }
                Column(Modifier.width(220.dp)) {
                    Focusable(onClick = { onPlay(e) }, modifier = Modifier.fillMaxWidth().height(124.dp).memo(sc, "e${e.id}"),
                        container = if (e.id == current) C.pink.copy(alpha = 0.22f) else C.card) {
                        Box(Modifier.fillMaxSize()) {
                            Text(e.number, fontSize = 44.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = if (watched) 0.35f else 0.9f),
                                modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp))
                            if (watched) Icon(Icons.Rounded.CheckCircle, null, tint = C.pink, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(22.dp))
                            if (e.id == current) Text("ДАЛЕЕ", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = C.pink,
                                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))
                            if (prog > 0.01 && prog <= 0.92) ProgressLine(prog, Modifier.align(Alignment.BottomCenter))
                        }
                    }
                    Text(e.displayTitle, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = C.muted,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}
