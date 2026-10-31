package com.chaska.inicio

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable

/** App que se puede abrir desde el inicio. */
data class AppTv(val paquete: String, val nombre: String)

object Apps {
    /** Todas las apps que se pueden abrir (las de TV primero), sin repetir y sin este launcher. */
    fun todas(ctx: Context): List<AppTv> {
        val pm = ctx.packageManager
        val vistas = LinkedHashMap<String, AppTv>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (r in pm.queryIntentActivities(i, 0)) {
                val p = r.activityInfo.packageName
                if (p == ctx.packageName || p in vistas) continue
                vistas[p] = AppTv(p, r.loadLabel(pm).toString())
            }
        }
        return vistas.values.sortedBy { it.nombre.lowercase() }
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

/** Lista ordenada de favoritas, guardada como paquetes separados por comas. */
class Favoritas(private val ctx: Context, private val prefs: SharedPreferences) {
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

    fun lista(): List<String> {
        val guardada = prefs.getString(CLAVE, null)
        val l = guardada?.split(',')?.filter { it.isNotBlank() }
            ?: porDefecto.filter { Apps.intentDe(ctx, it) != null }.also { guardar(it) }
        // Si se desinstaló alguna, deja de salir.
        return l.filter { Apps.intentDe(ctx, it) != null }
    }

    fun guardar(l: List<String>) = prefs.edit().putString(CLAVE, l.joinToString(",")).apply()

    fun anadir(p: String) { val l = lista(); if (p !in l) guardar(l + p) }

    fun quitar(p: String) = guardar(lista() - p)

    fun mover(p: String, paso: Int) {
        val l = lista().toMutableList()
        val i = l.indexOf(p)
        val j = i + paso
        if (i < 0 || j !in l.indices) return
        l[i] = l[j]; l[j] = p
        guardar(l)
    }

    companion object { const val CLAVE = "favoritas" }
}
