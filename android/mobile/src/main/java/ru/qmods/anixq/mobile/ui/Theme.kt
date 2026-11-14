package ru.qmods.anixq.mobile.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Look of the app, like the iPhone's styles: dark (classic), OLED, light or as the system; accent colour. */
object Looks {
    enum class Mode(val title: String) { DARK("Тёмная"), OLED("OLED"), LIGHT("Светлая"), SYSTEM("Как в системе") }
    enum class Accent(val title: String, val color: Color, val second: Color) {
        ROSE("Роза", Color(0xFFFF4D7A), Color(0xFF8C52FF)), OCEAN("Океан", Color(0xFF0A84FF), Color(0xFF40C8E0)),
        MINT("Мята", Color(0xFF30D158), Color(0xFF40C8E0)), SUNSET("Закат", Color(0xFFFF9F0A), Color(0xFFFF453A)),
        VIOLET("Фиалка", Color(0xFFBF5AF2), Color(0xFF5E5CE6)), YOU("Material You", Color(0xFFFF4D7A), Color(0xFF8C52FF)),
    }

    var mode by mutableStateOf(Mode.DARK); private set
    var accent by mutableStateOf(Accent.ROSE); private set
    /** Resolved for the current system setting (SYSTEM → light or dark). */
    var light by mutableStateOf(false)
    /** Wallpaper colour for "Material You" (Android 12+). */
    var dynamic by mutableStateOf<Color?>(null)

    private lateinit var app: Context
    fun init(c: Context) {
        app = c.applicationContext
        val p = app.getSharedPreferences("looks", Context.MODE_PRIVATE)
        mode = Mode.entries.getOrElse(p.getInt("mode", 0)) { Mode.DARK }
        accent = Accent.entries.getOrElse(p.getInt("accent", 0)) { Accent.ROSE }
        if (Build.VERSION.SDK_INT >= 31) dynamic = Color(app.getColor(android.R.color.system_accent1_400))
    }
    fun set(m: Mode) { mode = m; app.getSharedPreferences("looks", Context.MODE_PRIVATE).edit().putInt("mode", m.ordinal).apply() }
    fun set(a: Accent) { accent = a; app.getSharedPreferences("looks", Context.MODE_PRIVATE).edit().putInt("accent", a.ordinal).apply() }
}

/** Colours of the current look. Read in composition, so a change repaints everything at once. */
object Pal {
    private val dark get() = !Looks.light
    private val oled get() = Looks.mode == Looks.Mode.OLED

    val bg get() = if (dark) Color(0xFF000000) else Color(0xFFF2F2F7)
    val card get() = if (dark) (if (oled) Color(0xFF0E0E10) else Color(0xFF1C1C1E)) else Color(0xFFFFFFFF)
    val cardHi get() = if (dark) (if (oled) Color(0xFF1C1C1E) else Color(0xFF2C2C2E)) else Color(0xFFE5E5EA)
    val stroke get() = if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)
    val text get() = if (dark) Color(0xFFFFFFFF) else Color(0xFF000000)
    val muted get() = if (dark) Color(0xFF8E8E93) else Color(0xFF6C6C70)
    val faint get() = if (dark) Color(0xFF48484A) else Color(0xFFC7C7CC)
    val pink: Color get() = if (Looks.accent == Looks.Accent.YOU) Looks.dynamic ?: Looks.accent.color else Looks.accent.color
    val violet: Color get() = Looks.accent.second
    val gold = Color(0xFFFFD60A)
    val green = Color(0xFF30D158)
    val orange = Color(0xFFFF9F0A)
    val red = Color(0xFFFF453A)
    val blue = Color(0xFF0A84FF)
    val teal = Color(0xFF40C8E0)
    val purple = Color(0xFFBF5AF2)
    val indigo = Color(0xFF5E5CE6)
    val accent get() = Brush.horizontalGradient(listOf(pink, violet))
    val progress get() = Brush.horizontalGradient(listOf(pink, pink.copy(alpha = 0.6f)))

    fun score(s: Double): Color = when { s >= 8 -> green; s >= 6.5 -> orange; else -> red }
}

@Composable
fun AnixTheme(content: @Composable () -> Unit) {
    val system = isSystemInDarkTheme()
    Looks.light = when (Looks.mode) { Looks.Mode.LIGHT -> true; Looks.Mode.SYSTEM -> !system; else -> false }
    val base = Typography()
    val context = LocalContext.current
    val scheme = if (Looks.light) {
        (if (Looks.accent == Looks.Accent.YOU && Build.VERSION.SDK_INT >= 31) dynamicLightColorScheme(context) else lightColorScheme()).copy(
            primary = Pal.pink, onPrimary = Color.White, secondary = Pal.violet, background = Pal.bg, onBackground = Pal.text,
            surface = Pal.bg, onSurface = Pal.text, surfaceVariant = Pal.card, onSurfaceVariant = Pal.muted, surfaceContainer = Pal.card,
            surfaceContainerHigh = Pal.card, surfaceContainerHighest = Pal.cardHi, surfaceContainerLow = Pal.card, surfaceContainerLowest = Pal.bg,
            outline = Pal.faint, outlineVariant = Pal.cardHi, secondaryContainer = Pal.cardHi, onSecondaryContainer = Pal.text)
    } else {
        (if (Looks.accent == Looks.Accent.YOU && Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(context) else darkColorScheme()).copy(
            primary = Pal.pink, onPrimary = Color.White, secondary = Pal.violet, onSecondary = Color.White,
            background = Pal.bg, onBackground = Pal.text, surface = Pal.bg, onSurface = Pal.text,
            surfaceVariant = Pal.card, onSurfaceVariant = Pal.muted, surfaceContainer = Pal.card,
            surfaceContainerHigh = Pal.cardHi, surfaceContainerHighest = Pal.cardHi, surfaceContainerLow = Pal.card,
            surfaceContainerLowest = Pal.bg, outline = Pal.faint, outlineVariant = Pal.cardHi,
            secondaryContainer = Pal.cardHi, onSecondaryContainer = Pal.text)
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = base.copy(
            titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
            titleMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
        ),
    ) {
        // Without a Surface the default text colour is black: use the theme's text colour everywhere.
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Pal.text, content = content)
    }
}
