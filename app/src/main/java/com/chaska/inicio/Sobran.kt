package com.chaska.inicio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject

/**
 * «Apps que sobran»: apps del fabricante o de Google que funcionan sin que el usuario las use (anuncios, canales
 * FAST, menús de fábrica, estadísticas). Vale para cualquier Android TV: solo se enseñan las que hay en esa TV.
 * El usuario elige cuáles desactivar; Stark TV las vuelve a desactivar en cada arranque (algunas TVs las
 * reactivan solas) y se pueden volver a activar todas. Necesita la depuración (adb).
 *
 * La lista (assets/sobran.json) junta:
 * - Las de Stark TV, medidas en TVs reales, con su explicación.
 * - Las «recomendadas» de Universal Android Debloater Next Generation (UAD-ng, GPL-3.0).
 * - Las de Projectivy Launcher (Xiaomi) que no estaban en UAD-ng.
 * Nunca salen las que se abren desde el inicio (YouTube, Netflix…): son apps del usuario.
 */
object Sobran {
    private const val TAG = "InicioTV"
    private val principal = Handler(Looper.getMainLooper())

    class Paquete(val paquete: String, val explicacion: String?, val origen: String)

    /** Lo que se puede desactivar en esta TV y cómo está ahora. */
    class Candidata(val p: Paquete, val nombre: String, var desactivada: Boolean)

    private var cache: List<Paquete>? = null

    private fun lista(ctx: Context): List<Paquete> = cache ?: try {
        val j = JSONObject(ctx.assets.open("sobran.json").bufferedReader().use { it.readText() })
        val fuentes = j.getJSONObject("fuentes")
        val arr = j.getJSONArray("paquetes")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Paquete(o.getString("p"), o.optString("d").ifBlank { null }, fuentes.optString(o.optString("o"), ""))
        }.filter { Adb.esPaquete(it.paquete) }
    } catch (e: Exception) {
        Log.w(TAG, "sobran: no se pudo leer la lista: $e"); emptyList()
    }.also { cache = it }

    /** Las que el usuario ha elegido desactivar (se repiten en cada arranque). */
    fun elegidas(aj: Ajustes): Set<String> {
        val s = aj.quitarPaquetes.split(',').filter { Adb.esPaquete(it) }.toMutableSet()
        if (aj.quitarFast) s += Adb.FAST                  // la opción antigua de los canales FAST
        return s
    }

    private fun guardar(aj: Ajustes, s: Set<String>) {
        aj.quitarPaquetes = s.sorted().joinToString(",")
        if (Adb.FAST !in s) aj.quitarFast = false
    }

    /** Busca (por adb, en otro hilo) qué apps de la lista hay en esta TV. null en [listo] = sin depuración. */
    fun buscar(ctx: Context, listo: (List<Candidata>?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val todas = Adb.shell(app, "pm list packages")
            val apagadas = Adb.shell(app, "pm list packages -d")
            if (todas == null || apagadas == null) { principal.post { listo(null) }; return@Thread }
            fun paquetes(t: String) = t.lineSequence().map { it.trim().removePrefix("package:") }.filter { it.isNotBlank() }.toSet()
            val hay = paquetes(todas)
            val off = paquetes(apagadas)
            val pm = app.packageManager
            val res = lista(app).filter { it.paquete in hay }
                // Las que se abren desde el inicio son apps del usuario (YouTube, Netflix…): nunca salen aquí.
                .filter { pm.getLeanbackLaunchIntentForPackage(it.paquete) == null && pm.getLaunchIntentForPackage(it.paquete) == null }
                .map { Candidata(it, nombre(app, it.paquete), it.paquete in off) }
                .sortedWith(compareBy({ it.p.origen != "Stark TV" }, { it.nombre.lowercase() }))
            // Las de la lista que ya estaban desactivadas (a mano o por adb) se quedan como elegidas: así siguen
            // desactivadas tras reiniciar aunque la TV las reactive.
            val aj = Ajustes(app)
            val ya = res.filter { it.desactivada }.map { it.p.paquete }
            if (!elegidas(aj).containsAll(ya)) guardar(aj, elegidas(aj) + ya)
            principal.post { listo(res) }
        }.start()
    }

    private fun nombre(ctx: Context, p: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationInfo(p, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS).loadLabel(pm).toString()
            .takeIf { it.isNotBlank() && it != p } ?: p
    } catch (e: Exception) { p }

    /** Desactiva o vuelve a activar una; [listo] dice si la TV lo aceptó. */
    fun cambiar(ctx: Context, aj: Ajustes, p: String, desactivar: Boolean, listo: (Boolean) -> Unit) {
        if (!Adb.esPaquete(p)) return listo(false)
        val orden = if (desactivar) "pm disable-user --user 0 $p" else "pm enable $p"
        Adb.enSegundoPlano(ctx, orden) { r ->
            val ok = r != null && (r.contains("disabled") || r.contains("enabled"))
            if (ok) guardar(aj, if (desactivar) elegidas(aj) + p else elegidas(aj) - p)
            Log.i(TAG, "sobran: $p ${if (desactivar) "desactivada" else "activada"} $ok")
            listo(ok)
        }
    }

    /** Vuelve a activar todas las que se desactivaron desde aquí. */
    fun activarTodas(ctx: Context, aj: Ajustes, listo: (Boolean) -> Unit) {
        val s = elegidas(aj)
        if (s.isEmpty()) return listo(true)
        Adb.enSegundoPlano(ctx, s.joinToString("; ") { "pm enable $it" }) { r ->
            if (r != null) guardar(aj, emptySet())
            listo(r != null)
        }
    }

    /** Órdenes para el arranque (Adb.reponerAjustes): se vuelven a desactivar las elegidas. */
    fun ordenesArranque(aj: Ajustes): List<String> = elegidas(aj).sorted().map { "pm disable-user --user 0 $it" }

    @Volatile private var ultimoRepaso = 0L
    @Volatile private var repasando = false

    /**
     * Vuelve a desactivar las del registro que estén funcionando. Algunas vuelven solas: al encender la TV o
     * cuando Google Play las actualiza (FAST, 02/11). Tras encender, la depuración tarda en estar lista: se
     * reintenta durante un minuto. [siempre] = sin esperar los 10 minutos entre repasos.
     */
    fun repasar(ctx: Context, siempre: Boolean = false) {
        val app = ctx.applicationContext
        val aj = Ajustes(app)
        val s = elegidas(aj)
        val ahora = android.os.SystemClock.elapsedRealtime()
        if (s.isEmpty() || repasando || (!siempre && ahora - ultimoRepaso < 10 * 60_000L && ultimoRepaso != 0L)) return
        repasando = true
        Thread {
            try {
                var activas: String? = null
                for (i in 0 until 12) {
                    activas = Adb.shell(app, "pm list packages -e")
                    if (activas != null) break
                    Thread.sleep(5000)
                }
                if (activas == null) { Log.i(TAG, "sobran: sin depuración, no se puede repasar"); return@Thread }
                val on = activas.lineSequence().map { it.trim().removePrefix("package:") }.toSet()
                val volver = s.filter { it in on }
                if (volver.isNotEmpty()) {
                    Adb.shell(app, volver.joinToString("; ") { "pm disable-user --user 0 $it" })
                    Log.i(TAG, "sobran: vueltas a desactivar ${volver.joinToString()}")
                }
                ultimoRepaso = android.os.SystemClock.elapsedRealtime()
            } finally { repasando = false }
        }.start()
    }
}
