package com.chaska.inicio

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** Iconos de línea dibujados a mano en una rejilla de 24×24 (sin emojis ni imágenes). */
class Icono(private val tipo: Tipo, private val color: Int, private val lado: Int) : Drawable() {
    enum class Tipo { AJUSTES, TV, CUADRICULA, ESTRELLA, ESTRELLA_LLENA, MOVER, OCULTAR, INFO,
        SOL, SOL_NUBE, NUBE, LLUVIA, NIEVE, TORMENTA, NIEBLA }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val camino = Path()

    override fun getIntrinsicWidth() = lado
    override fun getIntrinsicHeight() = lado

    override fun draw(c: Canvas) {
        val b = bounds
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        c.scale(b.width() / 24f, b.height() / 24f)
        p.color = color
        p.alpha = alpha
        p.strokeWidth = 1.8f
        p.style = Paint.Style.STROKE
        when (tipo) {
            Tipo.AJUSTES -> {
                c.drawCircle(12f, 12f, 3f, p)
                c.drawCircle(12f, 12f, 6.6f, p)
                for (i in 0 until 8) {
                    c.save(); c.rotate(i * 45f, 12f, 12f); c.drawLine(12f, 2.6f, 12f, 5.2f, p); c.restore()
                }
            }
            Tipo.TV -> {
                c.drawRoundRect(RectF(3f, 5f, 21f, 17f), 2f, 2f, p)
                c.drawLine(8f, 21f, 16f, 21f, p)
            }
            Tipo.CUADRICULA -> for ((x, y) in listOf(4f to 4f, 13f to 4f, 4f to 13f, 13f to 13f))
                c.drawRoundRect(RectF(x, y, x + 7f, y + 7f), 1.5f, 1.5f, p)
            Tipo.ESTRELLA, Tipo.ESTRELLA_LLENA -> {
                estrella(camino, 12f, 12.6f, 9.5f, 4.2f)
                if (tipo == Tipo.ESTRELLA_LLENA) p.style = Paint.Style.FILL_AND_STROKE
                c.drawPath(camino, p)
            }
            Tipo.MOVER -> {
                c.drawLine(4f, 12f, 20f, 12f, p)
                c.drawLines(floatArrayOf(8f, 8f, 4f, 12f, 4f, 12f, 8f, 16f, 16f, 8f, 20f, 12f, 20f, 12f, 16f, 16f), p)
            }
            Tipo.OCULTAR -> {
                c.drawArc(RectF(3f, 6f, 21f, 18f), 200f, 140f, false, p)
                c.drawArc(RectF(3f, 6f, 21f, 18f), 20f, 140f, false, p)
                c.drawLine(4f, 4f, 20f, 20f, p)
            }
            Tipo.INFO -> {
                c.drawCircle(12f, 12f, 9f, p)
                c.drawLine(12f, 11f, 12f, 16.5f, p)
                c.drawPoint(12f, 7.8f, p)
            }
            Tipo.SOL -> sol(c, 12f, 12f, 1f)
            Tipo.SOL_NUBE -> { sol(c, 8.5f, 8.5f, .7f); nube(c) }
            Tipo.NUBE -> nube(c)
            Tipo.LLUVIA -> { nube(c, -3f); c.drawLines(floatArrayOf(8f, 17f, 7f, 20f, 12f, 17f, 11f, 20f, 16f, 17f, 15f, 20f), p) }
            Tipo.NIEVE -> { nube(c, -3f); for (x in listOf(8f, 12f, 16f)) c.drawPoint(x, 19f, p) }
            Tipo.TORMENTA -> {
                nube(c, -3f)
                camino.reset(); camino.moveTo(13f, 15.5f); camino.lineTo(10.5f, 19f); camino.lineTo(13.5f, 19f); camino.lineTo(11f, 22.5f)
                c.drawPath(camino, p)
            }
            Tipo.NIEBLA -> c.drawLines(floatArrayOf(4f, 9f, 20f, 9f, 6f, 13f, 18f, 13f, 4f, 17f, 20f, 17f), p)
        }
        c.restore()
    }

    private fun sol(c: Canvas, x: Float, y: Float, e: Float) {
        c.drawCircle(x, y, 3.6f * e, p)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            c.drawLine(x + cos(a).toFloat() * 6f * e, y + sin(a).toFloat() * 6f * e,
                x + cos(a).toFloat() * 8.2f * e, y + sin(a).toFloat() * 8.2f * e, p)
        }
    }

    private fun nube(c: Canvas, dy: Float = 0f) {
        camino.reset()
        camino.moveTo(7f, 19f + dy)
        camino.cubicTo(3.5f, 19f + dy, 3f, 13.5f + dy, 7f, 13.2f + dy)
        camino.cubicTo(7.5f, 8.5f + dy, 14.5f, 8f + dy, 15.8f, 12.2f + dy)
        camino.cubicTo(20.5f, 11.8f + dy, 21.5f, 19f + dy, 17f, 19f + dy)
        camino.close()
        c.drawPath(camino, p)
    }

    private fun estrella(path: Path, cx: Float, cy: Float, ext: Float, int: Float) {
        path.reset()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) ext else int
            val a = Math.toRadians(-90.0 + i * 36.0)
            val x = cx + cos(a).toFloat() * r
            val y = cy + sin(a).toFloat() * r
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    override fun setAlpha(a: Int) { p.alpha = a; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** Icono del tiempo según el código WMO de Open-Meteo. */
        fun clima(codigo: Int) = when (codigo) {
            0 -> Tipo.SOL
            1, 2 -> Tipo.SOL_NUBE
            45, 48 -> Tipo.NIEBLA
            in 51..67, in 80..82 -> Tipo.LLUVIA
            in 71..77, 85, 86 -> Tipo.NIEVE
            in 95..99 -> Tipo.TORMENTA
            else -> Tipo.NUBE
        }
    }
}
