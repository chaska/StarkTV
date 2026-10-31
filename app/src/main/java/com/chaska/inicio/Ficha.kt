package com.chaska.inicio

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.animation.OvershootInterpolator

/**
 * Ficha de app en 3D: degradado de su color, brillo de cristal arriba, sombra, y con el foco
 * se levanta, se inclina hacia donde vas, se enciende un halo de su color, le da la vuelta una luz
 * por el borde y le cruza un destello. Debajo, su reflejo en el suelo.
 * Todo se dibuja a mano: sin imágenes ni librerías.
 */
class Ficha(
    ctx: Context,
    val paquete: String?,               // null = la ficha de Favoritos
    tam: Int,
    private val conReflejo: Boolean,
    private val conNombre: Boolean,
    private val animado: Boolean,
) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val esFav = paquete == null
    private val ancho = ANCHOS[tam.coerceIn(0, 3)] * d * (if (esFav) 0.66f else 1f)
    private val alto = ANCHOS[tam.coerceIn(0, 3)] * d * 9f / 16f
    private val pad = 26f * d                         // sitio para el halo y para crecer con el foco
    private val radio = 12f * d
    private val altoNombre = if (conNombre) 22f * d else 0f
    private val altoReflejo = if (conReflejo) alto * 0.4f else 0f
    private val r = RectF(pad, pad, pad + ancho, pad + alto)
    /** Rectángulo de la ficha dentro de la vista (sin el margen del halo). */
    val zona: RectF get() = RectF(r)
    private val rGiro = RectF(r).apply { inset(-3f * d, -3f * d) }
    private val camino = Path().apply { addRoundRect(r, radio, radio, Path.Direction.CW) }

    val nombre: String = if (paquete == null) "Favoritos" else Apps.nombre(ctx, paquete)
    private val banner: Drawable? = paquete?.let { Apps.banner(ctx, it) }
    private val icono: Drawable? = if (banner == null) paquete?.let { Apps.icono(ctx, it) } else null
    val color: Int = if (esFav) 0xFFE0A949.toInt() else colorMedio(banner ?: icono)

    var esFavorita = false
        set(v) { field = v; invalidate() }
    var levantada = false
        set(v) { field = v; aplicarFoco() }
    var alEnfocar: ((Ficha) -> Unit)? = null

    private var halo: Bitmap? = null
    private var animador: ValueAnimator? = null

    private val pSombra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF101014.toInt()
        setShadowLayer(14f * d, 0f, 8f * d, 0xA0000000.toInt())
    }
    private val pFondo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = if (esFav) LinearGradient(r.left, r.top, r.right, r.bottom,
            intArrayOf(0xFFFFE3A3.toInt(), 0xFFE0A949.toInt(), 0xFF8A5A16.toInt(), 0xFF5C3B0C.toInt()),
            floatArrayOf(0f, .3f, .75f, 1f), Shader.TileMode.CLAMP)
        else LinearGradient(r.left, r.top, r.right, r.bottom,
            intArrayOf(mezclar(this@Ficha.color, Color.WHITE, .4f), this@Ficha.color, mezclar(this@Ficha.color, Color.BLACK, .45f)),
            floatArrayOf(0f, .38f, 1f), Shader.TileMode.CLAMP)
    }
    private val pBrillo = Paint().apply {
        shader = LinearGradient(0f, r.top, 0f, r.top + alto * .5f,
            intArrayOf(0x52FFFFFF, 0x0FFFFFFF, 0), floatArrayOf(0f, .84f, 1f), Shader.TileMode.CLAMP)
    }
    private val pSombraInt = Paint().apply {
        shader = LinearGradient(0f, r.bottom - alto * .45f, 0f, r.bottom, 0, 0x59000000, Shader.TileMode.CLAMP)
    }
    private val pApagado = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24000000 }
    private val pBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d; color = 0x2EFFFFFF }
    private val pBordeFoco = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f * d; color = 0xD9FFFFFF.toInt() }
    private val giroShader = SweepGradient(r.centerX(), r.centerY(),
        intArrayOf(0, 0, mezclar(color, Color.WHITE, .6f), Color.WHITE, 0), floatArrayOf(0f, .62f, .78f, .84f, .92f))
    private val matrizGiro = Matrix()
    private val pGiro = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d; shader = giroShader }
    private val anchoDestello = ancho * .35f
    private val pDestello = Paint().apply {
        shader = LinearGradient(0f, 0f, anchoDestello, 0f, intArrayOf(0, 0x73FFFFFF, 0), null, Shader.TileMode.CLAMP)
    }
    private val arribaReflejo = r.bottom + 4f * d + altoNombre
    private val pDesvanecer = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        shader = LinearGradient(0f, arribaReflejo, 0f, arribaReflejo + altoReflejo.coerceAtLeast(1f), 0x47000000, 0, Shader.TileMode.CLAMP)
    }
    private val pTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (esFav) 0xFF2B1A03.toInt() else Color.WHITE
        textSize = alto * .16f
        textAlign = Paint.Align.CENTER
        typeface = Fuentes.medio(ctx)
        if (esFav) setShadowLayer(d, 0f, d, 0x99FFF0C8.toInt()) else setShadowLayer(4f * d, 0f, d, 0x73000000)
    }
    private val estrella = Icono(Icono.Tipo.ESTRELLA_LLENA, 0xFFF2A93B.toInt(), 0).apply {
        val l = (alto * .16f).toInt()
        val x = (r.right - 8f * d - l).toInt()
        val y = (r.top + 7f * d).toInt()
        setBounds(x, y, x + l, y + l)
    }
    private val estrellaFav = Icono(Icono.Tipo.ESTRELLA, 0xFF3A2405.toInt(), 0).apply {
        val l = (alto * .3f).toInt()
        val x = (r.centerX() - l / 2).toInt()
        val y = (r.centerY() - alto * .3f).toInt()
        setBounds(x, y, x + l, y + l)
    }
    private val pNombre = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fuentes.normal(ctx)
        color = 0xB8FFFFFF.toInt()
        textSize = 12f * d
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f * d, 0f, d, 0xCC000000.toInt())
    }

    init {
        isFocusable = true
        isClickable = true
        isLongClickable = true
        contentDescription = nombre
        cameraDistance = 9000f * d
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val abajo = maxOf(pad, 4f * d + altoNombre + altoReflejo)
        setMeasuredDimension((ancho + 2 * pad).toInt(), (r.bottom + abajo).toInt())
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        pivotX = r.centerX()
        pivotY = r.centerY()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        aplicarFoco()
        if (gainFocus) alEnfocar?.invoke(this)
    }

    override fun onDetachedFromWindow() {
        parar()
        super.onDetachedFromWindow()
    }

    private fun aplicarFoco() {
        val activo = isFocused || levantada
        val s = if (levantada) 1.16f else if (activo) 1.13f else 1f
        animate()
            .scaleX(s).scaleY(s)
            .translationY(if (levantada) -22f * d else if (activo) -10f * d else 0f)
            .rotationX(if (levantada) 9f else if (activo) 5f else 0f)
            .rotationY(if (activo) -2.5f * inclinacionActual else 0f)
            .translationZ(if (activo) 12f * d else 0f)
            .setDuration(280)
            .setInterpolator(OvershootInterpolator(1.3f))
            .start()
        if (activo) {
            if (halo == null) halo = crearHalo()
            if (animado) arrancar()
        } else {
            parar()
            halo?.recycle()
            halo = null
        }
        invalidate()
    }

    private fun arrancar() {
        if (animador != null) return
        animador = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { invalidate() }
            start()
        }
    }

    private fun parar() {
        animador?.cancel()
        animador = null
    }

    /** El halo se pinta una vez en un bitmap (el desenfoque es caro) y luego solo se copia. */
    private fun crearHalo(): Bitmap {
        val b = Bitmap.createBitmap((ancho + 2 * pad).toInt(), (alto + 2 * pad).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val tono = mezclar(color, Color.WHITE, .25f)
        p.color = tono; p.alpha = 150; p.maskFilter = BlurMaskFilter(22f * d, BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(RectF(r).apply { inset(-4f * d, -4f * d) }, radio, radio, p)
        p.color = tono; p.alpha = 230; p.maskFilter = BlurMaskFilter(9f * d, BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(RectF(r).apply { inset(-2f * d, -2f * d) }, radio, radio, p)
        return b
    }

    override fun onDraw(c: Canvas) {
        val activo = isFocused || levantada
        if (activo) halo?.let { c.drawBitmap(it, 0f, 0f, null) }
        if (conReflejo) reflejo(c)
        c.drawRoundRect(r, radio, radio, pSombra)
        cuerpo(c)
        if (!activo) c.drawPath(camino, pApagado)
        c.drawRoundRect(r, radio, radio, if (activo) pBordeFoco else pBorde)
        if (activo && animado) { giro(c); destello(c) }
        if (esFavorita) estrella.draw(c)
        if (conNombre) c.drawText(nombre, r.centerX(), r.bottom + 17f * d, pNombre)
    }

    private fun cuerpo(c: Canvas) {
        c.save()
        c.clipPath(camino)
        c.drawRect(r, pFondo)
        when {
            banner != null -> {
                banner.setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
                banner.draw(c)
            }
            esFav -> {
                val y = r.centerY()
                estrellaFav.draw(c)
                c.drawText("Favoritos", r.centerX(), y + alto * .28f, pTexto.apply { textSize = alto * .15f })
            }
            else -> {
                val lado = (alto * .42f).toInt()
                val x = (r.centerX() - lado / 2).toInt()
                val y = (r.top + alto * .14f).toInt()
                icono?.setBounds(x, y, x + lado, y + lado)
                icono?.draw(c)
                c.drawText(nombre, r.centerX(), r.bottom - alto * .14f, pTexto)
            }
        }
        c.drawRect(r, pBrillo)
        c.drawRect(r, pSombraInt)
        c.restore()
    }

    /** La ficha dada la vuelta bajo ella, cada vez más transparente: el suelo brillante. */
    private fun reflejo(c: Canvas) {
        val arriba = arribaReflejo
        val abajo = arriba + altoReflejo
        val capa = c.saveLayer(0f, arriba, width.toFloat(), abajo, null)
        c.save()
        c.scale(1f, -1f, 0f, (r.bottom + arriba) / 2f)
        cuerpo(c)
        c.restore()
        c.drawRect(0f, arriba, width.toFloat(), abajo, pDesvanecer)
        c.restoreToCount(capa)
    }

    private fun giro(c: Canvas) {
        val ang = (SystemClock.uptimeMillis() % 3200) / 3200f * 360f
        matrizGiro.setRotate(ang, r.centerX(), r.centerY())
        giroShader.setLocalMatrix(matrizGiro)
        c.drawRoundRect(rGiro, radio + 3f * d, radio + 3f * d, pGiro)
    }

    private fun destello(c: Canvas) {
        val f = (SystemClock.uptimeMillis() % 2800) / 2800f
        if (f > .55f) return
        val x = r.left - anchoDestello * 1.5f + (f / .55f) * (ancho + anchoDestello * 3f)
        c.save()
        c.clipPath(camino)
        c.translate(x + .36f * r.centerY(), 0f)
        c.skew(-.36f, 0f)
        c.drawRect(0f, r.top - alto, anchoDestello, r.bottom + alto, pDestello)
        c.restore()
    }

    companion object {
        val ANCHOS = floatArrayOf(112f, 144f, 176f, 208f)

        /** -1 si te mueves a la izquierda, 1 a la derecha: la ficha que recibe el foco se inclina hacia ahí. */
        var inclinacionActual = 0f

        fun mezclar(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
        )

        /** Color medio del banner o icono, para el halo y la luz ambiental. */
        fun colorMedio(dr: Drawable?): Int {
            if (dr == null) return 0xFF4A5A6E.toInt()
            val b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            dr.setBounds(0, 0, 8, 8)
            dr.draw(Canvas(b))
            var rr = 0L; var gg = 0L; var bb = 0L; var n = 0
            for (y in 0 until 8) for (x in 0 until 8) {
                val px = b.getPixel(x, y)
                if (Color.alpha(px) < 128) continue
                rr += Color.red(px); gg += Color.green(px); bb += Color.blue(px); n++
            }
            b.recycle()
            if (n == 0) return 0xFF4A5A6E.toInt()
            val c = Color.rgb((rr / n).toInt(), (gg / n).toInt(), (bb / n).toInt())
            val hsv = FloatArray(3)
            Color.colorToHSV(c, hsv)
            hsv[1] = (hsv[1] * 1.25f).coerceAtMost(1f)          // un poco más vivo
            hsv[2] = hsv[2].coerceIn(.45f, .9f)                 // ni negro ni blanco
            return Color.HSVToColor(hsv)
        }
    }
}
