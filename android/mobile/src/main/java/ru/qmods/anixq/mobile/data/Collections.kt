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

/** Your notes and own score per title, plus notes for single episodes — like the iPhone's "Мои заметки". */
object Notes {
    data class Note(val text: String = "", val rating: Int? = null, val episodes: Map<String, String> = emptyMap()) {
        val isEmpty get() = text.isBlank() && rating == null && episodes.isEmpty()
    }

    private lateinit var app: Context
    val all = MutableStateFlow<Map<Int, Note>>(emptyMap())

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        reload()
    }

    private fun reload() {
        all.value = runCatching {
            val o = JSONObject(app.getSharedPreferences("notes", Context.MODE_PRIVATE).getString("all", "{}")!!)
            o.keys().asSequence().mapNotNull { k ->
                val x = o.optJSONObject(k) ?: return@mapNotNull null
                val eps = x.optJSONObject("episodes")?.let { e -> e.keys().asSequence().associateWith { e.optString(it) } } ?: emptyMap()
                k.toIntOrNull()?.let { it to Note(x.optString("text"), x.optInt("rating").takeIf { r -> r > 0 }, eps) }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun get(titleId: Int) = all.value[titleId] ?: Note()

    fun set(titleId: Int, n: Note) {
        all.value = if (n.isEmpty) all.value - titleId else all.value + (titleId to n)
        val o = JSONObject()
        all.value.forEach { (id, x) ->
            o.put(id.toString(), JSONObject().put("text", x.text).put("rating", x.rating ?: 0)
                .put("episodes", JSONObject().apply { x.episodes.forEach { (k, v) -> put(k, v) } }))
        }
        app.getSharedPreferences("notes", Context.MODE_PRIVATE).edit().putString("all", o.toString()).apply()
    }

    fun setEpisode(titleId: Int, number: String, text: String) {
        val n = get(titleId)
        set(titleId, n.copy(episodes = if (text.isBlank()) n.episodes - number else n.episodes + (number to text.trim())))
    }

    /** For backups. */
    fun export(): JSONObject = JSONObject(app.getSharedPreferences("notes", Context.MODE_PRIVATE).getString("all", "{}")!!)
    fun import(o: JSONObject) { app.getSharedPreferences("notes", Context.MODE_PRIVATE).edit().putString("all", o.toString()).apply(); reload() }
}
