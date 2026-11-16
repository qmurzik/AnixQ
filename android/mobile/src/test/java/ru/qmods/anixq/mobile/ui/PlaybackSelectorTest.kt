package ru.qmods.anixq.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.qmods.anixq.tv.data.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h851dp-port-xhdpi")
class PlaybackSelectorTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val options = listOf(TeamOption(Team(1,"AniLibria.TV",false),true,false)) + listOf("Плеер CVH","Плеер Kodik").flatMapIndexed { provider, name ->
        (0..19).map { i -> TeamOption(Team(-100-provider*100-i, if(i==0) "Озвучка AniLibria" else "Озвучка Студия %02d".format(i),false),true,false,"YummyAnime",name,"yummy") }
    }
    @Test fun portraitPickerSeparatesProvidersAndSearchesOnlyCurrentVoices() {
        val selected = mutableStateOf(-100)
        rule.setContent { AnixTheme { Box(Modifier.fillMaxSize().background(Pal.bg).padding(top = 40.dp)) {
            PlaybackSelector(options, selected.value) { selected.value = it.team.id }
        } } }
        rule.onNodeWithText("YummyAnime · Плеер", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("playback-source").performClick()
        rule.onNodeWithText("AnimeLib").performClick()
        rule.runOnIdle { assertEquals(1, selected.value) }
        rule.onNodeWithTag("playback-source").performClick()
        rule.onNodeWithText("YummyAnime").performClick()
        rule.runOnIdle { assertEquals(-100, selected.value) }
        rule.onNodeWithTag("playback-balancer").performClick()
        rule.onNodeWithText("Kodik").performClick()
        rule.runOnIdle { assertEquals(-200, selected.value) }
        rule.onNodeWithTag("playback-dub").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Студия 18")
        rule.onNode(hasText("Студия 18") and !hasSetTextAction()).performClick()
        rule.runOnIdle { assertEquals(-218, selected.value) }
        rule.onNodeWithTag("playback-selector").assertIsDisplayed()
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(image))
        val file = File("build/reports/yummy-playback-picker.png").apply { parentFile?.mkdirs() }
            file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
