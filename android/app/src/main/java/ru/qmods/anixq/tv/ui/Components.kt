package ru.qmods.anixq.tv.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
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
import coil.request.ImageRequest
import ru.qmods.anixq.tv.data.Title
import ru.qmods.anixq.tv.data.WatchEntry

object C {
    val bg = Color(0xFF0A0A0F)
    val card = Color(0xFF17171F)
    val cardHi = Color(0xFF23232E)
    val text = Color(0xFFF5F4FA)
    val muted = Color(0xFF9C99AD)
    val faint = Color(0xFF5E5B6E)
    val pink = Color(0xFFFF4D7A)
    val violet = Color(0xFF8B5CFF)
    val gold = Color(0xFFFFC94D)
    val grad = Brush.horizontalGradient(listOf(pink, violet))
    /** Left side of every backdrop: text sits on solid background, picture fades in to the right. */
    val scrimLeft = Brush.horizontalGradient(0f to bg, 0.38f to bg.copy(alpha = 0.92f), 0.62f to bg.copy(alpha = 0.35f), 1f to bg.copy(alpha = 0.1f))
    val scrimBottom = Brush.verticalGradient(0.45f to Color.Transparent, 1f to bg)
}

/**
 * The layout is drawn for ~1200 dp wide; most TVs report 960 dp, so everything is scaled to 80 %
 * (7 posters per row, like other TV apps).
 */
private const val UI_SCALE = 0.8f

/** TV-style scrolling: the focused row/card snaps to a fixed spot near the start instead of the edge chasing the cursor. */
@OptIn(ExperimentalFoundationApi::class)
private object PivotSpec : BringIntoViewSpec {
    override val scrollAnimationSpec = tween<Float>(durationMillis = 200)
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - containerSize * 0.046f
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Pivot(content: @Composable () -> Unit) = CompositionLocalProvider(LocalBringIntoViewSpec provides PivotSpec, content = content)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnixTheme(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density * UI_SCALE, d.fontScale)) {
        MaterialTheme(colorScheme = darkColorScheme(primary = C.pink, secondary = C.violet, background = C.bg, surface = C.card,
            onSurface = C.text, onBackground = C.text)) {
            CompositionLocalProvider(LocalContentColor provides C.text, content = content)
        }
    }
}

/** The soft system "tick" when the cursor moves, like the TV's own menus. */
private var lastTick = 0L
fun Modifier.tick(): Modifier = composed {
    val view = androidx.compose.ui.platform.LocalView.current
    onFocusChanged {
        if (it.isFocused && ru.qmods.anixq.tv.data.Store.navSounds) {
            val now = System.currentTimeMillis()
            if (now - lastTick > 70) { lastTick = now; view.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_DOWN) }
        }
    }
}

/** Any focusable thing: grows and gets a white frame when focused. */
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    radius: Dp = 12.dp,
    scale: Float = 1.08f,
    container: Color = C.card,
    focusedContainer: Color = C.cardHi,
    border: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = if (focusedContainer.luminance() > 0.5f) Color.Black else C.text
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.tick(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(radius)),
        colors = ClickableSurfaceDefaults.colors(containerColor = container, contentColor = C.text,
            focusedContainerColor = focusedContainer, focusedContentColor = dark,
            pressedContainerColor = focusedContainer, pressedContentColor = dark),
        scale = ClickableSurfaceDefaults.scale(focusedScale = scale),
        border = if (border) ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.5.dp, Color.White), inset = 0.dp, shape = RoundedCornerShape(radius))) else ClickableSurfaceDefaults.border(),
        glow = ClickableSurfaceDefaults.glow(),
    ) { content() }
}

/** Local screen renders (tests) draw covers from disk; the app never sets this. */
object CoverOverride { var load: ((String) -> androidx.compose.ui.graphics.painter.Painter?)? = null }

@Composable
fun Cover(url: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val o = url?.let { CoverOverride.load?.invoke(it) }
    if (o != null) { androidx.compose.foundation.Image(o, null, modifier, contentScale = contentScale); return }
    AsyncImage(model = url, contentDescription = null, contentScale = contentScale, modifier = modifier)
}

/**
 * Soft colour wash made from a 16×24 px copy of the poster stretched over the screen:
 * looks like a blur, costs nothing, works on every Android version.
 */
