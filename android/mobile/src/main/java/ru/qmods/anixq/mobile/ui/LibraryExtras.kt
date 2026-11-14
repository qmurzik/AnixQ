package ru.qmods.anixq.mobile.ui

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.util.Xml
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import ru.qmods.anixq.mobile.data.Collections
import ru.qmods.anixq.mobile.data.Notes
import ru.qmods.anixq.tv.data.Bookmark
import ru.qmods.anixq.tv.data.Franchise
import ru.qmods.anixq.tv.data.Shiki
import ru.qmods.anixq.tv.data.ShikiExtras
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Title

// MARK: - Friends (Shikimori)

@Composable
fun FriendsScreen(nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var friends by remember { mutableStateOf<List<ShikiExtras.Friend>?>(null) }
    var feed by remember { mutableStateOf<List<ShikiExtras.HistoryEntry>?>(null) }
    var opening by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        val uid = Shiki.userId ?: run { friends = emptyList(); feed = emptyList(); return@LaunchedEffect }
        val f = ShikiExtras.friends(uid); friends = f
        feed = coroutineScope { f.take(20).map { async { ShikiExtras.history(it, 10) } }.awaitAll() }.flatten().sortedByDescending { it.date }.take(80)
    }
    fun open(shikiId: Int?, names: List<String>, key: Int) {
        shikiId ?: return
        opening = key
        scope.launch {
            val t = Franchise.titleFor(shikiId, names.filter { it.isNotBlank() }); opening = null
            if (t != null) nav.open(t) else Toast.makeText(context, "Этого тайтла нет на AnimeLib", Toast.LENGTH_SHORT).show()
        }
    }
    Column(Modifier.fillMaxSize().background(Pal.bg).statusBarsPadding()) {
        TopBar("Друзья", { nav.pop() })
        if (Store.shikiJson == null) { EmptyState(Icons.Default.Group, "Войдите в Shikimori", "Лента друзей берётся из вашего профиля Shikimori"); return@Column }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Capsule("Лента", selected = tab == 0) { tab = 0 }
            Capsule("Друзья (${friends?.size ?: 0})", selected = tab == 1) { tab = 1 }
        }
        if (tab == 0) {
            val f = feed
            if (f == null) { Loading(); return@Column }
            if (f.isEmpty()) { EmptyState(Icons.Default.Group, if (friends.isNullOrEmpty()) "У вас пока нет друзей на Shikimori" else "Друзья давно ничего не отмечали"); return@Column }
            LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(f, key = { it.id }) { h ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.card).clickable { open(h.shikiId, listOfNotNull(h.russian, h.name), h.id) }.padding(10.dp)) {
                        Img(h.image, Modifier.width(52.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(Pal.cardHi))
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(20.dp).clip(CircleShape).background(Pal.cardHi)) { Img(h.user?.avatar, Modifier.fillMaxSize()) }
                                Text("  ${h.user?.nickname ?: ""}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("  ${ago(h.date)}", fontSize = 12.sp, color = Pal.muted)
                            }
                            Text(h.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                            Text(h.text, fontSize = 13.sp, color = Pal.muted, maxLines = 2)
                        }
                        if (opening == h.id) CircularProgressIndicator(Modifier.size(18.dp), color = Pal.pink, strokeWidth = 2.dp)
                    }
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp)) {
                items(friends.orEmpty(), key = { it.id }) { fr ->
                    Row(Modifier.fillMaxWidth().clickable {
                        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("${ShikiExtras.HOST}/${android.net.Uri.encode(fr.nickname)}"))) }
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Pal.cardHi)) { Img(fr.avatar, Modifier.fillMaxSize()) }
                        Text(fr.nickname, fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }
}

// MARK: - "Для вас": recommendations from your scores, next parts of finished franchises, abandoned titles

