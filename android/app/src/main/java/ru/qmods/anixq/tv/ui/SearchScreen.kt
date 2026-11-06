package ru.qmods.anixq.tv.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.CatalogQuery
import ru.qmods.anixq.tv.data.CatalogSort
import ru.qmods.anixq.tv.data.Title

private val ru = listOf("абвгде", "ёжзийк", "лмнопр", "стуфхц", "чшщъыь", "эюя123", "456789")
private val en = listOf("abcdef", "ghijkl", "mnopqr", "stuvwx", "yz1234", "567890")

private object SearchState {
    var popular: List<Title> = emptyList()
    val recent = ArrayList<String>()
}

@Composable
fun SearchScreen(nav: Nav) {
    var text by rememberSaveable { mutableStateOf("") }
    var latin by rememberSaveable { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Title>>(emptyList()) }
    var popular by remember { mutableStateOf(SearchState.popular) }
    var busy by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { text = it }
        }
    }
    val voiceIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Название аниме")
    }
    val hasVoice = remember { voiceIntent.resolveActivity(context.packageManager) != null }

    LaunchedEffect(Unit) {
        if (popular.isEmpty()) runCatching { Api.catalog(CatalogQuery(sort = CatalogSort.POPULAR, status = 1), 1).first }
            .getOrNull()?.let { popular = it; SearchState.popular = it }
    }
    LaunchedEffect(text) {
        val q = text.trim()
        if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        busy = true
        results = runCatching { Api.search(q) }.getOrDefault(emptyList())
        busy = false
        if (results.isNotEmpty()) { SearchState.recent.remove(q); SearchState.recent.add(0, q); while (SearchState.recent.size > 6) SearchState.recent.removeAt(6) }
    }

    Row(Modifier.fillMaxSize()) {
        // Keyboard
        Column(Modifier.width(380.dp).fillMaxHeight().padding(start = 56.dp, top = TopBarHeight + 10.dp, end = 12.dp)) {
            Box(Modifier.fillMaxWidth().height(54.dp).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart) {
                Text(if (text.isEmpty()) "Название…" else text + "▏", fontSize = 20.sp, maxLines = 1,
                    color = if (text.isEmpty()) C.muted else Color.White)
            }
            Spacer(Modifier.height(14.dp))
            (if (latin) en else ru).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    row.forEachIndexed { c, ch ->
                        Key(ch.toString(), modifier = Modifier.memo("search", "k$r$c", initial = r == 0 && c == 0)) { text += ch }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Key(icon = Icons.Rounded.SpaceBar, wide = 2) { text += " " }
                Key(icon = Icons.AutoMirrored.Rounded.Backspace, wide = 2) { text = text.dropLast(1) }
                Key(if (latin) "РУС" else "ENG", wide = 2) { latin = !latin }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hasVoice) Key("Голос", icon = Icons.Rounded.Mic, wide = 3) { runCatching { voice.launch(voiceIntent) } }
                Key("Очистить", wide = if (hasVoice) 3 else 6) { text = "" }
            }
            if (SearchState.recent.isNotEmpty() && text.isEmpty()) {
                Spacer(Modifier.height(18.dp))
                Hint("Недавние")
                Spacer(Modifier.height(6.dp))
                SearchState.recent.take(4).forEach { q ->
                    Chip(q, false) { text = q }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        // Results
        val shown = if (text.trim().length >= 2) results else popular
        LazyVerticalGrid(
            columns = GridCells.Adaptive(122.dp), modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(start = 16.dp, end = 56.dp, top = TopBarHeight + 10.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    when {
                        text.trim().length < 2 -> "Сейчас смотрят"
                        busy -> "Ищем «${text.trim()}»…"
                        results.isEmpty() -> "Ничего не нашлось по «${text.trim()}»"
                        else -> "Найдено: ${results.size}"
                    }, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                )
            }
            items(shown, key = { it.id }) { t -> PosterCard(t, onClick = { nav.open(t) }, width = null, modifier = Modifier.memo("search", "t${t.id}")) }
        }
    }
}

@Composable
private fun Key(label: String = "", icon: ImageVector? = null, wide: Int = 1, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val w = 46 * wide + 6 * (wide - 1)
    Focusable(onClick = onClick, modifier = modifier.width(w.dp).height(46.dp), radius = 10.dp, scale = 1.1f,
        container = Color.White.copy(alpha = 0.07f), focusedContainer = Color.White) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) }
                if (label.isNotEmpty()) Text(label, fontSize = if (label.length == 1) 20.sp else 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
