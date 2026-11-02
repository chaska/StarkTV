package com.chaska.inicio

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.chaska.inicio.pap.Pap
import com.chaska.inicio.pap.SelectorPap

/**
 * Servicio de accesibilidad que recoge la tecla de ajustes (la tuerca) del mando antes que el servicio
 * de MediaTek (com.mediatek.hotkey.dispatcher), que con TV en pantalla abre imagen y sonido.
 * Solo actúa con Stark TV delante o con el PAP en marcha; en Live TV y en el resto, la tecla sigue igual.
 * Se activa por adb: settings put secure enabled_accessibility_services com.chaska.inicio/.Teclas
 */
class Teclas : AccessibilityService() {
    private var delante: String? = null
    private var largo = false
    /**
     * Android solo espera ~0,5 s a que el servicio diga si se queda la tecla; si tarda más, se la pasa a MediaTek
     * (y sale su menú de imagen y sonido). Por eso aquí solo se decide y se contesta al momento: abrir el selector
     * (que lee la lista de apps) o cerrar el PAP se hace justo después (02/11).
     */
    private val luego = android.os.Handler(android.os.Looper.getMainLooper())
    private fun despues(accion: () -> Unit) { luego.post(accion) }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) e.packageName?.toString()?.let { delante = it }
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() { super.onServiceConnected(); servicio = this }
    override fun onDestroy() { if (servicio === this) servicio = null; SelectorPap.actual?.cerrar(); super.onDestroy() }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0)
            // Solo para depurar (adb shell setprop log.tag.InicioTV DEBUG): no se registra lo que se pulsa.
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "tecla ${e.keyCode} ${KeyEvent.keyCodeToString(e.keyCode)} scan=${e.scanCode} delante=$delante")
        // Con el PAP en marcha, Android TV no vuelve al inicio con la tecla Inicio (intenta abrir
        // «todas las apps» y falla). Inicio corto abre el selector; mantenido, sale del PAP.
        // Selector del PAP abierto (no tiene foco, para no molestar a la app de debajo): las teclas van a él.
        val selector = SelectorPap.actual
        if (selector != null && SelectorPap.visible && e.keyCode !in TECLAS_AJUSTES && e.keyCode !in TECLAS_SISTEMA) {
            val copia = KeyEvent(e)
            despues { selector.tecla(copia) }
            return true
        }
        // Durante el PAP la tuerca cambia de parte (abre el selector) y mantenida cierra el PAP.
        // Es la tecla que llega siempre: Inicio no llega si una app tiene un vídeo a pantalla completa.
        // La estrella del mando: en el PAP hace de tuerca; en el inicio de Stark TV abre sus favoritos.
        if (e.keyCode in TECLAS_ESTRELLA) {
            val pap = Pap.activo(Ajustes(this))
            if (!pap && !MainActivity.enInicio) return false            // en otra app, lo de siempre
            if (pap) {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 1) { largo = true; despues { Pap.cerrar(this, Ajustes(this)) } }
                if (e.action == KeyEvent.ACTION_UP) {
                    if (!largo) despues { val s = SelectorPap.actual; if (s != null && SelectorPap.visible) s.seguir() else Pap.selector(this) }
                    largo = false
                }
            } else if (e.action == KeyEvent.ACTION_UP) despues {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_FAVORITOS, true))
            }
            return true
        }
        if (e.keyCode in TECLAS_AJUSTES && Pap.activo(Ajustes(this))) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 1) { largo = true; despues { Pap.cerrar(this, Ajustes(this)) } }
            if (e.action == KeyEvent.ACTION_UP) {
                if (!largo) despues { val s = SelectorPap.actual; if (s != null && SelectorPap.visible) s.seguir() else Pap.selector(this) }
                largo = false
            }
            return true
        }
        if (e.keyCode == KeyEvent.KEYCODE_HOME && Pap.activo(Ajustes(this))) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 1) { largo = true; despues { Pap.cerrar(this, Ajustes(this)) } }
            if (e.action == KeyEvent.ACTION_UP) { if (!largo) despues { Pap.selector(this) }; largo = false }
            return true
        }
        if (e.keyCode !in TECLAS_AJUSTES) return false
        val nuestro = MainActivity.enInicio || delante == packageName || Pap.activo(Ajustes(this))
        if (!nuestro) return false
        if (e.action == KeyEvent.ACTION_UP) despues {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_AJUSTES, true))
        }
        return true
    }

    companion object {
        private const val TAG = "InicioTV"
        /** El servicio en marcha: el selector del PAP es una capa suya. */
        @Volatile var servicio: Teclas? = null

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
        // La estrella del mando Thomson llega como ZOOM_OUT (169, scan 240); MediaTek la usa para sus favoritos.
        val TECLAS_ESTRELLA = setOf(KeyEvent.KEYCODE_ZOOM_OUT)

        /**
         * Android a veces deja el servicio «conectándose» sin llegar a arrancarlo (02/11, tras actualizar): está
         * activado pero no recibe teclas y la tuerca se va a MediaTek. Si a los 5 s no está en marcha, se apaga
         * y se vuelve a encender (con WRITE_SECURE_SETTINGS, concedido por adb).
         */
        fun vigilar(ctx: android.content.Context) {
            val app = ctx.applicationContext
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (servicio != null) return@postDelayed
                if (app.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return@postDelayed
                val cr = app.contentResolver
                val nuestro = "${app.packageName}/${Teclas::class.java.name}"
                val actuales = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    ?.split(':')?.filter { it.isNotBlank() } ?: emptyList()
                val otros = actuales.filter { it != nuestro && it != "${app.packageName}/.Teclas" }
                try {
                    android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, otros.joinToString(":"))
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (otros + nuestro).joinToString(":"))
                        android.provider.Settings.Secure.putInt(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                        Log.i(TAG, "servicio de la tuerca vuelto a arrancar (estaba atascado)")
                    }, 1500)
                } catch (e: Exception) { Log.w(TAG, "no se pudo volver a arrancar el servicio de la tuerca: $e") }
            }, 5000)
        }
        /** Teclas que nunca se quedan en el selector. */
        private val TECLAS_SISTEMA = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_HOME)
    }
}
