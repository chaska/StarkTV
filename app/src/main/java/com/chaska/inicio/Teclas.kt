package com.chaska.inicio

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Servicio de accesibilidad que recoge la tecla de ajustes (la tuerca) del mando antes que el servicio
 * de MediaTek (com.mediatek.hotkey.dispatcher), que con TV en pantalla abre imagen y sonido.
 * Solo actúa con Stark TV delante o con el PAP en marcha; en Live TV y en el resto, la tecla sigue igual.
 * Se activa por adb: settings put secure enabled_accessibility_services com.chaska.inicio/.Teclas
 */
class Teclas : AccessibilityService() {
    private var delante: String? = null

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) e.packageName?.toString()?.let { delante = it }
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0)
            Log.i(TAG, "tecla ${e.keyCode} ${KeyEvent.keyCodeToString(e.keyCode)} scan=${e.scanCode} delante=$delante")
        if (e.keyCode !in TECLAS_AJUSTES) return false
        val nuestro = delante == packageName || Ajustes(this).papActivo
        if (!nuestro) return false
        if (e.action == KeyEvent.ACTION_UP) {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_AJUSTES, true))
        }
        return true
    }

    companion object {
        private const val TAG = "InicioTV"
        // La tuerca del mando Thomson llega como BUTTON_14 (201, scan 384); SETTINGS por si otro mando.
        val TECLAS_AJUSTES = setOf(KeyEvent.KEYCODE_BUTTON_14, KeyEvent.KEYCODE_SETTINGS)
    }
}