@Composable
fun ForYouScreen(nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    data class Pick(val id: Int, val name: String, val russian: String?, val image: String?, val score: Double?, val because: MutableList<String>, var weight: Double)
    var recs by remember { mutableStateOf<List<Pick>?>(null) }
    var recsNote by remember { mutableStateOf<String?>(null) }
    var continuations by remember { mutableStateOf<List<Title>?>(null) }
    var opening by remember { mutableStateOf<Int?>(null) }
    val abandoned = remember {
        val now = System.currentTimeMillis() / 1000.0
        val stopped = setOf(Bookmark.DROPPED, Bookmark.ON_HOLD, Bookmark.COMPLETED).flatMap { Store.titles(it) }.map { it.id }.toSet()
        Store.history.filter { now - it.at > 30 * 86400 && it.progress < 0.92 && it.title.id !in stopped }.sortedByDescending { it.at }.take(12)
    }
    LaunchedEffect(Unit) {
        launch {
            if (Store.shikiJson == null) { recsNote = "Подключите Shikimori — рекомендации строятся по вашим оценкам."; recs = emptyList(); return@launch }
            val rates = Shiki.allRates()
            val known = rates.map { it.malId }.toSet() + Bookmark.entries.flatMap { Store.titles(it) }.mapNotNull { it.shikiId }
            val seeds = rates.filter { it.score >= 7 }.sortedByDescending { it.score }.take(10)
            if (seeds.isEmpty()) { recsNote = "Поставьте оценки 7+ хотя бы паре тайтлов — и здесь появятся рекомендации."; recs = emptyList(); return@launch }
            val picks = HashMap<Int, Pick>()
            coroutineScope {
                seeds.map { seed -> async { seed to similar(seed.malId) } }.awaitAll()
            }.forEach { (seed, list) ->
                list.take(12).forEachIndexed { rank, x ->
                    val id = x.optInt("id").takeIf { it > 0 } ?: return@forEachIndexed
                    if (id in known) return@forEachIndexed
                    val w = (seed.score - 5) * (1.0 - rank * 0.05)
                    val p = picks.getOrPut(id) { Pick(id, x.optString("name"), x.optString("russian").ifBlank { null },
                        ShikiExtras.absolute(x.optJSONObject("image")?.optString("original")), x.optDouble("score").takeIf { !it.isNaN() && it > 0 }, mutableListOf(), 0.0) }
                    p.weight += w; if (seed.name !in p.because) p.because += seed.name
                }
            }
            recs = picks.values.sortedByDescending { it.weight + (it.score ?: 0.0) * 0.3 }.take(30)
        }
        launch {
            val finished = (Store.titles(Bookmark.COMPLETED) + Store.titles(Bookmark.FAVORITE) + Store.history.filter { it.progress > 0.92 }.map { it.title }).distinctBy { it.id }
            val known = (Store.history.mapNotNull { it.title.shikiId } + Bookmark.entries.flatMap { Store.titles(it) }.mapNotNull { it.shikiId }).toSet()
            val out = ArrayList<Title>()
            for (t in finished.take(14)) {
                val sid = t.shikiId ?: continue
                val nodes = runCatching { Franchise.nodes(sid) }.getOrDefault(emptyList())
                val me = nodes.firstOrNull { it.shikiId == sid } ?: continue
                val next = nodes.firstOrNull { it.date > me.date && it.isMain && it.shikiId !in known && it.date < System.currentTimeMillis() / 1000.0 } ?: continue
                Franchise.title(next)?.let { n -> if (out.none { it.id == n.id }) out += n }
            }
            continuations = out
        }
    }
    Column(Modifier.fillMaxSize().background(Pal.bg).statusBarsPadding()) {
        TopBar("Для вас", { nav.pop() })
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                SectionHeader("Рекомендации")
                when {
                    recs == null -> Loading(Modifier.fillMaxWidth().height(120.dp))
                    recs!!.isEmpty() -> Text(recsNote ?: "Пока нечего посоветовать", color = Pal.muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
                    else -> LazyRow(contentPadding = PaddingValues(16.dp, 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(recs!!, key = { it.id }) { p ->
                            Column(Modifier.width(118.dp).clickable(enabled = opening == null) {
                                opening = p.id
                                scope.launch {
                                    val t = Franchise.titleFor(p.id, listOfNotNull(p.russian, p.name)); opening = null
                                    if (t != null) nav.open(t) else Toast.makeText(context, "Этого тайтла нет на AnimeLib", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Box {
                                    Img(p.image, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(Pal.card))
                                    if (opening == p.id) CircularProgressIndicator(Modifier.align(Alignment.Center).size(22.dp), color = Pal.pink, strokeWidth = 2.dp)
                                }
                                Text(p.russian ?: p.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                Text("Похоже на «${p.because.first()}»", fontSize = 11.sp, color = Pal.muted, maxLines = 2)
                            }
                        }
                    }
                }
            }
            item {
                val c = continuations
                if (c == null) { SectionHeader("Продолжения франшиз"); Loading(Modifier.fillMaxWidth().height(120.dp)) }
                else Shelf("Продолжения франшиз", c) { nav.open(it) }
            }
            if (abandoned.isNotEmpty()) {
                item { Column(Modifier.padding(top = 26.dp)) { SectionHeader("Давно не открывали"); Text("Начали больше месяца назад и не досмотрели.", fontSize = 13.sp, color = Pal.muted, modifier = Modifier.padding(horizontal = 16.dp)) } }
                items(abandoned, key = { "a" + it.title.id }) { e ->
                    Row(Modifier.fillMaxWidth().clickable { nav.open(e.title) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PosterImage(e.title.thumb, Modifier.width(44.dp), 6.dp)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(e.title.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Серия ${e.ep} · ${ago((e.at * 1000).toLong())}", fontSize = 12.sp, color = Pal.muted)
                        }
                        Text("Убрать", color = Pal.muted, fontSize = 13.sp, modifier = Modifier.clickable { Store.removeFromHistory(e.title.id) }.padding(8.dp))
                    }
                }
            }
        }
    }
}

private suspend fun similar(id: Int): List<JSONObject> = withContext(Dispatchers.IO) {
    runCatching {
        ru.qmods.anixq.tv.data.Api.http.newCall(okhttp3.Request.Builder().url("${ShikiExtras.HOST}/api/animes/$id/similar").header("User-Agent", "AnixQ").build())
            .execute().use { r -> org.json.JSONArray(r.body!!.string()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } }
    }.getOrDefault(emptyList())
}

// MARK: - MyAnimeList XML export / import

private val malStatus = mapOf("watching" to "Watching", "completed" to "Completed", "on_hold" to "On-Hold", "dropped" to "Dropped", "planned" to "Plan to Watch", "rewatching" to "Watching")
private val bookmarkOfMal = mapOf("watching" to Bookmark.WATCHING, "completed" to Bookmark.COMPLETED, "on-hold" to Bookmark.ON_HOLD,
    "dropped" to Bookmark.DROPPED, "plan to watch" to Bookmark.PLANNED, "1" to Bookmark.WATCHING, "2" to Bookmark.COMPLETED, "3" to Bookmark.ON_HOLD,
    "4" to Bookmark.DROPPED, "6" to Bookmark.PLANNED)

@Composable
fun ListTransferScreen(nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var xml by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/xml")) { uri ->
        val text = xml ?: return@rememberLauncherForActivityResult
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
            .onSuccess { Toast.makeText(context, "Файл сохранён", Toast.LENGTH_SHORT).show() }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = "import"; summary = null
        scope.launch {
            val entries = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { parseMal(it) } }.getOrNull() } ?: emptyList()
            var added = 0; var notFound = 0
            entries.forEachIndexed { i, e ->
                progress = i + 1 to entries.size
                val t = Franchise.titleFor(e.malId, listOf(e.name))
                if (t == null) { notFound++; return@forEachIndexed }
                bookmarkOfMal[e.status.lowercase()]?.let { if (Store.bookmark(t.slug) != it) { Store.setBookmark(t, it); added++ } }
                Store.markWatchedUpTo(t, e.watched)
            }
            progress = null; busy = null
            summary = if (entries.isEmpty()) "В файле не нашлось тайтлов — нужен XML из MyAnimeList или Shikimori"
                else "Тайтлов: ${entries.size}, в закладки добавлено $added" + if (notFound > 0) ", нет на AnimeLib: $notFound" else ""
        }
    }
    Column(Modifier.fillMaxSize().background(Pal.bg).statusBarsPadding()) {
        TopBar("Экспорт и импорт (MAL)", { nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Group("Экспорт", "Формат MyAnimeList XML — его принимают MyAnimeList, Shikimori (Настройки → Импорт), AniList и другие.") {
                GroupRow(if (busy == "export") "Готовим файл…" else "Создать файл экспорта", icon = Icons.Default.Upload, iconColor = Pal.blue, divider = false) {
                    if (busy != null) return@GroupRow
                    busy = "export"
                    scope.launch { xml = buildMal(); busy = null; save.launch("anixq-list.xml") }
                }
            }
            Group("Импорт", "Экспорт из MyAnimeList или Shikimori (XML; .xml.gz сначала распакуйте). Тайтлы ищутся на AnimeLib и добавляются в закладки, просмотренные серии отмечаются.") {
                GroupRow("Выбрать XML-файл", icon = Icons.Default.Download, iconColor = Pal.green, divider = false) { if (busy == null) open.launch(arrayOf("*/*")) }
            }
            progress?.let { (d, t) -> Text("Импорт: $d из $t", color = Pal.muted, modifier = Modifier.padding(16.dp)) }
            summary?.let { Text(it, modifier = Modifier.padding(16.dp)) }
        }
    }
}

private data class MalEntry(val malId: Int, val name: String, val status: String, val watched: Int, val score: Int)

private fun parseMal(input: java.io.InputStream): List<MalEntry> {
    val p = Xml.newPullParser(); p.setInput(input, null)
    val out = ArrayList<MalEntry>()
    var cur: MutableMap<String, String>? = null; var tag = ""
    while (p.next() != XmlPullParser.END_DOCUMENT) {
        when (p.eventType) {
            XmlPullParser.START_TAG -> { tag = p.name; if (tag == "anime") cur = HashMap() }
            XmlPullParser.TEXT -> cur?.let { m -> if (tag.isNotEmpty() && p.text.isNotBlank()) m[tag] = (m[tag] ?: "") + p.text.trim() }
            XmlPullParser.END_TAG -> {
                if (p.name == "anime") cur?.let { m ->
                    val id = m["series_animedb_id"]?.toIntOrNull()
                    if (id != null && id > 0) out += MalEntry(id, m["series_title"] ?: "", m["my_status"] ?: "", m["my_watched_episodes"]?.toIntOrNull() ?: 0, m["my_score"]?.toIntOrNull() ?: 0)
                }
                if (p.name == "anime") cur = null
                tag = ""
            }
        }
    }
    return out
}

private suspend fun buildMal(): String {
    val esc = { s: String -> s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") }
    val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<myanimelist>\n<myinfo><user_export_type>1</user_export_type></myinfo>\n")
    val shiki = if (Store.shikiJson != null) Shiki.allRates() else emptyList()
    if (shiki.isNotEmpty()) shiki.forEach { r ->
        sb.append("<anime><series_animedb_id>${r.malId}</series_animedb_id><series_title><![CDATA[${r.name}]]></series_title><series_episodes>${r.totalEpisodes}</series_episodes>")
            .append("<my_watched_episodes>${r.episodes}</my_watched_episodes><my_score>${r.score}</my_score><my_status>${malStatus[r.status] ?: "Plan to Watch"}</my_status><update_on_import>1</update_on_import></anime>\n")
    } else {
        val status = mapOf(Bookmark.WATCHING to "Watching", Bookmark.COMPLETED to "Completed", Bookmark.ON_HOLD to "On-Hold", Bookmark.DROPPED to "Dropped",
            Bookmark.PLANNED to "Plan to Watch", Bookmark.REWATCHING to "Watching", Bookmark.FAVORITE to "Completed")
        status.forEach { (b, s) ->
            Store.titles(b).forEach { t ->
                val sid = t.shikiId ?: return@forEach
                sb.append("<anime><series_animedb_id>$sid</series_animedb_id><series_title>${esc(t.name ?: t.title)}</series_title>")
                    .append("<my_watched_episodes>${Store.watched[t.id]?.size ?: 0}</my_watched_episodes><my_score>${Notes.get(t.id).rating ?: 0}</my_score><my_status>$s</my_status><update_on_import>1</update_on_import></anime>\n")
            }
        }
    }
    return sb.append("</myanimelist>\n").toString()
}

// MARK: - Backup (file)

@Composable
fun BackupScreen(nav: Nav) {
    val context = LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val o = JSONObject().put("app", "AnixQ Android").put("v", 1).put("at", System.currentTimeMillis())
            .put("library", Store.exportSync()).put("notes", Notes.export()).put("collections", Collections.export()).put("settings", Store.exportPrefs())
        runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(o.toString().toByteArray()) } }
            .onSuccess { note = "Резервная копия сохранена" }.onFailure { note = "Не удалось сохранить: ${it.message}" }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        note = runCatching {
            val o = JSONObject(context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
            o.optJSONObject("library")?.let { Store.importSync(it) }
            o.optJSONObject("notes")?.let { Notes.import(it) }
            o.optJSONArray("collections")?.let { Collections.importAll(it) }
            o.optJSONObject("settings")?.let { Store.importPrefs(it) }
            "Восстановлено: история, закладки, заметки, подборки и настройки"
        }.getOrElse { "Это не резервная копия AnixQ" }
    }
    Column(Modifier.fillMaxSize().background(Pal.bg).statusBarsPadding()) {
        TopBar("Резервная копия", { nav.pop() })
        Group(null, "В файл попадают история, позиции в сериях, закладки, заметки, подборки и настройки. Входы в аккаунты не сохраняются.") {
            GroupRow("Сохранить в файл", icon = Icons.Default.Backup, iconColor = Pal.blue) {
                save.launch("anixq-backup-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.json")
            }
            GroupRow("Восстановить из файла", icon = Icons.Default.Restore, iconColor = Pal.orange, divider = false) { open.launch(arrayOf("application/json", "*/*")) }
        }
        note?.let { Text(it, modifier = Modifier.padding(16.dp)) }
    }
}

// MARK: - App lock (fingerprint / face / screen lock), private bookmark folders

object AppLock {
    private fun prefs(c: Context) = c.getSharedPreferences("lock", Context.MODE_PRIVATE)
    var unlocked by mutableStateOf(false)
    private var unlockedFolders = mutableSetOf<Bookmark>()
    private var backgroundAt = 0L

    val supported get() = Build.VERSION.SDK_INT >= 29
    fun appLocked(c: Context) = supported && prefs(c).getBoolean("app", false)
    fun setAppLocked(c: Context, on: Boolean) { prefs(c).edit().putBoolean("app", on).apply(); if (on) unlocked = true }
    fun lockedFolders(c: Context): Set<Bookmark> = if (!supported) emptySet() else
        (prefs(c).getStringSet("folders", emptySet()) ?: emptySet()).mapNotNull { r -> Bookmark.of(r.toIntOrNull()) }.toSet()
    fun setFolderLocked(c: Context, b: Bookmark, on: Boolean) {
        val s = lockedFolders(c).map { it.raw.toString() }.toMutableSet()
        if (on) s += b.raw.toString() else s -= b.raw.toString()
        prefs(c).edit().putStringSet("folders", s).apply()
    }
    fun isFolderOpen(c: Context, b: Bookmark) = b !in lockedFolders(c) || b in unlockedFolders

    /** Locks again after a minute in the background. */
    fun onStop() { backgroundAt = System.currentTimeMillis() }
    fun onStart(c: Context) {
        if (backgroundAt > 0 && System.currentTimeMillis() - backgroundAt > 60_000) { unlocked = false; unlockedFolders.clear() }
    }

    fun authenticate(activity: Activity, reason: String, done: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < 29) { done(true); return }
        val b = BiometricPrompt.Builder(activity).setTitle("AnixQ").setSubtitle(reason)
        if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
            android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
        runCatching {
            b.build().authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = done(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = done(false)
            })
        }.onFailure { done(true) }
    }

    fun openFolder(activity: Activity, b: Bookmark, then: () -> Unit) {
        if (isFolderOpen(activity, b)) { then(); return }
        authenticate(activity, "Папка «${b.title}»") { ok -> if (ok) { unlockedFolders += b; then() } }
    }
}

