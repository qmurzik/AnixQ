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
    private val cvh = CvhExtractor(http)
    val shiki = ShikimoriCatalog(http, cvh)
    val extractors: List<StreamExtractor> = listOf(AksorExtractor(http), cvh, KodikExtractor(http),
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
    /** A service's explicit external ID links catalog cards, with known edition conflicts excluded. */
    fun sameCatalogTitle(a: Title, b: Title): Boolean {
        if (a.id == b.id && a.slug == b.slug) return true
        if (SourceIds.catalogSource(a.slug) == SourceIds.catalogSource(b.slug)) return false
        fun identity(title: Title): Int? {
            if (title.malId != null && title.shikiId != null && title.malId != title.shikiId) return null
            return SourceIds.shikiId(title)
        }
        val id = identity(a) ?: return false
        if (id != identity(b) || a.year != null && b.year != null && a.year != b.year) return false
        val firstType = a.typeLabel?.let(::normalizeType)
        val secondType = b.typeLabel?.let(::normalizeType)
        return firstType == null || secondType == null || firstType == secondType
    }

    fun catalogAliases(title: Title, candidates: Iterable<Title>): List<Title> =
        candidates.filter { sameCatalogTitle(title, it) }.distinctBy { it.slug }
            .groupBy { SourceIds.catalogSource(it.slug) }.values.mapNotNull { it.singleOrNull() }

    suspend fun catalogAlternative(title: Title, source: String): Title? = withTimeoutOrNull(6_000) {
        isolated {
            val candidate = when (source) {
                "shiki" -> SourceIds.shikiId(title)?.let { shiki.detail(it).title }
                "yummy" -> match(title)?.let { yummy.detail(it).title }
                "animelib" -> catalogAliases(title, Api.catalog(CatalogQuery(text = title.title), 1).first).singleOrNull()
                else -> null
            }
            candidate?.takeIf { SourceIds.catalogSource(it.slug) == source && sameCatalogTitle(title, it) }
        }
    }

    private fun normalizeType(s: String) = when(s.lowercase()) {
        "тв", "tv", "tv series", "сериал", "тв сериал", "tv сериал" -> "tv"
        "фильм", "movie", "полнометражный фильм" -> "movie"
        "спешл", "special", "tv_special", "спецвыпуск" -> "special"
        else -> s.lowercase()
    }
    suspend fun detail(title: Title): Detail {
        val id = SourceIds.shikiId(title)
        if (title.id <= 0 || id == null) return Api.detail(title.slug)
        withTimeoutOrNull(4_000) { isolated { Api.detail(title.slug) } }?.let { return it }
        val fallback = shiki.detail(id)
        return fallback.copy(title = fallback.title.copy(id = title.id, slug = title.slug))
    }

    suspend fun episodes(title: Title): List<Episode> {
        val shikiId = SourceIds.shikiId(title)
        if (SourceIds.isYummy(title.slug) || shikiId == null) return Api.episodes(title.slug)
        if (!SourceIds.isShiki(title.slug)) {
            val primary = withTimeoutOrNull(5_000) { isolated { Api.episodes(title.slug) } }
            if (!primary.isNullOrEmpty()) return primary
        }
        val own = withTimeoutOrNull(8_000) { isolated { shiki.episodes(shikiId) } }.orEmpty()
        if (own.isNotEmpty()) return own
        val reserve = withTimeoutOrNull(7_000) {
            isolated { match(title)?.let { yummy.episodes(it) } }
        }.orEmpty()
        return reserve.mapNotNull { episode ->
            val number = episode.number.toIntOrNull()?.takeIf { it in 1..1023 } ?: return@mapNotNull null
            episode.copy(id = SourceIds.shikiEpisode(shikiId, number))
        }.distinctBy { it.id }
    }

    private suspend fun shikiPlayers(title: Title, ep: Episode): List<Player> =
        withTimeoutOrNull(8_000) {
            isolated { SourceIds.shikiId(title)?.let { shiki.players(it, ep.number) } }
        }.orEmpty()

    suspend fun players(title: Title, ep: Episode, includeExternal: Boolean = true, preferredTeamId: Int? = null): List<Player> = coroutineScope {
        if (SourceIds.isShiki(title.slug) || preferredTeamId?.let(SourceIds::isShikiTeam) == true ||
            (ep.id <= 0 && !SourceIds.isYummy(title.slug))) {
            val own = shikiPlayers(title, ep)
            if (own.isNotEmpty()) return@coroutineScope own
            if (ep.id <= 0) return@coroutineScope additional(title, ep, includeShiki = false)
        }
        val lib = async {
            when {
                SourceIds.isYummy(title.slug) -> emptyList()
                !includeExternal && SourceIds.shikiId(title) != null ->
                    withTimeoutOrNull(5_000) { isolated { Api.players(ep.id) } }.orEmpty()
                else -> isolated { Api.players(ep.id) }.orEmpty()
            }
        }
        val extra = if (includeExternal || title.id < 0) async { additional(title, ep) } else null
        val base = lib.await()
        val libria = base.filter { Libria.isLibria(it.team.name) }.distinctBy { it.team.id }.map {
            it.copy(team = it.team.copy(id = Int.MIN_VALUE + it.team.id), player = "external", hrefs = emptyMap(), source = Libria.SOURCE, headers = emptyMap(), resolverUrl = "anilibria:", balancer = null)
        }
        val reserve = extra?.await() ?: if (base.none(::supported)) {
            shikiPlayers(title, ep).ifEmpty { additional(title, ep, includeShiki = false) }
        } else emptyList()
        base + libria + reserve
    }
    suspend fun additional(title: Title, ep: Episode, includeShiki: Boolean = true): List<Player> = coroutineScope {
        val shikiPlayers = if (includeShiki) async { shikiPlayers(title, ep) } else null
        val yummyPlayers = async {
            withTimeoutOrNull(15_000) {
                isolated { match(title)?.let { yummy.players(it, ep.number) } }.orEmpty()
            }.orEmpty()
        }
        val reserve = async {
            withTimeoutOrNull(9_000) { isolated { animeGo.players(title, ep) }.orEmpty() }.orEmpty()
        }
        shikiPlayers?.await().orEmpty() + yummyPlayers.await().sortedBy { player ->
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
        return player.copy(player = "animelib", hrefs = stream.qualities, headers = stream.headers, mimeTypes = stream.mimeTypes)
    }
    fun supported(player: Player): Boolean = if (player.source == Libria.SOURCE) true else player.resolverUrl?.let { url -> extractors.any { it.supports(url) } } ?: (player.isNative || player.isKodik)
    private suspend fun <T> isolated(block: suspend () -> T): T? = try { block() } catch(e: CancellationException) { throw e } catch (_: Exception) { null }
}
