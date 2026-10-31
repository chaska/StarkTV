package com.chaska.inicio.pap

import com.chaska.inicio.*

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
        lienzo.sonido = Pap.SONIDOS[aj.papSonido.coerceIn(0, 2)]
        lienzo.modo = Pap.REPARTOS.getOrElse(aj.papReparto) { "" }
        setContentView(lienzo)
        // No quita el foco a la app de debajo (algunas, como TDT Live, se van a su inicio si lo pierden):
        // las teclas le llegan desde el servicio Teclas mientras está abierto.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }

    override fun onResume() { super.onResume(); actual = this }
    override fun onDestroy() { if (actual === this) actual = null; super.onDestroy() }
    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() { visible = false; super.onStop() }

    private fun pintarNombres() {
        lienzo.nombres = elegida.map { ops.getOrNull(it)?.second ?: "" }
        lienzo.invalidate()
    }

    private var atrasLargo = false

    /** Teclas que le pasa el servicio Teclas (el selector no tiene foco). */
    fun tecla(e: KeyEvent): Boolean {
        val ok = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER
        if (lienzo.lista >= 0) return teclaLista(e)
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (e.keyCode == KeyEvent.KEYCODE_BACK) {
                if (e.repeatCount == 1) { atrasLargo = true; cerrarPap() }
                return true
            }
            if (ok) return true
            return onKeyDown(e.keyCode, e)
        }
        if (e.action == KeyEvent.ACTION_UP) {
            if (e.keyCode == KeyEvent.KEYCODE_BACK) { if (!atrasLargo) volver(aj.papParte); atrasLargo = false; return true }
            if (ok) return onKeyUp(e.keyCode, e)
        }
        return true
    }

    /** Lista de MODO abierta: ▲ ▼ eligen, OK aplica, Atrás la cierra sin cambiar nada. */
    private fun teclaLista(e: KeyEvent): Boolean {
        val n = Pap.REPARTOS.size + 1                 // + «Intercambiar lados»
        if (e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { lienzo.lista = (lienzo.lista - 1 + n) % n; lienzo.invalidate() }
            KeyEvent.KEYCODE_DPAD_DOWN -> { lienzo.lista = (lienzo.lista + 1) % n; lienzo.invalidate() }
        }
        if (e.action == KeyEvent.ACTION_UP) when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> { lienzo.lista = -1; lienzo.invalidate() }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> aplicarModo(lienzo.lista)
        }
        return true
    }

    /** Un modo de reparto (las ventanas se recolocan solas) o intercambiar lo que va en cada parte. */
    private fun aplicarModo(i: Int) {
        lienzo.lista = -1
        if (i == Pap.REPARTOS.size) {
            aj.papIzq = aj.papDer.also { aj.papDer = aj.papIzq }
            Visor.abiertos.toList().forEach { it.finishAndRemoveTask() }
            finish(); overridePendingTransition(0, 0)
            Pap.lanzar(this, aj, aj.papParte, cambio = true)
            return
        }
        aj.papReparto = i
        finish(); overridePendingTransition(0, 0)
        Pap.lanzar(this, aj, if (Pap.esPip(i)) 2 else aj.papParte)
    }

    /** La tuerca otra vez con el selector abierto: se cierra y se sigue en la parte de antes. */
    fun seguir() = volver(aj.papParte)

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
                    m.tipo == Marca.CERRAR && paso < 0 -> Marca(Marca.ALTAVOZ, m.parte)
                    m.tipo == Marca.CERRAR && paso > 0 -> Marca(Marca.MODO, m.parte)
                    m.tipo == Marca.ALTAVOZ && paso > 0 -> Marca(Marca.CERRAR, m.parte)
                    m.tipo == Marca.MODO && paso < 0 -> Marca(Marca.CERRAR, m.parte)
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
                m.tipo == Marca.CERRAR || m.tipo == Marca.ALTAVOZ || m.tipo == Marca.MODO -> Marca(Marca.FUENTE, m.parte)
                m.tipo == Marca.FUENTE -> Marca(Marca.PARTE, m.parte)
                m.tipo == Marca.PARTE && ab && m.parte == 2 -> Marca(Marca.FUENTE, 1)
                else -> m
            }
            else -> return true
        }
        if (nueva != null) lienzo.marca = nueva
        return true
    }

    override fun onKeyUp(code: Int, e: KeyEvent): Boolean {
        val m = lienzo.marca
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when (m.tipo) {
                    Marca.CERRAR -> cerrarPap()
                    Marca.MODO -> { lienzo.lista = aj.papReparto.coerceIn(0, Pap.REPARTOS.size - 1); lienzo.invalidate() }
                    Marca.ALTAVOZ -> {
                        // La activa → ninguna → las dos.
                        aj.papSonido = (aj.papSonido.coerceIn(0, 2) + 1) % 3
                        lienzo.sonido = Pap.SONIDOS[aj.papSonido]
                        lienzo.invalidate()
                        Pap.aplicarSonido(this, aj)
                    }
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
        Pap.aplicarSonido(this, aj)
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
        Pap.cerrar(this, aj)
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        /** Para que el colocador del PAP lo vuelva a poner encima si una app se le cuela. */
        @Volatile var visible = false
        var actual: SelectorPap? = null
    }

    /** Qué está marcado: una parte, la fuente de una parte o el círculo de cerrar. */
    private data class Marca(val tipo: Int, val parte: Int) {
        companion object { const val PARTE = 0; const val FUENTE = 1; const val CERRAR = 2; const val ALTAVOZ = 3; const val MODO = 4 }
    }

    /** Dibuja los dos recuadros, la fuente bajo cada uno, el círculo con la X y la leyenda. */
    private class Lienzo(ctx: Context, r1: Rect, r2: Rect, private val sel: Int, private val arribaAbajo: Boolean) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val partes = listOf(RectF(r1), RectF(r2)).onEach { it.inset(6 * d, 6 * d) }
        var marca = Marca(Marca.PARTE, 1)
            set(v) { field = v; invalidate() }
        var nombres: List<String> = listOf("", "")
        var sonido = ""
        var modo = ""
        /** Lista de MODO abierta: índice marcado; -1 cerrada. */
        var lista = -1
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
            // Altavoz, a la izquierda de la X: qué parte suena.
            val ax = cx - 74 * d
            val enA = marca.tipo == Marca.ALTAVOZ
            if (enA) { pHalo.color = conAlfa(sel, 0xC0); c.drawCircle(ax, cy, rc, pHalo) }
            pRelleno.color = if (enA) sel else 0xCC1A1D26.toInt()
            c.drawCircle(ax, cy, rc, pRelleno)
            pX.color = if (enA) Color.BLACK else sel
            val k = rc * .3f
            val cuerpo = android.graphics.Path().apply {
                moveTo(ax - k * 1.3f, cy - k * .5f); lineTo(ax - k * .5f, cy - k * .5f); lineTo(ax + k * .4f, cy - k * 1.2f)
                lineTo(ax + k * .4f, cy + k * 1.2f); lineTo(ax - k * .5f, cy + k * .5f); lineTo(ax - k * 1.3f, cy + k * .5f); close()
            }
            c.drawPath(cuerpo, pX)
            c.drawArc(RectF(ax - k * .2f, cy - k * .9f, ax + k * 1.4f, cy + k * .9f), -50f, 100f, false, pX)
            pFuente.color = Color.WHITE
            c.drawText(sonido, ax, cy - rc - 8 * d, pFuente)
            // MODO, a la derecha de la X: reparto, PIP e intercambiar.
            val mx = cx + 74 * d
            val enM = marca.tipo == Marca.MODO
            if (enM) { pHalo.color = conAlfa(sel, 0xC0); c.drawCircle(mx, cy, rc, pHalo) }
            pRelleno.color = if (enM) sel else 0xCC1A1D26.toInt()
            c.drawCircle(mx, cy, rc, pRelleno)
            pX.color = if (enM) Color.BLACK else sel
            val q = rc * .45f
            c.drawRoundRect(RectF(mx - q, cy - q * .7f, mx + q, cy + q * .7f), 3 * d, 3 * d, pX)
            c.drawLine(mx, cy - q * .7f, mx, cy + q * .7f, pX)
            pFuente.color = Color.WHITE
            c.drawText("Modo", mx, cy - rc - 8 * d, pFuente)
            if (lista >= 0) dibujarLista(c, mx, cy - rc - 30 * d)
            // Leyenda.
            val flechas = if (arribaAbajo) "▲ ▼" else "◀ ▶"
            val texto = "$flechas elige · ▼ cambia la fuente · OK aplica · Tuerca: volver · Mantén la tuerca o pulsa la X: salir"
            val ty = height - 22 * d
            val w = pTexto.measureText(texto) / 2 + 16 * d
            c.drawRoundRect(cx - w, ty - 22 * d, cx + w, ty + 10 * d, 14 * d, 14 * d, pFondoTexto)
            c.drawText(texto, cx, ty, pTexto)
        }

        /** Lista de MODO encima del círculo: los repartos y «Intercambiar lados». */
        private fun dibujarLista(c: Canvas, cx: Float, abajo: Float) {
            val items = Pap.REPARTOS + "Intercambiar lados"
            val alto = 34 * d
            val ancho = 260 * d
            val arriba = abajo - alto * items.size - 16 * d
            val x = (cx - ancho / 2).coerceAtMost(width - ancho - 16 * d)
            pFondoTexto.color = 0xE6141823.toInt()
            c.drawRoundRect(RectF(x, arriba, x + ancho, abajo), 16 * d, 16 * d, pFondoTexto)
            pFondoTexto.color = 0xB3000000.toInt()
            items.forEachIndexed { i, t ->
                val y = arriba + 8 * d + i * alto
                if (i == lista) {
                    pRelleno.color = sel
                    c.drawRoundRect(RectF(x + 6 * d, y, x + ancho - 6 * d, y + alto - 4 * d), 12 * d, 12 * d, pRelleno)
                }
                pFuente.color = if (i == lista) Color.BLACK else if (t == modo) sel else Color.WHITE
                c.drawText(t, x + ancho / 2, y + alto * .62f, pFuente)
            }
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
