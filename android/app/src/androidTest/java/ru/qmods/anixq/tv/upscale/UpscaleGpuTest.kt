package ru.qmods.anixq.tv.upscale

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.GLES20
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.GlEffect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.qmods.anixq.tv.ui.*
import java.io.File
import java.nio.ByteBuffer

/** Real GLES shaders and framebuffer pixels on a device/emulator; no mocked GL calls. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class UpscaleGpuTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun withGl(version: Int, block: () -> Unit) {
        val display = GlUtil.getDefaultEglDisplay()
        val egl = GlUtil.createEglContext(EGL14.EGL_NO_CONTEXT, display, version, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)
        val surface = GlUtil.createFocusedPlaceholderEglSurface(egl, display)
        try { block() } finally { GlUtil.destroyEglSurface(display, surface); GlUtil.destroyEglContext(display, egl) }
    }
    private data class Frame(val w: Int, val h: Int, val bytes: ByteArray)
    private fun render(effects: List<GlEffect>, label: String): Frame {
        var w = 64; var h = 48
        val pixels = ByteBuffer.allocateDirect(w*h*4)
        for (y in 0 until h) for (x in 0 until w) {
            val line = kotlin.math.abs(y - x*0.6) < 1.3 || x%13 == 0
            val v = if (line) 18 else 120 + ((x*3+y*2)%110)
            pixels.put(v.toByte()).put((if (line) 25 else v-25).toByte()).put((if (line) 30 else 100+y).toByte()).put(255.toByte())
        }
        pixels.flip()
        val textures = arrayListOf<Int>(); val fbos = arrayListOf<Int>()
        var tex = GlUtil.createTexture(w,h,false).also(textures::add)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,tex)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,w,h,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels)
        try {
            for (effect in effects) {
                val program = effect.toGlShaderProgram(context,false) as androidx.media3.effect.BaseGlShaderProgram
                try {
                    val size = program.configure(w,h)
                    val out = GlUtil.createTexture(size.width,size.height,false).also(textures::add)
                    val fbo = GlUtil.createFboForTexture(out).also(fbos::add)
                    GlUtil.focusFramebufferUsingCurrentContext(fbo,size.width,size.height)
                    program.drawFrame(tex,0)
                    GLES20.glFinish(); GlUtil.checkGlError()
                    tex=out;w=size.width;h=size.height
                } finally { program.release() }
            }
            val bytes = ByteBuffer.allocateDirect(w*h*4)
            GLES20.glReadPixels(0,0,w,h,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,bytes)
            GlUtil.checkGlError()
            val data = ByteArray(bytes.capacity()); bytes.rewind();bytes.get(data)
            assertTrue("$label: rendered frame must not be black", data.indices.filter { it%4<3 }.count { (data[it].toInt() and 255)>32 } > w*h)
            val image = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            bytes.rewind();image.copyPixelsFromBuffer(bytes)
            File(context.getExternalFilesDir(null),"upscale-checks").apply { mkdirs() }.resolve("$label.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            image.recycle()
            return Frame(w,h,data)
        } finally {
            GLES20.glDeleteFramebuffers(fbos.size,fbos.toIntArray(),0)
            GLES20.glDeleteTextures(textures.size,textures.toIntArray(),0)
        }
    }
    @Test fun fsrActuallyScalesAndDiffersFromBilinearOnGles2() = withGl(2) {
        Upscaler.split=1f
        val plain=render(upscaleEffects(16,96),"fsr-original")
        Upscaler.split=0f
        val processed=render(upscaleEffects(16,96),"fsr-processed")
        assertEquals(128,processed.w);assertEquals(96,processed.h)
        assertEquals(plain.w,processed.w)
        val changed=processed.bytes.indices.count { it%4<3 && kotlin.math.abs((processed.bytes[it].toInt() and 255)-(plain.bytes[it].toInt() and 255))>3 }
        assertTrue("FSR must change reconstructed pixels, not only the mode name",changed>128*96/8)
    }
    @Test fun lightweightModesRenderOnGles2() = withGl(2) {
        Upscaler.split=0f
        for (mode in listOf(1,2,16)) render(upscaleEffects(mode,96),"gles2-mode-$mode")
    }
    @Test fun everyAnime4kAssetCompilesAndRendersOnGles3() = withGl(3) {
        Upscaler.split=0f
        for (asset in context.assets.list("anime4k").orEmpty().filter { it.endsWith(".glsl") }) {
            Upscaler.lastFailure=null
            render(listOf(Anime4K.Effect("anime4k/$asset",96)),asset.removeSuffix(".glsl"))
            assertNull("$asset unexpectedly used fallback",Upscaler.lastFailure)
            assertTrue("Test GPU must support half-float CNN targets",Anime4K.supported==true)
        }
    }
    @Test fun anime4kCannotBlackScreenGles2Fallback() = withGl(2) {
        render(listOf(Anime4K.Effect("anime4k/Anime4K_Upscale_CNN_x2_S.glsl",96)),"anime4k-gles2-safe")
    }
}
