package ru.qmods.anixq.tv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import ru.qmods.anixq.tv.data.Api

/** What the player overlay needs from whatever is actually playing the video. */
interface Engine {
    val position: Double
    val duration: Double
    val isPlaying: Boolean
    val isBuffering: Boolean
    val ended: Boolean
    fun play()
    fun pause()
    fun seek(seconds: Double)
    fun release()
}

/** AnimeLib's own player: plain MP4 from their mirrors, no ads. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ExoEngine(private val context: Context, private val onError: (String) -> Unit) : Engine {
    val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(
            OkHttpDataSource.Factory(Api.http).setDefaultRequestProperties(mapOf("Referer" to "${Api.SITE}/", "Origin" to Api.SITE))))
        .setHandleAudioBecomingNoisy(true)
        // Start after ~1 s of video instead of 2.5 s; keep up to a minute buffered for shaky Wi-Fi.
        .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 60_000, 1_000, 2_500).build())
        .build()

    val view: PlayerView = PlayerView(context).apply {
        useController = false
        player = exo
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        isFocusable = false
        isFocusableInTouchMode = false
        setShutterBackgroundColor(Color.BLACK)
        setKeepContentOnPlayerReset(true)
        keepScreenOn = true
        // Readable from the sofa: white text with a black outline, no box.
        subtitleView?.setStyle(androidx.media3.ui.CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null))
        subtitleView?.setFractionalTextSize(0.058f)
        subtitleView?.setApplyEmbeddedStyles(false)
    }

    /** Screen height in pixels the upscaler targets (capped at 1080 on weak boxes, else up to 2160). */
    private val screenHeight: Int by lazy {
        val h = runCatching {
            @Suppress("DEPRECATION")
            val d = (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
            maxOf(d.mode.physicalHeight, d.mode.physicalWidth.let { it * 9 / 16 }).coerceAtMost(d.mode.physicalHeight)
        }.getOrDefault(1080)
        h.coerceIn(720, if (ru.qmods.anixq.tv.data.Store.weakDevice) 1080 else 2160)
    }

    /**
     * 0 off · 1 sharpen (RCAS) · 2 Lanczos + RCAS ·
     * 3 Anime4K neural ×2 · 4 Anime4K restore + ×2 · 5 Anime4K M (bigger network).
     */
    private var appliedMode = 0
    @Volatile var firstFrame = false; private set
    var loadedAt = 0L; private set

    /** Effects must be in place before the media is prepared (setting them on a running player stalls it). */
    private fun applyUpscale(mode: Int) {
        if (mode == appliedMode) return
        exo.stop()
        setUpscale(mode)
        appliedMode = mode
    }

    private fun setUpscale(mode: Int) {
        val effects = mutableListOf<androidx.media3.common.Effect>()
        when (mode) {
            1 -> effects += Upscaler.Sharpen()
            2 -> { effects += Upscaler.Scale(screenHeight); effects += Upscaler.Sharpen() }
            3 -> effects += Anime4K.Effect("anime4k/Anime4K_Upscale_CNN_x2_S.glsl", screenHeight, compare = true)
            4 -> { effects += Anime4K.Effect("anime4k/Anime4K_Restore_CNN_S.glsl", screenHeight)
                   effects += Anime4K.Effect("anime4k/Anime4K_Upscale_CNN_x2_S.glsl", screenHeight, compare = true) }
            5 -> effects += Anime4K.Effect("anime4k/Anime4K_Upscale_CNN_x2_M.glsl", screenHeight, compare = true)
        }
        if (mode == 0) Upscaler.lastInfo = ""
        runCatching { exo.setVideoEffects(effects) }.onFailure { android.util.Log.w("AnixQ", "upscaler: ${it.message}") }
    }

    /** Dropped frames in the last ~10 s — the upscaler turns itself off if the box can't keep up. */
    @Volatile var recentDrops = 0; private set
    private var dropWindowStart = 0L

    fun setSubtitles(on: Boolean) {
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, !on).setPreferredTextLanguage("ru").build()
    }

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { onError(error.errorCodeName) }
            override fun onRenderedFirstFrame() { firstFrame = true }
        })
        exo.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                val now = System.currentTimeMillis()
                if (now - dropWindowStart > 10_000) { dropWindowStart = now; recentDrops = 0 }
                recentDrops += droppedFrames
            }
        })
    }

    fun load(url: String, startAt: Double, subtitles: List<ru.qmods.anixq.tv.data.Subtitle> = emptyList(), upscale: Int = appliedMode) {
        applyUpscale(upscale)
        firstFrame = false
        loadedAt = System.currentTimeMillis()
        val subs = subtitles.mapIndexed { i, s ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(s.url))
                .setMimeType(when (s.format) {
                    "vtt", "webvtt" -> androidx.media3.common.MimeTypes.TEXT_VTT
                    "srt" -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
                    else -> androidx.media3.common.MimeTypes.TEXT_SSA
                })
                .setLabel(s.label).setLanguage("ru")
                .setSelectionFlags(if (i == 0) androidx.media3.common.C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        exo.setMediaItem(MediaItem.Builder().setUri(url).setSubtitleConfigurations(subs).build(), (startAt * 1000).toLong())
        exo.prepare()
        exo.playWhenReady = true
    }

    override val position get() = exo.currentPosition / 1000.0
    override val duration get() = exo.duration.takeIf { it > 0 }?.div(1000.0) ?: 0.0
    override val isPlaying get() = exo.isPlaying || (exo.playWhenReady && exo.playbackState == Player.STATE_BUFFERING)
    override val isBuffering get() = exo.playbackState == Player.STATE_BUFFERING
    override val ended get() = exo.playbackState == Player.STATE_ENDED
    override fun play() { if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0); exo.play() }
    override fun pause() = exo.pause()
    override fun seek(seconds: Double) = exo.seekTo((seconds.coerceAtLeast(0.0) * 1000).toLong())
    override fun release() = exo.release()
}

