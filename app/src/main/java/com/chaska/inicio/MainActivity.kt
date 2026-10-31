package com.chaska.inicio

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import com.chaska.inicio.pap.Pap
import java.io.File

/**
 * Pantalla de inicio: fondo + luces, botón Ajustes, reloj con el tiempo, ventanita de TDT (opcional)
 * y el dock (Favoritos primero y luego todas las apps) en una de 6 posiciones.
 */
class MainActivity : Activity() {
    private val aj by lazy { Ajustes(this) }
    private val lista by lazy { ListaApps(this, aj.p) }

    private lateinit var fondo: Fondo
    private lateinit var velo: View
    private lateinit var luces: Luces
    private lateinit var contenido: FrameLayout
    private lateinit var botonAjustes: TextView
    private lateinit var botonPap: TextView
    private lateinit var hora: TextClock
    private lateinit var horaSalva: TextClock
    private lateinit var version: TextView
    private lateinit var fecha: TextClock
    private lateinit var tiempo: TextView
    private lateinit var titulo: TextView
    private lateinit var fila: LinearLayout
    private lateinit var aviso: TextView
    private lateinit var ventana: VentanaTv
    private lateinit var salva: LinearLayout
    private lateinit var salvaTiempo: TextView
    private var zonaDock: LinearLayout? = null
    private lateinit var bloqueReloj: LinearLayout

    private var dialogo: Dialog? = null
    /** Interruptor «Launcher por defecto» de Ajustes, para ponerlo al día al volver de los ajustes del sistema. */
    private var swPorDefecto: Interruptor? = null
    private var moviendo: Ficha? = null
    private var firma = ""
    private var textoTiempo: String? = null
    private var codigoTiempo = 3
    private var ultimoTiempo = 0L
    private var ultimaTecla = SystemClock.uptimeMillis()
    private var ultimaRotacion = SystemClock.uptimeMillis()
    private var enSalva = false
    private val reloj = Handler(Looper.getMainLooper())
    private val tic = object : Runnable {
        override fun run() {
            comprobarTiempos()
            reloj.postDelayed(this, 30_000)
        }
    }
    private val ocultarAviso = Runnable { if (moviendo == null) aviso.visibility = View.GONE }

