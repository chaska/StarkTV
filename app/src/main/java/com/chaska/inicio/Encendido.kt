package com.chaska.inicio

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * Apagar y encender: el temporizador de apagado, el volumen máximo de noche y la app que se abre al encender.
 * Vive en el servicio Teclas (siempre en marcha con la TV encendida): no hace falta otra app ni la depuración.
 * Ideas de los módulos Sleep Timer, Quiet Hours y Open on wake de CleanYourTV (MIT).
 */
object Encendido {
    private const val TAG = "InicioTV"
    private const val K_FIN = "apagar_fin"          // hora (reloj de pared, ms) a la que se apaga; 0 = sin temporizador
    private const val K_MIN = "apagar_min"          // minutos elegidos, para enseñarlo en Ajustes

    val MINUTOS_APAGAR = listOf(0, 15, 30, 60, 90, 120)
    val TOPES_NOCHE = listOf(0, 10, 20, 30, 40, 50)  // % del volumen máximo; 0 = sin tope
    val HORAS_NOCHE = listOf("22:00 a 08:00", "20:00 a 07:00", "Todo el día")
    /** Clave de «abrir al encender» para la app de TV de la tele. */
    const val TV = "tv:antena"

    private val h = Handler(Looper.getMainLooper())
    /** En el último minuto del temporizador: OK lo cancela. */
    @Volatile var avisando = false
        private set

    // ---------- Temporizador de apagado ----------

    fun minutosElegidos(aj: Ajustes): Int = if (aj.p.getLong(K_FIN, 0) > System.currentTimeMillis()) aj.p.getInt(K_MIN, 0) else 0

    /** [minutos] 0 = quitar el temporizador. */
    fun apagarEn(ctx: Context, minutos: Int) {
        val aj = Ajustes(ctx)
        val fin = if (minutos > 0) System.currentTimeMillis() + minutos * 60_000L else 0L
        aj.p.edit().putLong(K_FIN, fin).putInt(K_MIN, minutos).apply()
        armar(ctx)
        Toast.makeText(ctx.applicationContext, if (minutos > 0) "La TV se apagará en ${texto(minutos)}" else "Temporizador quitado", Toast.LENGTH_SHORT).show()
    }

    fun texto(m: Int) = when {
        m == 0 -> "No"
        m < 60 -> "$m min"
        m % 60 == 0 -> "${m / 60} h"
        else -> "${m / 60} h ${m % 60} min"
    }

    private val aviso = Runnable {
        val s = Teclas.servicio ?: return@Runnable
        avisando = true
        Toast.makeText(s, "La TV se apaga en 1 minuto · pulsa OK para seguir viendo", Toast.LENGTH_LONG).show()
        h.postDelayed({ if (avisando) Toast.makeText(s, "La TV se apaga en 1 minuto · pulsa OK para seguir viendo", Toast.LENGTH_LONG).show() }, 4000)
    }

