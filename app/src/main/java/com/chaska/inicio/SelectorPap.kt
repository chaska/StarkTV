package com.chaska.inicio

import android.app.Activity
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.KeyEvent
import android.view.View

/**
 * Selector del PAP: pantalla transparente encima de las dos partes. Un recuadro del color de la
 * selección marca la parte activa; ◀ ▶ (▲ ▼ si es arriba y abajo) lo mueven y OK deja manejando esa parte.
 * ▼ lleva al círculo con la X (OK cierra el PAP). Mantener Atrás también sale del PAP; Atrás corto vuelve.
 * Mientras está abierto, el mando no toca las apps de debajo.
 */
class SelectorPap : Activity() {
    private val aj by lazy { Ajustes(this) }
    private lateinit var lienzo: Lienzo
    /** 1 o 2: parte marcada; 0: el círculo de cerrar. */
    private var marca = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fuentes.actual = aj.fuente
        marca = aj.papParte.coerceIn(1, 2)
        val (r1, r2) = Pap.limites(this, aj.papReparto)
        lienzo = Lienzo(this, r1, r2, aj.colorSel, aj.papReparto == 3)
        lienzo.marca = marca
        setContentView(lienzo)
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        val arribaAbajo = aj.papReparto == 3
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (!arribaAbajo && marca != 0) marca = 1
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!arribaAbajo && marca != 0) marca = 2
            KeyEvent.KEYCODE_DPAD_UP -> marca = if (marca == 0) aj.papParte.coerceIn(1, 2) else if (arribaAbajo) 1 else marca
            KeyEvent.KEYCODE_DPAD_DOWN -> marca = if (arribaAbajo && marca == 1) 2 else 0
            KeyEvent.KEYCODE_BACK -> { e.startTracking(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> return true
            else -> return super.onKeyDown(code, e)
        }
        lienzo.marca = marca
        return true
    }

    override fun onKeyLongPress(code: Int, e: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME) { cerrarPap(); return true }
        return super.onKeyLongPress(code, e)
    }

    override fun onKeyUp(code: Int, e: KeyEvent): Boolean {
        when (code) {
            KeyEvent.KEYCODE_BACK -> {
                if (!e.isCanceled) volver(aj.papParte)      // Atrás corto: sigue como estaba
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (marca == 0) cerrarPap() else volver(marca)
                return true
            }
        }
        return super.onKeyUp(code, e)
    }

    private fun volver(parte: Int) {
        finish()
        overridePendingTransition(0, 0)
        Pap.lanzar(this, aj, parte)
    }

    private fun cerrarPap() {
        lienzo.marca = 0
        Pap.salir(aj)
        finish()
        overridePendingTransition(0, 0)
    }

    /** Dibuja los dos recuadros, el círculo con la X y la leyenda. */
    private class Lienzo(ctx: Context, r1: Rect, r2: Rect, private val sel: Int, private val arribaAbajo: Boolean) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val partes = listOf(RectF(r1), RectF(r2)).onEach { it.inset(6 * d, 6 * d) }
        var marca = 1
            set(v) { field = v; invalidate() }
        private val pMarco = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d }
        private val pHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 10 * d; maskFilter = BlurMaskFilter(12 * d, BlurMaskFilter.Blur.NORMAL)
        }
        private val pCirculo = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pX = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f * d; strokeCap = Paint.Cap.ROUND }
        private val pTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 15 * d; textAlign = Paint.Align.CENTER
            typeface = Fuentes.normal(ctx); setShadowLayer(6 * d, 0f, d, Color.BLACK)
        }
        private val pFondoTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }     // para el halo difuminado

        override fun onDraw(c: Canvas) {
            val radio = 14 * d
            partes.forEachIndexed { i, r ->
                val activa = marca == i + 1
                if (activa) {
                    pHalo.color = conAlfa(sel, 0xB0)
                    c.drawRoundRect(r, radio, radio, pHalo)
                }
                pMarco.color = if (activa) sel else 0x55FFFFFF
                pMarco.strokeWidth = (if (activa) 4f else 1.5f) * d
                c.drawRoundRect(r, radio, radio, pMarco)
            }
            // Círculo con la X: abajo, entre las dos partes (en el centro si es arriba y abajo).
            val cx = width / 2f
            val cy = height - 74 * d
            val rc = 26 * d
            val enX = marca == 0
            if (enX) { pHalo.color = conAlfa(sel, 0xC0); c.drawCircle(cx, cy, rc, pHalo) }
            pCirculo.color = if (enX) sel else 0xCC1A1D26.toInt()
            c.drawCircle(cx, cy, rc, pCirculo)
            pX.color = if (enX) Color.BLACK else sel
            val x = rc * .38f
            c.drawLine(cx - x, cy - x, cx + x, cy + x, pX)
            c.drawLine(cx + x, cy - x, cx - x, cy + x, pX)
            // Leyenda.
            val texto = if (arribaAbajo) "▲ ▼ elige la parte · OK la manejas · Para salir del PAP, mantén pulsado Atrás"
                        else "◀ ▶ elige la parte · OK la manejas · Para salir del PAP, mantén pulsado Atrás"
            val ty = height - 22 * d
            val w = pTexto.measureText(texto) / 2 + 16 * d
            c.drawRoundRect(cx - w, ty - 22 * d, cx + w, ty + 10 * d, 14 * d, 14 * d, pFondoTexto)
            c.drawText(texto, cx, ty, pTexto)
        }
    }
}
