package ru.qmods.anixq.mobile

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import coil.Coil
import kotlinx.coroutines.launch
import ru.qmods.anixq.mobile.ui.AnixTheme
import ru.qmods.anixq.mobile.ui.HomeScreen
import ru.qmods.anixq.mobile.ui.LibLoginRoute
import ru.qmods.anixq.mobile.ui.LibLoginScreen
import ru.qmods.anixq.mobile.ui.LibraryScreen
import ru.qmods.anixq.mobile.ui.MoreScreen
import ru.qmods.anixq.mobile.ui.Nav
import ru.qmods.anixq.mobile.ui.PairRequest
import ru.qmods.anixq.mobile.ui.Pal
import ru.qmods.anixq.mobile.ui.Pip
import ru.qmods.anixq.mobile.ui.PlayerRoute
import ru.qmods.anixq.mobile.ui.PlayerScreen
import ru.qmods.anixq.mobile.ui.RemoteRoute
import ru.qmods.anixq.mobile.ui.RemoteScreen
import ru.qmods.anixq.mobile.ui.SearchScreen
import ru.qmods.anixq.mobile.ui.SyncLoginScreen
import ru.qmods.anixq.mobile.ui.SyncRoute
import ru.qmods.anixq.mobile.ui.Tab
import ru.qmods.anixq.mobile.ui.TitleRoute
import ru.qmods.anixq.mobile.ui.TitleScreen
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.Images
import ru.qmods.anixq.tv.data.Remote
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Sync
import ru.qmods.anixq.tv.data.Title
import ru.qmods.anixq.tv.data.Updater

class MainActivity : ComponentActivity() {
    private var nav: Nav? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Phone identity on the shared channel (before the library loads).
        Store.devicePrefix = "and"
        Store.deviceName = "Android"
        Remote.isTv = false
        Updater.versionCode = BuildConfig.VERSION_CODE
        Updater.channel = "android"
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        Store.init(applicationContext)
        Coil.setImageLoader(Images.loader(applicationContext))
        if (Store.libToken != null) lifecycleScope.launch { Api.loadProfile() }
        setContent {
            val n = remember { Nav().also { nav = it; handle(intent, it) } }
            AnixTheme { Root(n) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        nav?.let { handle(intent, it) }
    }

    /** anixq://pair/… (QR on the TV) and anixq://title/<slug>. */
    private fun handle(i: Intent?, nav: Nav) {
        val u = i?.data ?: return
        when (u.host) {
            "pair" -> { nav.stack.clear(); nav.tab = Tab.MORE; PairRequest.pending = u.toString() }
            "title" -> u.pathSegments.firstOrNull()?.let { slug ->
                nav.open(Title(id = slug.substringBefore("--").toIntOrNull() ?: 0, slug = slug))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Sync.start()
        Sync.syncNow()
        Remote.start(applicationContext)
        Updater.checkSometimes()
    }

    override fun onStop() {
        super.onStop()
        Sync.flush()
        // The live channel isn't needed in the background; the latest state is retained and arrives on return.
        if (!Pip.inPip) Remote.stop()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val m = Pip.model
        if (m != null && m.playing && m.native && Build.VERSION.SDK_INT >= 26) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Pip.inPip = isInPictureInPictureMode
    }
}

@Composable
private fun Root(nav: Nav) {
    val holder = rememberSaveableStateHolder()
    val top = nav.top
    BackHandler(enabled = top != null || nav.tab != Tab.HOME) { if (top != null) nav.pop() else nav.tab = Tab.HOME }
    Box(Modifier.fillMaxSize().background(Pal.bg)) {
        when (top) {
            is PlayerRoute -> PlayerScreen(nav, top)
            is TitleRoute -> holder.SaveableStateProvider("title-${top.title.id}-${nav.stack.size}") { TitleScreen(nav, top.title) }
            RemoteRoute -> RemoteScreen(nav)
            SyncRoute -> SyncLoginScreen(nav)
            LibLoginRoute -> LibLoginScreen(nav)
            null -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    holder.SaveableStateProvider(nav.tab.name) {
                        when (nav.tab) {
                            Tab.HOME -> HomeScreen(nav)
                            Tab.SEARCH -> SearchScreen(nav)
                            Tab.LIBRARY -> LibraryScreen(nav)
                            Tab.MORE -> MoreScreen(nav)
                        }
                    }
                }
                NavigationBar(containerColor = Pal.card) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = nav.tab == t, onClick = { nav.tab = t },
                            icon = { Icon(when (t) { Tab.HOME -> Icons.Default.Home; Tab.SEARCH -> Icons.Default.Search; Tab.LIBRARY -> Icons.Default.VideoLibrary; Tab.MORE -> Icons.Default.Menu }, null) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Pal.pink, selectedTextColor = Pal.pink, indicatorColor = Pal.pink.copy(alpha = 0.15f)),
                        )
                    }
                }
            }
        }
    }
}
