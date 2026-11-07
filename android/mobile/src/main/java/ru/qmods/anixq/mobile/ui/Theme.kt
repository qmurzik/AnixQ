package ru.qmods.anixq.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Same palette as the TV app and the iOS app's dark style. */
object Pal {
    val bg = Color(0xFF0A0A0F)
    val card = Color(0xFF17171F)
    val cardHi = Color(0xFF23232E)
    val text = Color(0xFFF5F4FA)
    val muted = Color(0xFF9C99AD)
    val faint = Color(0xFF5E5B6E)
    val pink = Color(0xFFFF4D7A)
    val violet = Color(0xFF8B5CFF)
    val gold = Color(0xFFFFC94D)
    val green = Color(0xFF3DDC84)
    val accent = Brush.horizontalGradient(listOf(pink, violet))
}

@Composable
fun AnixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pal.pink, onPrimary = Color.White, secondary = Pal.violet, onSecondary = Color.White,
            background = Pal.bg, onBackground = Pal.text, surface = Pal.bg, onSurface = Pal.text,
            surfaceVariant = Pal.card, onSurfaceVariant = Pal.muted, surfaceContainer = Pal.card,
            surfaceContainerHigh = Pal.cardHi, surfaceContainerHighest = Pal.cardHi, surfaceContainerLow = Pal.card,
            outline = Pal.faint, outlineVariant = Pal.cardHi, secondaryContainer = Pal.cardHi, onSecondaryContainer = Pal.text,
        ),
        content = content,
    )
}
