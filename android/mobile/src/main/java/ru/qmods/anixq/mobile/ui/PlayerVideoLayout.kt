package ru.qmods.anixq.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal val MiniPlayerHeight = 68.dp
internal val MiniVideoWidth = 112.dp

/** Keep one video slot across full, compact and system PiP layouts. */
@Composable
internal fun PlayerVideoLayout(
    fullscreen: Boolean,
    video: @Composable (Modifier) -> Unit,
    details: @Composable () -> Unit,
    minimized: Boolean = false,
    compact: @Composable () -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
        video(when {
            fullscreen -> Modifier.fillMaxSize()
            minimized -> Modifier.width(MiniVideoWidth).fillMaxHeight()
            else -> Modifier.fillMaxWidth().statusBarsPadding().aspectRatio(16f / 9f)
        })
        if (minimized) {
            compact()
        } else if (!fullscreen) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Spacer(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                details()
            }
        }
    }
}
