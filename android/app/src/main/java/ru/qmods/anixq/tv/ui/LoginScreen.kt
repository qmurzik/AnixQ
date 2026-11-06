package ru.qmods.anixq.tv.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.qmods.anixq.tv.data.LibAuth
import ru.qmods.anixq.tv.data.Pairing
import ru.qmods.anixq.tv.data.Store

/**
 * Sign-in without an iPhone. Left: the real AnimeLib sign-in page (remote + on-screen keyboard,
 * or VK / Yandex). Right: a QR for any phone or computer — type the login there and this TV signs
 * itself in; AnixQ on iPhone can also hand over everything (incl. sync) from the same page.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = remember { LibAuth() }
    var status by remember { mutableStateOf("Войдите любым удобным способом") }
    var done by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    var submitted by remember { mutableStateOf(false) }

    fun success(text: String) {
        if (done) return
        done = true
        status = text
        scope.launch { delay(1800); nav.pop() }
    }

    val web = remember {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.textZoom = 115
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            isFocusable = true
            isFocusableInTouchMode = true
        }
    }

    fun inject(view: WebView) {
        val c = pending ?: return
        view.evaluateJavascript(LibAuth.fillScript(c.first, c.second)) { r ->
            if (r?.contains("ok") == true) { pending = null; submitted = true; status = "Данные подставлены. Отметьте «Я не робот» и нажмите «Войти» пультом" }
            else if (!view.url.orEmpty().contains("/auth/login")) view.loadUrl(auth.url)
        }
    }

    fun handle(view: WebView, url: String): Boolean {
        val code = auth.codeFrom(url) ?: return false
        status = "Проверяем аккаунт…"
        view.stopLoading()
        scope.launch {
            val ok = runCatching { auth.finish(code) }.getOrDefault(false)
            if (ok) success("Готово! Вы вошли${Store.libUser?.let { " как $it" } ?: ""}")
            else { status = "Не получилось завершить вход, попробуйте ещё раз"; view.loadUrl(auth.url) }
        }
        return true
    }

    DisposableEffect(web) {
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = handle(view, request.url.toString())
            @Deprecated("API < 24")
            override fun shouldOverrideUrlLoading(view: WebView, url: String) = handle(view, url)
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { handle(view, url) }
            override fun onPageFinished(view: WebView, url: String) {
                if (url.contains("/oauth/authorize") && !done) status = "Почти всё: подтвердите доступ кнопкой на странице"
                if (pending != null) inject(view)
                else if (submitted && url.contains("/auth/login")) {
                    submitted = false
                    status = "Сайт не принял вход — проверьте логин и пароль"
                }
            }
        }
        web.loadUrl(auth.url)
        onDispose { web.stopLoading(); web.destroy() }
    }

    val pairing = remember {
        Pairing(
            onDone = { from -> success("Готово — данные получены с $from") },
            onError = { status = it },
            onCredentials = { l, p -> pending = l to p; status = "Получили данные с телефона…"; inject(web); web.requestFocus() },
        )
    }
    DisposableEffect(pairing) { pairing.start(); onDispose { pairing.stop() } }
    LaunchedEffect(Unit) { delay(400); web.requestFocus() }

    BackHandler { if (web.canGoBack() && !done) web.goBack() else nav.pop() }

    Row(Modifier.fillMaxSize().background(C.bg).padding(32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text("Вход в AnimeLib", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Пультом прямо здесь — или через VK / Яндекс", fontSize = 15.sp, color = C.muted)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Color.White)) {
                AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.width(400.dp).fillMaxHeight().padding(top = 8.dp)) {
            Text("Удобнее с телефона", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            QrCode(pairing.webUrl, Modifier.size(230.dp))
            Spacer(Modifier.height(18.dp))
            Step2("1", "Наведите камеру любого телефона на код")
            Step2("2", "Введите email и пароль AnimeLib на открывшейся странице")
            Step2("3", "На ТВ отметьте «Я не робот» и нажмите «Войти»")
            Spacer(Modifier.height(10.dp))
            Text("Есть AnixQ на iPhone — на той же странице кнопка «Открыть в AnixQ»: перенесёт ещё и синхронизацию.",
                fontSize = 13.sp, color = C.muted)
            Text("Пароль шифруется ключом из этого QR и расшифровывается только на ТВ.", fontSize = 13.sp, color = C.muted,
                modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.weight(1f))
            Text(status, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (done) Color(0xFF5BE08F) else C.text)
        }
    }
}

@Composable
private fun Step2(n: String, text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(n, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White,
            modifier = Modifier.background(C.pink, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 1.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 16.sp)
    }
}
