package ru.qmods.anixq.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.qmods.anixq.tv.data.*
import ru.qmods.anixq.tv.data.sources.AnimeSources
import ru.qmods.anixq.tv.data.sources.SourceIds

internal val tvCatalogSources = listOf("animelib", "yummy", "shiki")

internal fun nextTvCatalog(source: String): String =
    tvCatalogSources[(tvCatalogSources.indexOf(source) + 1) % tvCatalogSources.size]

internal suspend fun searchTvCatalog(text: String, source: String): Pair<List<Title>, String?> {
    val query = CatalogQuery(text = text, source = source)
    if (source != "animelib") return Api.catalog(query, 1).first to null
    val primary = withTimeoutOrNull(5_000) { SmartSearch.search(text, useShikiCorrection = false) }
    return if (primary != null && primary.first.isNotEmpty()) primary
    else Api.catalog(query.copy(source = "shiki"), 1).first to null
}

internal fun tvContinueEntries(): List<WatchEntry> = buildList {
    for (entry in Store.history.sortedByDescending { it.at }) {
        val aliases = Store.catalogAliases(entry.title).map { it.slug }.toSet()
        if (none { it.title.slug in aliases }) add(entry)
        if (size == 20) break
    }
}

internal class TvTitleData(start: Title) {
    var detail by mutableStateOf(TitleCache.detail[start.slug] ?: Spotlight.cached(start.slug))
    var episodes by mutableStateOf(TitleCache.episodes[start.slug])
    var options by mutableStateOf(TitleCache.teams[start.slug].orEmpty())
    var related by mutableStateOf(TitleCache.related[start.slug].orEmpty())
    var similar by mutableStateOf(TitleCache.similar[start.slug].orEmpty())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var needsAlternative by mutableStateOf(false)
    var alternatives by mutableStateOf<List<Title>?>(null)
}

@Composable
internal fun rememberTvTitleData(start: Title, attempt: Int): TvTitleData {
    val state = remember(start.slug) { TvTitleData(start) }
    LaunchedEffect(start.slug, attempt) {
        state.loading = true
        state.error = null
        state.needsAlternative = false
        state.alternatives = null
        try {
            coroutineScope {
                launch {
                    try {
                        val detail = AnimeSources.detail(start)
                        state.detail = detail
                        TitleCache.detail[start.slug] = detail
                        state.episodes?.let { Store.rememberEpisodeIds(detail.title, it) }
                        if (start.id > 0 && detail.sourceUrl != null) state.needsAlternative = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        state.needsAlternative = true
                    }
                }
                launch { Api.related(start.slug).filter { it.first.id != start.id }.also { state.related = it; TitleCache.related[start.slug] = it } }
                launch { Api.similar(start.slug).also { state.similar = it; TitleCache.similar[start.slug] = it } }
                val title = state.detail?.title ?: start
                val episodes = AnimeSources.episodes(title)
                state.episodes = episodes
                if (episodes.isNotEmpty()) {
                    TitleCache.episodes[start.slug] = episodes
                    Store.rememberEpisodeIds(title, episodes)
                }
                if (start.id > 0 && episodes.isNotEmpty() && episodes.all { it.id < 0 }) state.needsAlternative = true
                val last = Store.lastEntry(title)
                if (last != null && episodes.none { ru.qmods.anixq.tv.data.sources.YummyAnime.sameNumber(it.number, last.ep) }) {
                    state.needsAlternative = true
                }
                val target = resumePlan(title, episodes)?.episode
                if (target == null) {
                    state.needsAlternative = true
                    state.error = "Серии недоступны в этом каталоге"
                } else {
                    fun show(players: List<Player>) {
                        val options = PlaybackChoices.options(players.filter(AnimeSources::supported))
                        state.options = options
                        TitleCache.teams[start.slug] = options
                    }
                    val base = AnimeSources.players(title, target, includeExternal = false)
                    show(base)
                    state.loading = false
                    if (base.none(AnimeSources::supported)) state.needsAlternative = true
                    else if (base.none { it.isExternalEmbed } || base.all { it.source == "shiki" }) {
                        val extra = AnimeSources.additional(title, target, includeShiki = base.none { it.source == "shiki" })
                        show(base + extra)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.error = e.message ?: "Не удалось получить серии"
            state.needsAlternative = true
        } finally {
            state.loading = false
        }
    }
    val title = state.detail?.title ?: start
    LaunchedEffect(start.slug, attempt, state.needsAlternative, title.shikiId, title.malId) {
        if (!state.needsAlternative) return@LaunchedEffect
        val known = Store.catalogAliases(title).associateBy { SourceIds.catalogSource(it.slug) }
        state.alternatives = coroutineScope {
            tvCatalogSources.filterNot { it == SourceIds.catalogSource(start.slug) }.map { source ->
                async { AnimeSources.catalogAlternative(title, source) ?: known[source] }
            }.mapNotNull { it.await() }
        }
    }
    return state
}

@Composable
internal fun TvCatalogFallback(data: TvTitleData, scope: String, nav: Nav, modifier: Modifier = Modifier) {
    if (!data.needsAlternative) return
    val choices = data.alternatives
    Column(modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(when {
            choices == null -> "Ищем этот тайтл в других каталогах…"
            choices.isEmpty() -> "Совпадающая карточка в других каталогах не найдена"
            else -> "Открыть этот тайтл через другой каталог"
        }, color = C.muted)
        choices.orEmpty().forEach { alternative ->
            val source = SourceIds.catalogSource(alternative.slug)
            PillButton("Открыть в ${playbackSourceLabel(source)}", modifier = Modifier.memo(scope, "fallback:$source")) {
                nav.switchCatalogTitle(alternative)
            }
        }
    }
}
