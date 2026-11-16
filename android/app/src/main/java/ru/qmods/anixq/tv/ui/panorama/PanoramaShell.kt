package ru.qmods.anixq.tv.ui.panorama

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import ru.qmods.anixq.tv.data.Profiles
import ru.qmods.anixq.tv.data.Updater
import ru.qmods.anixq.tv.ui.*

/** Alternate chrome owns focus only while visible. Player and remote navigation keep the same Nav. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PanoramaShell(nav: Nav, holder: androidx.compose.runtime.saveable.SaveableStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()) {
    val content = remember { FocusRequester() }
    val rail = remember { Tab.entries.associateWith { FocusRequester() } }
    var inRail by remember { mutableStateOf(false) }
    val railWidth by animateDpAsState(if (inRail) 216.dp else 84.dp, tween(motionMillis(220)), label = "navigation-width")
    DisposableEffect(Unit) { onDispose { Focus.barActive = false } }
    BackHandler(enabled = !inRail || nav.tab != Tab.HOME) {
        if (!inRail) runCatching { rail.getValue(nav.tab).requestFocus() }
        else { nav.tab = Tab.HOME; runCatching { rail.getValue(Tab.HOME).requestFocus() } }
    }
    Box(Modifier.fillMaxSize().background(P.bg).testTag("panorama-shell")) {
        Box(Modifier.fillMaxSize().padding(start = 84.dp).cinemaEntrance(nav.tab).focusRequester(content)
            .focusProperties { exit = { if (it == FocusDirection.Left) rail.getValue(nav.tab) else FocusRequester.Default } }.focusRestorer().focusGroup()) {
            holder.SaveableStateProvider("panorama:${nav.tab}") {
                when (nav.tab) {
                    Tab.HOME -> PanoramaHome(nav)
                    Tab.CATALOG -> PanoramaCatalog(nav)
                    Tab.SEARCH -> PanoramaSearch(nav)
                    Tab.LIBRARY -> PanoramaLibrary(nav)
                    Tab.SCHEDULE -> PanoramaSchedule(nav)
                    Tab.SETTINGS -> CompositionLocalProvider(LocalTopInset provides 24.dp) { SettingsScreen(nav) }
                }
            }
        }
        // Expanded navigation overlays the canvas instead of remeasuring every poster on every focus change.
        AnimatedVisibility(inRail, enter = fadeIn(tween(motionMillis(180))), exit = fadeOut(tween(motionMillis(140)))) {
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to P.bg.copy(alpha = .8f), .4f to Color.Transparent)))
        }
        Column(Modifier.width(railWidth).fillMaxHeight().background(P.bg.copy(alpha = .98f))
            .padding(horizontal = 14.dp, vertical = 24.dp)
            .onFocusChanged { inRail = it.hasFocus; Focus.barActive = it.hasFocus }
            .focusProperties { enter = { rail.getValue(nav.tab) } }
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight) {
                    Focus.barActive = false
                    runCatching { content.requestFocus() }.isSuccess
                } else false
            }.focusGroup(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.height(54.dp).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("A", color = P.accent, fontWeight = FontWeight.Black, fontSize = 29.sp)
                if (inRail) Text("nixQ", color = P.ink, fontWeight = FontWeight.SemiBold, fontSize = 24.sp)
            }
            Spacer(Modifier.height(17.dp))
            Tab.entries.filter { it != Tab.SETTINGS }.forEach { tab ->
                RailItem(tab, nav.tab == tab, inRail, Modifier.focusRequester(rail.getValue(tab)).testTag("panorama-nav-${tab.name}")) { nav.tab = tab }
            }
            Spacer(Modifier.weight(1f))
            val profiles by Profiles.list.collectAsState()
            if (profiles.size > 1) PSurface({ nav.picking = true }, Modifier.fillMaxWidth().testTag("panorama-profile")) {
                Row(Modifier.height(43.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    val current by Profiles.current.collectAsState()
                    Text(current?.name?.take(1)?.uppercase() ?: "Я", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    if (inRail) Text(current?.name ?: "Профили", Modifier.padding(start = 14.dp), fontSize = 13.sp, maxLines = 1)
                }
            }
            RailItem(Tab.SETTINGS, nav.tab == Tab.SETTINGS, inRail,
                Modifier.focusRequester(rail.getValue(Tab.SETTINGS)).testTag("panorama-nav-SETTINGS")) { nav.tab = Tab.SETTINGS }
            if (inRail && Updater.available != null) Text("Доступно обновление", color = P.accent, fontSize = 10.sp, modifier = Modifier.padding(10.dp))
            Text(if (inRail) "ANIXQ · ПАНОРАМА" else "TV", color = P.muted, letterSpacing = 1.sp, fontSize = 8.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp))
        }
    }
}

@Composable
private fun RailItem(tab: Tab, selected: Boolean, expanded: Boolean, modifier: Modifier, onClick: () -> Unit) {
    PSurface(onClick, modifier.fillMaxWidth(), selected = selected, radius = 10.dp) {
        Row(Modifier.height(44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(tab.icon, tab.label, Modifier.size(20.dp))
            if (expanded) Text(tab.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
internal fun PClock() {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now)), color = P.ink,
        fontSize = 13.sp, letterSpacing = 1.sp)
}
