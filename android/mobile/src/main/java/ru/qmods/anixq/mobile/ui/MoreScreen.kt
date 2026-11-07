package ru.qmods.anixq.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import ru.qmods.anixq.mobile.BuildConfig
import ru.qmods.anixq.mobile.data.PairSender
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.LibAuth
import ru.qmods.anixq.tv.data.Remote
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Sync
import ru.qmods.anixq.tv.data.UpdateState
import ru.qmods.anixq.tv.data.Updater

/** Shared pairing entry: a scanned code or an opened anixq://pair link. */
object PairRequest { var pending by mutableStateOf<String?>(null) }

@Composable
fun MoreScreen(nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by Store.version.collectAsState()
    val status by Sync.status.collectAsState()
    val online by Remote.online.collectAsState()
    val update by Updater.state.collectAsState()
    var pairing by remember { mutableStateOf<String?>(null) }

    fun pair(text: String) {
        val t = PairSender.parse(text) ?: run { pairing = "Это не QR-код AnixQ с телевизора"; return }
        if (Store.libToken == null && Store.githubToken == null) { pairing = "Сначала войдите в AnimeLib или GitHub здесь — телевизор получит эти входы"; return }
        pairing = "Отправляем на телевизор…"
        scope.launch {
            pairing = try { PairSender.send(t); "Готово: телевизор получил ваши входы" } catch (e: Exception) { "Не получилось: ${e.message}" }
        }
    }
    LaunchedEffect(PairRequest.pending) { PairRequest.pending?.let { PairRequest.pending = null; pair(it) } }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Ещё", fontSize = 28.sp, fontWeight = FontWeight.Black)

        Card(Icons.Default.CloudSync, "Синхронизация", if (Sync.isOn) "GitHub: ${Store.githubLogin ?: "подключено"} · $status" else
            "Прогресс, списки и озвучки — одни и те же на телефоне, телевизоре и iPhone") {
            if (Sync.isOn) Row {
                OutlinedButton(onClick = { Sync.syncNow() }) { Text("Синхронизировать") }
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = { Sync.signOut(); Remote.stop() }) { Text("Выйти") }
            } else Button(onClick = { nav.push(SyncRoute) }, colors = ButtonDefaults.buttonColors(containerColor = Pal.pink)) { Text("Войти через GitHub") }
        }

        Card(Icons.Default.SettingsRemote, "Телевизор", when {
            Store.remoteChannel.isEmpty() -> "Появится после синхронизации: войдите в GitHub под тем же аккаунтом, что и на ТВ"
            online -> "Связь с ТВ есть: прогресс переходит мгновенно, работает пульт и «На ТВ»"
            else -> "Подключаемся к ТВ…"
        }) {
            Row {
                if (Store.remoteChannel.isNotEmpty()) { OutlinedButton(onClick = { nav.push(RemoteRoute) }) { Text("Пульт") }; Spacer(Modifier.size(8.dp)) }
                OutlinedButton(onClick = {
                    scanQr(context, onResult = { pair(it) }, onError = { pairing = it })
                }) { Icon(Icons.Default.QrCodeScanner, null, Modifier.size(18.dp)); Text(" Подключить ТВ") }
            }
            Text("На телевизоре: Настройки → «Подключить телефон», затем отсканируйте QR. ТВ получит входы в AnimeLib, Шикимори и GitHub.",
                fontSize = 12.sp, color = Pal.muted, modifier = Modifier.padding(top = 8.dp))
            pairing?.let { Text(it, fontSize = 13.sp, color = Pal.gold, modifier = Modifier.padding(top = 6.dp)) }
        }

        Card(Icons.Default.AccountCircle, "AnimeLib", Store.libUser?.let { "Вы вошли как $it — отметки и таймкоды сохраняются на сайте" }
            ?: "Вход нужен для части серий (18+) и сохранения отметок на сайте") {
            if (Store.libToken != null) OutlinedButton(onClick = { Store.setLib(null, null, null, null) }) { Text("Выйти") }
            else Button(onClick = { nav.push(LibLoginRoute) }, colors = ButtonDefaults.buttonColors(containerColor = Pal.pink)) { Text("Войти в AnimeLib") }
        }

        Text("Плеер", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
        Toggle("Пропускать опенинги сами", Store.autoSkipOpening) { Store.autoSkipOpening = it }
        Toggle("Следующая серия автоматически", Store.autoNext) { Store.autoNext = it }
        Toggle("Субтитры (где есть)", Store.subtitles) { Store.subtitles = it }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Качество по умолчанию", Modifier.weight(1f))
            listOf(480, 720, 1080).forEach { q ->
                val sel = Store.preferredQuality == q
                Text("${q}p", Modifier.padding(start = 6.dp).clip(RoundedCornerShape(10.dp)).background(if (sel) Pal.pink else Pal.card)
                    .clickable { Store.preferredQuality = q }.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 13.sp)
            }
        }

        Card(Icons.Default.SystemUpdate, "Версия ${BuildConfig.VERSION_NAME}", when (val u = update) {
            is UpdateState.Available -> "Доступна версия ${u.release.name}"
            is UpdateState.Downloading -> "Загрузка ${(u.progress * 100).toInt()}%"
            is UpdateState.Installing -> "Установка…"
            is UpdateState.UpToDate -> "Установлена последняя версия"
            is UpdateState.Checking -> "Проверяем…"
            is UpdateState.Failed -> u.message
            else -> "Обновления приходят прямо в приложение"
        }) {
            if (Updater.available != null) Button(onClick = { Updater.install(context) }, colors = ButtonDefaults.buttonColors(containerColor = Pal.pink)) { Text("Обновить") }
            else OutlinedButton(onClick = { Updater.check() }) { Text("Проверить обновления") }
        }
    }
}