    private val apagar = Runnable {
        val s = Teclas.servicio ?: return@Runnable
        avisando = false
        Ajustes(s).p.edit().putLong(K_FIN, 0).apply()
        // Bloquear pantalla = reposo en Android TV (como el botón de encendido). Si la TV no lo hace, por adb.
        val ok = s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        Log.i(TAG, "temporizador: a reposo ($ok)")
        h.postDelayed({
            val pm = s.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isInteractive) Adb.enSegundoPlano(s, "input keyevent KEYCODE_SLEEP")
        }, 3000)
    }

    /** Programa (o reprograma tras reiniciar Stark TV) el aviso y el apagado. */
    fun armar(ctx: Context) {
        h.removeCallbacks(aviso); h.removeCallbacks(apagar); avisando = false
        val fin = Ajustes(ctx).p.getLong(K_FIN, 0)
        val falta = fin - System.currentTimeMillis()
        if (fin == 0L || falta <= 0) return
        if (falta > 60_000) h.postDelayed(aviso, falta - 60_000) else h.post(aviso)
        h.postDelayed(apagar, falta)
    }

    /** OK en el último minuto: se sigue viendo (el temporizador se quita). */
    fun seguirViendo(ctx: Context) {
        Ajustes(ctx).p.edit().putLong(K_FIN, 0).putInt(K_MIN, 0).apply()
        armar(ctx)
        Toast.makeText(ctx, "Apagado cancelado", Toast.LENGTH_SHORT).show()
    }

    // ---------- Volumen máximo de noche ----------

    private fun esNoche(aj: Ajustes): Boolean {
        val hora = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (aj.horaNoche) {
            1 -> hora >= 20 || hora < 7
            2 -> true
            else -> hora >= 22 || hora < 8
        }
    }

    /** Si es de noche y el volumen pasa del tope, se baja al tope (y se ve la barra de volumen). */
    fun limitar(ctx: Context) {
        val aj = Ajustes(ctx)
        if (aj.volNoche <= 0 || !esNoche(aj)) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val tope = (max * aj.volNoche + 50) / 100
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) > tope) try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, tope, AudioManager.FLAG_SHOW_UI)
            Log.i(TAG, "volumen de noche: bajado a $tope de $max")
        } catch (e: Exception) { Log.w(TAG, "volumen de noche: $e") }
    }

    // ---------- Apps dormidas ----------

    /**
     * Apps que gastan memoria en segundo plano sin hacer falta y que Android vuelve a abrir solo al usarlas.
     * Solo Play Store (dos procesos, ~70 MB en la Thomson): se abre al entrar en ella. Probado el 03/11: los
     * contactos los vuelven a abrir los servicios de Google en segundos, y el chip seguro (com.android.se) es
     * fijo del sistema y no se deja parar (se quita desactivándolo en «Apps que sobran»).
     * Se para al encender la TV y, al volver al inicio de Stark TV, pasados unos minutos. Por adb (am force-stop).
     */
    val DORMIDAS = listOf("com.android.vending")
    val MINUTOS_DORMIR = listOf(-1, 1, 5, 10)        // -1 = no se duermen
    @Volatile private var delante: String? = null

    private val dormir = Runnable {
        val s = Teclas.servicio ?: return@Runnable
        if (Ajustes(s).dormirMin < 0) return@Runnable
        val parar = DORMIDAS.filter { it != delante }
        Adb.enSegundoPlano(s, parar.joinToString("; ") { "am force-stop $it" }) { r ->
            Log.i(TAG, "apps dormidas: ${if (r != null) parar.joinToString() else "sin depuración"}")
        }
    }

    /** Desde Teclas: qué app está delante. En el inicio de Stark TV, se duermen pasados los minutos elegidos. */
    fun delante(ctx: Context, paquete: String) {
        if (paquete == "com.android.systemui") return          // la barra de volumen no cuenta
        delante = paquete
        h.removeCallbacks(dormir)
        val min = Ajustes(ctx).dormirMin
        if (min >= 0 && paquete == ctx.packageName) h.postDelayed(dormir, min.coerceAtLeast(1) * 60_000L)
    }

    // ---------- Al encender ----------

    private fun abrirAlEncender(ctx: Context) {
        val aj = Ajustes(ctx)
        val clave = aj.alEncender
        if (clave.isBlank() || aj.papActivo) return
        val intent = if (clave == TV)
            listOf("com.mediatek.wwtv.tvcenter", "com.google.android.tv", "com.android.tv").firstNotNullOfOrNull { Apps.intentDe(ctx, it) }
        else Apps.intentDe(ctx, clave)
        if (intent == null) { Log.i(TAG, "al encender: no está $clave"); return }
        try { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), com.chaska.inicio.pap.Pap.pantallaCompleta()) }
        catch (e: Exception) { Log.w(TAG, "al encender: $e") }
    }

    private val alEncender = Runnable { Teclas.servicio?.let { abrirAlEncender(it) } }

    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> {
                    limitar(c)
                    Monitor.seguir()
                    h.removeCallbacks(dormir); h.postDelayed(dormir, 20_000)
                    h.removeCallbacks(alEncender)
                    h.postDelayed(alEncender, 1500)                  // la TV tarda en dejar de enseñar su logo
                }
                Intent.ACTION_SCREEN_OFF -> { h.removeCallbacks(alEncender); Monitor.pausar() }
                else -> limitar(c)                                   // cambio de volumen o cada minuto
            }
        }
    }

    /** Desde Teclas.onServiceConnected. [recienEncendida]: la TV acaba de arrancar (no llega SCREEN_ON). */
    fun empezar(s: Context, recienEncendida: Boolean) {
        try {
            s.registerReceiver(receptor, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_TIME_TICK)
                addAction("android.media.VOLUME_CHANGED_ACTION")
            })
        } catch (e: Exception) { Log.w(TAG, "encendido: $e") }
        armar(s)
        limitar(s)
        if (recienEncendida) h.postDelayed(alEncender, 3000)
        h.postDelayed(dormir, if (recienEncendida) 60_000L else 20_000L)  // tras arrancar, Play Store se abre solo
    }

    fun parar(s: Context) {
        try { s.unregisterReceiver(receptor) } catch (e: Exception) { }
        h.removeCallbacks(aviso); h.removeCallbacks(apagar); h.removeCallbacks(alEncender); h.removeCallbacks(dormir)
    }
}
