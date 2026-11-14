package ru.qmods.anixq.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.qmods.anixq.mobile.data.Notes
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.Franchise
import ru.qmods.anixq.tv.data.Shiki
import ru.qmods.anixq.tv.data.ShikiExtras
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Title

// MARK: - Notes & own score (iPhone: "Мои заметки")

@Composable
fun NotesCard(title: Title) {
    val all by Notes.all.collectAsState()
    val note = all[title.id] ?: Notes.Note()
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 26.dp)) {
        SectionHeader("Мои заметки")
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.card).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Моя оценка", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                for (i in 1..10) {
                    val on = (note.rating ?: 0) >= i
                    Icon(if (on) Icons.Default.Star else Icons.Default.StarBorder, "$i", Modifier.size(22.dp).clickable {
                        Notes.set(title.id, note.copy(rating = if (note.rating == i) null else i))
                    }, tint = if (on) Pal.gold else Pal.faint)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(note.text.ifBlank { "Добавить заметку — мысли, кому советовать, на чём остановились…" }, fontSize = 14.sp,
                color = if (note.text.isBlank()) Pal.muted else Pal.text, modifier = Modifier.fillMaxWidth().clickable { editing = true })
            if (note.episodes.isNotEmpty()) {
                androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Pal.stroke)
                note.episodes.entries.sortedBy { it.key.toDoubleOrNull() ?: 0.0 }.forEach { (k, v) ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text("Серия $k", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Pal.pink, modifier = Modifier.width(70.dp))
                        Text(v, fontSize = 12.sp)
                    }
                }
            }
        }
    }
    if (editing) TextDialog("Заметка", note.text, "Что запомнилось, кому советовать…", { editing = false }) {
        Notes.set(title.id, note.copy(text = it.trim())); editing = false
    }
}

@Composable
fun TextDialog(title: String, initial: String, hint: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Pal.card,
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it }, placeholder = { Text(hint) }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Pal.pink, cursorColor = Pal.pink))
        },
        confirmButton = { TextButton({ onSave(text) }) { Text("Сохранить", color = Pal.pink) } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена", color = Pal.muted) } })
}

// MARK: - Shikimori list entry (status / episodes / score)

private val statuses = listOf("watching" to "Смотрю", "planned" to "Запланировано", "completed" to "Просмотрено",
    "on_hold" to "Отложено", "dropped" to "Брошено", "rewatching" to "Пересматриваю")

@Composable
fun ShikiRateCard(shikiId: Int, totalEpisodes: Int) {
    if (Store.shikiJson == null) return
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var rate by remember { mutableStateOf<Shiki.Rate?>(null) }
    var saving by remember { mutableStateOf(false) }
    var statusMenu by remember { mutableStateOf(false) }
    var scoreMenu by remember { mutableStateOf(false) }
    LaunchedEffect(shikiId) { rate = Shiki.rate(shikiId); loaded = true }
    fun save(status: String? = null, score: Int? = null, episodes: Int? = null) {
        saving = true
        scope.launch { Shiki.saveRate(shikiId, status, score, episodes)?.let { rate = it }; saving = false }
    }
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 22.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.card).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Shikimori", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (saving) CircularProgressIndicator(Modifier.size(16.dp), color = Pal.pink, strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(10.dp))
        if (!loaded) { CircularProgressIndicator(Modifier.size(20.dp).align(Alignment.CenterHorizontally), color = Pal.pink, strokeWidth = 2.dp); return@Column }
        val r = rate
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Capsule(statuses.firstOrNull { it.first == r?.status }?.second ?: "Не в списке", selected = r != null) { statusMenu = true }
                DropdownMenu(statusMenu, { statusMenu = false }, containerColor = Pal.cardHi) {
                    statuses.forEach { (k, v) -> DropdownMenuItem({ Text(v) }, onClick = { statusMenu = false; save(status = k) }) }
                    if (r != null) DropdownMenuItem({ Text("Убрать из списка", color = Pal.red) }, onClick = {
                        statusMenu = false; saving = true
                        scope.launch { if (Shiki.removeRate(r.id)) rate = null; saving = false }
                    })
                }
            }
            val eps = r?.episodes ?: 0
            Row(Modifier.clip(CircleShape).background(Pal.cardHi), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Remove, "Меньше", Modifier.size(34.dp).clickable(enabled = eps > 0) { save(episodes = eps - 1) }.padding(8.dp), tint = Pal.text)
                Text(if (totalEpisodes > 0) "$eps / $totalEpisodes" else "$eps эп.", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Default.Add, "Больше", Modifier.size(34.dp).clickable(enabled = totalEpisodes == 0 || eps < totalEpisodes) {
                    save(episodes = eps + 1, status = if (r == null) "watching" else null)
                }.padding(8.dp), tint = Pal.text)
            }
            Box {
                Capsule(if ((r?.score ?: 0) > 0) "★ ${r?.score}" else "Оценка", selected = (r?.score ?: 0) > 0) { scoreMenu = true }
                DropdownMenu(scoreMenu, { scoreMenu = false }, containerColor = Pal.cardHi) {
                    (10 downTo 0).forEach { n -> DropdownMenuItem({ Text(if (n == 0) "Без оценки" else "$n") }, onClick = { scoreMenu = false; save(score = n) }) }
                }
            }
        }
    }
}

