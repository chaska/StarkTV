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
 * Selector del PAP: pantalla transparente encima de las dos partes.
 * - Recuadro del color de la selección sobre la parte marcada; ◀ ▶ (▲ ▼ si es arriba y abajo) la cambian.
 * - Debajo de cada parte, su fuente («◀ YouTube ▶»): ▼ baja a ella, ◀ ▶ la cambian, OK la aplica.
 * - Abajo del todo, el círculo con la X: OK cierra el PAP. Mantener Atrás también; Atrás corto vuelve.
 * Mientras está abierto, el mando no toca las apps de debajo.
 */
class SelectorPap : Activity() {
    private val aj by lazy { Ajustes(this) }
    private lateinit var lienzo: Lienzo
    private lateinit var ops: List<Pair<String, String>>
    /** Fuente elegida (índice en [ops]) para la parte 1 y la 2, aún sin aplicar. */
    private val elegida = IntArray(2)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fuentes.actual = aj.fuente
        ops = Pap.opciones(this)
        elegida[0] = ops.indexOfFirst { it.first == aj.papIzq }.coerceAtLeast(0)
        elegida[1] = ops.indexOfFirst { it.first == aj.papDer }.coerceAtLeast(0)
        val (r1, r2) = Pap.limites(this, aj.papReparto)
        lienzo = Lienzo(this, r1, r2, aj.colorSel, aj.papReparto == 3)
        lienzo.marca = Marca(Marca.PARTE, aj.papParte.coerceIn(1, 2))
        pintarNombres()
        setContentView(lienzo)
    }

    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() { visible = false; super.onStop() }

    private fun pintarNombres() {
        lienzo.nombres = elegida.map { ops.getOrNull(it)?.second ?: "" }
        lienzo.invalidate()
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        val m = lienzo.marca
        val ab = aj.papReparto == 3
        val nueva: Marca? = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val paso = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                when {
                    m.tipo == Marca.FUENTE -> {
                        val i = m.parte - 1
                        elegida[i] = (elegida[i] + paso + ops.size) % ops.size
                        pintarNombres(); m
                    }
                    m.tipo == Marca.PARTE && !ab -> Marca(Marca.PARTE, if (paso > 0) 2 else 1)
                    else -> m
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> when {
                m.tipo == Marca.PARTE -> Marca(Marca.FUENTE, m.parte)
                m.tipo == Marca.FUENTE && ab && m.parte == 1 -> Marca(Marca.PARTE, 2)
                m.tipo == Marca.FUENTE -> Marca(Marca.CERRAR, m.parte)
                else -> m
            }
            KeyEvent.KEYCODE_DPAD_UP -> when {
                m.tipo == Marca.CERRAR -> Marca(Marca.FUENTE, m.parte)
                m.tipo == Marca.FUENTE -> Marca(Marca.PARTE, m.parte)
                m.tipo == Marca.PARTE && ab && m.parte == 2 -> Marca(Marca.FUENTE, 1)
                else -> m
            }
            KeyEvent.KEYCODE_BACK -> { e.startTracking(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> return true
            else -> return super.onKeyDown(code, e)
        }
        if (nueva != null) lienzo.marca = nueva
        return true
    }

    override fun onKeyLongPress(code: Int, e: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME) { cerrarPap(); return true }
        return super.onKeyLongPress(code, e)
    }

    override fun onKeyUp(code: Int, e: KeyEvent): Boolean {
        val m = lienzo.marca
        when (code) {
            KeyEvent.KEYCODE_BACK -> {
                if (!e.isCanceled) volver(aj.papParte)      // Atrás corto: sigue como estaba
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when (m.tipo) {
                    Marca.CERRAR -> cerrarPap()
                    Marca.FUENTE -> aplicarFuente(m.parte)
                    else -> volver(m.parte)
                }
                return true
            }
        }
        return super.onKeyUp(code, e)
    }

    /** Cambia lo que va en una parte y deja manejándola. Si antes era TV, se cierra su visor. */
    private fun aplicarFuente(parte: Int) {
        val clave = ops.getOrNull(elegida[parte - 1])?.first ?: return
        val antes = if (parte == 1) aj.papIzq else aj.papDer
        if (clave != antes) {
            if (parte == 1) aj.papIzq = clave else aj.papDer = clave
            Visor.abiertos.filter { (parte == 1 && it is VisorIzq) || (parte == 2 && it is VisorDer) }
                .forEach { it.finishAndRemoveTask() }
        }
        volver(parte, cambio = clave != antes)
    }

    /**
     * Deja manejando [parte]. Si solo se cambia de parte, no se vuelve a abrir nada: se pone delante
     * la ventana de esa parte (la busca por adb) y el mando va con ella. Si cambió su fuente, se abre.
     */
    private fun volver(parte: Int, cambio: Boolean = false) {
        if (cambio) {
            finish(); overridePendingTransition(0, 0)
            Pap.lanzar(this, aj, parte, cambio = true)
            return
        }
        aj.papParte = parte
        val b = Pap.busqueda(if (parte == 1) aj.papIzq else aj.papDer, parte)
        Thread {
            val t = Adb.tareas(this)?.let { Pap.buscar(it, b) }
            runOnUiThread {
                if (t != null) Pap.alFrente(this, t.id) else Pap.lanzar(this, aj, parte)   // sin adb: como antes
                finish(); overridePendingTransition(0, 0)
            }
        }.start()
    }

    private fun cerrarPap() {
        Pap.salir(aj)
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        /** Para que el colocador del PAP lo vuelva a poner encima si una app se le cuela. */
        @Volatile var visible = false
    }

    /** Qué está marcado: una parte, la fuente de una parte o el círculo de cerrar. */
    private data class Marca(val tipo: Int, val parte: Int) {
        companion object { const val PARTE = 0; const val FUENTE = 1; const val CERRAR = 2 }
    }

    /** Dibuja los dos recuadros, la fuente bajo cada uno, el círculo con la X y la leyenda. */
    private class Lienzo(ctx: Context, r1: Rect, r2: Rect, private val sel: Int, private val arribaAbajo: Boolean) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val partes = listOf(RectF(r1), RectF(r2)).onEach { it.inset(6 * d, 6 * d) }
        var marca = Marca(Marca.PARTE, 1)
            set(v) { field = v; invalidate() }
        var nombres: List<String> = listOf("", "")
        private val pMarco = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d }
        private val pHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 10 * d; maskFilter = BlurMaskFilter(12 * d, BlurMaskFilter.Blur.NORMAL)
        }
        private val pRelleno = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pX = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f * d; strokeCap = Paint.Cap.ROUND }
        private val pTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 15 * d; textAlign = Paint.Align.CENTER
            typeface = Fuentes.normal(ctx); setShadowLayer(6 * d, 0f, d, Color.BLACK)
        }
        private val pFuente = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 16 * d; textAlign = Paint.Align.CENTER; typeface = Fuentes.medio(ctx)
        }
        private val pFondoTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }     // para el halo difuminado

        override fun onDraw(c: Canvas) {
            val radio = 14 * d
            partes.forEachIndexed { i, r ->
                val parte = i + 1
                val activa = marca.tipo == Marca.PARTE && marca.parte == parte
                if (activa) { pHalo.color = conAlfa(sel, 0xB0); c.drawRoundRect(r, radio, radio, pHalo) }
                pMarco.color = if (activa) sel else 0x55FFFFFF
                pMarco.strokeWidth = (if (activa) 4f else 1.5f) * d
                c.drawRoundRect(r, radio, radio, pMarco)
                pastilla(c, r, parte)
            }
            // Círculo con la X: abajo, entre las dos partes.
            val cx = width / 2f
            val cy = height - 74 * d
            val rc = 26 * d
            val enX = marca.tipo == Marca.CERRAR
            if (enX) { pHalo.color = conAlfa(sel, 0xC0); c.drawCircle(cx, cy, rc, pHalo) }
            pRelleno.color = if (enX) sel else 0xCC1A1D26.toInt()
            c.drawCircle(cx, cy, rc, pRelleno)
            pX.color = if (enX) Color.BLACK else sel
            val x = rc * .38f
            c.drawLine(cx - x, cy - x, cx + x, cy + x, pX)
            c.drawLine(cx + x, cy - x, cx - x, cy + x, pX)
            // Leyenda.
            val flechas = if (arribaAbajo) "▲ ▼" else "◀ ▶"
            val texto = "$flechas elige · ▼ cambia la fuente · OK aplica · Para salir del PAP, mantén pulsado Inicio o Atrás"
            val ty = height - 22 * d
            val w = pTexto.measureText(texto) / 2 + 16 * d
            c.drawRoundRect(cx - w, ty - 22 * d, cx + w, ty + 10 * d, 14 * d, 14 * d, pFondoTexto)
            c.drawText(texto, cx, ty, pTexto)
        }

        /** «◀ YouTube ▶» abajo dentro de la parte; brilla cuando está marcada. */
        private fun pastilla(c: Canvas, r: RectF, parte: Int) {
            val marcada = marca.tipo == Marca.FUENTE && marca.parte == parte
            val texto = if (marcada) "◀   ${nombres.getOrElse(parte - 1) { "" }}   ▶" else nombres.getOrElse(parte - 1) { "" }
            val w = pFuente.measureText(texto) / 2 + 18 * d
            val cx = r.centerX()
            // Si la parte de abajo toca el borde inferior, la pastilla se sube para no chocar con la X.
            val base = if (r.bottom > height - 110 * d) r.bottom - 120 * d else r.bottom - 22 * d
            val p = RectF(cx - w, base - 24 * d, cx + w, base + 12 * d)
            if (marcada) { pHalo.color = conAlfa(sel, 0xC0); c.drawRoundRect(p, 18 * d, 18 * d, pHalo) }
            pRelleno.color = if (marcada) sel else 0xCC1A1D26.toInt()
            c.drawRoundRect(p, 18 * d, 18 * d, pRelleno)
            pFuente.color = if (marcada) Color.BLACK else Color.WHITE
            c.drawText(texto, cx, base, pFuente)
        }
    }
}
