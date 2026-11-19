package ru.qmods.anixq.tv.data.sources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import ru.qmods.anixq.tv.data.*
import ru.qmods.anixq.tv.data.sources.extractors.*

/** Shared orchestration for phone and TV; an unavailable catalogue never disables the other. */
object AnimeSources {
    val yummy = YummyAnime()
    private val http = SourceHttp()
    private val animeGo = AnimeGo()
    val extractors: List<StreamExtractor> = listOf(AksorExtractor(http), CvhExtractor(http), KodikExtractor(http),
        VkExtractor(http), RutubeExtractor(http), SibnetExtractor(http), ZedfilmExtractor(http), DirectExtractor())
    private val matchMutex = Mutex()
    private val matches = LinkedHashMap<String, Pair<Long, Int?>>()
    suspend fun match(title: Title): Int? {
        if (SourceIds.isYummy(title.slug)) return SourceIds.yummyRemote(title.id)
        if (title.shikiId == null && title.malId == null) return null
        return matchMutex.withLock {
            matches[title.slug]?.takeIf { System.currentTimeMillis() - it.first < 600_000 }?.let { return@withLock it.second }
            val found = withTimeoutOrNull(12_000) {
                val list = yummy.byRemoteId(title.shikiId, title.malId)
                // Search currently includes remote_ids. Never guess by the title string alone.
                val candidates = list.filter { safelyMatches(title, it) }
                candidates.singleOrNull()?.let { SourceIds.yummyRemote(it.id) }
            }
            matches[title.slug] = System.currentTimeMillis() to found
            while(matches.size > 80) matches.remove(matches.keys.first())
            found
        }
    }
    fun safelyMatches(base: Title, candidate: Title): Boolean {
        val mal = base.malId
        val sameId = if (mal != null) mal == candidate.malId else
            base.shikiId != null && base.shikiId == candidate.shikiId && base.shikiId == candidate.malId
        if (!sameId) return false
        if (base.year != null && candidate.year != null && base.year != candidate.year) return false
        if (base.typeLabel != null && candidate.typeLabel != null && normalizeType(base.typeLabel) != normalizeType(candidate.typeLabel)) return false
        return true
    }
    private fun normalizeType(s: String) = when(s.lowercase()) {
        "тв", "tv", "tv series", "сериал", "тв сериал", "tv сериал" -> "tv"
        "фильм", "movie", "полнометражный фильм" -> "movie"
        else -> s.lowercase()
    }
    suspend fun players(title: Title, ep: Episode, includeExternal: Boolean = true): List<Player> = coroutineScope {
        val lib = async { if (SourceIds.isYummy(title.slug)) emptyList() else isolated { Api.players(ep.id) }.orEmpty() }
        val extra = if (includeExternal || title.id < 0) async { additional(title, ep) } else null
        val base = lib.await()
        val libria = base.filter { Libria.isLibria(it.team.name) }.distinctBy { it.team.id }.map {
            it.copy(team = it.team.copy(id = Int.MIN_VALUE + it.team.id), player = "external", hrefs = emptyMap(), source = Libria.SOURCE, headers = emptyMap(), resolverUrl = "anilibria:", balancer = null)
        }
        val reserve = extra?.await() ?: if (base.none(::supported)) additional(title, ep) else emptyList()
        base + libria + reserve
    }
    suspend fun additional(title: Title, ep: Episode): List<Player> = coroutineScope {
        val yummyPlayers = async {
            withTimeoutOrNull(15_000) {
                isolated { match(title)?.let { yummy.players(it, ep.number) } }.orEmpty()
            }.orEmpty()
        }
        val reserve = async {
            withTimeoutOrNull(9_000) { isolated { animeGo.players(title, ep) }.orEmpty() }.orEmpty()
        }
        yummyPlayers.await().sortedBy { player ->
            extractors.indexOfFirst { it.supports(player.resolverUrl.orEmpty()) }.takeIf { it >= 0 } ?: Int.MAX_VALUE
        } + reserve.await()
    }

    fun chooseSource(players: List<Player>, current: Player?, source: String): Player? {
        val options = PlaybackChoices.options(players.filter(::supported))
        val selected = options.firstOrNull { it.team.id == current?.team?.id && it.source == current?.source }
        val choice = PlaybackChoices.chooseSource(options, selected, source) ?: return null
        return players.firstOrNull { it.team.id == choice.team.id && it.source == choice.source && it.isNative }
            ?: players.firstOrNull { it.team.id == choice.team.id && it.source == choice.source }
    }
    fun requiresYummyLogin(player: Player): Boolean = player.isYummy && player.canResolve &&
        !AksorExtractor.supportsUrl(player.resolverUrl.orEmpty()) &&
        (yummy.userToken.isNullOrBlank() || yummy.applicationToken.isNullOrBlank())

    suspend fun resolve(player: Player, title: Title, ep: Episode, cached: Player? = null): Player {
        check(!requiresYummyLogin(player)) { "Войдите в YummyAnime в настройках для этого балансера или выберите Aksor / AnimeLib" }
        if (cached != null && cached.hrefs.isNotEmpty() && cached.source == player.source && cached.team.id == player.team.id && cached.resolverUrl == player.resolverUrl) return cached
        if (player.source == Libria.SOURCE && player.canResolve) return Libria.player(title, ep.number, player)
            ?: error("AniLibria: эта серия недоступна. Выберите другой источник")
        val url = player.resolverUrl ?: return player
        val extractor = extractors.firstOrNull { it.supports(url) }
            ?: error(if (url.contains("alloha", true)) "Alloha не предоставляет поддерживаемый прямой поток. Выберите другой балансер." else "Этот балансер пока не поддерживается")
        val stream = withTimeoutOrNull(25_000) {
            if (extractor is AksorExtractor) extractor.resolveEpisode(url, ep.number,
                if (player.isYummy && SourceIds.isYummy(title.slug)) SourceIds.yummyRemote(title.id) else null)
            else if (extractor is KodikExtractor && player.source == AnimeGo.SOURCE) extractor.resolve(url, "${AnimeGo.SITE}/")
            else extractor.resolve(url)
        } ?: error("${extractor.name}: превышено время ожидания; выберите другой источник")
        return player.copy(player = "animelib", hrefs = stream.qualities, headers = stream.headers)
    }
    fun supported(player: Player): Boolean = if (player.source == Libria.SOURCE) true else player.resolverUrl?.let { url -> extractors.any { it.supports(url) } } ?: (player.isNative || player.isKodik)
    private suspend fun <T> isolated(block: suspend () -> T): T? = try { block() } catch(e: CancellationException) { throw e } catch (_: Exception) { null }
}
