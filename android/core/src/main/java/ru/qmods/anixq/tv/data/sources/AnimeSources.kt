package ru.qmods.anixq.tv.data.sources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import ru.qmods.anixq.tv.data.*
import ru.qmods.anixq.tv.data.sources.extractors.*

/** Shared orchestration for phone and TV; an unavailable catalogue never disables the other. */
object AnimeSources {
    val yummy = YummyAnime()
    private val http = SourceHttp()
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
    suspend fun players(title: Title, ep: Episode, includeYummy: Boolean = true): List<Player> = coroutineScope {
        val lib = async { if (SourceIds.isYummy(title.slug)) emptyList() else isolated { Api.players(ep.id) }.orEmpty() }
        val extra = async { if (includeYummy || title.id < 0) additional(title, ep) else emptyList() }
        val base = lib.await()
        val libria = base.filter { Libria.isLibria(it.team.name) }.distinctBy { it.team.id }.map {
            it.copy(team = it.team.copy(id = Int.MIN_VALUE + it.team.id), player = "external", hrefs = emptyMap(), source = Libria.SOURCE, headers = emptyMap(), resolverUrl = "anilibria:", balancer = null)
        }
        base + libria + extra.await()
    }
    suspend fun additional(title: Title, ep: Episode): List<Player> {
        if (yummy.userToken.isNullOrBlank() || yummy.applicationToken.isNullOrBlank()) return emptyList()
        return withTimeoutOrNull(15_000) {
        isolated { match(title)?.let { yummy.players(it, ep.number).sortedBy { p -> extractors.indexOfFirst { e -> e.supports(p.resolverUrl.orEmpty()) }.let { n -> if (n < 0) Int.MAX_VALUE else n } } } }.orEmpty()
        }.orEmpty()
    }
    suspend fun resolve(player: Player, title: Title, ep: Episode): Player {
        if (player.source == Libria.SOURCE && player.canResolve) return Libria.player(title, ep.number, player)
            ?: error("AniLibria: эта серия недоступна. Выберите другой источник")
        val url = player.resolverUrl ?: return player
        val extractor = extractors.firstOrNull { it.supports(url) }
            ?: error(if (url.contains("alloha", true)) "Alloha не предоставляет поддерживаемый прямой поток. Выберите другой балансер." else "Этот балансер пока не поддерживается")
        val stream = withTimeoutOrNull(25_000) { extractor.resolve(url) } ?: error("${extractor.name}: превышено время ожидания; выберите другой источник")
        return player.copy(player = "animelib", hrefs = stream.qualities, headers = stream.headers)
    }
    fun supported(player: Player): Boolean = if (player.source == Libria.SOURCE) true else player.resolverUrl?.let { url -> extractors.any { it.supports(url) } } ?: (player.isNative || player.isKodik)
    private suspend fun <T> isolated(block: suspend () -> T): T? = try { block() } catch(e: CancellationException) { throw e } catch (_: Exception) { null }
}
