package ru.qmods.anixq.mobile.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import org.json.JSONObject
import ru.qmods.anixq.tv.data.Bookmark
import ru.qmods.anixq.tv.data.Remote
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Sync
import ru.qmods.anixq.tv.data.Title

// MARK: - Images

/** Screenshot tests swap network images for local ones. */
val LocalImageOverride = compositionLocalOf<((String) -> Painter?)?> { null }

@Composable
fun Img(url: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, alpha: Float = 1f) {
    if (url == null) { Box(modifier); return }
    val fake = LocalImageOverride.current
    if (fake != null) { fake(url)?.let { Image(it, null, modifier, contentScale = contentScale, alpha = alpha) } ?: Box(modifier); return }
    AsyncImage(url, null, modifier, contentScale = contentScale, alpha = alpha)
}

// MARK: - Press feedback (the iPhone's PressableStyle)

@Composable
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, label = "press")
    return this.scale(s).clickable(src, null, enabled = enabled, onClick = onClick)
}

// MARK: - Posters

@Composable
fun PosterImage(url: String?, modifier: Modifier = Modifier, radius: Dp = 12.dp) {
    val shape = RoundedCornerShape(radius)
    Box(modifier.aspectRatio(2f / 3f).clip(shape).background(Pal.card).border(0.5.dp, Pal.stroke, shape)) {
        Img(url, Modifier.fillMaxSize())
    }
}

@Composable
fun ScoreBadge(score: Double, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(6.dp)).background(Pal.score(score).copy(alpha = 0.92f)).padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Star, null, Modifier.size(9.dp), tint = Color.Black.copy(alpha = 0.85f))
        Text("%.1f".format(score), fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color.Black.copy(alpha = 0.85f), modifier = Modifier.padding(start = 2.dp))
    }
}

@Composable
fun PosterCard(t: Title, modifier: Modifier = Modifier, subtitle: String? = null, progress: Float? = null, onClick: () -> Unit) {
    Column(modifier.pressable(onClick = onClick)) {
        Box(Modifier.shadow(6.dp, RoundedCornerShape(12.dp), ambientColor = Color.Black, spotColor = Color.Black)) {
            PosterImage(t.thumb, Modifier.fillMaxWidth())
            t.rating?.takeIf { it > 0 }?.let { ScoreBadge(it, Modifier.padding(6.dp)) }
            if (progress != null && progress > 0.01f)
                ProgressLine(progress, Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 8.dp))
        }
        Spacer(Modifier.height(6.dp))
        // Two lines reserved, so the meta lines of a shelf stay aligned.
        Text(t.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
        val sub = subtitle ?: listOfNotNull(t.typeLabel, t.year?.toString()).joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, fontSize = 11.sp, color = Pal.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ProgressLine(value: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(height).clip(CircleShape).background(Pal.progress))
    }
}

// MARK: - Sections and shelves

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action != null) Row(Modifier.clip(CircleShape).clickable(onClick = onAction).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(action, color = Pal.pink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = Pal.pink)
        }
    }
}

