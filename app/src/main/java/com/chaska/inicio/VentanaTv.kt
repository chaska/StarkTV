package com.chaska.inicio

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Ventanita flotante con la TDT en directo (sin sonido). Esta Thomson no deja leer el nombre del canal
 * a otras apps, así que se intenta mostrar la imagen del sintonizador; si no deja, lo dice en la ventana.
 * Prueba dos formas de sintonizar: la de entrada directa y "sin canal" (la TV suele poner el último).
 */
class VentanaTv(ctx: Context, sel: Int) : FrameLayout(ctx) {
    private val tv = TvView(ctx)
    private val estado = TextView(ctx).apply {
        textSize = 13f
        setTextColor(0xCCFFFFFF.toInt())
        gravity = Gravity.CENTER
        text = "Conectando con la TDT…"
    }.conFuente()
    private var entrada: String? = null
    private var intento = 0
    private val siguiente = Runnable { probar() }
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
        addView(TextView(ctx).apply {
            text = "TDT en directo"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(ctx.dp(10), ctx.dp(14), ctx.dp(10), ctx.dp(6))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xB3000000.toInt(), 0))
        }.conFuente(medio = true), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
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
                removeCallbacks(vigia)
                estado.visibility = View.GONE
            }
            override fun onVideoUnavailable(inputId: String, reason: Int) {
                estado.visibility = View.VISIBLE
                estado.text = when (reason) {
                    TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING -> "Sintonizando…"
                    TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL -> "Sin señal"
                    TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING -> "Cargando…"
                    else -> "Sin imagen"
                }
            }
            override fun onConnectionFailed(inputId: String) = otroIntento()
            override fun onDisconnected(inputId: String) = otroIntento()
        })
    }

    fun encender() {
        val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        entrada = tim?.tvInputList?.firstOrNull { it.type == TvInputInfo.TYPE_TUNER }?.id
        if (entrada == null) { estado.text = "Esta TV no deja ver el sintonizador"; return }
        intento = 0
        probar()
    }

    fun apagar() {
        removeCallbacks(siguiente)
        removeCallbacks(vigia)
        try { tv.reset() } catch (e: Exception) { }
    }

    private fun probar() {
        val id = entrada ?: return
        val uri: Uri? = when (intento) {
            0 -> TvContract.buildChannelUriForPassthroughInput(id)
            1 -> null
            else -> { estado.visibility = View.VISIBLE; estado.text = "La TV no deja mostrar la TDT aquí"; return }
        }
        try {
            tv.tune(id, uri)
        } catch (e: Exception) {
            otroIntento()
            return
        }
        // Si en 7 s no hay imagen, se prueba la otra forma.
        removeCallbacks(vigia)
        postDelayed(vigia, 7000)
    }

    private fun otroIntento() {
        intento++
        removeCallbacks(vigia)
        removeCallbacks(siguiente)
        post(siguiente)
    }
}
