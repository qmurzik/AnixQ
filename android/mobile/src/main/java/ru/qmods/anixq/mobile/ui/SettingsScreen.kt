package ru.qmods.anixq.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
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

/** QR pairing with a TV (scanner in the app or an opened anixq://pair link). */
object PairFlow {
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    /** Link opened from the phone's camera app; handled by the visible screen. */
    var pending by mutableStateOf<String?>(null)

    fun scan(context: Context, scope: CoroutineScope) {
        val opts = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, opts).startScan()
            .addOnSuccessListener { b -> b.rawValue?.let { run(it, scope) } }
            .addOnFailureListener { message = "Сканер недоступен (${it.message}). Наведите обычную камеру телефона на QR — ссылка откроет AnixQ." }
    }

    fun run(text: String, scope: CoroutineScope) {
        val t = PairSender.parse(text) ?: run { message = "Это не QR-код AnixQ с телевизора"; return }
        busy = true; message = "Связываемся с телевизором…"
        scope.launch {
            message = try {
                when (val r = PairSender.exchange(t) { message = "На телевизоре нажмите «Разрешить»" }) {
                    PairSender.Outcome.Sent -> "Готово: телевизор получил ваши входы"
                    is PairSender.Outcome.Received -> if (r.what.isEmpty()) "Готово: телевизор подключён" else "Готово: получено с ТВ — ${r.what.joinToString(", ")}"
                }
            } catch (e: Exception) { "Не получилось: ${e.message}" }
            busy = false
        }
    }
}

