package com.chaska.inicio

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

fun Context.dp(v: Number): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun conAlfa(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

/** Paneles de cristal: degradado azulado translúcido, borde de luz y el fondo difuminado detrás. */
object Vidrio {
    fun fondo(ctx: Context): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xB02E3852.toInt(), 0xE00E111C.toInt())).apply {
            cornerRadius = ctx.dp(18).toFloat()
            setStroke(ctx.dp(1), 0x33FFFFFF)
        }

    fun panel(ctx: Context, titulo: String?, ancho: Int, icono: Icono.Tipo? = null): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = fondo(ctx)
        elevation = ctx.dp(24).toFloat()
        setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
        minimumWidth = ancho
        if (titulo != null) addView(TextView(ctx).apply {
            text = titulo
            textSize = 19f
            setTextColor(Color.WHITE)
            setPadding(ctx.dp(8), 0, 0, ctx.dp(10))
            if (icono != null) {
                setCompoundDrawablesRelative(Icono(icono, Color.WHITE, ctx.dp(20)).apply { setBounds(0, 0, ctx.dp(20), ctx.dp(20)) }, null, null, null)
                compoundDrawablePadding = ctx.dp(10)
            }
        }.conFuente(medio = true))
    }

    /** Ventana sin marco con el panel dentro; detrás, la pantalla oscurecida y (si se puede) difuminada. */
    fun dialogo(
        act: Activity, vista: View, aj: Ajustes,
        gravedad: Int = Gravity.CENTER, x: Int = 0, y: Int = 0, alto: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    ): Dialog {
        val dl = Dialog(act, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        dl.setContentView(vista)
        dl.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, alto)
            setGravity(gravedad)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val a = attributes
            a.x = x
            a.y = y
            a.dimAmount = .55f
            if (aj.cristal && Build.VERSION.SDK_INT >= 31 && act.windowManager.isCrossWindowBlurEnabled) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                a.blurBehindRadius = act.dp(18)
            }
            attributes = a
        }
        dl.show()
        return dl
    }
}

/** Fila de menú o de ajustes: icono y texto a la izquierda, valor o interruptor a la derecha; brilla con el foco. */
class Fila(ctx: Context, titulo: String, sel: Int, icono: Icono.Tipo? = null) : LinearLayout(ctx) {
    val valor = TextView(ctx).apply {
        textSize = 14f
        setTextColor(0xBFFFFFFF.toInt())
        setPadding(ctx.dp(12), 0, 0, 0)
    }.conFuente()
    val texto: TextView = crearTexto(ctx, titulo, icono)
    var accesorio: View? = null
        set(v) {
            field?.let { removeView(it) }
            field = v
            if (v != null) addView(v)
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        minimumHeight = ctx.dp(40)
        setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(conAlfa(sel, 0x61), conAlfa(sel, 0x1A))).apply {
                    cornerRadius = ctx.dp(10).toFloat()
                    setStroke(ctx.dp(1.5f), sel)
                })
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
        addView(texto, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(valor)
    }

    private fun crearTexto(ctx: Context, titulo: String, icono: Icono.Tipo?) = TextView(ctx).apply {
        text = titulo
        textSize = 15f
        setTextColor(Color.WHITE)
        if (icono != null) {
            setCompoundDrawablesRelative(Icono(icono, 0xE6FFFFFF.toInt(), ctx.dp(18)).apply { setBounds(0, 0, ctx.dp(18), ctx.dp(18)) }, null, null, null)
            compoundDrawablePadding = ctx.dp(10)
        }
    }.conFuente()
}

/** Interruptor encendido/apagado dibujado a mano (verde con luz cuando está encendido). */
class Interruptor(ctx: Context, encendido: Boolean) : View(ctx) {
    var encendido = encendido
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((34 * d).toInt(), (18 * d).toInt())

    override fun onDraw(c: Canvas) {
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        p.shader = if (encendido) LinearGradient(0f, 0f, 0f, r.bottom, 0xFF45D39B.toInt(), 0xFF1F8A62.toInt(), Shader.TileMode.CLAMP) else null
        p.color = if (encendido) Color.WHITE else 0xFF3A3F4B.toInt()
        if (encendido) p.setShadowLayer(6 * d, 0f, 0f, 0x8C45D39B.toInt()) else p.clearShadowLayer()
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
        p.shader = null
        p.color = Color.WHITE
        p.setShadowLayer(2 * d, 0f, d, 0x80000000.toInt())
        val rad = r.height() / 2 - 2.5f * d
        c.drawCircle(if (encendido) r.right - r.height() / 2 else r.height() / 2, r.centerY(), rad, p)
    }
}

/** Barra de nivel (brillo del fondo) con el color de la selección. */
class Barra(ctx: Context, private val sel: Int, valor: Int) : View(ctx) {
    var valor = valor
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((90 * d).toInt(), (10 * d).toInt())

    override fun onDraw(c: Canvas) {
        val y = height / 2f
        val r = RectF(0f, y - 2.5f * d, width.toFloat(), y + 2.5f * d)
        p.shader = null
        p.color = 0xFF3A3F4B.toInt()
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
        r.right = width * valor / 100f
        p.shader = LinearGradient(0f, 0f, r.right.coerceAtLeast(1f), 0f,
            intArrayOf(Ficha.mezclar(sel, Color.BLACK, .4f), sel, Color.WHITE), null, Shader.TileMode.CLAMP)
        p.setShadowLayer(4 * d, 0f, 0f, sel)
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
        p.clearShadowLayer()
    }
}
