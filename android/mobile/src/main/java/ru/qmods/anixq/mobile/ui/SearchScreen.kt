package ru.qmods.anixq.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.CatalogQuery
import ru.qmods.anixq.tv.data.CatalogSort
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Title

private object Genres { var list: List<Pair<Int, String>> = emptyList() }

@Composable
fun SearchScreen(nav: Nav) {
    var text by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Title>>(emptyList()) }
    var corrected by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var recent by remember { mutableStateOf(Store.recentSearches) }
    var genres by remember { mutableStateOf(Genres.list) }
    val focus = LocalFocusManager.current
    val q = text.trim()
    LaunchedEffect(Unit) { if (genres.isEmpty()) genres = Api.genres().also { Genres.list = it } }
    LaunchedEffect(q) {
        if (q.length < 2) { results = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        delay(350)
        // Forgiving search: layout, transliteration, a typo at the end, Shikimori.
        val (found, used) = ru.qmods.anixq.tv.data.SmartSearch.search(q)
        results = found
        corrected = used
        searching = false
    }
    fun saveRecent(s: String) { if (s.isNotBlank()) { recent = (listOf(s) + recent.filter { !it.equals(s, true) }).take(8); Store.recentSearches = recent } }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        LargeTitle("Поиск")
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(11.dp)).background(Pal.cardHi)
            .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = Pal.muted)
            Box(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                if (text.isEmpty()) Text("Название, оригинал или английское", color = Pal.muted, fontSize = 16.sp, maxLines = 1)
                BasicTextField(text, { text = it }, singleLine = true, textStyle = TextStyle(color = Pal.text, fontSize = 16.sp),
                    cursorBrush = SolidColor(Pal.pink), modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { saveRecent(q); focus.clearFocus() }))
            }
            if (text.isNotEmpty()) Icon(Icons.Default.Cancel, "Очистить", Modifier.size(18.dp).clickable { text = "" }, tint = Pal.muted)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (q.isEmpty()) {
                if (recent.isNotEmpty()) item {
                    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Недавние", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("Очистить", color = Pal.pink, fontSize = 15.sp, modifier = Modifier.clickable { recent = emptyList(); Store.recentSearches = emptyList() })
                    }
                }
                items(recent) { r ->
                    Row(Modifier.fillMaxWidth().clickable { text = r }.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, null, Modifier.size(20.dp), tint = Pal.pink)
                        Text(r, fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                if (genres.isNotEmpty()) {
                    item { Text("Жанры", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 10.dp)) }
                    items(genres.chunked(2)) { pair ->
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { (id, name) -> GenreTile(id, name, Modifier.weight(1f)) { nav.push(CatalogRoute(name, CatalogQuery(CatalogSort.POPULAR, genre = id))) } }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            } else {
                if (searching && results.isEmpty()) item { Loading() }
                else if (!searching && results.isEmpty() && q.length >= 2) item { EmptyState(Icons.Default.SearchOff, "Ничего не найдено") }
                corrected?.let { c -> item { Text("Показаны результаты по «$c»", color = Pal.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) } }
                items(results, key = { it.id }) { t ->
                    TitleRow(t) { saveRecent(q); nav.open(t) }
                }
            }
        }
    }
}

@Composable
private fun GenreTile(id: Int, name: String, modifier: Modifier, onClick: () -> Unit) {
    val hues = floatArrayOf(0.95f, 0.6f, 0.45f, 0.08f, 0.78f, 0.55f, 0.0f, 0.3f)
    val h = hues[id % hues.size] * 360f
    val c1 = Color.hsv(h, 0.65f, 0.75f); val c2 = Color.hsv((h + 18f) % 360f, 0.75f, 0.5f)
    Box(modifier.heightIn(min = 54.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(c1, c2))).pressable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
    }
}

/** List row: small poster, names, meta, score. */
@Composable
fun TitleRow(t: Title, trailing: String? = null, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PosterImage(t.thumb, Modifier.width(56.dp), 8.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(t.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                (t.engName ?: t.name)?.takeIf { it != t.title }?.let { Text(it, fontSize = 12.sp, color = Pal.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                val meta = listOfNotNull(t.typeLabel, t.year?.toString(), t.age).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, fontSize = 12.sp, color = Pal.muted, maxLines = 1)
            }
            if (trailing != null) Text(trailing, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Pal.pink)
            else t.rating?.takeIf { it > 0 }?.let { ScoreBadge(it) }
        }
        HorizontalDivider(Modifier.padding(start = 84.dp), thickness = 0.5.dp, color = Color.White.copy(alpha = 0.1f))
    }
}
