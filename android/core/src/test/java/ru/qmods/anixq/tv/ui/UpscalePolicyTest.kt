package ru.qmods.anixq.tv.ui

import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class UpscalePolicyTest {
    @Test fun fsrIncreasesRealOutputDimensionsWithinTheScreenAndTwoTimesBudget() {
        assertEquals(1920,Fsr1.outputSize(1280,720,1080).width)
        assertEquals(1080,Fsr1.outputSize(1280,720,1080).height)
        assertEquals(960,Fsr1.outputSize(640,480,2160).height)
        assertEquals(1920,Fsr1.outputSize(1920,1080,720).width)
        assertEquals(1080,Fsr1.outputSize(1920,1080,720).height)
    }
    @Test fun oldPreferenceIdsRetainTheirMeaningAndFsrIsNotDisguisedSharpening() {
        assertEquals(listOf("rcas"),upscaleMode(1).chain)
        assertEquals(listOf("lanczos","rcas"),upscaleMode(2).chain)
        assertEquals(listOf("Upscale_CNN_x2_S"),upscaleMode(3).chain)
        assertEquals(listOf("easu","rcas"),upscaleMode(16).chain)
        assertEquals(UPSCALE_MODES.size,UPSCALE_MODES.map { it.id }.distinct().size)
    }
    @Test fun animeShaderSizingPreservesMpvConditionsAndComputedLayerSizes() {
        val sizes=mapOf("MAIN" to (640 to 360),"OUTPUT" to (1920 to 1080),"conv" to (640 to 360))
        assertEquals(1280.0,Anime4K.rpn("conv.w 2 *",sizes),0.0)
        assertEquals(1.0,Anime4K.rpn("OUTPUT.w MAIN.w / 1.2 > OUTPUT.h MAIN.h / 1.2 > *",sizes),0.0)
        assertEquals(0.0,Anime4K.rpn("OUTPUT.w MAIN.w / 1.2 >",sizes+ ("OUTPUT" to (640 to 360))),0.0)
    }
}
