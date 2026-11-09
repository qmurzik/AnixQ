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
