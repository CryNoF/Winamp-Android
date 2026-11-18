package cl.winampyt

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/** Conecta NewPipeExtractor con OkHttp. */
class NpDownloader private constructor(val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val url = request.url()
        val data = request.dataToSend()
        val body = when {
            data != null -> data.toRequestBody()
            method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody()
            else -> null
        }
        val b = okhttp3.Request.Builder().method(method, body).url(url).header("User-Agent", UA)
        for ((k, values) in request.headers()) {
            b.removeHeader(k)
            for (v in values) b.addHeader(k, v)
        }
        client.newCall(b.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("YouTube pide verificación (reCAPTCHA)", url)
            return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }

    companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
        val instance: NpDownloader by lazy {
            NpDownloader(
                OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(40, TimeUnit.SECONDS)
                    .build()
            )
        }
    }
}
