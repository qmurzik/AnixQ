package ru.qmods.anixq.tv.ui

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix
import org.json.JSONObject
import ru.qmods.anixq.tv.data.Store
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Colour settings for the picture (same presets as the iPhone app). */
data class Picture(val saturation: Float = 1f, val brightness: Float = 0f, val contrast: Float = 1f) {
    val isNeutral get() = abs(saturation - 1) < 0.01f && abs(brightness) < 0.005f && abs(contrast - 1) < 0.01f

    fun toJson() = JSONObject().put("s", saturation.toDouble()).put("b", brightness.toDouble()).put("c", contrast.toDouble()).toString()

    companion object {
        val presets = listOf(
            "Как есть" to Picture(),
            "Сочно" to Picture(1.3f, 0f, 1.05f),
            "Ярко" to Picture(1.15f, 0.04f, 1.08f),
            "Кино" to Picture(1.0f, -0.02f, 1.12f),
            "Мягко" to Picture(0.92f, 0.02f, 0.94f),
        )

        fun from(json: String?): Picture = runCatching {
            val j = JSONObject(json!!)
            Picture(j.optDouble("s", 1.0).toFloat(), j.optDouble("b", 0.0).toFloat(), j.optDouble("c", 1.0).toFloat())
        }.getOrDefault(Picture())
    }
}

/**
 * The colour tuning, read by the GPU pipeline for every frame — sliders work live while the video plays.
 * (Turning it on from "neutral" needs the pipeline, so that one change reloads at the same moment.)
 */
@UnstableApi
object ColorTuning : RgbMatrix {
    @Volatile var value: Picture = Picture()

    fun load() { value = Picture.from(Store.picture) }
    fun set(p: Picture) { value = p; Store.picture = p.toJson() }

    /** Column-major 4×4: saturation around Rec.709 luma, then contrast around mid-grey, plus brightness. */
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val p = value
        val s = p.saturation; val c = p.contrast
        val wr = 0.2126f; val wg = 0.7152f; val wb = 0.0722f
        val off = 0.5f * (1 - c) + p.brightness
        // Row i, column j of M = (1-s)·w_j + (i==j ? s : 0), then scaled by contrast.
        fun m(i: Int, j: Int): Float {
            val w = when (j) { 0 -> wr; 1 -> wg; else -> wb }
            return c * ((1 - s) * w + if (i == j) s else 0f)
        }
        return floatArrayOf(
            m(0, 0), m(1, 0), m(2, 0), 0f,
            m(0, 1), m(1, 1), m(2, 1), 0f,
            m(0, 2), m(1, 2), m(2, 2), 0f,
            off, off, off, 1f,
        )
    }
}

enum class SoundMode(val title: String, val subtitle: String) {
    OFF("Выключено", "Звук как в файле"),
    SMART("Умное улучшение", "Ровная громкость, разборчивые голоса, вернёт «воздух» сжатому звуку"),
    VOICE("Чёткие голоса", "Голоса вперёд, музыка и фон тише"),
    NIGHT("Ночной режим", "Тихие сцены громче, взрывы тише");

    companion object {
        @Volatile var current: SoundMode = OFF
        fun load() { current = entries.getOrElse(Store.soundMode) { OFF } }
        fun set(m: SoundMode) { current = m; Store.soundMode = m.ordinal }
    }
}

/** Second-order IIR filter (RBJ audio-EQ cookbook). */
private class Biquad(private val b0: Float, private val b1: Float, private val b2: Float, private val a1: Float, private val a2: Float) {
    private var z1 = 0f
    private var z2 = 0f

