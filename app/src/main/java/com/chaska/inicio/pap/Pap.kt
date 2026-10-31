package com.chaska.inicio.pap

import com.chaska.inicio.*

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log

/**
 * PAP: dos cosas a la vez, cada una en su parte de la pantalla (app o fuente de TV).
 * Usa las ventanas libres de Android (en esta Thomson se activan con force_resizable_activities y
 * enable_freeform_support por adb). Cada parte se abre como tarea propia con sus límites; la que se
 * abre la última es la que maneja el mando.
 */
object Pap {
    private const val TV = "tv:"
    private const val MODO_PANTALLA_COMPLETA = 1
    private const val MODO_VENTANA = 5

    val REPARTOS = listOf("Mitad y mitad", "Grande y pequeña", "Pequeña y grande", "Arriba y abajo",
        "PIP abajo derecha", "PIP abajo izquierda", "PIP arriba izquierda", "PIP arriba derecha")

    /** 4-7: PIP. La parte 1 a pantalla completa y la 2 en una ventana pequeña en la esquina. */
    fun esPip(reparto: Int) = reparto >= 4

    /** Lo que se puede poner en una parte: las fuentes de TV y todas las apps. */
    fun opciones(ctx: Context): List<Pair<String, String>> =
        Ajustes.FUENTES_TV.mapIndexed { i, n -> "$TV$i" to n } +
            Apps.todas(ctx).map { it to Apps.nombre(ctx, it) }.sortedBy { it.second.lowercase() }

    fun nombre(ctx: Context, clave: String): String =
        if (clave.startsWith(TV)) Ajustes.FUENTES_TV.getOrElse(clave.removePrefix(TV).toIntOrNull() ?: 0) { "TV" }
        else Apps.nombre(ctx, clave)

    /** Límites de la parte 1 (izquierda o arriba) y la parte 2 según el reparto. */
    fun limites(act: Activity, reparto: Int): Pair<Rect, Rect> {
        val m = act.windowManager.maximumWindowMetrics.bounds
        val w = m.width(); val h = m.height()
        return when (reparto) {
            1 -> Rect(0, 0, w * 7 / 10, h) to Rect(w * 7 / 10, 0, w, h)
            2 -> Rect(0, 0, w * 3 / 10, h) to Rect(w * 3 / 10, 0, w, h)
            3 -> Rect(0, 0, w, h / 2) to Rect(0, h / 2, w, h)
            in 4..7 -> {
                val pw = w * 32 / 100; val ph = pw * 9 / 16; val m = w / 40
                val izq = reparto == 5 || reparto == 6
                val arriba = reparto == 6 || reparto == 7
                val x = if (izq) m else w - pw - m
                val y = if (arriba) m else h - ph - m
                Rect(0, 0, w, h) to Rect(x, y, x + pw, y + ph)
            }
            else -> Rect(0, 0, w / 2, h) to Rect(w / 2, 0, w, h)
        }
    }

