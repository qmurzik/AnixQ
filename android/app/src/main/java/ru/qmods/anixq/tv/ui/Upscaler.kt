package ru.qmods.anixq.tv.ui

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import kotlin.math.roundToInt

/**
 * Real-time video upscaler on the TV's GPU, two passes per frame (the FSR 1.0 recipe):
 *  1. Lanczos-2 upscale from the stream's size (480p / 720p) to the screen's, with anti-ringing
 *     (result clamped to the 2×2 source neighbourhood, so line art gets no halos).
 *  2. RCAS — Robust Contrast-Adaptive Sharpening from AMD FidelityFX: sharpens edges in proportion
 *     to local contrast, with noise detection, so flat colour areas and grain aren't amplified.
 * [Upscaler.split] > 0 shows the original on the left part of the screen for comparison.
 */
@UnstableApi
object Upscaler {
    @Volatile var split = 0f            // 0 = off; 0.5 = left half untouched
    @Volatile var sharpness = 0.5f      // RCAS strength in "stops" (0 = max, 2 = soft)
    @Volatile var lastInfo = ""         // e.g. "720p → 1080p"

    /** Pass 1: Lanczos-2 upscale to [maxHeight] (keeps aspect). No-op if the video is already that big. */
    class Scale(private val maxHeight: Int) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(SCALE_FS) { w, h ->
            if (h >= maxHeight) { lastInfo = "${h}p (уже в разрешении экрана)"; Size(w, h) }
            else { val out = Size((w * maxHeight.toFloat() / h).roundToInt() and 1.inv(), maxHeight); lastInfo = "${h}p → ${maxHeight}p"; out }
        }
    }

    /** Pass 2: RCAS sharpening at output resolution. */
    class Sharpen : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(RCAS_FS) { w, h -> Size(w, h) }
    }

    private class Program(fragment: String, private val sizeFor: (Int, Int) -> Size) : BaseGlShaderProgram(false, 1) {
        private val program = GlProgram(VS, fragment)
        private var inW = 1; private var inH = 1; private var outW = 1; private var outH = 1

        init {
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            inW = inputWidth; inH = inputHeight
            return sizeFor(inputWidth, inputHeight).also { outW = it.width; outH = it.height }
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            program.use()
            program.setSamplerTexIdUniform("uTex", inputTexId, 0)
            program.setFloatsUniformIfPresent("uInSize", floatArrayOf(inW.toFloat(), inH.toFloat()))
            program.setFloatsUniformIfPresent("uOutSize", floatArrayOf(outW.toFloat(), outH.toFloat()))
            program.setFloatsUniformIfPresent("uSharp", floatArrayOf(Math.pow(2.0, -sharpness.toDouble()).toFloat()))
            program.setFloatsUniformIfPresent("uSplit", floatArrayOf(split))
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        override fun release() { super.release(); runCatching { program.delete() } }
    }

    private const val VS = """
        attribute vec4 aFramePosition;
        varying vec2 vUv;
        void main() { gl_Position = aFramePosition; vUv = (aFramePosition.xy + 1.0) * 0.5; }
    """

    private const val SCALE_FS = """
        precision highp float;
        uniform sampler2D uTex;
        uniform vec2 uInSize;
        uniform float uSplit;
        varying vec2 vUv;
        float lanczos2(float x) {
            x = abs(x);
            if (x < 1e-4) return 1.0;
            if (x >= 2.0) return 0.0;
            float px = 3.14159265 * x;
            return 2.0 * sin(px) * sin(px * 0.5) / (px * px);
        }
        void main() {
            if (vUv.x < uSplit) { gl_FragColor = texture2D(uTex, vUv); return; }   // comparison: plain stretch
            vec2 pos = vUv * uInSize - 0.5;
            vec2 base = floor(pos);
            vec2 f = pos - base;
            vec3 sum = vec3(0.0); float wsum = 0.0;
            vec3 mn = vec3(1.0); vec3 mx = vec3(0.0);
            for (int j = -1; j <= 2; j++) {
                float wy = lanczos2(float(j) - f.y);
                for (int i = -1; i <= 2; i++) {
                    vec3 s = texture2D(uTex, (base + vec2(float(i), float(j)) + 0.5) / uInSize).rgb;
                    float w = lanczos2(float(i) - f.x) * wy;
                    sum += s * w; wsum += w;
                    if (i >= 0 && i <= 1 && j >= 0 && j <= 1) { mn = min(mn, s); mx = max(mx, s); }
                }
            }
            gl_FragColor = vec4(clamp(sum / wsum, mn, mx), 1.0);   // anti-ringing
        }
    """

    // AMD FidelityFX RCAS (FSR 1.0), float path.
    private const val RCAS_FS = """
        precision highp float;
        uniform sampler2D uTex;
        uniform vec2 uOutSize;
        uniform float uSharp;
        uniform float uSplit;
        varying vec2 vUv;
        float luma(vec3 c) { return c.g * 0.5 + (c.r + c.b) * 0.25; }
        void main() {
            vec2 px = 1.0 / uOutSize;
            vec3 e = texture2D(uTex, vUv).rgb;
            if (uSplit > 0.0 && abs(vUv.x - uSplit) < px.x * 1.5) { gl_FragColor = vec4(1.0); return; }   // divider
            if (vUv.x < uSplit) { gl_FragColor = vec4(e, 1.0); return; }
            vec3 b = texture2D(uTex, vUv + vec2(0.0, -px.y)).rgb;
            vec3 d = texture2D(uTex, vUv + vec2(-px.x, 0.0)).rgb;
            vec3 f = texture2D(uTex, vUv + vec2(px.x, 0.0)).rgb;
            vec3 h = texture2D(uTex, vUv + vec2(0.0, px.y)).rgb;
            vec3 mn4 = min(min(b, d), min(f, h));
            vec3 mx4 = max(max(b, d), max(f, h));
            vec3 hitMin = min(mn4, e) / (4.0 * mx4 + 1e-5);
            vec3 hitMax = (1.0 - max(mx4, e)) / (4.0 * mn4 - 4.0 - 1e-5);
            vec3 lobeRGB = max(-hitMin, hitMax);
            float lobe = max(-0.1875, min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0)) * uSharp;
            // Noise detection: don't sharpen grain / dithering.
            float bL = luma(b), dL = luma(d), eL = luma(e), fL = luma(f), hL = luma(h);
            float nz = 0.25 * (bL + dL + fL + hL) - eL;
            float range = max(max(max(bL, dL), max(fL, hL)), eL) - min(min(min(bL, dL), min(fL, hL)), eL);
            nz = clamp(abs(nz) / max(range, 1e-5), 0.0, 1.0);
            lobe *= -0.5 * nz + 1.0;
            vec3 c = (lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0);
            gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """
}
