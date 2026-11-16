package ru.qmods.anixq.tv.panorama

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.qmods.anixq.tv.data.*
import ru.qmods.anixq.tv.data.sources.YummyAnime
import ru.qmods.anixq.tv.ui.*
import ru.qmods.anixq.tv.ui.panorama.*
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class PanoramaTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val fixture by lazy { JSONObject(javaClass.getResourceAsStream("/panorama/titles.json")!!.reader().readText()).getJSONArray("response") }
    private val titles by lazy { (0 until fixture.length()).mapNotNull { YummyAnime.parseTitle(fixture.getJSONObject(it)) } }
    @Before fun setup() {
        context.getSharedPreferences("tv.appearance",0).edit().clear().commit()
        TvAppearance.init(context)
        Store.init(context,"panorama-test")
        Store.bookmarks.clear(); Store.history.clear(); Store.navSounds = false; Store.hoverPreview = false
        Focus.barActive = false
        listOf("panorama:home","panorama:library","panorama:catalog","panorama:search","settings").forEach(Focus::reset)
        val painters = titles.associate { t ->
            val bitmap = javaClass.getResourceAsStream("/panorama/poster-${-t.id}.jpg")!!.use(BitmapFactory::decodeStream)
            t.poster to BitmapPainter(bitmap.asImageBitmap())
        }
        CoverOverride.load = { painters[it] }
    }
    @After fun clean() { TvAppearance.select(TvAppearance.Mode.CLASSIC); CoverOverride.load = null; Focus.barActive = false }
    private fun settleInitialFocus() {
        rule.waitUntil(3000) { rule.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.advanceTimeBy(200)
        rule.waitForIdle()
    }
    private fun capture(name: String) = rule.runOnIdle {
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val path = File("build/reports/panorama/$name.png").apply { parentFile?.mkdirs() }
        path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun interfaceChoicePersistsOnTheDeviceAndDoesNotChangeTheLibrary() {
        assertEquals(TvAppearance.Mode.CLASSIC, TvAppearance.mode.value)
        Store.setBookmark(titles[0],Bookmark.PLANNED)
        TvAppearance.select(TvAppearance.Mode.PANORAMA); TvAppearance.animate(false)
        TvAppearance.init(context)
        assertEquals(TvAppearance.Mode.PANORAMA,TvAppearance.mode.value)
        assertFalse(TvAppearance.motion.value)
        assertEquals(Bookmark.PLANNED,Store.bookmark(titles[0].slug))
        TvAppearance.select(TvAppearance.Mode.CLASSIC)
        assertEquals(Bookmark.PLANNED,Store.bookmark(titles[0].slug))
    }
    @Test fun bothInterfacesCanBeSelectedUsingTheRealSettingsControl() {
        val nav = Nav().apply { picking = false; tab = Tab.SETTINGS }
        rule.setContent { AnixTheme { App(nav) } }
        settleInitialFocus()
        rule.onNodeWithTag("tv-style-PANORAMA").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithTag("tv-style-PANORAMA").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(TvAppearance.Mode.PANORAMA,TvAppearance.mode.value); assertEquals(Tab.SETTINGS,nav.tab) }
        rule.waitUntil(3000) { runCatching { rule.onNodeWithTag("tv-style-PANORAMA").assertIsFocused() }.isSuccess }
        rule.onNodeWithTag("tv-panorama-motion").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithTag("tv-panorama-motion").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertFalse(TvAppearance.motion.value) }
        rule.onNodeWithTag("tv-style-CLASSIC").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithTag("tv-style-CLASSIC").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(TvAppearance.Mode.CLASSIC,TvAppearance.mode.value) }
    }
    @Test fun railDpadEntersContentAndBackReturnsToTheSelectedSection() {
        TvAppearance.select(TvAppearance.Mode.PANORAMA)
        val nav = Nav().apply { picking = false; tab = Tab.LIBRARY }
        rule.setContent { AnixTheme { App(nav) } }
        rule.onNodeWithTag("panorama-nav-LIBRARY").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithTag("panorama-nav-LIBRARY").performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithTag("panorama-nav-LIBRARY").assertIsNotFocused()
        rule.runOnIdle { assertEquals(Tab.LIBRARY,nav.tab); rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("panorama-nav-LIBRARY").assertIsFocused()
        rule.onNodeWithTag("panorama-nav-LIBRARY").performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionDown); pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(Tab.SETTINGS,nav.tab) }
        rule.onNodeWithTag("panorama-nav-SETTINGS").performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithTag("tv-interface-settings").assertExists()
        capture("settings")
    }
    @Test fun alternateLibraryUsesExistingBookmarksAndOpensTheSameTitleRoute() {
        TvAppearance.select(TvAppearance.Mode.PANORAMA)
        titles.forEach { Store.setBookmark(it,Bookmark.PLANNED) }
        val nav = Nav().apply { picking = false; tab = Tab.LIBRARY }
        rule.setContent { AnixTheme { App(nav) } }
        settleInitialFocus()
        rule.onAllNodesWithText("Запланировано", substring = true).onFirst().performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onAllNodesWithText("Запланировано", substring = true).onFirst().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithTag("panorama-title-${titles.first().id}").assertExists()
        capture("library")
        // Verify routing with the production card without launching a decoder or using a server session.
        rule.onNodeWithTag("panorama-title-${titles.first().id}").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithTag("panorama-title-${titles.first().id}").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(titles.first().id,(nav.stack.last() as TitleRoute).title.id) }
    }
    @Test fun heroAndPostersRenderAtTvSizeWithRealCatalogueFixtures() {
        TvAppearance.select(TvAppearance.Mode.PANORAMA)
        val raw = JSONObject(javaClass.getResourceAsStream("/panorama/anilib-title.json")!!.reader().readText()).getJSONObject("data")
        val hero = Title.fromApi(raw)!!
        val background = raw.getJSONObject("background").getString("url")
        val original = CoverOverride.load
        val posterPainter = BitmapPainter(javaClass.getResourceAsStream("/panorama/cinema-poster.jpg")!!.use(BitmapFactory::decodeStream).asImageBitmap())
        val backgroundPainter = BitmapPainter(javaClass.getResourceAsStream("/panorama/cinema-backdrop.jpg")!!.use(BitmapFactory::decodeStream).asImageBitmap())
        CoverOverride.load = { if(it == background) backgroundPainter else if(it == hero.poster) posterPainter else original?.invoke(it) }
        TitleCache.detail[hero.slug] = Detail(hero,"Танджиро отправляется в опасное путешествие, чтобы спасти сестру и найти ответы после нападения демонов.",background,listOf("Приключения","Фэнтези"))
        val nav = Nav()
        rule.setContent { AnixTheme { CinemaTheme { Column(Modifier.fillMaxSize().background(P.bg).padding(start = 84.dp)) {
            PanoramaHero(hero,nav,"panorama:hero-test",hasMore = true)
            CinemaRow("На одном дыхании",titles,"panorama:hero-test",nav)
        } } } }
        rule.onNodeWithTag("panorama-hero-primary").assertExists()
        capture("hero")
        rule.onNodeWithTag("panorama-hero-primary").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(hero.id,(nav.stack.last() as TitleRoute).title.id) }
    }
}
