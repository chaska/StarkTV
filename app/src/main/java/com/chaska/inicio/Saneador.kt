package com.chaska.inicio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Comprueba que un archivo recibido es de verdad lo que dice su extensión y le quita los metadatos
 * (EXIF con GPS, XMP, perfiles, comentarios, etiquetas de vídeo, adjuntos) dejando solo la imagen o el vídeo.
 * Falla cerrado: si no lo reconoce o no lo puede limpiar, lanza Rechazado y el archivo no se guarda.
 * Imágenes: se reescriben trozo a trozo sin recomprimir (salvo una foto girada por EXIF, que se gira y se
 * vuelve a guardar). Vídeos: las cajas de metadatos se vacían en el sitio, sin mover nada.
 */
object Saneador {
    class Rechazado(motivo: String) : Exception(motivo)

    private const val LADO_MAXIMO = 8192          // más grande que esto no se descodifica (bomba de píxeles)
    private const val HTML_MAXIMO = 5L * 1024 * 1024

    /** Deja [f] limpio según [ext] (en minúsculas) o lanza Rechazado. */
    fun limpiar(f: File, ext: String) {
        when (ext) {
            "jpg", "jpeg" -> { exigir(f, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())); jpeg(f); imagenValida(f) }
            "png" -> { exigir(f, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)); png(f); imagenValida(f) }
            "webp" -> { webp(f); imagenValida(f) }
            "gif" -> { gif(f); imagenValida(f) }
            "mp4", "mov" -> isoBmff(f)
            "webm", "mkv" -> { exigir(f, byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())); matroska(f) }
            "html", "htm" -> html(f)
            else -> throw Rechazado("Tipo no admitido")
        }
    }

    private const val LADO_WEBP = 2560          // la TV es 2K: más grande no se ve mejor y ocupa más

    /**
     * Pasa una imagen fija ya limpia a WebP (y a [LADO_WEBP] px de lado como mucho). Devuelve true si la convirtió;
     * los GIF y WebP animados, y los WebP que ya son de buen tamaño, se dejan como están.
     */
    fun aWebp(f: File, ext: String): Boolean {
        if (ext !in setOf("jpg", "jpeg", "png", "gif", "webp")) return false
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        val lado = maxOf(o.outWidth, o.outHeight)
        if (ext == "webp" && lado <= LADO_WEBP) return false
        val fuente = ImageDecoder.createSource(f)
        if ((ext == "gif" || ext == "webp") && ImageDecoder.decodeDrawable(fuente) is android.graphics.drawable.AnimatedImageDrawable) return false
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val m = maxOf(info.size.width, info.size.height)
            if (m > LADO_WEBP) d.setTargetSize(info.size.width * LADO_WEBP / m, info.size.height * LADO_WEBP / m)
        }
        try { reescribir(f) { out -> if (!bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 88, out)) throw Rechazado("No se pudo pasar a WebP") } }
        finally { bmp.recycle() }
        return true
    }

    private fun cabecera(f: File, n: Int): ByteArray = f.inputStream().use { i ->
        val b = ByteArray(n); var leidos = 0
        while (leidos < n) { val r = i.read(b, leidos, n - leidos); if (r < 0) break; leidos += r }
        if (leidos < n) throw Rechazado("Archivo vacío o cortado")
        b
    }

    private fun exigir(f: File, magia: ByteArray) {
        if (!cabecera(f, magia.size).contentEquals(magia)) throw Rechazado("El contenido no es del tipo que dice la extensión")
    }

    /** Que Android la sepa abrir y que no sea enorme. */
    private fun imagenValida(f: File) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) throw Rechazado("La imagen no se puede abrir")
        if (o.outWidth > LADO_MAXIMO || o.outHeight > LADO_MAXIMO) throw Rechazado("Imagen demasiado grande (máx. ${LADO_MAXIMO}px de lado)")
    }

    private fun reescribir(f: File, cuerpo: (java.io.OutputStream) -> Unit) {
        val tmp = File(f.parentFile, f.name + ".limpio")
        try {
            tmp.outputStream().buffered().use(cuerpo)
            if (!f.delete() || !tmp.renameTo(f)) throw Rechazado("No se pudo guardar limpio")
        } finally { tmp.delete() }
    }

    // ---------- JPEG: fuera APP1..APP15 (EXIF, XMP, ICC…) y comentarios ----------

    private fun jpeg(f: File) {
        val datos = f.readBytes()
        val girada = orientacionExif(datos)
        reescribir(f) { o ->
            o.write(0xFF); o.write(0xD8)
            var i = 2
            while (true) {
                if (i + 4 > datos.size || datos[i] != 0xFF.toByte()) throw Rechazado("JPEG dañado")
                val m = datos[i + 1].toInt() and 0xFF
                if (m == 0xD9) { o.write(datos, i, 2); break }
                val largo = ((datos[i + 2].toInt() and 0xFF) shl 8) or (datos[i + 3].toInt() and 0xFF)
                if (largo < 2 || i + 2 + largo > datos.size) throw Rechazado("JPEG dañado")
                val quitar = m in 0xE1..0xEF && !(m == 0xEE && esAdobe(datos, i + 4)) || m == 0xFE ||
                    (m == 0xE0 && !esJfif(datos, i + 4))
                if (!quitar) o.write(datos, i, 2 + largo)
                i += 2 + largo
                if (m == 0xDA) { o.write(datos, i, datos.size - i); break }   // a partir de aquí, la imagen
            }
        }
        // Una foto de móvil girada por EXIF se gira de verdad (si no, saldría tumbada sin el EXIF).
        if (girada) {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(datos))) { d, _, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            reescribir(f) { o -> bmp.compress(Bitmap.CompressFormat.JPEG, 92, o) }
            bmp.recycle()
        }
    }

    private fun esJfif(d: ByteArray, p: Int) = p + 5 <= d.size && String(d, p, 5, Charsets.ISO_8859_1) == "JFIF\u0000"
    private fun esAdobe(d: ByteArray, p: Int) = p + 5 <= d.size && String(d, p, 5, Charsets.ISO_8859_1) == "Adobe"

    /** ¿Lleva EXIF con una orientación distinta de «normal»? */
    private fun orientacionExif(d: ByteArray): Boolean {
        var i = 2
        while (i + 4 <= d.size && d[i] == 0xFF.toByte()) {
            val m = d[i + 1].toInt() and 0xFF
            val largo = ((d[i + 2].toInt() and 0xFF) shl 8) or (d[i + 3].toInt() and 0xFF)
            if (m == 0xDA || largo < 2) return false
            if (m == 0xE1 && largo > 16 && i + 12 <= d.size && String(d, i + 4, 4, Charsets.ISO_8859_1) == "Exif") {
                val t = i + 10
                val le = d[t] == 'I'.code.toByte()
                fun u16(p: Int) = if (p < 0 || p + 2 > d.size) 0 else if (le) (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8)
                    else ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
                fun u32(p: Int) = if (le) u16(p).toLong() or (u16(p + 2).toLong() shl 16) else (u16(p).toLong() shl 16) or u16(p + 2).toLong()
                val desp = u32(t + 4)
                if (desp > d.size) return false
                val ifd = t + desp.toInt()
                val n = u16(ifd)
                for (k in 0 until n.coerceAtMost(64)) {
                    val e = ifd + 2 + k * 12
                    if (u16(e) == 0x0112) return u16(e + 8) !in 0..1
                }
                return false
            }
            i += 2 + largo
        }
        return false
    }

    // ---------- PNG: solo los trozos de la imagen (y de la animación APNG) ----------

    private val PNG_SI = setOf("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "sBIT", "bKGD", "acTL", "fcTL", "fdAT")

    private fun png(f: File) {
        val d = f.readBytes()
        reescribir(f) { o ->
            o.write(d, 0, 8)
            var i = 8
            while (true) {
                if (i + 12 > d.size) throw Rechazado("PNG dañado")
                val largo = ByteBuffer.wrap(d, i, 4).int
                if (largo < 0 || i + 12L + largo > d.size) throw Rechazado("PNG dañado")
                val nombre = String(d, i + 4, 4, Charsets.ISO_8859_1)
                if (nombre in PNG_SI) o.write(d, i, 12 + largo)               // largo + tipo + datos + CRC
                i += 12 + largo
                if (nombre == "IEND") break
            }
        }
    }

    // ---------- WebP: fuera EXIF, XMP e ICC (y se quitan sus marcas de VP8X) ----------

    private val WEBP_SI = setOf("VP8 ", "VP8L", "VP8X", "ANIM", "ANMF", "ALPH")

    private fun webp(f: File) {
        val d = f.readBytes()
        if (d.size < 20 || String(d, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(d, 8, 4, Charsets.ISO_8859_1) != "WEBP")
            throw Rechazado("El contenido no es WebP")
        val trozos = java.io.ByteArrayOutputStream()
        var i = 12
        while (i + 8 <= d.size) {
            val tipo = String(d, i, 4, Charsets.ISO_8859_1)
            val largo = (d[i + 4].toLong() and 0xFF) or ((d[i + 5].toLong() and 0xFF) shl 8) or
                ((d[i + 6].toLong() and 0xFF) shl 16) or ((d[i + 7].toLong() and 0xFF) shl 24)
            if (i + 8 + largo > d.size) throw Rechazado("WebP dañado")
            val total = 8 + largo + (largo and 1)
            if (tipo in WEBP_SI) {
                val copia = d.copyOfRange(i, minOf(i + total, d.size.toLong()).toInt())
                if (tipo == "VP8X" && copia.size > 8) copia[8] = (copia[8].toInt() and 0x20.inv() and 0x08.inv() and 0x04.inv()).toByte()
                trozos.write(copia)
                if (copia.size.toLong() < total) trozos.write(0)
            }
            i += total.toInt()
        }
        val cuerpo = trozos.toByteArray()
        if (cuerpo.isEmpty()) throw Rechazado("WebP sin imagen")
        reescribir(f) { o ->
            o.write("RIFF".toByteArray())
            val n = cuerpo.size + 4
            o.write(byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte()))
            o.write("WEBP".toByteArray()); o.write(cuerpo)
        }
    }

    // ---------- GIF: fuera comentarios y extensiones de aplicación (menos la del bucle) ----------

    private fun gif(f: File) {
        val d = f.readBytes()
        if (d.size < 13 || String(d, 0, 6, Charsets.ISO_8859_1).let { it != "GIF87a" && it != "GIF89a" }) throw Rechazado("El contenido no es GIF")
        fun subbloques(desde: Int): Int {
            var p = desde
            while (true) {
                if (p >= d.size) throw Rechazado("GIF dañado")
                val n = d[p].toInt() and 0xFF; p += 1 + n
                if (n == 0) return p
            }
        }
        reescribir(f) { o ->
            var i = 13
            val paleta = d[10].toInt() and 0xFF
            if (paleta and 0x80 != 0) i += 3 * (1 shl ((paleta and 7) + 1))
            if (i > d.size) throw Rechazado("GIF dañado")
            o.write(d, 0, i)
            while (true) {
                if (i >= d.size) throw Rechazado("GIF dañado")
                when (d[i].toInt() and 0xFF) {
                    0x3B -> { o.write(0x3B); break }
                    0x2C -> {
                        if (i + 10 > d.size) throw Rechazado("GIF dañado")
                        var p = i + 10
                        val loc = d[i + 9].toInt() and 0xFF
                        if (loc and 0x80 != 0) p += 3 * (1 shl ((loc and 7) + 1))
                        val fin = subbloques(p + 1)
                        o.write(d, i, fin - i); i = fin
                    }
                    0x21 -> {
                        if (i + 2 > d.size) throw Rechazado("GIF dañado")
                        val etiqueta = d[i + 1].toInt() and 0xFF
                        val fin = subbloques(i + 2)
                        val bucle = etiqueta == 0xFF && i + 14 <= d.size &&
                            String(d, i + 3, 11, Charsets.ISO_8859_1).let { it == "NETSCAPE2.0" || it == "ANIMEXTS1.0" }
                        if (etiqueta == 0xF9 || etiqueta == 0x01 || bucle) o.write(d, i, fin - i)
                        i = fin
                    }
                    else -> throw Rechazado("GIF dañado")
                }
            }
        }
    }

    // ---------- MP4 / MOV: las cajas de metadatos se convierten en «free» y se vacían ----------

    private val CONTENEDORES = setOf("moov", "trak", "mdia", "minf", "dinf", "stbl", "edts", "mvex", "moof", "traf")
    private val METADATOS = setOf("udta", "meta", "uuid", "XMP_")

    private fun isoBmff(f: File) {
        val primera = String(cabecera(f, 8), 4, 4, Charsets.ISO_8859_1)
        if (primera !in setOf("ftyp", "moov", "mdat", "wide", "free", "skip")) throw Rechazado("El contenido no es MP4/MOV")
        RandomAccessFile(f, "rw").use { r ->
            var vistas = 0
            var hayMoov = false
            fun recorrer(desde: Long, hasta: Long) {
                var p = desde
                while (p + 8 <= hasta) {
                    if (++vistas > 100_000) throw Rechazado("Vídeo con demasiadas cajas")
                    r.seek(p)
                    var largo = r.readInt().toLong() and 0xFFFFFFFFL
                    val tipo = ByteArray(4).also { r.readFully(it) }.toString(Charsets.ISO_8859_1)
                    var cab = 8L
                    if (largo == 1L) { largo = r.readLong(); cab = 16 }
                    if (largo == 0L) largo = hasta - p
                    if (largo < cab || p + largo > hasta) throw Rechazado("Vídeo dañado")
                    if (tipo == "moov") hayMoov = true
                    when (tipo) {
                        in METADATOS -> vaciar(r, p, largo, cab)
                        in CONTENEDORES -> recorrer(p + cab, p + largo)
                    }
                    p += largo
                }
            }
            recorrer(0, r.length())
            if (!hayMoov) throw Rechazado("Vídeo sin índice (moov)")
        }
    }

    /** La caja pasa a llamarse «free» (relleno que todo reproductor salta) y su contenido se pone a cero. */
    private fun vaciar(r: RandomAccessFile, p: Long, largo: Long, cab: Long) {
        r.seek(p + 4); r.write("free".toByteArray())
        r.seek(p + cab)
        val ceros = ByteArray(64 * 1024)
        var falta = largo - cab
        while (falta > 0) { val n = minOf(falta, ceros.size.toLong()).toInt(); r.write(ceros, 0, n); falta -= n }
    }

    // ---------- WebM / MKV: «Tags» y «Attachments» pasan a ser relleno (Void) ----------

    private fun matroska(f: File) {
        RandomAccessFile(f, "rw").use { r ->
            fun id(): Long {
                val b = r.read(); if (b <= 0) throw Rechazado("Vídeo dañado")
                val n = Integer.numberOfLeadingZeros(b) - 24 + 1
                if (n !in 1..4) throw Rechazado("Vídeo dañado")
                var v = b.toLong(); repeat(n - 1) { v = (v shl 8) or (r.read().toLong() and 0xFF) }
                return v
            }
            fun tam(): Long {
                val b = r.read(); if (b <= 0) throw Rechazado("Vídeo dañado")
                val n = Integer.numberOfLeadingZeros(b) - 24 + 1
                if (n !in 1..8) throw Rechazado("Vídeo dañado")
                var v = (b and (0xFF shr n)).toLong(); var todoUnos = v == (0xFFL shr n)
                repeat(n - 1) { val x = r.read().toLong() and 0xFF; v = (v shl 8) or x; if (x != 0xFFL) todoUnos = false }
                return if (todoUnos) -1L else v
            }
            r.seek(0)
            if (id() != 0x1A45DFA3L) throw Rechazado("El contenido no es WebM/MKV")
            val cabTam = tam(); if (cabTam < 0) throw Rechazado("Vídeo dañado")
            r.seek(r.filePointer + cabTam)
            if (id() != 0x18538067L) throw Rechazado("Vídeo sin segmento")
            val segTam = tam()
            val fin = if (segTam < 0) r.length() else minOf(r.length(), r.filePointer + segTam)
            var vistas = 0
            while (r.filePointer < fin) {
                if (++vistas > 1_000_000) break
                val inicio = r.filePointer
                val e = id()
                val t = tam()
                if (t < 0) break                          // trozo de tamaño desconocido (emisión en directo): no se sigue
                val datos = r.filePointer
                if (datos + t > fin) throw Rechazado("Vídeo dañado")
                if (e == 0x1254C367L || e == 0x1941A469L) {   // Tags y Attachments
                    val total = datos + t - inicio
                    if (total < 9) throw Rechazado("Vídeo dañado")
                    r.seek(inicio); r.write(0xEC)
                    val relleno = total - 9
                    r.write(0x01); for (k in 6 downTo 0) r.write(((relleno shr (8 * k)) and 0xFF).toInt())
                    val ceros = ByteArray(64 * 1024); var falta = relleno
                    while (falta > 0) { val n = minOf(falta, ceros.size.toLong()).toInt(); r.write(ceros, 0, n); falta -= n }
                }
                r.seek(datos + t)
            }
        }
    }

    // ---------- HTML: texto de verdad (UTF-8, sin binario) y de tamaño razonable ----------

    private fun html(f: File) {
        if (f.length() > HTML_MAXIMO) throw Rechazado("HTML demasiado grande (máx. 5 MB)")
        val b = f.readBytes()
        if (b.any { it == 0.toByte() }) throw Rechazado("El HTML lleva datos binarios")
        try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b))
        } catch (e: Exception) { throw Rechazado("El HTML no es texto UTF-8") }
    }
}
