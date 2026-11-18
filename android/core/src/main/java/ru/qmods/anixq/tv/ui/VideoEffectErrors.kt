package ru.qmods.anixq.tv.ui

import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException

/** A codec may reject the GL input surface before the frame processor can report an error. */
@UnstableApi
fun isVideoEffectFailure(error: PlaybackException, effectsActive: Boolean): Boolean {
    if (error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED) return true
    if (generateSequence(error as Throwable?) { it.cause }.take(12).any {
        it is VideoFrameProcessingException || it is GlUtil.GlException
    }) return true
    // Retry the direct video path once for a video renderer failure with effects active.
    // A source/network failure or audio decoder failure is not evidence of a broken GL path.
    return effectsActive && error is ExoPlaybackException && error.type == ExoPlaybackException.TYPE_RENDERER &&
        error.rendererFormat?.sampleMimeType?.startsWith("video/") == true
}
