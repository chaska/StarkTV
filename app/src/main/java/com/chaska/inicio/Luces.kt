package com.chaska.inicio

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Iluminación encima del fondo: un foco frío arriba a la izquierda, uno cálido a la derecha,
 * el suelo en penumbra, la viñeta de los bordes y un foco del color de la app activa que la sigue.
 * Solo se repinta cuando el foco se mueve, no en cada fotograma. El foco de color va pegado a la ficha:
 * sale a la vez que ella crece (mismos 280 ms) y en cada fotograma mira dónde está, así acompaña
 * también al dock cuando se desplaza.
 */
class Luces(ctx: Context) : View(ctx) {
    private var x = -1f
    private var y = -1f
    private var color = 0xFFF2A93B.toInt()
    private var radio = -1f
    private var anim: ValueAnimator? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mezcla = ArgbEvaluator()

    // Quieta casi siempre: guardada en la tarjeta gráfica, así no se vuelven a pintar sus degradados a pantalla
    // completa cada vez que la luz de una ficha se mueve.
    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    private var objetivo: View? = null
    private var ox = 0f
    private var oy = 0f
    private val loc = IntArray(2)
    private val yo = IntArray(2)

    /** Dónde está ahora el punto (ox, oy) de lo enfocado, en coordenadas de esta capa. */
    private fun destino(): Pair<Float, Float>? {
        val v = objetivo ?: return null
        if (!v.isAttachedToWindow) return null
        v.getLocationInWindow(loc); getLocationInWindow(yo)
        return (loc[0] - yo[0] + ox) to (loc[1] - yo[1] + oy)
    }

    /** Lleva el foco de color a [v] (al punto cx, cy de la ficha); [nr] es su radio, en proporción a lo enfocado. */
    fun apuntar(v: View, cx: Float, cy: Float, nc: Int, nr: Float) {
        anim?.cancel()
        objetivo = v; ox = cx; oy = cy
        val (nx, ny) = destino() ?: return
        if (x < 0) { x = nx; y = ny; color = nc; radio = nr; invalidate(); return }
        val x0 = x; val y0 = y; val c0 = color; val r0 = radio
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                val t = it.animatedFraction
                val (dx, dy) = destino() ?: (nx to ny)
                x = x0 + (dx - x0) * t
                y = y0 + (dy - y0) * t
                radio = r0 + (nr - r0) * t
                color = mezcla.evaluate(t, c0, nc) as Int
                invalidate()
            }
            start()
        }
    }

    /** El dock se ha desplazado: el foco va con la ficha, sin esperar. */
    fun seguir() {
        if (anim?.isRunning == true) return             // la animación ya mira dónde está en cada fotograma
        val (nx, ny) = destino() ?: return
        if (nx != x || ny != y) { x = nx; y = ny; invalidate() }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    // Las luces fijas se crean una vez por tamaño de pantalla; el foco se dibuja con un degradado
    // de radio 1 que se escala y se coloca con el lienzo, así no se crea nada en cada fotograma.
    private val pCenital = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pContra = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pSuelo = Paint()
    private val pVineta = Paint()
    private val colores = IntArray(3)
    private val posiciones = floatArrayOf(0f, .45f, 1f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val fw = w.toFloat(); val fh = h.toFloat()
        pCenital.shader = RadialGradient(fw * .12f, -fh * .08f, fw * .5f, conAlfa(Ficha.mezclar(0xFF78AAFF.toInt(), Tema.color, .6f), 0x38), 0, Shader.TileMode.CLAMP)
        pContra.shader = RadialGradient(fw * .95f, 0f, fw * .36f, 0x26FF8C5A, 0, Shader.TileMode.CLAMP)
        pSuelo.shader = LinearGradient(0f, fh * .62f, 0f, fh,
            intArrayOf(0, 0x8C000000.toInt(), 0xCC000000.toInt()), floatArrayOf(0f, .4f, 1f), Shader.TileMode.CLAMP)
        pVineta.shader = RadialGradient(fw / 2, fh * .45f, fw * .75f,
            intArrayOf(0, 0, 0xA6000000.toInt()), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
    }

    /** Repinta la luz de ambiente con el color de la interfaz nuevo. */
    fun cambiarTema() { if (width > 0) onSizeChanged(width, height, width, height); invalidate() }

    private var colorShader = 0

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawCircle(w * .12f, -h * .08f, w * .5f, pCenital)
        c.drawCircle(w * .95f, 0f, w * .36f, pContra)
        c.drawRect(0f, h * .62f, w, h, pSuelo)
        if (x >= 0) {
            // El degradado solo se rehace si cambia el color (una vez por app, no por fotograma).
            if (colorShader != color || p.shader == null) {
                colorShader = color
                colores[0] = conAlfa(color, 0xB0); colores[1] = conAlfa(color, 0x48); colores[2] = 0
                p.shader = RadialGradient(0f, 0f, 1f, colores, posiciones, Shader.TileMode.CLAMP)
            }
            c.save()
            c.translate(x, y)
            c.scale(radio, radio * .66f)
            c.drawCircle(0f, 0f, 1f, p)
            c.restore()
        }
        c.drawRect(0f, 0f, w, h, pVineta)
    }

    private fun conAlfa(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
}
