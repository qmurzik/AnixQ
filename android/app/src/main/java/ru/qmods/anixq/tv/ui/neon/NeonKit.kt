package ru.qmods.anixq.tv.ui.neon

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Title
import ru.qmods.anixq.tv.data.WatchEntry
import ru.qmods.anixq.tv.ui.*
import kotlin.random.Random

/** Night-city palette: deep indigo night, hot pink and cyan neon, a little amber like station boards. */
internal object N {
    val night = Color(0xFF07060F)
    val street = Color(0xFF0E0B1E)
    val panel = Color(0xFF151129)
    val raised = Color(0xFF221B3D)
    val ink = Color(0xFFF5F1FF)
    val muted = Color(0xFF8D86B5)
    val pink = Color(0xFFFF2E88)
    val cyan = Color(0xFF00E5FF)
    val violet = Color(0xFFA259FF)
    val amber = Color(0xFFFFB000)
    val line = Color(0xFF2A2350)
}

/** Each tab has its own sign colour (like different shops along the street). */
internal fun Tab.neon(): Color = when (this) {
    Tab.HOME -> N.pink; Tab.CATALOG -> N.cyan; Tab.SEARCH -> N.amber
    Tab.LIBRARY -> N.violet; Tab.SCHEDULE -> Color(0xFF39FF88); Tab.SETTINGS -> N.muted
}
internal val Tab.kanji: String get() = when (this) {
    Tab.HOME -> "夜"; Tab.CATALOG -> "店"; Tab.SEARCH -> "探"; Tab.LIBRARY -> "蔵"; Tab.SCHEDULE -> "駅"; Tab.SETTINGS -> "設"
}

@Composable
internal fun NeonTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCinema provides true, LocalContentColor provides N.ink,
        LocalTextStyle provides TextStyle(fontFamily = FontFamily.SansSerif), content = content)
}

/** Animations follow the same policy as Panorama: Auto (off on weak boxes) / Full / Off. */
@Composable
internal fun neonMillis(normal: Int): Int {
    val policy by TvAppearance.motion.collectAsState()
    return when (policy) {
        TvAppearance.Motion.OFF -> 0
        TvAppearance.Motion.FULL -> normal
        TvAppearance.Motion.AUTO -> if (Store.weakDevice) 0 else normal
    }
}

/** Text that glows like a neon tube. */
@Composable
internal fun Glow(text: String, color: Color, size: TextUnit, modifier: Modifier = Modifier, weight: FontWeight = FontWeight.Bold,
                  glow: Float = 1f, maxLines: Int = 1, letterSpacing: TextUnit = TextUnit.Unspecified, lineHeight: TextUnit = TextUnit.Unspecified) {
    Text(text, modifier, color = androidx.compose.ui.graphics.lerp(color, Color.White, .62f), fontSize = size, fontWeight = weight, maxLines = maxLines,
        overflow = TextOverflow.Ellipsis, letterSpacing = letterSpacing, lineHeight = lineHeight,
        style = TextStyle(shadow = Shadow(color.copy(alpha = .95f * glow), Offset.Zero, 22f * glow)))
}

/** Several soft strokes around a shape: a neon outline that works on every Android version (no blur APIs). */
internal fun Modifier.neonOutline(color: Color, strength: Float, radius: Dp = 12.dp) = drawBehind {
    if (strength <= 0.01f) return@drawBehind
    val r = radius.toPx()
    for (i in 4 downTo 1) {
        val w = (2 + i * 4).dp.toPx()
        drawRoundRect(color.copy(alpha = strength * 0.11f * (5 - i)), Offset(-w / 2, -w / 2), Size(size.width + w, size.height + w),
            CornerRadius(r + w / 2), style = Stroke(w))
    }
    drawRoundRect(color.copy(alpha = strength), Offset.Zero, size, CornerRadius(r), style = Stroke(2.dp.toPx()))
}

/** Neon tubes don't just turn on — they flicker first. Returns 0…1 brightness for [on]. */
@Composable
internal fun flicker(on: Boolean): Float {
    val ms = neonMillis(260)
    val a = remember { Animatable(if (on) 1f else 0f) }
    LaunchedEffect(on, ms) {
        if (!on) { a.animateTo(0f, tween(ms / 2)); return@LaunchedEffect }
        if (ms == 0) { a.snapTo(1f); return@LaunchedEffect }
        a.animateTo(1f, keyframes { durationMillis = ms; 0.15f at 0; 0.9f at (ms * 0.18f).toInt(); 0.25f at (ms * 0.32f).toInt(); 1f at (ms * 0.5f).toInt(); 0.7f at (ms * 0.7f).toInt(); 1f at ms })
    }
    return a.value
}

