package ru.qmods.anixq.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import ru.qmods.anixq.tv.BuildConfig
import ru.qmods.anixq.tv.data.Pairing
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Sync
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(nav: Nav) {
    val version by Store.version.collectAsState()
    val status by Sync.status.collectAsState()
    val last by Sync.lastSync.collectAsState()
    val device by Sync.deviceLogin.collectAsState()
    var pairing by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    remember(version) { 0 }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 56.dp, end = 56.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenTitle("Настройки") }

        item {
            Section("AnimeLib") {
                Text(Store.libUser?.let { "Аккаунт: $it — плеер без рекламы, отметки серий на сайте" }
                    ?: "Без входа доступен только плеер Kodik (с рекламой). Войти можно пультом или с любого телефона по QR — iPhone не нужен.",
                    fontSize = 18.sp, color = C.muted)
                Spacer(Modifier.height(12.dp))
                if (Store.libToken != null) PillButton("Выйти из AnimeLib") { Store.setLib(null, null, null, null) }
                else PillButton("Войти в AnimeLib", primary = true, modifier = Modifier.memo("settings", "login", initial = true)) { nav.push(LoginRoute) }
            }
        }

        item {
            Section("Синхронизация между устройствами") {
                Text(
                    if (Store.githubToken != null) "Подключено${Store.githubLogin?.let { " · GitHub $it" } ?: ""} · $status" +
                        (if (last > 0) " · ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(last))}" else "")
                    else "Нужна, если смотрите ещё на телефоне (iPhone или Android) или втором ТВ: прогресс, история и закладки в вашем приватном GitHub Gist.",
                    fontSize = 18.sp, color = C.muted,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Связать с телефоном (AnixQ)", Icons.Rounded.PhoneIphone, primary = Store.githubToken == null && Store.libToken != null,
                        modifier = Modifier.memo("settings", "pair")) { pairing = true }
                    if (Store.githubToken == null) PillButton("Войти через GitHub на ТВ") { Sync.signInOnTv(); dialog = "device" }
                    else {
                        PillButton("Синхронизировать", Icons.Rounded.Sync) { Sync.syncNow() }
                        PillButton("Выйти") { dialog = "signout" }
                    }
                }
            }
        }

        item {
            Section("Плеер") {
                Toggle("Автоматически пропускать опенинг", Store.autoSkipOpening) { Store.autoSkipOpening = it }
                Toggle("Сразу включать следующую серию", Store.autoNext) { Store.autoNext = it }
                Toggle("Режим марафона: без опенингов и эндингов, серии подряд", Store.marathon) { Store.marathon = it }
                Toggle("Без спойлеров: скрывать названия новых серий", Store.noSpoilers) { Store.noSpoilers = it }
                Toggle("Звуки навигации пультом", Store.navSounds) { Store.navSounds = it }
                Toggle("Видео-превью, когда задерживаешься на тайтле", Store.hoverPreview) { Store.hoverPreview = it }
                Toggle("Заставка через 3 минуты без нажатий", Store.screensaver) { Store.screensaver = it }
                if (version >= 0 && Store.screensaver) Toggle("Заставка с видео: отрывки серий без звука", Store.screensaverVideo) { Store.screensaverVideo = it }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Качество", fontSize = 20.sp, modifier = Modifier.width(260.dp))
                    listOf(1080, 720, 480).forEach { q -> Chip("${q}p", Store.preferredQuality == q) { Store.preferredQuality = q } }
                }
            }
        }

        item {
            Section("Профили") {
                val profiles by ru.qmods.anixq.tv.data.Profiles.list.collectAsState()
                val me by ru.qmods.anixq.tv.data.Profiles.current.collectAsState()
                Text("У каждого своя история, закладки и синхронизация. Сейчас: ${me?.name ?: ""}", fontSize = 18.sp, color = C.muted)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (profiles.size > 1) PillButton("Сменить профиль") { nav.picking = true }
                    if (profiles.size < 6) PillButton("Добавить профиль") { dialog = "addProfile" }
                    me?.let { PillButton("Переименовать") { dialog = "renameProfile" } }
                    if (me?.id != "main") me?.let { p -> PillButton("Удалить «${p.name}»") { ru.qmods.anixq.tv.data.Profiles.remove(p) } }
                }
            }
        }

        item {
            Section("Пульт с телефона") {
                val online by ru.qmods.anixq.tv.data.Remote.online.collectAsState()
                Text(when {
                    Store.githubToken == null -> "Включите синхронизацию выше — и телефон сможет включать серии на этом ТВ и управлять ими из любого места."
                    online -> "Готово: на телефоне откройте серию → «На ТВ» — она включится здесь."
                    else -> "Подключаемся… (нужна синхронизация и интернет)"
                }, fontSize = 18.sp, color = if (online) Color(0xFF5BE08F) else C.muted)
                val ctx = androidx.compose.ui.platform.LocalContext.current
                if (android.os.Build.VERSION.SDK_INT >= 29 && !android.provider.Settings.canDrawOverlays(ctx)) {
                    Spacer(Modifier.height(12.dp))
                    PillButton("Разрешить включать серии, когда AnixQ свёрнут") {
                        runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${ctx.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
            }
        }

        item { Section("Обновления") { UpdateSection() } }

        item {
            Section("Пульт") {
                listOf(
                    "OK" to "пауза · пропустить опенинг · следующая серия (когда показана карточка)",
                    "← →" to "перемотка на 10 с (удерживайте — быстрее)",
                    "↑" to "панель управления",
                    "↓" to "список серий",
                    "Назад" to "скрыть панель, потом выйти",
                    "Долгое OK на «Продолжить»" to "страница тайтла",
                ).forEach { (k, v) ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(k, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(300.dp))
                        Text(v, fontSize = 18.sp, color = C.muted)
                    }
                }
            }
        }

        item { Hint("AnixQ TV ${BuildConfig.VERSION_NAME}") }
    }

    if (pairing) PairDialog { pairing = false }
    if (dialog == "device") {
        Dialog(onDismissRequest = { dialog = null }) {
            Column(Modifier.background(C.card, RoundedCornerShape(22.dp)).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Вход через GitHub", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                val d = device
                if (d == null) {
                    Text(if (Store.githubToken != null) "Готово! Синхронизация включена." else "Получаем код…", fontSize = 20.sp, color = C.muted)
                    Spacer(Modifier.height(16.dp))
                    PillButton("Закрыть", modifier = Modifier.memo("device", "close", initial = true)) { dialog = null }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        QrCode(d.second, Modifier.size(200.dp))
                        Column {
                            Text("1. Откройте ${d.second.removePrefix("https://")}", fontSize = 19.sp)
                            Text("    (или отсканируйте QR)", fontSize = 17.sp, color = C.muted)
                            Spacer(Modifier.height(10.dp))
                            Text("2. Введите код:", fontSize = 19.sp)
                            Text(d.first, fontSize = 40.sp, fontWeight = FontWeight.Black, color = C.pink, letterSpacing = 4.sp)
                            Spacer(Modifier.height(10.dp))
                            Text("Ждём подтверждения…", fontSize = 17.sp, color = C.muted)
                        }
                    }
                }
            }
        }
    }
    if (dialog == "addProfile") NameDialog("Новый профиль", "") { n -> dialog = null; if (n != null) ru.qmods.anixq.tv.data.Profiles.switchTo(ru.qmods.anixq.tv.data.Profiles.add(n)) }
    if (dialog == "renameProfile") ru.qmods.anixq.tv.data.Profiles.current.value?.let { p ->
        NameDialog("Имя профиля", p.name) { n -> dialog = null; if (!n.isNullOrBlank()) { ru.qmods.anixq.tv.data.Profiles.rename(p, n); ru.qmods.anixq.tv.data.Profiles.current.value = p.copy(name = n) } }
    }
    if (dialog == "signout") ChoiceDialog("Отключить синхронизацию на этом ТВ?", listOf(Choice("Отключить"), Choice("Отмена")), { dialog = null }) {
        if (it == 0) Sync.signOut()
        dialog = null
    }
}