@Composable
fun SettingsScreen(nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by Store.version.collectAsState()
    val status by Sync.status.collectAsState()
    val online by Remote.online.collectAsState()
    val update by Updater.state.collectAsState()
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        TopBar("Настройки", { nav.pop() })

        Group("Синхронизация", if (Sync.isOn) null else "Прогресс, закладки и озвучки — общие для телефона, телевизора и iPhone. Хранятся в вашем приватном GitHub Gist.") {
            if (Sync.isOn) {
                GroupRow("GitHub · ${Store.githubLogin ?: "подключено"}", status, Icons.Default.CloudSync, Pal.blue)
                GroupRow("Синхронизировать сейчас", icon = Icons.Default.Refresh, iconColor = Pal.green) { Sync.syncNow() }
                GroupRow("Выйти из GitHub", icon = Icons.AutoMirrored.Filled.Logout, iconColor = Pal.red, titleColor = Pal.red, divider = false) { Sync.signOut(); Remote.stop() }
            } else {
                GroupRow("Войти через GitHub", "Тот же аккаунт, что на ТВ и iPhone", Icons.Default.CloudSync, Pal.blue, chevron = true) { nav.push(SyncRoute) }
                GroupRow("Взять вход с телевизора", "Отсканируйте QR на ТВ: Настройки → «Связать с телефоном»", Icons.Default.QrCodeScanner, Pal.purple, divider = false) {
                    PairFlow.scan(context, scope)
                }
            }
        }
        PairFlow.message?.let { Text(it, color = Pal.gold, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp)) }

        Group("Телевизор", "На ТВ: Настройки → «Связать с телефоном» и отсканируйте QR. Телефон передаст свои входы или попросит вход с ТВ.") {
            GroupRow("Пульт ТВ", when { Store.remoteChannel.isEmpty() -> "Появится после синхронизации"; online -> "На связи"; else -> "Подключаемся…" },
                Icons.Default.SettingsRemote, Pal.pink, chevron = Store.remoteChannel.isNotEmpty()) { if (Store.remoteChannel.isNotEmpty()) nav.push(RemoteRoute) }
            GroupRow("Подключить ТВ по QR", icon = Icons.Default.Tv, iconColor = Pal.indigo, divider = false,
                trailing = { if (PairFlow.busy) CircularProgressIndicator(Modifier.size(18.dp), color = Pal.pink, strokeWidth = 2.dp) }) { PairFlow.scan(context, scope) }
        }

        Group("AnimeLib", if (Store.libToken == null) "Вход нужен для части серий и сохранения отметок на сайте." else null) {
            if (Store.libToken != null) {
                GroupRow(Store.libUser ?: "Вы вошли", "Аккаунт AnimeLib", Icons.Default.AccountCircle, Pal.orange)
                GroupRow("Выйти", icon = Icons.AutoMirrored.Filled.Logout, iconColor = Pal.red, titleColor = Pal.red, divider = false) { Store.setLib(null, null, null, null) }
            } else GroupRow("Войти в AnimeLib", icon = Icons.Default.AccountCircle, iconColor = Pal.orange, chevron = true, divider = false) { nav.push(LibLoginRoute) }
        }

        Group("Shikimori", if (Store.shikiJson == null) "Просмотренные серии отмечаются в вашем профиле Shikimori — на телефоне, ТВ и iPhone." else null) {
            if (Store.shikiJson != null) {
                GroupRow(Store.shikiNickname ?: "Вы вошли", "Аккаунт Shikimori", Icons.Default.AccountCircle, Pal.blue)
                GroupRow("Выйти", icon = Icons.AutoMirrored.Filled.Logout, iconColor = Pal.red, titleColor = Pal.red, divider = false) { Store.setShiki(null) }
            } else GroupRow("Войти в Shikimori", icon = Icons.Default.AccountCircle, iconColor = Pal.blue, chevron = true, divider = false) { nav.push(ShikiLoginRoute) }
        }

        Group("Плеер") {
            ToggleRow("Пропускать опенинги", Icons.Default.FastForward, Pal.purple, Store.autoSkipOpening) { Store.autoSkipOpening = it }
            ToggleRow("Следующая серия сама", Icons.Default.SkipNext, Pal.green, Store.autoNext) { Store.autoNext = it }
            ToggleRow("Субтитры, где есть", Icons.Default.Subtitles, Pal.teal, Store.subtitles) { Store.subtitles = it }
            ToggleRow("Режим марафона", Icons.Default.LocalFireDepartment, Pal.orange, Store.marathon) { Store.marathon = it }
            ToggleRow("Без спойлеров", Icons.Default.VisibilityOff, Pal.indigo, Store.noSpoilers) { Store.noSpoilers = it }
            GroupRow("Качество загрузок", icon = Icons.Default.Download, iconColor = Pal.indigo, trailing = {
                var dq by remember { mutableStateOf(ru.qmods.anixq.mobile.data.Downloads.quality) }
                Row(Modifier.clip(RoundedCornerShape(9.dp)).background(Pal.cardHi).padding(2.dp)) {
                    listOf(480, 720, 1080).forEach { q ->
                        Text("$q", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (dq == q) Color.Black else Pal.text,
                            modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(if (dq == q) Color.White else Color.Transparent)
                                .clickable { dq = q; ru.qmods.anixq.mobile.data.Downloads.quality = q }.padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
            })
            GroupRow("Качество", icon = Icons.Default.HighQuality, iconColor = Pal.blue, divider = false, trailing = {
                Row(Modifier.clip(RoundedCornerShape(9.dp)).background(Pal.cardHi).padding(2.dp)) {
                    listOf(480, 720, 1080).forEach { q ->
                        val sel = Store.preferredQuality == q
                        Text("$q", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (sel) Color.Black else Pal.text,
                            modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(if (sel) Color.White else Color.Transparent)
                                .clickable { Store.preferredQuality = q }.padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
            })
        }

        Group("О приложении") {
            GroupRow("Версия", icon = Icons.Default.Info, iconColor = Pal.faint, value = BuildConfig.VERSION_NAME)
            GroupRow(when (val u = update) {
                is UpdateState.Available -> "Обновить до ${u.release.name}"
                is UpdateState.Downloading -> "Загрузка ${(u.progress * 100).toInt()}%"
                is UpdateState.Installing -> "Установка…"
                is UpdateState.Checking -> "Проверяем…"
                is UpdateState.UpToDate -> "Последняя версия"
                else -> "Проверить обновления"
            }, (update as? UpdateState.Failed)?.message, Icons.Default.SystemUpdate, Pal.green, titleColor = if (Updater.available != null) Pal.pink else Pal.text) {
                if (Updater.available != null) Updater.install(context) else Updater.check()
            }
            GroupRow("Сайт AnixQ", icon = Icons.Default.Language, iconColor = Pal.blue, chevron = true, divider = false) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://qmurzik.github.io/AnixQ/"))) }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, value: Boolean, set: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(value) }
    GroupRow(title, icon = icon, iconColor = color, onClick = { v = !v; set(v) }, trailing = {
        Switch(v, { v = it; set(it) }, colors = SwitchDefaults.colors(checkedTrackColor = Pal.green, checkedThumbColor = Color.White,
            uncheckedTrackColor = Pal.cardHi, uncheckedBorderColor = Pal.cardHi, uncheckedThumbColor = Color.White))
    })
}

/** Opens a page in the browser (a Custom Tab), never in another app that claims the link. */
fun openInBrowser(context: Context, url: String) {
    runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url)) }
        .onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
}

/** GitHub sign-in by code (device flow): same account as on the TV and iPhone → one library everywhere. */
@Composable
fun SyncLoginScreen(nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val login by Sync.deviceLogin.collectAsState()
    val status by Sync.status.collectAsState()
    val version by Store.version.collectAsState()
    LaunchedEffect(Unit) { if (Sync.deviceLogin.value == null && !Sync.isOn) Sync.signInOnTv() }
    LaunchedEffect(version) { if (Sync.isOn) nav.pop() }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar("Синхронизация", { nav.pop() })
        Box(Modifier.padding(top = 18.dp).size(76.dp).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(Pal.blue, Pal.teal))),
            contentAlignment = Alignment.Center) { Icon(Icons.Default.CloudSync, null, Modifier.size(42.dp), tint = Color.White) }
        Text("Одна библиотека везде", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        Text("Войдите в тот же GitHub, что на телевизоре и iPhone — прогресс, закладки и озвучки станут общими, а пульт ТВ заработает сам.",
            fontSize = 15.sp, color = Pal.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))

        val l = login
        Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pal.card).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (l == null) {
                CircularProgressIndicator(color = Pal.pink)
                Text(status, color = Pal.muted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                if (status.startsWith("Ошибка") || status == "Вход отменён") Text("Попробовать снова", color = Pal.pink, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp).clickable { Sync.signInOnTv() })
            } else {
                Text("Код для GitHub", fontSize = 13.sp, color = Pal.muted)
                Text(l.first, fontSize = 38.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                    modifier = Modifier.padding(vertical = 10.dp).clip(RoundedCornerShape(12.dp)).background(Pal.cardHi).padding(horizontal = 16.dp, vertical = 6.dp))
                PrimaryButton(Modifier.fillMaxWidth(), onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("GitHub code", l.first))
                    Toast.makeText(context, "Код скопирован — вставьте его на странице GitHub", Toast.LENGTH_LONG).show()
                    openInBrowser(context, l.second)
                }) { Text("Скопировать код и открыть GitHub", color = Color.White, fontWeight = FontWeight.SemiBold) }
                Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Step("1", "На странице GitHub войдите и вставьте код")
                    Step("2", "Нажмите «Continue», затем «Authorize»")
                    Step("3", "Вернитесь сюда — вход завершится сам")
                }
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = Pal.muted, strokeWidth = 2.dp)
                    Text("  Ждём подтверждения на GitHub…", fontSize = 13.sp, color = Pal.muted)
                }
            }
        }
        Text("или", color = Pal.muted, fontSize = 13.sp)
        Row(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.card).border(0.5.dp, Pal.stroke, RoundedCornerShape(14.dp))
            .pressable { PairFlow.scan(context, scope) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ColoredIcon(Icons.Default.QrCodeScanner, Pal.purple, 36.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Взять вход с телевизора", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("На ТВ: Настройки → «Связать с телефоном», отсканируйте QR и разрешите на ТВ", fontSize = 12.5.sp, color = Pal.muted)
            }
        }
        PairFlow.message?.let { Text(it, color = Pal.gold, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp)) }
        Text("Нужен только доступ к приватным заметкам (gist), где хранится ваша библиотека.", fontSize = 12.sp, color = Pal.faint,
            textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
    }
}

