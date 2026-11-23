package ru.qmods.anixq.mobile.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon as AIcon
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.qmods.anixq.mobile.data.PlaybackHistory
import ru.qmods.anixq.mobile.data.ConnectionCheck
import ru.qmods.anixq.mobile.data.ConnectionResult
import ru.qmods.anixq.mobile.BuildConfig
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.Crashes
import ru.qmods.anixq.tv.data.Schedule
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Sync

// MARK: - Diagnostics: is each server reachable from this network?

@Composable
fun DiagnosticsScreen(nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val probes = ConnectionCheck.probes
    val results = remember { mutableStateListOf<ConnectionResult?>().apply { repeat(probes.size) { add(null) } } }
    var running by remember { mutableStateOf(false) }
    var network by remember { mutableStateOf("") }
    fun run() {
        if (running) return
        running = true
        network = connectionType(context)
        for (i in probes.indices) results[i] = null
        scope.launch {
            try {
                val client = ConnectionCheck.client()
                coroutineScope {
                    probes.forEachIndexed { index, probe ->
                        launch { results[index] = ConnectionCheck.probe(client, probe) }
                    }
                }
            } finally {
                running = false
            }
        }
    }

    fun copy() {
        val report = buildString {
            appendLine("AnixQ ${BuildConfig.VERSION_NAME} · $network")
            probes.forEachIndexed { index, probe ->
                appendLine("${probe.name} (${probe.host}) — ${results[index]?.display ?: "нет результата"}")
            }
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Проверка связи AnixQ", report))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { run() }
    Column(Modifier.fillMaxSize().background(Pal.bg).statusBarsPadding()) {
        TopBar("Проверка связи", { nav.pop() }) { TextButton({ run() }, enabled = !running) { Text("Ещё раз") } }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Group(network, "Запросы идут без входа в аккаунт. Строки видеосерверов показывают HTTP-ответ хоста, проверка серии не выполняется.") {
                GroupRow("Скопировать отчёт", icon = Icons.Default.NetworkCheck, iconColor = Pal.blue, onClick = if (running || results.none { it != null }) null else ::copy)
                probes.forEachIndexed { i, p ->
                    val r = results[i]
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(when (r?.success) { true -> Pal.green; false -> Pal.red; null -> Pal.faint }))
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold)
                            Text(p.description, fontSize = 12.sp, color = Pal.muted)
                        }
                        if (r == null) CircularProgressIndicator(Modifier.size(16.dp), color = Pal.pink, strokeWidth = 2.dp)
                        else Text(r.display, fontSize = 12.sp, color = if (r.success) Pal.muted else Pal.red, textAlign = TextAlign.End, modifier = Modifier.width(140.dp))
                    }
                }
            }
            Group("Приложение") {
                Text(deviceInfo(LocalContext.current), fontSize = 13.sp, modifier = Modifier.padding(14.dp))
            }
            Crashes.pending()?.let { c ->
                Group("Последний сбой") { SelectionContainer { Text(c.takeLast(2500), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(12.dp)) } }
            }
            Group { GroupRow("Сообщить о проблеме", icon = Icons.Default.BugReport, iconColor = Pal.red, chevron = true, divider = false) { nav.push(ReportRoute) } }
        }
    }
}

private fun connectionType(context: Context): String {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "Нет активной сети"
    val transport = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная сеть"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Активная сеть"
    }
    return if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) "$transport · VPN" else transport
}

fun deviceInfo(c: Context): String = listOf(
    "Версия AnixQ: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
    "Устройство: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
    "Тема: ${Looks.mode.title}, AnimeLib: ${if (Store.libToken != null) "вход" else "нет"}, Shikimori: ${if (Store.shikiJson != null) "вход" else "нет"}, синхронизация: ${if (Sync.isOn) "вкл" else "выкл"}",
).joinToString("\n") { "- $it" }

/** This app's own recent log lines (Android lets an app read its own log). */
private suspend fun ownLog(): String = withContext(Dispatchers.IO) {
    runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "150", "--pid=${android.os.Process.myPid()}", "*:I"))
        p.inputStream.bufferedReader().readText().lines().filter { "AnixQ" in it || " E " in it }.takeLast(80).joinToString("\n")
    }.getOrDefault("")
}

