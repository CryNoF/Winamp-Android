package cl.winampyt

import org.json.JSONObject
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/** Hace lo que hacía yt-dlp en la versión de Windows: buscar, listar y bajar el audio. */
object YtEngine {

    class Item(val id: String, val dur: Long, val ch: String, val title: String) {
        fun toJson(): JSONObject =
            JSONObject().put("id", id).put("dur", dur).put("ch", ch).put("title", title)
    }

    private val svc get() = ServiceList.YouTube
    private var inited = false
    private val locks = ConcurrentHashMap<String, Any>()
    private val okExt = setOf("m4a", "webm", "mp4", "opus", "ogg", "mp3")
    private const val CHUNK = 1L shl 20 // 1 MiB por petición
    private const val CACHE_MAX = 400L * 1024 * 1024

    @Synchronized
    private fun init() {
        if (!inited) {
            NewPipe.init(NpDownloader.instance)
            inited = true
        }
    }

    private val videoRe = Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/embed/|/live/)([\w-]{11})""")
    private val listRe = Regex("""[?&]list=([\w-]+)""")
    fun videoId(url: String): String? = videoRe.find(url)?.groupValues?.get(1)
    fun listId(url: String): String? = listRe.find(url)?.groupValues?.get(1)

    private fun toItem(s: StreamInfoItem): Item? {
        val id = videoId(s.url ?: return null) ?: return null
        return Item(id, maxOf(0L, s.duration), s.uploaderName ?: "", s.name ?: id)
    }

    fun friendly(e: Throwable): String = when {
        e is UnknownHostException -> "Sin conexión a internet"
        else -> (e.message ?: e.javaClass.simpleName).lineSequence().firstOrNull()?.take(160) ?: "error"
    }

    // ---------------- Búsqueda ----------------
    fun search(term: String): List<Item> {
        init()
        val qh = svc.searchQHFactory.fromQuery(term, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        val info = SearchInfo.getInfo(svc, qh)
        val out = ArrayList<Item>()
        fun take(list: List<InfoItem>) {
            for (it in list) if (it is StreamInfoItem && out.size < 20) toItem(it)?.let { i -> out.add(i) }
        }
        take(info.relatedItems)
        var page = info.nextPage
        var tries = 0
        while (out.size < 20 && page != null && tries < 2) {
            val more = SearchInfo.getMoreItems(svc, qh, page)
            take(more.items)
            page = more.nextPage
            tries++
        }
        return out
    }

    fun searchLists(term: String): List<Item> {
        init()
        val qh = svc.searchQHFactory.fromQuery(term, listOf(YoutubeSearchQueryHandlerFactory.PLAYLISTS), "")
        val info = SearchInfo.getInfo(svc, qh)
        val out = ArrayList<Item>()
        for (it in info.relatedItems) {
            if (it is PlaylistInfoItem && out.size < 12) {
                val lid = listId(it.url ?: continue) ?: continue
                out.add(Item(lid, 0, it.uploaderName ?: "", it.name ?: lid))
            }
        }
        return out
    }

    // ---------------- Videos y listas por enlace ----------------
    fun fromUrl(url: String): List<Item> {
        init()
        val lid = listId(url)
        if (lid != null && !lid.startsWith("RD")) {
            val purl = "https://www.youtube.com/playlist?list=$lid"
            val info = PlaylistInfo.getInfo(svc, purl)
            val out = ArrayList<Item>()
            info.relatedItems.forEach { s -> toItem(s)?.let { out.add(it) } }
            var page = info.nextPage
            while (page != null && out.size < 500) {
                val more = PlaylistInfo.getMoreItems(svc, purl, page)
                more.items.forEach { s -> toItem(s)?.let { out.add(it) } }
                page = more.nextPage
            }
            return out
        }
        val vid = videoId(url) ?: throw IOException("Enlace no válido")
        val si = StreamInfo.getInfo(svc, "https://www.youtube.com/watch?v=$vid")
        return listOf(Item(vid, maxOf(0L, si.duration), si.uploaderName ?: "", si.name ?: vid))
    }

    // ---------------- Audio ----------------
    fun findAudio(id: String, cacheDir: File): File? =
        cacheDir.listFiles()?.firstOrNull { it.isFile && it.nameWithoutExtension == id && it.extension in okExt }

    /** Baja el audio (si no está ya en caché) y devuelve el archivo. */
    fun prep(id: String, cacheDir: File): File {
        val lock = locks.getOrPut(id) { Any() }
        return synchronized(lock) {
            findAudio(id, cacheDir)?.let { it.setLastModified(System.currentTimeMillis()); return@synchronized it }
            init()
            val info = StreamInfo.getInfo(svc, "https://www.youtube.com/watch?v=$id")
            val streams = info.audioStreams.filter { it.isUrl && !it.content.isNullOrBlank() }
            if (streams.isEmpty()) throw IOException("Este video no tiene audio disponible")
            val best = streams.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
                ?: streams.maxByOrNull { it.averageBitrate }!!
            var ext = best.format?.suffix ?: "m4a"
            if (ext !in okExt) ext = "m4a"
            cacheDir.mkdirs()
            trimCache(cacheDir)
            val dest = File(cacheDir, "$id.$ext")
            download(best.content, dest)
            dest
        }
    }

    private fun download(url: String, dest: File) {
        val part = File(dest.path + ".part")
        val client = NpDownloader.instance.client
        try {
            var pos = 0L
            var total = -1L
            FileOutputStream(part).use { out ->
                while (total < 0 || pos < total) {
                    val req = okhttp3.Request.Builder().url(url)
                        .header("User-Agent", NpDownloader.UA)
                        .header("Range", "bytes=$pos-${pos + CHUNK - 1}")
                        .build()
                    client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) throw IOException("YouTube respondió HTTP ${r.code}")
                        val body = r.body ?: throw IOException("Respuesta vacía")
                        val cr = r.header("Content-Range")
                        if (r.code == 206 && cr != null) {
                            total = cr.substringAfter('/').toLongOrNull() ?: -1L
                            val n = body.byteStream().copyTo(out)
                            if (n == 0L) throw IOException("Descarga interrumpida")
                            pos += n
                            if (total < 0) total = pos
                        } else {
                            if (pos != 0L) throw IOException("El servidor ignoró el rango")
                            pos += body.byteStream().copyTo(out)
                            total = pos
                        }
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw IOException("No se pudo guardar el audio")
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    private fun trimCache(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        var sum = 0L
        for (f in files) {
            sum += f.length()
            if (sum > CACHE_MAX) f.delete()
        }
    }
}
