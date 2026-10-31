package com.chaska.inicio

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaPlayer
import android.os.Environment
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File

/** Un fondo elegible: archivo de una carpeta o uno de los incluidos en la app ("asset:"). */
data class OpcionFondo(val clave: String, val nombre: String, val tipo: Tipo) {
    enum class Tipo(val etiqueta: String) { IMAGEN("Imagen"), ANIMADA("Animada"), VIDEO("Vídeo"), HTML("HTML5") }
}

object CatalogoFondos {
    private val imagenes = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "avif")
    private val animadas = setOf("gif")
    private val videos = setOf("mp4", "mkv", "webm", "mov", "3gp")
    private val html = setOf("html", "htm")

    fun tipoDe(nombre: String): OpcionFondo.Tipo? = when (nombre.substringAfterLast('.', "").lowercase()) {
        in imagenes -> OpcionFondo.Tipo.IMAGEN
        in animadas -> OpcionFondo.Tipo.ANIMADA
        in videos -> OpcionFondo.Tipo.VIDEO
        in html -> OpcionFondo.Tipo.HTML
        else -> null
    }

    /** Carpetas donde se buscan fondos: /sdcard/Fondos, Descargas y la carpeta Fondos de cada USB. */
    fun carpetas(): List<File> {
        val sd = Environment.getExternalStorageDirectory()
        val lista = mutableListOf(File(sd, "Fondos"), File(sd, Environment.DIRECTORY_DOWNLOADS))
        File("/storage").listFiles()?.forEach { vol ->
            if (vol.name != "emulated" && vol.name != "self") lista += File(vol, "Fondos")
        }
        return lista
    }

    /** Los fondos que trae la app, en este orden y con su nombre en Ajustes. */
    val INCLUIDOS = listOf(
        "castillo.webp" to "Castillo", "humo.html" to "Humo",
        "particulas.html" to "Partículas", "halloween.html" to "Halloween - HTML5",
        "santa.webp" to "Santa's Day",
    )

    fun nombreIncluido(clave: String): String? = INCLUIDOS.firstOrNull { "asset:" + it.first == clave }?.second

    /** Carpeta privada de los fondos recibidos (Recibidor). */
    fun recibidos(ctx: Context) = File(ctx.filesDir, "fondos")

    /** ¿Se puede abrir este HTML? Solo los de la app y los recibidos (revisados al llegar). */
    fun htmlPermitido(ctx: Context, clave: String): Boolean {
        if (clave.startsWith("asset:")) return true
        val raiz = recibidos(ctx).canonicalPath + File.separator
        return try { File(clave).canonicalPath.startsWith(raiz) } catch (e: Exception) { false }
    }

    fun todos(ctx: Context): List<OpcionFondo> {
        val res = mutableListOf<OpcionFondo>()
        val hay = ctx.assets.list("fondos")?.toSet().orEmpty()
        for ((f, nombre) in INCLUIDOS) if (f in hay) tipoDe(f)?.let { res += OpcionFondo("asset:$f", nombre, it) }
        // Primero los recibidos desde el móvil o el PC (Ajustes › Fondo › Recibir fondos), ya revisados y limpios.
        // De /sdcard, Descargas y USB solo imágenes y vídeos: un HTML de ahí no se ha revisado y podría ser de cualquiera.
        val recibidos = recibidos(ctx)
        for (dir in listOf(recibidos) + carpetas()) {
            val deConfianza = dir == recibidos
            dir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { f ->
                // Una copia de un fondo que ya trae la app (castillo.webp en Descargas) no sale dos veces.
                if (f.isFile && !f.name.startsWith(".") && INCLUIDOS.none { it.first.equals(f.name, ignoreCase = true) }) {
                    tipoDe(f.name)?.let { if (it != OpcionFondo.Tipo.HTML || deConfianza) res += OpcionFondo(f.absolutePath, f.name.substringBeforeLast('.'), it) }
                } else if (f.isDirectory && deConfianza) {
                    val idx = File(f, "index.html")
                    if (idx.isFile) res += OpcionFondo(idx.absolutePath, f.name, OpcionFondo.Tipo.HTML)
                }
            }
        }
        return res
    }
}

/**
 * Pinta el fondo elegido dentro de [capa]. Todo se crea en mostrar() y se destruye en liberar(),
 * que se llama al salir del inicio: así el fondo no gasta RAM ni GPU mientras ves Netflix o la TDT.
 */
class Fondo(private val ctx: Context, private val capa: FrameLayout) {
    private var reproductor: MediaPlayer? = null
    private var web: WebView? = null
    private var animada: AnimatedImageDrawable? = null

