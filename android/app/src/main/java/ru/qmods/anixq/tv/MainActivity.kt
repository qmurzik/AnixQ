package ru.qmods.anixq.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import coil.Coil
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.Images
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Sync
import ru.qmods.anixq.tv.data.Remote
import ru.qmods.anixq.tv.data.Updater
import ru.qmods.anixq.tv.ui.AnixTheme
import ru.qmods.anixq.tv.ui.App
import ru.qmods.anixq.tv.ui.Nav
import ru.qmods.anixq.tv.ui.LoginRoute
import ru.qmods.anixq.tv.ui.Tab
import ru.qmods.anixq.tv.data.Title

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Updater.versionCode = BuildConfig.VERSION_CODE
        Store.init(applicationContext)
        Api.diskDir = cacheDir
        ru.qmods.anixq.tv.data.Crashes.install(applicationContext, "AnixQ TV", BuildConfig.VERSION_NAME)
        ru.qmods.anixq.tv.data.WatchNext.init(applicationContext)
        Coil.setImageLoader(Images.loader(applicationContext))
        if (Store.libToken != null) lifecycleScope.launch { Api.loadProfile() }
        setContent {
            val nav = remember { Nav().also { applyLaunchExtras(it); this.nav = it } }
            androidx.compose.runtime.DisposableEffect(nav) {
                Remote.navigator = { m -> nav.playFromPhone(m) }
                Remote.searchHandler = { q ->
                    ru.qmods.anixq.tv.ui.SearchRequest.pending = q
                    nav.stack.clear(); nav.tab = Tab.SEARCH
                }
                onDispose { Remote.navigator = null; Remote.searchHandler = null }
            }
            AnixTheme { App(nav) }
        }
    }

    private var nav: Nav? = null

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("rc_play")?.let { runCatching { nav?.playFromPhone(org.json.JSONObject(it)) } }
        nav?.let { handleSearch(intent, it) }
    }

    private fun handleSearch(i: android.content.Intent, nav: Nav): Boolean {
        val q = i.getStringExtra(android.app.SearchManager.QUERY)?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (i.action != android.content.Intent.ACTION_SEARCH && i.action != "android.media.action.MEDIA_PLAY_FROM_SEARCH") return false
        ru.qmods.anixq.tv.ui.SearchRequest.pending = q
        ru.qmods.anixq.tv.ui.SearchRequest.playFirst = i.action == "android.media.action.MEDIA_PLAY_FROM_SEARCH"
        nav.stack.clear(); nav.tab = Tab.SEARCH
        return true
    }

    /** Used by CI screenshots: `-e tab 1`, `-e slug <slug>`, `--ez play true`. */
    private fun applyLaunchExtras(nav: Nav) {
        val i = intent ?: return
        i.getStringExtra("rc_play")?.let { runCatching { nav.playFromPhone(org.json.JSONObject(it)) }; return }
        if (handleSearch(i, nav)) return
        i.getStringExtra("tab")?.toIntOrNull()?.let { nav.tab = Tab.entries.getOrElse(it) { Tab.HOME } }
        if (i.getStringExtra("login") != null) { nav.push(LoginRoute); return }
        val slug = i.getStringExtra("slug") ?: return
        val t = Title(id = slug.substringBefore("--").toIntOrNull() ?: 0, slug = slug)
        if (i.getBooleanExtra("play", false)) nav.play(t, null) else nav.open(t)
    }

    override fun onStart() {
        super.onStart()
        Sync.start()
        Sync.syncNow()
        ru.qmods.anixq.tv.data.Crashes.upload()
        Remote.start(applicationContext)
        RemoteService.start(applicationContext)
        Updater.checkSometimes()
        // Google TV "Продолжить просмотр": also what was watched on the phone / iPhone.
        lifecycleScope.launch { kotlinx.coroutines.delay(15_000); ru.qmods.anixq.tv.data.WatchNext.refreshFromLibrary() }
    }

    override fun onStop() {
        super.onStop()
        Sync.flush()
    }
}
