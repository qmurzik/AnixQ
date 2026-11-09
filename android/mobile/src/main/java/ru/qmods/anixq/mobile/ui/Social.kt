package ru.qmods.anixq.mobile.ui

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONObject
import ru.qmods.anixq.mobile.data.Collections
import ru.qmods.anixq.tv.data.Room
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Title

// MARK: - Watch together

private val emojis = listOf("👍", "😂", "😮", "😢", "❤️", "🔥")

/** Right-side panel in the player: create / share the room, people, chat and reactions. */
@Composable
fun RoomPanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val id by Room.roomId.collectAsState()
    val people by Room.people.collectAsState()
    val chat by Room.chat.collectAsState()
    var text by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)).clickable(onClick = onClose)) {
        Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(340.dp).background(Pal.bg.copy(alpha = 0.95f))
            .safeDrawingPadding().clickable(enabled = false) {}.padding(16.dp)) {
            Text("Смотреть вместе", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            val rid = id
            if (rid == null) {
                Text("Пауза, перемотка и смена серии — у всех сразу. Друзья заходят по ссылке: в AnixQ на iPhone, Android, ТВ или прямо в браузере.",
                    fontSize = 13.sp, color = Pal.muted, modifier = Modifier.padding(vertical = 10.dp))
                PrimaryButton(Modifier.fillMaxWidth(), onClick = { Room.create() }) { Text("Создать комнату", color = Color.White, fontWeight = FontWeight.SemiBold) }
                return@Column
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Код комнаты", fontSize = 12.sp, color = Pal.muted)
                    Text(rid, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pal.pink, letterSpacing = 2.sp)
                }
                IconButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "Смотрим вместе в AnixQ: ${Room.link(rid)}"), null))
                }) { Icon(Icons.Default.Share, "Позвать", tint = Pal.text) }
            }
            Text(if (people.isEmpty()) "Ждём друзей…" else "В комнате: " + people.values.joinToString { it.first }, fontSize = 13.sp,
                color = if (people.isEmpty()) Pal.muted else Pal.green, modifier = Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                emojis.forEach { e -> Text(e, fontSize = 22.sp, modifier = Modifier.clip(CircleShape).clickable { Room.react(e) }.padding(4.dp)) }
            }
            LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp), reverseLayout = true) {
                items(chat.reversed()) { l ->
                    Text(buildString { append(l.name); append(": "); append(l.text) }, fontSize = 14.sp, modifier = Modifier.padding(vertical = 3.dp))
                }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.cardHi).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (text.isEmpty()) Text("Сообщение", color = Pal.muted, fontSize = 15.sp)
                    BasicTextField(text, { text = it }, singleLine = true, textStyle = TextStyle(color = Pal.text, fontSize = 15.sp),
                        cursorBrush = SolidColor(Pal.pink), modifier = Modifier.fillMaxWidth())
                }
                IconButton(onClick = { Room.chat(text); text = "" }) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить", tint = Pal.pink) }
            }
            TextButton(onClick = { Room.leave(); onClose() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Выйти из комнаты", color = Pal.red)
            }
        }
    }
}

/** Over the picture: friends' latest messages and flying reactions. */
@Composable
fun RoomOverlay(modifier: Modifier = Modifier) {
    val id by Room.roomId.collectAsState()
    if (id == null) return
    val chat by Room.chat.collectAsState()
    val reactions by Room.reactions.collectAsState()
    val now = rememberTicker()
    Box(modifier) {
        Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 90.dp).width(300.dp)) {
            chat.filter { now - it.at < 10_000 && it.name != "Вы" }.takeLast(3).forEach { l ->
                Text("${l.name}: ${l.text}", fontSize = 14.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 110.dp)) {
            reactions.filter { now - it.second < 4000 }.forEach { (e, at) ->
                val rise by animateFloatAsState(((now - at) / 4000f).coerceIn(0f, 1f), label = "rise")
                Text(e, fontSize = 34.sp, modifier = Modifier.padding(bottom = (rise * 120).dp))
            }
        }
    }
}

// MARK: - Collections

@Composable
fun CollectionsScreen(nav: Nav) {
    val context = LocalContext.current
    val all by Collections.all.collectAsState()
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar("Подборки", { nav.pop() }) { IconButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "Новая", tint = Pal.pink) } }
        if (all.isEmpty()) EmptyState(Icons.Default.CollectionsBookmark, "Своих подборок пока нет", "Создайте «На выходные» или «Для друга» и добавляйте тайтлы кнопкой «+» на их странице")
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(all, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.card).pressable { nav.push(CollectionRoute(c.id)) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(c.emoji, fontSize = 28.sp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(c.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("${c.items.size} ${plural(c.items.size, "тайтл", "тайтла", "тайтлов")}", fontSize = 12.sp, color = Pal.muted)
                    }
                    IconButton(onClick = {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "${c.emoji} ${c.name} — ${Collections.shareUrl(c)}"), null))
                    }) { Icon(Icons.Default.Share, "Поделиться", tint = Pal.muted) }
                }
            }
        }
    }
    if (creating) NewCollectionDialog({ creating = false }) { creating = false }
}