@Composable
private fun PairDialog(onClose: () -> Unit) {
    var state by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableStateOf<Pair<String, (Boolean) -> Unit>?>(null) }
    val pairing = remember { Pairing(onDone = { state = it }, onError = { error = it }, onRequest = { from, approve -> request = from to approve }) }
    DisposableEffect(pairing) { pairing.start(); onDispose { pairing.stop() } }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.background(C.card, RoundedCornerShape(22.dp)).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Подключить телефон", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            val req = request
            if (req != null && state == null) {
                Text("${req.first} просит вход с этого ТВ", fontSize = 21.sp)
                Text("Телефон получит синхронизацию (GitHub), AnimeLib и Shikimori этого телевизора", fontSize = 17.sp, color = C.muted)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PillButton("Разрешить", primary = true, modifier = Modifier.memo("pairreq", "ok", initial = true)) { request = null; req.second(true) }
                    PillButton("Отклонить", modifier = Modifier.memo("pairreq", "no")) { request = null; req.second(false); onClose() }
                }
            } else if (state != null) {
                Text("Готово — связь с $state установлена", fontSize = 21.sp)
                Text("Синхронизация включится через пару секунд", fontSize = 17.sp, color = C.muted)
                Spacer(Modifier.height(18.dp))
                PillButton("Отлично", primary = true, modifier = Modifier.memo("pairdone", "ok", initial = true)) { onClose() }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    QrCode(pairing.qrText, Modifier.size(240.dp))
                    Column(Modifier.width(320.dp)) {
                        Step("1", "Наведите камеру телефона на код (iPhone или Android с AnixQ)")
                        Step("2", "Нажмите «Открыть в AnixQ»")
                        Step("3", "Телефон передаст свои входы или попросит вход с этого ТВ")
                        Spacer(Modifier.height(8.dp))
                        Text("Данные шифруются ключом из этого QR — никто, кроме ТВ, их не прочитает.", fontSize = 16.sp, color = C.muted)
                        error?.let { Text(it, fontSize = 17.sp, color = C.pink, modifier = Modifier.padding(top = 8.dp)) }
                    }
                }
                Spacer(Modifier.height(18.dp))
                PillButton("Отмена", modifier = Modifier.memo("pair", "cancel", initial = true)) { onClose() }
            }
        }
    }
}

@Composable
private fun Step(n: String, text: String) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(n, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White,
            modifier = Modifier.background(C.pink, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 2.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 19.sp)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(18.dp)).padding(22.dp)) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    // Own state: settings live in SharedPreferences, which Compose doesn't observe — without this the
    // switch was saved but never moved on screen (and a second press saved the same value again).
    var on by remember(label) { mutableStateOf(value) }
    LaunchedEffect(value) { on = value }
    Focusable(onClick = { on = !on; onChange(on) }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), radius = 12.dp, scale = 1.01f,
        container = Color.Transparent) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 20.sp, modifier = Modifier.weight(1f))
            Row(Modifier.width(52.dp).height(30.dp).background(if (on) C.pink else Color.White.copy(alpha = 0.2f), RoundedCornerShape(50)).padding(4.dp),
                horizontalArrangement = if (on) Arrangement.End else Arrangement.Start) {
                androidx.compose.foundation.layout.Box(Modifier.size(22.dp).background(Color.White, RoundedCornerShape(50)))
            }
        }
    }
}
