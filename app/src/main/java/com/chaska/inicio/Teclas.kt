package com.chaska.inicio

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.chaska.inicio.pap.Pap

/**
 * Servicio de accesibilidad que recoge la tecla de ajustes (la tuerca) del mando antes que el servicio
 * de MediaTek (com.mediatek.hotkey.dispatcher), que con TV en pantalla abre imagen y sonido.
 * Solo actúa con Stark TV delante o con el PAP en marcha; en Live TV y en el resto, la tecla sigue igual.
 * Se activa por adb: settings put secure enabled_accessibility_services com.chaska.inicio/.Teclas
 */
class Teclas : AccessibilityService() {
    private var delante: String? = null
    private var largo = false

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) e.packageName?.toString()?.let { delante = it }
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0)
            Log.i(TAG, "tecla ${e.keyCode} ${KeyEvent.keyCodeToString(e.keyCode)} scan=${e.scanCode} delante=$delante")
        // Con el PAP en marcha, Android TV no vuelve al inicio con la tecla Inicio (intenta abrir
        // «todas las apps» y falla). Inicio corto abre el selector; mantenido, sale del PAP.
        if (e.keyCode == KeyEvent.KEYCODE_HOME && Pap.activo(Ajustes(this))) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 1) { largo = true; Pap.cerrar(this, Ajustes(this)) }
            if (e.action == KeyEvent.ACTION_UP) { if (!largo) Pap.selector(this); largo = false }
            return true
        }
        if (e.keyCode !in TECLAS_AJUSTES) return false
        val nuestro = delante == packageName || Pap.activo(Ajustes(this))
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

        /**
         * Android apaga el servicio al actualizar la app. Si tenemos WRITE_SECURE_SETTINGS (concedido por adb),
         * se vuelve a encender solo, respetando los demás servicios de accesibilidad que haya.
         */
        fun asegurarActivo(ctx: android.content.Context) {
            if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val cr = ctx.contentResolver
            val nuestro = "${ctx.packageName}/${Teclas::class.java.name}"
            val actuales = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.filter { it.isNotBlank() } ?: emptyList()
            if (actuales.any { it == nuestro || it == "${ctx.packageName}/.Teclas" }) return
            try {
                android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (actuales + nuestro).joinToString(":"))
                android.provider.Settings.Secure.putInt(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                Log.i(TAG, "servicio de la tuerca reactivado")
            } catch (e: Exception) {
                Log.w(TAG, "no se pudo reactivar el servicio de la tuerca: $e")
            }
        }
        // La tuerca del mando Thomson llega como BUTTON_14 (201, scan 384); SETTINGS por si otro mando.
        val TECLAS_AJUSTES = setOf(KeyEvent.KEYCODE_BUTTON_14, KeyEvent.KEYCODE_SETTINGS)
    }
}