    fun run(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    companion object {
        private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(), (a1 / a0).toFloat(), (a2 / a0).toFloat())

        fun peaking(f: Double, q: Double, db: Double, rate: Double): Biquad {
            val a = 10.0.pow(db / 40); val w = 2 * Math.PI * f / rate; val alpha = sin(w) / (2 * q); val c = cos(w)
            return make(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
        }

        fun highpass(f: Double, rate: Double, q: Double = 0.707): Biquad {
            val w = 2 * Math.PI * f / rate; val alpha = sin(w) / (2 * q); val c = cos(w)
            return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun lowShelf(f: Double, db: Double, rate: Double): Biquad {
            val a = 10.0.pow(db / 40); val w = 2 * Math.PI * f / rate; val c = cos(w); val s = sin(w)
            val alpha = s / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * alpha
            return make(a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
                (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa)
        }
    }
}

/**
 * Smart sound in ExoPlayer's audio chain (16-bit PCM): EQ for voices, an exciter that rebuilds the top
 * octave lossy compression removed, and automatic level so quiet and loud dubs sound the same.
 * Always active, so switching modes works live; OFF passes the audio through untouched.
 */
@UnstableApi
class SoundProcessor : BaseAudioProcessor() {
    private var rate = 48_000.0
    private var channels = 2
    private var mode: SoundMode? = null
    private var presence: Array<Biquad> = emptyArray()
    private var shelf: Array<Biquad> = emptyArray()
    private var exIn: Array<Biquad> = emptyArray()
    private var exOut: Array<Biquad> = emptyArray()
    private var exMix = 0f
    private var sideGain = 1f
    private var target = 0.12f
    private var maxGain = 3f
    private var level = 0.1f
    private var gain = 1f
    private var work = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        rate = inputAudioFormat.sampleRate.toDouble()
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        mode = null
        return inputAudioFormat
    }

    private fun configure(m: SoundMode) {
        mode = m
        val presenceDb: Double; val shelfDb: Double
        when (m) {
            SoundMode.OFF -> { presenceDb = 0.0; shelfDb = 0.0; exMix = 0f; sideGain = 1f; target = 0.12f; maxGain = 1f }
            SoundMode.SMART -> { presenceDb = 2.5; shelfDb = 1.5; exMix = 0.12f; sideGain = 1f; target = 0.12f; maxGain = 3f }
            SoundMode.VOICE -> { presenceDb = 5.0; shelfDb = -3.0; exMix = 0.06f; sideGain = 0.6f; target = 0.12f; maxGain = 3f }
            SoundMode.NIGHT -> { presenceDb = 3.0; shelfDb = -4.0; exMix = 0.05f; sideGain = 0.85f; target = 0.11f; maxGain = 6f }
        }
        presence = Array(channels) { Biquad.peaking(2800.0, 0.9, presenceDb, rate) }
        shelf = Array(channels) { Biquad.lowShelf(120.0, shelfDb, rate) }
        exIn = Array(channels) { Biquad.highpass(minOf(5000.0, rate * 0.2), rate) }
        exOut = Array(channels) { Biquad.highpass(minOf(6500.0, rate * 0.25), rate) }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val out = replaceOutputBuffer(bytes)
        val m = SoundMode.current
        if (m == SoundMode.OFF) {
            out.put(inputBuffer); out.flip(); return
        }
        if (m != mode) configure(m)
        val samples = bytes / 2
        val frames = samples / channels
        if (work.size < samples) work = FloatArray(samples)
        val src = inputBuffer.order(ByteOrder.nativeOrder())
        for (i in 0 until samples) work[i] = src.short / 32768f

        if (channels == 2 && sideGain < 0.999f) {
            for (f in 0 until frames) {
                val l = work[f * 2]; val r = work[f * 2 + 1]
                val mid = (l + r) * 0.5f; val side = (l - r) * 0.5f * sideGain
                work[f * 2] = mid + side; work[f * 2 + 1] = mid - side
            }
        }
        var sum = 0f
        for (f in 0 until frames) for (c in 0 until channels) {
            val i = f * channels + c
            var x = shelf[c].run(presence[c].run(work[i]))
            if (exMix > 0f) x += exOut[c].run(tanh(exIn[c].run(x) * 4f)) * exMix
            work[i] = x
            sum += x * x
        }
        val rms = sqrt(sum / samples.coerceAtLeast(1))
        val block = frames / rate.toFloat()
        val attack = if (mode == SoundMode.NIGHT) 0.05f else 0.3f
        val release = if (mode == SoundMode.NIGHT) 0.6f else 2.5f
        level += (rms - level) * (1 - exp(-block / if (rms > level) attack else release))
        val wanted = if (level > 0.003f) (target / level).coerceIn(0.5f, maxGain) else gain
        gain += (wanted - gain) * (1 - exp(-block / 0.4f))

        out.order(ByteOrder.nativeOrder())
        for (i in 0 until samples) {
            val y = work[i] * gain
            val a = abs(y)
            val limited = if (a < 0.6f) y else Math.copySign(0.6f + 0.4f * tanh((a - 0.6f) / 0.4f), y)
            out.putShort((limited.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        out.flip()
    }

    override fun onFlush() { gain = 1f; level = 0.1f; mode = null }
}
