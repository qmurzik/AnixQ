package ru.qmods.anixq.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import ru.qmods.anixq.tv.data.Store
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import ru.qmods.anixq.tv.data.Title

// MARK: Navigation

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Главная", Icons.Rounded.Home), CATALOG("Каталог", Icons.Rounded.GridView), SEARCH("Поиск", Icons.Rounded.Search),
    LIBRARY("Моё", Icons.Rounded.Bookmarks), SETTINGS("Настройки", Icons.Rounded.Settings)
}

sealed interface Route { val scope: String }
data class TitleRoute(val title: Title) : Route { override val scope get() = "title:${title.id}" }
/** episodeId null = first episode; [next] = the one after [episodeId]. */
data class PlayerRoute(val title: Title, val episodeId: Int?, val teamId: Int? = null, val next: Boolean = false,
                       /** "Continue": re-read the newest synced position before starting. */ val resume: Boolean = false,
                       /** Ignore the saved position (episode menu → "С начала"). */ val fromStart: Boolean = false,
                       /** Exact moment to start at (sent from the phone). */ val startAt: Double? = null) : Route {
    override val scope get() = "player"
}

data object LoginRoute : Route { override val scope get() = "login" }

class Nav {
    var tab by mutableStateOf(Tab.HOME)
    /** "Кто смотрит?" on start when there are several profiles. */
    var picking by mutableStateOf(ru.qmods.anixq.tv.data.Profiles.list.value.size > 1)
    val stack = mutableStateListOf<Route>()

    fun open(t: Title) = push(TitleRoute(t))
    fun play(t: Title, episodeId: Int?, teamId: Int? = null, next: Boolean = false, resume: Boolean = false) =
        push(PlayerRoute(t, episodeId, teamId, next, resume))

    fun push(r: Route) { Focus.leaving(); stack.add(r) }
    /** Replace the player (next episode chosen from inside it). */
    fun replaceTop(r: Route) { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex); stack.add(r) }
    /** "Смотреть на ТВ" from the phone: replace whatever is playing. */
    fun playFromPhone(m: org.json.JSONObject) {
        val t = m.optJSONObject("title")?.let { Title.fromSync(it) } ?: return
        stack.removeAll { it is PlayerRoute }
        push(PlayerRoute(t, m.optInt("episodeId").takeIf { it > 0 }, m.optInt("teamId").takeIf { it > 0 },
            resume = m.optBoolean("resume"), startAt = m.optDouble("pos", -1.0).takeIf { it >= 0 }))
    }

    fun pop() { if (stack.isNotEmpty()) { val r = stack.removeAt(stack.lastIndex); if (r is TitleRoute) Focus.forget(r.scope) } }
}

/**
 * Remembers which item had focus on every screen, so coming back from a title or the player puts the
 * cursor exactly where it was. The first item marked `initial` gets focus on a fresh screen.
 */
object Focus {
    private val last = HashMap<String, String>()
    private val pending = HashSet<String>()
    private val started = HashSet<String>()

    fun leaving() { pending.addAll(last.keys) }
    fun forget(scope: String) { last.remove(scope); pending.remove(scope); started.remove(scope) }
    fun reset(scope: String) { last.remove(scope); pending.remove(scope); started.remove(scope) }

    /** True while the cursor is in the top bar: browsing tabs must not pull it down into the screen. */
    @Volatile var barActive = false

    internal fun shouldTake(scope: String, key: String, initial: Boolean): Boolean {
        if (barActive) return false
        if (scope in pending && last[scope] == key) { pending.remove(scope); started.add(scope); return true }
        if (initial && scope !in started && last[scope] == null) { started.add(scope); return true }
        return false
    }
    internal fun focused(scope: String, key: String) { last[scope] = key; started.add(scope) }
}

fun Modifier.memo(scope: String, key: String, initial: Boolean = false): Modifier = composed {
    val fr = remember { FocusRequester() }
    // Decide once per item; only the one item that should take focus starts an effect (cheap for long rows).
    val take = remember(scope, key) { Focus.shouldTake(scope, key, initial) }
    if (take) LaunchedEffect(Unit) {
        repeat(6) { delay(50); if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect }
    }
    focusRequester(fr).onFocusChanged { if (it.isFocused) Focus.focused(scope, key) }
}

