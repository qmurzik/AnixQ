package ru.qmods.anixq.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/** Both interfaces are always one click away, including immediately after switching. */
@Composable
fun TvInterfaceSettings() {
    val mode by TvAppearance.mode.collectAsState()
    val motion by TvAppearance.motion.collectAsState()
    Column(Modifier.fillMaxWidth().testTag("tv-interface-settings")) {
        Text("Интерфейс телевизора", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        Text("Одна библиотека и один плеер. Оформление можно поменять в любой момент.", fontSize = 13.sp, color = C.muted,
            modifier = Modifier.padding(bottom = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvAppearance.Mode.entries.forEach { item ->
                val selected = item == mode
                Focusable(onClick = { TvAppearance.select(item) }, modifier = Modifier.weight(1f).testTag("tv-style-${item.name}").memo("settings", "interface-${item.name}", initial = selected), scale = 1.02f,
                    container = if (selected) C.cardHi else C.card) {
                    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        InterfaceThumbnail(item, Modifier.width(144.dp).height(81.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (item == TvAppearance.Mode.CLASSIC) "Классический" else "Панорама", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (item == TvAppearance.Mode.CLASSIC) "Знакомый интерфейс AnixQ" else "Кино, воздух и боковая навигация", fontSize = 12.sp, maxLines = 2,
                                modifier = Modifier.padding(top = 5.dp))
                            Text(if (selected) "✓ ВЫБРАН" else "ВЫБРАТЬ", fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }
            }
        }
        if (mode == TvAppearance.Mode.PANORAMA) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(if (motion) "Анимации: включены" else "Анимации: выключены", motion, modifier = Modifier.testTag("tv-panorama-motion")) { TvAppearance.animate(!motion) }
            Text("Режим слабого устройства также сокращает анимации", fontSize = 12.sp, color = C.muted)
        }
    }
}

@Composable
private fun InterfaceThumbnail(mode: TvAppearance.Mode, modifier: Modifier) {
    val modern = mode == TvAppearance.Mode.PANORAMA
    val accent = if (modern) Color(0xFFFFB69A) else C.pink
    Canvas(modifier.background(if (modern) Color(0xFF090D15) else C.bg, RoundedCornerShape(8.dp))) {
        val x = size.width; val y = size.height
        fun block(left: Float, top: Float, width: Float, height: Float, color: Color) = drawRoundRect(color,
            Offset(left*x, top*y), Size(width*x,height*y), CornerRadius(2.dp.toPx()))
        if (modern) {
            block(.04f,.07f,.035f,.045f,accent)
            repeat(5) { block(.04f,.25f+it*.12f,.035f,.045f,if(it==0) accent else C.muted) }
        } else repeat(5) { block(.06f+it*.16f,.1f,.11f,.025f,if(it==0) accent else C.muted) }
        val inset = if(modern) .14f else .06f
        block(inset,.25f,.36f,.07f,C.text)
        block(inset,.37f,.28f,.022f,C.muted)
        block(inset,.44f,.32f,.022f,C.muted)
        block(inset,.52f,.17f,.08f,accent)
        block(.62f,.22f,.3f,.37f,accent.copy(alpha=.18f))
        repeat(5) { block(inset+it*.16f,.71f,.13f,.22f,accent.copy(alpha=.12f+it*.025f)) }
    }
}
