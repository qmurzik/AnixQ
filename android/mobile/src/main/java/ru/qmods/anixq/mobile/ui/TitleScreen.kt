package ru.qmods.anixq.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.Bookmark
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

/** Which episode the big button starts, and from where (same rules as the TV and iPhone). */
data class ResumePlan(val episode: Episode, val label: String, val sub: String?, val startAt: Double?)

fun resumePlan(title: Title, eps: List<Episode>): ResumePlan? {
    if (eps.isEmpty()) return null
    val last = Store.lastEntry(title.id)
    val i = last?.let { e -> eps.indexOfFirst { it.id == e.episodeId }.takeIf { it >= 0 } ?: eps.indexOfFirst { it.number == e.ep } }
    if (last != null && i != null && i >= 0) {
        val done = last.progress > 0.92 || Store.progress(eps[i].id) > 0.92
        if (done && i + 1 < eps.size) return ResumePlan(eps[i + 1], "Следующая серия ${eps[i + 1].number}", null, 0.0)
        if (!done) return ResumePlan(eps[i], "Продолжить серию ${eps[i].number}", "с ${fmt(last.pos)} из ${fmt(last.dur)}", last.pos)
        return ResumePlan(eps[i], "Пересмотреть серию ${eps[i].number}", null, 0.0)
    }
    val first = eps.firstOrNull { !Store.isWatched(title.id, it.number) } ?: eps.first()
    return ResumePlan(first, if (first == eps.first()) "Смотреть с 1 серии" else "Смотреть серию ${first.number}", null, null)
}

