package com.chaska.inicio

import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.StatFs
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Monitor de recursos: una línea pequeña arriba del todo, por encima de cualquier app (capa del servicio Teclas),
 * con el procesador, la memoria y el almacenamiento libre. Se mide cada 3 s en un hilo aparte y solo con la
 * pantalla encendida. El procesador sale de /proc/stat: Android lo esconde a las apps desde la 8, así que se
 * lee por la depuración; sin ella, la línea no enseña el procesador.
 */
object Monitor {
    private const val TAG = "InicioTV"
    private const val CADA_MS = 3000L

    private var vista: TextView? = null
    private var hilo: HandlerThread? = null
    private var trabajo: Handler? = null
    private val principal = Handler(Looper.getMainLooper())
    private var totalAntes = -1L
    private var libreAntes = -1L

    /** Lo pone o lo quita según el ajuste. Desde Teclas (al arrancar) y desde Ajustes. */
    fun aplicar(ctx: Context) {
        if (Ajustes(ctx).monitor) mostrar() else quitar()
    }

    private fun mostrar() {
        val s = Teclas.servicio ?: return
        if (vista != null) return
        val d = s.resources.displayMetrics.density
        val t = TextView(s).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Fuentes.normal(s)
            setPadding((10 * d).toInt(), (3 * d).toInt(), (10 * d).toInt(), (3 * d).toInt())
            background = GradientDrawable().apply { setColor(0xAA000000.toInt()); cornerRadius = 10 * d }
            text = "Midiendo…"
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (6 * d).toInt() }
        try { (s.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(t, lp) }
        catch (e: Exception) { Log.w(TAG, "monitor: $e"); return }
        vista = t
        totalAntes = -1; libreAntes = -1
        val h = HandlerThread("monitor").also { it.start() }
        hilo = h
        trabajo = Handler(h.looper).also { it.post(medir) }
    }

    fun quitar() {
        trabajo?.removeCallbacksAndMessages(null); trabajo = null
        hilo?.quitSafely(); hilo = null
        val v = vista ?: return
        vista = null
        try { (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) { }
    }

    /** Pantalla apagada: se deja de medir. Al encenderla, se sigue (si está activado). */
    fun pausar() { trabajo?.removeCallbacksAndMessages(null) }
    fun seguir() { trabajo?.let { it.removeCallbacksAndMessages(null); it.post(medir) } }

    private val medir = object : Runnable {
        override fun run() {
            val v = vista ?: return
            val ctx = v.context
            val partes = mutableListOf<String>()
            procesador(ctx)?.let { partes += "CPU $it %" }
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val mb = 1024L * 1024
            partes += "RAM ${(mi.totalMem - mi.availMem) / mb} de ${mi.totalMem / mb} MB"
            try {
                val sf = StatFs(Environment.getDataDirectory().path)
                partes += "Disco ${gb(sf.availableBytes)} libres de ${gb(sf.totalBytes)}"
            } catch (e: Exception) { }
            val texto = partes.joinToString("   ·   ")
            principal.post { vista?.text = texto }
            trabajo?.postDelayed(this, CADA_MS)
        }
    }

    private fun gb(b: Long) = String.format(java.util.Locale("es", "ES"), "%.1f GB", b / 1e9)

    /** Memoria disponible y disco libre, en bytes. */
    fun memoriaLibre(ctx: Context): Long =
        ActivityManager.MemoryInfo().also { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }.availMem
    fun discoLibre(): Long = try { StatFs(Environment.getDataDirectory().path).availableBytes } catch (e: Exception) { 0L }

    /**
     * «Liberar»: borra la caché de todas las apps y cierra las que están en segundo plano (por adb). Android las
     * vuelve a abrir y a llenar con el uso: es para cuando la TV va lenta, no para hacerlo a menudo.
     * [listo] vuelve en el hilo principal con el texto del resultado.
     */
    fun liberar(ctx: Context, listo: (String) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val mem = memoriaLibre(app); val disco = discoLibre()
            val dormir = if (Ajustes(app).dormirMin >= 0) Encendido.DORMIDAS.joinToString("") { "; am force-stop $it" } else ""
            val r = Adb.shell(app, "pm trim-caches 999G; am kill-all$dormir")
            Thread.sleep(1500)                                // Android tarda un poco en dar la memoria por libre
            val texto = if (r == null) "Hace falta la depuración: actívala en Permisos avanzados"
            else {
                val mb = 1024L * 1024
                val dCache = ((discoLibre() - disco) / mb).coerceAtLeast(0)
                val dMem = ((memoriaLibre(app) - mem) / mb).coerceAtLeast(0)
                "Liberados $dCache MB de caché y $dMem MB de memoria"
            }
            Log.i(TAG, "liberar: $texto")
            principal.post { listo(texto) }
        }.start()
    }

    /** Sin depuración no se insiste: un intento por minuto (abrir la conexión cuesta). */
    private var sinAdbHasta = 0L
    private fun porAdb(ctx: Context): String? {
        if (android.os.SystemClock.elapsedRealtime() < sinAdbHasta) return null
        val r = Adb.shell(ctx, Adb.PROC_STAT)?.lineSequence()?.firstOrNull { it.startsWith("cpu ") }
        if (r == null) sinAdbHasta = android.os.SystemClock.elapsedRealtime() + 60_000
        return r
    }

    /** % de uso de todo el procesador desde la medida anterior; null si no se puede leer o es la primera. */
    private fun procesador(ctx: Context): Int? {
        val linea = try { java.io.File("/proc/stat").bufferedReader().use { it.readLine() } } catch (e: Exception) { null }
            ?: porAdb(ctx)
            ?: return null
        val n = linea.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (n.size < 4) return null
        val libre = n[3] + (n.getOrNull(4) ?: 0)              // idle + iowait
        val total = n.sum()
        val r = if (totalAntes > 0 && total > totalAntes)
            (100 * (1 - (libre - libreAntes).toDouble() / (total - totalAntes))).toInt().coerceIn(0, 100) else null
        totalAntes = total; libreAntes = libre
        return r
    }
}
