package com.chaska.inicio

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast

/** Pantalla de inicio: fondo, reloj, botón de ajustes y fila de favoritas. Todo hecho en código, sin librerías. */
class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("ajustes", MODE_PRIVATE) }
    private val favoritas by lazy { Favoritas(this, prefs) }
    private lateinit var fondo: Fondo
    private lateinit var velo: View
    private lateinit var fila: LinearLayout
    private var dialogo: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val capa = FrameLayout(this)
        raiz.addView(capa, lleno())
        fondo = Fondo(this, capa)
        velo = View(this)
        raiz.addView(velo, lleno())
        raiz.addView(contenido(), lleno())
        setContentView(raiz)
    }

    override fun onStart() {
        super.onStart()
        velo.setBackgroundColor(Color.argb(prefs.getInt("oscurecer", 20) * 255 / 100, 0, 0, 0))
        fondo.mostrar(prefs.getString("fondo", "asset:particulas.html"))
        pintarFavoritas()
    }

    override fun onStop() {
        // Fuera del inicio el fondo no debe gastar nada.
        fondo.liberar()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pulsar "Inicio" estando ya aquí: cierra menús y vuelve a la primera favorita.
        dialogo?.dismiss()
        fila.getChildAt(0)?.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK) true else super.onKeyDown(keyCode, event)

    // ---------- Interfaz ----------

    private fun contenido(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(40))
        }

        val arriba = LinearLayout(this).apply { gravity = Gravity.TOP }
        val ajustes = TextView(this).apply {
            text = "⚙  Ajustes"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setBackgroundResource(R.drawable.marco)
            isFocusable = true
            setOnClickListener { menuAjustes() }
            agrandarConFoco(this)
        }
        arriba.addView(ajustes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        arriba.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        val reloj = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        reloj.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            textSize = 64f; setTextColor(Color.WHITE); sombra(this)
        })
        reloj.addView(TextClock(this).apply {
            format24Hour = "EEEE, d 'de' MMMM"; format12Hour = "EEEE, d 'de' MMMM"
            textSize = 20f; setTextColor(Color.argb(220, 255, 255, 255)); sombra(this)
        })
        arriba.addView(reloj)
        col.addView(arriba)

        col.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        fila = LinearLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(dp(8), dp(12), dp(8), dp(12)) }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false; clipToPadding = false
            addView(fila)
        }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return col
    }

    private fun pintarFavoritas(enfocar: String? = null) {
        fila.removeAllViews()
        for (p in favoritas.lista()) fila.addView(ficha(p))
        fila.addView(fichaAnadir())
        val i = favoritas.lista().indexOf(enfocar)
        (fila.getChildAt(if (i >= 0) i else 0))?.requestFocus()
    }

    private fun ficha(paquete: String): View {
        val f = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.marco)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            isFocusable = true
            contentDescription = Apps.nombre(this@MainActivity, paquete)
        }
        val banner = Apps.banner(this, paquete)
        if (banner != null) {
            f.addView(ImageView(this).apply { setImageDrawable(banner); scaleType = ImageView.ScaleType.FIT_CENTER }, lleno())
        } else {
            // Sin banner: icono y nombre.
            val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            l.addView(ImageView(this).apply { setImageDrawable(Apps.icono(this@MainActivity, paquete)) },
                LinearLayout.LayoutParams(dp(56), dp(56)))
            l.addView(TextView(this).apply {
                text = Apps.nombre(this@MainActivity, paquete); setTextColor(Color.WHITE)
                textSize = 15f; gravity = Gravity.CENTER; maxLines = 1
                setPadding(dp(6), dp(6), dp(6), 0)
            })
            f.addView(l, lleno())
        }
        f.setOnClickListener { abrir(paquete) }
        f.setOnLongClickListener { menuFicha(paquete); true }
        agrandarConFoco(f)
        f.layoutParams = LinearLayout.LayoutParams(dp(208), dp(117)).apply { marginEnd = dp(20) }
        return f
    }

    private fun fichaAnadir(): View = TextView(this).apply {
        text = "+"
        textSize = 44f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setBackgroundResource(R.drawable.marco)
        isFocusable = true
        contentDescription = "Añadir app"
        setOnClickListener { elegirApp() }
        agrandarConFoco(this)
        layoutParams = LinearLayout.LayoutParams(dp(117), dp(117))
    }

    // ---------- Acciones ----------

    private fun abrir(paquete: String) {
        val i = Apps.intentDe(this, paquete)
        if (i == null) { aviso("No se puede abrir"); return }
        try { startActivity(i) } catch (e: Exception) { aviso("No se puede abrir") }
    }

    private fun menuFicha(paquete: String) {
        val ops = arrayOf("◀  Mover a la izquierda", "▶  Mover a la derecha", "✕  Quitar de favoritas")
        lista(Apps.nombre(this, paquete), ops) { w ->
            when (w) {
                0 -> favoritas.mover(paquete, -1)
                1 -> favoritas.mover(paquete, +1)
                2 -> favoritas.quitar(paquete)
            }
            pintarFavoritas(paquete)
        }
    }

    private fun elegirApp() {
        val ya = favoritas.lista()
        val apps = Apps.todas(this).filter { it.paquete !in ya }
        if (apps.isEmpty()) { aviso("Ya están todas"); return }
        lista("Añadir a favoritas", apps.map { it.nombre }.toTypedArray()) { w ->
            favoritas.anadir(apps[w].paquete)
            pintarFavoritas(apps[w].paquete)
        }
    }

    private fun menuAjustes() {
        val ops = arrayOf("🖼  Cambiar fondo", "🌓  Oscurecer fondo", "➕  Añadir app a favoritas", "⚙  Ajustes de la TV", "ℹ  Versión")
        lista("Ajustes", ops) { w ->
            when (w) {
                0 -> elegirFondo()
                1 -> elegirOscuro()
                2 -> elegirApp()
                3 -> try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e: Exception) { aviso("No disponible") }
                4 -> aviso("Inicio TV " + packageManager.getPackageInfo(packageName, 0).versionName)
            }
        }
    }

    private fun elegirFondo() {
        val fondos = CatalogoFondos.todos(this)
        val nombres = mutableListOf("Sin fondo (negro)")
        nombres += fondos.map { "${it.tipo.etiqueta} · ${it.nombre}" }
        if (!Environment.isExternalStorageManager()) nombres += "⚠ Sin permiso para leer /sdcard/Fondos (ver README)"
        lista("Fondo", nombres.toTypedArray()) { w ->
            val clave = when {
                w == 0 -> ""
                w - 1 < fondos.size -> fondos[w - 1].clave
                else -> return@lista
            }
            prefs.edit().putString("fondo", clave).apply()
            fondo.mostrar(clave)
        }
    }

    private fun elegirOscuro() {
        val valores = intArrayOf(0, 20, 40, 60)
        lista("Oscurecer fondo", valores.map { "$it %" }.toTypedArray()) { w ->
            prefs.edit().putInt("oscurecer", valores[w]).apply()
            velo.setBackgroundColor(Color.argb(valores[w] * 255 / 100, 0, 0, 0))
        }
    }

    // ---------- Utilidades ----------

    private fun lista(titulo: String, ops: Array<String>, alElegir: (Int) -> Unit) {
        dialogo?.dismiss()
        dialogo = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(titulo)
            .setItems(ops) { _, w -> alElegir(w) }
            .show()
    }

    private fun agrandarConFoco(v: View) {
        v.setOnFocusChangeListener { x, foco ->
            val s = if (foco) 1.08f else 1f
            x.animate().scaleX(s).scaleY(s).setDuration(120).start()
            x.elevation = if (foco) dp(8).toFloat() else 0f
        }
    }

    private fun sombra(t: TextView) = t.setShadowLayer(8f, 0f, 2f, Color.argb(200, 0, 0, 0))

    private fun aviso(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun lleno() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
}
