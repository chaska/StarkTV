package com.chaska.inicio

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * Recibe fondos desde el navegador del móvil o del ordenador, en la misma red.
 * Solo escucha mientras está abierta la pantalla «Recibir fondos» y solo atiende a quien
 * lleva en la dirección el número de 4 cifras que sale en la TV. Guarda en files/fondos
 * (no hace falta permiso de almacenamiento); un .zip con index.html se descomprime en su carpeta.
 */
class Recibidor(private val ctx: Context, private val alCambiar: (Evento) -> Unit) {
    sealed class Evento {
        class Recibido(val nombre: String) : Evento()
        class Poner(val clave: String) : Evento()
        class Borrado(val clave: String) : Evento()
    }

    val codigo = (1000..9999).random().toString()
    private var servidor: ServerSocket? = null
    private val principal = Handler(Looper.getMainLooper())

    /** Dirección para escribir en el navegador, o null si la TV no tiene red. */
    val direccion: String? get() = ip()?.let { "http://$it:$PUERTO/$codigo/" }

    fun empezar() {
        if (servidor != null) return
        val s = try { ServerSocket(PUERTO) } catch (e: Exception) { return }
        servidor = s
        thread(name = "recibidor", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { c.use { atender(it) } }
            }
        }
    }

    fun parar() {
        try { servidor?.close() } catch (_: Exception) {}
        servidor = null
    }

    // ---------- HTTP mínimo: una petición por conexión ----------

    private fun atender(c: Socket) {
        c.soTimeout = 30_000
        val ent = BufferedInputStream(c.getInputStream(), 64 * 1024)
        val sal = c.getOutputStream()
        val primera = linea(ent) ?: return
        val partes = primera.split(' ')
        if (partes.size < 2) return
        val metodo = partes[0]
        var largo = -1L
        while (true) {
            val h = linea(ent) ?: return
            if (h.isEmpty()) break
            if (h.startsWith("content-length:", ignoreCase = true)) largo = h.substringAfter(':').trim().toLongOrNull() ?: -1L
        }
        val ruta = partes[1].substringBefore('?')
        val q = partes[1].substringAfter('?', "").split('&').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) kv[0] to URLDecoder.decode(kv[1], "UTF-8") else null
        }.toMap()
        val base = "/$codigo"
        if (ruta == base) return responder(sal, 302, "text/plain", "", "Location: $base/")
        if (!ruta.startsWith("$base/")) return responder(sal, 403, "text/plain; charset=utf-8", "Falta el número que sale en la TV.")
        when ("$metodo ${ruta.removePrefix(base)}") {
            "GET /" -> responder(sal, 200, "text/html; charset=utf-8", PAGINA)
            "GET /lista" -> responder(sal, 200, "application/json; charset=utf-8", lista())
            "POST /subir" -> subir(ent, sal, q["nombre"], largo)
            "POST /poner" -> conNombre(sal, q["nombre"]) { f -> avisar(Evento.Poner(clave(f))); "ok" }
            "POST /borrar" -> conNombre(sal, q["nombre"]) { f ->
                val k = clave(f); f.deleteRecursively(); avisar(Evento.Borrado(k)); "ok"
            }
            else -> responder(sal, 404, "text/plain", "No existe")
        }
    }

    private fun carpeta() = File(ctx.filesDir, "fondos").apply { mkdirs() }

    /** Lo que se guarda en Ajustes para un fondo recibido: el archivo o el index.html de su carpeta. */
    private fun clave(f: File) = if (f.isDirectory) File(f, "index.html").absolutePath else f.absolutePath

    private fun conNombre(sal: OutputStream, nombre: String?, accion: (File) -> String) {
        val f = nombre?.let { limpio(it) }?.let { File(carpeta(), it) }
        if (f == null || !f.exists()) return responder(sal, 404, "text/plain; charset=utf-8", "No está")
        responder(sal, 200, "text/plain", accion(f))
    }

    private fun subir(ent: InputStream, sal: OutputStream, nombre: String?, largo: Long) {
        val n = nombre?.let { limpio(it) }
        val ext = n?.substringAfterLast('.', "")?.lowercase()
        if (n == null || ext !in ADMITIDOS) return responder(sal, 400, "text/plain; charset=utf-8", "Tipo no admitido")
        if (largo <= 0 || largo > MAXIMO) return responder(sal, 413, "text/plain; charset=utf-8", "Demasiado grande (máximo 1 GB)")
        if (carpeta().usableSpace < largo + 50L * 1024 * 1024) return responder(sal, 507, "text/plain; charset=utf-8", "No hay sitio en la TV")
        val tmp = File(carpeta(), ".subiendo-$n")
        try {
            tmp.outputStream().use { o -> copiar(ent, o, largo) }
            val final = if (ext == "zip") descomprimir(tmp, n.substringBeforeLast('.')) else File(carpeta(), n).also { it.delete(); tmp.renameTo(it) }
            tmp.delete()
            avisar(Evento.Recibido(final.name))
            responder(sal, 200, "text/plain", final.name)
        } catch (e: Exception) {
            tmp.delete()
            responder(sal, 400, "text/plain; charset=utf-8", e.message ?: "Error al guardar")
        }
    }

    /** Un HTML5 con sus imágenes: se descomprime en su carpeta, que debe tener un index.html. */
    private fun descomprimir(zip: File, nombre: String): File {
        val dest = File(carpeta(), nombre).apply { deleteRecursively(); mkdirs() }
        val raiz = dest.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val f = File(dest, e.name)
                if (!f.canonicalPath.startsWith(raiz)) continue          // nada fuera de su carpeta
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
            }
        }
        // Si todo venía dentro de una sola carpeta, se sube un nivel para que index.html quede arriba.
        val hijos = dest.listFiles().orEmpty()
        if (!File(dest, "index.html").exists() && hijos.size == 1 && File(hijos[0], "index.html").exists()) {
            hijos[0].listFiles()?.forEach { it.renameTo(File(dest, it.name)) }
            hijos[0].deleteRecursively()
        }
        if (!File(dest, "index.html").exists()) { dest.deleteRecursively(); throw IllegalArgumentException("El .zip no lleva index.html") }
        return dest
    }

    private fun lista(): String = carpeta().listFiles().orEmpty()
        .filter { !it.name.startsWith(".") }
        .sortedBy { it.name.lowercase() }
        .joinToString(",", "[", "]") { f ->
            val tam = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()
            "{\"nombre\":\"${json(f.name)}\",\"tam\":$tam}"
        }

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

    private fun responder(sal: OutputStream, codigo: Int, tipo: String, cuerpo: String, extra: String? = null) {
        val b = cuerpo.toByteArray()
        val cab = buildString {
            append("HTTP/1.1 $codigo X\r\nContent-Type: $tipo\r\nContent-Length: ${b.size}\r\nConnection: close\r\nCache-Control: no-store\r\n")
            if (extra != null) append(extra).append("\r\n")
            append("\r\n")
        }
        try { sal.write(cab.toByteArray()); sal.write(b); sal.flush() } catch (_: Exception) {}
    }

    companion object {
        const val PUERTO = 8765
        private const val MAXIMO = 1024L * 1024 * 1024
        private val ADMITIDOS = setOf("webp", "jpg", "jpeg", "png", "gif", "avif", "mp4", "webm", "mkv", "mov", "html", "htm", "zip")

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
<title>Fondos para Stark TV</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; font: 16px/1.45 system-ui, sans-serif; background: #0d1018; color: #e8ecf5; }
  main { max-width: 560px; margin: 0 auto; padding: 24px 16px 48px; }
  h1 { font-size: 22px; margin: 0 0 4px; }
  p { color: #a9b2c6; margin: 0 0 18px; }
  label.subir { display: block; border: 2px dashed #46506a; border-radius: 14px; padding: 28px 16px; text-align: center; cursor: pointer; }
  label.subir.encima { border-color: #f2a93b; background: #f2a93b14; }
  label.subir b { display: block; font-size: 18px; margin-bottom: 4px; }
  input[type=file] { display: none; }
  .barra { height: 8px; background: #252b3b; border-radius: 4px; overflow: hidden; margin: 6px 0 2px; }
  .barra i { display: block; height: 100%; width: 0; background: #f2a93b; }
  .estado { font-size: 14px; color: #a9b2c6; min-height: 20px; }
  h2 { font-size: 16px; margin: 28px 0 8px; }
  ul { list-style: none; padding: 0; margin: 0; }
  li { display: flex; align-items: center; gap: 8px; padding: 10px 0; border-bottom: 1px solid #222838; }
  li span { flex: 1; overflow-wrap: anywhere; }
  li small { color: #7d869b; display: block; }
  button { font: inherit; border: 0; border-radius: 10px; padding: 8px 12px; cursor: pointer; }
  .poner { background: #f2a93b; color: #1b1203; }
  .borrar { background: #2a3044; color: #e8ecf5; }
</style></head><body><main>
<h1>Fondos para Stark TV</h1>
<p>Imágenes (webp, jpg, png, gif), vídeos (mp4, webm, mkv) o HTML5. Un HTML5 con imágenes propias, en un .zip con su index.html.</p>
<label class="subir" id="zona"><b>Elegir archivos</b>o arrastrarlos aquí
  <input type="file" id="archivos" multiple accept=".webp,.jpg,.jpeg,.png,.gif,.avif,.mp4,.webm,.mkv,.mov,.html,.htm,.zip"></label>
<div class="barra"><i id="progreso"></i></div>
<div class="estado" id="estado"></div>
<h2>En la TV</h2>
<ul id="lista"></ul>
</main>
<script>
const est = document.getElementById('estado'), prog = document.getElementById('progreso'), zona = document.getElementById('zona');
function mb(n) { return n > 1048576 ? (n / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(n / 1024)) + ' KB'; }
function pedir(metodo, url, cuerpo, alAvanzar) {
  return new Promise(function (ok, mal) {
    const x = new XMLHttpRequest(); x.open(metodo, url);
    if (alAvanzar) x.upload.onprogress = function (e) { if (e.lengthComputable) alAvanzar(e.loaded / e.total); };
    x.onload = function () { x.status === 200 ? ok(x.responseText) : mal(x.responseText || ('Error ' + x.status)); };
    x.onerror = function () { mal('Sin conexión con la TV'); };
    x.send(cuerpo);
  });
}
async function subir(archivos) {
  for (let i = 0; i < archivos.length; i++) {
    const f = archivos[i];
    est.textContent = 'Subiendo ' + f.name + ' (' + (i + 1) + ' de ' + archivos.length + ')…';
    try {
      await pedir('POST', 'subir?nombre=' + encodeURIComponent(f.name), f, function (p) { prog.style.width = (p * 100) + '%'; });
      est.textContent = 'Recibido: ' + f.name;
    } catch (e) { est.textContent = f.name + ': ' + e; }
    prog.style.width = '0';
    await cargar();
  }
}
async function cargar() {
  const l = JSON.parse(await pedir('GET', 'lista'));
  const ul = document.getElementById('lista'); ul.innerHTML = '';
  if (!l.length) { ul.innerHTML = '<li><span>Todavía no hay fondos enviados.</span></li>'; return; }
  for (const f of l) {
    const li = document.createElement('li'), s = document.createElement('span');
    s.textContent = f.nombre; const sm = document.createElement('small'); sm.textContent = mb(f.tam); s.appendChild(sm);
    const p = document.createElement('button'); p.className = 'poner'; p.textContent = 'Poner de fondo';
    p.onclick = async function () { await pedir('POST', 'poner?nombre=' + encodeURIComponent(f.nombre)); est.textContent = 'Puesto de fondo: ' + f.nombre; };
    const b = document.createElement('button'); b.className = 'borrar'; b.textContent = 'Borrar';
    b.onclick = async function () { if (confirm('¿Borrar ' + f.nombre + ' de la TV?')) { await pedir('POST', 'borrar?nombre=' + encodeURIComponent(f.nombre)); cargar(); } };
    li.append(s, p, b); ul.appendChild(li);
  }
}
document.getElementById('archivos').onchange = function (e) { subir(e.target.files); e.target.value = ''; };
zona.ondragover = function (e) { e.preventDefault(); zona.classList.add('encima'); };
zona.ondragleave = function () { zona.classList.remove('encima'); };
zona.ondrop = function (e) { e.preventDefault(); zona.classList.remove('encima'); subir(e.dataTransfer.files); };
cargar().catch(function (e) { est.textContent = e; });
</script></body></html>"""
    }
}