    /**
     * Abre (o trae delante) las dos partes. [manejar] 1 o 2 es la que queda con el mando:
     * se abre la otra primero y la elegida al final.
     */
    fun lanzar(act: Activity, aj: Ajustes, manejar: Int = 1, nuevo: Boolean = false, cambio: Boolean = false) {
        val (r1, r2) = limites(act, aj.papReparto)
        // Android corre 80 px una ventana libre si cae en el sitio exacto de otra (aunque esté oculta,
        // p. ej. de un PAP anterior). Al empezar de cero se cierran nuestros visores y se desplaza 0-2 px.
        // Al cambiar lo que va en una parte, la nueva ventana caería donde la vieja: también se desplaza.
        if (nuevo) Visor.abiertos.toList().forEach { it.finishAndRemoveTask() }
        if (nuevo || cambio) aj.p.edit().putInt("pap_n", (aj.p.getInt("pap_n", 0) + 1) % 3).apply()
        val d = aj.p.getInt("pap_n", 0)
        r1.left += d; r2.left += d
        val partes = listOf(Triple(aj.papIzq, r1, VisorIzq::class.java), Triple(aj.papDer, r2, VisorDer::class.java))
        // Debajo de todo, el fondo negro del PAP (a pantalla completa): tapa el launcher.
        try {
            act.startActivity(Intent(act, FondoPap::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opciones(null, MODO_PANTALLA_COMPLETA))
        } catch (e: Exception) { Log.w(TAG, "PAP: sin fondo: $e") }
        // En PIP la ventana pequeña (parte 2) se abre la última para que quede encima.
        val orden = if (manejar == 1 && !esPip(aj.papReparto)) listOf(1, 0) else listOf(0, 1)
        val objetivos = listOf(busqueda(aj.papIzq, 1) to Rect(r1), busqueda(aj.papDer, 2) to Rect(r2))
        aj.papActivo = true
        aj.papParte = manejar
        for (i in orden) {
            val (clave, rect, visor) = partes[i]
            val intent = if (clave.startsWith(TV))
                Intent(act, visor).putExtra(Visor.EXTRA_FUENTE, clave.removePrefix(TV).toIntOrNull() ?: 0)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            else Apps.intentDe(act, clave) ?: continue
            try { act.startActivity(intent, opciones(rect, MODO_VENTANA)) }
            catch (e: Exception) { Log.w(TAG, "PAP: no se pudo abrir $clave: $e") }
        }
        colocar(act.applicationContext, aj, objetivos, manejar)
        aplicarSonido(act.applicationContext, aj)
        if (nuevo && aj.papAviso > 0) avisar(act.applicationContext, aj.papAviso)
    }

    @Volatile private var tanda = 0

    /**
     * Las apps se abren a su manera (trampolines como YouTube, pantallas de bienvenida como TDTChannels)
     * y Android las pone donde quiere. Durante 30 s, cada segundo, se mira por adb dónde está cada parte
     * y se mueve a su sitio; si el selector está abierto, se vuelve a poner encima.
     */
    private fun colocar(ctx: Context, aj: Ajustes, objetivos: List<Pair<Pair<String, Boolean>, Rect>>, manejar: Int) {
        val mia = ++tanda
        val principal = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val fin = android.os.SystemClock.uptimeMillis() + 30_000
            var activaDelante = false
            while (android.os.SystemClock.uptimeMillis() < fin && mia == tanda && aj.papActivo) {
                val lista = Adb.tareas(ctx) ?: break             // sin adb no se puede: se deja como está
                var movida = false
                for ((busca, rect) in objetivos) {
                    val (afinidad, exacta) = busca
                    lista.filter { it.visible && it.modo == "freeform" && (if (exacta) it.afinidad == afinidad else it.afinidad.startsWith(afinidad)) }
                        .forEach { t ->
                            if (t.limites != rect) { Adb.mover(ctx, t.id, rect); movida = true; Log.i(TAG, "PAP: ${t.afinidad} a $rect") }
                        }
                }
                // Cuando ya están las dos, la parte que se maneja se pone delante una vez (por si una app con
                // trampolín, como YouTube, se coló por encima y se quedó con el mando).
                val todas = objetivos.all { (b, _) -> buscar(lista, b) != null }
                if (todas && !activaDelante && !(esPip(aj.papReparto) && manejar == 1)) {
                    activaDelante = true
                    buscar(lista, objetivos[manejar - 1].first)?.let { t -> principal.post { alFrente(ctx, t.id) } }
                }
                if (movida && SelectorPap.visible) principal.post {
                    ctx.startActivity(Intent(ctx, SelectorPap::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), pantallaCompleta())
                }
                Thread.sleep(1000)
            }
        }.start()
    }

    val AVISOS = listOf(0, 3, 5)

    /** Qué parte suena: la 1, la 2, las dos o ninguna. Los nombres siguen al reparto. */
    fun sonidos(reparto: Int) = when {
        esPip(reparto) -> listOf("Grande", "Pequeña", "Las dos", "Apagado")
        reparto == 3 -> listOf("Arriba", "Abajo", "Las dos", "Apagado")
        else -> listOf("Izquierda", "Derecha", "Las dos", "Apagado")
    }

    fun suena(aj: Ajustes, parte: Int) = when (aj.papSonido.coerceIn(0, 3)) {
        0 -> parte == 1
        1 -> parte == 2
        2 -> true
        else -> false
    }

