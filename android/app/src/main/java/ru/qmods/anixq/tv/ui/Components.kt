package ru.qmods.anixq.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil.compose.AsyncImage
import ru.qmods.anixq.tv.data.Title
import ru.qmods.anixq.tv.data.WatchEntry

object C {
    val bg = Color(0xFF0B0A10)
    val card = Color(0xFF1A1922)
    val cardHi = Color(0xFF26242F)
    val text = Color(0xFFF6F4FB)
    val muted = Color(0xFFA8A3B9)
    val pink = Color(0xFFFF4D7A)
    val violet = Color(0xFF8B5CFF)
    val grad = Brush.horizontalGradient(listOf(pink, violet))
}

/**
 * The layout was drawn for ~1200 dp wide; most TVs report 960 dp, so everything is scaled to 80 %
 * (7 posters per row instead of 5, like other TV apps).
 */
private const val UI_SCALE = 0.8f

@Composable
fun AnixTheme(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density * UI_SCALE, d.fontScale)) {
        MaterialTheme(colorScheme = darkColorScheme(primary = C.pink, secondary = C.violet, background = C.bg, surface = C.card,
            onSurface = C.text, onBackground = C.text)) {
            // Text outside a Surface would otherwise be black on the dark background.
            CompositionLocalProvider(LocalContentColor provides C.text, content = content)
        }
    }
}

/** Any focusable thing: grows and gets a white frame when focused. */
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    radius: Dp = 14.dp,
    scale: Float = 1.06f,
    container: Color = C.card,
    focusedContainer: Color = C.cardHi,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(radius)),
        colors = ClickableSurfaceDefaults.colors(containerColor = container, contentColor = C.text,
            focusedContainerColor = focusedContainer,
            // Dark text on light focus backgrounds (keyboard keys, round player buttons).
            focusedContentColor = if (focusedContainer.luminance() > 0.5f) Color.Black else C.text,
            pressedContainerColor = focusedContainer,
            pressedContentColor = if (focusedContainer.luminance() > 0.5f) Color.Black else C.text),
        scale = ClickableSurfaceDefaults.scale(focusedScale = scale),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(radius))),
    ) { content() }
}

@Composable
fun PosterCard(t: Title, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 150.dp, badge: String? = null, progress: Double? = null, onFocus: () -> Unit = {}) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(width)) {
        Focusable(onClick = onClick, modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(model = t.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                t.rating?.let {
                    Text("★ %.1f".format(it), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                        modifier = Modifier.padding(6.dp).background(Color(0xFFFFC94D), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                badge?.let {
                    Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(C.pink, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp))
                }
                progress?.let { ProgressLine(it, Modifier.align(Alignment.BottomCenter)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(t.title, fontSize = 15.sp, fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium, maxLines = 2,
            overflow = TextOverflow.Ellipsis, color = if (focused) Color.White else C.text)
        if (t.meta.isNotEmpty()) Text(t.meta, fontSize = 12.sp, color = C.muted, maxLines = 1)
    }
}

@Composable
fun ProgressLine(value: Double, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(5.dp).background(Color.White.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(value.toFloat().coerceIn(0f, 1f)).height(5.dp).background(C.grad))
    }
}

/** 16:9 "continue watching" card. */
@Composable
fun ContinueCard(e: WatchEntry, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier, onFocus: () -> Unit = {}) {
    Column(Modifier.width(300.dp)) {
        Focusable(onClick = onClick, onLongClick = onLongClick, modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f).onFocusChanged { if (it.isFocused) onFocus() }) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(model = e.title.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))))
                Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                    Text(if (e.progress > 0.92) "Дальше · серия ${(e.ep.toIntOrNull() ?: 0) + 1}" else "Серия ${e.ep}",
                        fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                    if (e.dur > 0 && e.progress <= 0.92) Text("осталось ${((e.dur - e.pos) / 60).toInt()} мин", fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f))
                }
                ProgressLine(e.progress, Modifier.align(Alignment.BottomCenter))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(e.title.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(e.teamName, fontSize = 12.sp, color = C.muted, maxLines = 1)
    }
}

@Composable
fun Shelf(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.padding(bottom = 26.dp)) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 48.dp, bottom = 12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), content = content)
    }
}

@Composable
fun TitleShelf(title: String, titles: List<Title>, scope: String, onFocus: (Title) -> Unit = {}, initialKey: Int? = null, onOpen: (Title) -> Unit) {
    if (titles.isEmpty()) return
    Shelf(title) {
        items(titles, key = { it.id }) { t ->
            PosterCard(t, onClick = { onOpen(t) }, modifier = Modifier.memo(scope, "$title/${t.id}", initial = t.id == initialKey), onFocus = { onFocus(t) })
        }
    }
}

/** Pill button: white when focused, gradient when primary. */
@Composable
fun PillButton(text: String, icon: ImageVector? = null, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(containerColor = if (primary) Color.Transparent else Color.White.copy(alpha = 0.12f),
            contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        Row(
            Modifier.then(if (primary && !focused) Modifier.background(C.grad) else Modifier).padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(22.dp)) }
            if (text.isNotEmpty()) Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) C.pink.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.1f), contentColor = Color.White,
            focusedContainerColor = Color.White, focusedContentColor = Color.Black),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun Loading(text: String = "Загрузка…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = C.muted, fontSize = 18.sp) }
}
