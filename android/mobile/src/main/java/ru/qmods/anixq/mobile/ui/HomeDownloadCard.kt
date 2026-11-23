package ru.qmods.anixq.mobile.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import ru.qmods.anixq.mobile.data.Downloads
import ru.qmods.anixq.mobile.data.HomeDownloads
import ru.qmods.anixq.tv.data.sources.AnimeSources
import ru.qmods.anixq.tv.data.Episode
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.WatchEntry

@Composable
internal fun HomeNetworkSettings() {
    val context = LocalContext.current
    var network by remember { mutableStateOf(HomeDownloads.network(context)) }
    fun selectNetwork() {
        val current = HomeDownloads.currentNetwork(context)
        if (current == null) {
            Toast.makeText(context, "Подключитесь к Wi-Fi и включите геолокацию, чтобы Android показал название сети", Toast.LENGTH_LONG).show()
        } else {
            HomeDownloads.setNetwork(context, current)
            network = current
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { selectNetwork() }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(network?.let { "Домашний Wi-Fi · $it" } ?: "Домашний Wi-Fi", color = Pal.text)
        Text("Дома AnixQ предложит скачать следующие серии. Название сети хранится на этом устройстве.", color = Pal.muted)
        FlowSettingsActions(network, {
            if (HomeDownloads.currentNetwork(context) != null) selectNetwork()
            else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }, {
            HomeDownloads.setNetwork(context, null)
            network = null
        })
    }
}

@Composable
private fun FlowSettingsActions(network: String?, select: () -> Unit, remove: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Capsule(if (network == null) "Выбрать текущую сеть" else "Сменить сеть", onClick = select)
        if (network != null) Capsule("Убрать", onClick = remove)
    }
}

@Composable
internal fun HomeDownloadCard(entry: WatchEntry) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val queued by Downloads.items.collectAsState()
    var atHome by remember { mutableStateOf(HomeDownloads.isHome(context)) }
    var dismissed by remember(entry.title.id) { mutableStateOf(HomeDownloads.dismissed(context, entry)) }
    var episodes by remember(entry.title.id) { mutableStateOf<List<Episode>>(emptyList()) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                atHome = HomeDownloads.isHome(context)
                delay(10_000L)
            }
        }
    }
    LaunchedEffect(atHome, entry.title.slug, dismissed) {
        if (atHome && !dismissed) {
            episodes = try { AnimeSources.episodes(entry.title) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { Downloads.episodes(entry.title.id).orEmpty() }
        }
    }
    if (!atHome || dismissed) return
    val pending = HomeDownloads.nextEpisodes(entry, episodes) { episode ->
        queued.containsKey(episode.id) || Store.isWatched(entry.title.id, episode.number)
    }
    if (pending.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).clip(RoundedCornerShape(16.dp))
            .background(Pal.card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Скачать серии дома?", color = Pal.text)
        Text("${entry.title.title} · серии ${pending.joinToString(", ") { it.number }}", color = Pal.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Capsule("Скачать", selected = true) {
                if (!HomeDownloads.isHome(context)) {
                    atHome = false
                } else {
                    Downloads.enqueue(entry.title, episodes, pending, entry.teamId) { message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                    HomeDownloads.dismiss(context, entry)
                    dismissed = true
                }
            }
            Capsule("Позже") {
                HomeDownloads.dismiss(context, entry)
                dismissed = true
            }
        }
    }
}
