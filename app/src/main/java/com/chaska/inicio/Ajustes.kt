package com.chaska.inicio

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Todos los ajustes del launcher, guardados en SharedPreferences "ajustes". */
class Ajustes(ctx: Context) {
    val p: SharedPreferences = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    var fondo by texto("fondo", "asset:humo.html")
    var brillo by entero("brillo", if (p.contains("oscurecer")) 100 - p.getInt("oscurecer", 20) else 80)
    var reloj by booleano("reloj", true)
    var tiempo by booleano("tiempo", true)
    var ciudad by texto("ciudad", "Madrid")
    var nombres by booleano("nombres", false)
    // 0 micro, 1 pequeño, 2 mediano, 3 grande (la 0.2 guardaba "tamano" sin micro: se corre uno)
    var tamano by entero("tam", if (p.contains("tamano")) p.getInt("tamano", 1) + 1 else 2)
    var fuente by entero("fuente", 1)
    var dock by entero("dock", 0)               // índice de POSICIONES
    var tvVentana by booleano("tv_ventana", false)
    var tvRotulo by booleano("tv_rotulo", true)
    var opacidad by entero("opacidad_menus", 80) // % de opacidad de los paneles de cristal (30-100)
    var oscuridad by entero("oscuridad_menus", 40) // % que los colores de los paneles se llevan hacia el negro (0-90)
    var verVersion by booleano("ver_version", true) // «Stark TV 0.9.x» abajo a la derecha del inicio  // nombre del canal o de la fuente sobre la imagen (ventanita y PAP)
    var tituloDock by booleano("titulo_dock", true)
    var quitarFast by booleano("quitar_fast", false)
    var quitarPaquetes by texto("quitar_paquetes", "")   // «Apps que sobran» desactivadas por el usuario (Sobran.kt)   // desactivar al encender los canales FAST con anuncios (solo con adb)
    var favoritosDock by booleano("favoritos_dock", true) // ficha «Favoritos» al principio del dock
    var dockOculto by booleano("dock_oculto", false)      // el dock solo sale al pulsar la casa
    var tvSonido by booleano("tv_sonido", false)
    var tvFuente by entero("tv_fuente", 0)      // índice de FUENTES_TV
    var tvTamano by entero("tv_tamano", 1)      // ventanita de TV: 0 = 0,5x, 1 = 1x, 2 = 2x (TAMANOS_TV)
    var tvPos by entero("tv_pos", 0)            // ventanita de TV: 0 automática, 1-4 según POSICIONES_TV
    var papReparto by entero("pap_reparto", 0)   // índice de Pap.REPARTOS
    var papIzq by texto("pap_izq", "tv:0")       // "tv:N" o paquete de la app
    var papDer by texto("pap_der", "com.google.android.youtube.tv")
    var papActivo by booleano("pap_activo", false)
    var papBoton by booleano("pap_boton", true)
    var papDock by booleano("pap_dock", false)
    var papParte by entero("pap_parte", 1)
    var papAviso by entero("pap_aviso", 5)       // segundos del aviso al empezar: 0, 3 o 5       // parte que se maneja (1 o 2)
    var papSonido by entero("pap_sonido", 0)     // 0 la que manejas, 1 parte 1, 2 parte 2, 3 las dos
    var relojPos by entero("reloj_pos", 0)      // 0 automático (lado libre), 1 izquierda, 2 derecha
    var rotarMin by entero("rotar_min", 0)      // 0 = no cambiar el fondo solo
    var salvaMin by entero("salva_min", 10)     // 0 = sin salvapantallas
    var color by entero("color", 0)
    var reflejo by booleano("reflejo", true)
    var cristal by booleano("cristal", true)
    var animaciones by booleano("animaciones", true)

    val colorSel: Int get() = COLORES[color.coerceIn(COLORES.indices)].second

    /** Copia de todo salvo la caché del tiempo, en JSON con el tipo de cada valor. */
    fun guardarCopia(f: File) {
        val o = JSONObject()
        for ((k, v) in p.all) {
            if (k.startsWith("geo_")) continue
            val e = when (v) {
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is String -> JSONObject().put("t", "s").put("v", v)
                else -> continue
            }
            o.put(k, e)
        }
        f.parentFile?.mkdirs()
        f.writeText(o.toString(2))
    }

    fun recuperarCopia(f: File) {
        // La copia vive en /sdcard, donde otras apps pueden escribir: se acota todo lo que entra.
        if (f.length() > 256 * 1024) return
        val o = JSONObject(f.readText())
        val ed = p.edit().clear()
        for (k in o.keys()) {
            if (!Regex("^[a-z0-9_]{1,40}$").matches(k)) continue
            val e = o.optJSONObject(k) ?: continue
            if (e.optString("t") == "s" && e.optString("v").length > 8000) continue
            when (e.getString("t")) {
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "s" -> ed.putString(k, e.getString("v"))
            }
        }
        ed.apply()
    }

    private fun booleano(k: String, d: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = p.getBoolean(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) = p.edit().putBoolean(k, value).apply()
    }

    private fun entero(k: String, d: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = p.getInt(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) = p.edit().putInt(k, value).apply()
    }

    private fun texto(k: String, d: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = p.getString(k, d) ?: d
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) = p.edit().putString(k, value).apply()
    }

    companion object {
        val COLORES = listOf(
            "Ámbar" to 0xFFF2A93B.toInt(),
            "Blanco" to 0xFFFFFFFF.toInt(),
            "Azul" to 0xFF4FA3F7.toInt(),
            "Verde" to 0xFF3CCB8F.toInt(),
            "Rojo" to 0xFFE5484D.toInt(),
            "Marrón" to 0xFFB07A4F.toInt(),
            "Violeta" to 0xFFA77BF3.toInt(),
        )
        val TAMANOS = listOf("Micro", "Pequeño", "Mediano", "Grande")
        val POSICIONES = listOf("Abajo", "Centro", "Arriba", "Izquierda", "Centro vertical", "Derecha")
        val POSICIONES_RELOJ = listOf("Automática", "Izquierda", "Derecha")
        val POSICIONES_TV = listOf("Arriba izquierda", "Arriba derecha", "Abajo izquierda", "Abajo derecha")
        /** Tamaños de la ventanita de TV respecto al de siempre (288×162). */
        val TAMANOS_TV = listOf(0.5f, 1f, 2f)
        val FUENTES_TV = listOf("Antena", "HDMI 1", "HDMI 2", "HDMI 3", "AV")
        fun esVertical(dock: Int) = dock >= 3
        val MINUTOS_ROTAR = listOf(0, 15, 30, 60)
        val MINUTOS_SALVA = listOf(0, 5, 10, 30)
    }
}
