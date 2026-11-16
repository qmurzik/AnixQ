package ru.qmods.anixq.mobile.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.qmods.anixq.tv.data.Bookmark
import ru.qmods.anixq.tv.data.Franchise
import ru.qmods.anixq.tv.data.Store

data class FranchiseMapRoute(val shikiId: Int, val name: String) : Route

/**
 * The whole franchise as a map: the main story (TV seasons, films) on the central line, side stories
 * (OVA, specials, spin-offs) branching off it, by year — with what you've watched marked.
 */
@Composable
fun FranchiseMapScreen(nav: Nav, route: FranchiseMapRoute) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by Store.version.collectAsState()
    var nodes by remember { mutableStateOf<List<Franchise.Node>?>(null) }
    var opening by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(route.shikiId) { nodes = Franchise.nodes(route.shikiId) }
    // What you've done with each part (matched by the Shikimori id of titles in your library).
    val status = remember(version) {
        val m = HashMap<Int, String>()
        Store.history.forEach { e -> e.title.shikiId?.let { m[it] = if (e.progress > .92) "done" else "watching" } }
        Bookmark.entries.forEach { b -> Store.titles(b).forEach { t -> t.shikiId?.let { id ->
            m[id] = when (b) { Bookmark.COMPLETED, Bookmark.FAVORITE -> "done"; Bookmark.WATCHING, Bookmark.REWATCHING -> m[id] ?: "watching"; Bookmark.PLANNED -> m[id] ?: "planned"; else -> m[id] ?: "dropped" }
        } } }
        m
    }
    Column(Modifier.fillMaxSize().background(Pal.bg).statusBarsPadding()) {
        TopBar("Карта франшизы", { nav.pop() })
        Text(route.name, color = Pal.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val list = nodes
        if (list == null) { Loading(); return@Column }
        if (list.isEmpty()) { EmptyState(androidx.compose.material.icons.Icons.Default.Movie, "У этого тайтла нет других частей"); return@Column }
        val done = list.count { status[it.shikiId] == "done" }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(Pal.green, "просмотрено $done из ${list.size}"); Legend(Pal.pink, "смотрю"); Legend(Pal.blue, "в планах")
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            itemsIndexed(list, key = { _, n -> n.shikiId }) { i, n ->
                val newYear = i == 0 || list[i - 1].year != n.year
                val st = status[n.shikiId]
                val color = when (st) { "done" -> Pal.green; "watching" -> Pal.pink; "planned" -> Pal.blue; "dropped" -> Pal.muted; else -> Pal.faint }
                val here = n.shikiId == route.shikiId
                Column {
                    if (newYear) Text(n.year?.toString() ?: "—", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Pal.muted, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                        // The line: main entries sit on it, side stories hang off a dashed branch.
                        Box(Modifier.width(if (n.isMain) 56.dp else 84.dp).fillMaxHeight()) {
                            Canvas(Modifier.fillMaxSize()) {
                                val x = 28.dp.toPx()
                                drawLine(Pal.cardHi, Offset(x, if (i == 0) size.height / 2 else 0f), Offset(x, if (i == list.lastIndex) size.height / 2 else size.height), 4.dp.toPx())
                                if (!n.isMain) drawLine(color.copy(alpha = .7f), Offset(x, size.height / 2), Offset(size.width - 6.dp.toPx(), size.height / 2), 2.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                                if (n.isMain) { drawCircle(color, 9.dp.toPx(), Offset(x, size.height / 2)); drawCircle(Pal.bg, 4.dp.toPx(), Offset(x, size.height / 2)) }
                                else drawCircle(Pal.cardHi, 4.dp.toPx(), Offset(x, size.height / 2))
                            }
                        }
                        Row(Modifier.weight(1f).padding(end = 16.dp, top = 5.dp, bottom = 5.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (here) Pal.pink.copy(alpha = .14f) else Pal.card).border(if (here) 1.5.dp else 0.dp, Pal.pink, RoundedCornerShape(12.dp))
                            .pressable(enabled = opening == null) {
                                if (here) { nav.pop(); return@pressable }
                                opening = n.shikiId
                                scope.launch {
                                    val t = Franchise.title(n); opening = null
                                    if (t != null) nav.open(t) else Toast.makeText(context, "Этой части нет на AnimeLib", Toast.LENGTH_SHORT).show()
                                }
                            }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Img(n.image, Modifier.width(if (n.isMain) 46.dp else 36.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(Pal.cardHi))
                            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                Text(n.name, fontSize = if (n.isMain) 15.sp else 13.sp, fontWeight = if (n.isMain) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(n.kind, if (here) "вы здесь" else null, when (st) { "done" -> "просмотрено"; "watching" -> "смотрю"; "planned" -> "в планах"; "dropped" -> "брошено"; else -> null })
                                    .joinToString(" · "), fontSize = 12.sp, color = if (st != null) color else Pal.muted)
                            }
                            if (opening == n.shikiId) CircularProgressIndicator(Modifier.size(18.dp), color = Pal.pink, strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Legend(c: Color, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(9.dp).clip(CircleShape).background(c)); Text(" $text", fontSize = 12.sp, color = Pal.muted)
}
