package ru.qmods.anixq.tv

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import coil.Coil
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.qmods.anixq.tv.data.Episode
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Team
import ru.qmods.anixq.tv.data.Title
import ru.qmods.anixq.tv.ui.AnixTheme
import ru.qmods.anixq.tv.ui.App
import ru.qmods.anixq.tv.ui.HomeData
import ru.qmods.anixq.tv.ui.Nav
import ru.qmods.anixq.tv.ui.Tab

/** Local renders of the TV screens with real covers (no emulator needed). `gradle recordPaparazziDebug`. */
class Screens {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(screenWidth = 1920, screenHeight = 1080, xdpi = 320, ydpi = 320, density = Density.XHIGH,
            orientation = ScreenOrientation.LANDSCAPE, softButtons = false),
        maxPercentDifference = 100.0,
    )

    private val titles: List<Title> by lazy {
        val a = JSONArray(javaClass.getResourceAsStream("/fake/titles.json")!!.reader().readText())
        (0 until a.length()).map { i ->
            val x = a.getJSONObject(i)
            Title(x.getInt("id"), x.getString("slug"), x.getString("rus"), cover = "fake://c$i", coverThumb = "fake://c$i",
                rating = x.optDouble("rating"), typeLabel = x.optString("type"), statusId = 1,
                year = x.optString("year").split(" ").mapNotNull { it.toIntOrNull() }.firstOrNull { it > 1900 }, age = x.optString("age"))
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    @Before fun setup() {
        val ctx = paparazzi.context
        Coil.setImageLoader(ImageLoader.Builder(ctx).dispatcher(Dispatchers.Unconfined).components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                    val name = chain.request.data.toString().removePrefix("fake://")
                    val bmp = javaClass.getResourceAsStream("/fake/$name.jpg")!!.use { BitmapFactory.decodeStream(it) }
                    return SuccessResult(BitmapDrawable(ctx.resources, bmp), chain.request, DataSource.MEMORY)
                }
            })
        }.build())
        val cache = HashMap<String, androidx.compose.ui.graphics.painter.Painter>()
        ru.qmods.anixq.tv.ui.CoverOverride.load = load@{ url ->
            if (!url.startsWith("fake://")) return@load null
            val name = url.removePrefix("fake://")
            cache.getOrPut(name) {
                val bmp = javaClass.getResourceAsStream("/fake/$name.jpg")!!.use { BitmapFactory.decodeStream(it) }
                androidx.compose.ui.graphics.painter.BitmapPainter(bmp.asImageBitmap())
            }
        }
        Store.init(ctx)
        HomeData.rows.value = listOf("Новые серии" to titles.take(9), "Популярные онгоинги" to titles.drop(5), "Популярное" to titles.reversed())
    }

    private val registry = object : androidx.activity.result.ActivityResultRegistryOwner {
        override val activityResultRegistry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I,
                                         options: androidx.core.app.ActivityOptionsCompat?) {}
        }
    }

    private fun shot(name: String, content: @Composable () -> Unit) = paparazzi.snapshot(name) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides registry) { AnixTheme(content) }
    }

    @Test fun home() = shot("home") { App(Nav()) }

    @Test fun homeContinue() {
        titles.take(4).forEachIndexed { i, t -> Store.updateProgress(t, Episode(1000 + i, "${i + 3}", "Серия", i), Team(1, "AniLibria", false), 300.0 + i * 200, 1420.0) }
        shot("home-continue") { App(Nav()) }
    }

    @Test fun title() {
        val t = titles[0]
        ru.qmods.anixq.tv.ui.TitleCache.detail[t.slug] = ru.qmods.anixq.tv.data.Detail(t,
            "Монки Д. Луффи — юный пират, который мечтает найти легендарное сокровище Ван-Пис и стать Королём пиратов. Вместе с командой «Соломенной шляпы» он плывёт по Гранд Лайн навстречу приключениям.",
            "fake://bg0", listOf("Приключения", "Комедия", "Сёнэн", "Фэнтези"))
        ru.qmods.anixq.tv.ui.TitleCache.episodes[t.slug] = (1..30).map { Episode(5000 + it, "$it", if (it % 3 == 0) "Название серии $it" else null, it - 1) }
        ru.qmods.anixq.tv.ui.TitleCache.teams[t.slug] = listOf(ru.qmods.anixq.tv.data.TeamOption(Team(1, "AniLibria", false), true, true),
            ru.qmods.anixq.tv.data.TeamOption(Team(2, "Studio Band", false), false, true))
        Store.updateProgress(t, Episode(5004, "4", null, 3), Team(1, "AniLibria", false), 512.0, 1420.0)
        shot("title") { App(Nav().apply { open(t) }) }
    }

    @Test fun titleNoArt() {
        val t = titles[2]
        ru.qmods.anixq.tv.ui.TitleCache.detail[t.slug] = ru.qmods.anixq.tv.data.Detail(t, "Короткое описание тайтла для проверки вёрстки без широкого фона.", null, listOf("Экшен", "Фэнтези"))
        ru.qmods.anixq.tv.ui.TitleCache.episodes[t.slug] = (1..12).map { Episode(6000 + it, "$it", null, it - 1) }
        shot("title-noart") { App(Nav().apply { open(t) }) }
    }

    private fun playerModel(): ru.qmods.anixq.tv.ui.PlayerModel {
        val t = titles[1]
        val m = ru.qmods.anixq.tv.ui.PlayerModel(paparazzi.context, kotlinx.coroutines.MainScope(), t, ru.qmods.anixq.tv.ui.PlayerRoute(t, null))
        m.episodes = (1..12).map { Episode(7000 + it, "$it", if (it == 6) "Решающая битва" else null, it - 1) }
        m.index = 4
        m.players = listOf(ru.qmods.anixq.tv.data.Player(Team(1, "AniLibria", false), "Animelib", null, mapOf("1080p" to "a", "720p" to "b"),
            listOf(ru.qmods.anixq.tv.data.Segment("opening", 85.0, 175.0), ru.qmods.anixq.tv.data.Segment("ending", 1290.0, 1380.0)), 1, null),
            ru.qmods.anixq.tv.data.Player(Team(2, "Studio Band", false), "Kodik", "//k", emptyMap(), emptyList(), 2, null))
        m.player = m.players[0]; m.quality = "1080p"; m.message = null; m.dur = 1420.0; m.playing = true
        return m
    }

    @Composable private fun FakeVideo() = androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
        ru.qmods.anixq.tv.ui.Cover("fake://bg0", androidx.compose.ui.Modifier.fillMaxSize())
    }

    @Test fun playerControls() {
        val m = playerModel(); m.pos = 512.0
        shot("player-controls") { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) { FakeVideo()
            ru.qmods.anixq.tv.ui.Controls(m, androidx.compose.ui.focus.FocusRequester(), androidx.compose.ui.focus.FocusRequester(), {}, {}, {}, {}) } }
    }

    @Test fun playerSeekAndSkip() {
        val m = playerModel(); m.pos = 120.0
        shot("player-seek") { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) { FakeVideo()
            ru.qmods.anixq.tv.ui.ActionCard("Пропустить опенинг", "нажмите OK", androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(48.dp))
            ru.qmods.anixq.tv.ui.SeekPreview(m, 30.0, androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter)) } }
    }

    @Test fun playerNextAndEpisodes() {
        val m = playerModel(); m.pos = 1300.0
        shot("player-next") { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) { FakeVideo()
            ru.qmods.anixq.tv.ui.ActionCard("Серия 6 · Решающая битва", "через 7 с  ·  OK — сейчас",
                androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(48.dp), progress = 0.7f)
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter)) { ru.qmods.anixq.tv.ui.EpisodesPanel(m) {} } } }
    }

    @Test fun library() {
        titles.take(5).forEachIndexed { i, t -> Store.updateProgress(t, Episode(1000 + i, "${i + 3}", null, i), Team(1, "AniLibria", false), 1400.0, 1420.0)
            Store.markWatched(t, Episode(1000 + i, "${i + 3}", null, i)) }
        shot("library") { App(Nav().apply { tab = Tab.LIBRARY }) }
    }

    @Test fun playerPause() {
        val m = playerModel(); m.pos = 640.0; m.playing = false
        shot("player-pause") { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) { FakeVideo(); ru.qmods.anixq.tv.ui.PauseScreen(m) } }
    }

    @Test fun homeGenres() {
        ru.qmods.anixq.tv.ui.GenreTiles.list.value = listOf(1 to "Экшен", 2 to "Романтика", 3 to "Комедия", 4 to "Фэнтези", 5 to "Исекай", 6 to "Драма")
        shot("home-genres") { App(Nav()) }
    }

    @Test fun catalog() = shot("catalog") { App(Nav().apply { tab = Tab.CATALOG }) }
    @Test fun search() = shot("search") { App(Nav().apply { tab = Tab.SEARCH }) }
    @Test fun settings() = shot("settings") { App(Nav().apply { tab = Tab.SETTINGS }) }
}
