package ru.qmods.anixq.tv.focus

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.qmods.anixq.tv.ui.isVideoEffectFailure

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoEffectFailureTest {
    private fun decoder(mime:String)=ExoPlaybackException.createForRenderer(Exception("codec rejected output surface"),"codec",0,
        Format.Builder().setSampleMimeType(mime).build(),C.FORMAT_HANDLED,false,PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
    @Test fun videoDecoderCanRecoverByRemovingTheEffectSurface() {
        assertTrue(isVideoEffectFailure(decoder("video/avc"),true))
        assertFalse(isVideoEffectFailure(decoder("video/avc"),false))
    }
    @Test fun networkAndAudioFailuresDoNotDisableVideoProcessing() {
        assertFalse(isVideoEffectFailure(ExoPlaybackException.createForSource(java.io.IOException("network"),PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),true))
        assertFalse(isVideoEffectFailure(decoder("audio/mp4a-latm"),true))
    }
    @Test fun frameProcessorFailureIsRecognizedWithoutAGuessedCodecName() {
        assertTrue(isVideoEffectFailure(PlaybackException("GPU",null,PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED),true))
    }
}