/** Every focusable thing in the theme: dark glass that lights up with a flickering neon edge. */
@Composable
internal fun NSurface(onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = N.pink, selected: Boolean = false,
                      onLongClick: (() -> Unit)? = null, onFocus: (Boolean) -> Unit = {}, radius: Dp = 12.dp, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val light = flicker(focused)
    Surface(onClick = onClick, onLongClick = onLongClick,
        modifier = modifier.tick().neonOutline(color, maxOf(light, if (selected) .35f else 0f), radius)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(radius)),
        colors = ClickableSurfaceDefaults.colors(containerColor = if (selected) N.raised else N.panel.copy(alpha = .86f), contentColor = N.ink,
            focusedContainerColor = N.raised, focusedContentColor = N.ink, pressedContainerColor = color.copy(alpha = .35f), pressedContentColor = N.ink),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (neonMillis(1) > 0) 1.04f else 1f),
        glow = ClickableSurfaceDefaults.glow()) { content() }
}

@Composable
internal fun NButton(label: String, icon: ImageVector? = null, color: Color = N.pink, primary: Boolean = false, selected: Boolean = false,
                     modifier: Modifier = Modifier, onClick: () -> Unit) {
    NSurface(onClick, modifier, color, selected || primary, radius = 24.dp) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            icon?.let { Icon(it, null, Modifier.size(18.dp), tint = if (primary) color else N.ink) }
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, color = if (primary) color else N.ink)
        }
    }
}

/** Section heading as a small shop sign: a kanji in a neon box and the Russian name. */
@Composable
internal fun NSign(kanji: String, label: String, color: Color, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).neonOutline(color, .8f, 6.dp).background(N.night, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            Glow(kanji, color, 18.sp)
        }
        Glow(label, N.ink, 21.sp, Modifier.padding(start = 14.dp), weight = FontWeight.SemiBold, glow = .25f)
        Box(Modifier.padding(start = 16.dp).weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(color.copy(alpha = .55f), Color.Transparent))))
        trailing()
    }
}

/** Digital clock in the corner, like a sign over a shop. */
@Composable
internal fun NClock(color: Color = N.cyan) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    Glow(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now)), color, 18.sp, letterSpacing = 3.sp, weight = FontWeight.Medium, glow = .7f)
}

// MARK: - The city

/**
 * Rain falling in two layers (near drops are longer and faster), a skyline with lit windows at the bottom
 * and the colour of what's in focus washing the sky. Still when animations are off.
 */
@Composable
internal fun NightCity(tint: Color, modifier: Modifier = Modifier) {
    val ms = neonMillis(1400)
    val sky by animateFloatAsState(1f, tween(600), label = "sky")
    val drops = remember { List(70) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }
    val windows = remember { List(26) { i -> Triple(i, Random.nextInt(3, 9), Random.nextLong()) } }
    val t = if (ms > 0) {
        val inf = rememberInfiniteTransition(label = "rain")
        inf.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Restart), label = "drop").value
    } else 0.37f
    val tintNow by androidx.compose.animation.animateColorAsState(tint, tween(900), label = "tint")
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(0f to N.night, .55f to N.street, 1f to Color(0xFF120A24)))
        drawRect(Brush.radialGradient(listOf(tintNow.copy(alpha = .22f * sky), Color.Transparent), Offset(size.width * .72f, size.height * .18f), size.width * .6f))
        // Skyline
        val base = size.height
        windows.forEach { (i, floors, seed) ->
            val r = Random(seed)
            val w = size.width / windows.size
            val h = base * (0.10f + floors * 0.025f)
            val x = i * w
            drawRect(Color(0xFF0B0919), Offset(x, base - h), Size(w * 0.92f, h))
            repeat(floors * 2) { k ->
                if (r.nextFloat() < 0.28f) drawRect((if (r.nextBoolean()) N.amber else N.cyan).copy(alpha = 0.25f + r.nextFloat() * 0.25f),
                    Offset(x + w * (0.15f + 0.4f * (k % 2)), base - h + 8f + (k / 2) * (h / (floors + 1))), Size(w * 0.18f, 4.5f))
            }
        }
        // Rain
        drops.forEachIndexed { i, (x0, y0, depth) ->
            val near = depth > 0.6f
            val len = if (near) 26f else 12f
            val speed = if (near) 1.6f else 0.9f
            val y = ((y0 + t * speed) % 1f) * (size.height + len) - len
            val x = x0 * size.width - y * 0.06f
            drawLine((if (i % 7 == 0) tintNow else Color.White).copy(alpha = if (near) .16f else .08f), Offset(x, y), Offset(x - len * .12f, y + len),
                strokeWidth = if (near) 1.6f else 1f, cap = StrokeCap.Round)
        }
        // Wet-street haze at the bottom.
        drawRect(Brush.verticalGradient(0.78f to Color.Transparent, 1f to tintNow.copy(alpha = .10f)))
    }
}

// MARK: - Cards