/** Shown over the app while it's locked. */
@Composable
fun LockScreen() {
    val activity = LocalContext.current as Activity
    LaunchedEffect(Unit) { AppLock.authenticate(activity, "Разблокировать AnixQ") { if (it) AppLock.unlocked = true } }
    Column(Modifier.fillMaxSize().background(Pal.bg).clickable { AppLock.authenticate(activity, "Разблокировать AnixQ") { if (it) AppLock.unlocked = true } },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Lock, null, Modifier.size(54.dp), tint = Pal.pink)
        Text("AnixQ заблокирован", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        Text("Нажмите, чтобы открыть", color = Pal.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun LockSettingsScreen(nav: Nav) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(Pal.bg).statusBarsPadding()) {
        TopBar("Блокировка", { nav.pop() })
        if (!AppLock.supported) { EmptyState(Icons.Default.Lock, "Нужен Android 10 или новее"); return@Column }
        Group(null, "Отпечаток, лицо или PIN-код телефона. Блокируется после минуты в фоне.") { LockSwitch(Icons.Default.Fingerprint, "Блокировать AnixQ", AppLock.appLocked(context)) { AppLock.setAppLocked(context, it) } }
        Group("Закрытые папки закладок", "Папку можно открыть только после проверки. Количество тайтлов в ней в медиатеке не показывается.") {
            Bookmark.entries.forEachIndexed { i, b ->
                LockSwitch(b.icon, b.title, b in AppLock.lockedFolders(context), i < Bookmark.entries.lastIndex) { AppLock.setFolderLocked(context, b, it) }
            }
        }
    }
}

@Composable
private fun LockSwitch(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: Boolean, divider: Boolean = false, set: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(value) }
    GroupRow(title, icon = icon, iconColor = Pal.indigo, divider = divider, onClick = { v = !v; set(v) }, trailing = {
        Switch(v, { v = it; set(it) }, colors = SwitchDefaults.colors(checkedTrackColor = Pal.green, checkedThumbColor = Color.White,
            uncheckedTrackColor = Pal.cardHi, uncheckedBorderColor = Pal.cardHi, uncheckedThumbColor = Color.White))
    })
}