@Composable
fun App(nav: Nav) {
    val holder = rememberSaveableStateHolder()
    val top = nav.stack.lastOrNull()
    BackHandler(enabled = top != null && top !is PlayerRoute && top != LoginRoute) { nav.pop() }
    var lastKey by remember { mutableStateOf(System.currentTimeMillis()) }
    var saver by remember { mutableStateOf(false) }
    LaunchedEffect(top) {
        while (true) {
            delay(5_000)
            if (nav.stack.lastOrNull() == null && Store.screensaver && System.currentTimeMillis() - lastKey > 180_000) saver = true
        }
    }
    Box(Modifier.fillMaxSize().background(C.bg).onPreviewKeyEvent { e ->
        lastKey = System.currentTimeMillis()
        // Any key just wakes up from the screensaver.
        if (saver) { if (e.type == KeyEventType.KeyUp) saver = false; return@onPreviewKeyEvent true }
        // The remote's Menu / Settings button opens Settings from anywhere outside the player.
        if (e.type == KeyEventType.KeyDown && (e.key == Key.Menu || e.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_SETTINGS) && top !is PlayerRoute) {
            nav.stack.clear(); nav.tab = Tab.SETTINGS; true
        } else false
    }) {
        if (nav.picking && top == null) { ProfilePicker { nav.picking = false; Focus.reset("home"); nav.tab = Tab.HOME }; return@Box }
        when (top) {
            null -> {
                BackHandler(enabled = nav.tab != Tab.HOME) { nav.tab = Tab.HOME }
                // Each tab draws its own backdrop and leaves room for the bar on top.
                // One focus group for the tab's content: "down" from the bar always lands in it
                // (geometric search from the far-right "Настройки" tab found nothing on some boxes).
                val content = remember { FocusRequester() }
                Box(Modifier.fillMaxSize().focusRequester(content).focusGroup()) {
                    holder.SaveableStateProvider(nav.tab.name) {
                        when (nav.tab) {
                            Tab.HOME -> HomeScreen(nav)
                            Tab.CATALOG -> CatalogScreen(nav)
                            Tab.SEARCH -> SearchScreen(nav)
                            Tab.LIBRARY -> LibraryScreen(nav)
                            Tab.SETTINGS -> SettingsScreen(nav)
                        }
                    }
                }
                TopBar(nav, content)
            }
            is TitleRoute -> holder.SaveableStateProvider(top.scope + nav.stack.size) { TitleScreen(nav, top.title) }
            is PlayerRoute -> PlayerScreen(nav, top)
            LoginRoute -> LoginScreen(nav)
        }
        androidx.compose.animation.AnimatedVisibility(saver, enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(1200)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(300))) { Screensaver() }
    }
}

/** Slow slideshow of the catalogue with a big clock — nicer than the TV going grey. */
@Composable
internal fun Screensaver() {
    val titles = remember { HomeData.rows.value.flatMap { it.second }.distinctBy { it.id }.shuffled().take(30) }
    var i by remember { mutableStateOf(0) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(9_000); i += 1; now = System.currentTimeMillis() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val t = titles.getOrNull(if (titles.isEmpty()) 0 else i % titles.size)
        androidx.compose.animation.Crossfade(t, animationSpec = androidx.compose.animation.core.tween(1500), label = "saver") { cur ->
            if (cur != null) Box(Modifier.fillMaxSize()) {
                Ambient(cur.thumb, Modifier.fillMaxSize(), alpha = 0.55f)
                Cover(cur.poster, Modifier.align(Alignment.CenterEnd).padding(end = 110.dp).height(440.dp).aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(16.dp)))
                Column(Modifier.align(Alignment.BottomStart).padding(start = 72.dp, bottom = 70.dp).width(620.dp)) {
                    Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now)),
                        fontSize = 84.sp, fontWeight = FontWeight.Light, color = Color.White)
                    Text(java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale("ru")).format(java.util.Date(now)),
                        fontSize = 20.sp, color = Color.White.copy(alpha = 0.75f))
                    Spacer(Modifier.height(28.dp))
                    Text(cur.title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2)
                    Text(cur.meta, fontSize = 16.sp, color = Color.White.copy(alpha = 0.7f))
                }
            }
        }
    }
}

val TopBarHeight = 64.dp

