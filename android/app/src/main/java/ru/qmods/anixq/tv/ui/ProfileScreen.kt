package ru.qmods.anixq.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import ru.qmods.anixq.tv.data.Profile
import ru.qmods.anixq.tv.data.Profiles

@Composable
fun Avatar(p: Profile, size: Dp = 34.dp) {
    Box(Modifier.size(size).background(Color(p.color), CircleShape), contentAlignment = Alignment.Center) {
        Text(p.name.take(1).uppercase(), fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

/** "Кто смотрит?" — shown at start when the TV has several profiles. */
@Composable
fun ProfilePicker(onDone: () -> Unit) {
    val list by Profiles.list.collectAsState()
    val current by Profiles.current.collectAsState()
    var adding by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(C.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Кто смотрит?", fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(40.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                list.forEach { p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Focusable(onClick = { if (p.id != current?.id) Profiles.switchTo(p); onDone() }, radius = 70.dp, scale = 1.1f,
                            container = Color.Transparent, modifier = Modifier.size(128.dp).memo("profiles", p.id, initial = p.id == current?.id)) {
                            Box(Modifier.fillMaxSize().padding(6.dp)) { Avatar(p, 116.dp) }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(p.name, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (list.size < 6) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Focusable(onClick = { adding = true }, radius = 70.dp, scale = 1.1f, modifier = Modifier.size(128.dp)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, modifier = Modifier.size(48.dp)) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Добавить", fontSize = 18.sp, color = C.muted)
                }
            }
        }
    }
    if (adding) NameDialog("Новый профиль", "") { name -> adding = false; if (name != null) Profiles.switchTo(Profiles.add(name)).also { onDone() } }
}

/** Name entry with the TV's own keyboard. */
@Composable
fun NameDialog(title: String, initial: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { fr.requestFocus() } }
    Dialog(onDismissRequest = { onDone(null) }) {
        Column(Modifier.width(460.dp).background(C.card, RoundedCornerShape(20.dp)).padding(24.dp)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            BasicTextField(text, { text = it.take(20) }, singleLine = true, cursorBrush = SolidColor(C.pink),
                textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
                modifier = Modifier.focusRequester(fr).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(14.dp).width(400.dp),
                decorationBox = { inner -> if (text.isEmpty()) Text("Имя", color = C.muted, fontSize = 20.sp); inner() })
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Готово", primary = true) { onDone(text) }
                PillButton("Отмена") { onDone(null) }
            }
        }
    }
}
