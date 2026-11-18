package cl.winampyt

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/**
 * "Guardar MP3": en Android se guarda el audio tal como viene de YouTube (M4A/AAC,
 * misma calidad, suena en cualquier reproductor) en la carpeta Música/WinampYT.
 */
class Mp3Queue(private val ctx: Context, private val cacheDir: File) {
    class Job(val id: String, val title: String, val sub: String)

    private val q = LinkedBlockingQueue<Job>()
    private val ex = Executors.newSingleThreadExecutor()

    @Volatile var current = ""
    @Volatile var done = 0
    @Volatile var failed = 0
    @Volatile var lastError = ""
    val pending: Int get() = q.size

    fun add(j: Job): Int {
        if (q.none { it.id == j.id && it.sub == j.sub }) {
            q.add(j)
            ex.execute { runOne() }
        }
        return q.size
    }

    private fun runOne() {
        val j = synchronized(this) { q.poll()?.also { current = it.title.ifBlank { it.id } } } ?: return
        try {
            val f = YtEngine.prep(j.id, cacheDir)
            save(f, j)
            done++
        } catch (e: Exception) {
            failed++
            lastError = YtEngine.friendly(e)
        } finally {
            current = ""
        }
    }

    private fun save(f: File, j: Job) {
        val ext = f.extension
        val mime = when (ext) {
            "m4a", "mp4" -> "audio/mp4"
            "webm" -> "audio/webm"
            "opus", "ogg" -> "audio/ogg"
            else -> "audio/mpeg"
        }
        val base = LocalServer.safeName(j.title).ifBlank { j.id }
        val sub = LocalServer.safeName(j.sub)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$base.$ext")
            put(MediaStore.Audio.Media.TITLE, j.title)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/WinampYT" + if (sub.isNotEmpty()) "/$sub" else "")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException("No se pudo crear el archivo en Música")
        try {
            resolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
