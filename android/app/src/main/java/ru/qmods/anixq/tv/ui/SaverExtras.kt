package ru.qmods.anixq.tv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ru.qmods.anixq.tv.data.Bookmark
import ru.qmods.anixq.tv.data.Schedule
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Title
import java.io.File

/** Frames saved from the player for the screensaver ("Кадр в заставку"), newest 60 kept. */
object SaverFrames {
    private var dir: File? = null
    fun init(c: Context) { dir = File(c.filesDir, "saver-frames").apply { mkdirs() } }
    fun save(b: Bitmap, title: String): Boolean = runCatching {
        val d = dir ?: return false
        val name = "${System.currentTimeMillis()}_${title.replace(Regex("[^\\p{L}\\p{N} ]"), "").take(60)}.jpg"
        File(d, name).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        d.listFiles()?.sortedByDescending { it.lastModified() }?.drop(60)?.forEach { it.delete() }
        true
    }.getOrDefault(false)
    fun list(): List<File> = dir?.listFiles()?.filter { it.extension == "jpg" }?.sortedByDescending { it.lastModified() }.orEmpty()
    fun caption(f: File) = f.nameWithoutExtension.substringAfter('_').ifBlank { null }
}

/** One slide: your own frame, or the wide art of a title you love. */
private sealed interface Slide {
    data class Frame(val file: File) : Slide
    data class Art(val title: Title, val image: String?) : Slide
}

/**
 * Slideshow with a slow "Ken Burns" camera: your frames from the player and the art of favourite / watched /
 * best-rated titles, a big clock — and the countdown to the next episode of what you watch.
 */
@Composable
internal fun FavouritesSaver() {
    val slides = remember {
        val frames = SaverFrames.list().shuffled().take(20).map { Slide.Frame(it) }
        val loved = (Store.titles(Bookmark.FAVORITE) + Store.continueList.map { it.title } + Store.titles(Bookmark.WATCHING) +
            Store.titles(Bookmark.COMPLETED).filter { (it.rating ?: 0.0) >= 8 }).distinctBy { it.id }.shuffled().take(20)
        val fallback = if (frames.size + loved.size < 6) HomeData.rows.value.flatMap { it.second }.distinctBy { it.id }.shuffled().take(20) else emptyList()
        (frames.map { it as Slide } + (loved + fallback).map { Slide.Art(it, null) }).shuffled()
    }
    var i by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(12_000); i += 1; now = System.currentTimeMillis() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val slide = slides.getOrNull(if (slides.isEmpty()) 0 else i % slides.size)
        Crossfade(slide, animationSpec = tween(1800), label = "saver") { cur ->
            when (cur) {
                is Slide.Frame -> {
                    val bmp by produceState<Bitmap?>(null, cur.file) { value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(cur.file.path) }.getOrNull() } }
                    bmp?.let { KenBurns { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
                }
                is Slide.Art -> {
                    val art by produceState<String?>(cur.title.poster, cur.title.slug) { value = (TitleCache.detail[cur.title.slug] ?: Spotlight.cached(cur.title.slug) ?: runCatching { Spotlight.get(cur.title.slug) }.getOrNull())?.background ?: cur.title.poster }
                    KenBurns { Cover(art, Modifier.fillMaxSize()) }
                }
                null -> Box(Modifier.fillMaxSize())
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = .7f), .55f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.5f to Color.Transparent, 1f to Color.Black.copy(alpha = .75f))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 72.dp, bottom = 70.dp).width(640.dp)) {
            Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now)), fontSize = 84.sp, fontWeight = FontWeight.Light, color = Color.White)
            Text(java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale("ru")).format(java.util.Date(now)), fontSize = 20.sp, color = Color.White.copy(alpha = .75f))
            Spacer(Modifier.height(24.dp))
            when (slide) {
                is Slide.Frame -> { Text("Ваш кадр", fontSize = 14.sp, color = Color.White.copy(alpha = .6f)); SaverFrames.caption(slide.file)?.let { Text(it, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2) } }
                is Slide.Art -> { Text(slide.title.title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2); Text(slide.title.meta, fontSize = 16.sp, color = Color.White.copy(alpha = .7f)) }
                null -> {}
            }
        }
        NextEpisodeCountdown(Modifier.align(Alignment.TopEnd).padding(48.dp))
    }
}

/** Slow zoom and drift across the picture, a new direction each time. */
@Composable
private fun KenBurns(content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    val dir = remember { listOf(-1f, 1f).random() to listOf(-1f, 1f).random() }
    LaunchedEffect(Unit) { if (!Store.weakDevice) p.animateTo(1f, tween(14_000, easing = LinearEasing)) }
    Box(Modifier.fillMaxSize().graphicsLayer {
        val s = 1.06f + .08f * p.value
        scaleX = s; scaleY = s
        translationX = dir.first * 30.dp.toPx() * p.value; translationY = dir.second * 16.dp.toPx() * p.value
    }) { content() }
}

/**
 * "Скоро новая серия": the nearest episode (next 2 days) of what you watch, counting down —
 * shown over the screensaver.
 */
@Composable
internal fun NextEpisodeCountdown(modifier: Modifier = Modifier) {
    val next by produceState<Schedule.Item?>(null) {
        value = runCatching { Schedule.load() }.getOrDefault(emptyList())
            .filter { Schedule.isMine(it.shikiId) && it.at > System.currentTimeMillis() && it.at - System.currentTimeMillis() < 48 * 3600_000L }
            .minByOrNull { it.at }
    }
    val item = next ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(item) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    val left = ((item.at - now) / 1000).coerceAtLeast(0)
    Row(modifier.clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = .55f)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(item.asTitle().poster, Modifier.width(58.dp).height(82.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.padding(start = 14.dp)) {
            Text("СКОРО НОВАЯ СЕРИЯ", fontSize = 11.sp, letterSpacing = 2.sp, color = C.pink, fontWeight = FontWeight.Bold)
            Text(item.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, modifier = Modifier.widthIn(max = 320.dp))
            Text("Серия ${item.episode}", fontSize = 13.sp, color = Color.White.copy(alpha = .7f))
            Text(if (left == 0L) "уже вышла" else "%d:%02d:%02d".format(left / 3600, left / 60 % 60, left % 60), fontSize = 30.sp, fontWeight = FontWeight.Light, color = Color.White)
        }
    }
}