@Composable
fun Shelf(title: String, titles: List<Title>, seeAll: (() -> Unit)? = null, onOpen: (Title) -> Unit) {
    if (titles.isEmpty()) return
    Column(Modifier.padding(top = 26.dp)) {
        SectionHeader(title, if (seeAll != null) "Все" else null) { seeAll?.invoke() }
        Spacer(Modifier.height(10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(titles, key = { it.id }) { t -> PosterCard(t, Modifier.width(118.dp)) { onOpen(t) } }
        }
    }
}

/** "Топ-10 сейчас": posters with big gradient numbers behind them. */
@Composable
fun TopTenShelf(title: String, titles: List<Title>, seeAll: (() -> Unit)? = null, onOpen: (Title) -> Unit) {
    if (titles.isEmpty()) return
    Column(Modifier.padding(top = 26.dp)) {
        SectionHeader(title, if (seeAll != null) "Все" else null) { seeAll?.invoke() }
        Spacer(Modifier.height(4.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(titles.take(10), key = { _, t -> t.id }) { i, t ->
                Row(Modifier.pressable { onOpen(t) }, verticalAlignment = Alignment.Bottom) {
                    Text("${i + 1}", fontSize = 92.sp, fontWeight = FontWeight.Black, lineHeight = 92.sp,
                        style = TextStyle(brush = Brush.verticalGradient(listOf(Pal.pink.copy(alpha = 0.95f), Pal.pink.copy(alpha = 0.2f)))),
                        textAlign = TextAlign.End, modifier = Modifier.width(if (i == 9) 104.dp else 58.dp).offset(x = 18.dp, y = 14.dp))
                    PosterImage(t.thumb, Modifier.width(112.dp).shadow(8.dp, RoundedCornerShape(12.dp)))
                }
            }
        }
    }
}

@Composable
fun MetaChip(text: String, icon: ImageVector? = null, tint: Color = Color.White) {
    Row(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.42f)).border(0.5.dp, Color.White.copy(alpha = 0.12f), CircleShape)
        .padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, Modifier.size(11.dp).padding(end = 1.dp), tint = tint)
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1, modifier = Modifier.padding(start = if (icon != null) 3.dp else 0.dp))
    }
}

// MARK: - Buttons

/** Main "Смотреть" button: accent → violet gradient slab with a soft glow. */
@Composable
fun PrimaryButton(modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.heightIn(min = 50.dp).shadow(if (enabled) 14.dp else 0.dp, shape, ambientColor = Pal.pink, spotColor = Pal.pink)
            .clip(shape).background(if (enabled) Pal.accent else Brush.horizontalGradient(listOf(Pal.cardHi, Pal.cardHi)))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent)), shape)
            .pressable(enabled, onClick).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, content = content,
    )
}

/** Square secondary button next to "Смотреть" (glassy card). */
@Composable
fun GlassButton(icon: ImageVector, description: String?, modifier: Modifier = Modifier, tint: Color = Color.White, onClick: () -> Unit) {
    Box(modifier.size(50.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.12f))
        .border(0.5.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp)).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = tint)
    }
}

/** Round translucent button over artwork (back, share…). */
@Composable
fun CircleButton(icon: ImageVector, description: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(38.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(20.dp), tint = Color.White)
    }
}

@Composable
fun Capsule(text: String, icon: ImageVector? = null, selected: Boolean = false, trailing: ImageVector? = null, onClick: () -> Unit) {
    Row(Modifier.clip(CircleShape).background(if (selected) Color.White else Pal.cardHi).clickable(onClick = onClick)
        .padding(horizontal = 13.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val c = if (selected) Color.Black else Pal.text
        if (icon != null) Icon(icon, null, Modifier.size(15.dp), tint = c)
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c, maxLines = 1, modifier = Modifier.padding(start = if (icon != null) 5.dp else 0.dp))
        if (trailing != null) Icon(trailing, null, Modifier.size(15.dp).padding(start = 2.dp), tint = c)
    }
}

/** White symbol on a coloured rounded square (Settings-style). */
@Composable
fun ColoredIcon(icon: ImageVector, color: Color, size: Dp = 28.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.8f)))),
        contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(size * 0.58f), tint = Color.White) }
}

// MARK: - Grouped lists (iOS inset grouped)

@Composable
fun Group(header: String? = null, footer: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 22.dp)) {
        if (header != null) Text(header.uppercase(), fontSize = 13.sp, color = Pal.muted, modifier = Modifier.padding(start = 16.dp, bottom = 7.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.card)) { content() }
        if (footer != null) Text(footer, fontSize = 13.sp, color = Pal.muted, lineHeight = 17.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp))
    }
}

