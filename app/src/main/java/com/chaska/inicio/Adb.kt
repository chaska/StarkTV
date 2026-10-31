package com.chaska.inicio

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Stark TV hablando con la depuración por red de la propia tele (127.0.0.1:5555), como adb desde el PC.
 * La primera vez la TV pregunta «¿Permitir depuración?»: hay que marcar «Permitir siempre».
 * Con esto Stark TV puede colocar las ventanas de otras apps y reponer ajustes tras reiniciar.
 * La llave va en la carpeta privada de la app (files/adbkey).
 */
object Adb {
    private const val TAG = "InicioTV"
    private var conexion: Dadb? = null
    private val principal = Handler(Looper.getMainLooper())

    /** true si la última orden llegó a la TV. */
    @Volatile var conectado = false
        private set

    /** Nombre de paquete de Android de verdad (com.ejemplo.app): lo único que se deja meter en una orden. */
    fun esPaquete(s: String) = s.length <= 150 && rePaquete.matches(s)
    private val rePaquete = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    /** Caracteres que usan nuestras órdenes; con cualquier otro (| & $ ` > ' " salto de línea…) no se ejecuta. */
    private val reOrden = Regex("^[A-Za-z0-9 ._;=-]+$")

    /** Ejecuta una orden de shell; null si no hay conexión o si la orden trae algo raro. Bloquea: no llamar en el hilo principal. */
    @Synchronized
    fun shell(ctx: Context, orden: String): String? = if (!reOrden.matches(orden)) {
        Log.w(TAG, "adb: orden rechazada por caracteres no permitidos")
        null
    } else try {
        val d = conexion ?: abrir(ctx).also { conexion = it }
        val r = d.shell(orden)
        conectado = true
        r.allOutput
    } catch (e: Throwable) {
        Log.w(TAG, "adb: $e")
        try { conexion?.close() } catch (x: Throwable) { }
        conexion = null
        conectado = false
        null
    }

    /** Lo mismo en otro hilo; [listo] vuelve en el hilo principal. */
    fun enSegundoPlano(ctx: Context, orden: String, listo: (String?) -> Unit = {}) {
        val app = ctx.applicationContext
        Thread { val r = shell(app, orden); principal.post { listo(r) } }.start()
    }

    private fun abrir(ctx: Context): Dadb {
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return Dadb.create("127.0.0.1", 5555, AdbKeyPair.read(priv, pub))
    }

    /** Ajustes que necesita Stark TV y que la TV pierde al reiniciar o al actualizar la app. */
    fun reponerAjustes(ctx: Context) = enSegundoPlano(ctx, listOf(
        "settings put global force_resizable_activities 1",
        "settings put global enable_freeform_support 1",
        "settings put global hidden_api_policy 1",
        "wm set-ignore-orientation-request true",
        "appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow",
        "pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS",
    ).joinToString("; ")) { r -> Log.i(TAG, "ajustes repuestos: ${r != null}") }

    /** Tarea de la lista de Android: número, afinidad (≈ paquete), si se ve, modo y límites. */
    class Tarea(val id: Int, val afinidad: String, val visible: Boolean, val modo: String, var limites: Rect? = null)

    private val reTarea = Regex("""\* Task\{[0-9a-f]+ #(\d+) type=standard A=\d+:([\w.]+) .*?visible=(\w+).*?mode=(\w+)""")
    private val reLimites = Regex("""mBounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)""")

    /** Tareas de arriba abajo (la primera es la que está delante). */
    fun tareas(ctx: Context): List<Tarea>? {
        val txt = shell(ctx, "dumpsys activity activities") ?: return null
        val vistas = LinkedHashMap<Int, Tarea>()
        var actual: Tarea? = null
        for (l in txt.lineSequence()) {
            val m = reTarea.find(l)
            if (m != null) {
                val id = m.groupValues[1].toInt()
                actual = vistas.getOrPut(id) { Tarea(id, m.groupValues[2], m.groupValues[3] == "true", m.groupValues[4]) }
                continue
            }
            val t = actual
            if (t != null && t.limites == null) reLimites.find(l)?.let { b ->
                val (a, c, d, e) = b.destructured
                t.limites = Rect(a.toInt(), c.toInt(), d.toInt(), e.toInt())
            }
        }
        return vistas.values.toList()
    }

    fun mover(ctx: Context, id: Int, r: Rect) = shell(ctx, "am task resize $id ${r.left} ${r.top} ${r.right} ${r.bottom}")
}
