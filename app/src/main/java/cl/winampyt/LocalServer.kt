package cl.winampyt

import android.content.Context
import android.webkit.WebView
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.lang.ref.WeakReference
import java.util.UUID

/** Referencia al WebView para que el servicio pueda mandarle teclas (anterior/siguiente/pausa). */
object Bridge {
    var web: WeakReference<WebView>? = null
}

/** Arranca una sola vez el servidor interno, igual que hacía servidor.ps1 en Windows. */
object ServerHolder {
    const val PORT = 18765
    val token: String = UUID.randomUUID().toString().replace("-", "")
    private var server: LocalServer? = null

    @Synchronized
    fun start(ctx: Context): LocalServer {
        server?.let { if (it.isAlive) return it }
        val app = ctx.applicationContext
        val s = try {
            LocalServer(app, PORT).also { it.start(SOCKET_READ_TIMEOUT, false) }
        } catch (e: Exception) {
            LocalServer(app, 0).also { it.start(SOCKET_READ_TIMEOUT, false) }
        }
        server = s
        return s
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    private const val SOCKET_READ_TIMEOUT = 5000
}

class LocalServer(private val ctx: Context, port: Int) : NanoHTTPD("127.0.0.1", port) {

    private val cacheDir = File(ctx.cacheDir, "audio").apply { mkdirs() }
    private val plsDir = File(ctx.filesDir, "playlists").apply { mkdirs() }
    private val stateFile = File(ctx.filesDir, "estado.json")
    private val mp3 = Mp3Queue(ctx, cacheDir)

    companion object {
        fun safeName(n: String): String =
            n.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "").trim().trimEnd('.').take(60).trim()
    }

