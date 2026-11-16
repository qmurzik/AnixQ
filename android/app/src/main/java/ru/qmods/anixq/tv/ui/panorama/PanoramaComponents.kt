package ru.qmods.anixq.tv.ui.panorama

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.composed
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import ru.qmods.anixq.tv.R
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Title
import ru.qmods.anixq.tv.data.WatchEntry
import ru.qmods.anixq.tv.ui.*

internal object P {
    val bg = Color(0xFF090D15)
    val panel = Color(0xFF171E2B)
    val raised = Color(0xFF283245)
    val ink = Color(0xFFF2F3ED)
    val muted = Color(0xFF9DA9BC)
    val accent = Color(0xFFFFB69A)
    val line = Color(0xFF303B50)
}

// Static instances also work on API 23–25, where variation axes are unavailable.
private val CinemaFont = FontFamily(
    Font(R.font.manrope_400, FontWeight.Normal),
    Font(R.font.manrope_500, FontWeight.Medium),
    Font(R.font.manrope_600, FontWeight.SemiBold),
    Font(R.font.manrope_700, FontWeight.Bold),
)

@Composable
internal fun CinemaTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCinema provides true, LocalTextStyle provides TextStyle(fontFamily = CinemaFont), LocalContentColor provides P.ink, content = content)
}

internal fun Modifier.cinemaEntrance(key: Any) = composed {
    val duration = motionMillis(320)
    val reveal = remember(key) { Animatable(if (duration == 0) 1f else 0f) }
    LaunchedEffect(key, duration) { reveal.animateTo(1f, tween(duration)) }
    graphicsLayer { alpha = reveal.value; translationY = (1f - reveal.value) * 10.dp.toPx() }
}

@Composable
internal fun motionMillis(normal: Int): Int {
    val enabled by TvAppearance.motion.collectAsState()
    return if (enabled && !Store.weakDevice) normal else 0
}

/** Focus uses colour + a steady frame; no blur, perpetual shimmer or layout changes. */
@Composable
internal fun PSurface(onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false,
    primary: Boolean = false, onLongClick: (() -> Unit)? = null, onFocus: (Boolean) -> Unit = {},
    radius: Dp = 12.dp, content: @Composable () -> Unit) {
    Surface(onClick = onClick, onLongClick = onLongClick,
        modifier = modifier.tick().onFocusChanged { onFocus(it.isFocused) },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(radius)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) P.accent else if (selected) P.raised else P.panel,
            contentColor = if (primary) P.bg else P.ink,
            focusedContainerColor = P.accent, focusedContentColor = P.bg,
            pressedContainerColor = P.accent, pressedContentColor = P.bg),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (motionMillis(1) > 0) 1.025f else 1f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, P.ink), shape = RoundedCornerShape(radius)))) { content() }
}

@Composable
internal fun PButton(label: String, icon: ImageVector? = null, primary: Boolean = false, selected: Boolean = false,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    PSurface(onClick, modifier, selected, primary) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            icon?.let { Icon(it, null, Modifier.size(18.dp)) }
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
internal fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = P.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp, modifier = modifier)
}

@Composable
internal fun PageHeading(title: String, subtitle: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 38.dp, end = 38.dp, top = 32.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Eyebrow("ANIXQ / ${title.uppercase()}")
            Text(title, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = P.ink, modifier = Modifier.padding(top = 6.dp))
            subtitle?.let { Text(it, fontSize = 13.sp, color = P.muted, modifier = Modifier.padding(top = 6.dp)) }
        }
        trailing()
    }
}

@Composable
internal fun PMeta(title: Title) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        title.rating?.takeIf { it > 0 }?.let {
            Text("★ %.1f".format(it), color = P.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(listOfNotNull(title.year?.toString(), title.typeLabel, title.episodeInfo, title.age).joinToString("  ·  "),
            color = P.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun PPoster(title: Title, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp? = 142.dp,
    badge: String? = null, rank: Int? = null, onFocus: () -> Unit = {}) {
    var focused by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (focused) 1f else 0.55f, tween(motionMillis(180)), label = "poster-rule")
    Column(modifier.then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())) {
        PSurface(onClick, Modifier.fillMaxWidth().testTag("panorama-title-${title.id}"), onFocus = { focused = it; if (it) onFocus() }, radius = 9.dp) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.69f).background(P.panel)) {
                Cover(title.poster, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to P.bg.copy(alpha = .92f))))
                badge?.let { Text(it, fontSize = 10.sp, color = P.bg, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(8.dp).background(P.accent, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp)) }
                rank?.let { Text("%02d".format(it), fontSize = 37.sp, fontWeight = FontWeight.Light, color = P.ink,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 6.dp)) }
                title.rating?.takeIf { it > 0 }?.let { Text("%.1f".format(it), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = P.ink, modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)) }
            }
        }
        Text(title.title, color = P.ink, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp).height(35.dp))
        Text(listOfNotNull(title.year?.toString(), title.typeLabel).joinToString(" · "), fontSize = 10.sp, color = P.muted,
            maxLines = 1, modifier = Modifier.padding(top = 3.dp))
        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(1.dp).background(P.accent.copy(alpha = if (focused) alpha else 0f)))
    }
}

