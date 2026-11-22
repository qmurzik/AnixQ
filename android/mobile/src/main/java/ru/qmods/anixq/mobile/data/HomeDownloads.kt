package ru.qmods.anixq.mobile.data

import android.content.Context
import android.net.wifi.WifiManager
import ru.qmods.anixq.tv.data.Episode
import ru.qmods.anixq.tv.data.WatchEntry

internal object HomeDownloads {
    private fun prefs(context: Context) = context.getSharedPreferences("home_downloads", Context.MODE_PRIVATE)
    fun network(context: Context): String? = prefs(context).getString("ssid", null)
    fun setNetwork(context: Context, ssid: String?) {
        prefs(context).edit().putString("ssid", ssid).apply()
    }

    @Suppress("DEPRECATION") // Does not depend on Wi-Fi being the default route when a VPN is active.
    fun currentNetwork(context: Context): String? = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)
            ?.connectionInfo?.ssid?.removeSurrounding("\"")
            ?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID }
    }.getOrNull()

    fun isHome(context: Context): Boolean = network(context)?.let { it == currentNetwork(context) } ?: false

    fun dismissed(context: Context, entry: WatchEntry): Boolean =
        System.currentTimeMillis() - prefs(context).getLong("dismissed.${entry.title.id}", 0) < 86_400_000L

    fun dismiss(context: Context, entry: WatchEntry) {
        prefs(context).edit().putLong("dismissed.${entry.title.id}", System.currentTimeMillis()).apply()
    }

    fun nextEpisodes(
        entry: WatchEntry,
        episodes: List<Episode>,
        unavailable: (Episode) -> Boolean,
    ): List<Episode> {
        val current = episodes.indexOfFirst { it.id == entry.episodeId }
            .takeIf { it >= 0 } ?: episodes.indexOfFirst { it.number == entry.ep }
        if (current < 0) return emptyList()
        return episodes.asSequence().drop(current + 1).filterNot(unavailable).take(3).toList()
    }
}
