package ru.qmods.anixq.tv.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Episodes watched on the TV also count on your Shikimori profile (account handed over from the iPhone). */
object Shiki {
    private const val HOST = "https://shikimori.io"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun markEpisode(malId: Int?, episode: String) {
        val n = episode.toDoubleOrNull()?.toInt() ?: return
        if (malId == null || Store.shikiJson == null) return
        scope.launch {
            try {
                val uid = Store.shikiJson?.int("userId") ?: return@launch
                val existing = (call("GET", "/api/v2/user_rates?user_id=$uid&target_id=$malId&target_type=Anime") as? JSONArray)?.optJSONObject(0)
                if (existing != null) {
                    if ((existing.int("episodes") ?: 0) >= n) return@launch
                    call("PATCH", "/api/v2/user_rates/${existing.int("id")}", JSONObject().put("user_rate", JSONObject().put("episodes", n)))
                } else {
                    call("POST", "/api/v2/user_rates", JSONObject().put("user_rate", JSONObject().put("user_id", uid).put("target_id", malId)
                        .put("target_type", "Anime").put("status", "watching").put("episodes", n)))
                }
            } catch (e: Exception) { Log.d("AnixQ", "shiki: ${e.message}") }
        }
    }

    /** Your score (1–10) on Shikimori; the title becomes "completed" if it wasn't in the list. */
    fun setScore(malId: Int?, score: Int, onDone: (Boolean) -> Unit = {}) {
        if (malId == null || Store.shikiJson == null) { onDone(false); return }
        scope.launch {
            val ok = try {
                val uid = Store.shikiJson?.int("userId") ?: throw Exception("no user")
                val existing = (call("GET", "/api/v2/user_rates?user_id=$uid&target_id=$malId&target_type=Anime") as? JSONArray)?.optJSONObject(0)
                if (existing != null) call("PATCH", "/api/v2/user_rates/${existing.int("id")}", JSONObject().put("user_rate", JSONObject().put("score", score)))
                else call("POST", "/api/v2/user_rates", JSONObject().put("user_rate", JSONObject().put("user_id", uid).put("target_id", malId)
                    .put("target_type", "Anime").put("status", "completed").put("score", score)))
                true
            } catch (e: Exception) { Log.d("AnixQ", "shiki score: ${e.message}"); false }
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    /** Your list entry for a title on Shikimori (status / score / episodes), null when not in the list. */
    data class Rate(val id: Int, val status: String, val score: Int, val episodes: Int, val rewatches: Int)

    suspend fun rate(malId: Int): Rate? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val uid = Store.shikiJson?.int("userId") ?: return@withContext null
        runCatching {
            (call("GET", "/api/v2/user_rates?user_id=$uid&target_id=$malId&target_type=Anime") as? JSONArray)?.optJSONObject(0)?.let {
                Rate(it.int("id") ?: 0, it.str("status") ?: "planned", it.int("score") ?: 0, it.int("episodes") ?: 0, it.int("rewatches") ?: 0)
            }
        }.getOrNull()
    }

    /** Create or change your list entry; null fields stay as they are. Returns the entry or null on failure. */
    suspend fun saveRate(malId: Int, status: String? = null, score: Int? = null, episodes: Int? = null): Rate? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val uid = Store.shikiJson?.int("userId") ?: return@withContext null
        runCatching {
            val fields = JSONObject()
            status?.let { fields.put("status", it) }; score?.let { fields.put("score", it) }; episodes?.let { fields.put("episodes", it) }
            val existing = (call("GET", "/api/v2/user_rates?user_id=$uid&target_id=$malId&target_type=Anime") as? JSONArray)?.optJSONObject(0)
            val r = if (existing != null) call("PATCH", "/api/v2/user_rates/${existing.int("id")}", JSONObject().put("user_rate", fields))
            else call("POST", "/api/v2/user_rates", JSONObject().put("user_rate", fields.put("user_id", uid).put("target_id", malId)
                .put("target_type", "Anime").apply { if (!has("status")) put("status", "planned") }))
            (r as? JSONObject)?.let { Rate(it.int("id") ?: 0, it.str("status") ?: "planned", it.int("score") ?: 0, it.int("episodes") ?: 0, it.int("rewatches") ?: 0) }
        }.getOrNull()
    }

