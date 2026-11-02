package com.chaska.inicio.pap

import com.chaska.inicio.*

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * «YouTube (web)» en una parte del PAP: youtube.com/tv, lo mismo que carga por dentro la app de YouTube
 * para TV, pero en una ventana de Stark TV. Así cae justo en su parte sin depuración (la app de YouTube
 * se abre a través de otra pantalla y Android la pone en el centro).
 * La sesión se inicia una vez con el código de yt.be/activate y queda guardada (cookies del WebView).
 * Hay dos (izquierda/derecha) con tareas distintas para poder estar abiertos a la vez.
 */
open class VisorWeb(val parte: Int) : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Pap.lanzadoEnEsteProceso) { Ajustes(this).papActivo = false; finishAndRemoveTask(); return }   // reabierto por Android
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            // La web de YouTube TV solo se abre como en una tele (el navegador de la app de YouTube, Cobalt).
            settings.userAgentString = UA
            webViewClient = object : WebViewClient() {
                // Solo YouTube y el inicio de sesión de Google; cualquier otro enlace no se abre aquí.
                override fun shouldOverrideUrlLoading(v: WebView, r: android.webkit.WebResourceRequest) =
                    r.url.host?.let { h -> PERMITIDOS.none { h == it || h.endsWith(".$it") } } ?: true
            }
            webChromeClient = object : WebChromeClient() {
                // Vídeo protegido (Widevine): es lo único que se concede (ni cámara ni micrófono).
                override fun onPermissionRequest(r: PermissionRequest) {
                    if (r.resources.all { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }) r.grant(r.resources) else r.deny()
                }
            }
            isFocusable = true
        }
        // La web es 16:9: recuadro 16:9 centrado (bandas negras) y escalada para que vea una TV de 1280×720,
        // como la app a pantalla completa. Si no, al ser la parte más estrecha, se corta.
        val raiz = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(web, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        }
        raiz.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l; val h = b - t
            var vw = w; var vh = w * 9 / 16
            if (vh > h) { vh = h; vw = h * 16 / 9 }
            val lp = web.layoutParams as FrameLayout.LayoutParams
            if (lp.width != vw || lp.height != vh) raiz.post {
                lp.width = vw; lp.height = vh; web.layoutParams = lp
                web.setInitialScale(vw * 100 / ANCHO_CSS)
            }
        }
        setContentView(raiz)
        web.requestFocus()
        web.loadUrl(URL)
        abiertos += this
    }

    override fun onResume() { super.onResume(); if (::web.isInitialized) { web.onResume(); web.resumeTimers() } }
    override fun onPause() { if (::web.isInitialized) web.onPause(); super.onPause() }

    override fun onDestroy() {
        abiertos -= this
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // Atrás nunca cierra la parte: va a la web (Escape, como en la app). El PAP se cierra con la tuerca.
        if (e.keyCode == KeyEvent.KEYCODE_BACK && ::web.isInitialized) {
            web.dispatchKeyEvent(KeyEvent(e.action, KeyEvent.KEYCODE_ESCAPE)); return true
        }
        return super.dispatchKeyEvent(e)
    }

    companion object {
        const val URL = "https://www.youtube.com/tv"
        private const val ANCHO_CSS = 1280
        private val PERMITIDOS = listOf("youtube.com", "google.com", "googlevideo.com", "ytimg.com", "gstatic.com", "youtube-nocookie.com", "yt.be", "youtu.be")
        private const val UA = "Mozilla/5.0 (Linux armeabi-v7a; Android 14) Cobalt/25.lts.30.1034943-gold (unlike Gecko) v8/8.8.278.17-jit gles Starboard/15, Thomson_ATV_2K/14 (Thomson, TV) com.google.android.youtube.tv/5.30.320"
        /** Visores web abiertos, para cerrarlos al salir del PAP. */
        val abiertos = mutableSetOf<VisorWeb>()
    }
}

class VisorWebIzq : VisorWeb(1)
class VisorWebDer : VisorWeb(2)
