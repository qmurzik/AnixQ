package ru.qmods.anixq.mobile.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import ru.qmods.anixq.tv.data.sources.AnimeSources
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Title
import ru.qmods.anixq.tv.data.sources.SourceIds
import java.util.concurrent.ConcurrentHashMap

/** Phone policy for automatic watch records and history surfaces. */
internal object PlaybackHistory {
    private const val RETRY_MS = 60 * 1000L
    @Volatile private var prefs: SharedPreferences? = null
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val retryAt = ConcurrentHashMap<String, Long>()
    private val slots = Semaphore(4)
    private val changes = MutableStateFlow(0)
    val revision = changes.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("playback_metadata", Context.MODE_PRIVATE)
    }

    fun canRecord(title: Title): Boolean = SourceIds.isYummy(title.slug) || cached(title) == true

    fun lastEntry(title: Title) = Store.lastEntry(title.id)?.takeIf { canRecord(it.title) }

    fun entries() = Store.history.filter { canRecord(it.title) }.sortedByDescending { it.at }

    fun continueEntries() = entries().take(20)

    internal fun permitsGenres(genres: List<String>): Boolean? {
        if (genres.any { it.trim().equals("Хентай", true) || it.trim().equals("Hentai", true) }) return false
        return true.takeIf { genres.any(String::isNotBlank) }
    }

    private fun cached(title: Title): Boolean? = when (prefs?.getString(title.slug, null)) {
        "allow" -> true
        "hide" -> false
        else -> null
    }

    fun remember(title: Title, genres: List<String>) {
        if (SourceIds.isYummy(title.slug)) return
        val allowed = permitsGenres(genres) ?: return
        if (cached(title) == allowed) return
        prefs?.edit()?.putString(title.slug, if (allowed) "allow" else "hide")?.apply()
        changes.update { it + 1 }
    }

    suspend fun classify(title: Title) {
        if (SourceIds.isYummy(title.slug) || cached(title) != null) return
        val now = System.currentTimeMillis()
        if ((retryAt[title.slug] ?: 0) > now || !pending.add(title.slug)) return
        try {
            val detail = slots.withPermit { withTimeoutOrNull(10_000) { AnimeSources.detail(title) } }
            if (detail != null && detail.genres.isNotEmpty()) remember(title, detail.genres)
            else retryAt[title.slug] = now + RETRY_MS
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            retryAt[title.slug] = now + RETRY_MS
        } finally {
            pending.remove(title.slug)
        }
    }

    suspend fun classifyHistory() = coroutineScope {
        val titles = Store.history.sortedByDescending { it.at }.map { it.title }.distinctBy { it.slug }
        for (batch in titles.chunked(4)) batch.map { async { classify(it) } }.awaitAll()
    }
}