@Composable
private fun Step(n: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(Pal.pink), contentAlignment = Alignment.Center) {
            Text(n, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Text(text, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

/** AnimeLib's own sign-in page; the person presses "Я не робот" and "Войти" themselves. */
@Composable
fun LibLoginScreen(nav: Nav) {
    val scope = rememberCoroutineScope()
    val auth = remember { LibAuth() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar("Вход в AnimeLib", { nav.pop() }) {
            if (busy) CircularProgressIndicator(Modifier.padding(end = 14.dp).size(18.dp), color = Pal.pink, strokeWidth = 2.dp)
        }
        error?.let { Text(it, color = Pal.gold, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        AndroidView(factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = settings.userAgentString.replace("; wv", "")
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : android.webkit.WebViewClient() {
                    private fun intercept(view: android.webkit.WebView, url: String): Boolean {
                        val code = auth.codeFrom(url) ?: return false
                        if (busy) return true
                        busy = true; error = null
                        scope.launch {
                            val ok = runCatching { auth.finish(code) }.getOrDefault(false)
                            if (ok) { Api.loadProfile(); nav.pop() }
                            else { busy = false; error = "AnimeLib не принял вход — попробуйте ещё раз"; view.loadUrl(auth.url) }
                        }
                        return true
                    }
                    override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest) =
                        intercept(view, request.url.toString())
                    override fun onPageStarted(view: android.webkit.WebView, url: String, favicon: android.graphics.Bitmap?) {
                        // Some redirects don't go through shouldOverrideUrlLoading.
                        if (auth.codeFrom(url) != null) { view.stopLoading(); intercept(view, url) }
                    }
                }
                loadUrl(auth.url)
            }
        }, modifier = Modifier.fillMaxSize())
    }
}
