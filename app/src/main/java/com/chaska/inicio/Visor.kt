package com.chaska.inicio

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
open class Visor(private val parte: Int) : Activity() {
    private lateinit var ventana: VentanaTv
    private val aj by lazy { Ajustes(this) }

    /** Ajuste «Sonido» del PAP. Solo manda en las partes de TV: las apps suenan siempre. */
    private fun suena(foco: Boolean) = when (aj.papSonido) {
        1, 2 -> aj.papSonido == parte
        3 -> true
        else -> foco
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fuentes.actual = aj.fuente
        ventana = VentanaTv(this, aj.colorSel, aj.p).apply {
            isFocusable = false
            onFocusChangeListener = null
            foreground = null
            elevation = 0f
            clipToOutline = false
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(ventana, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        })
        abiertos += this
    }

    override fun onStart() {
        super.onStart()
        ventana.encender(intent.getIntExtra(EXTRA_FUENTE, 0), conSonido = suena(hasWindowFocus()))
    }

    override fun onStop() {
        ventana.apagar()
        super.onStop()
    }

    override fun onDestroy() {
        abiertos -= this
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        ventana.sonido(suena(hasFocus))
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
        setContentView(android.view.View(this).apply { setBackgroundColor(Color.BLACK) })
        actual = this
    }

    override fun onDestroy() {
        if (actual === this) actual = null
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK) true else super.onKeyDown(keyCode, event)

    companion object { var actual: FondoPap? = null }
}
