package ru.qmods.anixq.tv.ui.neon

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import ru.qmods.anixq.tv.data.Profiles
import ru.qmods.anixq.tv.data.Updater
import ru.qmods.anixq.tv.ui.*

/** Neon city ambience shared by every screen: what's in focus tints the sky. */
internal object NeonSky { var tint by mutableStateOf(N.pink) }

/**
 * The street: content on the left, the signs of the sections on the right edge — vertical neon boards with a kanji
 * and the Russian name. Right from the content lights the signs; Back goes to the signs, then Home.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun NeonShell(nav: Nav, holder: androidx.compose.runtime.saveable.SaveableStateHolder) {
    val content = remember { FocusRequester() }
    val signs = remember { Tab.entries.associateWith { FocusRequester() } }
    var onSigns by remember { mutableStateOf(false) }
    val last = remember { arrayOf(nav.tab) }
    val fromRight = remember(nav.tab) { (nav.tab.ordinal >= last[0].ordinal).also { last[0] = nav.tab } }
    LaunchedEffect(nav.tab) { NeonSky.tint = nav.tab.neon() }
    DisposableEffect(Unit) { onDispose { Focus.barActive = false } }
    BackHandler(enabled = !onSigns || nav.tab != Tab.HOME) {
        if (!onSigns) runCatching { signs.getValue(nav.tab).requestFocus() }
        else { nav.tab = Tab.HOME; runCatching { signs.getValue(Tab.HOME).requestFocus() } }
    }
    Box(Modifier.fillMaxSize().background(N.night).testTag("neon-shell")) {
        NightCity(NeonSky.tint)
        Box(Modifier.fillMaxSize().padding(end = 96.dp).streetPan(nav.tab, fromRight).focusRequester(content)
            .focusProperties { exit = { if (it == FocusDirection.Right) signs.getValue(nav.tab) else FocusRequester.Default } }.focusRestorer().focusGroup()) {
            holder.SaveableStateProvider("neon:${nav.tab}") {
                when (nav.tab) {
                    Tab.HOME -> NeonHome(nav)
                    Tab.CATALOG -> NeonCatalog(nav)
                    Tab.SEARCH -> NeonSearch(nav)
                    Tab.LIBRARY -> NeonLibrary(nav)
                    Tab.SCHEDULE -> NeonSchedule(nav)
                    Tab.SETTINGS -> Box(Modifier.fillMaxSize().background(N.night.copy(alpha = .82f))) {
                        CompositionLocalProvider(LocalTopInset provides 24.dp) { SettingsScreen(nav) }
                    }
                }
            }
        }
        // The signs on the building.
        Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(96.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, N.night.copy(alpha = .9f))))
            .padding(vertical = 18.dp, horizontal = 12.dp)
            .onFocusChanged { onSigns = it.hasFocus; Focus.barActive = it.hasFocus }
            .focusProperties { enter = { signs.getValue(nav.tab) } }
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft) { Focus.barActive = false; runCatching { content.requestFocus() }.isSuccess } else false
            }.focusGroup(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Glow("AQ", N.pink, 20.sp, Modifier.padding(bottom = 6.dp), weight = FontWeight.Black)
            Tab.entries.filter { it != Tab.SETTINGS }.forEach { tab ->
                Sign(tab, nav.tab == tab, onSigns, Modifier.weight(1f).focusRequester(signs.getValue(tab)).testTag("neon-nav-${tab.name}")) { nav.tab = tab }
            }
            val profiles by Profiles.list.collectAsState()
            if (profiles.size > 1) NSurface({ nav.picking = true }, Modifier.size(48.dp).testTag("neon-profile"), N.violet, radius = 24.dp) {
                val current by Profiles.current.collectAsState()
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Glow(current?.name?.take(1)?.uppercase() ?: "Я", N.violet, 16.sp) }
            }
            Sign(Tab.SETTINGS, nav.tab == Tab.SETTINGS, onSigns, Modifier.height(64.dp).focusRequester(signs.getValue(Tab.SETTINGS)).testTag("neon-nav-SETTINGS")) { nav.tab = Tab.SETTINGS }
            if (Updater.available != null) Glow("NEW", N.amber, 10.sp)
        }
    }
}

/** A vertical neon board: kanji on top, the section name written downwards. Lit = current or in focus. */
@Composable
private fun Sign(tab: Tab, current: Boolean, signsActive: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color = tab.neon()
    var focused by remember { mutableStateOf(false) }
    val lit by animateFloatAsState(if (current) 1f else if (signsActive) .45f else .28f, tween(neonMillis(300)), label = "sign")
    NSurface(onClick, modifier.fillMaxWidth(), color, selected = current, onFocus = { focused = it; if (it) onClick() }, radius = 8.dp) {
        Column(Modifier.fillMaxSize().padding(vertical = 8.dp).graphicsLayer { alpha = maxOf(lit, if (focused) 1f else 0f) },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Glow(tab.kanji, color, 26.sp, glow = if (current || focused) 1f else .3f)
            if (tab != Tab.SETTINGS) Column(Modifier.padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                tab.label.uppercase().take(6).forEach { ch -> Text(ch.toString(), fontSize = 8.sp, lineHeight = 9.sp, color = color.copy(alpha = .85f), fontWeight = FontWeight.Bold) }
            }
        }
    }
}