@Composable
fun GroupRow(
    title: String, subtitle: String? = null, icon: ImageVector? = null, iconColor: Color = Pal.pink, value: String? = null,
    chevron: Boolean = false, divider: Boolean = true, titleColor: Color = Pal.text,
    trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null,
) {
    Column {
        Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { ColoredIcon(icon, iconColor); Spacer(Modifier.width(14.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, color = titleColor)
                if (subtitle != null) Text(subtitle, fontSize = 12.5.sp, color = Pal.muted, lineHeight = 16.sp)
            }
            if (value != null) Text(value, fontSize = 15.sp, color = Pal.muted, modifier = Modifier.padding(start = 8.dp))
            trailing?.invoke()
            if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Pal.faint, modifier = Modifier.padding(start = 4.dp))
        }
        if (divider) HorizontalDivider(Modifier.padding(start = if (icon != null) 58.dp else 16.dp), thickness = 0.5.dp, color = Color.White.copy(alpha = 0.1f))
    }
}

// MARK: - States

@Composable
fun Shimmer(modifier: Modifier, shape: Shape = RoundedCornerShape(12.dp)) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "x")
    Box(modifier.clip(shape).background(Pal.card).drawWithContent {
        drawContent()
        drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.07f), Color.Transparent),
            start = Offset(size.width * x, 0f), end = Offset(size.width * (x + 0.6f), size.height)))
    })
}

@Composable
fun ShelfSkeleton() {
    Column(Modifier.padding(top = 26.dp)) {
        Shimmer(Modifier.padding(horizontal = 16.dp).width(180.dp).height(22.dp), RoundedCornerShape(6.dp))
        Row(Modifier.padding(start = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(4) { Shimmer(Modifier.width(118.dp).height(177.dp)) }
        }
    }
}

@Composable
fun ErrorState(message: String, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.WifiOff, null, Modifier.size(40.dp), tint = Pal.muted)
        Text(message, color = Pal.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 10.dp))
        TextButton(onClick = retry) { Text("Повторить", color = Pal.pink, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(46.dp), tint = Pal.faint)
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp), textAlign = TextAlign.Center)
        if (subtitle != null) Text(subtitle, fontSize = 14.sp, color = Pal.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier.fillMaxWidth().height(220.dp)) =
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Pal.pink, strokeWidth = 3.dp) }

@Composable
fun Centered(content: @Composable BoxScope.() -> Unit) = Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center, content = content)

// MARK: - Bookmarks

val Bookmark.icon: ImageVector get() = when (this) {
    Bookmark.WATCHING -> Icons.Default.Visibility; Bookmark.PLANNED -> Icons.Default.Schedule; Bookmark.DROPPED -> Icons.Default.Block
    Bookmark.COMPLETED -> Icons.Default.CheckCircle; Bookmark.FAVORITE -> Icons.Default.Favorite; Bookmark.REWATCHING -> Icons.Default.Replay
    Bookmark.ON_HOLD -> Icons.Default.PauseCircle
}
val Bookmark.color: Color get() = when (this) {
    Bookmark.WATCHING -> Pal.green; Bookmark.PLANNED -> Pal.blue; Bookmark.DROPPED -> Pal.red; Bookmark.COMPLETED -> Pal.purple
    Bookmark.FAVORITE -> Pal.pink; Bookmark.REWATCHING -> Pal.teal; Bookmark.ON_HOLD -> Pal.orange
}

/** "+" menu with every bookmark folder. */
@Composable
fun BookmarkMenu(title: Title, open: Boolean, onDismiss: () -> Unit) {
    val current = Store.bookmark(title.slug)
    DropdownMenu(open, onDismiss, containerColor = Pal.cardHi) {
        Bookmark.entries.forEach { b ->
            DropdownMenuItem({ Text(b.title) }, onClick = { onDismiss(); Store.setBookmark(title, b) },
                leadingIcon = { Icon(if (b == current) Icons.Default.CheckCircle else b.icon, null, tint = if (b == current) Pal.pink else b.color) })
        }
        if (current != null) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            DropdownMenuItem({ Text("Удалить из закладок", color = Pal.red) }, onClick = { onDismiss(); Store.setBookmark(title, null) })
        }
    }
}

