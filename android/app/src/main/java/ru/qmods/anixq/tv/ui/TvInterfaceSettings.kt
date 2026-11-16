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
                        InterfaceThumbnail(item, Modifier.width(112.dp).height(63.dp))
                        Column(Modifier.weight(1f)) {
                            Text(when (item) { TvAppearance.Mode.CLASSIC -> "Классический"; TvAppearance.Mode.PANORAMA -> "Панорама"; TvAppearance.Mode.NEON -> "Неон-Токио" },
                                fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(when (item) { TvAppearance.Mode.CLASSIC -> "Знакомый интерфейс AnixQ"; TvAppearance.Mode.PANORAMA -> "Крупные арты и боковая навигация"
                                TvAppearance.Mode.NEON -> "Ночной город: дождь, вывески, метро" }, fontSize = 12.sp, maxLines = 2,
                                modifier = Modifier.padding(top = 5.dp))
                            Text(if (selected) "✓ ВЫБРАН" else "ВЫБРАТЬ", fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }
            }
        }
        if (mode != TvAppearance.Mode.CLASSIC) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("Анимации: " + when (motion) { TvAppearance.Motion.AUTO -> "авто"; TvAppearance.Motion.FULL -> "полные"; TvAppearance.Motion.OFF -> "выключены" },
                motion != TvAppearance.Motion.OFF, modifier = Modifier.testTag("tv-panorama-motion")) { TvAppearance.cycleMotion() }
            Text("Авто учитывает производительность устройства", fontSize = 12.sp, color = C.muted)
        }
    }
}

@Composable
private fun InterfaceThumbnail(mode: TvAppearance.Mode, modifier: Modifier) {
    if (mode == TvAppearance.Mode.NEON) { NeonThumbnail(modifier); return }
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

/** Night street: billboard in a pink frame, rain, cyan signs on the right. */
@Composable
private fun NeonThumbnail(modifier: Modifier) {
    val pink = Color(0xFFFF2E88); val cyan = Color(0xFF00E5FF); val amber = Color(0xFFFFB000)
    Canvas(modifier.background(Color(0xFF07060F), RoundedCornerShape(8.dp))) {
        val x = size.width; val y = size.height
        repeat(16) { i -> val px = (i * 37 % 100) / 100f * x; val py = (i * 53 % 100) / 100f * y
            drawLine(Color.White.copy(alpha = .15f), Offset(px, py), Offset(px - 2f, py + 8f), 1f) }
        drawRoundRect(pink, Offset(.40f * x, .12f * y), Size(.42f * x, .48f * y), CornerRadius(3f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f))
        drawRoundRect(pink.copy(alpha = .2f), Offset(.40f * x, .12f * y), Size(.42f * x, .48f * y), CornerRadius(3f))
        repeat(5) { drawRoundRect(if (it == 0) cyan else cyan.copy(alpha = .35f), Offset(.90f * x, (.08f + it * .17f) * y), Size(.06f * x, .13f * y), CornerRadius(2f)) }
        drawRect(Color.White, Offset(.06f * x, .44f * y), Size(.26f * x, .06f * y))
        drawRect(amber, Offset(.06f * x, .56f * y), Size(.12f * x, .05f * y))
        repeat(6) { drawRect(Color(0xFF151129), Offset((.06f + it * .14f) * x, (.82f - (it % 3) * .06f) * y), Size(.12f * x, .3f * y)) }
    }
}