/** A poster as a lit shop window, with its reflection on the wet street below. */
@Composable
internal fun NPoster(title: Title, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp? = 150.dp, color: Color = N.pink,
                     badge: String? = null, onFocus: () -> Unit = {}) {
    var focused by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(if (focused) 1f else 0f, tween(neonMillis(220)), label = "lift")
    Column(modifier.then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())) {
        NSurface(onClick, Modifier.fillMaxWidth().testTag("neon-title-${title.id}").semantics { contentDescription = title.title }, color,
            onFocus = { focused = it; if (it) onFocus() }, radius = 10.dp) {
            Box(Modifier.fillMaxWidth().aspectRatio(.69f).background(N.panel)) {
                Cover(title.poster, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.55f to Color.Transparent, 1f to N.night.copy(alpha = .9f))))
                badge?.let { Text(it, fontSize = 10.sp, color = N.night, fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(8.dp).background(color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) }
                title.rating?.takeIf { it > 0 }?.let { Glow("%.1f".format(it), N.amber, 12.sp, Modifier.align(Alignment.BottomEnd).padding(9.dp)) }
            }
        }
        // Reflection: the same art upside-down, fading into the street.
        Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp))
            .graphicsLayer { alpha = .18f + .22f * lift; scaleY = -1f }) {
            Cover(title.poster, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to N.night, 1f to Color.Transparent)))
        }
        Text(title.title, color = if (focused) N.ink else N.ink.copy(alpha = .78f), fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp).height(35.dp))
    }
}

@Composable
internal fun NRow(kanji: String, label: String, titles: List<Title>, scope: String, nav: Nav, color: Color = N.pink, onFocus: (Title) -> Unit = {}) {
    if (titles.isEmpty()) return
    Column(Modifier.padding(bottom = 22.dp)) {
        NSign(kanji, label, color, Modifier.padding(start = 44.dp, end = 44.dp, bottom = 12.dp)) {
            Text("%02d".format(titles.size), color = N.muted, fontSize = 11.sp, modifier = Modifier.padding(start = 12.dp))
        }
        Pivot { LazyRow(contentPadding = PaddingValues(start = 44.dp, end = 44.dp, top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            itemsIndexed(titles, key = { _, t -> t.id }) { _, t -> NPoster(t, { nav.open(t) }, Modifier.memo(scope, "$label/${t.id}"), color = color, onFocus = { onFocus(t) }) }
        } }
    }
}

/** "Continue": a train ticket — title, episode, a perforated edge and the progress as the route. */
@Composable
internal fun NTicket(entry: WatchEntry, left: Int?, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    NSurface(onClick, modifier.width(340.dp).height(118.dp), N.cyan, onLongClick = onLongClick, radius = 8.dp) {
        Row(Modifier.fillMaxSize()) {
            Cover(entry.title.poster, Modifier.width(82.dp).fillMaxHeight())
            Canvas(Modifier.width(10.dp).fillMaxHeight()) {
                var y = 6f
                while (y < size.height) { drawCircle(N.night, 3.2f, Offset(size.width / 2, y)); y += 12f }
            }
            Column(Modifier.fillMaxSize().padding(start = 4.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
                Text("ПОЕЗД № ${entry.ep}".uppercase(), color = N.cyan, fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Text(entry.title.title, maxLines = 2, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.weight(1f))
                val finished = entry.progress > .92
                Text(when {
                    finished && left == 0 -> "Ждём следующий поезд"
                    finished -> "Следующая серия"
                    else -> "${fmt(entry.pos)} → ${fmt(entry.dur)}"
                }, fontSize = 11.sp, color = N.muted, maxLines = 1)
                Canvas(Modifier.padding(top = 7.dp).fillMaxWidth().height(6.dp)) {
                    val y = size.height / 2
                    drawLine(N.line, Offset(0f, y), Offset(size.width, y), 2f)
                    val x = size.width * entry.progress.toFloat().coerceIn(0f, 1f)
                    drawLine(N.cyan, Offset(0f, y), Offset(x, y), 2.5f)
                    drawCircle(N.cyan, 4f, Offset(x, y))
                }
            }
        }
    }
}

@Composable
internal fun NMessage(title: String, text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(44.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Glow(title, N.pink, 23.sp, glow = .6f)
        Text(text, color = N.muted, fontSize = 14.sp)
        action?.let { NButton(it, onClick = onAction) }
    }
}

/** Entering a section: the street slides past (direction follows where the sign was). */
internal fun Modifier.streetPan(key: Any, fromRight: Boolean) = composed {
    val ms = neonMillis(380)
    val p = remember(key) { Animatable(if (ms == 0) 1f else 0f) }
    LaunchedEffect(key, ms) { p.animateTo(1f, tween(ms)) }
    graphicsLayer { alpha = .2f + .8f * p.value; translationX = (1f - p.value) * (if (fromRight) 90.dp.toPx() else -90.dp.toPx()) }
}