@Composable
private fun Card(icon: ImageVector, title: String, sub: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.card).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Pal.pink)
            Text(" $title", fontWeight = FontWeight.SemiBold)
        }
        Text(sub, fontSize = 13.sp, color = Pal.muted, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
        content()
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, set: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(value) }
    Row(Modifier.fillMaxWidth().clickable { v = !v; set(v) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(v, { v = it; set(it) }, colors = SwitchDefaults.colors(checkedTrackColor = Pal.pink))
    }
}

/** Google's code scanner: no camera permission, the system draws the viewfinder. */
fun scanQr(context: Context, onResult: (String) -> Unit, onError: (String) -> Unit) {
    val opts = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
    com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, opts).startScan()
        .addOnSuccessListener { b -> b.rawValue?.let(onResult) }
        .addOnFailureListener { onError("Сканер недоступен (${it.message}). Можно навести обычную камеру телефона на QR — ссылка откроет AnixQ.") }
}

/** GitHub sign-in by code (device flow): same account as on the TV and iPhone → one library everywhere. */
@Composable
fun SyncLoginScreen(nav: Nav) {
    val context = LocalContext.current
    val login by Sync.deviceLogin.collectAsState()
    val status by Sync.status.collectAsState()
    val version by Store.version.collectAsState()
    LaunchedEffect(Unit) { if (Sync.deviceLogin.value == null && !Sync.isOn) Sync.signInOnTv() }
    LaunchedEffect(version) { if (Sync.isOn) nav.pop() }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("Вход в GitHub", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(24.dp))
        val l = login
        if (l == null) { CircularProgressIndicator(color = Pal.pink); Text(status, color = Pal.muted, modifier = Modifier.padding(top = 12.dp)); return@Column }
        Text("Ваш код", color = Pal.muted)
        Text(l.first, fontSize = 40.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 8.dp))
        Button(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", l.first))
            Toast.makeText(context, "Код скопирован — вставьте его на странице GitHub", Toast.LENGTH_SHORT).show()
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l.second))) }
        }, colors = ButtonDefaults.buttonColors(containerColor = Pal.pink), modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text("Скопировать код и открыть GitHub")
        }
        Text("Войдите в тот же аккаунт GitHub, что на телевизоре и iPhone, вставьте код и нажмите Authorize. Сюда вернитесь сами — вход завершится автоматически.\n\nНужен только доступ к приватным заметкам (gist), где хранится ваша библиотека.",
            color = Pal.muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
    }
}

/** AnimeLib's own sign-in page; the person presses "Я не робот" and "Войти" themselves. */
@Composable
fun LibLoginScreen(nav: Nav) {
    val scope = rememberCoroutineScope()
    val auth = remember { LibAuth() }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("Вход в AnimeLib", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (busy) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(18.dp), color = Pal.pink, strokeWidth = 2.dp)
        }
        AndroidView(factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = settings.userAgentString.replace("; wv", "")
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest): Boolean {
                        val code = auth.codeFrom(request.url.toString()) ?: return false
                        busy = true
                        scope.launch {
                            val ok = runCatching { auth.finish(code) }.getOrDefault(false)
                            if (ok) { Api.loadProfile(); nav.pop() } else { busy = false; view.loadUrl(auth.url) }
                        }
                        return true
                    }
                }
                loadUrl(auth.url)
            }
        }, modifier = Modifier.fillMaxSize())
    }
    DisposableEffect(Unit) { onDispose { } }
}
