package com.chaska.inicio

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable

object Apps {
    /** Paquetes de todas las apps que se pueden abrir (las de TV primero), sin este launcher. */
    fun todas(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val vistas = LinkedHashSet<String>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (r in pm.queryIntentActivities(i, 0)) {
                val p = r.activityInfo.packageName
                if (p != ctx.packageName) vistas += p
            }
        }
        return vistas.toList()
    }

    fun intentDe(ctx: Context, paquete: String): Intent? {
        val pm = ctx.packageManager
        return (pm.getLeanbackLaunchIntentForPackage(paquete) ?: pm.getLaunchIntentForPackage(paquete))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun nombre(ctx: Context, paquete: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationInfo(paquete, 0).loadLabel(pm).toString()
    } catch (e: Exception) { paquete }

    /** Banner horizontal de la app (el de las TV); si no tiene, null. */
    fun banner(ctx: Context, paquete: String): Drawable? = try {
        ctx.packageManager.getApplicationBanner(paquete)
    } catch (e: Exception) { null }

    fun icono(ctx: Context, paquete: String): Drawable? = try {
        ctx.packageManager.getApplicationIcon(paquete)
    } catch (e: Exception) { null }
}

/** Orden del carrusel, apps ocultas y favoritas (máximo 6). Cada lista son paquetes separados por comas. */
class ListaApps(private val ctx: Context, private val p: SharedPreferences) {
    private val porDefecto = listOf(
        "com.mediatek.wwtv.tvcenter",          // TDT de la tele
        "com.netflix.ninja",
        "com.google.android.youtube.tv",
        "com.amazon.amazonvideo.livingroom",
        "com.tdtchannels.player",
        "org.schabi.newpipe",
        "org.videolan.vlc",
        "org.courville.nova",
        "com.google.android.youtube.tvmusic",
    )

    private fun leer(k: String) = p.getString(k, null)?.split(',')?.filter { it.isNotBlank() }
    private fun escribir(k: String, l: Collection<String>) = p.edit().putString(k, l.joinToString(",")).apply()

    /** Todas las apps visibles en su orden; las instaladas nuevas se añaden al final. */
    fun orden(): List<String> {
        val instaladas = Apps.todas(ctx)
        val guardado = leer(K_ORDEN)
        val base = guardado?.filter { it in instaladas }
            ?: porDefecto.filter { it in instaladas }
        val nuevas = (instaladas - base.toSet()).sortedBy { Apps.nombre(ctx, it).lowercase() }
        val todo = base + nuevas
        if (todo != guardado) escribir(K_ORDEN, todo)
        val ocultas = ocultas()
        return todo.filter { it !in ocultas }
    }

    /** Guarda el orden de las visibles sin perder el sitio de las ocultas. */
    fun guardarOrden(visibles: List<String>) {
        val ocultas = ocultas()
        escribir(K_ORDEN, visibles + (leer(K_ORDEN) ?: emptyList()).filter { it in ocultas })
    }

    fun ocultas(): Set<String> = leer(K_OCULTAS)?.toSet() ?: emptySet()
    fun ocultar(paquete: String) { escribir(K_OCULTAS, ocultas() + paquete); quitarFavorita(paquete) }
    fun mostrar(paquete: String) = escribir(K_OCULTAS, ocultas() - paquete)

    fun favoritas(): List<String> {
        val l = leer(K_FAV) ?: porDefecto.filter { Apps.intentDe(ctx, it) != null }.take(MAX_FAV).also { escribir(K_FAV, it) }
        return l.filter { Apps.intentDe(ctx, it) != null }
    }

    /** Añade si cabe; devuelve false si ya hay 6. */
    fun anadirFavorita(paquete: String): Boolean {
        val l = favoritas()
        if (paquete in l) return true
        if (l.size >= MAX_FAV) return false
        escribir(K_FAV, l + paquete)
        return true
    }

    fun quitarFavorita(paquete: String) = escribir(K_FAV, favoritas() - paquete)

    companion object {
        const val MAX_FAV = 6
        private const val K_ORDEN = "orden"
        private const val K_OCULTAS = "ocultas"
        private const val K_FAV = "favoritas"
    }
}
