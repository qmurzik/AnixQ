package ru.qmods.anixq.tv.ui

import android.content.Context
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.BaseGlShaderProgram
import kotlin.math.roundToInt

/** AMD FSR 1 EASU float path, ported to GLES 2 texture taps; followed by Upscaler's RCAS. */
@UnstableApi
object Fsr1 {
    class Scale(private val targetHeight: Int) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram =
            Upscaler.Program(context.assets.open("fsr/easu.glsl").bufferedReader().use { it.readText() },
                useHdr, reportScale = true) { w, h -> outputSize(w, h, targetHeight) }
    }

    fun outputSize(width: Int, height: Int, targetHeight: Int): Size {
        require(width > 0 && height > 0 && targetHeight > 0)
        if (height >= targetHeight) return Size(width, height)
        // At most 2x per axis: larger single-step spatial reconstruction cannot restore more detail.
        val outH = minOf(targetHeight.toLong(), height.toLong() * 2).toInt()
        val outW = (width.toDouble() * outH / height).roundToInt().coerceAtLeast(2) and -2
        return Size(outW, outH)
    }
}

/**
 * Snapdragon GSR 1: one pass, 3 taps on flat areas and 12 only on edges — the lightest real ×2 upscaler,
 * for boxes where FSR's two passes drop frames. Same output sizing as FSR.
 */
@UnstableApi
object Sgsr {
    class Scale(private val targetHeight: Int) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram =
            Upscaler.Program(context.assets.open("sgsr/sgsr1.glsl").bufferedReader().use { it.readText() },
                useHdr, reportScale = true) { w, h -> Fsr1.outputSize(w, h, targetHeight) }
    }
}