    suspend fun removeRate(rateId: Int): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        runCatching { call("DELETE", "/api/v2/user_rates/$rateId"); true }.getOrDefault(false)
    }

    /** Every anime in your Shikimori list (for the MAL export). */
    data class ListEntry(val malId: Int, val name: String, val status: String, val score: Int, val episodes: Int, val totalEpisodes: Int)
    suspend fun allRates(): List<ListEntry> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val uid = Store.shikiJson?.int("userId") ?: return@withContext emptyList()
        val out = ArrayList<ListEntry>()
        for (page in 1..20) {
            val a = runCatching { call("GET", "/api/users/$uid/anime_rates?limit=500&page=$page") as? JSONArray }.getOrNull() ?: break
            for (i in 0 until a.length()) {
                val x = a.optJSONObject(i) ?: continue; val an = x.obj("anime") ?: continue
                out += ListEntry(an.int("id") ?: continue, an.str("name") ?: "", x.str("status") ?: "planned", x.int("score") ?: 0,
                    x.int("episodes") ?: 0, an.int("episodes") ?: 0)
            }
            if (a.length() < 500) break
        }
        out
    }

    val userId: Int? get() = Store.shikiJson?.int("userId")

    /** Hours / episodes / completed titles from the Shikimori profile (episodes × episode length, rewatches included). */
    data class Totals(val minutes: Double, val episodes: Int, val completed: Int)
    @Volatile var totals: Totals? = null; private set

    suspend fun loadTotals(): Totals? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val uid = Store.shikiJson?.int("userId") ?: return@withContext null
        var minutes = 0.0; var episodes = 0; var completed = 0
        for (page in 1..60) {
            val q = "{ userRates(userId: $uid, targetType: Anime, limit: 50, page: $page) { episodes rewatches status anime { duration episodes } } }"
            val rates = runCatching {
                Api.http.newCall(Request.Builder().url("$HOST/api/graphql").header("User-Agent", "AnixQ")
                    .post(JSONObject().put("query", q).toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                    JSONObject(r.body!!.string()).getJSONObject("data").getJSONArray("userRates")
                }
            }.getOrNull() ?: return@withContext if (page == 1) null else Totals(minutes, episodes, completed).also { totals = it }
            for (i in 0 until rates.length()) {
                val x = rates.getJSONObject(i); val a = x.optJSONObject("anime")
                val dur = (a?.optInt("duration") ?: 24).takeIf { it > 0 } ?: 24
                val watched = x.optInt("episodes") + x.optInt("rewatches") * (a?.optInt("episodes") ?: 0)
                episodes += watched; minutes += watched * dur.toDouble()
                if (x.optString("status") == "completed") completed++
            }
            if (rates.length() < 50) break
        }
        Totals(minutes, episodes, completed).also { totals = it }
    }

    private fun call(method: String, path: String, body: JSONObject? = null, retried: Boolean = false): Any? {
        val s = Store.shikiJson ?: return null
        val rb = Request.Builder().url(HOST + path).header("User-Agent", "AnixQ").header("Accept", "application/json")
        val token = s.str("token")
        if (token != null) rb.header("Authorization", "Bearer $token")
        else s.str("cookies")?.let { c -> rb.header("Cookie", c); s.str("csrf")?.let { rb.header("X-CSRF-Token", it) } }
        when (method) {
            "GET" -> rb.get()
            else -> rb.method(method, (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
        }
        Api.http.newCall(rb.build()).execute().use { r ->
            if (r.code == 401 && !retried && refresh()) return call(method, path, body, true)
            if (!r.isSuccessful) throw Exception("Shikimori ${r.code}")
            val text = r.body?.string().orEmpty()
            return if (text.trimStart().startsWith("[")) JSONArray(text) else if (text.isBlank()) null else JSONObject(text)
        }
    }

    private fun refresh(): Boolean {
        val s = Store.shikiJson ?: return false
        val id = s.str("clientId") ?: return false
        val secret = s.str("clientSecret") ?: return false
        val refresh = s.str("refresh") ?: return false
        return try {
            val form = FormBody.Builder().add("grant_type", "refresh_token").add("client_id", id).add("client_secret", secret)
                .add("refresh_token", refresh).build()
            Api.http.newCall(Request.Builder().url("$HOST/oauth/token").header("User-Agent", "AnixQ").post(form).build()).execute().use { r ->
                if (!r.isSuccessful) return false
                val j = JSONObject(r.body!!.string())
                Store.setShiki(s.put("token", j.optString("access_token")).put("refresh", j.optString("refresh_token", refresh)))
                true
            }
        } catch (e: Exception) { false }
    }
}
