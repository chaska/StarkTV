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
    var tituloDock by booleano("titulo_dock", true)
    var tvSonido by booleano("tv_sonido", false)
    var tvFuente by entero("tv_fuente", 0)      // índice de FUENTES_TV
    var papReparto by entero("pap_reparto", 0)   // índice de Pap.REPARTOS
    var papIzq by texto("pap_izq", "tv:0")       // "tv:N" o paquete de la app
    var papDer by texto("pap_der", "com.google.android.youtube.tv")
    var papActivo by booleano("pap_activo", false)
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
        val o = JSONObject(f.readText())
        val ed = p.edit().clear()
        for (k in o.keys()) {
            val e = o.getJSONObject(k)
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
        )
        val TAMANOS = listOf("Micro", "Pequeño", "Mediano", "Grande")
        val POSICIONES = listOf("Abajo", "Centro", "Arriba", "Izquierda", "Centro vertical", "Derecha")
        val POSICIONES_RELOJ = listOf("Automática", "Izquierda", "Derecha")
        val FUENTES_TV = listOf("Antena", "HDMI 1", "HDMI 2", "HDMI 3", "AV")
        fun esVertical(dock: Int) = dock >= 3
        val MINUTOS_ROTAR = listOf(0, 15, 30, 60)
        val MINUTOS_SALVA = listOf(0, 5, 10, 30)
    }
}
