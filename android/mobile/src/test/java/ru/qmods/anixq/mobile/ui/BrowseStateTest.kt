package ru.qmods.anixq.mobile.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.qmods.anixq.tv.data.CatalogQuery
import ru.qmods.anixq.tv.data.Title

class BrowseStateTest {
    @Test fun returningToAFilteredListKeepsItsLoadedPages() = runBlocking {
        val calls = mutableListOf<Pair<CatalogQuery, Int>>()
        val filtered = CatalogQuery(genre = 8, status = 1)
        val loader = CatalogLoader { query, page ->
            calls += query to page
            listOf(Title(page, "title-$page")) to (page < 3)
        }
        loader.ensureLoaded(filtered)
        loader.loadMore()
        loader.ensureLoaded(filtered)
        assertEquals(listOf(1, 2), loader.items.map { it.id })
        assertEquals(listOf(filtered to 1, filtered to 2), calls)
        loader.loadMore()
        assertEquals(filtered to 3, calls.last())
        val otherQuery = filtered.copy(status = 2)
        loader.ensureLoaded(otherQuery)
        assertEquals(listOf(1), loader.items.map { it.id })
        assertEquals(otherQuery to 1, calls.last())
    }

    @Test fun openingAndClosingATitleKeepsTheSearchAndCatalogNavState() {
        val nav = Nav()
        val title = Title(1, "title")
        val search = SearchResult(CatalogQuery(text = "Naruto", source = "yummy"), listOf(title), null)
        nav.searchText = search.query.text
        nav.searchSource = search.query.source
        nav.searchResult = search
        nav.catalogQuery = CatalogQuery(genre = 8)
        val route = CatalogRoute("Драма", nav.catalogQuery)
        nav.push(route)
        nav.open(title)
        nav.pop()
        assertSame(route, nav.top)
        assertSame(search, nav.searchResult)
        assertEquals("Naruto", nav.searchText)
        assertEquals("yummy", nav.searchSource)
        assertEquals(8, nav.catalogQuery.genre)
        assertNotEquals(route.stateKey, route.copy().stateKey)
    }
}
