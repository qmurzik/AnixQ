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
data class CatalogRoute(val title: String, val query: CatalogQuery) : Route
data class FolderRoute(val status: Bookmark) : Route
data object HistoryRoute : Route
data object RemoteRoute : Route
data object SettingsRoute : Route
data object SyncRoute : Route
data object LibLoginRoute : Route

class Nav {
    var tab by mutableStateOf(Tab.HOME)
    val stack = mutableStateListOf<Route>()
    val top: Route? get() = stack.lastOrNull()

    fun push(r: Route) { if (stack.lastOrNull() != r) stack.add(r) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun open(t: Title) = push(TitleRoute(t))
    fun play(r: PlayerRoute) { stack.removeAll { it is PlayerRoute }; stack.add(r) }
}
