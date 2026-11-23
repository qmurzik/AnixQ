package ru.qmods.anixq.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.qmods.anixq.tv.data.Bookmark
import ru.qmods.anixq.tv.data.CatalogQuery
import ru.qmods.anixq.tv.data.Title

enum class Tab(val label: String) { HOME("Главная"), CATALOG("Каталог"), LIBRARY("Медиатека"), SEARCH("Поиск") }

sealed interface Route
data class TitleRoute(val title: Title) : Route
/**
 * Episode to play. [episodeId] null = continue where the library says (or the first episode);
 * [startAt] an exact moment (handoff from the TV).
 */
data class PlayerRoute(
    val title: Title,
    val episodeId: Int? = null,
    val teamId: Int? = null,
    val startAt: Double? = null,
    val resume: Boolean = false,
    val next: Boolean = false,
) : Route
data class CatalogRoute(val title: String, val query: CatalogQuery) : Route {
    internal val loader = CatalogLoader()
    internal val stateKey = "catalog-${java.util.UUID.randomUUID()}"
}
data class FolderRoute(val status: Bookmark) : Route
data object HistoryRoute : Route
data object DownloadsRoute : Route
data object CollectionsRoute : Route
data class CollectionRoute(val id: String) : Route
data object ShikiLoginRoute : Route
data object ScheduleRoute : Route
data object RemoteRoute : Route
data object SettingsRoute : Route
data object SyncRoute : Route
data object LibLoginRoute : Route
data object TraceRoute : Route
data object DiagnosticsRoute : Route
data object ReportRoute : Route
data object StatsRoute : Route
data object AchievementsRoute : Route
data object YearCardRoute : Route
data object FriendsRoute : Route
data object ForYouRoute : Route
data object ListTransferRoute : Route
data object BackupRoute : Route
data object LockRoute : Route
data object RandomRoute : Route
data object SeasonRoute : Route
data class PersonRoute(val id: Int, val isCharacter: Boolean, val name: String) : Route
data class CommentsRoute(val postId: Int, val type: String, val title: String) : Route

internal data class SearchResult(
    val query: CatalogQuery,
    val titles: List<Title>,
    val corrected: String?,
)

class Nav {
    internal val catalogLoader = CatalogLoader()
    internal var catalogQuery by mutableStateOf(CatalogQuery())
    internal var searchText by mutableStateOf("")
    internal var searchSource by mutableStateOf("animelib")
    internal var searchResult by mutableStateOf<SearchResult?>(null)
    private var searchScrollQuery: CatalogQuery? = null
    private var searchScroll = androidx.compose.foundation.lazy.LazyListState()

    internal fun searchScrollFor(query: CatalogQuery): androidx.compose.foundation.lazy.LazyListState {
        if (searchScrollQuery != query) {
            searchScrollQuery = query
            searchScroll = androidx.compose.foundation.lazy.LazyListState()
        }
        return searchScroll
    }
    var tab by mutableStateOf(Tab.HOME)
    val stack = mutableStateListOf<Route>()
    val top: Route? get() = stack.lastOrNull()

    var playerRoute by mutableStateOf<PlayerRoute?>(null)
        private set

    fun push(r: Route) {
        if (r is PlayerRoute) play(r)
        else if (stack.lastOrNull() != r) stack.add(r)
    }

    fun pop() {
        if (top is PlayerRoute) closePlayer()
        else if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    fun open(t: Title) = push(TitleRoute(t))

    fun play(r: PlayerRoute) {
        playerRoute = r
        expandPlayer()
    }

    fun minimizePlayer() {
        if (top is PlayerRoute) stack.removeAt(stack.lastIndex)
    }

    fun expandPlayer() {
        val route = playerRoute ?: return
        if (top == route) return
        stack.removeAll { it is PlayerRoute }
        stack.add(route)
    }

    fun closePlayer() {
        stack.removeAll { it is PlayerRoute }
        playerRoute = null
    }
}
