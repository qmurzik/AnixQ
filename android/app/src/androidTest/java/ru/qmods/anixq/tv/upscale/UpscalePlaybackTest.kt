package ru.qmods.anixq.tv.upscale

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.qmods.anixq.tv.ui.Upscaler
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real MediaCodec/Media3 video, including switching the graph off again. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class UpscalePlaybackTest {
    @Test fun effectsCanBeEnabledChangedAndFullyDisabledWithoutLosingPosition() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val clip=File(instrumentation.targetContext.cacheDir,"upscale-smoke.mp4")
        instrumentation.context.assets.open("upscale-smoke.mp4").use { input -> clip.outputStream().use { input.copyTo(it) } }
        ActivityScenario.launch(UpscaleHarnessActivity::class.java).use { scenario ->
            for (mode in listOf(0,16,0,3,1,0)) {
                scenario.onActivity { a ->
                    a.error=null;Upscaler.lastInfo=""
                    val previous=a.engine.exo
                    val wasLoaded=previous.mediaItemCount>0
                    a.engine.load(clip.toURI().toString(),1.0,upscale=mode,headers=emptyMap())
                    a.engine.exo.setPlaybackSpeed(1.25f)
                    if (mode==0 && wasLoaded) assertNotSame("Disabling must discard the GL graph",previous,a.engine.exo)
                }
                val deadline=SystemClock.elapsedRealtime()+20_000
                var ready=false;var error:String?=null
                while (!ready && error==null && SystemClock.elapsedRealtime()<deadline) {
                    scenario.onActivity { a -> ready=a.engine.firstFrame;error=a.error }
                    if (!ready) SystemClock.sleep(100)
                }
                assertNull("Mode $mode failed before first frame",error)
                assertTrue("Mode $mode produced no frame",ready)
                var picture:Bitmap?=null
                val copied=CountDownLatch(1)
                scenario.onActivity { a ->
                    a.engine.pause()
                    assertTrue("Seek position lost in mode $mode",a.engine.position>=0.9)
                    assertEquals(1.25f,a.engine.exo.playbackParameters.speed,0.01f)
                    a.engine.captureFrame { picture=it;copied.countDown() }
                }
                assertTrue("PixelCopy timed out",copied.await(5,TimeUnit.SECONDS))
                val bitmap=checkNotNull(picture) { "Mode $mode has no visible video surface" }
                val colors=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(colors,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                assertTrue("Mode $mode is black",colors.count { (it and 0xffffff)>0x101010 }>colors.size/10)
                File(instrumentation.targetContext.getExternalFilesDir(null),"upscale-checks").apply { mkdirs() }
                    .resolve("playback-$mode.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
        }
    }
}