// MARK: - Report a problem (GitHub issue form, everything filled in)

@Composable
fun ReportScreen(nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var withLog by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().background(Pal.bg).statusBarsPadding()) {
        TopBar("Сообщить о проблеме", { nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Pal.pink, cursorColor = Pal.pink)
            OutlinedTextField(title, { title = it }, label = { Text("Коротко: что не так") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = colors)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(text, { text = it }, label = { Text("Подробнее: что делали, что ожидали, что получилось") }, minLines = 5, modifier = Modifier.fillMaxWidth(), colors = colors)
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Приложить журнал и последний сбой", modifier = Modifier.weight(1f))
                Switch(withLog, { withLog = it }, colors = SwitchDefaults.colors(checkedTrackColor = Pal.green, checkedThumbColor = Color.White))
            }
            Text("Откроется страница GitHub с заполненным отчётом — останется нажать «Submit». Личные данные в журнал не попадают.", fontSize = 12.sp, color = Pal.muted, modifier = Modifier.padding(top = 8.dp))
            PrimaryButton(Modifier.fillMaxWidth().padding(top = 16.dp), onClick = {
                scope.launch {
                    val parts = mutableListOf("## Что случилось", text.ifBlank { "_(опишите проблему)_" }, "## Устройство", deviceInfo(context))
                    if (withLog) {
                        Crashes.pending()?.let { parts += listOf("## Последний сбой", "```", it.takeLast(2500), "```") }
                        ownLog().takeIf { it.isNotBlank() }?.let { parts += listOf("## Журнал", "```", it, "```") }
                    }
                    var body = parts.joinToString("\n\n")
                    if (body.length > 6000) body = body.take(6000) + "\n…\n```"
                    val url = Uri.parse("https://github.com/qmurzik/AnixQ/issues/new").buildUpon()
                        .appendQueryParameter("title", title.ifBlank { "Проблема в AnixQ (Android)" }).appendQueryParameter("body", body).build()
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                }
            }) { Text("Открыть отчёт", color = Color.White, fontWeight = FontWeight.SemiBold) }
        }
    }
}

// MARK: - New episodes in the phone's calendar

object CalendarSync {
    private const val PREFS = "calendar"
    fun enabled(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", false)
    fun hasPermission(c: Context) = c.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
        if (!on) clear(c)
    }

    private fun calendarId(c: Context): Long? = runCatching {
        c.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}", null, null)?.use { cur ->
            var first: Long? = null
            while (cur.moveToNext()) { val id = cur.getLong(0); if (first == null) first = id; if (cur.getInt(1) == 1) return@use id }
            first
        }
    }.getOrNull()

    private fun clear(c: Context) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!hasPermission(c)) return
        p.getStringSet("events", emptySet())!!.forEach { id ->
            runCatching { c.contentResolver.delete(android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id.toLong()), null, null) }
        }
        p.edit().putStringSet("events", emptySet()).apply()
    }

    /** Upcoming episodes of what you watch (next 2 weeks) as calendar events, replaced on each run. Returns how many. */
    suspend fun sync(c: Context): Int {
        if (!enabled(c) || !hasPermission(c)) return 0
        val cal = calendarId(c) ?: return 0
        val items = Schedule.load().filter { Schedule.isMine(it.shikiId) && it.at * 1000 < System.currentTimeMillis() + 14 * 86_400_000L }
        return withContext(Dispatchers.IO) {
            clear(c)
            val ids = HashSet<String>()
            items.forEach { i ->
                val start = i.at * 1000
                val v = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, cal)
                    put(CalendarContract.Events.TITLE, "${i.title} — серия ${i.episode}")
                    put(CalendarContract.Events.DESCRIPTION, "Новая серия · AnixQ")
                    put(CalendarContract.Events.DTSTART, start); put(CalendarContract.Events.DTEND, start + 24 * 60_000)
                    put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                }
                runCatching { c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)?.lastPathSegment }.getOrNull()?.let { ids += it }
            }
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("events", ids).apply()
            ids.size
        }
    }
}