    private fun json(o: JSONObject, status: Response.IStatus = Response.Status.OK): Response {
        val r = newFixedLengthResponse(status, "application/json; charset=utf-8", o.toString())
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun raw(text: String): Response {
        val r = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", text)
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun fail(msg: String) = json(JSONObject().put("ok", false).put("error", msg))
    private fun ok() = json(JSONObject().put("ok", true))
    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
    private fun forbidden() = newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "")

    private fun items(list: List<YtEngine.Item>, err: String): Response {
        if (list.isEmpty()) return fail(err)
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return json(JSONObject().put("ok", true).put("items", arr))
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val q = session.parms ?: emptyMap<String, String>()

        // Solo esta app puede hablar con el servidor (otras apps del teléfono no tienen la cookie).
        val cookie = session.headers["cookie"] ?: ""
        val authed = cookie.split(';').any { it.trim() == "wy=${ServerHolder.token}" }
        if (!authed) {
            if ((uri == "/" || uri == "/index.html") && q["k"] == ServerHolder.token) {
                val r = html()
                r.addHeader("Set-Cookie", "wy=${ServerHolder.token}; Path=/; HttpOnly; SameSite=Strict")
                return r
            }
            return forbidden()
        }

        val files = HashMap<String, String>()
        if (session.method == Method.POST || session.method == Method.PUT) {
            try { session.parseBody(files) } catch (e: Exception) { return fail("datos no válidos") }
        }
        val body = files["postData"] ?: ""

        return try {
            when {
                uri == "/" || uri == "/index.html" || uri == "/WinampYT.html" -> html()
                uri == "/api/ping" -> ok()
                uri == "/api/show" -> json(JSONObject().put("ok", true).put("host", false))

                uri == "/api/search" -> {
                    val t = (q["q"] ?: "").replace(Regex("[\"\\r\\n]"), " ").trim()
                    if (t.isEmpty()) fail("Búsqueda vacía") else items(YtEngine.search(t), "Sin resultados")
                }
                uri == "/api/searchlists" -> {
                    val t = (q["q"] ?: "").replace(Regex("[\"\\r\\n]"), " ").trim()
                    if (t.isEmpty()) fail("Búsqueda vacía") else items(YtEngine.searchLists(t), "Sin listas")
                }
                uri == "/api/list" -> {
                    val u = q["url"] ?: ""
                    if (!Regex("^https://[^\\s\"]+$").matches(u)) fail("Enlace no válido")
                    else items(YtEngine.fromUrl(u), "No se encontraron canciones")
                }
                uri == "/api/prep" -> {
                    val id = q["id"] ?: ""
                    if (!Regex("^[\\w-]{11}$").matches(id)) fail("id no válido")
                    else {
                        val f = YtEngine.prep(id, cacheDir)
                        json(JSONObject().put("ok", true).put("url", "/audio/${f.name}").put("size", f.length()))
                    }
                }
                uri.startsWith("/audio/") -> audio(session, uri.substring(7))

                uri == "/api/state" ->
                    if (session.method == Method.POST) {
                        if (body.startsWith("{")) { stateFile.writeText(body); ok() } else fail("datos no válidos")
                    } else raw(if (stateFile.exists()) stateFile.readText() else "{}")

                uri == "/api/pls" -> {
                    val names = (plsDir.listFiles { f -> f.isFile && f.extension == "json" } ?: emptyArray())
                        .sortedByDescending { it.lastModified() }.map { it.nameWithoutExtension }
                    json(JSONObject().put("ok", true).put("names", JSONArray(names)))
                }
                uri.startsWith("/api/pls/") -> {
                    val nm = safeName(q["name"] ?: "")
                    if (nm.isEmpty()) fail("Nombre no válido")
                    else {
                        val file = File(plsDir, "$nm.json")
                        when (uri) {
                            "/api/pls/get" -> if (file.exists()) raw(file.readText()) else fail("No existe esa lista")
                            "/api/pls/save" ->
                                if (session.method == Method.POST && body.startsWith("{")) {
                                    file.writeText(body)
                                    json(JSONObject().put("ok", true).put("name", nm))
                                } else fail("datos no válidos")
                            "/api/pls/del" -> { file.delete(); ok() }
                            else -> notFound()
                        }
                    }
                }

                uri == "/api/mp3" -> {
                    val id = q["id"] ?: ""
                    if (!Regex("^[\\w-]{11}$").matches(id)) fail("id no válido")
                    else {
                        val n = mp3.add(Mp3Queue.Job(id, q["title"] ?: "", q["sub"] ?: ""))
                        json(JSONObject().put("ok", true).put("pending", n))
                    }
                }
                uri == "/api/mp3/status" -> json(
                    JSONObject().put("ok", true).put("pending", mp3.pending).put("current", mp3.current)
                        .put("done", mp3.done).put("failed", mp3.failed).put("lastError", mp3.lastError)
                        .put("dir", "Música/WinampYT")
                )
                uri == "/api/mp3/open" -> json(JSONObject().put("ok", true).put("dir", "Música/WinampYT (abre tu app de Archivos o de Música)"))

                else -> notFound()
            }
        } catch (e: Exception) {
            fail(YtEngine.friendly(e))
        }
    }

    private fun html(): Response {
        val bytes = ctx.assets.open("WinampYT.html").use { it.readBytes() }
        val r = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", ByteArrayInputStream(bytes), bytes.size.toLong())
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun audio(session: IHTTPSession, name: String): Response {
        val m = Regex("^([\\w-]{11})\\.(m4a|webm|mp4|opus|ogg|mp3)$").matchEntire(name) ?: return notFound()
        val f = File(cacheDir, name)
        if (!f.exists()) return notFound()
        val mime = when (m.groupValues[2]) {
            "m4a", "mp4" -> "audio/mp4"
            "webm" -> "audio/webm"
            "opus", "ogg" -> "audio/ogg"
            else -> "audio/mpeg"
        }
        val len = f.length()
        var start = 0L
        var end = len - 1
        var partial = false
        val range = session.headers["range"]
        if (range != null) {
            val mm = Regex("bytes=(\\d*)-(\\d*)").find(range)
            if (mm != null) {
                partial = true
                val a = mm.groupValues[1]
                val b = mm.groupValues[2]
                if (a.isNotEmpty()) {
                    start = a.toLong()
                    if (b.isNotEmpty()) end = minOf(b.toLong(), len - 1)
                } else if (b.isNotEmpty()) start = maxOf(0L, len - b.toLong())
            }
        }
        if (start >= len || start > end) {
            val r = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
            r.addHeader("Content-Range", "bytes */$len")
            return r
        }
        val count = end - start + 1
        val raf = RandomAccessFile(f, "r")
        raf.seek(start)
        val stream = object : InputStream() {
            var left = count
            override fun read(): Int {
                if (left <= 0) return -1
                val b = raf.read()
                if (b >= 0) left--
                return b
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = raf.read(b, off, minOf(len.toLong(), left).toInt())
                if (n > 0) left -= n
                return n
            }
            override fun close() { raf.close() }
        }
        val status = if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val r = newFixedLengthResponse(status, mime, stream, count)
        r.addHeader("Accept-Ranges", "bytes")
        if (partial) r.addHeader("Content-Range", "bytes $start-$end/$len")
        r.addHeader("Cache-Control", "no-store")
        return r
    }
}