    /**
     * Qué parte suena. La TV (visor) se silencia ella misma; a las apps se les quita o devuelve por adb
     * el permiso de reproducir sonido (appops PLAY_AUDIO). Todo sale por la misma salida: altavoces o Bluetooth.
     */
    fun aplicarSonido(ctx: Context, aj: Ajustes) {
        for ((parte, clave) in listOf(1 to aj.papIzq, 2 to aj.papDer)) {
            val si = suena(aj, parte)
            if (clave.startsWith(TV)) Visor.abiertos.filter { it.parte == parte }.forEach { it.sonido(si) }
            else Adb.enSegundoPlano(ctx, "appops set $clave PLAY_AUDIO ${if (si) "allow" else "ignore"}")
        }
    }

    /** Al empezar: cómo se maneja el PAP. Android solo deja avisos de ~2 s o ~3,5 s: 3 s = uno largo, 5 s = largo + corto. */
    private fun avisar(ctx: Context, segundos: Int) {
        val texto = "PAP · Tuerca: cambiar de parte · Mantén la tuerca o pulsa la X: salir"
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        h.postDelayed({ android.widget.Toast.makeText(ctx, texto, android.widget.Toast.LENGTH_LONG).show() }, 1500)
        if (segundos >= 5) h.postDelayed({ android.widget.Toast.makeText(ctx, texto, android.widget.Toast.LENGTH_SHORT).show() }, 5000)
    }

    /** Qué tarea buscar para una parte: nuestro visor (afinidad exacta) o la app (afinidad que empieza por el paquete). */
    fun busqueda(clave: String, parte: Int): Pair<String, Boolean> =
        if (clave.startsWith(TV)) (if (parte == 1) "com.chaska.inicio.pap1" else "com.chaska.inicio.pap2") to true
        else clave to false

    /** La tarea visible de más arriba que corresponde a esa búsqueda. */
    fun buscar(lista: List<Adb.Tarea>, b: Pair<String, Boolean>): Adb.Tarea? =
        lista.firstOrNull { it.visible && it.modo == "freeform" && (if (b.second) it.afinidad == b.first else it.afinidad.startsWith(b.first)) }

    fun alFrente(ctx: Context, id: Int) = try {
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).moveTaskToFront(id, 0)
    } catch (e: Exception) { Log.w(TAG, "PAP: no se pudo poner delante $id: $e") }

    /** El PAP está en marcha de verdad (no basta el ajuste: tras reinstalar se queda «activo» sin ventanas). */
    fun activo(aj: Ajustes) = aj.papActivo && FondoPap.actual != null

    /** Lo único que llama el launcher: si el PAP está en marcha abre el selector; si no, lo empieza. */
    fun empezar(act: Activity) {
        val aj = Ajustes(act)
        if (activo(aj)) { selector(act); return }
        aj.papActivo = false
        lanzar(act, aj, 1, nuevo = true)
    }

    fun selector(ctx: Context) =
        ctx.startActivity(Intent(ctx, SelectorPap::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), pantallaCompleta())

    /**
     * Cierra el PAP del todo: las ventanas de las apps de cada parte (por adb), los visores de TV,
     * el fondo y el selector; y vuelve al launcher. Así la próxima vez esas apps se abren a pantalla completa.
     */
    fun cerrar(ctx: Context, aj: Ajustes) {
        aj.papActivo = false
        tanda++                                       // para el colocador si seguía en marcha
        val apps = listOf(aj.papIzq, aj.papDer).filter { !it.startsWith(TV) }
        Visor.abiertos.toList().forEach { it.finishAndRemoveTask() }
        FondoPap.actual?.finishAndRemoveTask()
        SelectorPap.actual?.finish()
        val app = ctx.applicationContext
        apps.forEach { Adb.enSegundoPlano(app, "appops set $it PLAY_AUDIO allow") }      // devolverles el sonido
        Thread {
            Adb.tareas(app)?.filter { t -> t.modo == "freeform" && apps.any { t.afinidad.startsWith(it) } }
                ?.forEach { Adb.shell(app, "am stack remove ${it.id}") }
        }.start()
        ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opciones para abrir una app normal: pantalla completa aunque la última vez estuviera en el PAP. */
    fun pantallaCompleta(): Bundle = opciones(null, MODO_PANTALLA_COMPLETA)

    private fun opciones(rect: Rect?, modo: Int): Bundle {
        val o = ActivityOptions.makeBasic()
        if (rect != null) o.launchBounds = rect
        try {
            ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(o, modo)
        } catch (e: Throwable) {
            Log.w(TAG, "setLaunchWindowingMode no disponible: $e")
        }
        return o.toBundle()
    }

    private const val TAG = "InicioTV"
}
