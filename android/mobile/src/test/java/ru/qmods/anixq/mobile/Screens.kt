package ru.qmods.anixq.mobile

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.qmods.anixq.mobile.ui.AnixTheme
import ru.qmods.anixq.mobile.ui.FolderRoute
import ru.qmods.anixq.mobile.ui.HomeData
import ru.qmods.anixq.mobile.ui.LocalImageOverride
import ru.qmods.anixq.mobile.ui.Nav
import ru.qmods.anixq.mobile.ui.RemoteRoute
import ru.qmods.anixq.mobile.ui.SettingsRoute
import ru.qmods.anixq.mobile.ui.SyncRoute
import ru.qmods.anixq.mobile.ui.Tab
import ru.qmods.anixq.mobile.ui.TitleCache
import ru.qmods.anixq.tv.data.Bookmark
import ru.qmods.anixq.tv.data.Detail
import ru.qmods.anixq.tv.data.Episode
import ru.qmods.anixq.tv.data.Remote
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Team
import ru.qmods.anixq.tv.data.TeamOption
import ru.qmods.anixq.tv.data.Title

/** Phone screens rendered on the JVM with real covers: `gradle :mobile:recordPaparazziDebug`. */
class Screens {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 100.0)

    private val titles: List<Title> by lazy {
        val a = JSONArray(javaClass.getResourceAsStream("/fake/titles.json")!!.reader().readText())
        (0 until a.length()).map { i ->
            val x = a.getJSONObject(i)
            Title(x.getInt("id"), x.getString("slug"), x.getString("rus"), cover = "fake://c$i", coverThumb = "fake://c$i",
                rating = x.optDouble("rating"), typeLabel = x.optString("type"), statusId = 1,
                year = x.optString("year").split(" ").mapNotNull { it.toIntOrNull() }.firstOrNull { it > 1900 }, age = x.optString("age"))
        }
    }

    private val cache = HashMap<String, Painter>()
    private val images: (String) -> Painter? = { url ->
        if (!url.startsWith("fake://")) null else cache.getOrPut(url) {
            val bmp = javaClass.getResourceAsStream("/fake/${url.removePrefix("fake://")}.jpg")!!.use { BitmapFactory.decodeStream(it) }
            BitmapPainter(bmp.asImageBitmap())
        }
    }

    @Before fun setup() {
        Store.devicePrefix = "and"; Remote.isTv = false
        Store.init(paparazzi.context)
        HomeData.rows = mapOf("updated" to titles.take(9), "ongoing" to titles.drop(4), "popular" to titles.reversed(), "top" to titles.drop(2), "newest" to titles)
        HomeData.hero = titles.take(5).map { it to "fake://bg0" }
        HomeData.loadedAt = System.currentTimeMillis()
    }

    private fun shot(name: String, nav: Nav) = paparazzi.snapshot(name) {
        CompositionLocalProvider(LocalImageOverride provides images) { AnixTheme { Box(Modifier.fillMaxSize()) { Root(nav) } } }
    }

    private fun progress() {
        titles.take(4).forEachIndexed { i, t -> Store.updateProgress(t, Episode(1000 + i, "${i + 3}", "Серия", i), Team(1, "AniLibria", false), 300.0 + i * 200, 1420.0) }
        Store.setBookmark(titles[5], Bookmark.WATCHING); Store.setBookmark(titles[6], Bookmark.WATCHING); Store.setBookmark(titles[7], Bookmark.PLANNED)
    }

    @Test fun home() { progress(); shot("home", Nav()) }

    @Test fun homeTv() {
        progress()
        Remote.tvState.value = JSONObject().put("state", "playing").put("title", titles[0].title).put("slug", titles[0].slug).put("titleId", titles[0].id)
            .put("cover", "fake://c0").put("ep", "7").put("episodeId", 1).put("pos", 640.0).put("dur", 1420.0).put("device", "Mi TV")
            .put("at", System.currentTimeMillis() / 1000.0)
        shot("home-tv", Nav())
    }

    @Test fun catalog() = shot("catalog", Nav().apply { tab = Tab.CATALOG })
    @Test fun search() = shot("search", Nav().apply { tab = Tab.SEARCH })
    @Test fun library() { progress(); shot("library", Nav().apply { tab = Tab.LIBRARY }) }
    @Test fun folder() { progress(); shot("folder", Nav().apply { tab = Tab.LIBRARY; push(FolderRoute(Bookmark.WATCHING)) }) }
    @Test fun settings() = shot("settings", Nav().apply { push(SettingsRoute) })
    @Test fun syncLogin() { ru.qmods.anixq.tv.data.Sync.deviceLogin.value = "8F2A-91C3" to "https://github.com/login/device"; shot("sync", Nav().apply { push(SyncRoute) }) }

    @Test fun title() {
        val t = titles[0]
        TitleCache.detail[t.slug] = Detail(t, "Монки Д. Луффи — юный пират, который мечтает найти легендарное сокровище Ван-Пис и стать Королём пиратов. Вместе с командой «Соломенной шляпы» он плывёт по Гранд Лайн навстречу приключениям.",
            "fake://bg0", listOf("Приключения", "Комедия", "Сёнэн", "Фэнтези"))
        TitleCache.episodes[t.slug] = (1..30).map { Episode(5000 + it, "$it", if (it % 3 == 0) "Название серии $it" else null, it - 1) }
        TitleCache.teams[t.slug] = listOf(TeamOption(Team(1, "AniLibria", false), true, true), TeamOption(Team(2, "Studio Band", false), false, true))
        Store.updateProgress(t, Episode(5004, "4", null, 3), Team(1, "AniLibria", false), 512.0, 1420.0)
        Store.setBookmark(t, Bookmark.WATCHING)
        shot("title", Nav().apply { open(t) })
    }

    @Test fun titleNoArt() {
        val t = titles[2]
        TitleCache.detail[t.slug] = Detail(t, "Короткое описание тайтла для проверки вёрстки без широкого фона.", null, listOf("Экшен", "Фэнтези"))
        TitleCache.episodes[t.slug] = (1..12).map { Episode(6000 + it, "$it", null, it - 1) }
        shot("title-noart", Nav().apply { open(t) })
    }

    @Test fun remote() {
        Remote.tvState.value = JSONObject().put("state", "playing").put("title", titles[1].title).put("slug", titles[1].slug).put("titleId", titles[1].id)
            .put("cover", "fake://c1").put("ep", "5").put("team", "AniLibria").put("episodeId", 1).put("pos", 512.0).put("dur", 1420.0)
            .put("hasNext", true).put("hasPrev", true).put("inOpening", true).put("device", "Mi TV").put("at", System.currentTimeMillis() / 1000.0)
        shot("remote", Nav().apply { push(RemoteRoute) })
    }
}
