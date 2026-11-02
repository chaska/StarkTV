package com.chaska.inicio

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Ventanita flotante con la TDT en directo (sin sonido) y el canal que se ve.
 * Lee los canales de la lista de Android (permiso READ_EPG_DATA) y sintoniza en el sintonizador
 * principal (HW0; el HW1 es el de imagen dentro de imagen y en esta Thomson da error).
 * CH+ / CH− del mando cambian de canal; el último se recuerda en "tv_canal".
 * Si la TV no da canales (esta Thomson solo se los enseña a Live TV), se pide la fila del canal
 * por número (el actual era la 1533) en HW0 y luego en HW1.
 */
class VentanaTv(ctx: Context, sel: Int, private val prefs: SharedPreferences) : FrameLayout(ctx) {
    private class Canal(val id: Long, val numero: String, val nombre: String, val entrada: String)

    private val tv = TvView(ctx)
    private val estado = TextView(ctx).apply {
        textSize = 13f
        setTextColor(0xCCFFFFFF.toInt())
        gravity = Gravity.CENTER
        text = "Conectando con la TDT…"
    }.conFuente()
    private val rotulo = TextView(ctx).apply {
        text = "TDT en directo"
        textSize = 12f
        setTextColor(Color.WHITE)
        maxLines = 1
        setPadding(ctx.dp(10), ctx.dp(14), ctx.dp(10), ctx.dp(6))
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xB3000000.toInt(), 0))
    }.conFuente(medio = true)
    /** Enseña u oculta el nombre del canal o de la fuente (ajuste «Nombre del canal y la fuente»). */
    fun mostrarRotulo(si: Boolean) { rotulo.visibility = if (si) View.VISIBLE else View.GONE }

    private var canales: List<Canal> = emptyList()
    private var actual = -1
    private var entrada: String? = null
    private var sintonizadores: List<String> = emptyList()
    /** Sin lista de canales: se prueba por número de fila del canal (Live TV usa ~1500). */
    private var idCanal = -1L
    private var intento = 0
    private val siguiente = Runnable { probarSinCanal() }
    private val vigia = Runnable { if (estado.visibility == View.VISIBLE) otroIntento() }
    /** Ya hubo imagen y se perdió (en el PAP, otra app que reproduce vídeo se queda la vía de vídeo del chip). */
    private var tuvoImagen = false
    private var recuperaciones = 0
    /** Tras perder la imagen se pasa al segundo sintonizador (HW1, el de imagen dentro de imagen) y no se vuelve al HW0. */
    private var enSegundo = false
    private val recuperar = Runnable {
        if (estado.visibility != View.VISIBLE) return@Runnable
        val hw1 = sintonizadores.firstOrNull { it != entrada }
        if (enSegundo || hw1 == null) {
            // Ni el segundo sintonizador: el chip no deja TV y el vídeo de otra app a la vez. Se avisa y no se pelea.
            Log.i(TAG, "sin imagen tampoco en ${hw1 ?: "el segundo sintonizador"}: se deja de intentar")
            estado.text = "La TV y el vídeo de la otra app no caben a la vez"
            return@Runnable
        }
        enSegundo = true
        Log.i(TAG, "se perdió la imagen (otra app con vídeo): se pasa a $hw1")
        val uri = canales.getOrNull(actual)?.let { TvContract.buildChannelUri(it.id) } ?: TvContract.buildChannelUri(idCanal)
        try { tv.tune(hw1, uri) } catch (e: Exception) { Log.w(TAG, "tune en $hw1 falló: $e") }
    }

    init {
        isFocusable = true
        isClickable = true
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, ctx.dp(14).toFloat())
        }
        elevation = ctx.dp(16).toFloat()
        setBackgroundColor(Color.BLACK)
        addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(estado, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(rotulo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        mostrarRotulo(prefs.getBoolean("tv_rotulo", true))
        // Marco: borde fino, que se enciende del color de la selección con el foco.
        foreground = GradientDrawable().apply { cornerRadius = ctx.dp(14).toFloat(); setStroke(ctx.dp(1), 0x40FFFFFF) }
        setOnFocusChangeListener { v, f ->
            (foreground as GradientDrawable).setStroke(ctx.dp(if (f) 3 else 1), if (f) sel else 0x40FFFFFF)
            v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(200).start()
            outlineSpotShadowColor = if (f) sel else Color.BLACK
            elevation = ctx.dp(if (f) 24 else 16).toFloat()
        }
        tv.setStreamVolume(0f)
        tv.setCallback(object : TvView.TvInputCallback() {
            override fun onVideoAvailable(inputId: String) {
                if (sonda != null) { terminarSonda(1); return }
                Log.i(TAG, "imagen disponible en $inputId")
                tuvoImagen = true
                removeCallbacks(vigia)
                estado.visibility = View.GONE
            }
            override fun onVideoUnavailable(inputId: String, reason: Int) {
                if (sonda != null) {
                    // Sintonizando o cargando: se espera. Solo sonido: es una radio (existe, pero no se guarda).
                    when (reason) {
                        TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING, TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING -> {}
                        TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY -> terminarSonda(0)
                        else -> terminarSonda(-1)
                    }
                    return
                }
                Log.i(TAG, "sin imagen en $inputId, motivo $reason")
                estado.visibility = View.VISIBLE
                // Sin imagen después de tenerla: otra app con vídeo (YouTube) se ha quedado el sintonizador principal.
                // Se prueba una vez el segundo (02/11: volver al principal era un tira y afloja con YouTube).
                if (RECUPERAR && tuvoImagen && reason != TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING && recuperaciones < MAX_RECUPERAR) {
                    recuperaciones++
                    removeCallbacks(recuperar); postDelayed(recuperar, 2000)
                }
                estado.text = when (reason) {
                    TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING -> "Sintonizando…"
                    TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL -> "Sin señal"
                    TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING -> "Cargando…"
                    TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY -> "Radio · solo sonido"
                    else -> "Sin imagen"
                }
            }
            override fun onConnectionFailed(inputId: String) { if (sonda != null) { terminarSonda(-1); return }; Log.w(TAG, "conexión fallida $inputId"); otroIntento() }
            override fun onDisconnected(inputId: String) { if (sonda != null) { terminarSonda(-1); return }; Log.w(TAG, "desconectado $inputId"); otroIntento() }
        })
    }

    /** [fuente]: 0 Antena, 1-3 HDMI, 4 AV. Por defecto, la de la ventanita del inicio. */
    fun encender(fuente: Int = prefs.getInt("tv_fuente", 0), conSonido: Boolean = prefs.getBoolean("tv_sonido", false)) {
        val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        sonido(conSonido)
        if (fuente > 0) { externa(tim, fuente); return }
        prepararSintonizadores(tim)
        canales = leerCanales(sintonizadores.toSet())
        Log.i(TAG, "sintonizadores=$sintonizadores canales=${canales.size}")
        if (canales.isNotEmpty()) {
            val guardado = prefs.getLong("tv_canal", -1)
            actual = canales.indexOfFirst { it.id == guardado }.takeIf { it >= 0 } ?: 0
            sintonizar()
        } else if (entrada != null) {
            intento = 0
            idCanal = prefs.getLong("tv_id", 1533)
            // Con la lista propia (Ajustes › Canales de la antena), siempre un canal de ella.
            propios().let { l -> if (l.isNotEmpty() && idCanal !in l) idCanal = l.first() }
            // El mismo canal que se vio la última vez en la TV (Live TV), si sabemos en qué fila está.
            filaDe(prefs.getInt(K_ULTIMO, -1))?.let { idCanal = it }
            probarSinCanal()
        } else {
            estado.text = "Esta TV no deja ver el sintonizador"
        }
    }

    private fun prepararSintonizadores(tim: TvInputManager?) {
        // Solo los sintonizadores de la TV: otras apps (canales FAST, VLC…) también se anuncian como «sintonizador».
        // Los del fabricante primero (en MediaTek, com.mediatek.tvinput); en otras marcas, todos.
        val todos = tim?.tvInputList?.filter { it.type == TvInputInfo.TYPE_TUNER && !it.isPassthroughInput }?.map { it.id } ?: emptyList()
        sintonizadores = todos.filter { it.startsWith("com.mediatek.tvinput/") }.ifEmpty { todos }.sorted()
        // El principal primero (HW0).
        entrada = sintonizadores.firstOrNull { it.endsWith("HW0") } ?: sintonizadores.firstOrNull()
    }

    /** Sonido de la ventana: apagado por defecto para no pisar al resto. */
    fun sonido(con: Boolean) = tv.setStreamVolume(if (con) 1f else 0f)

    /** HDMI 1-3 o AV: entradas directas (passthrough), no necesitan canal. */
    private fun externa(tim: TvInputManager?, fuente: Int) {
        val todas = tim?.tvInputList ?: emptyList()
        val hdmi = todas.filter { it.type == TvInputInfo.TYPE_HDMI }.map { it.id }.sorted()
        val av = todas.filter { it.type == TvInputInfo.TYPE_COMPOSITE }.map { it.id }.sorted()
        val id = if (fuente <= 3) hdmi.getOrNull(fuente - 1) else av.firstOrNull()
        canales = emptyList()
        entrada = null
        rotulo.text = listOf("HDMI 1", "HDMI 2", "HDMI 3", "AV").getOrElse(fuente - 1) { "Entrada" }
        if (id == null) { estado.visibility = View.VISIBLE; estado.text = "Esta TV no tiene esa entrada"; return }
        estado.visibility = View.VISIBLE
        estado.text = "Conectando…"
        Log.i(TAG, "entrada externa $id")
        try { tv.tune(id, TvContract.buildChannelUriForPassthroughInput(id)) }
        catch (e: Exception) { estado.text = "La TV no deja mostrar esta entrada aquí" }
    }

    fun apagar() {
        removeCallbacks(recuperar)
        tuvoImagen = false; recuperaciones = 0; enSegundo = false
        removeCallbacks(siguiente)
        removeCallbacks(vigia)
        try { tv.reset() } catch (e: Exception) { }
    }

    /** CH+ / CH−. Sin lista, va al número de fila de al lado. */
    fun cambiarCanal(paso: Int): Boolean {
        if (canales.isEmpty()) {
            if (entrada == null) return false
            val l = propios()
            idCanal = if (l.isNotEmpty()) l[((l.indexOf(idCanal).coerceAtLeast(0) + paso) % l.size + l.size) % l.size]
                else (idCanal + paso).coerceAtLeast(1)
            prefs.edit().putLong("tv_id", idCanal).apply()
            intento = 0
            probarSinCanal()
            return true
        }
        actual = (actual + paso + canales.size) % canales.size
        sintonizar()
        return true
    }

    private fun leerCanales(entradas: Set<String>): List<Canal> = try {
        val l = mutableListOf<Canal>()
        context.contentResolver.query(TvContract.Channels.CONTENT_URI, arrayOf(
            TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER,
            TvContract.Channels.COLUMN_DISPLAY_NAME, TvContract.Channels.COLUMN_INPUT_ID), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val ent = c.getString(3) ?: continue
                if (entradas.isNotEmpty() && ent !in entradas) continue
                l += Canal(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", ent)
            }
        }
        // Por número de canal (1, 2, 3… no 1, 10, 11…).
        l.sortedWith(compareBy({ it.numero.substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }, { it.numero }))
    } catch (e: Exception) {
        Log.w(TAG, "no se pueden leer los canales: $e")
        emptyList()
    }

    private fun sintonizar() {
        val c = canales.getOrNull(actual) ?: return
        prefs.edit().putLong("tv_canal", c.id).apply()
        rotulo.text = listOf(c.numero, c.nombre).filter { it.isNotBlank() }.joinToString("  ·  ").ifBlank { "TDT en directo" }
        estado.visibility = View.VISIBLE
        estado.text = "Sintonizando…"
        Log.i(TAG, "sintonizando ${c.numero} ${c.nombre} (${c.id}) en ${c.entrada}")
        try {
            tv.tune(c.entrada, TvContract.buildChannelUri(c.id))
        } catch (e: Exception) {
            Log.w(TAG, "tune falló: $e")
            estado.text = "La TV no deja mostrar la TDT aquí"
        }
    }

    private fun probarSinCanal() {
        removeCallbacks(vigia)
        val hw0 = entrada ?: return
        val hw1 = sintonizadores.firstOrNull { it != hw0 } ?: hw0
        val (id, uri) = when (intento) {
            0 -> hw0 to TvContract.buildChannelUri(idCanal)
            1 -> hw1 to TvContract.buildChannelUri(idCanal)
            // Sin canal (null) NO: en esta Thomson hace caer el servicio del sintonizador (com.mediatek.tvinput).
            else -> { estado.visibility = View.VISIBLE; estado.text = "La TV no deja mostrar la TDT aquí"; return }
        }
        val l = propios()
        rotulo.text = when {
            numeros()[idCanal] != null -> "Canal ${numeros()[idCanal]}"
            l.isNotEmpty() && idCanal in l -> "Canal ${l.indexOf(idCanal) + 1}"
            intento <= 1 -> "Canal $idCanal"
            else -> "TDT en directo"
        }
        Log.i(TAG, "sin lista de canales: intento $intento en $id con $uri")
        try {
            tv.tune(id, uri)
        } catch (e: Exception) {
            otroIntento()
            return
        }
        // Si en 10 s no hay imagen, se prueba en el otro sintonizador.
        removeCallbacks(vigia)
        postDelayed(vigia, 10_000)
    }

    private fun otroIntento() {
        if (canales.isNotEmpty()) return            // con canales no hay plan B: se queda el aviso
        intento++
        removeCallbacks(vigia)
        removeCallbacks(siguiente)
        post(siguiente)
    }

    // ---------- Búsqueda de canales propia ----------
    // Cuando la TV no deja leer su lista de canales (solo a su app de TV, como esta Thomson), se reconstruye:
    // se sintoniza cada fila de la lista del sistema y se guardan las que dan imagen, en orden. Las radios
    // (solo sonido) se saltan. Se sube desde el canal conocido y luego se baja, hasta 15 filas seguidas sin nada.

    private var sonda: ((Int) -> Unit)? = null
    private val sondaTarde = Runnable { terminarSonda(-1) }
    private var buscando = false

    private fun terminarSonda(r: Int) {
        val s = sonda ?: return
        sonda = null
        removeCallbacks(sondaTarde)
        s(r)
    }

    /** La lista propia guardada (filas del sistema en el orden elegido) y, si se quiere, las radios detrás. */
    fun propios(): List<Long> = filas(K_PROPIOS) + if (conRadios()) radios() else emptyList()

    private fun filas(k: String) = (prefs.getString(k, "") ?: "").split(',').mapNotNull { it.toLongOrNull() }

    /** Las radios encontradas (en el orden de la app de TV si se sabe). */
    fun radios(): List<Long> = filas(K_RADIOS)
    fun conRadios() = prefs.getBoolean(K_CON_RADIOS, false)
    fun ponerRadios(si: Boolean) = prefs.edit().putBoolean(K_CON_RADIOS, si).apply()

    fun guardarPropios(l: List<Long>) = prefs.edit().putString(K_PROPIOS, l.joinToString(",")).apply()

    /** Número de canal (el de la app de TV) de cada fila, si la TV lo dice (MediaTek). */
    fun numeros(): Map<Long, Int> = (prefs.getString(K_NUMEROS, "") ?: "").split(',').mapNotNull { e ->
        val partes = e.split(':')
        if (partes.size != 2) return@mapNotNull null
        val f = partes[0].toLongOrNull() ?: return@mapNotNull null
        val n = partes[1].toIntOrNull() ?: return@mapNotNull null
        f to n
    }.toMap()

    private fun filaDe(numero: Int): Long? = if (numero < 0) null else numeros().entries.firstOrNull { it.value == numero }?.key

    /** Mientras se busca: el número que anuncia MediaTek para la fila que acaba de dar imagen. */
    private var numeroVisto: ((Int) -> Unit)? = null
    private val avisoNumero = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            val n = numeroMtk(i) ?: return
            numeroVisto?.let { numeroVisto = null; it(n) }
        }
    }

    /** Busca los canales; [avanza] (fila probada, canales encontrados) y [fin] con la lista nueva (ya guardada). */
    fun buscarCanales(avanza: (Long, Int) -> Unit, fin: (List<Long>) -> Unit) {
        val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        prepararSintonizadores(tim)
        val hw = entrada ?: return fin(emptyList())
        removeCallbacks(siguiente); removeCallbacks(vigia)
        sonido(false)
        buscando = true
        val inicio = prefs.getLong("tv_id", 1533).coerceAtLeast(1)
        val vistos = sortedSetOf<Long>()
        val radiosVistas = sortedSetOf<Long>()
        val nums = mutableMapOf<Long, Int>()
        try { context.registerReceiver(avisoNumero, android.content.IntentFilter(AVISO_MTK), Context.RECEIVER_EXPORTED) } catch (e: Exception) { }
        var subiendo = true
        var fallos = 0
        fun probar(id: Long) {
            if (!buscando) return
            if (id < 1 || fallos >= MAX_FALLOS) {
                if (subiendo) { subiendo = false; fallos = 0; return probar(inicio - 1) }
                buscando = false
                try { context.unregisterReceiver(avisoNumero) } catch (e: Exception) { }
                // Con número (MediaTek), en el mismo orden que la app de TV; si no, en el orden encontrado.
                val l = vistos.sortedWith(compareBy({ nums[it] ?: Int.MAX_VALUE }, { it }))
                val rs = radiosVistas.sortedWith(compareBy({ nums[it] ?: Int.MAX_VALUE }, { it }))
                if (l.isNotEmpty()) {
                    guardarPropios(l)
                    prefs.edit().putString(K_RADIOS, rs.joinToString(",")).apply()
                    prefs.edit().putString(K_NUMEROS, nums.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()
                }
                Log.i(TAG, "búsqueda de canales: ${l.size} canales y ${rs.size} radios, ${nums.size} con número")
                return fin(l)
            }
            estado.visibility = View.VISIBLE
            estado.text = "Probando $id…"
            sonda = { r ->
                if (r == 1) vistos += id
                if (r == 0) radiosVistas += id
                fallos = if (r >= 0) 0 else fallos + 1
                avanza(id, vistos.size)
                val seguir = Runnable { numeroVisto = null; probar(if (subiendo) id + 1 else id - 1) }
                // Si el aviso no llega a tiempo, el número se saca del registro de MediaTek («find channel id…==>fila»),
                // con la depuración. En otras TVs no hay ni aviso ni registro: el canal se queda sin número.
                val delRegistro = Runnable {
                    numeroVisto = null
                    val app = context.applicationContext
                    Thread {
                        val log = Adb.shell(app, "logcat -d -t 800")
                        val n = log?.let { Regex("find channel id(\\d+)==>$id\\b").findAll(it).lastOrNull()?.groupValues?.get(1)?.toLongOrNull() }
                            ?.let { (it shr 18).toInt() }?.takeIf { it in 1..9999 }
                        post { if (n != null) nums[id] = n; probar(if (subiendo) id + 1 else id - 1) }
                    }.start()
                }
                if (r >= 0) {
                    // Se espera el aviso de MediaTek con su número de canal (1,5 s no siempre bastaba: 15 de 33).
                    numeroVisto = { n -> nums[id] = n; removeCallbacks(delRegistro); post(seguir) }
                    postDelayed(delRegistro, 3000)
                } else post(seguir)
            }
            postDelayed(sondaTarde, ESPERA_SONDA)
            try { tv.tune(hw, TvContract.buildChannelUri(id)) } catch (e: Exception) { terminarSonda(-1) }
        }
        probar(inicio)
    }

    fun pararBusqueda() {
        buscando = false; sonda = null; numeroVisto = null; removeCallbacks(sondaTarde)
        try { context.unregisterReceiver(avisoNumero) } catch (e: Exception) { }
    }

    /** Pone en la ventana una fila concreta (para probar un canal de la lista). */
    fun verFila(id: Long) {
        pararBusqueda()
        val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        prepararSintonizadores(tim)
        idCanal = id; intento = 0
        prefs.edit().putLong("tv_id", id).apply()
        probarSinCanal()
    }

    companion object {
        const val TAG = "InicioTV"
        const val K_PROPIOS = "canales_propios"
        const val K_NUMEROS = "canales_numeros"
        const val K_RADIOS = "canales_radios"
        const val K_CON_RADIOS = "canales_con_radios"
        /** Último canal visto en la TV (número de la app de TV), según los avisos de MediaTek. */
        const val K_ULTIMO = "tv_ultimo_numero"
        /** MediaTek anuncia cada cambio de canal con channel_id = número de canal × 2^18 + 129 (visto en la Thomson). */
        const val AVISO_MTK = "mtk.intent.event_update_pf"

        fun numeroMtk(i: android.content.Intent): Int? {
            @Suppress("DEPRECATION")
            val id = i.extras?.get("channel_id")?.toString()?.toLongOrNull() ?: return null
            val n = (id shr 18).toInt()
            return n.takeIf { it in 1..9999 }
        }

        private fun leerNumeros(p: SharedPreferences): MutableMap<Long, Int> = (p.getString(K_NUMEROS, "") ?: "").split(',').mapNotNull { e ->
            val partes = e.split(':')
            if (partes.size != 2) return@mapNotNull null
            val f = partes[0].toLongOrNull() ?: return@mapNotNull null
            val n = partes[1].toIntOrNull() ?: return@mapNotNull null
            f to n
        }.toMap().toMutableMap()

        /** ¿Sabemos ya en qué fila está el canal [numero] de la TV? */
        fun filaConocida(p: SharedPreferences, numero: Int) = numero in leerNumeros(p).values

        /** Apunta que el canal [numero] de la TV está en la fila [fila] (lo aprende al verlo en Live TV). */
        fun aprenderFila(p: SharedPreferences, fila: Long, numero: Int) {
            val m = leerNumeros(p)
            m.entries.removeAll { it.value == numero }
            m[fila] = numero
            p.edit().putString(K_NUMEROS, m.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()
        }
        private const val MAX_FALLOS = 15
        private const val ESPERA_SONDA = 4000L
        private const val MAX_RECUPERAR = 2
        /** Apagado (02/11): volver a sintonizar al perder la imagen le quitaba el vídeo a la app de la otra parte del PAP. */
        private const val RECUPERAR = false
    }
}