    private val vertical get() = Ajustes.esVertical(aj.dock)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fuentes.actual = aj.fuente
        Tema.color = aj.colorSel
        Tema.opacidad = aj.opacidad
        Tema.oscuridad = aj.oscuridad
        val raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val capa = FrameLayout(this)
        raiz.addView(capa, lleno())
        fondo = Fondo(this, capa)
        velo = View(this)
        raiz.addView(velo, lleno())
        luces = Luces(this)
        raiz.addView(luces, lleno())
        contenido = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        raiz.addView(contenido, lleno())
        construirInicio()
        salva = construirSalva()
        raiz.addView(salva, lleno())
        setContentView(raiz)
        // Tras cambiar la fuente la pantalla se rehace: se vuelve a abrir Ajustes donde estaba.
        reabrirLauncher?.let { k ->
            reabrirLauncher = null
            contenido.post { abrirLauncher(k) }
        }
    }

    override fun onStart() {
        super.onStart()
        Teclas.asegurarActivo(this)
        // Una vez por arranque: reponer por adb los ajustes que la TV pierde al reiniciar.
        if (!ajustesRepuestos) { ajustesRepuestos = true; Adb.reponerAjustes(this) }
        swPorDefecto?.encendido = esPorDefecto()
        aplicar()
        fondo.mostrar(aj.fondo)
        pintarCarrusel()
        if (aj.tvVentana) ventana.encender()
        ultimaTecla = SystemClock.uptimeMillis()
        reloj.removeCallbacks(tic)
        reloj.post(tic)
    }

    override fun onStop() {
        // Fuera del inicio el fondo y la TDT no gastan nada.
        fondo.liberar()
        ventana.apagar()
        reloj.removeCallbacks(tic)
        soltar()
        salirSalva(false)
        super.onStop()
    }

    override fun onPause() { enInicio = false; super.onPause() }

    override fun onResume() {
        super.onResume()
        enInicio = true
        // La tuerca del mando (servicio Teclas) pide abrir los ajustes de Stark TV.
        if (intent.getBooleanExtra(EXTRA_AJUSTES, false)) {
            intent.removeExtra(EXTRA_AJUSTES)
            dialogo?.dismiss()
            contenido.post { abrirAjustes() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AJUSTES, false)) return      // lo abre onResume
        // Pulsar Inicio estando ya aquí: cierra todo y vuelve al principio del dock.
        dialogo?.dismiss()
        soltar()
        salirSalva(false)
        fila.getChildAt(0)?.requestFocus()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        ultimaTecla = SystemClock.uptimeMillis()
        if (enSalva) {
            if (e.action == KeyEvent.ACTION_UP) salirSalva(true)
            return true
        }
        // El jog del mando deja la flecha apretada ~550 ms y Android lo toma como tecla mantenida
        // (repite a los 400 ms y luego cada 50): sin esto salta 4 fichas. Las repeticiones de los
        // primeros 700 ms se ignoran; mantener la flecha más rato sigue avanzando rápido.
        if (e.repeatCount > 0 && e.eventTime - e.downTime < 700 && when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
                else -> false
            }) return true
        val m = moviendo
        if (m != null) {
            val atras = if (vertical) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_LEFT
            val alante = if (vertical) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_RIGHT
            when (e.keyCode) {
                atras, alante -> {
                    if (e.action == KeyEvent.ACTION_DOWN) moverPaso(m, if (e.keyCode == atras) -1 else 1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                    if (e.action == KeyEvent.ACTION_UP) soltar()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
            }
        }
        if (e.action == KeyEvent.ACTION_DOWN) Ficha.inclinacionActual = when {
            vertical -> 0f
            e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> -1f
            e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> 1f
            else -> 0f
        }
        // CH+ / CH− del mando cambian el canal de la ventanita de TDT.
        if (aj.tvVentana && (e.keyCode == KeyEvent.KEYCODE_CHANNEL_UP || e.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN)) {
            if (e.action == KeyEvent.ACTION_DOWN) ventana.cambiarCanal(if (e.keyCode == KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1)
            return true
        }
        if (e.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(e)
    }

    // ---------- Construcción ----------

    private fun construirInicio() {
        botonAjustes = botonIcono(getDrawable(R.drawable.ic_ajustes)!!, "Ajustes") { abrirAjustes() }
        contenido.addView(botonAjustes)
        // PAP de un toque: si está parado lo empieza; si está en marcha, abre su panel.
        botonPap = botonIcono(getDrawable(R.drawable.ic_pap)!!, "PAP") { Pap.empezar(this) }
        contenido.addView(botonPap)

        bloqueReloj = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hora = relojDegradado(40f)
        fecha = TextClock(this).apply {
            format24Hour = "EEEE, d 'de' MMMM"; format12Hour = format24Hour
            textSize = 14f
            isSingleLine = true
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(0xC8FFFFFF.toInt())
            setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
        }.conFuente()
        tiempo = TextView(this).apply {
            textSize = 13f
            setTextColor(0xE6FFFFFF.toInt())
            setPadding(dp(8), dp(3), dp(10), dp(3))
            compoundDrawablePadding = dp(6)
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x1FFFFFFF, 0x08FFFFFF)).apply {
                cornerRadius = dp(20).toFloat(); setStroke(dp(1), 0x22FFFFFF)
            }
            visibility = View.GONE
        }.conFuente()
        hora.gravity = Gravity.CENTER_HORIZONTAL
        bloqueReloj.addView(hora, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        bloqueReloj.addView(fecha, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        bloqueReloj.addView(tiempo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); gravity = Gravity.CENTER_HORIZONTAL })
        contenido.addView(bloqueReloj)

        ventana = VentanaTv(this, aj.colorSel, aj.p).apply {
            visibility = View.GONE
            setOnClickListener { abrirTdt() }
            // OK mantenido: elegir fuente y sonido sin entrar en Ajustes.
            isLongClickable = true
            setOnLongClickListener { menuVentana(); true }
        }
        contenido.addView(ventana)

        titulo = TextView(this).apply {
            textSize = 14f
            setTextColor(0xB8FFFFFF.toInt())
            setShadowLayer(dp(8).toFloat(), 0f, 0f, 0xCC000000.toInt())
        }.conFuente()
        fila = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }

        aviso = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xD9282E42.toInt(), 0xE60A0C14.toInt())).apply {
                cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0x26FFFFFF)
            }
            elevation = dp(20).toFloat()
            visibility = View.GONE
        }.conFuente()
        contenido.addView(aviso, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (resources.displayMetrics.heightPixels * .33f).toInt() })
        // Versión, pequeña abajo a la derecha, para saber siempre cuál está instalada.
        version = TextView(this).apply {
            text = "Stark TV ${packageManager.getPackageInfo(packageName, 0).versionName}"
            textSize = 11f
            setTextColor(0x80FFFFFF.toInt())
            setShadowLayer(dp(4).toFloat(), 0f, 0f, Color.BLACK)
        }.conFuente()
        contenido.addView(version, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(14), dp(4)) })
        montarDock()
    }

    /** Coloca el dock según el ajuste: horizontal abajo/centro/arriba o vertical izquierda/centro/derecha. */
    private fun montarDock() {
        zonaDock?.let { (it.parent as? ViewGroup)?.removeView(it) }
        (titulo.parent as? ViewGroup)?.removeView(titulo)
        (fila.parent as? ViewGroup)?.removeView(fila)
        val v = vertical
        fila.orientation = if (v) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val scroll: ViewGroup = if (v) ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(30))
        } else HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(14), 0, dp(40), 0)
        }
        scroll.clipChildren = false
        scroll.clipToPadding = false
        scroll.isFocusable = false
        scroll.addView(fila)
        val zona = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        zona.addView(titulo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(if (v) 30 else 44); marginEnd = dp(44); bottomMargin = dp(2)
            if (!v && aj.tvVentana && aj.tvPos == 3) gravity = Gravity.END
        })
        zona.addView(scroll, if (v) LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f)
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val ancho = ViewGroup.LayoutParams.MATCH_PARENT
        val alto = ViewGroup.LayoutParams.WRAP_CONTENT
        val cab = cabecera()
        // En vertical el dock va de arriba abajo; solo baja si en su lado de arriba hay reloj o botón.
        val arribaLibre = { izquierda: Boolean -> if (izquierda) !cab.relojIzq && !cab.botonIzq else cab.relojIzq && cab.botonIzq }
        val lp = when (aj.dock) {
            0 -> FrameLayout.LayoutParams(ancho, alto, Gravity.BOTTOM).apply { bottomMargin = dp(6) }
            1 -> FrameLayout.LayoutParams(ancho, alto, Gravity.CENTER_VERTICAL)
            2 -> FrameLayout.LayoutParams(ancho, alto, Gravity.TOP).apply { topMargin = dp(100) }
            3 -> FrameLayout.LayoutParams(alto, ancho, Gravity.START).apply {
                topMargin = dp(if (arribaLibre(true)) 8 else 120); marginStart = dp(14)
            }
            4 -> FrameLayout.LayoutParams(alto, ancho, Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) }
            else -> FrameLayout.LayoutParams(alto, ancho, Gravity.END).apply {
                topMargin = dp(if (arribaLibre(false)) 8 else 120); marginEnd = dp(14)
            }
        }
        contenido.addView(zona, contenido.indexOfChild(aviso), lp)
        zonaDock = zona
        colocarCabecera(cab)
        colocarVentana(cab)
    }

    /** Dónde van el reloj y el botón de ajustes. El botón, en la esquina contraria al reloj
     *  salvo que ahí haya un dock vertical: entonces se pone junto al reloj. */
    private class Cabecera(val relojIzq: Boolean, val botonIzq: Boolean)

    private fun cabecera(): Cabecera {
        var relojIzq = when (aj.relojPos) {
            1 -> true
            2 -> false
            else -> aj.dock == 5            // automático: el reloj se va al lado que deja libre el dock
        }
        if (aj.tvVentana && aj.tvPos in 1..2) {
            val tvIzq = aj.tvPos == 1
            if (relojIzq == tvIzq) relojIzq = !tvIzq
        }
        var botonIzq = !relojIzq
        if (botonIzq && aj.dock == 3) botonIzq = false
        if (!botonIzq && aj.dock == 5) botonIzq = true
        return Cabecera(relojIzq, botonIzq)
    }

    private fun colocarCabecera(c: Cabecera) {
        val mismoLado = c.relojIzq == c.botonIzq
        val lado = Gravity.TOP or if (c.botonIzq) Gravity.START else Gravity.END
        botonAjustes.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, lado).apply {
            topMargin = dp(16); marginStart = dp(22); marginEnd = dp(22)
        }
        // El botón PAP, junto a la tuerca (hacia dentro de la pantalla).
        botonPap.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, lado).apply {
            topMargin = dp(16); marginStart = dp(78); marginEnd = dp(78)
        }
        bloqueReloj.gravity = Gravity.CENTER_HORIZONTAL
        bloqueReloj.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or if (c.relojIzq) Gravity.START else Gravity.END).apply {
            topMargin = dp(22)
            // Si comparte esquina con el botón, se aparta para dejarle sitio.
            val borde = dp(if (mismoLado) (if (aj.papBoton) 140 else 84) else 40)
            marginStart = borde; marginEnd = borde
        }
    }

    /** La ventanita de TDT va en una esquina que dejan libre el dock y el reloj. */
    private fun colocarVentana(c: Cabecera) {
        val lp = FrameLayout.LayoutParams(dp(288), dp(162))
        if (aj.tvPos in 1..4) {
            val izq = aj.tvPos == 1 || aj.tvPos == 3
            val arriba = aj.tvPos <= 2
            lp.gravity = (if (arriba) Gravity.TOP else Gravity.BOTTOM) or (if (izq) Gravity.START else Gravity.END)
            if (arriba) lp.topMargin = dp(76)
            else lp.bottomMargin = if (aj.dock == 0) {
                val alto = Ficha.ANCHOS[aj.tamano.coerceIn(0, 3)] * 9 / 16
                dp(alto + 34 + (if (aj.reflejo) alto * .4f else 0f) + (if (aj.nombres) 22 else 0))
            } else dp(32)
            lp.marginStart = dp(32); lp.marginEnd = dp(32)
            ventana.layoutParams = lp
            apartarVentana()
            return
        }
        when (aj.dock) {
            0 -> { lp.gravity = Gravity.TOP or if (c.relojIzq) Gravity.END else Gravity.START; lp.topMargin = dp(76) }
            3 -> { lp.gravity = Gravity.BOTTOM or Gravity.END; lp.bottomMargin = dp(32) }
            else -> { lp.gravity = Gravity.BOTTOM or Gravity.START; lp.bottomMargin = dp(32) }
        }
        lp.marginStart = dp(32); lp.marginEnd = dp(32)
        ventana.layoutParams = lp
        apartarVentana()
    }

    /** Ya colocado todo, si la ventanita pisa el dock se aparta: a su lado si el dock es vertical,
     *  encima o debajo si es horizontal. Se mide el dock de verdad, no se supone su tamaño. */
    private fun apartarVentana() {
        if (!aj.tvVentana) return
        val vto = contenido.viewTreeObserver
        vto.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                contenido.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val z = zonaDock ?: return
                val rv = android.graphics.Rect().also { ventana.getHitRect(it) }
                val rz = android.graphics.Rect().also { z.getHitRect(it) }
                // En horizontal la zona ocupa todo el ancho: lo que cuenta es la fila de fichas.
                if (!vertical) rz.set(0, rz.top, contenido.width, rz.bottom)
                if (!android.graphics.Rect.intersects(rv, rz)) return
                val lp = ventana.layoutParams as FrameLayout.LayoutParams
                val hueco = dp(16)
                if (vertical) {
                    // Dock a la izquierda: la ventanita justo a su derecha; dock a la derecha: justo a su izquierda.
                    val arriba = lp.gravity and Gravity.VERTICAL_GRAVITY_MASK
                    if (rz.centerX() < contenido.width / 2) {
                        lp.gravity = arriba or Gravity.START; lp.marginStart = rz.right + hueco; lp.marginEnd = 0
                    } else {
                        lp.gravity = arriba or Gravity.END; lp.marginEnd = contenido.width - rz.left + hueco; lp.marginStart = 0
                    }
                } else {
                    val encima = rz.top - hueco - rv.height()
                    lp.gravity = (lp.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) or Gravity.TOP
                    lp.bottomMargin = 0
                    lp.topMargin = if (encima >= dp(70)) encima else rz.bottom + hueco
                }
                ventana.layoutParams = lp
            }
        })
    }

    private fun construirSalva(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        alpha = 0f
        visibility = View.GONE
        horaSalva = relojDegradado(110f)
        addView(horaSalva)
        addView(TextClock(this@MainActivity).apply {
            format24Hour = "EEEE, d 'de' MMMM"; format12Hour = format24Hour
            textSize = 22f
            setTextColor(0xC8FFFFFF.toInt())
        }.conFuente())
        salvaTiempo = TextView(this@MainActivity).apply {
            textSize = 18f
            setTextColor(0xC8FFFFFF.toInt())
            setPadding(0, dp(8), 0, 0)
            compoundDrawablePadding = dp(8)
            gravity = Gravity.CENTER_VERTICAL
        }.conFuente()
        addView(salvaTiempo)
    }

    /** Hora en degradado plateado con un leve resplandor azulado. */
    private fun relojDegradado(tam: Float) = TextClock(this).apply {
        format24Hour = "HH:mm"; format12Hour = "HH:mm"
        textSize = tam
        setTextColor(Color.WHITE)
        addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> pintarHora(v as TextView) }
        pintarHora(this)
    }.conFuente(medio = true)

    /** Hora en degradado blanco hacia el color de la interfaz, con un resplandor de ese color. */
    private fun pintarHora(v: TextView) {
        v.setShadowLayer(dp(14).toFloat(), 0f, 0f, conAlfa(Tema.color, 0x66))
        v.paint.shader = LinearGradient(0f, 0f, 0f, v.height.coerceAtLeast(1).toFloat(),
            Color.WHITE, Ficha.mezclar(Color.WHITE, Tema.color, .3f), Shader.TileMode.CLAMP)
        v.invalidate()
    }

    private fun fondoBoton() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused),
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x40FFFFFF, 0x14FFFFFF)).apply {
                cornerRadius = dp(12).toFloat(); setStroke(dp(2), aj.colorSel)
            })
        addState(intArrayOf(),
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x24FFFFFF, 0x0AFFFFFF)).apply {
                cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0x1AFFFFFF)
            })
    }

    private fun icono(t: Icono.Tipo, color: Int, lado: Int) = Icono(t, color, dp(lado)).apply { setBounds(0, 0, dp(lado), dp(lado)) }

    // ---------- Estado ----------

    private fun aplicar() {
        velo.setBackgroundColor(Color.argb((100 - aj.brillo.coerceIn(0, 100)) * 255 / 100, 0, 0, 0))
        hora.visibility = if (aj.reloj) View.VISIBLE else View.GONE
        fecha.visibility = hora.visibility
        tiempo.visibility = if (aj.tiempo && textoTiempo != null) View.VISIBLE else View.GONE
        botonAjustes.background = null
        botonPap.background = null
        botonPap.visibility = if (aj.papBoton) View.VISIBLE else View.GONE
        // PAP o PIP según el modo; con el PAP en marcha, el botón brilla del color de la selección.
        botonPap.setCompoundDrawablesRelative(medida(dibujoPap(), 36), null, null, null)
        ventana.visibility = if (aj.tvVentana) View.VISIBLE else View.GONE
        titulo.visibility = if (aj.tituloDock) View.VISIBLE else View.GONE
        version.visibility = if (aj.verVersion) View.VISIBLE else View.GONE
    }

    private fun firmaActual() = listOf(lista.orden(), lista.favoritas(), aj.tamano, aj.nombres, aj.reflejo, aj.animaciones, aj.dock, aj.papDock).toString()

    /** Rehace el dock solo si algo cambió (orden, favoritas, tamaño…): volver al inicio es instantáneo. */
    private fun pintarCarrusel(forzar: Boolean = false) {
        val nueva = firmaActual()
        if (!forzar && nueva == firma && fila.childCount > 0) return
        firma = nueva
        val enfocado = (currentFocus as? Ficha)?.paquete
        val orden = lista.orden()
        val favs = lista.favoritas()
        val v = vertical
        fila.removeAllViews()
        // Las fichas llevan margen interno para el halo; en vertical, además, el nombre va debajo.
        val sep = dp(if (v) 12 else 18) - 2 * dp(Ficha.margen(aj.tamano)) + if (v && aj.nombres) dp(24) else 0
        fun anadir(f: Ficha) {
            f.alEnfocar = { x -> x.postDelayed({ apuntarLuz(x, x.color, x.pivotX, x.pivotY) }, 120) }
            fila.addView(f, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (fila.childCount > 0) { if (v) topMargin = sep else marginStart = sep }
            })
        }
        anadir(nuevaFicha(null).apply { setOnClickListener { abrirFavoritos() } })
        if (aj.papDock) anadir(nuevaFicha(Ficha.PAP).apply { setOnClickListener { Pap.empezar(this@MainActivity) } })
        for (p in orden) {
            val f = nuevaFicha(p)
            f.esFavorita = p in favs
            f.setOnClickListener { abrir(p) }
            f.setOnLongClickListener { menuApp(f); true }
            anadir(f)
        }
        titulo.text = "Todas las apps · ${orden.size}"
        val objetivo = fichas().firstOrNull { enfocado != null && it.paquete == enfocado } ?: fila.getChildAt(0)
        objetivo?.requestFocus()
    }

    // En vertical no hay suelo debajo de cada ficha: sin reflejo.
    private fun nuevaFicha(p: String?) = Ficha(this, p, aj.tamano, aj.reflejo && !vertical, aj.nombres, aj.animaciones)

    private fun fichas(): List<Ficha> = (0 until fila.childCount).mapNotNull { fila.getChildAt(it) as? Ficha }

    private fun refrescarEstrellas() {
        val favs = lista.favoritas()
        fichas().forEach { f -> f.paquete?.let { f.esFavorita = it in favs } }
        firma = firmaActual()
    }

    private fun apuntarLuz(v: View, color: Int, cx: Float, cy: Float) {
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        // La luz de fondo, vez y media lo enfocado: con fichas micro, pequeña; con grandes, amplia.
        luces.apuntar(loc[0] + cx, loc[1] + cy, color, v.width * 1.5f)
    }

    // ---------- Acciones ----------

    private fun abrir(p: String) {
        val i = Apps.intentDe(this, p) ?: return avisar("No se puede abrir")
        try { startActivity(i, Pap.pantallaCompleta()) } catch (e: Exception) { avisar("No se puede abrir") }
    }

    private fun abrirTdt() {
        val tdt = listOf("com.mediatek.wwtv.tvcenter").firstOrNull { Apps.intentDe(this, it) != null }
        if (tdt != null) abrir(tdt) else avisar("No encuentro la app de la TDT")
    }

    private fun avisar(t: String) {
        aviso.text = t
        aviso.visibility = View.VISIBLE
        aviso.removeCallbacks(ocultarAviso)
        aviso.postDelayed(ocultarAviso, 2600)
    }

    private fun menuApp(f: Ficha) {
        val p = f.paquete ?: return
        val ancho = dp(290)
        val panel = Vidrio.panel(this, f.nombre, ancho)
        val esFav = p in lista.favoritas()
        fun op(t: String, ic: Icono.Tipo, accion: () -> Unit) = panel.addView(Fila(this, t, aj.colorSel, ic).apply {
            setOnClickListener { dialogo?.dismiss(); accion() }
        }, filaLp())
        op(if (esFav) "Quitar de favoritos" else "Añadir a favoritos", if (esFav) Icono.Tipo.ESTRELLA_LLENA else Icono.Tipo.ESTRELLA) {
            if (esFav) lista.quitarFavorita(p)
            else if (!lista.anadirFavorita(p)) avisar("Favoritos llenos: quita una antes (máximo 6)")
            refrescarEstrellas()
        }
        op("Mover", Icono.Tipo.MOVER) { entrarMover(f) }
        op("Ocultar de la barra", Icono.Tipo.OCULTAR) {
            lista.ocultar(p)
            pintarCarrusel(forzar = true)
            avisar("Oculta. Se recupera en Ajustes del launcher")
        }
        op("Información de la app", Icono.Tipo.INFO) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", p, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { avisar("No disponible") }
        }
        // Junto a la ficha: encima o debajo si el dock es horizontal, al lado si es vertical.
        panel.measure(View.MeasureSpec.makeMeasureSpec(ancho, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val altoMenu = panel.measuredHeight
        val loc = IntArray(2)
        f.getLocationOnScreen(loc)
        val z = f.zona
        val izq = loc[0] + z.left; val der = loc[0] + z.right
        val arr = loc[1] + z.top; val aba = loc[1] + z.bottom
        val pw = resources.displayMetrics.widthPixels
        val ph = resources.displayMetrics.heightPixels
        val x: Int
        val y: Int
        if (vertical) {
            x = (if ((izq + der) / 2 < pw / 2) der + dp(24) else izq - ancho - dp(24)).toInt()
            y = ((arr + aba) / 2 - altoMenu / 2).toInt()
        } else {
            x = ((izq + der) / 2 - ancho / 2).toInt()
            y = (if ((arr + aba) / 2 > ph / 2) arr - altoMenu - dp(20) else aba + dp(20)).toInt()
        }
        dialogo = Vidrio.dialogo(this, panel, aj, Gravity.TOP or Gravity.START,
            x.coerceIn(dp(16), pw - ancho - dp(16)), y.coerceIn(dp(16), (ph - altoMenu - dp(16)).coerceAtLeast(dp(16))))
    }

    private fun entrarMover(f: Ficha) {
        moviendo = f
        f.levantada = true
        fichas().forEach { if (it !== f) it.animate().alpha(.4f).setDuration(200).start() }
        aviso.removeCallbacks(ocultarAviso)
        aviso.text = if (vertical) "Mantén ▲ o ▼ para llevarla · OK para soltar" else "Mantén ◀ o ▶ para llevarla · OK para soltar"
        aviso.visibility = View.VISIBLE
        f.requestFocus()
    }

    private fun moverPaso(f: Ficha, dx: Int) {
        val i = fila.indexOfChild(f)
        val j = i + dx
        // Favoritos (y la ficha PAP si está) van fijas al principio.
        if (j < (if (aj.papDock) 2 else 1) || j >= fila.childCount) return
        // Todas las fichas tras Favoritos llevan el mismo margen: basta con cambiarla de sitio.
        fila.removeViewAt(i)
        fila.addView(f, j)
        f.requestFocus()
        f.post { apuntarLuz(f, f.color, f.pivotX, f.pivotY) }
    }

    private fun soltar() {
        val f = moviendo ?: return
        moviendo = null
        f.levantada = false
        fichas().forEach { it.animate().alpha(1f).setDuration(200).start() }
        aviso.visibility = View.GONE
        lista.guardarOrden(fichas().mapNotNull { it.paquete }.filter { it != Ficha.PAP })
        firma = firmaActual()
    }

    private fun abrirFavoritos() {
        val favs = lista.favoritas()
        val panel = Vidrio.panel(this, "Favoritos", 0, Icono.Tipo.ESTRELLA_LLENA).apply {
            clipChildren = false; clipToPadding = false
            setPadding(dp(26), dp(18), dp(26), dp(22))
        }
        val rejilla = GridLayout(this).apply { columnCount = 3; clipChildren = false; clipToPadding = false }
        val solape = -dp(4)
        for (k in 0 until ListaApps.MAX_FAV) {
            val p = favs.getOrNull(k)
            if (p != null) {
                val f = Ficha(this, p, 2, false, false, aj.animaciones)
                f.setOnClickListener { dialogo?.dismiss(); abrir(p) }
                f.setOnLongClickListener {
                    dialogo?.dismiss()
                    lista.quitarFavorita(p)
                    refrescarEstrellas()
                    avisar("Quitada de favoritos")
                    true
                }
                rejilla.addView(f, GridLayout.LayoutParams().apply { setMargins(solape, solape, solape, solape) })
            } else {
                val hueco = TextView(this).apply {
                    text = "Hueco libre"
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(0x80FFFFFF.toInt())
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(1.5f), 0x40FFFFFF, dp(6).toFloat(), dp(4).toFloat())
                    }
                }.conFuente()
                rejilla.addView(hueco, GridLayout.LayoutParams().apply {
                    width = dp(Ficha.ANCHOS[2]); height = dp(Ficha.ANCHOS[2] * 9 / 16)
                    setMargins(dp(22), dp(22), dp(22), dp(22))
                })
            }
        }
        panel.addView(rejilla)
        if (favs.isEmpty()) panel.addView(nota("Mantén OK sobre una app del dock y elige «Añadir a favoritos»."))
        else panel.addView(nota("Mantén OK sobre una para quitarla."))
        dialogo = Vidrio.dialogo(this, panel, aj)
    }

    // ---------- Ajustes ----------

    /** Primera pantalla: Generales (los de la TV) o Launcher. */
    private fun abrirAjustes() {
        val sel = aj.colorSel
        val panel = Vidrio.panel(this, "Ajustes", dp(300), dibujo = getDrawable(R.drawable.ic_ajustes))
        val grupo = grupo()
        val generales = Fila(this, "Generales", sel, dibujo = getDrawable(R.drawable.ic_ajustes_tv)).apply {
            valor.text = "›"
            setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (e: Exception) { avisar("No disponible") }
            }
        }
        val launcher = Fila(this, "Launcher", sel, dibujo = getDrawable(R.mipmap.ic_launcher)).apply {
            valor.text = "›"
            setOnClickListener { dialogo?.dismiss(); abrirLauncher() }
        }
        anadirAGrupo(grupo, generales)
        anadirAGrupo(grupo, launcher)
        panel.addView(grupo, filaLp())
        dialogo = Vidrio.dialogo(this, panel, aj, Gravity.START or Gravity.TOP, dp(24), dp(24))
        launcher.requestFocus()
    }

    /**
     * Ajustes del launcher a pantalla completa, como en un iPhone: grupos con título,
     * interruptores para sí/no y «valor ›» para lo que tiene varias opciones
     * (◀ ▶ lo cambian ahí mismo, OK abre la lista). Atrás vuelve a la primera pantalla.
     */
    private fun abrirLauncher(enfocar: String? = null) {
        val sel = aj.colorSel
        val panel = Vidrio.panel(this, null, dp(640))
        panel.addView(TextView(this).apply {
            text = "‹  Ajustes"
            textSize = 13f
            setTextColor(conAlfa(sel, 0xFF))
            setPadding(dp(8), 0, 0, dp(2))
        }.conFuente())
        panel.addView(TextView(this).apply {
            text = "Launcher"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, 0, dp(6))
        }.conFuente(medio = true))
        // Pestañas arriba (◀ ▶ las recorren y se ve solo lo de cada una); ▼ baja a sus opciones.
        val pestanas = LinearLayout(this).apply { setPadding(dp(4), dp(2), dp(4), dp(10)) }
        panel.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(pestanas) })
        val cuerpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; addView(cuerpo) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val paginas = mutableListOf<LinearLayout>()
        fun mostrar(i: Int) {
            paginas.forEachIndexed { k, p -> p.visibility = if (k == i) View.VISIBLE else View.GONE }
            for (k in 0 until pestanas.childCount) pestanas.getChildAt(k).isSelected = k == i
        }

        var actual: LinearLayout? = null
        fun seccion(t: String) {
            val pagina = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            cuerpo.addView(pagina, filaLp())
            paginas += pagina
            val i = paginas.size - 1
            pestanas.addView(TextView(this).apply {
                text = t
                textSize = 14f
                setTextColor(Color.WHITE)
                isFocusable = true
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat(); setColor(conAlfa(sel, 0x80)); setStroke(dp(1.5f), sel)
                    })
                    addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat(); setColor(0x26FFFFFF)
                    })
                    addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                }
                setOnFocusChangeListener { _, f -> if (f) mostrar(i) }
                // OK sobre la pestaña: a su primera opción.
                setOnClickListener { (pagina.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.requestFocus() }
            }.conFuente(medio = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) })
            actual = grupo().also { pagina.addView(it, filaLp()) }
        }
        fun nueva(clave: String, t: String, valor: String? = null, acc: View? = null, alPulsar: (Fila) -> Unit): Fila {
            val f = Fila(this, t, sel)
            f.tag = clave
            if (valor != null) f.valor.text = valor
            f.accesorio = acc
            f.setOnClickListener { alPulsar(f) }
            anadirAGrupo(actual!!, f)
            return f
        }
        fun interruptor(clave: String, t: String, valor: Boolean, cambiar: (Boolean) -> Unit) {
            val sw = Interruptor(this, valor)
            nueva(clave, t, acc = sw) { sw.encendido = !sw.encendido; cambiar(sw.encendido) }
        }
        // Varias opciones: «valor ›». ◀ ▶ cambian sin salir de la fila; OK abre la lista con la marca.
        fun opciones(clave: String, t: String, ops: List<String>, inicial: Int, cambiar: (Int) -> Unit) {
            var i = inicial.coerceIn(ops.indices)
            lateinit var f: Fila
            val poner = { n: Int -> i = n; f.valor.text = "${ops[n]}  ›"; cambiar(n) }
            f = nueva(clave, t, "${ops[i]}  ›") { elegir(t, ops, actual = i) { n -> if (n != i) poner(n) } }
            f.setOnKeyListener { _, code, ev ->
                if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
                if (ev.action == KeyEvent.ACTION_DOWN) {
                    val n = (i + if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1).coerceIn(ops.indices)
                    if (n != i) poner(n)
                }
                true
            }
        }

        seccion("Fondo")
        nueva("fondo", "Fondo", "${nombreFondo(aj.fondo)}  ›") { f -> elegirFondo { f.valor.text = "${nombreFondo(aj.fondo)}  ›" } }
        val barra = Barra(this, sel, aj.brillo)
        val filaBrillo = nueva("brillo", "Brillo", "${aj.brillo}%", barra) { f ->
            aj.brillo = if (aj.brillo >= 100) 0 else aj.brillo + 10
            barra.valor = aj.brillo; f.valor.text = "${aj.brillo}%"; aplicar()
        }
        filaBrillo.setOnKeyListener { _, code, ev ->
            if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
            if (ev.action == KeyEvent.ACTION_DOWN) {
                aj.brillo = (aj.brillo + if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 10 else -10).coerceIn(0, 100)
                barra.valor = aj.brillo; filaBrillo.valor.text = "${aj.brillo}%"; aplicar()
            }
            true
        }
        val minutos = { l: List<Int> -> l.map { if (it == 0) "No" else "$it min" } }
        opciones("rotar", "Cambiar fondo solo", minutos(Ajustes.MINUTOS_ROTAR), Ajustes.MINUTOS_ROTAR.indexOf(aj.rotarMin)) { i ->
            aj.rotarMin = Ajustes.MINUTOS_ROTAR[i]; ultimaRotacion = SystemClock.uptimeMillis()
        }
        interruptor("version", "Ver la versión sobre el fondo", aj.verVersion) { aj.verVersion = it; aplicar() }
        nueva("recibir", "Recibir fondos desde el móvil o PC", "›") { dialogo?.dismiss(); abrirRecibir() }

        seccion("Dock")
        opciones("dock", "Posición", Ajustes.POSICIONES, aj.dock) { i -> aj.dock = i; montarDock(); pintarCarrusel(true) }
        opciones("tamano", "Tamaño de fichas", Ajustes.TAMANOS, aj.tamano) { i -> aj.tamano = i; pintarCarrusel(true) }
        interruptor("titulo", "Título del dock", aj.tituloDock) { aj.tituloDock = it; aplicar() }
        interruptor("nombres", "Nombre bajo las apps", aj.nombres) { aj.nombres = it; pintarCarrusel(true) }
        nueva("ocultas", "Apps ocultas", "${lista.ocultas().size}  ›") { f -> elegirOcultas { f.valor.text = "${lista.ocultas().size}  ›" } }

        seccion("Reloj")
        interruptor("reloj", "Reloj", aj.reloj) { aj.reloj = it; aplicar() }
        interruptor("tiempo", "Tiempo", aj.tiempo) { aj.tiempo = it; aplicar(); if (it) pedirTiempo() }
        nueva("ciudad", "Ciudad", "${aj.ciudad}  ›") { f ->
            pedirCiudad { c -> aj.ciudad = c; f.valor.text = "$c  ›"; textoTiempo = null; pedirTiempo() }
        }
        opciones("relojpos", "Posición", Ajustes.POSICIONES_RELOJ, aj.relojPos) { i -> aj.relojPos = i; montarDock() }

        seccion("Pantalla")
        nueva("fuente", "Tipografía", "${Fuentes.LISTA[aj.fuente].nombre}  ›") { elegirFuente() }
        opciones("color", "Color de la interfaz", Ajustes.COLORES.map { it.first }, aj.color) { i ->
            aj.color = i
            Tema.color = aj.colorSel
            pintarHora(hora); pintarHora(horaSalva); luces.cambiarTema()
            aplicar()
            dialogo?.dismiss()
            abrirLauncher(enfocar = "color")     // se vuelve a abrir para pintarlo con el color nuevo
        }
        // Opacidad de los menús: ◀ ▶ de 10 en 10 (30-100 %); el panel cambia al momento.
        val barraOp = Barra(this, sel, aj.opacidad)
        fun ponerOpacidad(v: Int, f: Fila) {
            aj.opacidad = v.coerceIn(30, 100); Tema.opacidad = aj.opacidad
            barraOp.valor = aj.opacidad; f.valor.text = "${aj.opacidad}%"
            panel.background = Vidrio.fondo(this)
        }
        val filaOp = nueva("opacidad", "Opacidad de los menús", "${aj.opacidad}%", barraOp) { f ->
            ponerOpacidad(if (aj.opacidad >= 100) 30 else aj.opacidad + 10, f)
        }
        filaOp.setOnKeyListener { _, code, ev ->
            if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
            if (ev.action == KeyEvent.ACTION_DOWN) ponerOpacidad(aj.opacidad + if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 10 else -10, filaOp)
            true
        }
        // Oscuridad de los menús: lleva sus colores hacia el negro (0-90 %), sin tocar la transparencia.
        val barraOsc = Barra(this, sel, aj.oscuridad * 100 / 90)
        fun ponerOscuridad(v: Int, f: Fila) {
            aj.oscuridad = v.coerceIn(0, 90); Tema.oscuridad = aj.oscuridad
            barraOsc.valor = aj.oscuridad * 100 / 90; f.valor.text = "${aj.oscuridad}%"
            panel.background = Vidrio.fondo(this)
        }
        val filaOsc = nueva("oscuridad", "Oscuridad de los menús", "${aj.oscuridad}%", barraOsc) { f ->
            ponerOscuridad(if (aj.oscuridad >= 90) 0 else aj.oscuridad + 10, f)
        }
        filaOsc.setOnKeyListener { _, code, ev ->
            if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
            if (ev.action == KeyEvent.ACTION_DOWN) ponerOscuridad(aj.oscuridad + if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 10 else -10, filaOsc)
            true
        }
        opciones("salva", "Salvapantallas", minutos(Ajustes.MINUTOS_SALVA), Ajustes.MINUTOS_SALVA.indexOf(aj.salvaMin)) { i ->
            aj.salvaMin = Ajustes.MINUTOS_SALVA[i]
        }
        interruptor("tdt", "Ventana de TV en directo", aj.tvVentana) {
            aj.tvVentana = it
            aplicar()
            if (it) ventana.encender() else ventana.apagar()
        }
        opciones("tvfuente", "Fuente de la ventana", Ajustes.FUENTES_TV, aj.tvFuente) { i ->
            aj.tvFuente = i
            if (aj.tvVentana) { ventana.apagar(); ventana.encender() }
        }
        interruptor("tvsonido", "Sonido de la ventana", aj.tvSonido) { aj.tvSonido = it; ventana.sonido(it) }
        interruptor("tvrotulo", "Nombre del canal y la fuente", aj.tvRotulo) { aj.tvRotulo = it; ventana.mostrarRotulo(it) }

        seccion("Efectos")
        interruptor("reflejo", "Reflejo en el suelo", aj.reflejo) { aj.reflejo = it; pintarCarrusel(true) }
        interruptor("cristal", "Cristal difuminado", aj.cristal) { aj.cristal = it }
        interruptor("animaciones", "Animaciones de luz", aj.animaciones) { aj.animaciones = it; pintarCarrusel(true) }

        seccion("PAP")
        opciones("papreparto", "Reparto", Pap.REPARTOS, aj.papReparto) { i -> aj.papReparto = i }
        val partes = { r: Int -> if (r == 3) "Arriba" to "Abajo" else "Izquierda" to "Derecha" }
        // Qué va en cada parte: ◀ ▶ recorren Antena, HDMI 1-3, AV y las apps; OK abre la lista.
        val papOps = Pap.opciones(this)
        opciones("papizq", partes(aj.papReparto).first, papOps.map { it.second }, papOps.indexOfFirst { it.first == aj.papIzq }) { i -> aj.papIzq = papOps[i].first }
        opciones("papder", partes(aj.papReparto).second, papOps.map { it.second }, papOps.indexOfFirst { it.first == aj.papDer }) { i -> aj.papDer = papOps[i].first }
        opciones("papsonido", "Sonido", Pap.sonidos(aj.papReparto), aj.papSonido.coerceIn(0, 3)) { i -> aj.papSonido = i; Pap.aplicarSonido(this, aj) }
        opciones("papaviso", "Aviso al empezar", Pap.AVISOS.map { if (it == 0) "Desactivado" else "$it s" }, Pap.AVISOS.indexOf(aj.papAviso)) { i -> aj.papAviso = Pap.AVISOS[i] }
        interruptor("papboton", "Widget PAP en el inicio", aj.papBoton) { aj.papBoton = it; aplicar(); montarDock() }
        interruptor("papdock", "PAP en el dock", aj.papDock) { aj.papDock = it; pintarCarrusel(true) }
        nueva("papempezar", "Empezar PAP", "›") { dialogo?.dismiss(); Pap.empezar(this) }

        seccion("Sistema")
        nueva("adb", "Permisos avanzados (adb)", if (Adb.conectado) "Conectado" else "Pulsa para conectar  ›") { f ->
            f.valor.text = "Conectando…"
            Adb.enSegundoPlano(this, "echo ok") { r ->
                f.valor.text = if (r != null) "Conectado" else "Acepta el aviso en la TV y vuelve a pulsar"
                if (r != null) Adb.reponerAjustes(this)
            }
        }
        val sw = Interruptor(this, esPorDefecto())
        swPorDefecto = sw
        nueva("pordefecto", "Launcher por defecto", acc = sw) { cambiarPorDefecto(!esPorDefecto()) }

        nueva("copia", "Copia de ajustes", "Guardar o recuperar  ›") { elegirCopia() }
        panel.addView(nota("Stark TV ${packageManager.getPackageInfo(packageName, 0).versionName}"))

        val dl = Vidrio.dialogo(this, panel, aj, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, dp(20),
            resources.displayMetrics.heightPixels - dp(40))
        dl.setOnCancelListener { abrirAjustes() }          // Atrás: vuelve a la primera pantalla
        dialogo = dl
        // Al volver a una fila concreta (cambio de color o de fuente) se abre su pestaña; si no, la primera.
        val foco = enfocar?.let { cuerpo.findViewWithTag<View>(it) }
        val pagina = paginas.indexOfFirst { p -> foco != null && p.findViewWithTag<View>(enfocar) != null }
        mostrar(if (pagina >= 0) pagina else 0)
        (foco ?: pestanas.getChildAt(0))?.requestFocus()
    }

    private fun esPorDefecto() = getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true

    /**
     * Encender: Android pregunta si usar Stark TV como inicio.
     * Apagar: si el inicio de Google está desactivado, se abre su ficha para pulsar «Activar»;
     * si ya está activo, la pantalla (escondida en Android TV) para elegir el inicio.
     */
    private fun cambiarPorDefecto(serlo: Boolean) {
        if (serlo) {
            try {
                startActivityForResult(getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_HOME), 7)
            } catch (e: Exception) { abrirElegirInicio() }
            return
        }
        val google = "com.google.android.tvlauncher"
        val activo = try { packageManager.getApplicationInfo(google, 0).enabled } catch (e: Exception) { false }
        if (!activo) {
            avisar("Pulsa «Activar» y vuelve con Atrás")
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", google, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { avisar("No encuentro el inicio de Google") }
        } else abrirElegirInicio()
    }

    private fun abrirElegirInicio() {
        try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { avisar("Esta TV no deja elegir el inicio") }
    }

    /** El dibujo a [alto] dp de alto sin deformarlo. */
    private fun medida(d: android.graphics.drawable.Drawable, alto: Int) = d.mutate().apply {
        val h = dp(alto)
        setBounds(0, 0, h * intrinsicWidth / intrinsicHeight.coerceAtLeast(1), h)
    }

    private fun dibujoPap() = getDrawable(if (Pap.esPip(aj.papReparto)) R.drawable.ic_pip else R.drawable.ic_pap)!!.mutate()

    /** Botón redondo de la cabecera (tuerca, PAP): solo icono, se agranda y brilla con el foco. */
    private fun botonIcono(dibujo: android.graphics.drawable.Drawable, nombre: String, alPulsar: () -> Unit) = TextView(this).apply {
        contentDescription = nombre
        setPadding(dp(6), dp(6), dp(6), dp(6))
        setCompoundDrawablesRelative(medida(dibujo, 36), null, null, null)
        isFocusable = true
        alpha = .8f
        setOnClickListener { alPulsar() }
        // Sin caja ni contorno: con el foco el icono crece y se ve entero; la luz del fondo lo sigue.
        setOnFocusChangeListener { v, f ->
            v.animate().scaleX(if (f) 1.3f else 1f).scaleY(if (f) 1.3f else 1f).alpha(if (f) 1f else .8f)
                .translationY(if (f) -dp(2).toFloat() else 0f).setDuration(160).start()
            if (f) apuntarLuz(v, aj.colorSel, v.width / 2f, v.height / 2f)
        }
    }

    /** Menú de la ventanita de TV (OK mantenido): fuente y sonido al momento. */
    private fun menuVentana() {
        val sel = aj.colorSel
        val panel = Vidrio.panel(this, "Ventana de TV", dp(280), Icono.Tipo.TV)
        val g = grupo()
        Ajustes.FUENTES_TV.forEachIndexed { i, nombre ->
            anadirAGrupo(g, Fila(this, nombre, sel).apply {
                if (i == aj.tvFuente) accesorio = View(this@MainActivity).apply {
                    background = icono(Icono.Tipo.CHECK, sel, 18)
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                }
                setOnClickListener {
                    dialogo?.dismiss()
                    aj.tvFuente = i
                    ventana.apagar(); ventana.encender()
                }
            })
        }
        val sw = Interruptor(this, aj.tvSonido)
        Ajustes.POSICIONES_TV.forEachIndexed { i, nombre ->
            anadirAGrupo(g, Fila(this, "Posición: $nombre", sel).apply {
                if (i + 1 == aj.tvPos) accesorio = View(this@MainActivity).apply {
                    background = icono(Icono.Tipo.CHECK, sel, 18)
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                }
                setOnClickListener { dialogo?.dismiss(); aj.tvPos = i + 1; montarDock() }
            })
        }
        anadirAGrupo(g, Fila(this, "Sonido", sel).apply {
            accesorio = sw
            setOnClickListener { sw.encendido = !sw.encendido; aj.tvSonido = sw.encendido; ventana.sonido(sw.encendido) }
        })
        panel.addView(g, filaLp())
        dialogo = Vidrio.dialogo(this, panel, aj)
        (g.getChildAt(aj.tvFuente * 2) ?: g.getChildAt(0))?.requestFocus()
    }

    /** «Recibir fondos»: mientras está abierta, la TV atiende en su dirección y lo recibido se puede poner de fondo. */
    private fun abrirRecibir() {
        val sel = aj.colorSel
        val panel = Vidrio.panel(this, "Recibir fondos", dp(460))
        val estado = nota("Esperando archivos…")
        val r = Recibidor(this, { aj.fondo }) { e ->
            when (e) {
                is Recibidor.Evento.Recibido -> estado.text = "Recibido: ${e.nombre}. Ya está en la lista de fondos."
                is Recibidor.Evento.Poner -> { aj.fondo = e.clave; fondo.mostrar(e.clave); estado.text = "Puesto de fondo: ${nombreFondo(e.clave)}" }
                is Recibidor.Evento.Borrado -> if (aj.fondo == e.clave) { aj.fondo = ""; fondo.mostrar("") }
                is Recibidor.Evento.Renombrado -> if (aj.fondo == e.antes) { aj.fondo = e.ahora; fondo.mostrar(e.ahora) }
            }
        }
        val dir = r.direccion
        panel.addView(nota(if (dir != null) "En el navegador del móvil o del ordenador, conectado a la misma wifi, abre:" else "La TV no tiene red: conéctala a la wifi o por cable."))
        if (dir != null) panel.addView(TextView(this).apply {
            text = dir
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(4))
        }.conFuente(medio = true))
        panel.addView(nota("Imágenes (webp, jpg, png, gif), vídeos (mp4, webm, mkv) o HTML5; un HTML5 con imágenes propias, en un .zip con su index.html. Desde esa página también puedes ponerlos de fondo o borrarlos. Solo funciona mientras esta pantalla esté abierta."))
        panel.addView(estado)
        val g = grupo()
        val cerrar = Fila(this, "Cerrar", sel)
        anadirAGrupo(g, cerrar)
        panel.addView(g, filaLp().apply { topMargin = dp(14) })
        val dl = Vidrio.dialogo(this, panel, aj)
        cerrar.setOnClickListener { dl.dismiss() }
        // Al cerrar se vuelve a Ajustes, a esta misma fila (con los fondos nuevos ya en la lista).
        dl.setOnDismissListener { r.parar(); abrirLauncher(enfocar = "recibir") }
        r.empezar()
        cerrar.requestFocus()
    }

    /** Grupo de filas con fondo redondeado, como las tarjetas de ajustes del iPhone. */
    private fun grupo() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0x17FFFFFF) }
    }

    /** Añade la fila al grupo con una línea fina de separación (desde el texto, como en iOS). */
    private fun anadirAGrupo(g: LinearLayout, f: View) {
        if (g.childCount > 0) g.addView(View(this).apply { setBackgroundColor(0x1AFFFFFF) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(14); marginEnd = dp(14) })
        g.addView(f, filaLp())
    }

    private fun nombreFondo(clave: String): String = when {
        clave.isEmpty() -> "Sin fondo"
        CatalogoFondos.nombreIncluido(clave) != null -> CatalogoFondos.nombreIncluido(clave)!!
        clave.endsWith("/index.html") -> File(clave).parentFile?.name ?: clave
        else -> clave.removePrefix("asset:").substringAfterLast('/').substringBeforeLast('.')
    }

    /** Lista de cristal encima de lo que haya; al elegir se cierra solo esa lista. */
    private fun elegir(tituloLista: String, opciones: List<String>, fuentes: Boolean = false, actual: Int = -1, alElegir: (Int) -> Unit) {
        val panel = Vidrio.panel(this, tituloLista, dp(380))
        val cont = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dl: Dialog? = null
        opciones.forEachIndexed { i, t ->
            cont.addView(Fila(this, t, aj.colorSel).apply {
                if (fuentes) texto.typeface = Fuentes.normal(this@MainActivity, i)   // cada fuente se ve con su letra
                if (i == actual) accesorio = View(this@MainActivity).apply {
                    background = icono(Icono.Tipo.CHECK, conAlfa(aj.colorSel, 0xFF), 18)
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                }
                setOnClickListener { dl?.dismiss(); alElegir(i) }
            }, filaLp())
        }
        panel.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(cont) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dl = Vidrio.dialogo(this, panel, aj,
            alto = if (opciones.size > 8) resources.displayMetrics.heightPixels - dp(80) else ViewGroup.LayoutParams.WRAP_CONTENT)
        if (actual >= 0) (cont.getChildAt(actual) ?: cont.getChildAt(0))?.requestFocus()
    }

    private fun elegirFuente() {
        elegir("Tipografía", Fuentes.LISTA.map { it.nombre }, fuentes = true, actual = aj.fuente) { i ->
            if (i == aj.fuente) return@elegir
            aj.fuente = i
            Fuentes.actual = i
            // Toda la pantalla usa la fuente: se rehace y se vuelve a Launcher en la misma fila.
            reabrirLauncher = "fuente"
            dialogo?.dismiss()
            recreate()
        }
    }

    private fun elegirFondo(listo: () -> Unit) {
        val fondos = CatalogoFondos.todos(this)
        val ops = mutableListOf("Sin fondo")
        ops += fondos.map { if (it.clave.startsWith("asset:")) it.nombre else "${it.tipo.etiqueta} · ${it.nombre}" }
        if (!Environment.isExternalStorageManager()) ops += "Sin permiso para leer /sdcard/Fondos (ver README)"
        elegir("Fondo", ops) { i ->
            val clave = when {
                i == 0 -> ""
                i - 1 < fondos.size -> fondos[i - 1].clave
                else -> return@elegir
            }
            aj.fondo = clave
            fondo.mostrar(clave)
            listo()
        }
    }

    private fun elegirOcultas(listo: () -> Unit) {
        val ocultas = lista.ocultas().toList()
        if (ocultas.isEmpty()) return avisar("No hay apps ocultas")
        elegir("Volver a mostrar", ocultas.map { Apps.nombre(this, it) }) { i ->
            lista.mostrar(ocultas[i])
            pintarCarrusel(true)
            listo()
        }
    }

    private fun pedirCiudad(listo: (String) -> Unit) {
        val campo = EditText(this).apply { setText(aj.ciudad); setSingleLine(); selectAll() }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Ciudad del tiempo")
            .setView(campo)
            .setPositiveButton("Guardar") { _, _ -> campo.text.toString().trim().takeIf { it.isNotEmpty() }?.let(listo) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun elegirCopia() {
        val f = File(Environment.getExternalStorageDirectory(), "InicioTV/copia.json")
        elegir("Copia de ajustes", listOf("Guardar copia en /sdcard/InicioTV", "Recuperar copia")) { i ->
            try {
                if (i == 0) {
                    aj.guardarCopia(f)
                    avisar("Copia guardada")
                } else {
                    if (!f.isFile) return@elegir avisar("No hay copia guardada")
                    aj.recuperarCopia(f)
                    dialogo?.dismiss()
                    recreate()
                }
            } catch (e: Exception) {
                avisar("Sin permiso para escribir en /sdcard (ver README)")
            }
        }
    }

    // ---------- Tiempo, fondo automático y salvapantallas ----------

    private fun pedirTiempo() {
        if (!aj.tiempo) return
        ultimoTiempo = SystemClock.uptimeMillis()
        Tiempo.pedir(aj.ciudad, aj.p) { t, codigo ->
            if (t != null) { textoTiempo = t; codigoTiempo = codigo }
            tiempo.text = textoTiempo
            salvaTiempo.text = textoTiempo ?: ""
            val tipo = Icono.clima(codigoTiempo)
            tiempo.setCompoundDrawablesRelative(icono(tipo, 0xFFFFD27A.toInt(), 16), null, null, null)
            salvaTiempo.setCompoundDrawablesRelative(icono(tipo, 0xFFFFD27A.toInt(), 22), null, null, null)
            tiempo.visibility = if (aj.tiempo && textoTiempo != null) View.VISIBLE else View.GONE
        }
    }

    private fun comprobarTiempos() {
        val ahora = SystemClock.uptimeMillis()
        val falta = textoTiempo == null && (ultimoTiempo == 0L || ahora - ultimoTiempo > 60_000)
        if (aj.tiempo && (falta || ahora - ultimoTiempo > 30 * 60_000L)) pedirTiempo()
        if (aj.rotarMin > 0 && ahora - ultimaRotacion > aj.rotarMin * 60_000L) {
            ultimaRotacion = ahora
            siguienteFondo()
        }
        if (aj.salvaMin > 0 && !enSalva && dialogo?.isShowing != true && moviendo == null &&
            ahora - ultimaTecla > aj.salvaMin * 60_000L) entrarSalva()
    }

    private fun siguienteFondo() {
        val l = CatalogoFondos.todos(this)
        if (l.isEmpty()) return
        val i = l.indexOfFirst { it.clave == aj.fondo }
        aj.fondo = l[(i + 1) % l.size].clave
        fondo.mostrar(aj.fondo)
    }

    private fun entrarSalva() {
        enSalva = true
        contenido.animate().alpha(0f).setDuration(700).start()
        salva.visibility = View.VISIBLE
        salva.animate().alpha(1f).setDuration(700).start()
    }

    private fun salirSalva(animar: Boolean) {
        if (!enSalva) return
        enSalva = false
        contenido.animate().alpha(1f).setDuration(if (animar) 250 else 0).start()
        salva.animate().alpha(0f).setDuration(if (animar) 250 else 0).withEndAction { salva.visibility = View.GONE }.start()
    }

    // ---------- Utilidades ----------

    private fun nota(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(0xA6FFFFFF.toInt())
        setPadding(dp(12), dp(12), dp(12), 0)
        setLineSpacing(0f, 1.25f)
    }.conFuente()

    private fun filaLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun lleno() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    companion object {
        const val EXTRA_AJUSTES = "abrir_ajustes"
        /** El inicio de Stark TV está en pantalla. */
        @Volatile var enInicio = false
        private var ajustesRepuestos = false
        /** Fila de Launcher a la que volver tras rehacer la pantalla (cambio de fuente). */
        private var reabrirLauncher: String? = null
    }
}
