package ru.qmods.anixq.tv.ui

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Anime4K (bloc97, MIT) — small convolutional neural networks trained on anime, run as GPU shaders
 * on every video frame. The original mpv "hook" shader files are bundled unchanged in
 * assets/anime4k; this is a tiny runtime for that format inside Media3's video pipeline:
 * each //!HOOK pass is compiled to GLSL ES 3.0, intermediate layers live in half-float textures,
 * the last pass writes the frame (Restore: same size, Upscale: ×2).
 */
@UnstableApi
object Anime4K {
    data class Pass(val binds: List<String>, val save: String, val width: String, val height: String, val whenExpr: String?, val body: String)

    /** Whether this box can render to half-float textures (needed for the network's negative values). */
    @Volatile var supported: Boolean? = null

    fun parse(text: String): List<Pass> {
        val passes = ArrayList<Pass>()
        var binds = ArrayList<String>(); var save = "MAIN"; var w = "MAIN.w"; var h = "MAIN.h"; var whenE: String? = null
        val body = StringBuilder(); var inPass = false
        fun flush() { if (inPass) passes += Pass(binds, save, w, h, whenE, body.toString()) }
        for (line in text.lines()) {
            if (line.startsWith("//!DESC")) {
                flush(); inPass = true; binds = ArrayList(); save = "MAIN"; w = "MAIN.w"; h = "MAIN.h"; whenE = null; body.setLength(0)
            } else if (line.startsWith("//!BIND ")) binds += line.removePrefix("//!BIND ").trim()
            else if (line.startsWith("//!SAVE ")) save = line.removePrefix("//!SAVE ").trim()
            else if (line.startsWith("//!WIDTH ")) w = line.removePrefix("//!WIDTH ").trim()
            else if (line.startsWith("//!HEIGHT ")) h = line.removePrefix("//!HEIGHT ").trim()
            else if (line.startsWith("//!WHEN ")) whenE = line.removePrefix("//!WHEN ").trim()
            else if (line.startsWith("//!")) Unit
            else if (inPass) body.append(line).append('\n')
        }
        flush()
        return passes
    }

    /** mpv's reverse-Polish size expressions, e.g. "conv2d_last_tf.w 2 *" or the WHEN condition. */
    fun rpn(expr: String, sizes: Map<String, Pair<Int, Int>>): Double {
        val st = ArrayDeque<Double>()
        for (tok in expr.split(' ').filter { it.isNotBlank() }) {
            when (tok) {
                "+", "-", "*", "/", ">", "<" -> {
                    val b = st.removeLast(); val a = st.removeLast()
                    st.addLast(when (tok) { "+" -> a + b; "-" -> a - b; "*" -> a * b; "/" -> a / b; ">" -> if (a > b) 1.0 else 0.0; else -> if (a < b) 1.0 else 0.0 })
                }
                else -> st.addLast(tok.toDoubleOrNull() ?: run {
                    val (name, axis) = tok.split('.').let { it[0] to it.getOrElse(1) { "w" } }
                    val s = sizes[name] ?: (1 to 1)
                    (if (axis == "w") s.first else s.second).toDouble()
                })
            }
        }
        return st.lastOrNull() ?: 0.0
    }

    /** GLSL ES 3.0 for one pass: mpv's NAME_tex / NAME_texOff / NAME_pos / NAME_pt / NAME_size helpers. */
    fun fragmentSource(p: Pass, last: Boolean = false): String = buildString {
        append("#version 300 es\nprecision highp float;\nprecision highp int;\nin vec2 vUv;\nout vec4 fragColor;\n")
        for (b in p.binds.distinct()) {
            append("uniform highp sampler2D $b;\nuniform vec2 ${b}_size;\n")
            append("#define ${b}_pos vUv\n#define ${b}_pt (vec2(1.0) / ${b}_size)\n")
            append("#define ${b}_tex(pos) texture($b, pos)\n#define ${b}_texOff(off) texture($b, vUv + ${b}_pt * (off))\n")
        }
        append(p.body)
        if (last && "MAIN" in p.binds) {
            // Comparison mode: left of uSplit shows the plain (bilinear) frame, with a white divider.
            append("\nuniform float uSplit;\nvoid main() {\n")
            append("  if (uSplit > 0.0 && abs(vUv.x - uSplit) < 1.5 / float(textureSize(MAIN, 0).x * 2)) { fragColor = vec4(1.0); return; }\n")
            append("  fragColor = vUv.x < uSplit ? texture(MAIN, vUv) : hook();\n}\n")
        } else append("\nvoid main() { fragColor = hook(); }\n")
    }

    const val VS = "#version 300 es\nin vec4 aFramePosition;\nout vec2 vUv;\nvoid main() { gl_Position = aFramePosition; vUv = (aFramePosition.xy + 1.0) * 0.5; }\n"