@Composable
fun CalendarRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(CalendarSync.enabled(context) && CalendarSync.hasPermission(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) {
            on = true; CalendarSync.setEnabled(context, true)
            scope.launch { val n = CalendarSync.sync(context); Toast.makeText(context, "В календаре: $n ${plural(n, "серия", "серии", "серий")}", Toast.LENGTH_SHORT).show() }
        }
    }
    GroupRow("Новые серии в календаре", "Ближайшие серии того, что вы смотрите", androidx.compose.material.icons.Icons.Default.CloudSync, Pal.red, onClick = null, trailing = {
        Switch(on, { v ->
            if (!v) { on = false; CalendarSync.setEnabled(context, false) }
            else if (CalendarSync.hasPermission(context)) {
                on = true; CalendarSync.setEnabled(context, true)
                scope.launch { val n = CalendarSync.sync(context); Toast.makeText(context, "В календаре: $n ${plural(n, "серия", "серии", "серий")}", Toast.LENGTH_SHORT).show() }
            } else ask.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
        }, colors = SwitchDefaults.colors(checkedTrackColor = Pal.green, checkedThumbColor = Color.White,
            uncheckedTrackColor = Pal.cardHi, uncheckedBorderColor = Pal.cardHi, uncheckedThumbColor = Color.White))
    })
}

// MARK: - Launcher shortcuts (long press on the icon)

object Shortcuts {
    fun publish(c: Context) {
        if (Build.VERSION.SDK_INT < 25) return
        val sm = c.getSystemService(ShortcutManager::class.java) ?: return
        fun s(id: String, label: String, uri: String, icon: Int) = ShortcutInfo.Builder(c, id).setShortLabel(label)
            .setIcon(AIcon.createWithResource(c, icon))
            .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse(uri), c, ru.qmods.anixq.mobile.MainActivity::class.java)).build()
        val last = PlaybackHistory.continueEntries().firstOrNull()
        val list = listOfNotNull(
            last?.let { s("continue", "Продолжить: ${it.title.title}".take(25), "anixq://continue", android.R.drawable.ic_media_play) },
            s("search", "Поиск", "anixq://search", android.R.drawable.ic_menu_search),
            if (Store.remoteChannel.isNotEmpty()) s("remote", "Пульт ТВ", "anixq://remote", android.R.drawable.ic_menu_slideshow) else null,
            s("schedule", "Расписание", "anixq://schedule", android.R.drawable.ic_menu_my_calendar),
        )
        runCatching { sm.dynamicShortcuts = list.take(sm.maxShortcutCountPerActivity) }
    }
}

// MARK: - First launch

@Composable
fun Onboarding(nav: Nav, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Pal.bg).statusBarsPadding().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("AnixQ", fontSize = 44.sp, fontWeight = FontWeight.Black, color = Pal.pink)
        Text("Аниме с AnimeLib — без рекламы, с синхронизацией между телефоном, телевизором и iPhone.", fontSize = 17.sp, color = Pal.muted, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(28.dp))
        listOf("▶  Плеер без рекламы, пропуск опенингов, загрузки офлайн", "📺  Пульт для ТВ и «Смотреть вместе» по ссылке",
            "⭐  Shikimori: отметки, оценки, друзья, рекомендации").forEach { Text(it, fontSize = 15.sp, modifier = Modifier.padding(vertical = 6.dp)) }
        Spacer(Modifier.height(28.dp))
        PrimaryButton(Modifier.fillMaxWidth(), onClick = { onDone(); nav.push(SyncRoute) }) {
            Icon(Icons.Default.CloudSync, null, tint = Color.White); Text("  Синхронизировать устройства", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        TextButton({ onDone(); nav.push(LibLoginRoute) }, Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Icon(Icons.Default.PersonAdd, null, tint = Pal.pink); Text("  Войти в AnimeLib", color = Pal.pink)
        }
        TextButton(onDone, Modifier.fillMaxWidth()) { Text("Начать без входа", color = Pal.muted) }
    }
}
