package ru.qmods.anixq.tv.data

import android.net.Uri
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * AnimeLib sign-in exactly like their site does it: OAuth with PKCE on auth.hentaicdn.org
 * (one account for all *LIB sites). The TV opens the page, the user signs in there, and the
 * redirect back to the site carries a code that only this TV can turn into tokens.
 */
class LibAuth {
    val verifier = random(128)
    val state = random(40)
    val redirect = "${Api.SITE}/ru/front/auth/oauth/callback"

    val url: String = Uri.parse("https://auth.hentaicdn.org/auth/oauth/authorize").buildUpon()
        .appendQueryParameter("scope", "")
        .appendQueryParameter("client_id", "1")
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("redirect_uri", redirect)
        .appendQueryParameter("state", state)
        .appendQueryParameter("code_challenge", challenge(verifier))
        .appendQueryParameter("code_challenge_method", "S256")
        .appendQueryParameter("prompt", "consent")
        .appendQueryParameter("iframe", "false")
        .build().toString()

    /** The code when [url] is the redirect back to the site, null otherwise. */
    fun codeFrom(url: String): String? {
        if (!url.contains("/front/auth/oauth/callback")) return null
        val u = Uri.parse(url)
        if (u.getQueryParameter("state") != state) return null
        return u.getQueryParameter("code")
    }

    suspend fun finish(code: String) = Api.exchangeCode(code, verifier, redirect)

    companion object {
        private const val ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private fun random(n: Int): String { val r = SecureRandom(); return (1..n).map { ABC[r.nextInt(ABC.length)] }.joinToString("") }
        private fun challenge(v: String): String =
            Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(v.toByteArray()), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        /**
         * Puts the login and password the phone sent into the AnimeLib form. The anti-bot check and the
         * "Войти" button stay with the person at the TV: we only move the cursor next to them.
         */
        fun fillScript(login: String, password: String): String {
            fun q(s: String) = org.json.JSONObject.quote(s)
            return """
            (function(){
              var l=document.querySelector('input[name=login]'), p=document.querySelector('input[name=password]');
              if(!l||!p) return 'noform';
              function set(el,v){ var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value'); d.set.call(el,v);
                el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true})); }
              set(l,${q(login)}); set(p,${q(password)});
              var w=document.querySelector('altcha-widget'); var t=(w&&w.querySelector('input'))||(l.form&&l.form.querySelector('[type=submit]'));
              if(t){ t.scrollIntoView({block:'center'}); t.focus(); }
              return 'ok';
            })()
            """.trimIndent()
        }
    }
}
