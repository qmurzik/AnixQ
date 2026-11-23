package ru.qmods.anixq.tv.focus

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.qmods.anixq.tv.data.Episode
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Title
import ru.qmods.anixq.tv.data.sources.SourceIds
import ru.qmods.anixq.tv.ui.*

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class TvCatalogContinuityTest {
    @get:Rule val compose = createComposeRule()

    private val animeLib = Title(95, "95--ice", rusName = "Ледяная стена 2", shikiId = 64534, year = 2026)
    private val shiki = Title(SourceIds.shikiTitle(64534), "shiki:64534", shikiId = 64534, malId = 64534, year = 2026)

    @Test fun dpadOpensAnotherCatalogInTheSameCard() {
        Store.init(ApplicationProvider.getApplicationContext(), "tv-fallback-focus")
        val nav = Nav("shiki")
        nav.open(animeLib)
        val original = nav.stack.single() as TitleRoute
        val data = TvTitleData(animeLib).apply {
            needsAlternative = true
            alternatives = listOf(shiki)
        }
        compose.setContent {
            AnixTheme {
                Column {
                    PillButton("Повторить", modifier = Modifier.memo("test-fallback", "retry", initial = true)) {}
                    TvCatalogFallback(data, "test-fallback", nav)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Повторить").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Повторить").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Открыть в Шикимори").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            assertSame(original, nav.stack.single())
            assertSame(shiki, original.catalogTitle)
            assertEquals("shiki", CatalogState.query.source)
        }
    }

    @Test fun tvResumesTheSyncedPhoneEpisodeByNumber() {
        Store.init(ApplicationProvider.getApplicationContext(), "tv-catalog-continuity")
        Store.importSync(JSONObject()
            .put("titles", JSONObject().put(animeLib.id.toString(), animeLib.toSync()))
            .put("progress", JSONObject().put("211", JSONObject().put("title", animeLib.id)
                .put("ep", "03").put("pos", 135).put("dur", 1200).put("at", 200).put("team", 4))),
            initial = true)
        val episodes = (1..4).map { Episode(SourceIds.shikiEpisode(64534, it), it.toString(), null, it - 1) }
        val plan = resumePlan(shiki, episodes)
        assertEquals(episodes[2], plan?.episode)
        assertEquals(135.0, Store.resumeEpisode(shiki, episodes[2])!!, 0.0)
        val nav = Nav("shiki")
        nav.updateDefaultCatalog("yummy")
        assertEquals("yummy", CatalogState.query.source)
        assertEquals("shiki", nextTvCatalog("yummy"))
    }
}
