package ru.qmods.anixq.mobile.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.CatalogQuery
import ru.qmods.anixq.tv.data.CatalogSort
import ru.qmods.anixq.tv.data.Franchise
import ru.qmods.anixq.tv.data.ShikiExtras
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Title
import ru.qmods.anixq.tv.data.Trace

// MARK: - Search by screenshot (trace.moe)

@Composable
fun TraceScreen(nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var results by remember { mutableStateOf<List<Trace.Moment>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var opening by remember { mutableStateOf<Int?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val b = runCatching {
            if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
            else @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }.getOrNull() ?: return@rememberLauncherForActivityResult
        image = b; results = emptyList(); error = null; busy = true
        scope.launch {
            try { results = Trace.search(b); if (results.isEmpty()) error = "Ничего не нашлось" }
            catch (e: Exception) { error = e.message ?: "Ошибка поиска" }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize().background(Pal.bg).statusBarsPadding()) {
        TopBar("Поиск по кадру", { nav.pop() })
        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Скриншот из аниме — найдём тайтл, серию и момент. Лучше целый кадр без обрезки.", color = Pal.muted, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                PrimaryButton(Modifier.fillMaxWidth(), onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Default.ImageSearch, null, tint = Color.White)
                    Text("  Выбрать картинку", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
            image?.let { b -> item { Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop) } }
            if (busy) item { Loading(Modifier.fillMaxWidth().height(80.dp)) }
            error?.let { item { Text(it, color = Pal.muted) } }
            items(results, key = { it.anilistId }) { r ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.card).clickable(enabled = opening == null) {
                    opening = r.anilistId
                    scope.launch {
                        val t = Trace.resolve(r); opening = null
                        if (t != null) nav.open(t) else android.widget.Toast.makeText(context, "Этого тайтла нет на AnimeLib", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Img(r.image ?: r.cover, Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(r.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(r.episode?.let { "серия $it" }, fmt(r.from), "${(r.similarity * 100).toInt()}% совпадение").joinToString(" · "),
                            fontSize = 12.sp, color = if (r.similarity > 0.9) Pal.green else Pal.muted)
                    }
                    if (opening == r.anilistId) CircularProgressIndicator(Modifier.size(18.dp), color = Pal.pink, strokeWidth = 2.dp)
                }
            }
        }
    }
}

// MARK: - Random pick

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RandomScreen(nav: Nav) {
    val scope = rememberCoroutineScope()
    var genres by remember { mutableStateOf(Genres.list) }
    var picked by remember { mutableStateOf(setOf<Int>()) }
    var type by remember { mutableStateOf<Int?>(null) }
    var onlyFinished by remember { mutableStateOf(true) }
    var minScore by remember { mutableFloatStateOf(7f) }
    var pool by remember { mutableStateOf<List<Title>>(emptyList()) }
    var pick by remember { mutableStateOf<Title?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (genres.isEmpty()) genres = Api.genres().also { Genres.list = it } }
    fun roll() {
        busy = true
        scope.launch {
            if (pool.isEmpty()) {
                val known = (Store.history.map { it.title.id } + Store.titles(ru.qmods.anixq.tv.data.Bookmark.COMPLETED).map { it.id }).toSet()
                val q = CatalogQuery(CatalogSort.RATING, status = if (onlyFinished) 2 else null, type = type, genre = picked.firstOrNull())
                val found = ArrayList<Title>()
                for (page in listOf(1, (2..4).random())) found += runCatching { Api.catalog(q, page).first }.getOrDefault(emptyList())
                pool = found.distinctBy { it.id }.filter { (it.rating ?: 0.0) >= minScore && it.id !in known }
            }
            if (pool.isEmpty()) { pick = null; note = "Ничего не подошло — ослабьте фильтры" }
            else {
                val c = pool.random(); pool = pool - c; pick = c; note = null; summary = null
                summary = runCatching { Api.detail(c.slug).summary }.getOrNull()
            }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize().background(Pal.bg).statusBarsPadding()) {
        TopBar("Что посмотреть?", { nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            pick?.let { t ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.card).clickable { nav.open(t) }.padding(12.dp)) {
                    PosterImage(t.thumb, Modifier.width(110.dp))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(t.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(t.meta, fontSize = 13.sp, color = Pal.muted)
                        summary?.let { Text(it, fontSize = 13.sp, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            note?.let { Text(it, color = Pal.muted, modifier = Modifier.padding(bottom = 10.dp)) }
            PrimaryButton(Modifier.fillMaxWidth(), enabled = !busy, onClick = { roll() }) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else { Icon(Icons.Default.Casino, null, tint = Color.White); Text(if (pick == null) "  Выбрать случайно" else "  Ещё вариант", color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
            Text("Тип", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "Любой", 16 to "Сериал", 17 to "Фильм", 20 to "OVA", 21 to "ONA").forEach { (id, n) -> Capsule(n, selected = type == id) { type = id; pool = emptyList() } }
            }
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Только вышедшие", modifier = Modifier.weight(1f))
                Switch(onlyFinished, { onlyFinished = it; pool = emptyList() }, colors = SwitchDefaults.colors(checkedTrackColor = Pal.green))
            }
            Text("Рейтинг от ${"%.1f".format(minScore)}", modifier = Modifier.padding(top = 12.dp))
            Slider(minScore, { minScore = it; pool = emptyList() }, valueRange = 5f..9f, colors = SliderDefaults.colors(thumbColor = Pal.pink, activeTrackColor = Pal.pink))
            Text("Жанр", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                genres.forEach { (id, n) -> Capsule(n, selected = id in picked) { picked = if (id in picked) emptySet() else setOf(id); pool = emptyList() } }
            }
        }
    }
}

// MARK: - Season (Shikimori)

@Composable
fun SeasonScreen(nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val seasons = remember { ShikiExtras.seasons() }
    var season by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<ShikiExtras.SeasonAnime>?>(null) }
    var opening by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(season) { list = null; list = ShikiExtras.season(seasons[season].first) }
    Column(Modifier.fillMaxSize().background(Pal.bg).statusBarsPadding()) {
        TopBar("Сезон", { nav.pop() })
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(seasons.size) { i -> Capsule(seasons[i].second, selected = season == i) { season = i } }
        }
        val l = list
        if (l == null) { Loading(); return@Column }
        if (l.isEmpty()) { ErrorState("Shikimori не ответил") { season = season }; return@Column }
        LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(l, key = { it.id }) { a ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.card).clickable(enabled = opening == null) {
                    opening = a.id
                    scope.launch {
                        val t = Franchise.titleFor(a.id, listOfNotNull(a.russian, a.name)); opening = null
                        if (t != null) nav.open(t) else android.widget.Toast.makeText(context, "Этого тайтла пока нет на AnimeLib", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }.padding(10.dp)) {
                    Img(a.poster, Modifier.width(72.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Pal.cardHi))
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(a.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(a.kind.uppercase().takeIf { it.isNotBlank() }, a.studios.firstOrNull(),
                            if (a.episodes > 0) "${a.episodesAired.takeIf { it > 0 && it < a.episodes }?.let { "$it из " } ?: ""}${a.episodes} эп." else null).joinToString(" · "),
                            fontSize = 12.sp, color = Pal.muted)
                        Text(a.genres.take(3).joinToString(", "), fontSize = 12.sp, color = Pal.faint, maxLines = 1)
                        a.score?.let { Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, null, Modifier.size(13.dp), tint = Pal.gold); Text(" %.2f".format(it), fontSize = 12.sp)
                        } }
                    }
                    if (opening == a.id) CircularProgressIndicator(Modifier.size(18.dp), color = Pal.pink, strokeWidth = 2.dp)
                }
            }
        }
    }
}

// MARK: - Catalog filters (genre, years, length)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(query: CatalogQuery, onApply: (CatalogQuery) -> Unit, onClose: () -> Unit) {
    var q by remember { mutableStateOf(query) }
    var genres by remember { mutableStateOf(Genres.list) }
    LaunchedEffect(Unit) { if (genres.isEmpty()) genres = Api.genres().also { Genres.list = it } }
    val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    ModalBottomSheet(onClose, containerColor = Pal.card) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Фильтры", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton({ q = CatalogQuery(sort = q.sort) }) { Text("Сбросить", color = Pal.muted) }
            }
            Text("Годы выхода", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf<Triple<String, Int?, Int?>>(Triple("Любые", null, null), Triple("Этот год", year, year), Triple("${year - 1}", year - 1, year - 1),
                    Triple("${year - 5}–$year", year - 5, year), Triple("2010-е", 2010, 2019), Triple("2000-е", 2000, 2009), Triple("Классика (до 2000)", null, 1999))
                    .forEach { (n, a, b) -> Capsule(n, selected = q.yearFrom == a && q.yearTo == b) { q = q.copy(yearFrom = a, yearTo = b) } }
            }
            Text("Длина", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf<Triple<String, Int?, Int?>>(Triple("Любая", null, null), Triple("На вечер (до 13)", null, 13), Triple("Сезон (13–26)", 13, 26),
                    Triple("Длинные (26+)", 26, null), Triple("Очень длинные (100+)", 100, null))
                    .forEach { (n, a, b) -> Capsule(n, selected = q.episodesMin == a && q.episodesMax == b) { q = q.copy(episodesMin = a, episodesMax = b) } }
            }
            Text("Жанр", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                genres.forEach { (id, n) -> Capsule(n, selected = q.genre == id) { q = q.copy(genre = if (q.genre == id) null else id) } }
            }
            PrimaryButton(Modifier.fillMaxWidth().padding(top = 20.dp), onClick = { onApply(q); onClose() }) { Text("Показать", color = Color.White, fontWeight = FontWeight.SemiBold) }
        }
    }
}