@Composable
fun TitleScreen(nav: Nav, start: Title) {
    val context = LocalContext.current
    val version by Store.version.collectAsState()
    val tvOnline by Remote.online.collectAsState()
    var detail by remember { mutableStateOf(TitleCache.detail[start.slug]) }
    var episodes by remember { mutableStateOf(TitleCache.episodes[start.slug]) }
    var teams by remember { mutableStateOf(TitleCache.teams[start.slug] ?: emptyList()) }
    var teamId by remember { mutableStateOf(Store.lastEntry(start.id)?.teamId) }
    var error by remember { mutableStateOf<String?>(null) }
    var related by remember { mutableStateOf(TitleCache.related[start.slug] ?: emptyList()) }
    var similar by remember { mutableStateOf(TitleCache.similar[start.slug] ?: emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var dubMenu by remember { mutableStateOf(false) }
    var bookmarkMenu by remember { mutableStateOf(false) }
    val title = detail?.title ?: start

    LaunchedEffect(start.slug) {
        try {
            launch { runCatching { Api.related(start.slug) }.getOrNull()?.filter { it.first.id != start.id }?.let { related = it; TitleCache.related[start.slug] = it } }
            launch { runCatching { Api.similar(start.slug) }.getOrNull()?.let { similar = it; TitleCache.similar[start.slug] = it } }
            launch { runCatching { Api.detail(start.slug) }.getOrNull()?.let { detail = it; TitleCache.detail[start.slug] = it } }
            val eps = Api.episodes(start.slug)
            episodes = eps; TitleCache.episodes[start.slug] = eps
            val target = resumePlan(start, eps)?.episode ?: return@LaunchedEffect
            val players = Api.players(target.id)
            val opts = players.groupBy { it.team.id }.map { (_, ps) -> TeamOption(ps.first().team, ps.any { it.isNative }, ps.any { it.isKodik }) }
                .sortedWith(compareBy({ !it.native }, { it.team.isSubtitles }))
            teams = opts; TitleCache.teams[start.slug] = opts
            // Warm the mirrors now, so "Смотреть" starts in a moment.
            launch { players.firstOrNull { it.isNative }?.hrefs?.values?.firstOrNull()?.let { runCatching { Api.rankServers(it) } } }
            if (teamId == null || opts.none { it.team.id == teamId }) {
                teamId = opts.firstOrNull { o -> Store.dubs.any { it.equals(o.team.name, true) } }?.team?.id ?: opts.firstOrNull()?.team?.id
            }
        } catch (e: Exception) { error = e.message ?: "Ошибка загрузки" }
    }

    val plan = remember(version, episodes) { episodes?.let { resumePlan(title, it) } }
    val bookmark = remember(version) { Store.bookmark(start.slug) }
    val team = teams.firstOrNull { it.team.id == teamId }
    val hasTv = Store.remoteChannel.isNotEmpty()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(330.dp)) {
                AsyncImage(detail?.background ?: title.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.55f)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.3f), Pal.bg))))
                IconButton(onClick = { nav.pop() }, Modifier.statusBarsPadding().padding(4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White)
                }
                Row(Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
                    Cover(title.poster, Modifier.width(118.dp).aspectRatio(0.7f))
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(title.title, fontSize = 21.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        (title.engName ?: title.name)?.takeIf { it != title.title }?.let {
                            Text(it, fontSize = 13.sp, color = Pal.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(listOfNotNull(title.meta.ifEmpty { null }, title.age, if (title.isOngoing) "Онгоинг" else null).joinToString(" · "),
                            fontSize = 12.sp, color = Pal.muted)
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(16.dp)) {
                Button(
                    onClick = { plan?.let { nav.play(PlayerRoute(title, it.episode.id, teamId, startAt = it.startAt?.takeIf { s -> s == 0.0 })) } },
                    enabled = plan != null, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Pal.pink),
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Column(Modifier.padding(start = 6.dp)) {
                        Text(plan?.label ?: if (error != null) "Не удалось загрузить" else "Загрузка…", fontWeight = FontWeight.SemiBold)
                        plan?.sub?.let { Text(it, fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f)) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasTv) OutlinedButton(
                        onClick = {
                            val p = plan ?: return@OutlinedButton
                            Remote.playOnTv(title, p.episode.id, teamId, p.startAt)
                            Toast.makeText(context, if (tvOnline) "Включаем на телевизоре…" else "Отправим, как только появится связь с ТВ", Toast.LENGTH_SHORT).show()
                            nav.push(RemoteRoute)
                        },
                        enabled = plan != null, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                    ) { Icon(Icons.Default.Tv, null, Modifier.size(18.dp)); Text(" На ТВ", maxLines = 1) }
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { bookmarkMenu = true }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                            Icon(if (bookmark != null) Icons.Default.Bookmark else Icons.Default.BookmarkAdd, null, Modifier.size(18.dp),
                                tint = if (bookmark != null) Pal.pink else Pal.text)
                            Text(" " + (bookmark?.title ?: "В список"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(bookmarkMenu, { bookmarkMenu = false }) {
                            Bookmark.entries.forEach { b ->
                                DropdownMenuItem({ Text(b.title) }, onClick = { bookmarkMenu = false; Store.setBookmark(title, b) },
                                    trailingIcon = { if (b == bookmark) Icon(Icons.Default.CheckCircle, null, tint = Pal.pink) })
                            }
                            if (bookmark != null) DropdownMenuItem({ Text("Убрать из списков", color = Pal.pink) }, onClick = { bookmarkMenu = false; Store.setBookmark(title, null) })
                        }
                    }
                }
                if (teams.size > 1) Box(Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { dubMenu = true }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.RecordVoiceOver, null, Modifier.size(18.dp))
                        Text(" " + (team?.label ?: "Озвучка"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(dubMenu, { dubMenu = false }) {
                        teams.forEach { o ->
                            DropdownMenuItem({ Text(o.label + if (o.team.isSubtitles) " · субтитры" else "") }, onClick = {
                                dubMenu = false; teamId = o.team.id; Store.useDub(o.team.name)
                            }, trailingIcon = { if (o.team.id == teamId) Icon(Icons.Default.CheckCircle, null, tint = Pal.pink) })
                        }
                    }
                }
                detail?.genres?.takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), fontSize = 12.sp, color = Pal.muted, modifier = Modifier.padding(top = 14.dp))
                }
                detail?.summary?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = Pal.text.copy(alpha = 0.9f), maxLines = if (expanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp).clickable { expanded = !expanded })
                }
            }
        }
        val eps = episodes
        if (eps == null) item { if (error != null) Centered { Text(error!!, color = Pal.muted) } else Box(Modifier.height(120.dp)) { Loading() } }
        else {
            item { SectionTitle("Серии · ${eps.size}") }
            items(eps, key = { it.id }) { ep -> EpisodeRow(title, ep, version) { nav.play(PlayerRoute(title, ep.id, teamId)) } }
        }
        if (related.isNotEmpty()) item {
            SectionTitle("Связанное")
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(related, key = { it.first.id }) { (t, kind) -> PosterCard(t, subtitle = kind) { nav.open(t) } }
            }
        }
        if (similar.isNotEmpty()) item { TitleRow("Похожее", similar) { nav.open(it) } }
    }
}

@Composable
private fun EpisodeRow(title: Title, ep: Episode, version: Int, onClick: () -> Unit) {
    val watched = remember(version) { Store.isWatched(title.id, ep.number) }
    val progress = remember(version) { Store.progress(ep.id) }
    val current = remember(version) { Store.lastEntry(title.id)?.episodeId == ep.id }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (current) Pal.pink.copy(alpha = 0.25f) else Pal.card), contentAlignment = Alignment.Center) {
            Text(ep.number, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (watched) Pal.muted else Pal.text, maxLines = 1)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(ep.displayTitle, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (watched) Pal.muted else Pal.text)
            if (progress > 0.01 && progress < 0.92) ProgressLine(progress.toFloat(), Modifier.padding(top = 6.dp).width(140.dp))
        }
        if (watched) Icon(Icons.Default.CheckCircle, "Просмотрено", tint = Pal.green, modifier = Modifier.size(18.dp))
    }
}
