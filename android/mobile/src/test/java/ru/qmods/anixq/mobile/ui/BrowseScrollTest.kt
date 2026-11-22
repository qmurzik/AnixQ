package ru.qmods.anixq.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.qmods.anixq.tv.data.CatalogQuery
import ru.qmods.anixq.tv.data.Title

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BrowseScrollTest {
    @get:Rule val compose = createComposeRule()
    private val nav = Nav()
    private val query = CatalogQuery(text = "Naruto", genre = 8)

    @Test fun catalogRestoresPositionAfterReturningFromTitle() {
        val catalog = CatalogLoader { _, _ -> (1..100).map { Title(it, "title-$it") } to false }
        runBlocking { catalog.ensureLoaded(query) }
        compose.setContent {
            if (nav.top == null) LazyVerticalGrid(GridCells.Fixed(1), Modifier.testTag("catalog"), state = catalog.scroll) {
                items(catalog.items.size) { Box(Modifier.height(48.dp)) }
            }
        }
        compose.onNodeWithTag("catalog").performScrollToIndex(30)
        goToTitleAndBack()
        compose.runOnIdle { assertEquals(30, catalog.scroll.firstVisibleItemIndex) }
    }

    @Test fun searchRestoresPositionAfterReturningFromTitle() {
        compose.setContent {
            if (nav.top == null) {
                val state = remember { nav.searchScrollFor(query) }
                LazyColumn(Modifier.testTag("search"), state = state) {
                    items(100) { Box(Modifier.height(48.dp)) }
                }
            }
        }
        compose.onNodeWithTag("search").performScrollToIndex(30)
        goToTitleAndBack()
        compose.runOnIdle { assertEquals(30, nav.searchScrollFor(query).firstVisibleItemIndex) }
    }

    private fun goToTitleAndBack() {
        compose.runOnIdle { nav.open(Title(31, "title-31")) }
        compose.waitForIdle()
        compose.runOnIdle { nav.pop() }
    }
}
