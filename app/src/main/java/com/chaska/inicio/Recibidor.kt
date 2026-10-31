package com.chaska.inicio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * Recibe fondos desde el navegador del móvil o del ordenador, en la misma red.
 * Solo escucha mientras está abierta la pantalla «Recibir fondos» y solo atiende a direcciones de la red de casa.
 * Cada vez que se abre, la TV saca una clave nueva de 6 cifras (aleatoria segura) que la página pide una vez;
 * acertarla da a ese navegador una sesión (cookie aleatoria de 128 bits) que muere al cerrar la pantalla.
 * Frenos: 5 claves falsas desde un aparato lo dejan fuera; 20 en total cierran la recepción.
 * Cada archivo se comprueba y se limpia (Saneador) antes de guardarse en files/fondos; las imágenes fijas
 * se guardan siempre en WebP. Un .zip con index.html se descomprime en su carpeta, con topes y solo tipos permitidos.
 */
class Recibidor(private val ctx: Context, private val actual: () -> String, private val alCambiar: (Evento) -> Unit) {
    sealed class Evento {
        class Recibido(val nombre: String) : Evento()
        class Poner(val clave: String) : Evento()
        class Borrado(val clave: String) : Evento()
        class Renombrado(val antes: String, val ahora: String) : Evento()
        /** Demasiadas claves equivocadas: se ha cerrado la recepción. */
        class Bloqueado : Evento()
    }

    private val azar = SecureRandom()
    /** La clave que enseña la TV; nueva cada vez que se abre «Recibir fondos». */
    val claveTv = String.format("%06d", azar.nextInt(1_000_000))
    private var servidor: ServerSocket? = null
    private val principal = Handler(Looper.getMainLooper())
    private val huecos = java.util.concurrent.Semaphore(4)               // conexiones a la vez
    private val subiendo = java.util.concurrent.atomic.AtomicBoolean()    // una subida cada vez
    private val fallos = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val fallosTotales = java.util.concurrent.atomic.AtomicInteger()
    private val sesiones = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val version = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
    private val icono: ByteArray by lazy { iconoPng() }

    /** Dirección para escribir en el navegador, o null si la TV no tiene red. */
    val direccion: String? get() = ip()?.let { "http://$it:$PUERTO/" }