@Composable
internal fun PTitleRow(label: String, titles: List<Title>, scope: String, nav: Nav,
    ranked: Boolean = false, onFocus: (Title) -> Unit = {}) {
    if (titles.isEmpty()) return
    Column(Modifier.padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 38.dp, end = 38.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = P.ink, fontSize = 21.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("%02d".format(titles.size), color = P.muted, fontSize = 11.sp)
        }
        Pivot { LazyRow(contentPadding = PaddingValues(start = 38.dp, end = 42.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            itemsIndexed(titles, key = { _, t -> t.id }) { index, t ->
                PPoster(t, { nav.open(t) }, Modifier.memo(scope, "$label/${t.id}"), rank = if (ranked) index + 1 else null, onFocus = { onFocus(t) })
            }
        } }
    }
}

@Composable
internal fun PContinue(entry: WatchEntry, left: Int?, onClick: () -> Unit, onLongClick: () -> Unit,
    modifier: Modifier = Modifier) {
    PSurface(onClick, modifier.width(320.dp).height(126.dp), onLongClick = onLongClick) {
        Row(Modifier.fillMaxSize()) {
            Cover(entry.title.poster, Modifier.width(86.dp).fillMaxHeight())
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                Text(entry.title.title, maxLines = 2, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                val finished = entry.progress > .92
                Text(when {
                    finished && left == 0 -> "Ждём новую серию"
                    finished -> "Следующая серия"
                    else -> "Серия ${entry.ep} · ${fmt(entry.pos)} / ${fmt(entry.dur)}"
                }, fontSize = 11.sp, maxLines = 1)
                Box(Modifier.padding(top = 9.dp).fillMaxWidth().height(3.dp).background(P.muted.copy(alpha = .3f))) {
                    Box(Modifier.fillMaxWidth(entry.progress.toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(P.accent))
                }
            }
        }
    }
}

@Composable
internal fun PMessage(title: String, text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(38.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, color = P.ink, fontSize = 23.sp, fontWeight = FontWeight.Medium)
        Text(text, color = P.muted, fontSize = 14.sp)
        action?.let { PButton(it, onClick = onAction) }
    }
}


/** Wide home cards form a film strip. The actual poster remains intact in the foreground. */
@Composable
internal fun CinemaRow(label: String, titles: List<Title>, scope: String, nav: Nav, onFocus: (Title) -> Unit = {}) {
    if (titles.isEmpty()) return
    Column(Modifier.padding(bottom = 32.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 42.dp, end = 42.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = P.ink, fontSize = 23.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("КОЛЛЕКЦИЯ / %02d".format(titles.size), color = P.muted, letterSpacing = 1.sp, fontSize = 9.sp)
        }
        Pivot { LazyRow(contentPadding = PaddingValues(horizontal = 42.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            itemsIndexed(titles, key = { _, title -> title.id }) { index, title ->
                var focused by remember { mutableStateOf(false) }
                val reveal by animateFloatAsState(if (focused) 1f else 0f, tween(motionMillis(220)), label = "cinema-card-depth")
                PSurface({ nav.open(title) }, Modifier.width(290.dp).height(174.dp).memo(scope,"$label/${title.id}").testTag("panorama-title-${title.id}"),
                    onFocus = { focused = it; if (it) onFocus(title) }, radius = 12.dp) {
                    Box(Modifier.fillMaxSize().background(P.panel)) {
                        Cover(title.poster,Modifier.fillMaxSize().graphicsLayer { scaleX = 1.10f - .06f * reveal; scaleY = scaleX; alpha = .3f })
                        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to P.bg.copy(alpha=.85f), 1f to P.bg.copy(alpha=.1f))))
                        Cover(title.poster,Modifier.align(Alignment.CenterEnd).padding(end=16.dp).height(146.dp).aspectRatio(.69f)
                            .graphicsLayer { rotationZ = 3f - 3f * reveal; shadowElevation = 14.dp.toPx() }.clip(RoundedCornerShape(7.dp)))
                        Column(Modifier.fillMaxHeight().width(165.dp).padding(start=18.dp,top=17.dp,bottom=18.dp)) {
                            Text("%02d".format(index+1),color=P.accent,fontSize=31.sp,fontWeight=FontWeight.Light)
                            Spacer(Modifier.weight(1f))
                            Text(title.title,color=P.ink,fontSize=14.sp,lineHeight=18.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                            Text(listOfNotNull(title.year?.toString(),title.rating?.let { "★ %.1f".format(it) }).joinToString(" · "),color=P.muted,fontSize=10.sp,modifier=Modifier.padding(top=7.dp))
                        }
                    }
                }
            }
        } }
    }
}
