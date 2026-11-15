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

    @Test fun portraitFullscreenAndPipKeepOneVideoViewAndParent() {
        val mode = mutableStateOf("portrait")
        var creates = 0
        lateinit var video: View
        lateinit var parent: ViewParent
        rule.setContent {
            PlayerVideoLayout(fullscreen = mode.value != "portrait", video = { modifier ->
                AndroidView(modifier = modifier.fillMaxSize(), factory = { context ->
                    creates++
                    View(context).also { video = it }
                })
            }, details = {})
        }
        rule.runOnIdle { parent = video.parent }
        // Home → PiP, expand, rotate, Home again: no second owner and no surface detach.
        for (state in listOf("pip", "portrait", "fullscreen", "pip", "fullscreen", "portrait")) {
            rule.runOnIdle { mode.value = state }
            rule.waitForIdle()
            rule.runOnIdle {
                assertEquals("Video must not be recreated on $state", 1, creates)
                assertSame("Video parent must stay attached on $state", parent, video.parent)
            }
        }
    }
}
