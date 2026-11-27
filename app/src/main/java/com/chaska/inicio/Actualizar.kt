package com.chaska.inicio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Buscar e instalar la versión nueva de Stark TV desde la release de GitHub (el repo es público), sin PC.
 * Con «Permisos avanzados (adb)» conectado se instala sola (`pm install -r`, sin el aviso de Android);
 * sin adb hace falta conectarlo primero, como en Apps que sobran o Liberar caché.
 * Si alguien manipulara el APK de la release, Android no deja instalarlo encima: la firma no coincidiría
 * con la tuya y la instalación fallaría sola, sin que esto tenga que comprobarlo.
 */
object Actualizar {
    private const val TAG = "InicioTV"
    private const val URL_RELEASES = "https://api.github.com/repos/chaska/StarkTV/releases/latest"
    private val principal = Handler(Looper.getMainLooper())

    class Nueva(val version: String, val urlApk: String, val notas: String)

    /** [listo]: la versión nueva encontrada (null si ya tienes la última), o un texto de error. */
    fun comprobar(ctx: Context, listo: (nueva: Nueva?, error: String?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            try {
                val j = json(URL_RELEASES)
                val version = j.getString("tag_name").removePrefix("v")
                val actual = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
                if (!esMasNueva(version, actual)) { principal.post { listo(null, null) }; return@Thread }
                val apk = (0 until j.getJSONArray("assets").length())
                    .map { j.getJSONArray("assets").getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk") }
                    ?: throw IllegalStateException("la release no trae un APK")
                val notas = j.optString("body", "").lineSequence().map { it.trim() }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: "Versión $version"
                principal.post { listo(Nueva(version, apk.getString("browser_download_url"), notas), null) }
            } catch (e: Exception) {
                Log.w(TAG, "actualizar: no se pudo comprobar: $e")
                principal.post { listo(null, "Sin conexión con GitHub") }
            }
        }.start()
    }

    /** "0.9.41" > "0.9.5" por partes numéricas, no como texto (si no, "0.9.5" parecería mayor). */
    private fun esMasNueva(nueva: String, actual: String): Boolean {
        val n = nueva.split('.').map { it.toIntOrNull() ?: 0 }
        val a = actual.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(n.size, a.size)) {
            val x = n.getOrElse(i) { 0 }; val y = a.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Descarga el APK a la carpeta propia de la app y lo instala; [progreso] va contando los pasos. */
    fun descargar(ctx: Context, nueva: Nueva, progreso: (String) -> Unit) {
        val app = ctx.applicationContext
        if (!Adb.conectado) { progreso("Conecta «Permisos avanzados (adb)» primero (Sistema)"); return }
        Thread {
            // Siempre el mismo nombre: no hace falta limpiar versiones viejas y la ruta queda fija.
            val archivo = File(app.getExternalFilesDir(null), "actualizacion.apk")
            try {
                principal.post { progreso("Descargando…") }
                val con = URL(nueva.urlApk).openConnection() as HttpURLConnection
                con.connectTimeout = 10_000; con.readTimeout = 20_000
                con.instanceFollowRedirects = true
                con.inputStream.use { entrada -> archivo.outputStream().use { salida -> entrada.copyTo(salida) } }
                con.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "actualizar: descarga fallida: $e")
                principal.post { progreso("No se pudo descargar") }
                return@Thread
            }
            principal.post { progreso("Instalando…") }
            Adb.enSegundoPlano(app, "pm install -r ${archivo.absolutePath}") { r ->
                progreso(if (r?.contains("Success") == true) "Instalada la ${nueva.version}: reinicia Stark TV"
                else "La TV no ha dejado instalarla; prueba a mano")
            }
        }.start()
    }

    private fun json(url: String): JSONObject {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 8000; con.readTimeout = 10_000
        con.setRequestProperty("Accept", "application/vnd.github+json")
        try { return JSONObject(con.inputStream.bufferedReader().use { it.readText() }) } finally { con.disconnect() }
    }
}
