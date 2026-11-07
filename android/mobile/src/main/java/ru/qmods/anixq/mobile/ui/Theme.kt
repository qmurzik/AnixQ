package ru.qmods.anixq.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The iPhone app's dark "classic" style: near-black background, rose accent, violet gradient. */
object Pal {
    val bg = Color(0xFF000000)
    val card = Color(0xFF1C1C1E)
    val cardHi = Color(0xFF2C2C2E)
    val stroke = Color.White.copy(alpha = 0.08f)
    val text = Color(0xFFFFFFFF)
    val muted = Color(0xFF8E8E93)
    val faint = Color(0xFF48484A)
    val pink = Color(0xFFFF4D7A)
    val violet = Color(0xFF8C52FF)
    val gold = Color(0xFFFFD60A)
    val green = Color(0xFF30D158)
    val orange = Color(0xFFFF9F0A)
    val red = Color(0xFFFF453A)
    val blue = Color(0xFF0A84FF)
    val teal = Color(0xFF40C8E0)
    val purple = Color(0xFFBF5AF2)
    val indigo = Color(0xFF5E5CE6)
    val accent = Brush.horizontalGradient(listOf(pink, violet))
    val progress = Brush.horizontalGradient(listOf(pink, Color(0xFFFF8CBF)))

    fun score(s: Double): Color = when { s >= 8 -> green; s >= 6.5 -> orange; else -> red }
}

@Composable
fun AnixTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pal.pink, onPrimary = Color.White, secondary = Pal.violet, onSecondary = Color.White,
            background = Pal.bg, onBackground = Pal.text, surface = Pal.bg, onSurface = Pal.text,
            surfaceVariant = Pal.card, onSurfaceVariant = Pal.muted, surfaceContainer = Pal.card,
            surfaceContainerHigh = Pal.cardHi, surfaceContainerHighest = Pal.cardHi, surfaceContainerLow = Pal.card,
            surfaceContainerLowest = Pal.bg, outline = Pal.faint, outlineVariant = Pal.cardHi,
            secondaryContainer = Pal.cardHi, onSecondaryContainer = Pal.text,
        ),
        typography = base.copy(
            titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
            titleMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
        ),
    ) {
        // Without a Surface the default text colour is black: make it white everywhere.
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Pal.text, content = content)
    }
}
