package ru.qmods.anixq.tv.data

import org.junit.Assert.*
import org.junit.Test

class PlaybackChoicesTest {
    private fun option(id: Int, source: String, balancer: String?, dub: String, subtitles: Boolean = false) =
        TeamOption(Team(id, dub, subtitles), true, false, PlaybackLabels.source(source), balancer, source)
    @Test fun largeProviderListsBecomeThreeSmallIndependentPickers() {
        val options = listOf(option(1,"animelib",null,"AniLibria.TV"),option(2,"anilibria",null,"AniLibria.TV")) +
            listOf("Плеер CVH","Плеер Kodik").flatMapIndexed { n, provider -> (0..19).map { i -> option(-1-n*20-i,"yummy",provider,"Озвучка Студия $i") } }
        assertEquals(3, PlaybackChoices.sources(options).size)
        val selected = options[2]
        assertEquals(listOf("CVH","Kodik"), PlaybackChoices.balancers(options,selected).map { it.label })
        assertEquals(20, PlaybackChoices.dubs(options,selected).size)
        assertTrue(PlaybackChoices.dubs(options,selected).all { !it.dubLabel.contains("Озвучка") && !it.dubLabel.contains("YummyAnime") })
    }
    @Test fun switchingBalancerPreservesDubDespiteDisplayPrefixes() {
        val cvh = option(-1,"yummy","Плеер CVH","Озвучка AniLibria")
        val kodik = option(-2,"yummy","Плеер Kodik","AniLibria.TV")
        val other = option(-3,"yummy","Плеер Kodik","Озвучка Indie Dub")
        assertEquals(kodik, PlaybackChoices.chooseBalancer(listOf(cvh,other,kodik),cvh,kodik.balancerKey))
    }
    @Test fun sourceSwitchPreservesMatchingStudioAndSubtitleIdentity() {
        val lib = option(1,"animelib",null,"Studio Band")
        val yummy = option(-1,"yummy","Плеер Aksor","Дубляж StudioBand")
        val sub = option(-2,"yummy","Плеер Aksor","Субтитры StudioBand",true)
        assertEquals(yummy, PlaybackChoices.chooseSource(listOf(lib,sub,yummy),lib,"yummy"))
        assertNotEquals(PlaybackLabels.dubKey(sub.team),PlaybackLabels.dubKey(yummy.team))
    }
    @Test fun duplicateVideosDoNotDuplicateVoiceRows() {
        val team = Team(-1,"Озвучка AniLibria",false)
        val one = Player(team,"yummy",null,emptyMap(),emptyList(),null,null,source="yummy",resolverUrl="https://player.aksor.tv/video/a",balancer="Плеер Aksor")
        assertEquals(1, PlaybackChoices.options(listOf(one,one.copy(team=team.copy(id=-2)))).size)
        assertEquals("AniLibria",PlaybackLabels.dub("AniLibria.TV"))
    }
}