    fun mostrar(clave: String?) {
        liberar()
        val c = clave ?: return
        val tipo = CatalogoFondos.tipoDe(c.removePrefix("asset:")) ?: return
        if (!c.startsWith("asset:") && !File(c).canRead()) return
        try {
            when (tipo) {
                OpcionFondo.Tipo.IMAGEN, OpcionFondo.Tipo.ANIMADA -> imagen(c)
                OpcionFondo.Tipo.VIDEO -> video(c)
                OpcionFondo.Tipo.HTML -> if (CatalogoFondos.htmlPermitido(ctx, c)) html(c)
            }
        } catch (e: Exception) {
            liberar()
        }
    }

    fun liberar() {
        animada?.stop(); animada = null
        reproductor?.release(); reproductor = null
        web?.apply { stopLoading(); loadUrl("about:blank"); removeAllViews(); destroy() }
        web = null
        capa.removeAllViews()
    }

    private fun fuente(c: String): ImageDecoder.Source =
        if (c.startsWith("asset:")) ImageDecoder.createSource(ctx.assets, "fondos/" + c.removePrefix("asset:"))
        else ImageDecoder.createSource(File(c))

    private fun imagen(c: String) {
        val pantalla = ctx.resources.displayMetrics
        val d = ImageDecoder.decodeDrawable(fuente(c)) { dec, info, _ ->
            // Reduce imágenes enormes al tamaño de la pantalla para no llenar la RAM.
            var escala = 1
            while (info.size.width / (escala * 2) >= pantalla.widthPixels &&
                info.size.height / (escala * 2) >= pantalla.heightPixels) escala *= 2
            dec.setTargetSampleSize(escala)
        }
        val v = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageDrawable(d) }
        capa.addView(v, lleno())
        if (d is AnimatedImageDrawable) {
            animada = d
            d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            d.start()
        }
    }

    private fun video(c: String) {
        val tv = TextureView(ctx)
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                val mp = MediaPlayer()
                reproductor = mp
                try {
                    if (c.startsWith("asset:")) {
                        ctx.assets.openFd("fondos/" + c.removePrefix("asset:")).use { mp.setDataSource(it) }
                    } else {
                        mp.setDataSource(c)
                    }
                    mp.setSurface(Surface(st))
                    mp.isLooping = true
                    mp.setVolume(0f, 0f)
                    mp.setOnVideoSizeChangedListener { _, vw, vh -> recortar(tv, vw, vh) }
                    mp.setOnPreparedListener { it.start() }
                    mp.setOnErrorListener { _, _, _ -> true }
                    mp.prepareAsync()
                } catch (e: Exception) {
                    mp.release()
                    reproductor = null
                }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        capa.addView(tv, lleno())
    }

    /** Rellena la pantalla sin deformar el vídeo (como "recortar" en una foto). */
    private fun recortar(tv: TextureView, vw: Int, vh: Int) {
        if (vw == 0 || vh == 0 || tv.width == 0 || tv.height == 0) return
        val s = maxOf(tv.width.toFloat() / vw, tv.height.toFloat() / vh)
        val m = Matrix()
        m.setScale(s * vw / tv.width, s * vh / tv.height, tv.width / 2f, tv.height / 2f)
        tv.setTransform(m)
    }

    /**
     * Los HTML de la app se abren tal cual. Uno recibido va encerrado: sin red, sin leer archivos desde JavaScript
     * (así no puede llegar a la clave adb ni a los ajustes) y solo puede cargar lo que hay en su propia carpeta.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun html(c: String) {
        val propio = c.startsWith("asset:")
        val carpeta = if (propio) null else File(c).parentFile?.canonicalPath
        val w = WebView(ctx).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = propio
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true            // sus imágenes, con <img>, de su misma carpeta
            settings.allowContentAccess = false
            settings.blockNetworkLoads = !propio
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.setGeolocationEnabled(false)
            // Los fondos de la app (Halloween) leen los píxeles de su imagen para recortar el árbol y el agua.
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = propio
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = propio
            webViewClient = if (propio) WebViewClient() else Encerrado(carpeta)
        }
        web = w
        capa.addView(w, lleno())
        w.loadUrl(
            if (c.startsWith("asset:")) "file:///android_asset/fondos/" + c.removePrefix("asset:")
            else "file://$c"
        )
    }

    /** Un HTML recibido solo carga archivos de su carpeta y no navega a ningún sitio. */
    private class Encerrado(private val carpeta: String?) : WebViewClient() {
        override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
            val u = r.url
            val dentro = carpeta != null && u.scheme == "file" && u.path?.let {
                try { File(it).canonicalPath.startsWith(carpeta + File.separator) } catch (e: Exception) { false }
            } == true
            return if (dentro) null else WebResourceResponse("text/plain", "utf-8", 403, "No", emptyMap(), null)
        }
        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = true
    }

    private fun lleno() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
    )
}
