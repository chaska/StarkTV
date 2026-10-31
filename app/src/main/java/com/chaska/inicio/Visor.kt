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
open class Visor : Activity() {
    private lateinit var ventana: VentanaTv

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val aj = Ajustes(this)
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
        ventana.encender(intent.getIntExtra(EXTRA_FUENTE, 0), conSonido = hasWindowFocus())
    }

    override fun onStop() {
        ventana.apagar()
        super.onStop()
    }

    override fun onDestroy() {
        abiertos -= this
        super.onDestroy()
    }

    /** El sonido sigue a la mitad que manejas. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        ventana.sonido(hasFocus)
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

class VisorIzq : Visor()
class VisorDer : Visor()
