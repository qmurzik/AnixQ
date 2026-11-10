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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import coil.Coil
import kotlinx.coroutines.launch
import ru.qmods.anixq.mobile.ui.AnixTheme
import ru.qmods.anixq.mobile.ui.CatalogListScreen
import ru.qmods.anixq.mobile.ui.CatalogRoute
import ru.qmods.anixq.mobile.ui.CatalogScreen
import ru.qmods.anixq.mobile.ui.FolderRoute
import ru.qmods.anixq.mobile.ui.FolderScreen
import ru.qmods.anixq.mobile.ui.HistoryRoute
import ru.qmods.anixq.mobile.ui.HistoryScreen
import ru.qmods.anixq.mobile.ui.HomeScreen
import ru.qmods.anixq.mobile.ui.LibLoginRoute
import ru.qmods.anixq.mobile.ui.LibLoginScreen
import ru.qmods.anixq.mobile.ui.LibraryScreen
import ru.qmods.anixq.mobile.ui.Nav
import ru.qmods.anixq.mobile.ui.PairFlow
import ru.qmods.anixq.mobile.ui.Pal
import ru.qmods.anixq.mobile.ui.Pip
import ru.qmods.anixq.mobile.ui.PlayerRoute
import ru.qmods.anixq.mobile.ui.PlayerScreen
import ru.qmods.anixq.mobile.ui.RemoteRoute
import ru.qmods.anixq.mobile.ui.RemoteScreen
import ru.qmods.anixq.mobile.ui.SearchScreen
import ru.qmods.anixq.mobile.ui.SettingsRoute
import ru.qmods.anixq.mobile.ui.SettingsScreen
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
        Api.diskDir = cacheDir
        ru.qmods.anixq.tv.data.Crashes.install(applicationContext, "AnixQ Android", BuildConfig.VERSION_NAME)
        ru.qmods.anixq.mobile.data.Downloads.init(applicationContext)
        NewEpisodes.schedule(applicationContext)
        ru.qmods.anixq.mobile.data.Collections.init(applicationContext)
        ru.qmods.anixq.tv.data.Room.myName = "Android (${Build.MODEL})"
        // Android 13+: ask once for notifications (new episodes).
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val p = getSharedPreferences("app", MODE_PRIVATE)
            if (!p.getBoolean("askedNotifications", false)) {
                p.edit().putBoolean("askedNotifications", true).apply()
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        Coil.setImageLoader(Images.loader(applicationContext))
        if (Store.libToken != null) lifecycleScope.launch { Api.loadProfile() }
        setContent {
            val n = remember { Nav().also { nav = it; handle(intent, it) } }
            androidx.compose.runtime.DisposableEffect(n) {
                // Joined a friend's room: the first state says what they're watching — open it.
                ru.qmods.anixq.tv.data.Room.onStateWithoutPlayer = { m ->
                    val slug = m.optString("slug")
                    if (slug.isNotEmpty()) n.play(PlayerRoute(Title(id = slug.substringBefore("--").toIntOrNull() ?: 0, slug = slug,
                        rusName = m.optString("titleName").ifEmpty { null }), m.optInt("episodeId").takeIf { it > 0 },
                        m.optInt("teamId").takeIf { it > 0 }, startAt = m.optDouble("position", 0.0)))
                }
                onDispose { ru.qmods.anixq.tv.data.Room.onStateWithoutPlayer = null }
            }
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
            "pair" -> { nav.stack.removeAll { it is PlayerRoute }; nav.push(SettingsRoute); PairFlow.run(u.toString(), lifecycleScope) }
            "title" -> u.pathSegments.firstOrNull()?.let { slug -> nav.open(Title(id = slug.substringBefore("--").toIntOrNull() ?: 0, slug = slug)) }
            "continue" -> Store.continueList.firstOrNull()?.let { e -> nav.play(PlayerRoute(e.title, resume = true)) }
            "home" -> { nav.stack.clear(); nav.tab = Tab.HOME }
            "room" -> u.pathSegments.firstOrNull()?.let { id ->
                ru.qmods.anixq.tv.data.Room.join(id)
                android.widget.Toast.makeText(this, "Заходим в комнату — серия включится сама", android.widget.Toast.LENGTH_LONG).show()
            }
            "list" -> u.pathSegments.firstOrNull()?.let { token ->
                ru.qmods.anixq.mobile.data.Collections.import(token)?.let { c -> nav.push(ru.qmods.anixq.mobile.ui.CollectionRoute(c.id)) }
            }
        }
        i.data = null
    }

    override fun onStart() {
        super.onStart()
        Sync.start()
        Sync.syncNow()
        ru.qmods.anixq.tv.data.Crashes.upload()
        Remote.start(applicationContext)
        Updater.checkSometimes()
    }

    override fun onResume() {
        super.onResume()
        // Back from the browser after authorising on GitHub: finish the sign-in at once.
        Sync.wakeLogin()
    }

    override fun onStop() {
        super.onStop()
        Sync.flush()
        ContinueWidget.refresh(applicationContext)
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
fun Root(nav: Nav) {
    val holder = rememberSaveableStateHolder()
    val top = nav.top
    BackHandler(enabled = top != null || nav.tab != Tab.HOME) { if (top != null) nav.pop() else nav.tab = Tab.HOME }
    Box(Modifier.fillMaxSize().background(Pal.bg)) {
        when (top) {
            is PlayerRoute -> PlayerScreen(nav, top)
            is TitleRoute -> holder.SaveableStateProvider("title-${top.title.id}-${nav.stack.size}") { TitleScreen(nav, top.title) }
            is CatalogRoute -> holder.SaveableStateProvider("cat-${top.title}-${nav.stack.size}") { CatalogListScreen(nav, top) }
            is FolderRoute -> FolderScreen(nav, top.status)
            ru.qmods.anixq.mobile.ui.DownloadsRoute -> ru.qmods.anixq.mobile.ui.DownloadsScreen(nav)
            ru.qmods.anixq.mobile.ui.CollectionsRoute -> ru.qmods.anixq.mobile.ui.CollectionsScreen(nav)
            is ru.qmods.anixq.mobile.ui.CollectionRoute -> ru.qmods.anixq.mobile.ui.CollectionScreen(nav, top.id)
            ru.qmods.anixq.mobile.ui.ShikiLoginRoute -> ru.qmods.anixq.mobile.ui.ShikiLoginScreen(nav)
            HistoryRoute -> HistoryScreen(nav)
            RemoteRoute -> RemoteScreen(nav)
            SettingsRoute -> SettingsScreen(nav)
            SyncRoute -> SyncLoginScreen(nav)
            LibLoginRoute -> LibLoginScreen(nav)
            null -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    holder.SaveableStateProvider(nav.tab.name) {
                        when (nav.tab) {
                            Tab.HOME -> HomeScreen(nav)
                            Tab.CATALOG -> CatalogScreen(nav)
                            Tab.LIBRARY -> LibraryScreen(nav)
                            Tab.SEARCH -> SearchScreen(nav)
                        }
                    }
                }
                TabBar(nav)
            }
        }
    }
}

/** iOS-style tab bar: icon over a small label, accent for the selected tab. */
@Composable
private fun TabBar(nav: Nav) {
    Column(Modifier.fillMaxWidth().background(Color(0xF2121214)).navigationBarsPadding()) {
        HorizontalDivider(thickness = 0.5.dp, color = Color.White.copy(alpha = 0.12f))
        Row(Modifier.fillMaxWidth().height(54.dp)) {
            Tab.entries.forEach { t ->
                val sel = nav.tab == t
                val c = if (sel) Pal.pink else Pal.muted
                Column(Modifier.weight(1f).fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { nav.tab = t }.padding(top = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(when (t) { Tab.HOME -> Icons.Default.Subscriptions; Tab.CATALOG -> Icons.Default.GridView
                        Tab.LIBRARY -> Icons.Default.VideoLibrary; Tab.SEARCH -> Icons.Default.Search }, t.label, Modifier.size(25.dp), tint = c)
                    Text(t.label, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = c)
                }
            }
        }
    }
}
