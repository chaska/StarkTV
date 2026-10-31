package com.chaska.inicio

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
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
import java.io.File

/**
 * Pantalla de inicio: fondo + luces, botón Ajustes, reloj con el tiempo y el carrusel
 * (Favoritos primero y luego todas las apps). Menús y ajustes en paneles de cristal.
 */
class MainActivity : Activity() {
    private val aj by lazy { Ajustes(this) }
    private val lista by lazy { ListaApps(this, aj.p) }

    private lateinit var fondo: Fondo
    private lateinit var velo: View
    private lateinit var luces: Luces
    private lateinit var contenido: FrameLayout
    private lateinit var botonAjustes: TextView
    private lateinit var hora: TextClock
    private lateinit var fecha: TextClock
    private lateinit var tiempo: TextView
    private lateinit var titulo: TextView
    private lateinit var fila: LinearLayout
    private lateinit var aviso: TextView
    private lateinit var salva: LinearLayout
    private lateinit var salvaTiempo: TextView

    private var dialogo: Dialog? = null
    private var moviendo: Ficha? = null
    private var firma = ""
    private var textoTiempo: String? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    }

    override fun onStart() {
        super.onStart()
        aplicar()
        fondo.mostrar(aj.fondo)
        pintarCarrusel()
        ultimaTecla = SystemClock.uptimeMillis()
        reloj.removeCallbacks(tic)
        reloj.post(tic)
    }

    override fun onStop() {
        // Fuera del inicio el fondo no gasta nada.
        fondo.liberar()
        reloj.removeCallbacks(tic)
        soltar()
        salirSalva(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pulsar Inicio estando ya aquí: cierra todo y vuelve al principio del carrusel.
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
        val m = moviendo
        if (m != null) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (e.action == KeyEvent.ACTION_DOWN) moverPaso(m, if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                    if (e.action == KeyEvent.ACTION_UP) soltar()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
            }
        }
        if (e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> Ficha.inclinacionActual = -1f
            KeyEvent.KEYCODE_DPAD_RIGHT -> Ficha.inclinacionActual = 1f
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> Ficha.inclinacionActual = 0f
        }
        if (e.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(e)
    }

    // ---------- Construcción ----------

    private fun construirInicio() {
        botonAjustes = TextView(this).apply {
            text = "⚙  Ajustes"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            isFocusable = true
            elevation = dp(6).toFloat()
            setOnClickListener { abrirAjustes() }
            setOnFocusChangeListener { v, f ->
                v.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f)
                    .translationY(if (f) -dp(3).toFloat() else 0f).setDuration(160).start()
                v.elevation = dp(if (f) 14 else 6).toFloat()
                v.outlineSpotShadowColor = if (f) aj.colorSel else Color.BLACK
                v.outlineAmbientShadowColor = if (f) aj.colorSel else Color.BLACK
                if (f) apuntarLuz(v, aj.colorSel, v.width / 2f, v.height / 2f)
            }
        }
        contenido.addView(botonAjustes, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START).apply { setMargins(dp(40), dp(30), 0, 0) })

        val bloque = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        hora = relojDegradado(40f)
        fecha = TextClock(this).apply {
            format24Hour = "EEEE, d 'de' MMMM"; format12Hour = format24Hour
            textSize = 14f
            setTextColor(0xC8FFFFFF.toInt())
            setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
        }
        tiempo = TextView(this).apply {
            textSize = 13f
            setTextColor(0xE6FFFFFF.toInt())
            setPadding(dp(10), dp(3), dp(10), dp(3))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x1FFFFFFF, 0x08FFFFFF)).apply {
                cornerRadius = dp(20).toFloat(); setStroke(dp(1), 0x22FFFFFF)
            }
            visibility = View.GONE
        }
        bloque.addView(hora)
        bloque.addView(fecha)
        bloque.addView(tiempo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        contenido.addView(bloque, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END).apply { setMargins(0, dp(22), dp(40), 0) })

        val abajo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        titulo = TextView(this).apply {
            textSize = 14f
            setTextColor(0xB8FFFFFF.toInt())
            setShadowLayer(dp(8).toFloat(), 0f, 0f, 0xCC000000.toInt())
        }
        abajo.addView(titulo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(44); bottomMargin = -dp(14)
        })
        fila = LinearLayout(this).apply { clipChildren = false; clipToPadding = false; gravity = Gravity.TOP }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            isFocusable = false
            setPadding(dp(14), 0, dp(40), 0)
            addView(fila)
        }
        abajo.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contenido.addView(abajo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply { bottomMargin = dp(6) })

        aviso = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xD9282E42.toInt(), 0xE60A0C14.toInt())).apply {
                cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0x26FFFFFF)
            }
            elevation = dp(20).toFloat()
            visibility = View.GONE
        }
        contenido.addView(aviso, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (resources.displayMetrics.heightPixels * .33f).toInt() })
    }

    private fun construirSalva(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        alpha = 0f
        visibility = View.GONE
        addView(relojDegradado(110f))
        addView(TextClock(this@MainActivity).apply {
            format24Hour = "EEEE, d 'de' MMMM"; format12Hour = format24Hour
            textSize = 22f
            setTextColor(0xC8FFFFFF.toInt())
        })
        salvaTiempo = TextView(this@MainActivity).apply { textSize = 18f; setTextColor(0xC8FFFFFF.toInt()); setPadding(0, dp(8), 0, 0) }
        addView(salvaTiempo)
    }

    /** Hora en degradado plateado con un leve resplandor azulado. */
    private fun relojDegradado(tam: Float) = TextClock(this).apply {
        format24Hour = "HH:mm"; format12Hour = "HH:mm"
        textSize = tam
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.WHITE)
        setShadowLayer(dp(14).toFloat(), 0f, 0f, 0x668CB4FF)
        addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            (v as TextView).paint.shader = LinearGradient(0f, 0f, 0f, (bottom - top).toFloat(),
                Color.WHITE, 0xFFC9D4EA.toInt(), Shader.TileMode.CLAMP)
        }
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

    // ---------- Estado ----------

    private fun aplicar() {
        velo.setBackgroundColor(Color.argb((100 - aj.brillo.coerceIn(0, 100)) * 255 / 100, 0, 0, 0))
        hora.visibility = if (aj.reloj) View.VISIBLE else View.GONE
        fecha.visibility = hora.visibility
        tiempo.visibility = if (aj.tiempo && textoTiempo != null) View.VISIBLE else View.GONE
        botonAjustes.background = fondoBoton()
    }

    private fun firmaActual() = listOf(lista.orden(), lista.favoritas(), aj.tamano, aj.nombres, aj.reflejo, aj.animaciones).toString()

    /** Rehace el carrusel solo si algo cambió (orden, favoritas, tamaño…): volver al inicio es instantáneo. */
    private fun pintarCarrusel(forzar: Boolean = false) {
        val nueva = firmaActual()
        if (!forzar && nueva == firma && fila.childCount > 0) return
        firma = nueva
        val enfocado = (currentFocus as? Ficha)?.paquete
        val orden = lista.orden()
        val favs = lista.favoritas()
        fila.removeAllViews()
        val sep = dp(18) - 2 * dp(26)    // las fichas llevan margen interno para el halo
        fun anadir(f: Ficha) {
            f.alEnfocar = { x -> x.postDelayed({ apuntarLuz(x, x.color, x.pivotX, x.pivotY) }, 120) }
            fila.addView(f, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (fila.childCount > 0) marginStart = sep
            })
        }
        anadir(nuevaFicha(null).apply { setOnClickListener { abrirFavoritos() } })
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

    private fun nuevaFicha(p: String?) = Ficha(this, p, aj.tamano, aj.reflejo, aj.nombres, aj.animaciones)

    private fun fichas(): List<Ficha> = (0 until fila.childCount).mapNotNull { fila.getChildAt(it) as? Ficha }

    private fun refrescarEstrellas() {
        val favs = lista.favoritas()
        fichas().forEach { f -> f.paquete?.let { f.esFavorita = it in favs } }
        firma = firmaActual()
    }

    private fun apuntarLuz(v: View, color: Int, cx: Float, cy: Float) {
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        luces.apuntar(loc[0] + cx, loc[1] + cy, color)
    }

    // ---------- Acciones ----------

    private fun abrir(p: String) {
        val i = Apps.intentDe(this, p) ?: return avisar("No se puede abrir")
        try { startActivity(i) } catch (e: Exception) { avisar("No se puede abrir") }
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
        fun op(t: String, accion: () -> Unit) = panel.addView(Fila(this, t, aj.colorSel).apply {
            setOnClickListener { dialogo?.dismiss(); accion() }
        }, filaLp())
        op(if (esFav) "★  Quitar de favoritos" else "☆  Añadir a favoritos") {
            if (esFav) lista.quitarFavorita(p)
            else if (!lista.anadirFavorita(p)) avisar("Favoritos llenos: quita una antes (máximo 6)")
            refrescarEstrellas()
        }
        op("⇆  Mover") { entrarMover(f) }
        op("⊘  Ocultar de la barra") {
            lista.ocultar(p)
            pintarCarrusel(forzar = true)
            avisar("Oculta. Se recupera en Ajustes del launcher")
        }
        op("ⓘ  Información de la app") {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", p, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { avisar("No disponible") }
        }
        // Encima de la ficha.
        val loc = IntArray(2)
        f.getLocationOnScreen(loc)
        val pantalla = resources.displayMetrics
        val x = (loc[0] + f.pivotX - ancho / 2f).toInt().coerceIn(dp(24), pantalla.widthPixels - ancho - dp(24))
        val y = pantalla.heightPixels - loc[1] - dp(14)
        dialogo = Vidrio.dialogo(this, panel, aj, Gravity.BOTTOM or Gravity.START, x, y)
    }

    private fun entrarMover(f: Ficha) {
        moviendo = f
        f.levantada = true
        fichas().forEach { if (it !== f) it.animate().alpha(.4f).setDuration(200).start() }
        aviso.removeCallbacks(ocultarAviso)
        aviso.text = "Mantén ◀ o ▶ para llevarla · OK para soltar"
        aviso.visibility = View.VISIBLE
        f.requestFocus()
    }

    private fun moverPaso(f: Ficha, dx: Int) {
        val i = fila.indexOfChild(f)
        val j = i + dx
        if (j < 1 || j >= fila.childCount) return     // la 0 es Favoritos, no se mueve
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
        lista.guardarOrden(fichas().mapNotNull { it.paquete })
        firma = firmaActual()
    }

    private fun abrirFavoritos() {
        val favs = lista.favoritas()
        val panel = Vidrio.panel(this, "★  Favoritos", 0)
        val rejilla = GridLayout(this).apply { columnCount = 3; clipChildren = false; clipToPadding = false }
        val solape = -dp(16)
        for (k in 0 until ListaApps.MAX_FAV) {
            val p = favs.getOrNull(k)
            if (p != null) {
                val f = Ficha(this, p, 1, false, false, aj.animaciones)
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
                }
                rejilla.addView(hueco, GridLayout.LayoutParams().apply {
                    width = dp(Ficha.ANCHOS[1]); height = dp(Ficha.ANCHOS[1] * 9 / 16)
                    setMargins(dp(10), dp(10), dp(10), dp(10))
                })
            }
        }
        panel.addView(rejilla)
        if (favs.isEmpty()) panel.addView(nota("Mantén OK sobre una app del carrusel y elige «Añadir a favoritos»."))
        else panel.addView(nota("Mantén OK sobre una para quitarla."))
        dialogo = Vidrio.dialogo(this, panel, aj)
    }

    // ---------- Ajustes ----------

    private fun abrirAjustes(enfocar: Int = -1) {
        val sel = aj.colorSel
        val col1 = Vidrio.panel(this, "⚙  Ajustes", dp(250))
        val generales = Fila(this, "📺  Ajustes generales", sel).apply {
            setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (e: Exception) { avisar("No disponible") }
            }
        }
        val delLauncher = Fila(this, "▦  Ajustes del launcher", sel)
        col1.addView(generales, filaLp())
        col1.addView(delLauncher, filaLp())
        col1.addView(nota("«Generales» abre los ajustes de la TV: red, sonido, imagen…"))

        val col2 = Vidrio.panel(this, "Ajustes del launcher", dp(440))
        val filas = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col2.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(filas) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        delLauncher.setOnClickListener { filas.getChildAt(0)?.requestFocus() }

        fun nueva(t: String, valor: String? = null, acc: View? = null, alPulsar: (Fila) -> Unit): Fila {
            val f = Fila(this, t, sel)
            if (valor != null) f.valor.text = valor
            f.accesorio = acc
            f.setOnClickListener { alPulsar(f) }
            filas.addView(f, filaLp())
            return f
        }
        fun interruptor(t: String, valor: Boolean, cambiar: (Boolean) -> Unit) {
            val sw = Interruptor(this, valor)
            nueva(t, acc = sw) { sw.encendido = !sw.encendido; cambiar(sw.encendido) }
        }

        nueva("Fondo", "${nombreFondo(aj.fondo)} ›") { f -> elegirFondo { f.valor.text = "${nombreFondo(aj.fondo)} ›" } }

        val barra = Barra(this, sel, aj.brillo)
        val filaBrillo = nueva("Brillo del fondo", "${aj.brillo}%", barra) { f ->
            aj.brillo = if (aj.brillo >= 100) 0 else aj.brillo + 10
            barra.valor = aj.brillo; f.valor.text = "${aj.brillo}%"; aplicar()
        }
        filaBrillo.setOnKeyListener { _, code, ev ->
            // ◀ ▶ suben y bajan el brillo sin salir de la fila.
            if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
            if (ev.action == KeyEvent.ACTION_DOWN) {
                aj.brillo = (aj.brillo + if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 10 else -10).coerceIn(0, 100)
                barra.valor = aj.brillo; filaBrillo.valor.text = "${aj.brillo}%"; aplicar()
            }
            true
        }

        interruptor("Mostrar reloj", aj.reloj) { aj.reloj = it; aplicar() }
        interruptor("Mostrar tiempo", aj.tiempo) { aj.tiempo = it; aplicar(); if (it) pedirTiempo() }
        nueva("Ciudad del tiempo", "${aj.ciudad} ›") { f ->
            pedirCiudad { c -> aj.ciudad = c; f.valor.text = "$c ›"; textoTiempo = null; pedirTiempo() }
        }
        interruptor("Nombre bajo las apps", aj.nombres) { aj.nombres = it; pintarCarrusel(true) }
        nueva("Tamaño de fichas", "${Ajustes.TAMANOS[aj.tamano]} ›") { f ->
            aj.tamano = (aj.tamano + 1) % Ajustes.TAMANOS.size
            f.valor.text = "${Ajustes.TAMANOS[aj.tamano]} ›"; pintarCarrusel(true)
        }
        nueva("Apps ocultas", "${lista.ocultas().size} ›") { f -> elegirOcultas { f.valor.text = "${lista.ocultas().size} ›" } }
        nueva("Cambiar fondo solo", textoRotar()) { f ->
            val l = Ajustes.MINUTOS_ROTAR
            aj.rotarMin = l[(l.indexOf(aj.rotarMin) + 1) % l.size]
            ultimaRotacion = SystemClock.uptimeMillis(); f.valor.text = textoRotar()
        }
        nueva("Salvapantallas", textoSalva()) { f ->
            val l = Ajustes.MINUTOS_SALVA
            aj.salvaMin = l[(l.indexOf(aj.salvaMin) + 1) % l.size]
            f.valor.text = textoSalva()
        }
        nueva("Color de la selección", "${Ajustes.COLORES[aj.color].first} ›") {
            aj.color = (aj.color + 1) % Ajustes.COLORES.size
            aplicar()
            dialogo?.dismiss()
            abrirAjustes(enfocar = 10)     // se vuelve a abrir para pintarlo con el color nuevo
        }
        interruptor("Reflejo en el suelo", aj.reflejo) { aj.reflejo = it; pintarCarrusel(true) }
        interruptor("Cristal difuminado", aj.cristal) { aj.cristal = it }
        interruptor("Animaciones de luz", aj.animaciones) { aj.animaciones = it; pintarCarrusel(true) }
        nueva("Copia de ajustes", "Guardar o recuperar ›") { elegirCopia() }
        filas.addView(nota("Inicio TV ${packageManager.getPackageInfo(packageName, 0).versionName}"))

        val dos = LinearLayout(this).apply { setPadding(dp(24), dp(24), dp(24), dp(24)) }
        dos.addView(col1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dos.addView(col2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = dp(16) })
        dialogo = Vidrio.dialogo(this, dos, aj, Gravity.START or Gravity.TOP, 0, 0, ViewGroup.LayoutParams.MATCH_PARENT)
        if (enfocar >= 0) filas.getChildAt(enfocar)?.requestFocus() else delLauncher.requestFocus()
    }

    private fun textoRotar() = if (aj.rotarMin == 0) "No ›" else "Cada ${aj.rotarMin} min ›"
    private fun textoSalva() = if (aj.salvaMin == 0) "No ›" else "Tras ${aj.salvaMin} min ›"

    private fun nombreFondo(clave: String): String = when {
        clave.isEmpty() -> "Ninguno"
        clave.endsWith("/index.html") -> File(clave).parentFile?.name ?: clave
        else -> clave.removePrefix("asset:").substringAfterLast('/').substringBeforeLast('.')
    }

    /** Lista de cristal encima de lo que haya; al elegir se cierra solo esa lista. */
    private fun elegir(tituloLista: String, opciones: List<String>, alElegir: (Int) -> Unit) {
        val panel = Vidrio.panel(this, tituloLista, dp(380))
        val cont = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dl: Dialog? = null
        opciones.forEachIndexed { i, t ->
            cont.addView(Fila(this, t, aj.colorSel).apply { setOnClickListener { dl?.dismiss(); alElegir(i) } }, filaLp())
        }
        panel.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(cont) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dl = Vidrio.dialogo(this, panel, aj,
            alto = if (opciones.size > 8) resources.displayMetrics.heightPixels - dp(80) else ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun elegirFondo(listo: () -> Unit) {
        val fondos = CatalogoFondos.todos(this)
        val ops = mutableListOf("Sin fondo (negro)")
        ops += fondos.map { "${it.tipo.etiqueta} · ${it.nombre}" }
        if (!Environment.isExternalStorageManager()) ops += "⚠ Sin permiso para leer /sdcard/Fondos (ver README)"
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
                    aplicar()
                    fondo.mostrar(aj.fondo)
                    pintarCarrusel(true)
                    avisar("Copia recuperada")
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
        Tiempo.pedir(aj.ciudad, aj.p) { t ->
            if (t != null) textoTiempo = t
            tiempo.text = textoTiempo
            salvaTiempo.text = textoTiempo ?: ""
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
    }

    private fun filaLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun lleno() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
}