fun fmt(s: Double): String {
    val t = s.toInt().coerceAtLeast(0)
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10; val m100 = n % 100
    return when { m10 == 1 && m100 != 11 -> one; m10 in 2..4 && m100 !in 12..14 -> few; else -> many }
}

// MARK: - TV

/** What the TV is doing now, from its retained state message; null when nothing plays there. */
data class TvNow(val title: Title, val ep: String, val epName: String, val episodeId: Int, val team: String,
                 val pos: Double, val dur: Double, val playing: Boolean, val device: String, val hasNext: Boolean,
                 val hasPrev: Boolean, val inOpening: Boolean, val at: Double)

fun tvNow(s: JSONObject?): TvNow? {
    if (s == null) return null
    val st = s.optString("state")
    if (st != "playing" && st != "paused") return null
    val id = s.optInt("titleId"); val slug = s.optString("slug")
    if (id <= 0 || slug.isEmpty()) return null
    val t = Title(id = id, slug = slug, rusName = s.optString("title").ifEmpty { null }, coverThumb = s.optString("cover").ifEmpty { null })
    var pos = s.optDouble("pos", 0.0)
    val at = s.optDouble("at", 0.0)
    val now = System.currentTimeMillis() / 1000.0 + Sync.serverClockOffset
    // Between the TV's updates (every 5 s) the position moves on by itself while it plays.
    if (st == "playing" && at > 0) pos += (now - at).coerceIn(0.0, 30.0)
    val dur = s.optDouble("dur", 0.0)
    return TvNow(t, s.optString("ep"), s.optString("epName"), s.optInt("episodeId"), s.optString("team"),
        if (dur > 0) pos.coerceAtMost(dur) else pos, dur, st == "playing", s.optString("device").ifEmpty { "ТВ" },
        s.optBoolean("hasNext"), s.optBoolean("hasPrev"), s.optBoolean("inOpening"), at)
}

/** Ticks once a second while shown (positions move between TV updates). */
@Composable
fun rememberTicker(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    return now
}

/** "Сейчас на ТВ" card: continue right here from the TV's exact moment, or open the remote. */
@Composable
fun NowOnTvCard(nav: Nav, modifier: Modifier = Modifier) {
    val state by Remote.tvState.collectAsState()
    rememberTicker()
    val tv = tvNow(state) ?: return
    val now = System.currentTimeMillis() / 1000.0 + Sync.serverClockOffset
    if (!tv.playing && now - tv.at > 6 * 3600) return
    Row(
        modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Pal.pink.copy(alpha = 0.32f), Pal.violet.copy(alpha = 0.26f))))
            .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
            .clickable { nav.push(RemoteRoute) }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosterImage(tv.title.thumb, Modifier.width(50.dp), 8.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tv, null, Modifier.size(13.dp), tint = Color.White.copy(alpha = 0.8f))
                Text(" ${if (tv.playing) "Сейчас на ТВ" else "На паузе на ТВ"} · ${tv.device}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
            }
            Text(tv.title.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Серия ${tv.ep} · ${fmt(tv.pos)}${if (tv.dur > 0) " из " + fmt(tv.dur) else ""}", fontSize = 12.sp, color = Pal.muted)
            if (tv.dur > 0) ProgressLine((tv.pos / tv.dur).toFloat(), Modifier.padding(top = 6.dp), 3.dp)
        }
        Row(Modifier.clip(CircleShape).background(Color.White).pressable {
            if (tv.playing) Remote.send("pause")
            nav.play(PlayerRoute(tv.title, tv.episodeId.takeIf { it > 0 }, startAt = tv.pos))
        }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp), tint = Color.Black)
            Text("Здесь", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
