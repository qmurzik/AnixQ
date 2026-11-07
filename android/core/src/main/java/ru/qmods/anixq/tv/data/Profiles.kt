package ru.qmods.anixq.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class Profile(val id: String, val name: String, val color: Long)

/**
 * Several people on one TV: each profile has its own history, bookmarks, settings and even its own
 * GitHub sync / AnimeLib account. The first profile keeps the original storage.
 */
object Profiles {
    private lateinit var ctx: Context
    private val prefs get() = ctx.getSharedPreferences("anixq-profiles", Context.MODE_PRIVATE)
    val colors = listOf(0xFFFF4D7A, 0xFF8B5CFF, 0xFF34C3FF, 0xFFFF9F43, 0xFF2ED47A, 0xFFFFC94D)

    val list = MutableStateFlow<List<Profile>>(emptyList())
    val current = MutableStateFlow<Profile?>(null)

    fun init(context: Context) {
        ctx = context.applicationContext
        list.value = read()
        current.value = list.value.firstOrNull { it.id == prefs.getString("current", "main") } ?: list.value.first()
    }

    private fun read(): List<Profile> {
        val a = runCatching { JSONArray(prefs.getString("list", "[]")) }.getOrDefault(JSONArray())
        val l = (0 until a.length()).map { a.getJSONObject(it) }.map { Profile(it.getString("id"), it.getString("name"), it.getLong("color")) }
        return l.ifEmpty { listOf(Profile("main", "Я", colors[0])) }
    }

    private fun write(l: List<Profile>) {
        prefs.edit().putString("list", JSONArray().apply { l.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)) } }.toString()).apply()
        list.value = l
    }

    fun prefsName(p: Profile) = if (p.id == "main") "anixq" else "anixq-${p.id}"

    fun add(name: String): Profile {
        val p = Profile("p" + System.currentTimeMillis().toString(36), name.trim().ifEmpty { "Профиль ${list.value.size + 1}" },
            colors[list.value.size % colors.size])
        write(list.value + p)
        return p
    }

    fun rename(p: Profile, name: String) = write(list.value.map { if (it.id == p.id) it.copy(name = name) else it })

    fun remove(p: Profile) {
        if (p.id == "main") return
        write(list.value.filter { it.id != p.id })
        ctx.deleteSharedPreferences(prefsName(p))
        if (current.value?.id == p.id) switchTo(list.value.first())
    }

    /** Swap all per-user data in place. */
    fun switchTo(p: Profile) {
        prefs.edit().putString("current", p.id).apply()
        current.value = p
        Sync.stopLoop()
        Store.init(ctx, prefsName(p))
        Remote.restart()
        Sync.start()
        Sync.syncNow()
    }
}