    fun empezar() {
        if (servidor != null) return
        val s = try { ServerSocket(PUERTO) } catch (e: Exception) { return }
        servidor = s
        thread(name = "recibidor", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                if (!huecos.tryAcquire()) { try { c.close() } catch (_: Exception) {}; continue }
                thread(isDaemon = true) { try { c.use { atender(it) } } catch (_: Exception) {} finally { huecos.release() } }
            }
        }
    }

    fun parar() {
        try { servidor?.close() } catch (_: Exception) {}
        servidor = null
        sesiones.clear()
    }

    // ---------- HTTP mínimo: una petición por conexión ----------

    private fun atender(c: Socket) {
        // Solo la red de casa (y la propia TV); quien ya ha fallado 5 veces la clave, ni se le contesta.
        val quien = c.inetAddress
        if (!(quien.isSiteLocalAddress || quien.isLoopbackAddress || quien.isLinkLocalAddress)) return
        val ip = quien.hostAddress ?: return
        if ((fallos[ip] ?: 0) >= 5) return
        c.soTimeout = 15_000
        val ent = BufferedInputStream(c.getInputStream(), 64 * 1024)
        val sal = c.getOutputStream()
        val primera = linea(ent) ?: return
        val partes = primera.split(' ')
        if (partes.size < 2) return
        val metodo = partes[0]
        var largo = -1L
        var cookie = ""
        var propia = false                     // la pide nuestra página (cabecera que otra web no puede poner sin permiso)
        var cabeceras = 0
        while (true) {
            val h = linea(ent) ?: return
            if (h.isEmpty()) break
            if (++cabeceras > 50) return
            val nombre = h.substringBefore(':').trim().lowercase()
            val valor = h.substringAfter(':', "").trim()
            when (nombre) {
                "content-length" -> largo = valor.toLongOrNull() ?: -1L
                "cookie" -> cookie = valor
                "x-stark" -> propia = valor == "1"
            }
        }
        val ruta = partes[1].substringBefore('?')
        val q = partes[1].substringAfter('?', "").split('&').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) kv[0] to URLDecoder.decode(kv[1], "UTF-8") else null
        }.toMap()

        // Lo único que se ve sin clave: la página (que la pide) y el icono.
        when ("$metodo $ruta") {
            "GET /" -> return responder(sal, 200, "text/html; charset=utf-8", PAGINA.replace("@VERSION@", version.filter { it.isLetterOrDigit() || it == '.' }))
            "GET /icono.png" -> return responderBytes(sal, 200, "image/png", icono)
            "POST /entrar" -> return entrar(ent, sal, ip, largo, propia)
        }
        val token = cookie.split(';').map { it.trim() }.firstOrNull { it.startsWith("stark=") }?.removePrefix("stark=")
        if (token == null || token !in sesiones || (metodo == "POST" && !propia))
            return responder(sal, 401, "text/plain; charset=utf-8", "Escribe la clave que sale en la TV.")
        when ("$metodo $ruta") {
            "GET /lista" -> responder(sal, 200, "application/json; charset=utf-8", lista())
            "POST /subir" -> subir(ent, sal, q["nombre"], largo)
            "POST /poner" -> conNombre(sal, q["nombre"]) { f -> avisar(Evento.Poner(clave(f))); "ok" }
            "POST /borrar" -> conNombre(sal, q["nombre"]) { f ->
                val k = clave(f); f.deleteRecursively(); avisar(Evento.Borrado(k)); "ok"
            }
            "POST /renombrar" -> renombrar(sal, q["nombre"], q["nuevo"])
            else -> responder(sal, 404, "text/plain", "No existe")
        }
    }

    /** Comprueba la clave de la TV; si es buena, da una sesión para este navegador. */
    private fun entrar(ent: InputStream, sal: OutputStream, ip: String, largo: Long, propia: Boolean) {
        if (!propia || largo !in 1..32) return responder(sal, 400, "text/plain; charset=utf-8", "Petición no válida")
        val escrita = ByteArray(largo.toInt()).also { leerTodo(ent, it) }.toString(Charsets.UTF_8).filter { it.isDigit() }
        if (!java.security.MessageDigest.isEqual(escrita.toByteArray(), claveTv.toByteArray())) {
            fallos.merge(ip, 1, Int::plus)
            if (fallosTotales.incrementAndGet() >= 20) { parar(); avisar(Evento.Bloqueado()) }
            val quedan = 5 - (fallos[ip] ?: 0)
            return responder(sal, 403, "text/plain; charset=utf-8",
                if (quedan > 0) "Clave incorrecta. Te quedan $quedan intentos." else "Clave incorrecta. Este aparato queda bloqueado hasta que cierres y abras «Recibir fondos» en la TV.")
        }
        val token = ByteArray(16).also { azar.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        sesiones += token
        responder(sal, 200, "text/plain", "ok", "Set-Cookie: stark=$token; HttpOnly; SameSite=Strict; Path=/")
    }

    private fun leerTodo(ent: InputStream, b: ByteArray) {
        var n = 0
        while (n < b.size) { val r = ent.read(b, n, b.size - n); if (r < 0) throw IllegalStateException("cortado"); n += r }
    }

    private fun carpeta() = File(ctx.filesDir, "fondos").apply { mkdirs() }

    /** Lo que se guarda en Ajustes para un fondo recibido: el archivo o el index.html de su carpeta. */
    private fun clave(f: File) = if (f.isDirectory) File(f, "index.html").absolutePath else f.absolutePath

    private fun conNombre(sal: OutputStream, nombre: String?, accion: (File) -> String) {
        val f = nombre?.let { limpio(it) }?.let { File(carpeta(), it) }
        if (f == null || !f.exists()) return responder(sal, 404, "text/plain; charset=utf-8", "No está")
        responder(sal, 200, "text/plain", accion(f))
    }

    /** Cambia el nombre; un archivo conserva su extensión (si no, dejaría de saberse qué es). */
    private fun renombrar(sal: OutputStream, nombre: String?, nuevo: String?) {
        val f = nombre?.let { limpio(it) }?.let { File(carpeta(), it) }
        if (f == null || !f.exists()) return responder(sal, 404, "text/plain; charset=utf-8", "No está")
        var n = nuevo?.let { limpio(it) } ?: return responder(sal, 400, "text/plain; charset=utf-8", "Nombre no válido")
        if (f.isFile) {
            val ext = f.name.substringAfterLast('.', "")
            if (!n.endsWith(".$ext", ignoreCase = true)) n = "${n.substringBeforeLast('.').ifEmpty { n }}.$ext"
        }
        val destino = File(carpeta(), n)
        if (destino.exists()) return responder(sal, 409, "text/plain; charset=utf-8", "Ya hay uno con ese nombre")
        val antes = clave(f)
        if (!f.renameTo(destino)) return responder(sal, 400, "text/plain; charset=utf-8", "No se pudo renombrar")
        avisar(Evento.Renombrado(antes, clave(destino)))
        responder(sal, 200, "text/plain", destino.name)
    }

    private fun subir(ent: InputStream, sal: OutputStream, nombre: String?, largo: Long) {
        if (!subiendo.compareAndSet(false, true)) return responder(sal, 429, "text/plain; charset=utf-8", "Ya se está recibiendo otro archivo")
        try { subirUno(ent, sal, nombre, largo) } finally { subiendo.set(false) }
    }

    private fun subirUno(ent: InputStream, sal: OutputStream, nombre: String?, largo: Long) {
        val n = nombre?.let { limpio(it) }
        val ext = n?.substringAfterLast('.', "")?.lowercase()
        if (n == null || ext == null || ext !in ADMITIDOS) return responder(sal, 400, "text/plain; charset=utf-8", "Tipo no admitido")
        if (largo <= 0 || largo > MAXIMO) return responder(sal, 413, "text/plain; charset=utf-8", "Demasiado grande (máximo 1 GB)")
        if (carpeta().usableSpace < largo + 50L * 1024 * 1024) return responder(sal, 507, "text/plain; charset=utf-8", "No hay sitio en la TV")
        val tmp = File(carpeta(), ".subiendo-$n")
        try {
            tmp.outputStream().use { o -> copiar(ent, o, largo) }
            // Se comprueba que es lo que dice ser y se le quitan los metadatos; si no se puede, no se guarda.
            // Las imágenes fijas pasan a WebP (y a 2560 px como mucho): ocupan mucho menos.
            val final = if (ext == "zip") descomprimir(tmp, n.substringBeforeLast('.')) else {
                Saneador.limpiar(tmp, ext)
                val nombreFinal = if (Saneador.aWebp(tmp, ext)) n.substringBeforeLast('.') + ".webp" else n
                File(carpeta(), nombreFinal).also { it.delete(); if (!tmp.renameTo(it)) throw IllegalStateException("No se pudo guardar") }
            }
            tmp.delete()
            avisar(Evento.Recibido(final.name))
            responder(sal, 200, "text/plain", final.name)
        } catch (e: Exception) {
            tmp.delete()
            responder(sal, 400, "text/plain; charset=utf-8", e.message ?: "Error al guardar")
        }
    }

    /**
     * Un HTML5 con sus imágenes: se descomprime en su carpeta, que debe tener un index.html.
     * Solo tipos de la lista ZIP_SI (lo demás se descarta), como mucho 500 archivos y 300 MB en total,
     * y cada imagen, vídeo o HTML pasa por el Saneador. Si algo falla, no queda nada.
     * Aquí las imágenes no se convierten: el HTML las llama por su nombre.
     */
    private fun descomprimir(zip: File, nombre: String): File {
        val dest = File(carpeta(), nombre).apply { deleteRecursively(); mkdirs() }
        val raiz = dest.canonicalPath + File.separator
        try {
            var archivos = 0
            var total = 0L
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory || e.name.contains('\\')) continue
                    val ext = e.name.substringAfterLast('.', "").lowercase()
                    if (ext !in ZIP_SI) continue
                    val f = File(dest, e.name)
                    if (!f.canonicalPath.startsWith(raiz)) continue          // nada fuera de su carpeta
                    if (++archivos > 500) throw Saneador.Rechazado("El .zip lleva demasiados archivos")
                    f.parentFile?.mkdirs()
                    f.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = z.read(buf); if (n < 0) break
                            total += n
                            if (total > ZIP_MAXIMO) throw Saneador.Rechazado("El .zip descomprimido pasa de 300 MB")
                            o.write(buf, 0, n)
                        }
                    }
                    when (ext) {
                        in TEXTO -> if (f.readBytes().any { it == 0.toByte() }) throw Saneador.Rechazado("${e.name} no es texto")
                        in FUENTES -> {}
                        else -> Saneador.limpiar(f, ext)
                    }
                }
            }
        } catch (x: Exception) { dest.deleteRecursively(); throw x }
        // Si todo venía dentro de una sola carpeta, se sube un nivel para que index.html quede arriba.
        val hijos = dest.listFiles().orEmpty()
        if (!File(dest, "index.html").exists() && hijos.size == 1 && File(hijos[0], "index.html").exists()) {
            hijos[0].listFiles()?.forEach { it.renameTo(File(dest, it.name)) }
            hijos[0].deleteRecursively()
        }
        if (!File(dest, "index.html").exists()) { dest.deleteRecursively(); throw IllegalArgumentException("El .zip no lleva index.html") }
        return dest
    }

    private fun lista(): String {
        val puesto = actual()
        return carpeta().listFiles().orEmpty()
            .filter { !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
            .joinToString(",", "[", "]") { f ->
                val tam = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()
                "{\"nombre\":\"${json(f.name)}\",\"tam\":$tam,\"puesto\":${clave(f) == puesto}}"
            }
    }

    /** El icono de la app en PNG, para que la página enseñe que estás en Stark TV. */
    private fun iconoPng(): ByteArray = try {
        val d = ctx.packageManager.getApplicationIcon(ctx.packageName)
        val lado = 192
        val b = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, lado, lado); d.draw(Canvas(b))
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it); b.recycle() }.toByteArray()
    } catch (e: Exception) { ByteArray(0) }

    private fun avisar(e: Evento) = principal.post { alCambiar(e) }

    private fun copiar(ent: InputStream, sal: OutputStream, largo: Long) {
        val buf = ByteArray(64 * 1024)
        var falta = largo
        while (falta > 0) {
            val n = ent.read(buf, 0, minOf(buf.size.toLong(), falta).toInt())
            if (n < 0) throw IllegalStateException("Subida cortada")
            sal.write(buf, 0, n); falta -= n
        }
    }

    private fun linea(ent: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ent.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun responder(sal: OutputStream, codigo: Int, tipo: String, cuerpo: String, extra: String? = null) =
        responderBytes(sal, codigo, tipo, cuerpo.toByteArray(), extra)

    private fun responderBytes(sal: OutputStream, codigo: Int, tipo: String, b: ByteArray, extra: String? = null) {
        val cab = buildString {
            append("HTTP/1.1 $codigo X\r\nContent-Type: $tipo\r\nContent-Length: ${b.size}\r\nConnection: close\r\nCache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\n")
            append("Content-Security-Policy: default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'self' blob:; frame-ancestors 'none'; form-action 'none'\r\n")
            if (extra != null) append(extra).append("\r\n")
            append("\r\n")
        }
        try { sal.write(cab.toByteArray()); sal.write(b); sal.flush() } catch (_: Exception) {}
    }

    companion object {
        const val PUERTO = 8765
        private const val MAXIMO = 1024L * 1024 * 1024
        private val ADMITIDOS = setOf("webp", "jpg", "jpeg", "png", "gif", "mp4", "webm", "mkv", "mov", "html", "htm", "zip")
        /** Lo que se saca de un .zip de HTML5; lo demás se descarta. */
        private val TEXTO = setOf("js", "css", "json", "txt", "svg")
        private val FUENTES = setOf("woff", "woff2", "ttf", "otf")
        private val ZIP_SI = setOf("html", "htm", "webp", "png", "jpg", "jpeg", "gif", "mp4", "webm") + TEXTO + FUENTES
        private const val ZIP_MAXIMO = 300L * 1024 * 1024

        /** Solo el nombre, sin rutas ni caracteres raros. */
        fun limpio(n: String): String? {
            val s = n.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().trimStart('.')
            return s.ifEmpty { null }?.take(80)
        }

        private fun json(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

        fun ip(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("eth")) 0 else if (it.name.startsWith("wlan")) 1 else 2 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        } catch (e: Exception) { null }

        private val PAGINA = """<!doctype html>
<html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Stark TV · Fondos</title>
<link rel="icon" href="/icono.png">
<style>
  :root { color-scheme: dark; }
  body { margin: 0; font: 16px/1.45 system-ui, sans-serif; background: #0d1018; color: #e8ecf5; }
  main { max-width: 600px; margin: 0 auto; padding: 20px 16px 48px; }
  header { display: flex; align-items: center; gap: 14px; margin-bottom: 18px; }
  header img { width: 56px; height: 56px; border-radius: 14px; }
  header b { display: block; font-size: 22px; }
  header small { color: #8d96ab; }
  p { color: #a9b2c6; margin: 0 0 18px; }
  label.subir { display: block; border: 2px dashed #46506a; border-radius: 14px; padding: 26px 16px; text-align: center; cursor: pointer; }
  label.subir.encima { border-color: #f2a93b; background: #f2a93b14; }
  label.subir b { display: block; font-size: 18px; margin-bottom: 4px; }
  input[type=file] { display: none; }
  input[type=text], input[inputmode] { font: inherit; background: #171c28; color: #e8ecf5; border: 1px solid #353d52; border-radius: 10px; padding: 9px 11px; min-width: 0; }
  .clave { font-size: 26px; letter-spacing: .25em; text-align: center; width: 9em; }
  .barra { height: 8px; background: #252b3b; border-radius: 4px; overflow: hidden; margin: 10px 0 2px; }
  .barra i { display: block; height: 100%; width: 0; background: #f2a93b; }
  .estado { font-size: 14px; color: #a9b2c6; min-height: 20px; }
  h2 { font-size: 16px; margin: 26px 0 8px; }
  ul { list-style: none; padding: 0; margin: 0; }
  li { display: flex; align-items: center; gap: 8px; padding: 10px 0; border-bottom: 1px solid #222838; }
  li span { flex: 1; overflow-wrap: anywhere; }
  li input { flex: 1; }
  li small { color: #7d869b; display: block; }
  button { font: inherit; border: 0; border-radius: 10px; padding: 8px 12px; cursor: pointer; }
  .poner, .enviar { background: #f2a93b; color: #1b1203; }
  .borrar, .renombrar, .quitar { background: #2a3044; color: #e8ecf5; }
  .puesto { color: #f2a93b; font-size: 13px; font-weight: 600; }
  .oculto { display: none; }
  @media (max-width: 480px) { li { flex-wrap: wrap; } li span { flex-basis: 100%; } }
</style></head><body><main>
<header><img src="/icono.png" alt=""><div><b>Stark TV</b><small>Versión @VERSION@ · Recibir fondos</small></div></header>

<section id="entrada" class="oculto">
  <p>Escribe la clave de 6 cifras que sale ahora en la pantalla de la TV.</p>
  <form id="formClave"><input class="clave" id="clave" inputmode="numeric" autocomplete="one-time-code" maxlength="6" pattern="[0-9]{6}" required>
  <button class="enviar">Entrar</button></form>
  <div class="estado" id="estadoClave"></div>
</section>

<section id="panel" class="oculto">
  <p>Imágenes (webp, jpg, png, gif), vídeos (mp4, webm, mkv, mov) o HTML5 (un .zip con su index.html). La TV comprueba cada archivo, le quita los datos ocultos (ubicación, cámara, fecha…) y guarda las imágenes en WebP para que ocupen menos.</p>
  <label class="subir" id="zona"><b>Elegir archivos</b>o arrastrarlos aquí
    <input type="file" id="archivos" multiple accept=".webp,.jpg,.jpeg,.png,.gif,.mp4,.webm,.mkv,.mov,.html,.htm,.zip"></label>
  <ul id="pendientes"></ul>
  <div id="botonesEnvio" class="oculto" style="margin-top:10px"><button class="enviar" id="enviar">Enviar a la TV</button> <button class="quitar" id="cancelar">Cancelar</button></div>
  <div class="barra"><i id="progreso"></i></div>
  <div class="estado" id="estado"></div>
  <h2>En la TV</h2>
  <ul id="lista"></ul>
</section>
</main>
<script>
const est = document.getElementById('estado'), prog = document.getElementById('progreso'), zona = document.getElementById('zona');
let pendientes = [];
function mb(n) { return n > 1048576 ? (n / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(n / 1024)) + ' KB'; }
function pedir(metodo, url, cuerpo, alAvanzar) {
  return new Promise(function (ok, mal) {
    const x = new XMLHttpRequest(); x.open(metodo, url); x.setRequestHeader('X-Stark', '1');
    if (alAvanzar) x.upload.onprogress = function (e) { if (e.lengthComputable) alAvanzar(e.loaded / e.total); };
    x.onload = function () {
      if (x.status === 401) { mostrar(false); mal('Hace falta la clave de la TV'); return; }
      x.status === 200 ? ok(x.responseText) : mal(x.responseText || ('Error ' + x.status));
    };
    x.onerror = function () { mal('Sin conexión con la TV (¿está abierta «Recibir fondos»?)'); };
    x.send(cuerpo);
  });
}
function mostrar(dentro) {
  document.getElementById('entrada').classList.toggle('oculto', dentro);
  document.getElementById('panel').classList.toggle('oculto', !dentro);
  if (!dentro) document.getElementById('clave').focus();
}
document.getElementById('formClave').onsubmit = async function (e) {
  e.preventDefault();
  const c = document.getElementById('clave'), ec = document.getElementById('estadoClave');
  try { await pedir('POST', '/entrar', c.value.replace(/\D/g, '')); ec.textContent = ''; mostrar(true); cargar(); }
  catch (x) { ec.textContent = x; c.value = ''; }
};
function extension(n) { const i = n.lastIndexOf('.'); return i > 0 ? n.slice(i) : ''; }
function base(n) { const i = n.lastIndexOf('.'); return i > 0 ? n.slice(0, i) : n; }
function elegir(archivos) {
  for (const f of archivos) pendientes.push({ f: f, nombre: base(f.name) });
  pintarPendientes();
}
function pintarPendientes() {
  const ul = document.getElementById('pendientes'); ul.innerHTML = '';
  pendientes.forEach(function (p, i) {
    const li = document.createElement('li');
    const inp = document.createElement('input'); inp.type = 'text'; inp.value = p.nombre; inp.maxLength = 70;
    inp.oninput = function () { p.nombre = inp.value; };
    const ext = document.createElement('small'); ext.textContent = extension(p.f.name) + ' · ' + mb(p.f.size);
    const q = document.createElement('button'); q.className = 'quitar'; q.textContent = 'Quitar';
    q.onclick = function () { pendientes.splice(i, 1); pintarPendientes(); };
    li.append(inp, ext, q); ul.appendChild(li);
  });
  document.getElementById('botonesEnvio').classList.toggle('oculto', !pendientes.length);
}
document.getElementById('cancelar').onclick = function () { pendientes = []; pintarPendientes(); };
document.getElementById('enviar').onclick = async function () {
  const lista = pendientes.slice(); pendientes = []; pintarPendientes();
  for (let i = 0; i < lista.length; i++) {
    const p = lista[i], nombre = (p.nombre.trim() || base(p.f.name)) + extension(p.f.name);
    est.textContent = 'Enviando ' + nombre + ' (' + (i + 1) + ' de ' + lista.length + ')…';
    try {
      const final = await pedir('POST', '/subir?nombre=' + encodeURIComponent(nombre), p.f, function (x) { prog.style.width = (x * 100) + '%'; });
      est.textContent = 'Recibido en la TV: ' + final;
    } catch (e) { est.textContent = nombre + ': ' + e; }
    prog.style.width = '0';
    await cargar();
  }
};
async function cargar() {
  const l = JSON.parse(await pedir('GET', '/lista'));
  mostrar(true);
  const ul = document.getElementById('lista'); ul.innerHTML = '';
  if (!l.length) { ul.innerHTML = '<li><span>Todavía no hay fondos enviados.</span></li>'; return; }
  for (const f of l) {
    const li = document.createElement('li'), s = document.createElement('span');
    s.textContent = f.nombre; const sm = document.createElement('small'); sm.textContent = mb(f.tam); s.appendChild(sm);
    if (f.puesto) { const pu = document.createElement('small'); pu.className = 'puesto'; pu.textContent = 'Puesto de fondo ahora'; s.appendChild(pu); }
    const p = document.createElement('button'); p.className = 'poner'; p.textContent = 'Poner de fondo';
    p.onclick = async function () { await pedir('POST', '/poner?nombre=' + encodeURIComponent(f.nombre)); est.textContent = 'Puesto de fondo: ' + f.nombre; setTimeout(cargar, 300); };
    const r = document.createElement('button'); r.className = 'renombrar'; r.textContent = 'Renombrar';
    r.onclick = async function () {
      const n = prompt('Nuevo nombre para ' + f.nombre, base(f.nombre)); if (!n || n === base(f.nombre)) return;
      try { const final = await pedir('POST', '/renombrar?nombre=' + encodeURIComponent(f.nombre) + '&nuevo=' + encodeURIComponent(n)); est.textContent = 'Renombrado: ' + final; }
      catch (e) { est.textContent = e; }
      cargar();
    };
    const b = document.createElement('button'); b.className = 'borrar'; b.textContent = 'Borrar';
    b.onclick = async function () { if (confirm('¿Borrar ' + f.nombre + ' de la TV?')) { await pedir('POST', '/borrar?nombre=' + encodeURIComponent(f.nombre)); cargar(); } };
    li.append(s, p, r, b); ul.appendChild(li);
  }
}
document.getElementById('archivos').onchange = function (e) { elegir(e.target.files); e.target.value = ''; };
zona.ondragover = function (e) { e.preventDefault(); zona.classList.add('encima'); };
zona.ondragleave = function () { zona.classList.remove('encima'); };
zona.ondrop = function (e) { e.preventDefault(); zona.classList.remove('encima'); elegir(e.dataTransfer.files); };
cargar().catch(function () { mostrar(false); });
</script></body></html>"""
    }
}
