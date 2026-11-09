package ru.qmods.anixq.mobile.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import ru.qmods.anixq.tv.data.Title

/** Your own lists ("На выходные", "Для друга"…), shareable by a link — the same format as on the iPhone. */
object Collections {
    data class Item(val slug: String, val title: String, val cover: String?)
    data class Collection(val id: String, val name: String, val emoji: String, val items: List<Item>)

    private lateinit var app: Context
    val all = MutableStateFlow<List<Collection>>(emptyList())
    private const val COVER = "https://cover.imglib.info/uploads/anime/"

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        all.value = runCatching {
            val a = JSONArray(app.getSharedPreferences("collections", Context.MODE_PRIVATE).getString("all", "[]"))
            (0 until a.length()).map { i ->
                val c = a.getJSONObject(i); val it = c.getJSONArray("items")
                Collection(c.getString("id"), c.getString("name"), c.optString("emoji", "📺"),
                    (0 until it.length()).map { j -> it.getJSONObject(j).let { x -> Item(x.getString("slug"), x.getString("title"), x.optString("cover").ifEmpty { null }) } })
            }
        }.getOrDefault(emptyList())
    }

    private fun save() {
        val a = JSONArray()
        all.value.forEach { c ->
            a.put(JSONObject().put("id", c.id).put("name", c.name).put("emoji", c.emoji).put("items", JSONArray().apply {
                c.items.forEach { put(JSONObject().put("slug", it.slug).put("title", it.title).put("cover", it.cover ?: "")) }
            }))
        }
        app.getSharedPreferences("collections", Context.MODE_PRIVATE).edit().putString("all", a.toString()).apply()
    }

    fun create(name: String, emoji: String): Collection {
        val c = Collection(java.util.UUID.randomUUID().toString(), name.trim().ifEmpty { "Подборка" }, emoji, emptyList())
        all.value = all.value + c; save(); return c
    }

    fun delete(id: String) { all.value = all.value.filter { it.id != id }; save() }

    fun contains(c: Collection, t: Title) = c.items.any { it.slug == t.slug }

    fun toggle(id: String, t: Title) {
        all.value = all.value.map { c ->
            if (c.id != id) c else if (contains(c, t)) c.copy(items = c.items.filter { it.slug != t.slug })
            else c.copy(items = c.items + Item(t.slug, t.title, t.coverThumb ?: t.cover))
        }
        save()
    }

    /** qmurzik.github.io/AnixQ/list/#… — the whole list travels in the link (opens in AnixQ or on the site). */
    fun shareUrl(c: Collection): String {
        val items = JSONArray()
        c.items.forEach { i ->
            items.put(JSONArray().put(i.slug).put(i.title).apply { i.cover?.let { put(if (it.startsWith(COVER)) it.removePrefix(COVER) else it) } })
        }
        val json = JSONObject().put("n", c.name).put("e", c.emoji).put("i", items).toString()
        val b64 = Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        return "https://qmurzik.github.io/AnixQ/list/#$b64"
    }

    /** A shared list opened from a link: saved as a new collection. */
    fun import(token: String): Collection? = runCatching {
        val j = JSONObject(String(Base64.decode(token, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
        val a = j.getJSONArray("i")
        val items = (0 until a.length()).mapNotNull { k ->
            val x = a.getJSONArray(k); if (x.length() < 2) return@mapNotNull null
            val cover = x.optString(2).ifEmpty { null }?.let { if (it.startsWith("http")) it else COVER + it }
            Item(x.getString(0), x.getString(1), cover)
        }
        Collection(java.util.UUID.randomUUID().toString(), j.optString("n", "Подборка"), j.optString("e", "📺"), items)
            .also { all.value = all.value + it; save() }
    }.getOrNull()
}
