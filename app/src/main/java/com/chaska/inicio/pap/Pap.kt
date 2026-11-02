package com.chaska.inicio.pap

import com.chaska.inicio.*

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
    /** youtube.com/tv en una ventana de Stark TV (ver [VisorWeb]). */
    const val YOUTUBE_WEB = "web:youtube"
    private const val MODO_PANTALLA_COMPLETA = 1
    private const val MODO_VENTANA = 5

    val REPARTOS = listOf("Mitad y mitad", "Grande y pequeña", "Pequeña y grande", "Arriba y abajo",
        "PIP abajo derecha", "PIP abajo izquierda", "PIP arriba izquierda", "PIP arriba derecha")

    /** 4-7: PIP. La parte 1 a pantalla completa y la 2 en una ventana pequeña en la esquina. */
    fun esPip(reparto: Int) = reparto >= 4

    /** Lo que se puede poner en una parte: las fuentes de TV, YouTube (web) y las apps que no están ocultas en el dock. */
    fun opciones(ctx: Context): List<Pair<String, String>> {
        val ocultas = ListaApps(ctx, Ajustes(ctx).p).ocultas()
        return Ajustes.FUENTES_TV.mapIndexed { i, n -> "$TV$i" to n } + listOf(YOUTUBE_WEB to "YouTube (web)") +
            Apps.todas(ctx).filter { it !in ocultas }.map { it to Apps.nombre(ctx, it) }.sortedBy { it.second.lowercase() }
    }

    fun nombre(ctx: Context, clave: String): String =
        if (clave == YOUTUBE_WEB) "YouTube (web)"
        else if (clave.startsWith(TV)) Ajustes.FUENTES_TV.getOrElse(clave.removePrefix(TV).toIntOrNull() ?: 0) { "TV" }
        else Apps.nombre(ctx, clave)

    /** Límites de la parte 1 (izquierda o arriba) y la parte 2 según el reparto. */
    fun limites(ctx: Context, reparto: Int): Pair<Rect, Rect> {
        val m = ctx.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
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
    fun lanzar(act: Context, aj: Ajustes, manejar: Int = 1, nuevo: Boolean = false, cambio: Boolean = false) {
        // Primero, por adb, que cada app del PAP vea su parte como la pantalla entera (en otro hilo:
        // activarlo cierra la app, así que va antes de abrirla). Sin adb se abre igual.
        val apps = listOf(aj.papIzq, aj.papDer).filter { !it.startsWith(TV) && Adb.esPaquete(it) }
        val principal = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            if (ENCERRAR_ACTIVO) encerrarPantalla(act.applicationContext, apps)
            principal.post { abrirPartes(act, aj, manejar, nuevo, cambio) }
        }.start()
    }

    /** Cambio de Android que hace que una app vea su ventana como la pantalla (tamaño máximo, Display). */
    private const val ENCERRAR = "ALWAYS_SANDBOX_DISPLAY_APIS"
    /** Apagado (02/11): en prueba por si impedía dos vídeos a la vez en el PAP. */
    private const val ENCERRAR_ACTIVO = false

    /**
     * Una app en una parte del PAP pregunta a Android el tamaño de la pantalla y le dice el de la TV entera
     * (1280×720): se dibuja para ese tamaño y sale cortada o descolocada. Con [ENCERRAR] activado, a esa app
     * Android le contesta con el tamaño de su parte (50 %, 70-30 o PIP). A pantalla completa no cambia nada,
     * así que se deja puesto. Solo se activa en las que aún no lo tienen (activarlo cierra la app).
     */
    private fun encerrarPantalla(ctx: Context, apps: List<String>) {
        if (apps.isEmpty()) return
        val estado = Adb.shell(ctx, "dumpsys platform_compat") ?: return
        val linea = estado.lineSequence().firstOrNull { it.contains("name=$ENCERRAR;") } ?: return
        for (app in apps.distinct()) {
            if (linea.contains("$app=true")) continue
            Adb.shell(ctx, "am compat enable $ENCERRAR $app")
            Log.i(TAG, "PAP: $app verá su parte como la pantalla entera")
        }
    }

    private fun abrirPartes(act: Context, aj: Ajustes, manejar: Int, nuevo: Boolean, cambio: Boolean) {
        val (r1, r2) = limites(act, aj.papReparto)
        // Android corre 80 px una ventana libre si cae en el sitio exacto de otra (aunque esté oculta,
        // p. ej. de un PAP anterior). Al empezar de cero se cierran nuestros visores y se desplaza 0-2 px.
        // Al cambiar lo que va en una parte, la nueva ventana caería donde la vieja: también se desplaza.
        if (nuevo) { Visor.abiertos.toList().forEach { it.finishAndRemoveTask() }; VisorWeb.abiertos.toList().forEach { it.finishAndRemoveTask() } }
        if (nuevo || cambio) aj.p.edit().putInt("pap_n", (aj.p.getInt("pap_n", 0) + 1) % 3).apply()
        val d = aj.p.getInt("pap_n", 0)
        r1.left += d; r2.left += d
        val partes = listOf(Triple(aj.papIzq, r1, 1), Triple(aj.papDer, r2, 2))
        // Debajo de todo, el fondo negro del PAP (a pantalla completa): tapa el launcher.
        try {
            act.startActivity(Intent(act, FondoPap::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opciones(null, MODO_PANTALLA_COMPLETA))
        } catch (e: Exception) { Log.w(TAG, "PAP: sin fondo: $e") }
        // En PIP la ventana pequeña (parte 2) se abre la última para que quede encima.
        val orden = if (manejar == 1 && !esPip(aj.papReparto)) listOf(1, 0) else listOf(0, 1)
        // La misma app en las dos partes (ajuste guardado de antes): solo se coloca en la primera.
        val objetivos = listOf(busqueda(aj.papIzq, 1) to Rect(r1), busqueda(aj.papDer, 2) to Rect(r2)).distinctBy { it.first }
        aj.papActivo = true
        aj.papParte = manejar
        lanzadoEnEsteProceso = true
        for (i in orden) {
            val (clave, rect, parte) = partes[i]
            val intent = when {
                clave.startsWith(TV) -> Intent(act, if (parte == 1) VisorIzq::class.java else VisorDer::class.java)
                    .putExtra(Visor.EXTRA_FUENTE, clave.removePrefix(TV).toIntOrNull() ?: 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                clave == YOUTUBE_WEB -> Intent(act, if (parte == 1) VisorWebIzq::class.java else VisorWebDer::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                else -> Apps.intentDe(act, clave) ?: continue
            }
            try { act.startActivity(intent, opciones(rect, MODO_VENTANA)) }
            catch (e: Exception) { Log.w(TAG, "PAP: no se pudo abrir $clave: $e") }
        }
        colocar(act.applicationContext, aj, objetivos, manejar)
        aplicarSonido(act.applicationContext, aj)
        if (nuevo && aj.papAviso > 0) avisar(act.applicationContext, aj.papAviso)
    }

    @Volatile private var tanda = 0

    /** Solo vale el PAP empezado desde Stark TV en este arranque. Tras reinstalar, Android reabre las
     *  ventanas guardadas del PAP por su cuenta: si este valor es false, se cierran solas. */
    @Volatile var lanzadoEnEsteProceso = false

    /**
     * Las apps se abren a su manera (trampolines como YouTube, pantallas de bienvenida como TDTChannels)
     * y Android las pone donde quiere. Durante 30 s, cada segundo, se mira por adb dónde está cada parte
     * y se mueve a su sitio. El selector es una capa por encima de todo: no hace falta subirlo.
     */
    private fun colocar(ctx: Context, aj: Ajustes, objetivos: List<Pair<Pair<String, Boolean>, Rect>>, manejar: Int) {
        val mia = ++tanda
        val principal = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val fin = android.os.SystemClock.uptimeMillis() + 30_000
            var activaDelante = false
            while (android.os.SystemClock.uptimeMillis() < fin && mia == tanda && aj.papActivo) {
                // Sin depuración no hace falta: las partes ya se abren con su tamaño. Si la hay (o tarda en estar
                // lista tras encender la TV), se reintenta y se recoloca la app que se haya ido a otro sitio.
                val lista = Adb.tareas(ctx)
                if (lista == null) { Thread.sleep(1000); continue }
                for ((busca, rect) in objetivos) {
                    val (afinidad, exacta) = busca
                    lista.filter { it.visible && it.modo == "freeform" && (if (exacta) it.afinidad == afinidad else it.afinidad.startsWith(afinidad)) }
                        .forEach { t ->
                            if (t.limites != rect) { Adb.mover(ctx, t.id, rect); Log.i(TAG, "PAP: ${t.afinidad} a $rect") }
                        }
                }
                // Cuando ya están las dos, la parte que se maneja se pone delante una vez (por si una app con
                // trampolín, como YouTube, se coló por encima y se quedó con el mando).
                val todas = objetivos.all { (b, _) -> buscar(lista, b) != null }
                if (todas && !activaDelante && !(esPip(aj.papReparto) && manejar == 1)) {
                    activaDelante = true
                    buscar(lista, (objetivos.getOrNull(manejar - 1) ?: objetivos.first()).first)?.let { t -> principal.post { alFrente(ctx, t.id) } }
                }
                Thread.sleep(1000)
            }
        }.start()
    }

    /**
     * Si Android cerró Stark TV con el PAP abierto (bloqueo, apagar y encender la TV), las apps del PAP
     * se quedan en ventanas flotantes donde caigan y ya nadie las coloca. Al arrancar el launcher se
     * quitan esas ventanas, para que la próxima vez se abran a pantalla completa o en un PAP nuevo.
     * Nuestros visores y el fondo se cierran solos (ver [lanzadoEnEsteProceso]).
     */
    fun recoger(ctx: Context) {
        if (lanzadoEnEsteProceso) return
        val app = ctx.applicationContext
        val aj = Ajustes(app)
        Thread {
            val fin = android.os.SystemClock.uptimeMillis() + 20_000
            var lista: List<Adb.Tarea>? = null
            while (lista == null && android.os.SystemClock.uptimeMillis() < fin && !lanzadoEnEsteProceso) {
                lista = Adb.tareas(app)
                if (lista == null) Thread.sleep(1000)
            }
            if (lista == null || lanzadoEnEsteProceso) return@Thread
            if (!lanzadoEnEsteProceso) aj.papActivo = false
            lista.filter { it.modo == "freeform" && !it.afinidad.startsWith(app.packageName) }.forEach { t ->
                if (lanzadoEnEsteProceso) return@Thread          // se empezó un PAP mientras tanto: no se toca
                Adb.shell(app, "am stack remove ${t.id}")
                Log.i(TAG, "PAP: quitada la ventana suelta de ${t.afinidad}")
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
            else if (Adb.esPaquete(clave)) Adb.enSegundoPlano(ctx, "appops set $clave PLAY_AUDIO ${if (si) "allow" else "ignore"}")
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
    fun busqueda(clave: String, parte: Int): Pair<String, Boolean> = when {
        clave.startsWith(TV) -> (if (parte == 1) "com.chaska.inicio.pap1" else "com.chaska.inicio.pap2") to true
        clave == YOUTUBE_WEB -> (if (parte == 1) "com.chaska.inicio.papweb1" else "com.chaska.inicio.papweb2") to true
        else -> clave to false
    }

    /** La tarea visible de más arriba que corresponde a esa búsqueda. */
    fun buscar(lista: List<Adb.Tarea>, b: Pair<String, Boolean>): Adb.Tarea? =
        lista.firstOrNull { it.visible && it.modo == "freeform" && (if (b.second) it.afinidad == b.first else it.afinidad.startsWith(b.first)) }

    /**
     * Sin adb: pone delante una parte volviendo a abrirla (la app o el visor ya abiertos solo pasan delante, con
     * el mando). No se cierra ni se recoloca nada más.
     */
    fun traerParte(ctx: Context, aj: Ajustes, parte: Int) {
        val clave = if (parte == 1) aj.papIzq else aj.papDer
        val intent = when {
            clave.startsWith(TV) -> Intent(ctx, if (parte == 1) VisorIzq::class.java else VisorDer::class.java)
                .putExtra(Visor.EXTRA_FUENTE, clave.removePrefix(TV).toIntOrNull() ?: 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            clave == YOUTUBE_WEB -> Intent(ctx, if (parte == 1) VisorWebIzq::class.java else VisorWebDer::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            else -> Apps.intentDe(ctx, clave) ?: return
        }
        try { ctx.startActivity(intent) } catch (e: Exception) { Log.w(TAG, "PAP: no se pudo poner delante $clave: $e") }
    }

    fun alFrente(ctx: Context, id: Int) = try {
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).moveTaskToFront(id, 0)
    } catch (e: Exception) { Log.w(TAG, "PAP: no se pudo poner delante $id: $e") }

    /**
     * El PAP está en marcha de verdad y en pantalla. No basta el ajuste (tras reinstalar se queda «activo» sin
     * ventanas) ni que exista el fondo: si se cerraron las partes o se volvió al inicio, el fondo queda escondido
     * y al pulsar PAP salía solo el selector encima del launcher (02/11). Así se empieza uno nuevo.
     */
    fun activo(aj: Ajustes) = aj.papActivo && FondoPap.actual != null && FondoPap.visible

    /** Lo único que llama el launcher: si el PAP está en marcha abre el selector; si no, lo empieza. */
    fun empezar(act: Context) {
        val aj = Ajustes(act)
        if (activo(aj)) { selector(act); return }
        aj.papActivo = false
        lanzar(act, aj, 1, nuevo = true)
    }

    fun selector(ctx: Context) {
        if (!SelectorPap.abrir()) android.widget.Toast.makeText(ctx.applicationContext,
            "PAP: falta el servicio de la tuerca de Stark TV (Accesibilidad)", android.widget.Toast.LENGTH_LONG).show()
    }

    /**
     * Cierra el PAP del todo: las ventanas de las apps de cada parte (por adb), los visores de TV,
     * el fondo y el selector; y vuelve al launcher. Así la próxima vez esas apps se abren a pantalla completa.
     */
    fun cerrar(ctx: Context, aj: Ajustes, irAlInicio: Boolean = true) {
        aj.papActivo = false
        tanda++                                       // para el colocador si seguía en marcha
        val apps = listOf(aj.papIzq, aj.papDer).filter { !it.startsWith(TV) && Adb.esPaquete(it) }
        Visor.abiertos.toList().forEach { it.finishAndRemoveTask() }
        VisorWeb.abiertos.toList().forEach { it.finishAndRemoveTask() }
        FondoPap.actual?.finishAndRemoveTask()
        SelectorPap.actual?.cerrar()
        val app = ctx.applicationContext
        apps.forEach { Adb.enSegundoPlano(app, "appops set $it PLAY_AUDIO allow") }      // devolverles el sonido
        // Las apps del PAP se cierran de verdad: si no, seguían sonando detrás del inicio (TDTChannels, 02/11).
        // Con adb, se quitan sus ventanas y se paran; sin adb, Android deja cerrar las que ya están en segundo plano.
        Thread {
            val con = Adb.tareas(app)
            con?.filter { t -> t.modo == "freeform" && apps.any { t.afinidad.startsWith(it) } }
                ?.forEach { Adb.shell(app, "am stack remove ${it.id}") }
            if (con != null) apps.forEach { Adb.shell(app, "am force-stop $it") }
            else {
                Thread.sleep(1500)
                val am = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                apps.forEach { try { am.killBackgroundProcesses(it) } catch (e: Exception) { Log.w(TAG, "PAP: no se pudo cerrar $it: $e") } }
            }
        }.start()
        if (irAlInicio) ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
