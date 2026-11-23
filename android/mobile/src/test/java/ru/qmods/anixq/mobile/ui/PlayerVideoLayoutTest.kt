package ru.qmods.anixq.mobile.ui

import android.view.View
import android.view.ViewParent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PlayerVideoLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun portraitFullscreenMiniAndPipKeepOneVideoViewAndParent() {
        val mode = mutableStateOf("portrait")
        var creates = 0
        lateinit var video: View
        lateinit var parent: ViewParent
        rule.setContent {
            PlayerVideoLayout(fullscreen = mode.value in setOf("pip", "fullscreen"), minimized = mode.value == "mini", video = { modifier ->
                AndroidView(modifier = modifier.fillMaxSize(), factory = { context ->
                    creates++
                    View(context).also { video = it }
                })
            }, details = {})
        }
        var fullWidth = 0
        rule.runOnIdle {
            parent = video.parent
            fullWidth = video.width
        }
        // Routes and PiP only resize the view in place.
        for (state in listOf("mini", "pip", "mini", "portrait", "fullscreen", "pip", "fullscreen", "portrait")) {
            rule.runOnIdle { mode.value = state }
            rule.waitForIdle()
            rule.runOnIdle {
                assertEquals("Video must not be recreated on $state", 1, creates)
                assertSame("Video parent must stay attached on $state", parent, video.parent)
                if (state == "mini") assertTrue(video.width in 1 until fullWidth)
                else assertEquals(fullWidth, video.width)
            }
        }
    }
}
