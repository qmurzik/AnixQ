package ru.qmods.anixq.mobile.ui.cinema

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import ru.qmods.anixq.mobile.data.Downloads
import ru.qmods.anixq.mobile.data.PlaybackHistory
import ru.qmods.anixq.mobile.ui.*
import ru.qmods.anixq.tv.data.*
import ru.qmods.anixq.tv.data.sources.AnimeSources
import ru.qmods.anixq.tv.data.sources.SourceIds
import ru.qmods.anixq.tv.data.sources.YummyAnime

@Composable
internal fun CinemaTitle(nav: Nav, route: TitleRoute) {
    val start = route.catalogTitle
    val context = LocalContext.current
    val version by Store.version.collectAsState()
    val revision by PlaybackHistory.revision.collectAsState()
    val downloads by Downloads.items.collectAsState()
    var detail by remember(start.slug) { mutableStateOf(TitleCache.detail[start.slug]) }
    var episodes by remember(start.slug) { mutableStateOf(TitleCache.episodes[start.slug]) }
    var options by remember(start.slug) { mutableStateOf(TitleCache.teams[start.slug].orEmpty()) }
    var team by remember(start.slug) { mutableStateOf(PlaybackHistory.lastEntry(start)?.teamId) }
    var error by remember(start.slug) { mutableStateOf<String?>(null) }
    var degraded by remember(start.slug) { mutableStateOf(false) }
    var alternatives by remember(start.slug) { mutableStateOf<List<Title>?>(null) }
    var attempt by remember(start.slug) { mutableIntStateOf(0) }
    var showBookmark by remember(start.slug) { mutableStateOf(false) }
    var showDownload by remember(start.slug) { mutableStateOf(false) }
    var activeEpisode by remember(start.slug) { mutableStateOf<Episode?>(null) }
    var noteEpisode by remember(start.slug) { mutableStateOf<Episode?>(null) }
    var expanded by remember(start.slug) { mutableStateOf(false) }
    var related by remember(start.slug) { mutableStateOf(TitleCache.related[start.slug].orEmpty()) }
    var similar by remember(start.slug) { mutableStateOf(TitleCache.similar[start.slug].orEmpty()) }
    val title = detail?.title ?: start
    val list = episodes.orEmpty()
    val idx = remember(title, list, version, revision) { nextIndex(title, list) }
    val next = list.getOrNull(idx)
    val last = remember(title, version, revision) { PlaybackHistory.lastEntry(title) }
    val bookmark = remember(title.slug, version) { Store.bookmark(title.slug) }

    LaunchedEffect(start.slug, attempt) {
        degraded = false
        alternatives = null
        error = null
        launch {
            try {
                val loaded = AnimeSources.detail(start)
                detail = loaded
                PlaybackHistory.remember(loaded.title, loaded.genres)
                TitleCache.detail[start.slug] = loaded
                episodes?.let { Store.rememberEpisodeIds(loaded.title, it) }
                if (start.id > 0 && loaded.sourceUrl != null) degraded = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { degraded = true }
        }
        launch { runCatching { Api.related(start.slug) }.getOrNull()?.filterNot { it.first.id == start.id }?.let {
            related = it; TitleCache.related[start.slug] = it
        } }
        launch { runCatching { Api.similar(start.slug) }.getOrNull()?.let {
            similar = it; TitleCache.similar[start.slug] = it
        } }
        try {
            val items = AnimeSources.episodes(start).ifEmpty { Downloads.episodes(start.id).orEmpty() }
            episodes = items
            if (items.isEmpty()) degraded = true
            else {
                TitleCache.episodes[start.slug] = items
                if (start.id > 0 && items.all { it.id < 0 }) degraded = true
                val current = detail?.title ?: start
                Store.rememberEpisodeIds(current, items)
                val target = items[nextIndex(current, items)]
                fun apply(players: List<Player>) {
                    val choices = PlaybackChoices.options(players.filter(AnimeSources::supported))
                    options = choices
                    TitleCache.teams[start.slug] = choices
                    team = Store.playbackOption(current, choices)?.team?.id
                        ?: team?.takeIf { id -> choices.any { it.team.id == id } }
                        ?: choices.firstOrNull()?.team?.id
                }
                val base = AnimeSources.players(current, target, includeExternal = false)
                apply(base)
                if (base.none(AnimeSources::supported) && items.none { Downloads.offlinePlayer(it.id) != null }) degraded = true
                if (base.isNotEmpty() && (base.none { it.isExternalEmbed } || base.all { it.source == "shiki" })) {
                    apply(base + AnimeSources.additional(current, target, includeShiki = base.none { it.source == "shiki" }))
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            episodes = Downloads.episodes(start.id) ?: episodes
            error = e.message ?: "Не удалось загрузить серии"
            degraded = true
        }
    }

    LaunchedEffect(start.slug, title.shikiId, title.malId, degraded, attempt) {
        if (degraded) {
            val current = SourceIds.catalogSource(start.slug)
            val known = Store.catalogAliases(title).associateBy { SourceIds.catalogSource(it.slug) }
            alternatives = coroutineScope {
                MobileCatalogSources.filterNot { it.first == current }.map { (source, _) ->
                    async { AnimeSources.catalogAlternative(title, source) ?: known[source] }
                }.awaitAll().filterNotNull()
            }
        }
    }

    fun play(ep: Episode, startAt: Double? = null) { nav.play(PlayerRoute(title, ep.id, team, startAt = startAt)) }
    fun download(items: List<Episode>) = Downloads.enqueue(title, list, items, team) {
        Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
    }
    fun share() {
        val url = detail?.sourceUrl ?: if (title.id > 0) "https://anilib.me/ru/anime/${title.slug}"
            else "anixq://title/${android.net.Uri.encode(title.slug)}"
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "${title.title} — $url"), null))
    }

    noteEpisode?.let { episode ->
        val notes = ru.qmods.anixq.mobile.data.Notes
        TextDialog("Заметка к серии ${episode.number}", notes.get(title.id).episodes[episode.number].orEmpty(),
            "Что запомнилось", { noteEpisode = null }) {
            notes.setEpisode(title.id, episode.number, it)
            noteEpisode = null
        }
    }
    if (showBookmark) AlertDialog(
        onDismissRequest = { showBookmark = false },
        title = { Text("Добавить в закладки") },
        containerColor = Film.panel,
        titleContentColor = Film.paper,
        text = {
            Column {
                Bookmark.entries.forEach { entry ->
                    TextButton({ Store.setBookmark(title, entry); showBookmark = false }) {
                        Text(entry.title, color = Film.paper)
                    }
                }
                if (bookmark != null) TextButton({ Store.setBookmark(title, null); showBookmark = false }) {
                    Text("Убрать закладку", color = Film.ticket)
                }
            }
        },
        confirmButton = { TextButton({ showBookmark = false }) { Text("Закрыть", color = Film.paper) } },
    )

    LazyColumn(Modifier.fillMaxSize().background(Film.canvas), contentPadding = PaddingValues(bottom = 24.dp)) {
        item("header") {
            CinemaHeader(playbackSourceLabel(SourceIds.catalogSource(start.slug)), nav::pop) {
                CinemaIcon(Icons.Default.Share, "Поделиться", ::share)
            }
        }
        item("title") {
            Column {
                Box(Modifier.fillMaxWidth().aspectRatio(1.75f)) {
                    CinemaArt(detail?.background ?: title.poster, Modifier.matchParentSize())
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                        listOf(Color.Transparent, Film.canvas.copy(alpha = .18f), Film.canvas))))
                }
                Row(Modifier.padding(horizontal = Film.edge).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CinemaArt(title.thumb, Modifier.width(89.dp).aspectRatio(.72f).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        CinemaLabel(title.meta, color = Film.ticket)
                        CinemaHeading(title.title, size = 32)
                        title.episodeInfo?.let { CinemaLabel(it) }
                    }
                }
                Row(Modifier.padding(horizontal = Film.edge, vertical = 17.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (next != null) {
                        val resume = Store.resumeEpisode(title, next)
                        CinemaAction(if (resume != null) "Продолжить · серия ${next.number}" else "Смотреть серию ${next.number}",
                            { play(next) }, Modifier.weight(1f), Icons.Default.PlayArrow, filled = true)
                    }
                    CinemaIcon(if (bookmark == null) Icons.Default.BookmarkBorder else Icons.Default.Bookmark,
                        "Закладки") { showBookmark = true }
                    if (list.isNotEmpty()) Box {
                        CinemaIcon(Icons.Default.Download, "Скачать серии") { showDownload = true }
                        DropdownMenu(showDownload, { showDownload = false }, containerColor = Film.panel) {
                            listOf("Следующие три" to list.drop(idx).take(3), "Все серии" to list).forEach { (label, batch) ->
                                DropdownMenuItem(text = { Text(label, color = Film.paper) }, onClick = {
                                    showDownload = false
                                    download(batch)
                                })
                            }
                        }
                    }
                }
            }
        }
        if (degraded) item("alternatives") {
            Column(Modifier.padding(horizontal = Film.edge, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CinemaLabel(when {
                    alternatives == null -> "Ищем этот тайтл в других каталогах…"
                    alternatives.isNullOrEmpty() -> "Других совпадающих карточек пока нет"
                    else -> "Доступные карточки в других каталогах"
                })
                alternatives.orEmpty().forEach { alternative ->
                    CinemaRow(playbackSourceLabel(SourceIds.catalogSource(alternative.slug)), alternative.title, Icons.Default.SwapHoriz,
                        { route.openCatalogTitle(alternative) })
                }
            }
        }
        if (last != null && list.isNotEmpty() && list.none { YummyAnime.sameNumber(it.number, last.ep) }) {
            item("saved") { CinemaLabel("Позиция серии ${last.ep} сохранена. В этом каталоге серии пока нет.", Modifier.padding(Film.edge)) }
        }
        if (!ru.qmods.anixq.tv.data.sources.YummyAccount.signedIn) item("yummy-sign-in") {
            Box(Modifier.padding(horizontal = Film.edge, vertical = 5.dp)) {
                CinemaRow("Вход в YummyAnime", "Для озвучек, доступных после входа", Icons.Default.PersonAdd, { nav.push(AdvancedSettingsRoute) })
            }
        }
        if (options.isNotEmpty()) item("audio") {
            PlaybackSelector(options, team) { option ->
                team = option.team.id
                Store.setPlaybackOption(title, option)
                Store.useDub(option.team.name)
            }
        }
        item("episodes-title") { CinemaHeading("Серии", Modifier.padding(Film.edge), 27) }
        if (episodes == null || error != null || list.isEmpty()) item("episodes-status") {
            CinemaStatus(error ?: if (episodes != null) "Серии пока недоступны" else null,
                loading = episodes == null && error == null,
                action = if (error != null || episodes != null && list.isEmpty()) ({ attempt++ }) else null)
        }
        items(list, key = { "episode-${it.id}" }) { ep ->
            val saved = downloads[ep.id]
            val progress = remember(title, ep, version) { Store.episodeProgress(title, ep) }
            Row(Modifier.padding(horizontal = Film.edge, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(if (ep.id == next?.id) Film.raised else Film.panel).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ep.number, Modifier.width(40.dp).padding(start = 5.dp), color = Film.ticket, fontSize = 19.sp)
                Column(Modifier.weight(1f).clickable { play(ep) }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    val watched = Store.isWatched(title, ep.number)
                    Text(Spoilers.title(ep, watched), color = Film.paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    CinemaLabel(when {
                        saved?.state == Downloads.State.DONE -> "Скачано"
                        saved?.state == Downloads.State.RUNNING -> "Скачивается · ${(saved.progress * 100).toInt()}%"
                        watched -> "Просмотрено"
                        else -> Store.resumeEpisode(title, ep)?.let { "На ${fmt(it)}" }.orEmpty()
                    })
                    if (progress > .01) Box(Modifier.fillMaxWidth().height(3.dp).background(Film.rule)) {
                        Box(Modifier.fillMaxWidth(progress.toFloat()).fillMaxHeight().background(Film.ticket))
                    }
                }
                CinemaIcon(Icons.Default.PlayArrow, "Смотреть серию ${ep.number}", { play(ep) })
                Box {
                    CinemaIcon(Icons.Default.MoreVert, "Действия серии ${ep.number}") { activeEpisode = ep }
                    DropdownMenu(activeEpisode?.id == ep.id, { activeEpisode = null }, containerColor = Film.panel) {
                        fun dismiss() { activeEpisode = null }
                        DropdownMenuItem({ Text("Смотреть с начала", color = Film.paper) }, { dismiss(); play(ep, 0.0) })
                        DropdownMenuItem({ Text("Отметить просмотренной", color = Film.paper) }, { dismiss(); Store.markWatched(title, ep) })
                        if (saved == null || saved.state == Downloads.State.FAILED) {
                            DropdownMenuItem({ Text("Скачать", color = Film.paper) }, { dismiss(); download(listOf(ep)) })
                        } else DropdownMenuItem({ Text("Удалить загрузку", color = Film.paper) }, { dismiss(); Downloads.remove(ep.id) })
                        DropdownMenuItem({ Text("Заметка к серии", color = Film.paper) }, { dismiss(); noteEpisode = ep })
                        if (ep.id > 0) DropdownMenuItem({ Text("Обсуждение серии", color = Film.paper) }, {
                            dismiss()
                            nav.push(CommentsRoute(ep.id, "episodes", "${title.title} · серия ${ep.number}"))
                        })
                        if (Store.remoteChannel.isNotEmpty()) DropdownMenuItem({ Text("Смотреть на ТВ", color = Film.paper) }, {
                            dismiss()
                            Remote.playOnTv(title, ep.id, team, Store.resumeEpisode(title, ep))
                            nav.push(RemoteRoute)
                        })
                    }
                }
            }
        }
        detail?.summary?.takeIf { it.isNotBlank() }?.let { synopsis ->
            item("synopsis") {
                Column(Modifier.padding(Film.edge), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CinemaHeading("О тайтле", size = 27)
                    CinemaLabel(detail?.genres.orEmpty().joinToString(" · "), color = Film.ticket)
                    Text(synopsis, color = Film.paper, maxLines = if (expanded) Int.MAX_VALUE else 5,
                        overflow = TextOverflow.Ellipsis, lineHeight = 21.sp)
                    TextButton({ expanded = !expanded }) { Text(if (expanded) "Свернуть" else "Читать полностью", color = Film.ticket) }
                }
            }
        }
        detail?.sourceUrl?.let { url ->
            item("source-page") {
                val online = options.any { it.source == "shiki" }
                Box(Modifier.padding(horizontal = Film.edge)) {
                    CinemaAction(if (online) "Смотреть на Шикимори" else "Открыть на Шикимори",
                        { openInBrowser(context, if (online) "$url/watch" else url) })
                }
            }
        }
        if (list.size > 1 && options.size > 1) item("compare-dubs") {
            DubCompareSection(title, list, team) { id ->
                options.firstOrNull { it.team.id == id }?.let {
                    team = id
                    Store.setPlaybackOption(title, it)
                    Store.useDub(it.team.name)
                }
            }
        }
        item("notes") { NotesCard(title) }
        title.shikiId?.let { id ->
            item("rating") { ShikiRateCard(id, list.size) }
            item("franchise") {
                Box(Modifier.padding(horizontal = Film.edge)) {
                    CinemaRow("Порядок просмотра", null, Icons.Default.AccountTree, { nav.push(FranchiseMapRoute(id, title.title)) })
                }
            }
            item("videos") { VideosShelf(id) }
            item("people") { PeopleShelves(id, nav) }
        }
        for ((label, cards) in listOf("Другие части" to related.map { it.first }, "Похожие тайтлы" to similar)) {
            if (cards.isNotEmpty()) item(label) {
                CinemaHeading(label, Modifier.padding(Film.edge), 26)
                LazyRow(contentPadding = PaddingValues(horizontal = Film.edge), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(cards, key = { it.id }) { card -> CinemaPoster(card, Modifier.width(116.dp)) { nav.open(card) } }
                }
            }
        }
        if (title.id > 0) item("comments") { CommentsPreview(title, nav) }
    }
}