/** Google-TV-like tabs on top; moving across them switches the screen. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TopBar(nav: Nav, content: FocusRequester) {
    val update by ru.qmods.anixq.tv.data.Updater.state.collectAsState()
    val requesters = remember { Tab.entries.associateWith { FocusRequester() } }
    // Coming up from the content always lands on the current tab (never jumps to Settings by accident).
    Row(Modifier.fillMaxWidth().height(TopBarHeight).background(Brush.verticalGradient(listOf(C.bg.copy(alpha = 0.9f), Color.Transparent)))
        .padding(horizontal = 48.dp).focusProperties { enter = { requesters.getValue(nav.tab) } }
        .onFocusChanged { Focus.barActive = it.hasFocus }
        .onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                Focus.barActive = false
                runCatching { content.requestFocus() }.isSuccess
            } else false
        }
        .focusGroup(),
        verticalAlignment = Alignment.CenterVertically) {
        Text("AnixQ", fontSize = 21.sp, fontWeight = FontWeight.Black, color = C.pink, modifier = Modifier.padding(end = 28.dp))
        Tab.entries.filter { it != Tab.SETTINGS }.forEach { t -> TabItem(t, nav, Modifier.focusRequester(requesters.getValue(t))) }
        Spacer(Modifier.weight(1f))
        if (ru.qmods.anixq.tv.data.Updater.available != null && update !is ru.qmods.anixq.tv.data.UpdateState.Installing)
            Text("Есть обновление", fontSize = 13.sp, color = C.pink, modifier = Modifier.padding(end = 14.dp))
        Clock()
        Spacer(Modifier.width(10.dp))
        val profiles by ru.qmods.anixq.tv.data.Profiles.list.collectAsState()
        val me by ru.qmods.anixq.tv.data.Profiles.current.collectAsState()
        if (profiles.size > 1) me?.let { p ->
            Surface(onClick = { nav.picking = true }, modifier = Modifier.padding(horizontal = 4.dp).tick(),
                shape = ClickableSurfaceDefaults.shape(CircleShape),
                colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.White),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f)) { Box(Modifier.padding(3.dp)) { Avatar(p, 30.dp) } }
        }
        Spacer(Modifier.width(4.dp))
        TabItem(Tab.SETTINGS, nav, Modifier.focusRequester(requesters.getValue(Tab.SETTINGS)))
    }
}

@Composable
private fun TabItem(t: Tab, nav: Nav, modifier: Modifier, iconOnly: Boolean = false) {
    val selected = nav.tab == t
    Surface(
        onClick = { nav.tab = t },
        modifier = modifier.padding(horizontal = 2.dp).tick().onFocusChanged { if (it.isFocused && nav.tab != t) nav.tab = t },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent,
            contentColor = if (selected) Color.White else C.muted,
            focusedContainerColor = Color.White, focusedContentColor = Color.Black),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Row(Modifier.padding(horizontal = if (iconOnly) 9.dp else 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (iconOnly) Icon(t.icon, contentDescription = t.label, modifier = Modifier.size(20.dp))
            else Text(t.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Clock() {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now)), fontSize = 16.sp, color = C.muted)
}

// MARK: Shared dialogs

data class Choice(val label: String, val selected: Boolean = false, val hint: String? = null)

@Composable
fun ChoiceDialog(title: String, choices: List<Choice>, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(min = 360.dp, max = 520.dp).background(C.card, RoundedCornerShape(20.dp)).padding(20.dp)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 14.dp))
            val initial = choices.indexOfFirst { it.selected }.coerceAtLeast(0)
            LazyColumn(Modifier.height((choices.size.coerceAtMost(7) * 58).dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(choices) { i, c ->
                    val fr = remember { FocusRequester() }
                    if (i == initial) LaunchedEffect(Unit) { delay(60); runCatching { fr.requestFocus() } }
                    Focusable(onClick = { onPick(i) }, modifier = Modifier.fillMaxWidth().focusRequester(fr), radius = 12.dp, scale = 1.03f,
                        container = Color.Transparent) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.label, fontSize = 17.sp, fontWeight = if (c.selected) FontWeight.Bold else FontWeight.Normal)
                                c.hint?.let { Text(it, fontSize = 13.sp, color = C.muted) }
                            }
                            if (c.selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = C.pink)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
    }
    Canvas(modifier.background(Color.White, RoundedCornerShape(12.dp)).padding(10.dp)) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

@Composable
fun ScreenTitle(text: String, sub: String? = null) {
    Column(Modifier.padding(top = TopBarHeight + 8.dp, bottom = 14.dp)) {
        Text(text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        sub?.let { Spacer(Modifier.height(4.dp)); Text(it, fontSize = 15.sp, color = C.muted) }
    }
}

@Composable
fun Hint(text: String) = Text(text, fontSize = 13.sp, color = C.muted)

