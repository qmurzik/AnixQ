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
class ExoEngine(context: Context, private val onError: (String) -> Unit) : Engine {
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

    fun setSubtitles(on: Boolean) {
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, !on).setPreferredTextLanguage("ru").build()
    }

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { onError(error.errorCodeName) }
        })
    }

    fun load(url: String, startAt: Double, subtitles: List<ru.qmods.anixq.tv.data.Subtitle> = emptyList()) {
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