@Composable
fun Ambient(url: String?, modifier: Modifier = Modifier) {
    val o = url?.let { CoverOverride.load?.invoke(it) }
    if (o != null) { androidx.compose.foundation.Image(o, null, modifier, contentScale = ContentScale.Crop, alpha = 0.6f); return }
    val context = LocalContext.current
    val req = remember(url) { url?.let { ImageRequest.Builder(context).data(it).size(16, 24).crossfade(400).build() } }
    AsyncImage(model = req, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}

@Composable
fun PosterCard(t: Title, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp? = 136.dp, badge: String? = null,
               progress: Double? = null, onFocus: () -> Unit = {}) {
    var focused by remember { mutableStateOf(false) }
    Column(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()) {
        Focusable(onClick = onClick, modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f)
            .onFocusChanged { if (it.isFocused != focused) { focused = it.isFocused; if (it.isFocused) onFocus() } }) {
            Box(Modifier.fillMaxSize()) {
                Cover(t.thumb, Modifier.fillMaxSize())
                badge?.let {
                    Text(it, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(C.pink, RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp))
                }
                progress?.let { ProgressLine(it, Modifier.align(Alignment.BottomCenter)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(t.title, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, maxLines = 2,
            overflow = TextOverflow.Ellipsis, color = if (focused) Color.White else C.muted)
    }
}

@Composable
fun ProgressLine(value: Double, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(modifier.fillMaxWidth().height(height).background(Color.White.copy(alpha = 0.22f))) {
        Box(Modifier.fillMaxWidth(value.toFloat().coerceIn(0f, 1f)).height(height).background(C.grad))
    }
}

/** "Continue watching": poster + where you stopped, one press resumes. */
@Composable
fun ContinueCard(e: WatchEntry, left: Int? = null, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier, onFocus: () -> Unit = {}) {
    val done = e.progress > 0.92
    val waiting = done && left == 0
    Focusable(onClick = onClick, onLongClick = onLongClick, scale = 1.06f,
        modifier = modifier.width(330.dp).height(124.dp).onFocusChanged { if (it.isFocused) onFocus() }) {
        Row(Modifier.fillMaxSize()) {
            Cover(e.title.thumb, Modifier.width(83.dp).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(e.title.title, fontSize = 16.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(when {
                        waiting -> "Серия ${e.ep} · ждём новую"
                        done -> "Дальше: серия ${nextNumber(e.ep)}"
                        else -> "Серия ${e.ep}" + if (e.dur > 0) " · осталось ${((e.dur - e.pos) / 60).toInt().coerceAtLeast(1)} мин" else ""
                    }, fontSize = 13.sp, color = if (done && !waiting) C.pink else C.muted, maxLines = 1, modifier = Modifier.weight(1f))
                    val more = if (done) (left ?: 0) else (left ?: 0)
                    if (more > 0) Text(if (done) "+$more" else "ещё $more", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        modifier = Modifier.background(if (done) C.pink else Color.White.copy(alpha = 0.16f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp))
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50))) { ProgressLine(if (done) 1.0 else e.progress) }
            }
        }
    }
}

private fun nextNumber(ep: String) = ep.toIntOrNull()?.plus(1)?.toString() ?: "?"

@Composable
fun Shelf(title: String, content: LazyListScope.() -> Unit) {
    Column(Modifier.padding(bottom = 22.dp)) {
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 56.dp, bottom = 10.dp))
        Pivot { LazyRow(contentPadding = PaddingValues(horizontal = 56.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), content = content) }
    }
}

@Composable
fun TitleShelf(title: String, titles: List<Title>, scope: String, onFocus: (Title) -> Unit = {}, initialKey: Int? = null, onOpen: (Title) -> Unit) {
    if (titles.isEmpty()) return
    Shelf(title) {
        items(titles, key = { it.id }) { t ->
            PosterCard(t, onClick = { onOpen(t) }, modifier = Modifier.memo(scope, "$title/${t.id}", initial = t.id == initialKey),
                onFocus = { onFocus(t) }, badge = if (t.isOngoing && title.startsWith("Новые")) "NEW" else null)
        }
    }
}

/** Pill button: white when focused, gradient when primary. */
@Composable
fun PillButton(text: String, icon: ImageVector? = null, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.tick().onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(containerColor = if (primary) Color.Transparent else Color.White.copy(alpha = 0.1f),
            contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black,
            pressedContainerColor = Color.White, pressedContentColor = Color.Black),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Row(
            Modifier.then(if (primary && !focused) Modifier.background(C.grad) else Modifier).padding(horizontal = 22.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) }
            if (text.isNotEmpty()) Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.tick(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent, contentColor = if (selected) Color.White else C.muted,
            focusedContainerColor = Color.White, focusedContentColor = Color.Black, pressedContainerColor = Color.White, pressedContentColor = Color.Black),
        border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.dp, Color.White.copy(alpha = if (selected) 0f else 0.14f)), shape = RoundedCornerShape(50))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(horizontal = 15.dp, vertical = 7.dp)) }
}

/** Small label like "ОНГОИНГ" / "R-17". */
@Composable
fun Tag(text: String, accent: Boolean = false) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (accent) Color.White else C.text,
        modifier = Modifier.background(if (accent) C.pink else Color.White.copy(alpha = 0.12f), RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp))
}

@Composable
fun MetaLine(t: Title, extra: String? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (t.isOngoing) Tag("ОНГОИНГ", accent = true)
        t.rating?.takeIf { it > 0 }?.let { Text("★ %.1f".format(it), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.gold) }
        Text(listOfNotNull(t.typeLabel, t.year?.toString(), extra).joinToString("  ·  "), fontSize = 15.sp, color = C.text.copy(alpha = 0.8f))
        t.age?.let { Tag(it) }
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun Loading(text: String = "Загрузка…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = C.muted, fontSize = 18.sp) }
}
