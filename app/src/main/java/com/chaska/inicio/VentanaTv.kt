package com.chaska.inicio

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Ventanita flotante con la TDT en directo (sin sonido) y el canal que se ve.
 * Lee los canales de la lista de Android (permiso READ_EPG_DATA) y sintoniza en el sintonizador
 * principal (HW0; el HW1 es el de imagen dentro de imagen y en esta Thomson da error).
 * CH+ / CH− del mando cambian de canal; el último se recuerda en "tv_canal".
 * Si la TV no da canales (esta Thomson solo se los enseña a Live TV), se pide la fila del canal
 * por número (el actual era la 1533) en HW0 y luego en HW1.
 */
class VentanaTv(ctx: Context, sel: Int, private val prefs: SharedPreferences) : FrameLayout(ctx) {
    private class Canal(val id: Long, val numero: String, val nombre: String, val entrada: String)

    private val tv = TvView(ctx)
    private val estado = TextView(ctx).apply {
        textSize = 13f
        setTextColor(0xCCFFFFFF.toInt())
        gravity = Gravity.CENTER
        text = "Conectando con la TDT…"
    }.conFuente()
    private val rotulo = TextView(ctx).apply {
        text = "TDT en directo"
        textSize = 12f
        setTextColor(Color.WHITE)
        maxLines = 1
        setPadding(ctx.dp(10), ctx.dp(14), ctx.dp(10), ctx.dp(6))
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xB3000000.toInt(), 0))
    }.conFuente(medio = true)
    private var canales: List<Canal> = emptyList()
    private var actual = -1
    private var entrada: String? = null
    private var sintonizadores: List<String> = emptyList()
    /** Sin lista de canales: se prueba por número de fila del canal (Live TV usa ~1500). */
    private var idCanal = -1L
    private var intento = 0
    private val siguiente = Runnable { probarSinCanal() }
    private val vigia = Runnable { if (estado.visibility == View.VISIBLE) otroIntento() }

    init {
        isFocusable = true
        isClickable = true
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, ctx.dp(14).toFloat())
        }
        elevation = ctx.dp(16).toFloat()
        setBackgroundColor(Color.BLACK)
        addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(estado, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(rotulo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        // Marco: borde fino, que se enciende del color de la selección con el foco.
        foreground = GradientDrawable().apply { cornerRadius = ctx.dp(14).toFloat(); setStroke(ctx.dp(1), 0x40FFFFFF) }
        setOnFocusChangeListener { v, f ->
            (foreground as GradientDrawable).setStroke(ctx.dp(if (f) 3 else 1), if (f) sel else 0x40FFFFFF)
            v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(200).start()
            outlineSpotShadowColor = if (f) sel else Color.BLACK
            elevation = ctx.dp(if (f) 24 else 16).toFloat()
        }
        tv.setStreamVolume(0f)
        tv.setCallback(object : TvView.TvInputCallback() {
            override fun onVideoAvailable(inputId: String) {
                Log.i(TAG, "imagen disponible en $inputId")
                removeCallbacks(vigia)
                estado.visibility = View.GONE
            }
            override fun onVideoUnavailable(inputId: String, reason: Int) {
                Log.i(TAG, "sin imagen en $inputId, motivo $reason")
                estado.visibility = View.VISIBLE
                estado.text = when (reason) {
                    TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING -> "Sintonizando…"
                    TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL -> "Sin señal"
                    TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING -> "Cargando…"
                    else -> "Sin imagen"
                }
            }
            override fun onConnectionFailed(inputId: String) { Log.w(TAG, "conexión fallida $inputId"); otroIntento() }
            override fun onDisconnected(inputId: String) { Log.w(TAG, "desconectado $inputId"); otroIntento() }
        })
    }

    /** [fuente]: 0 Antena, 1-3 HDMI, 4 AV. Por defecto, la de la ventanita del inicio. */
    fun encender(fuente: Int = prefs.getInt("tv_fuente", 0), conSonido: Boolean = prefs.getBoolean("tv_sonido", false)) {
        val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        sonido(conSonido)
        if (fuente > 0) { externa(tim, fuente); return }
        sintonizadores = tim?.tvInputList?.filter { it.type == TvInputInfo.TYPE_TUNER }?.map { it.id }?.sorted() ?: emptyList()
        // El principal primero (HW0).
        entrada = sintonizadores.firstOrNull { it.endsWith("HW0") } ?: sintonizadores.firstOrNull()
        canales = leerCanales(sintonizadores.toSet())
        Log.i(TAG, "sintonizadores=$sintonizadores canales=${canales.size}")
        if (canales.isNotEmpty()) {
            val guardado = prefs.getLong("tv_canal", -1)
            actual = canales.indexOfFirst { it.id == guardado }.takeIf { it >= 0 } ?: 0
            sintonizar()
        } else if (entrada != null) {
            intento = 0
            idCanal = prefs.getLong("tv_id", 1533)
            probarSinCanal()
        } else {
            estado.text = "Esta TV no deja ver el sintonizador"
        }
    }

    /** Sonido de la ventana: apagado por defecto para no pisar al resto. */
    fun sonido(con: Boolean) = tv.setStreamVolume(if (con) 1f else 0f)

    /** HDMI 1-3 o AV: entradas directas (passthrough), no necesitan canal. */
    private fun externa(tim: TvInputManager?, fuente: Int) {
        val todas = tim?.tvInputList ?: emptyList()
        val hdmi = todas.filter { it.type == TvInputInfo.TYPE_HDMI }.map { it.id }.sorted()
        val av = todas.filter { it.type == TvInputInfo.TYPE_COMPOSITE }.map { it.id }.sorted()
        val id = if (fuente <= 3) hdmi.getOrNull(fuente - 1) else av.firstOrNull()
        canales = emptyList()
        entrada = null
        rotulo.text = listOf("HDMI 1", "HDMI 2", "HDMI 3", "AV").getOrElse(fuente - 1) { "Entrada" }
        if (id == null) { estado.visibility = View.VISIBLE; estado.text = "Esta TV no tiene esa entrada"; return }
        estado.visibility = View.VISIBLE
        estado.text = "Conectando…"
        Log.i(TAG, "entrada externa $id")
        try { tv.tune(id, TvContract.buildChannelUriForPassthroughInput(id)) }
        catch (e: Exception) { estado.text = "La TV no deja mostrar esta entrada aquí" }
    }

    fun apagar() {
        removeCallbacks(siguiente)
        removeCallbacks(vigia)
        try { tv.reset() } catch (e: Exception) { }
    }

    /** CH+ / CH−. Sin lista, va al número de fila de al lado. */
    fun cambiarCanal(paso: Int): Boolean {
        if (canales.isEmpty()) {
            if (entrada == null) return false
            idCanal = (idCanal + paso).coerceAtLeast(1)
            prefs.edit().putLong("tv_id", idCanal).apply()
            intento = 0
            probarSinCanal()
            return true
        }
        actual = (actual + paso + canales.size) % canales.size
        sintonizar()
        return true
    }

    private fun leerCanales(entradas: Set<String>): List<Canal> = try {
        val l = mutableListOf<Canal>()
        context.contentResolver.query(TvContract.Channels.CONTENT_URI, arrayOf(
            TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER,
            TvContract.Channels.COLUMN_DISPLAY_NAME, TvContract.Channels.COLUMN_INPUT_ID), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val ent = c.getString(3) ?: continue
                if (entradas.isNotEmpty() && ent !in entradas) continue
                l += Canal(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", ent)
            }
        }
        // Por número de canal (1, 2, 3… no 1, 10, 11…).
        l.sortedWith(compareBy({ it.numero.substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }, { it.numero }))
    } catch (e: Exception) {
        Log.w(TAG, "no se pueden leer los canales: $e")
        emptyList()
    }

    private fun sintonizar() {
        val c = canales.getOrNull(actual) ?: return
        prefs.edit().putLong("tv_canal", c.id).apply()
        rotulo.text = listOf(c.numero, c.nombre).filter { it.isNotBlank() }.joinToString("  ·  ").ifBlank { "TDT en directo" }
        estado.visibility = View.VISIBLE
        estado.text = "Sintonizando…"
        Log.i(TAG, "sintonizando ${c.numero} ${c.nombre} (${c.id}) en ${c.entrada}")
        try {
            tv.tune(c.entrada, TvContract.buildChannelUri(c.id))
        } catch (e: Exception) {
            Log.w(TAG, "tune falló: $e")
            estado.text = "La TV no deja mostrar la TDT aquí"
        }
    }

    private fun probarSinCanal() {
        removeCallbacks(vigia)
        val hw0 = entrada ?: return
        val hw1 = sintonizadores.firstOrNull { it != hw0 } ?: hw0
        val (id, uri) = when (intento) {
            0 -> hw0 to TvContract.buildChannelUri(idCanal)
            1 -> hw1 to TvContract.buildChannelUri(idCanal)
            // Sin canal (null) NO: en esta Thomson hace caer el servicio del sintonizador (com.mediatek.tvinput).
            else -> { estado.visibility = View.VISIBLE; estado.text = "La TV no deja mostrar la TDT aquí"; return }
        }
        rotulo.text = if (intento <= 1) "Canal $idCanal" else "TDT en directo"
        Log.i(TAG, "sin lista de canales: intento $intento en $id con $uri")
        try {
            tv.tune(id, uri)
        } catch (e: Exception) {
            otroIntento()
            return
        }
        // Si en 10 s no hay imagen, se prueba en el otro sintonizador.
        removeCallbacks(vigia)
        postDelayed(vigia, 10_000)
    }

    private fun otroIntento() {
        if (canales.isNotEmpty()) return            // con canales no hay plan B: se queda el aviso
        intento++
        removeCallbacks(vigia)
        removeCallbacks(siguiente)
        post(siguiente)
    }

    companion object { const val TAG = "InicioTV" }
}
