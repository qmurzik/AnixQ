package ru.qmods.anixq.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import ru.qmods.anixq.tv.BuildConfig
import ru.qmods.anixq.tv.data.UpdateState
import ru.qmods.anixq.tv.data.Updater

/** Banner on the home screen while a new version is waiting. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val state by Updater.state.collectAsState()
    val context = LocalContext.current
    val r = Updater.available ?: return
    Row(modifier.padding(start = 48.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        when (val s = state) {
            is UpdateState.Downloading -> UpdateProgress("Скачиваем ${r.name}…", s.progress)
            is UpdateState.Installing -> Text("Устанавливаем ${r.name}…", fontSize = 16.sp)
            else -> {
                PillButton("Обновить до ${r.name}", Icons.Rounded.SystemUpdate, primary = true) { Updater.install(context) }
                if (s is UpdateState.Failed) Text(s.message, fontSize = 14.sp, color = C.pink, modifier = Modifier.width(560.dp))
                else if (r.notes.isNotBlank()) Text(r.notes, fontSize = 14.sp, color = C.muted, maxLines = 1, modifier = Modifier.width(560.dp))
            }
        }
    }
}

/** "Обновления" block in Settings. */
@Composable
fun UpdateSection() {
    val state by Updater.state.collectAsState()
    val context = LocalContext.current
    Column {
        Text("Установлена версия ${BuildConfig.VERSION_NAME}", fontSize = 15.sp, color = C.muted)
        Spacer(Modifier.height(12.dp))
        when (val s = state) {
            is UpdateState.Downloading -> UpdateProgress("Скачиваем ${s.release.name}…", s.progress)
            is UpdateState.Installing -> Text("Открываем установщик…", fontSize = 16.sp)
            is UpdateState.Available -> Column {
                Text("Доступна версия ${s.release.name}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                if (s.release.notes.isNotBlank()) Text(s.release.notes, fontSize = 14.sp, color = C.muted, modifier = Modifier.padding(vertical = 6.dp))
                PillButton("Установить", Icons.Rounded.SystemUpdate, primary = true) { Updater.install(context) }
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PillButton(if (s is UpdateState.Checking) "Проверяем…" else "Проверить обновления") { Updater.check() }
                when (s) {
                    UpdateState.UpToDate -> Text("У вас последняя версия", fontSize = 15.sp, color = Color(0xFF5BE08F))
                    is UpdateState.Failed -> Column {
                        Text(s.message, fontSize = 14.sp, color = C.pink, modifier = Modifier.width(620.dp))
                        if (s.release != null) PillButton("Повторить") { Updater.install(context) }
                    }
                    else -> {}
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Новые версии приходят сами — ставить вручную нужно было только первый раз.", fontSize = 13.sp, color = C.muted)
    }
}

@Composable
private fun UpdateProgress(label: String, p: Float) {
    Column(Modifier.width(420.dp)) {
        Text("$label ${(p * 100).toInt()}%", fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(8.dp).background(C.grad))
        }
    }
}
