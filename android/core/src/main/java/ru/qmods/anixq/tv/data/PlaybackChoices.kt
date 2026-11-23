package ru.qmods.anixq.tv.data

/** Shared names and grouping rules; raw provider names remain intact for resolving and sync. */
object PlaybackLabels {
    fun source(key: String) = playbackSourceLabel(key)
    fun balancer(raw: String?): String = raw?.replace(Regex("(?iu)^плеер\\s+"), "")?.trim().orEmpty().let {
        when { it.isBlank() -> "Основной"; it.equals("cdnvideohub", true) -> "CVH"; else -> it }
    }
    fun dub(raw: String): String {
        val clean = raw.replace(Regex("(?iu)^(?:(?:озвучка|дубляж|субтитры|subtitles)\\s+)+"), "").trim()
        return when (clean.lowercase().replace(Regex("[\\s.\\-]"), "")) {
            "anilibria", "anilibriatv", "анилибрия", "анилибрияtv" -> "AniLibria"
            "studioband" -> "StudioBand"
            else -> clean.ifBlank { raw }
        }
    }
    fun dubKey(team: Team) = (if (team.isSubtitles) "sub:" else "dub:") + dub(team.name).lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
}

data class PlaybackGroup(val key: String, val label: String, val count: Int)

object PlaybackChoices {
    fun options(players: List<Player>): List<TeamOption> = players.groupBy { it.source to it.team.id }.values.map { ps ->
        val p = ps.first()
        TeamOption(p.team, ps.any { it.isNative || it.canResolve }, ps.any { it.isKodik }, p.sourceLabel, p.balancer, p.source)
    }.distinctBy { Triple(it.source, it.balancerKey, PlaybackLabels.dubKey(it.team)) }
    fun sources(options: List<TeamOption>) = options.groupBy { it.source }.map { (key, values) -> PlaybackGroup(key, PlaybackLabels.source(key), values.size) }
    fun balancers(options: List<TeamOption>, current: TeamOption?) = options.filter { it.source == current?.source }
        .groupBy { it.balancerKey }.map { (key, values) -> PlaybackGroup(key, values.first().balancerLabel, values.size) }
    fun dubs(options: List<TeamOption>, current: TeamOption?) = options.filter { it.source == current?.source && it.balancerKey == current?.balancerKey }
        .distinctBy { PlaybackLabels.dubKey(it.team) }.sortedWith(compareBy({ it.team.isSubtitles }, { it.dubLabel.lowercase() }))
    fun chooseSource(options: List<TeamOption>, current: TeamOption?, source: String): TeamOption? = prefer(options.filter { it.source == source }, current)
    fun chooseBalancer(options: List<TeamOption>, current: TeamOption?, balancer: String): TeamOption? = prefer(options.filter { it.source == current?.source && it.balancerKey == balancer }, current)
    private fun prefer(options: List<TeamOption>, current: TeamOption?): TeamOption? {
        val sameDub = options.filter { current != null && PlaybackLabels.dubKey(it.team) == PlaybackLabels.dubKey(current.team) }
        val sameKind = options.filter { it.team.isSubtitles == current?.team?.isSubtitles }.ifEmpty { options }
        return sameDub.firstOrNull { it.balancerKey == current?.balancerKey } ?: sameDub.firstOrNull()
            ?: sameKind.firstOrNull { it.balancerKey == current?.balancerKey } ?: sameKind.firstOrNull()
    }
    fun sameDub(a: String, b: String): Boolean = PlaybackLabels.dub(a).equals(PlaybackLabels.dub(b), true)
}
