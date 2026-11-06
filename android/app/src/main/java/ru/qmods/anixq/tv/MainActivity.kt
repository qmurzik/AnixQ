package ru.qmods.anixq.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Sync
import ru.qmods.anixq.tv.ui.AnixTheme
import ru.qmods.anixq.tv.ui.App
import ru.qmods.anixq.tv.ui.Nav
import ru.qmods.anixq.tv.ui.Tab
import ru.qmods.anixq.tv.data.Title

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        if (Store.libToken != null) lifecycleScope.launch { Api.loadProfile() }
        setContent {
            val nav = remember { Nav().also { applyLaunchExtras(it) } }
            AnixTheme { App(nav) }
        }
    }

    /** Used by CI screenshots: `-e tab 1`, `-e slug <slug>`, `--ez play true`. */
    private fun applyLaunchExtras(nav: Nav) {
        val i = intent ?: return
        i.getStringExtra("tab")?.toIntOrNull()?.let { nav.tab = Tab.entries.getOrElse(it) { Tab.HOME } }
        val slug = i.getStringExtra("slug") ?: return
        val t = Title(id = slug.substringBefore("--").toIntOrNull() ?: 0, slug = slug)
        if (i.getBooleanExtra("play", false)) nav.play(t, null) else nav.open(t)
    }

    override fun onStart() {
        super.onStart()
        Sync.start()
        Sync.syncNow()
    }
}