/** Kodik iframe in a WebView, steered with its postMessage API. Used when AnimeLib gives no native stream. */
@SuppressLint("SetJavaScriptEnabled")
class KodikEngine(context: Context) : Engine {
    @Volatile override var position = 0.0; private set
    @Volatile override var duration = 0.0; private set
    @Volatile override var isPlaying = false; private set
    @Volatile override var ended = false; private set
    override val isBuffering get() = !started
    @Volatile private var started = false
    private var startAt = 0.0
    private val main = Handler(Looper.getMainLooper())

    val view: WebView = WebView(context).apply {
        setBackgroundColor(Color.BLACK)
        isFocusable = false
        isFocusableInTouchMode = false
        keepScreenOn = true
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.userAgentString = settings.userAgentString.replace("; wv", "")
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.d("AnixQ", "kodik console: ${m.message().take(200)}"); return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(v: WebView, h: android.webkit.SslErrorHandler, e: android.net.http.SslError) {
                android.util.Log.w("AnixQ", "kodik ssl error ${e.primaryError} ${e.url}"); h.cancel()
            }
            override fun onReceivedError(v: WebView, r: android.webkit.WebResourceRequest, e: android.webkit.WebResourceError) {
                if (r.isForMainFrame || r.url.host?.contains("kodik") == true) android.util.Log.w("AnixQ", "kodik load error ${e.errorCode} ${r.url}")
            }
        }
        addJavascriptInterface(Bridge(), "AQ")
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    inner class Bridge {
        @JavascriptInterface
        fun event(key: String, value: String) {
            val v = runCatching { JSONObject("{\"v\":$value}").opt("v") }.getOrNull()
            fun num(): Double? = (v as? Number)?.toDouble() ?: (v as? JSONObject)?.let { it.optDouble("time", it.optDouble("duration", Double.NaN)) }?.takeIf { !it.isNaN() }
            when (key) {
                "kodik_player_time_update" -> num()?.let {
                    position = it
                    if (!started) { started = true; if (startAt > 5) main.post { seek(startAt) } }
                }
                "kodik_player_duration_update" -> num()?.let { duration = it }
                "kodik_player_play" -> { isPlaying = true; ended = false; started = true }
                "kodik_player_pause" -> isPlaying = false
                "kodik_player_video_ended" -> { isPlaying = false; ended = true }
            }
        }
    }

    private var loadedAt = 0L
    /** Kodik didn't start by itself (autoplay blocked / old WebView). */
    val stuck get() = !started && loadedAt > 0 && System.currentTimeMillis() - loadedAt > 12_000

    /** Hand the remote to Kodik's own buttons inside the page. */
    fun interactive() {
        view.isFocusable = true; view.isFocusableInTouchMode = true
        view.requestFocus()
        loadedAt = 0
    }

    fun load(url: String, startAt: Double) {
        this.startAt = startAt
        loadedAt = System.currentTimeMillis()
        started = false; ended = false; position = 0.0; duration = 0.0
        val html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="margin:0;background:#000;overflow:hidden">
            <iframe id="k" src="$url" style="border:0;width:100vw;height:100vh" allow="autoplay *; fullscreen *" allowfullscreen></iframe>
            <script>
              window.addEventListener('message', function (e) {
                var d = e.data; if (!d || !d.key) return;
                try { AQ.event(String(d.key), JSON.stringify(d.value === undefined ? null : d.value)); } catch (x) {}
              });
              function aq(m, s) {
                var v = { method: m }; if (s !== undefined) v.seconds = s;
                document.getElementById('k').contentWindow.postMessage({ key: 'kodik_player_api', value: v }, '*');
              }
              setTimeout(function () { aq('play'); }, 2500);
            </script></body></html>
        """.trimIndent()
        view.loadDataWithBaseURL("${Api.SITE}/", html, "text/html", "utf-8", null)
    }

    private fun js(s: String) = view.evaluateJavascript(s, null)
    override fun play() { js("aq('play')"); isPlaying = true }
    override fun pause() { js("aq('pause')"); isPlaying = false }
    override fun seek(seconds: Double) { js("aq('seek', ${seconds.coerceAtLeast(0.0).toInt()})"); position = seconds }
    override fun release() { view.loadUrl("about:blank"); view.destroy() }
}
