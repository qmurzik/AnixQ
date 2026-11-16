package ru.qmods.anixq.tv.focus

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
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
import ru.qmods.anixq.tv.data.*
import ru.qmods.anixq.tv.ui.AnixTheme
import ru.qmods.anixq.tv.ui.Choice
import ru.qmods.anixq.tv.ui.ChoiceDialog

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class YummySourceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sourcePickerAcceptsDpadSelection() {
        var selected = -1
        rule.setContent { AnixTheme {
            ChoiceDialog("Источник", listOf(Choice("AnimeLib", true), Choice("YummyAnime")), {}) { selected = it }
        } }
        rule.waitForIdle()
        rule.onNodeWithText("AnimeLib").assertExists()
        rule.onNodeWithText("AnimeLib").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithText("AnimeLib").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(1, selected)
    }

    @Test fun legacyAndYummyHistoryRoundTripWithoutIdCollisions() {
        Store.init(ApplicationProvider.getApplicationContext(), "yummy-sync-test")
        Store.positions.clear(); Store.history.clear(); Store.watched.clear(); Store.bookmarks.clear()
        val lib = Title(45, "45--legacy", rusName = "Legacy")
        val yummy = Title(-45, "yummy:45", rusName = "Yummy")
        val titles = JSONObject().put("45", lib.toSync()).put("-45", yummy.toSync())
        fun progress(title: Int, source: String, position: Int, at: Int) = JSONObject()
            .put("title", title).put("ep", "1").put("team", if (title > 0) 1 else -77).put("teamName", "Dub")
            .put("pos", position).put("dur", 1200).put("at", at).put("playbackSource", source).put("balancer", if (title > 0) "" else "Aksor")
        val sync = JSONObject().put("v", 1).put("titles", titles)
            .put("progress", JSONObject().put("77", progress(45,"animelib",120,1)).put("-77", progress(-45,"yummy",300,2)))
        assertTrue(Store.importSync(sync, initial = true))
        assertEquals(120.0, Store.resume(77)!!, 0.01)
        assertEquals(300.0, Store.resume(-77)!!, 0.01)
        val exported = Store.exportSync()
        assertEquals("yummy", exported.getJSONObject("progress").getJSONObject("-77").getString("playbackSource"))
        assertEquals("Aksor", Store.lastEntry(-45)?.balancer)
        // New video_id after host removes a variant must still resume by exact episode number.
        assertEquals(300.0, Store.resumeEpisode(yummy, Episode(-88,"1",null,0))!!, 0.01)
        assertNull(Store.resumeEpisode(yummy, Episode(-89,"2",null,1)))
        Store.positions.clear(); Store.history.clear()
        Store.importSync(exported, initial = true)
        assertEquals(2, Store.history.size)
        assertEquals("45--legacy", Store.lastEntry(45)?.title?.slug)
        assertEquals("yummy:45", Store.lastEntry(-45)?.title?.slug)
        val player = Player(Team(-77,"Dub",false),"yummy",null,emptyMap(),emptyList(),null,null,source="yummy",balancer="Aksor")
        assertEquals(player, Store.playbackChoice(yummy, listOf(player)))
        assertTrue(Store.prefersExternal(yummy))
    }
}