// MARK: - Trailers, openings, endings

@Composable
fun VideosShelf(shikiId: Int) {
    val context = LocalContext.current
    var videos by remember(shikiId) { mutableStateOf<List<ShikiExtras.Video>>(emptyList()) }
    LaunchedEffect(shikiId) { videos = ShikiExtras.videos(shikiId) }
    if (videos.isEmpty()) return
    Column(Modifier.padding(top = 26.dp)) {
        SectionHeader("Трейлеры и опенинги")
        Spacer(Modifier.height(10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(videos, key = { it.id }) { v ->
                Column(Modifier.width(220.dp).pressable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v.url))) } }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Pal.card)) {
                        Img(v.image, Modifier.fillMaxSize())
                        Box(Modifier.align(Alignment.Center).size(42.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PlayArrow, null, tint = Color.White)
                        }
                        Text(v.kindLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                            modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(CircleShape).background(Pal.pink.copy(alpha = 0.9f)).padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    Text(v.name.ifBlank { v.kindLabel }, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

// MARK: - Characters and staff

@Composable
fun PeopleShelves(shikiId: Int, nav: Nav) {
    var roles by remember(shikiId) { mutableStateOf<Pair<List<ShikiExtras.Person>, List<ShikiExtras.Person>>?>(null) }
    LaunchedEffect(shikiId) { roles = ShikiExtras.roles(shikiId) }
    val (chars, staff) = roles ?: return
    if (chars.isNotEmpty()) PeopleRow("Персонажи", chars, nav)
    if (staff.isNotEmpty()) PeopleRow("Создатели", staff, nav)
}

@Composable
fun PeopleRow(header: String, people: List<ShikiExtras.Person>, nav: Nav) {
    Column(Modifier.padding(top = 26.dp)) {
        SectionHeader(header)
        Spacer(Modifier.height(10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(people, key = { (if (it.isCharacter) "c" else "p") + it.id }) { p ->
                Column(Modifier.width(84.dp).pressable { nav.push(PersonRoute(p.id, p.isCharacter, p.name)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(76.dp).clip(CircleShape).background(Pal.card).border(if (p.isMain) 2.dp else 0.dp, Pal.pink, CircleShape)) {
                        Img(p.image, Modifier.fillMaxSize())
                    }
                    Text(p.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                    if (p.role.isNotBlank()) Text(p.role, fontSize = 11.sp, color = Pal.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A character (with their voice actors) or a person (with the characters they voiced). */
@Composable
fun PersonScreen(nav: Nav, route: PersonRoute) {
    var page by remember(route) { mutableStateOf<ShikiExtras.PersonPage?>(null) }
    var failed by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(route) { page = ShikiExtras.person(route.id, route.isCharacter); failed = page == null }
    Column(Modifier.fillMaxSize().background(Pal.bg).statusBarsPadding()) {
        TopBar(route.name, { nav.pop() })
        val p = page
        if (p == null) { if (failed) ErrorState("Не удалось загрузить страницу") { nav.pop() } else Loading(); return@Column }
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Row(Modifier.padding(16.dp)) {
                    Img(p.image, Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(Pal.card))
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(p.name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        p.altName?.takeIf { it != p.name }?.let { Text(it, fontSize = 14.sp, color = Pal.muted) }
                        Text(if (route.isCharacter) "Персонаж" else "Человек", fontSize = 13.sp, color = Pal.pink, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            p.description?.let { d -> item {
                var more by remember { mutableStateOf(false) }
                Text(d, fontSize = 14.sp, lineHeight = 20.sp, color = Pal.muted, maxLines = if (more) Int.MAX_VALUE else 8, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp).clickable { more = !more })
            } }
            if (p.related.isNotEmpty()) item { PeopleRow(if (route.isCharacter) "Сейю" else "Персонажи", p.related, nav) }
            if (p.works.isNotEmpty()) {
                item { Column(Modifier.padding(top = 26.dp)) { SectionHeader("Аниме") } }
                items(p.works, key = { "w" + it.shikiId + it.role }) { w ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = opening == null) {
                        opening = w.shikiId
                        scope.launch {
                            val t = Franchise.titleFor(w.shikiId, listOf(w.russian, w.name).filter { it.isNotBlank() })
                            opening = null
                            if (t != null) nav.open(t) else android.widget.Toast.makeText(context, "Этого тайтла нет на AnimeLib", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(w.year?.toString(), w.role.takeIf { it.isNotBlank() }).joinToString(" · "), fontSize = 12.sp, color = Pal.muted)
                        }
                        if (opening == w.shikiId) CircularProgressIndicator(Modifier.size(18.dp), color = Pal.pink, strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

// MARK: - Comments (AnimeLib)

@Composable
fun CommentsPreview(title: Title, nav: Nav) {
    var list by remember(title.id) { mutableStateOf<List<Api.Comment>?>(null) }
    LaunchedEffect(title.id) { list = runCatching { Api.comments(title.id, "anime", 1).root }.getOrDefault(emptyList()) }
    val l = list ?: return
    Column(Modifier.padding(top = 26.dp)) {
        SectionHeader("Комментарии", if (l.isNotEmpty()) "Все" else null) { nav.push(CommentsRoute(title.id, "anime", title.title)) }
        if (l.isEmpty()) Text("Пока никто не написал", fontSize = 14.sp, color = Pal.muted, modifier = Modifier.padding(16.dp))
        l.take(3).forEach { CommentRow(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
    }
}

@Composable
fun CommentRow(c: Api.Comment, modifier: Modifier = Modifier, reply: Boolean = false) {
    var reveal by remember { mutableStateOf(!c.spoiler) }
    Row(modifier.fillMaxWidth().padding(start = if (reply) 34.dp else 0.dp)) {
        Box(Modifier.size(if (reply) 28.dp else 36.dp).clip(CircleShape).background(Pal.cardHi)) { Img(c.avatar, Modifier.fillMaxSize()) }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.user, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (c.date > 0) Text("  " + ago(c.date), fontSize = 12.sp, color = Pal.muted)
            }
            Box(Modifier.clickable(enabled = !reveal) { reveal = true }) {
                Text(c.text, fontSize = 14.sp, lineHeight = 19.sp, modifier = if (reveal) Modifier else Modifier.blur(8.dp))
                if (!reveal) Text("Спойлер — нажмите, чтобы показать", fontSize = 12.sp, color = Pal.pink, modifier = Modifier.align(Alignment.Center))
            }
            if (c.up > 0) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ThumbUp, null, Modifier.size(12.dp), tint = Pal.muted)
                Text(" ${c.up}", fontSize = 12.sp, color = Pal.muted)
            }
        }
    }
}

fun ago(ms: Long): String {
    val d = (System.currentTimeMillis() - ms) / 1000
    return when {
        d < 3600 -> "${(d / 60).coerceAtLeast(1)} мин назад"
        d < 86400 -> "${d / 3600} ч назад"
        d < 86400 * 30 -> "${d / 86400} дн. назад"
        else -> java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale("ru")).format(java.util.Date(ms))
    }
}

@Composable
fun CommentsScreen(nav: Nav, route: CommentsRoute) {
    val root = remember { mutableStateListOf<Api.Comment>() }
    val replies = remember { mutableStateListOf<Api.Comment>() }
    var page by remember { mutableIntStateOf(1) }
    var more by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun load() {
        if (loading || !more) return
        loading = true
        scope.launch {
            try {
                val r = Api.comments(route.postId, route.type, page)
                root += r.root.filter { n -> root.none { it.id == n.id } }; replies += r.replies
                more = r.hasMore && r.root.isNotEmpty(); page += 1
            } catch (e: Exception) { error = e.message ?: "Ошибка" }
            loading = false
        }
    }
    LaunchedEffect(route) { load() }
    Column(Modifier.fillMaxSize().background(Pal.bg).statusBarsPadding()) {
        TopBar("Комментарии", { nav.pop() })
        Text(route.title, fontSize = 13.sp, color = Pal.muted, modifier = Modifier.padding(horizontal = 16.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp, top = 8.dp)) {
            items(root, key = { it.id }) { c ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    CommentRow(c)
                    replies.filter { it.rootId == c.id }.forEach { CommentRow(it, Modifier.padding(top = 8.dp), reply = true) }
                }
                if (c.id == root.lastOrNull()?.id) LaunchedEffect(root.size) { load() }
            }
            item {
                when {
                    loading -> Loading(Modifier.fillMaxWidth().height(80.dp))
                    error != null && root.isEmpty() -> ErrorState(error!!) { error = null; load() }
                    root.isEmpty() -> EmptyState(Icons.Default.ChatBubbleOutline, "Пока никто не написал")
                }
            }
        }
    }
}
