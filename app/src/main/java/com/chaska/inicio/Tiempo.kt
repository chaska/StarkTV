package com.chaska.inicio

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/** Tiempo actual de Open-Meteo (gratis, sin clave). La ciudad se busca una vez y se guarda en "geo_*". */
object Tiempo {
    private val principal = Handler(Looper.getMainLooper())

    /** Devuelve "22° · Madrid" y el código WMO (para el icono), o null si no hay red o ciudad. */
    fun pedir(ciudad: String, p: SharedPreferences, alListo: (String?, Int) -> Unit) {
        Thread {
            var codigo = 3
            val texto = try {
                if (p.getString("geo_ciudad", null) != ciudad) {
                    val q = URLEncoder.encode(ciudad, "UTF-8")
                    val r = json("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=es&format=json")
                        .optJSONArray("results")?.optJSONObject(0)
                        ?: throw IllegalStateException("Ciudad no encontrada")
                    p.edit()
                        .putString("geo_ciudad", ciudad)
                        .putString("geo_nombre", r.getString("name"))
                        .putFloat("geo_lat", r.getDouble("latitude").toFloat())
                        .putFloat("geo_lon", r.getDouble("longitude").toFloat())
                        .apply()
                }
                val lat = p.getFloat("geo_lat", 0f)
                val lon = p.getFloat("geo_lon", 0f)
                val c = json("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&timezone=auto")
                    .getJSONObject("current")
                codigo = c.getInt("weather_code")
                "${c.getDouble("temperature_2m").roundToInt()}° · ${p.getString("geo_nombre", ciudad)}"
            } catch (e: Exception) {
                null
            }
            principal.post { alListo(texto, codigo) }
        }.start()
    }

    private fun json(url: String): JSONObject {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 8000
        con.readTimeout = 8000
        try {
            return JSONObject(con.inputStream.bufferedReader().use { it.readText() })
        } finally {
            con.disconnect()
        }
    }
}
