package ru.qmods.anixq.mobile.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Trace

/** Everything the iPhone player has under "Ещё": timer, frame, who-is-this, own subtitles, size, listen mode, notes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerMoreSheet(m: PlayerModel, nav: Nav, listen: Boolean, onListen: (Boolean) -> Unit, onFrame: (Bitmap) -> Unit, onWho: (Bitmap) -> Unit,
                    onNote: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var page by remember { mutableStateOf("main") }
    val pickSubs = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        m.addSubtitles(uri.toString(), displayName(context, uri) ?: "Субтитры.srt")
        onClose()
    }
    fun grab(then: (Bitmap) -> Unit) {
        val e = m.exo ?: return
        e.captureFrame { b -> if (b != null) then(b) else Toast.makeText(context, "Не удалось взять кадр", Toast.LENGTH_SHORT).show() }
    }
    ModalBottomSheet(onClose, containerColor = Pal.card) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            when (page) {
                "sleep" -> {
                    SheetTitle("Таймер сна")
                    listOf<Pair<String, Int?>>("До конца серии" to 0, "15 минут" to 15, "30 минут" to 30, "45 минут" to 45, "1 час" to 60, "Выключить" to null)
                        .forEach { (label, v) -> SheetRow(Icons.Default.Bedtime, label) { m.setSleep(v); onClose() } }
                }
                "subs" -> {
                    SheetTitle("Субтитры")
                    var scale by remember { mutableFloatStateOf(Store.subtitleScale) }
                    Text("Размер: ${(scale * 100).toInt()}%", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
                    Slider(scale, { scale = it; Store.subtitleScale = it; m.exo?.styleSubtitles(it, Store.subtitleBox) }, valueRange = 0.7f..1.8f,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        colors = SliderDefaults.colors(thumbColor = Pal.pink, activeTrackColor = Pal.pink))
                    SheetSwitch(Icons.Default.TextFields, "Тёмная подложка", Store.subtitleBox) { Store.subtitleBox = it; m.exo?.styleSubtitles(Store.subtitleScale, it) }
                    SheetRow(Icons.Default.Subtitles, "Загрузить файл SRT / ASS…") { pickSubs.launch(arrayOf("*/*")) }
                    if (m.player?.subtitles?.isNotEmpty() == true) SheetRow(Icons.Default.ClosedCaption, if (m.subtitlesOn) "Выключить субтитры" else "Включить субтитры") { m.toggleSubtitles(); onClose() }
                }
                else -> {
                    SheetTitle("Ещё")
                    SheetRow(Icons.Default.Bedtime, "Таймер сна", m.sleepLabel) { page = "sleep" }
                    if (m.native) {
                        SheetRow(Icons.Default.CameraAlt, "Стоп-кадр", "сохранить в галерею") { grab { onClose(); onFrame(it) } }
                        SheetRow(Icons.Default.PersonSearch, "Кто это?", "узнать персонажа на кадре") { grab { onClose(); onWho(it) } }
                        SheetRow(Icons.Default.Subtitles, "Субтитры", "размер, подложка, свой файл") { page = "subs" }
                        SheetSwitch(Icons.Default.Headphones, "Режим прослушивания", listen) { onListen(it); onClose() }
                        SheetSwitch(Icons.AutoMirrored.Filled.Comment, "Комментарии на видео", Store.danmaku) { Store.danmaku = it }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Pal.stroke)
                    SheetRow(Icons.Default.Edit, "Заметка к серии") { onClose(); onNote() }
                    m.episode?.let { ep ->
                        SheetRow(Icons.Default.ChatBubbleOutline, "Обсуждение серии") {
                            onClose(); nav.push(CommentsRoute(ep.id, "episodes", "${m.title.title} · серия ${ep.number}"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(t: String) = Text(t, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

@Composable
private fun SheetRow(icon: ImageVector, title: String, value: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Pal.pink)
        Text(title, fontSize = 16.sp, modifier = Modifier.padding(start = 16.dp).weight(1f))
        value?.let { Text(it, fontSize = 13.sp, color = Pal.muted) }
    }
}

@Composable
private fun SheetSwitch(icon: ImageVector, title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(value) }
    Row(Modifier.fillMaxWidth().clickable { on = !on; onChange(on) }.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Pal.pink)
        Text(title, fontSize = 16.sp, modifier = Modifier.padding(start = 16.dp).weight(1f))
        Switch(on, { on = it; onChange(it) }, colors = SwitchDefaults.colors(checkedTrackColor = Pal.green, checkedThumbColor = Color.White,
            uncheckedTrackColor = Pal.cardHi, uncheckedBorderColor = Pal.cardHi, uncheckedThumbColor = Color.White))
    }
}

fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}.getOrNull()

/** Saves the frame to Pictures/AnixQ and returns its address. */
fun saveFrame(context: Context, b: Bitmap, name: String): Uri? = runCatching {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AnixQ")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    context.contentResolver.openOutputStream(uri)?.use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    uri
}.getOrNull()

/** The captured frame: save to the gallery or share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameSheet(m: PlayerModel, frame: Bitmap, onClose: () -> Unit) {
    val context = LocalContext.current
    val name = "AnixQ ${m.title.title.take(40)} ${m.episode?.number ?: ""} ${fmt(m.pos).replace(':', '-')}"
    ModalBottomSheet(onClose, containerColor = Pal.card) {
        Column(Modifier.padding(16.dp).navigationBarsPadding()) {
            Image(frame.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(frame.width.toFloat() / frame.height).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
            Text("${m.title.title} · серия ${m.episode?.number ?: ""} · ${fmt(m.pos)}", fontSize = 13.sp, color = Pal.muted, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(Modifier.weight(1f), onClick = {
                    val ok = saveFrame(context, frame, name) != null
                    Toast.makeText(context, if (ok) "Сохранено в галерею (AnixQ)" else "Не удалось сохранить", Toast.LENGTH_SHORT).show()
                    onClose()
                }) { Text("Сохранить", color = Color.White, fontWeight = FontWeight.SemiBold) }
                PrimaryButton(Modifier.weight(1f), onClick = {
                    saveFrame(context, frame, name)?.let { uri ->
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_TEXT, "${m.title.title} — серия ${m.episode?.number ?: ""}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                    }
                    onClose()
                }) { Text("Поделиться", color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** "Кто это?": characters on the frame recognised by AnimeTrace. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhoIsThisSheet(frame: Bitmap, onClose: () -> Unit) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<List<Trace.CharacterMatch>?>(null) }
    LaunchedEffect(frame) { result = runCatching { Trace.characters(frame) }.getOrDefault(emptyList()) }
    ModalBottomSheet(onClose, containerColor = Pal.card) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text("Кто это?", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Image(frame.asImageBitmap(), null, Modifier.padding(vertical = 10.dp).fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
            val r = result
            when {
                r == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Pal.pink, strokeWidth = 2.dp)
                    Text("  Узнаём персонажей…", color = Pal.muted)
                }
                r.isEmpty() -> Text("Не удалось узнать — попробуйте кадр, где лицо крупнее.", color = Pal.muted)
                else -> r.forEach { w ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        w.characters.forEach { c ->
                            Text(c, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shikimori.io/characters?search=" + Uri.encode(c))))
                            })
                        }
                        Text(w.work, fontSize = 13.sp, color = Pal.muted)
                    }
                }
            }
            Text("Распознаёт AnimeTrace", fontSize = 11.sp, color = Pal.faint, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Timecoded comments flying right-to-left at their moment (like Niconico). */
@Composable
fun DanmakuLayer(m: PlayerModel, modifier: Modifier = Modifier) {
    if (!Store.danmaku || m.timed.isEmpty() || !m.native) return
    val now = m.pos
    val visible = m.timed.filter { now >= it.time && now < it.time + 8 }.take(6)
    BoxWithConstraints(modifier) {
        val width = with(LocalDensity.current) { maxWidth.toPx() }
        visible.forEachIndexed { lane, c ->
            key(c.id) {
                val x = remember { Animatable(width) }
                LaunchedEffect(Unit) { x.animateTo(-width * 0.9f, tween(((c.time + 8 - now) * 1000).toInt().coerceAtLeast(500), easing = LinearEasing)) }
                Text(c.text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.offset { IntOffset(x.value.toInt(), (40 + lane * 30).dp.roundToPx()) }
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun key(k: Any, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }

/** Listen mode: the picture off (battery), cover and big controls — the sound keeps playing. */
@Composable
fun ListenOverlay(m: PlayerModel, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Img(m.title.cover ?: m.title.coverThumb, Modifier.fillMaxSize(), alpha = 0.25f)
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Img(m.title.cover ?: m.title.coverThumb, Modifier.width(150.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)))
            Text(m.title.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp))
            Text("Серия ${m.episode?.number ?: ""} · ${fmt(m.pos)} / ${fmt(m.dur)}", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            TextButton(onExit, Modifier.padding(top = 10.dp)) { Text("Показать видео", color = Pal.pink) }
        }
    }
}

/** End of the title: own score 1–10 (also sent to Shikimori), like on the TV. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RateSheet(m: PlayerModel) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    ModalBottomSheet({ m.ratePrompt = false }, containerColor = Pal.card) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Как вам «${m.title.title}»?", fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(if (Store.shikiJson != null && m.title.shikiId != null) "Оценка попадёт в ваш профиль Shikimori" else "Оценка сохранится в AnixQ",
                fontSize = 13.sp, color = Pal.muted, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (n in 1..10) {
                    val tint = when { n <= 4 -> Pal.red; n <= 6 -> Pal.muted; n <= 7 -> Pal.orange; else -> Pal.green }
                    Box(Modifier.size(30.dp).clip(CircleShape).background(tint.copy(alpha = 0.22f)).clickable {
                        m.ratePrompt = false
                        Store.setRating(m.title.id, n)
                        ru.qmods.anixq.mobile.data.Notes.set(m.title.id, ru.qmods.anixq.mobile.data.Notes.get(m.title.id).copy(rating = n))
                        if (Store.shikiJson != null && m.title.shikiId != null)
                            ru.qmods.anixq.tv.data.Shiki.setScore(m.title.shikiId, n) { ok -> Toast.makeText(context, if (ok) "Оценка $n — в профиле Shikimori" else "Оценка $n сохранена", Toast.LENGTH_SHORT).show() }
                        else Toast.makeText(context, "Оценка $n сохранена", Toast.LENGTH_SHORT).show()
                    }, contentAlignment = Alignment.Center) { Text("$n", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = tint) }
                }
            }
            TextButton({ m.ratePrompt = false }, Modifier.padding(top = 8.dp)) { Text("Не сейчас", color = Pal.muted) }
        }
    }
}
