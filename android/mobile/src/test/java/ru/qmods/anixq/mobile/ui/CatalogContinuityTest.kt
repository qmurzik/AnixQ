package ru.qmods.anixq.mobile.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.qmods.anixq.mobile.data.PlaybackHistory
import ru.qmods.anixq.tv.data.Episode
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Team
import ru.qmods.anixq.tv.data.Title
import ru.qmods.anixq.tv.data.sources.SourceIds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogContinuityTest {
    private val animeLib = Title(154, "154--ice", shikiId = 64534, typeLabel = "ТВ сериал", year = 2026)
    private val shiki = Title(SourceIds.shikiTitle(64534), "shiki:64534", shikiId = 64534, malId = 64534, typeLabel = "TV", year = 2026)
    private val yummy = Title(SourceIds.yummy(202), "yummy:202", malId = 64534, typeLabel = "TV", year = 2026)

    @Test fun legacyHistoryRestoresEpisodeAndTimecodeAcrossCatalogs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Store.init(context, "legacy-catalog-continuity")
        PlaybackHistory.init(context)
        PlaybackHistory.remember(animeLib, listOf("Драма"))
        val legacy = JSONObject()
            .put("titles", JSONObject().put(animeLib.id.toString(), animeLib.toSync()))
            .put("progress", JSONObject().put("990", position(animeLib, "03", 120.0, 100.0)))
        Store.importSync(legacy, initial = true)

        val episode = Episode(SourceIds.shikiEpisode(64534, 3), "3", null, 2)
        assertEquals("03", PlaybackHistory.lastEntry(shiki)?.ep)
        assertEquals(120.0, Store.resumeEpisode(shiki, episode)!!, 0.0)
        assertEquals(120.0, Store.resumeEpisode(yummy, episode.copy(id = -930))!!, 0.0)
        assertNull(Store.resumeEpisode(shiki.copy(year = 2024), episode.copy(id = -900)))
        assertNull(Store.resumeEpisode(shiki.copy(typeLabel = "OVA"), episode.copy(id = -900)))
        assertNull(Store.resumeEpisode(shiki, episode.copy(id = -910, number = "4")))
    }

    @Test fun earlierEpisodePositionsSurviveTheSyncRoundTrip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Store.init(context, "catalog-sync-writer")
        val ep1 = Episode(1001, "1", null, 0)
        val ep2 = Episode(1002, "2", null, 1)
        Store.updateProgress(animeLib, ep1, Team(4, "Dub", false), 54.0, 1000.0)
        Store.updateProgress(animeLib, ep2, Team(4, "Dub", false), 330.0, 1000.0)
        val synced = Store.exportSync()
        assertEquals("1", synced.getJSONObject("progress").getJSONObject("1001").getString("refEpisode"))
        Store.init(context, "catalog-sync-reader")
        Store.importSync(synced, initial = true)

        assertEquals("2", Store.lastEntry(shiki)?.ep)
        assertEquals(54.0, Store.resumeEpisode(shiki, ep1.copy(id = SourceIds.shikiEpisode(64534, 1)))!!, 0.0)
        assertEquals(330.0, Store.resumeEpisode(yummy, ep2.copy(id = -1002))!!, 0.0)
    }

    @Test fun newerCompletedEpisodeSupersedesOlderPartialProgress() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Store.init(context, "catalog-newer-position")
        val synced = JSONObject()
            .put("titles", JSONObject().put(animeLib.id.toString(), animeLib.toSync()).put(shiki.id.toString(), shiki.toSync()))
            .put("progress", JSONObject().put("208", position(animeLib, "2", 30.0, 200.0))
                .put("-209", position(shiki, "2", 999.0, 300.0)))
        Store.importSync(synced, initial = true)
        assertNull(Store.resumeEpisode(animeLib, Episode(208, "2", null, 1)))
        assertEquals("shiki:64534", Store.lastEntry(animeLib)?.title?.slug)
    }

    @Test fun ambiguousMatchesDoNotShareTimecodes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Store.init(context, "catalog-ambiguous-test")
        val duplicate = animeLib.copy(id = 155, slug = "155--ice")
        val synced = JSONObject()
            .put("titles", JSONObject().put(animeLib.id.toString(), animeLib.toSync()).put(duplicate.id.toString(), duplicate.toSync()))
            .put("progress", JSONObject().put("208", position(animeLib, "2", 30.0, 200.0))
                .put("209", position(duplicate, "2", 80.0, 300.0)))
        Store.importSync(synced, initial = true)
        assertNull(Store.resumeEpisode(shiki, Episode(-901, "2", null, 1)))
        assertNull(Store.lastEntry(shiki))
    }

    @Test fun catalogSettingUpdatesSearchAndCardFallbackKeepsTheSameRoute() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Store.init(context, "catalog-default-test")
        Store.defaultCatalog = "shiki"
        Store.init(context, "catalog-other-profile")
        assertEquals("animelib", Store.defaultCatalog)
        Store.init(context, "catalog-default-test")
        val nav = Nav(Store.defaultCatalog)
        assertEquals("shiki", nav.catalogQuery.source)
        assertEquals("shiki", nav.searchSource)

        nav.open(animeLib)
        val route = nav.top as TitleRoute
        route.openCatalogTitle(shiki)
        nav.play(PlayerRoute(shiki))
        nav.pop()
        assertSame(route, nav.top)
        assertSame(shiki, route.catalogTitle)
        assertEquals(1, nav.stack.size)
        nav.updateDefaultCatalog("yummy")
        assertEquals("yummy", nav.catalogQuery.source)
        assertEquals("yummy", nav.searchSource)
    }

    private fun position(title: Title, number: String, seconds: Double, at: Double): JSONObject =
        JSONObject().put("title", title.id).put("ep", number).put("team", 4)
            .put("pos", seconds).put("dur", 1000).put("at", at)
}
