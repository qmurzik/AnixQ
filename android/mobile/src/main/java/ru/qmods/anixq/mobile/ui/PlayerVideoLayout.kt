package ru.qmods.anixq.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A single video composition slot: PiP/rotation only resize it, never reattach the same PlayerView. */
@Composable
internal fun PlayerVideoLayout(
    fullscreen: Boolean,
    video: @Composable (Modifier) -> Unit,
    details: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        video(if (fullscreen) Modifier.fillMaxSize()
            else Modifier.fillMaxWidth().statusBarsPadding().aspectRatio(16f / 9f))
        if (!fullscreen) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Spacer(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                details()
            }
        }
    }
}
