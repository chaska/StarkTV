package com.chaska.inicio.pap

import com.chaska.inicio.*

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.widget.FrameLayout

/**
 * Visor de TV para una mitad del PAP: la ventana de TV a tamaño completo de su mitad.
 * Suena solo cuando es la mitad que manejas (tiene el foco). CH+/CH− cambian de canal con Antena.
 * Hay dos (izquierda/derecha) con tareas distintas para poder estar abiertos a la vez.
 */
open class Visor(val parte: Int) : Activity() {
    private lateinit var ventana: VentanaTv
    private val aj by lazy { Ajustes(this) }

    /** Ajuste «Sonido» del PAP (la que manejas, una parte o las dos). */
    @Suppress("UNUSED_PARAMETER")
    private fun suena(foco: Boolean) = Pap.suena(aj, parte)

    fun sonido(con: Boolean) = ventana.sonido(con)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Pap.lanzadoEnEsteProceso) { aj.papActivo = false; finishAndRemoveTask(); return }   // reabierto por Android
        Fuentes.actual = aj.fuente
        Tema.color = aj.colorSel
        Tema.opacidad = aj.opacidad
        Tema.oscuridad = aj.oscuridad
        ventana = VentanaTv(this, aj.colorSel, aj.p).apply {
            isFocusable = false
            onFocusChangeListener = null
            foreground = null
            elevation = 0f
            clipToOutline = false
        }
        // La imagen en 16:9 centrada (bandas negras), sin estirarla a la forma de la mitad.
        val raiz = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(ventana, FrameLayout.LayoutParams(0, 0, android.view.Gravity.CENTER))
        }
        raiz.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l; val h = b - t
            var vw = w; var vh = w * 9 / 16
            if (vh > h) { vh = h; vw = h * 16 / 9 }
            val lp = ventana.layoutParams as FrameLayout.LayoutParams
            if (lp.width != vw || lp.height != vh) raiz.post { lp.width = vw; lp.height = vh; ventana.layoutParams = lp }
        }
        setContentView(raiz)
        abiertos += this
    }

    override fun onStart() {
        super.onStart()
        if (!::ventana.isInitialized) return
        ventana.encender(intent.getIntExtra(EXTRA_FUENTE, 0), conSonido = suena(hasWindowFocus()))
    }

    override fun onStop() {
        if (::ventana.isInitialized) ventana.apagar()
        super.onStop()
    }

    override fun onDestroy() {
        abiertos -= this
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::ventana.isInitialized) ventana.sonido(suena(hasFocus))
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_CHANNEL_UP || e.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            if (e.action == KeyEvent.ACTION_DOWN) ventana.cambiarCanal(if (e.keyCode == KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1)
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    companion object {
        const val EXTRA_FUENTE = "fuente"
        /** Visores abiertos, para cerrarlos al salir del PAP. */
        val abiertos = mutableSetOf<Visor>()
    }
}

class VisorIzq : Visor(1)
class VisorDer : Visor(2)

/**
 * Fondo del PAP: pantalla negra a pantalla completa, de Stark TV, que queda debajo de las dos partes.
 * Así el PAP es «una app» sobre el launcher y no asoma el inicio por los huecos. No se maneja:
 * si el mando llega aquí, Inicio (servicio Teclas) abre el selector.
 */
class FondoPap : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Pap.lanzadoEnEsteProceso) { Ajustes(this).papActivo = false; finishAndRemoveTask(); return }
        setContentView(android.view.View(this).apply { setBackgroundColor(Color.BLACK) })
        actual = this
    }

    override fun onDestroy() {
        if (actual === this) actual = null
        super.onDestroy()
    }

    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() { visible = false; super.onStop() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK) true else super.onKeyDown(keyCode, event)

    companion object {
        var actual: FondoPap? = null
        /** Se ve (está debajo de las partes). Si se fue al fondo, el PAP ya no está en pantalla. */
        @Volatile var visible = false
    }
}