    /** One bundled shader file (Restore or Upscale) as a Media3 effect. */
    class Effect(private val asset: String, private val outputHeight: Int, private val compare: Boolean = false) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
            Program(parse(context.assets.open(asset).bufferedReader().readText()), outputHeight, asset, compare)
    }

    private class Program(private val passes: List<Pass>, private val outputHeight: Int, private val name: String,
                          private val compareCapable: Boolean) : BaseGlShaderProgram(false, 1) {
        private val programs = ArrayList<GlProgram>()
        private val sizes = HashMap<String, Pair<Int, Int>>()
        private val textures = HashMap<String, Int>()      // saved layer -> texture id
        private val fbos = HashMap<String, Int>()
        private var enabled = true
        private var copy: GlProgram? = null
        private var outSize = Size(1, 1)

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            releaseTargets()
            sizes.clear(); sizes["MAIN"] = inputWidth to inputHeight
            val outW = outputHeight * inputWidth / maxOf(1, inputHeight)
            sizes["OUTPUT"] = outW to outputHeight
            // Can this GPU render to half-float textures? (ES 3.2 core, or EXT_color_buffer_half_float / _float)
            if (supported == null) {
                val ver = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
                val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
                supported = ver.contains("OpenGL ES 3") && (ver.contains("ES 3.2") || ext.contains("GL_EXT_color_buffer_half_float") || ext.contains("GL_EXT_color_buffer_float"))
                Log.i("AnixQ", "anime4k: $ver half-float=${supported}")
            }
            enabled = supported == true && passes.all { p -> p.whenExpr?.let { rpn(it, sizes) > 0.0 } ?: true }
            var finalSize = inputWidth to inputHeight
            if (enabled) {
                for ((i, p) in passes.withIndex()) {
                    val s = rpn(p.width, sizes).toInt() to rpn(p.height, sizes).toInt()
                    val last = i == passes.lastIndex
                    if (last) finalSize = s
                    else { sizes[p.save] = s; allocate(p.save, s) }
                }
            }
            outSize = Size(finalSize.first, finalSize.second)
            Upscaler.lastInfo = if (enabled) "${inputHeight}p → ${finalSize.second}p (нейросеть)" else Upscaler.lastInfo
            return outSize
        }

        private fun allocate(name: String, s: Pair<Int, Int>) {
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, s.first, s.second, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
            for ((k, v) in listOf(GLES20.GL_TEXTURE_MIN_FILTER to GLES20.GL_NEAREST, GLES20.GL_TEXTURE_MAG_FILTER to GLES20.GL_NEAREST,
                    GLES20.GL_TEXTURE_WRAP_S to GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_TEXTURE_WRAP_T to GLES20.GL_CLAMP_TO_EDGE)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, k, v)
            val f = IntArray(1); GLES20.glGenFramebuffers(1, f, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0)
            textures[name] = t[0]; fbos[name] = f[0]
        }

        private fun ensurePrograms() {
            if (programs.isNotEmpty() || !enabled) return
            try {
                passes.forEachIndexed { i, p ->
                    programs += GlProgram(VS, fragmentSource(p, last = i == passes.lastIndex && compareCapable)).apply {
                        setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
                    }
                }
            } catch (e: Exception) {
                Log.w("AnixQ", "anime4k $name: shader failed: ${e.message}")
                supported = false; enabled = false
            }
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            ensurePrograms()
            if (!enabled) { passThrough(inputTexId); return }
            val out = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, out, 0)
            // MAIN is sampled bilinearly by the last pass (the network adds detail on top of it).
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            for ((i, p) in passes.withIndex()) {
                val last = i == passes.lastIndex
                val target = if (last) outSize.width to outSize.height else sizes.getValue(p.save)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, if (last) out[0] else fbos.getValue(p.save))
                GLES20.glViewport(0, 0, target.first, target.second)
                val prog = programs[i]
                prog.use()
                p.binds.distinct().forEachIndexed { unit, b ->
                    val tex = if (b == "MAIN") inputTexId else textures.getValue(b)
                    prog.setSamplerTexIdUniform(b, tex, unit)
                    val s = sizes.getValue(b)
                    prog.setFloatsUniformIfPresent("${b}_size", floatArrayOf(s.first.toFloat(), s.second.toFloat()))
                }
                if (last && compareCapable) prog.setFloatsUniformIfPresent("uSplit", floatArrayOf(Upscaler.split))
                prog.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
        }

        private fun passThrough(tex: Int) {
            val c = copy ?: GlProgram(VS, "#version 300 es\nprecision mediump float;\nin vec2 vUv;\nout vec4 fragColor;\nuniform sampler2D uTex;\nvoid main(){ fragColor = texture(uTex, vUv); }\n").also {
                it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE); copy = it
            }
            c.use(); c.setSamplerTexIdUniform("uTex", tex, 0); c.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun releaseTargets() {
            if (fbos.isNotEmpty()) GLES20.glDeleteFramebuffers(fbos.size, fbos.values.toIntArray(), 0)
            if (textures.isNotEmpty()) GLES20.glDeleteTextures(textures.size, textures.values.toIntArray(), 0)
            fbos.clear(); textures.clear()
        }

        override fun release() {
            super.release()
            runCatching { releaseTargets(); programs.forEach { it.delete() }; copy?.delete() }
        }
    }
}
