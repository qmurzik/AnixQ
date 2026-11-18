package ru.qmods.anixq.tv.upscale

import android.app.Activity
import android.os.Bundle
import ru.qmods.anixq.tv.ui.ExoEngine
import ru.qmods.anixq.tv.data.Store

/** Debug-only host for real decoder -> effects -> Surface instrumentation. Never shipped in release. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class UpscaleHarnessActivity : Activity() {
    lateinit var engine: ExoEngine
    @Volatile var error: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this,"upscale-instrumentation")
        engine=ExoEngine(this) { error=it }
        setContentView(engine.view)
    }
    override fun onDestroy() { engine.release();super.onDestroy() }
}
