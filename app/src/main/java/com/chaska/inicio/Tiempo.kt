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

    fun pedir(ciudad: String, p: SharedPreferences, alListo: (String?) -> Unit) {
        Thread {
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
                val t = c.getDouble("temperature_2m").roundToInt()
                "${icono(c.getInt("weather_code"))}  $t° · ${p.getString("geo_nombre", ciudad)}"
            } catch (e: Exception) {
                null
            }
            principal.post { alListo(texto) }
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

    /** Códigos WMO de Open-Meteo. */
    private fun icono(codigo: Int) = when (codigo) {
        0 -> "☀"
        1, 2 -> "🌤"
        3 -> "☁"
        45, 48 -> "🌫"
        in 51..67, in 80..82 -> "🌧"
        in 71..77, 85, 86 -> "❄"
        in 95..99 -> "⛈"
        else -> "☁"
    }
}
