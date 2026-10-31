package com.chaska.inicio

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView

/**
 * Tipografías: 10 de Google Fonts (licencia OFL, en assets/fuentes con sus licencias) y la del sistema.
 * Casi todas son variables: un solo archivo con todos los grosores; se piden 400 (normal) y 600 (medio).
 */
object Fuentes {
    class Fuente(val nombre: String, val archivo: String?, val archivoMedio: String? = null)

    val LISTA = listOf(
        Fuente("Sistema", null),
        Fuente("Manrope", "manrope.ttf"),
        Fuente("Outfit", "outfit.ttf"),
        Fuente("Sora", "sora.ttf"),
        Fuente("Space Grotesk", "spacegrotesk.ttf"),
        Fuente("Plus Jakarta Sans", "plusjakartasans.ttf"),
        Fuente("Lexend", "lexend.ttf"),
        Fuente("Urbanist", "urbanist.ttf"),
        Fuente("DM Sans", "dmsans.ttf"),
        Fuente("Montserrat", "montserrat.ttf"),
        Fuente("Poppins", "poppins.ttf", "poppins_medio.ttf"),
    )

    /** Índice de la fuente en uso; lo fija MainActivity desde los ajustes. */
    var actual = 1
    private val cache = HashMap<String, Typeface>()

    fun normal(ctx: Context, indice: Int = actual) = cargar(ctx, indice, false)
    fun medio(ctx: Context, indice: Int = actual) = cargar(ctx, indice, true)

    private fun cargar(ctx: Context, indice: Int, medio: Boolean): Typeface {
        val f = LISTA[indice.coerceIn(LISTA.indices)]
        val clave = "${f.nombre}/$medio"
        cache[clave]?.let { return it }
        val t = try {
            when {
                f.archivo == null -> Typeface.create(if (medio) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
                f.archivoMedio != null -> Typeface.createFromAsset(ctx.assets, "fuentes/" + if (medio) f.archivoMedio else f.archivo)
                else -> Typeface.Builder(ctx.assets, "fuentes/${f.archivo}")
                    .setFontVariationSettings("'wght' ${if (medio) 600 else 400}")
                    .build()
            }
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
        cache[clave] = t
        return t
    }
}

fun <T : TextView> T.conFuente(medio: Boolean = false): T {
    typeface = if (medio) Fuentes.medio(context) else Fuentes.normal(context)
    return this
}
