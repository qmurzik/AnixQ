package ru.qmods.anixq.tv.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
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
import ru.qmods.anixq.tv.ui.*

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class PlaybackSelectorTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun remoteCanSelectSourceAndBalancerWithoutACombinedList() {
        Store.init(androidx.test.core.app.ApplicationProvider.getApplicationContext(), "tv-playback-picker")
        Store.navSounds = false
        val options = listOf(TeamOption(Team(1,"AniLibria.TV",false),true,false),
            TeamOption(Team(-1,"Озвучка AniLibria",false),true,false,"YummyAnime","Плеер CVH","yummy"),
            TeamOption(Team(-2,"Озвучка AniLibria",false),true,false,"YummyAnime","Плеер Kodik","yummy"))
        val selected = mutableStateOf(1)
        rule.setContent { AnixTheme { Box(Modifier.fillMaxSize().background(C.bg).padding(56.dp)) {
            PlaybackSelector(options, selected.value) { selected.value = it.team.id }
        } } }
        rule.onNodeWithTag("playback-source").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithTag("playback-source").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithText("YummyAnime").performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(-1,selected.value) }
        rule.onNodeWithTag("playback-balancer").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithTag("playback-balancer").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithText("Kodik").performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(-2,selected.value) }
        rule.onNodeWithText("Плеер Kodik", substring = true).assertDoesNotExist()
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(image))
        val file = java.io.File("build/reports/yummy-tv-playback-picker.png").apply { parentFile?.mkdirs() }
            file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