@Composable
fun NewCollectionDialog(onDismiss: () -> Unit, onCreated: (Collections.Collection) -> Unit) {
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("📺") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Pal.card, title = { Text("Новая подборка") }, text = {
        Column {
            OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Например, «На выходные»") })
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("📺", "🔥", "💖", "😂", "🌙", "⚔️", "🍿").forEach { e ->
                    Text(e, fontSize = 24.sp, modifier = Modifier.clip(CircleShape).background(if (e == emoji) Pal.cardHi else Color.Transparent).clickable { emoji = e }.padding(4.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { onCreated(Collections.create(name, emoji)) }) { Text("Создать", color = Pal.pink) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
fun CollectionScreen(nav: Nav, id: String) {
    val context = LocalContext.current
    val all by Collections.all.collectAsState()
    val c = all.firstOrNull { it.id == id } ?: run { LaunchedEffect(Unit) { nav.pop() }; return }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar("${c.emoji} ${c.name}", { nav.pop() }) {
            IconButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "${c.emoji} ${c.name} — ${Collections.shareUrl(c)}"), null))
            }) { Icon(Icons.Default.Share, "Поделиться", tint = Pal.pink) }
            IconButton(onClick = { Collections.delete(c.id) }) { Icon(Icons.Default.Delete, "Удалить", tint = Pal.muted) }
        }
        LazyVerticalGrid(GridCells.Adaptive(105.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(c.items, key = { it.slug }) { i ->
                val t = Title(id = i.slug.substringBefore("--").toIntOrNull() ?: 0, slug = i.slug, rusName = i.title, coverThumb = i.cover)
                PosterCard(t, subtitle = "") { nav.open(t) }
            }
            if (c.items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(Icons.Default.CollectionsBookmark, "Пусто", "Добавляйте тайтлы кнопкой «+» на их странице") }
        }
    }
}

// MARK: - Shikimori

/** Sign in on shikimori.io itself; the app then uses that session (as on the iPhone). */
@Composable
fun ShikiLoginScreen(nav: Nav) {
    var status by remember { mutableStateOf("Войдите в аккаунт Shikimori как обычно") }
    var done by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var web by remember { mutableStateOf<android.webkit.WebView?>(null) }
    LaunchedEffect(web) {
        val w = web ?: return@LaunchedEffect
        val probe = """(function(){ if (!window.__aqBusy) { window.__aqBusy = 1; fetch('/api/users/whoami', {credentials:'include', headers:{'Accept':'application/json'}})
            .then(function(r){ return r.ok ? r.json() : null; }).then(function(j){ if (j && j.id) { var m = document.querySelector('meta[name=csrf-token]');
            window.__aqWho = JSON.stringify({id: j.id, nickname: j.nickname || '', csrf: m ? m.content : ''}); } window.__aqBusy = 0; })
            .catch(function(){ window.__aqBusy = 0; }); } return window.__aqWho || null; })()"""
        while (!done) {
            delay(1500)
            if (w.url?.contains("shikimori") != true) continue
            w.evaluateJavascript(probe) { v ->
                val raw = v?.takeIf { it != "null" }?.let { runCatching { org.json.JSONTokener(it).nextValue() as? String }.getOrNull() } ?: return@evaluateJavascript
                val j = runCatching { JSONObject(raw) }.getOrNull() ?: return@evaluateJavascript
                if (done) return@evaluateJavascript
                done = true
                val cookies = android.webkit.CookieManager.getInstance().getCookie("https://shikimori.io") ?: ""
                Store.setShiki(JSONObject().put("cookies", cookies).put("csrf", j.optString("csrf").ifEmpty { null })
                    .put("userId", j.optInt("id")).put("nickname", j.optString("nickname")))
                status = "Готово: ${j.optString("nickname")}"
                nav.pop()
            }
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar("Shikimori", { nav.pop() })
        Text(status, fontSize = 13.sp, color = Pal.muted, modifier = Modifier.fillMaxWidth().background(Pal.card).padding(10.dp))
        AndroidView(factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = settings.userAgentString.replace("; wv", "")
                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                webViewClient = android.webkit.WebViewClient()
                loadUrl("https://shikimori.io/users/sign_in")
                web = this
            }
        }, modifier = Modifier.fillMaxSize())
    }
    scope.hashCode()
}
